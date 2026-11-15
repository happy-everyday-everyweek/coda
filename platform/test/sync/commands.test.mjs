/** SYNC-006 / SYNC-008：命令投递、幂等收据、离线队列、过期与取代。 */
import test from "node:test";
import assert from "node:assert/strict";

import { createCommand } from "../../contracts/commands.mjs";
import { createCommandQueue } from "../../sync/commands-queue.mjs";
import { createMemoryStorage } from "../../sync/storage.mjs";

function setup(deviceId = "dev_target") {
  const storage = createMemoryStorage();
  const queue = createCommandQueue({ storage, deviceId });
  return { storage, queue };
}

test("命令入队：accepted + 收据；目标离线为 waiting_target，绝不显示成功", () => {
  const { queue } = setup();
  const command = createCommand({ kind: "turn.stop", target: { deviceId: "dev_target" } });
  const record = queue.enqueue({ command, targetDeviceId: "dev_target" });
  assert.equal(record.state, "accepted");
  assert.equal(record.delivery, "queued");
  assert.equal(record.receipt.state, "accepted");
  assert.match(record.receipt.receiptId, /^rcpt_[0-9a-f]{24}$/);

  queue.markWaiting("dev_target");
  const waiting = queue.get(command.id);
  assert.equal(waiting.state, "accepted");
  assert.equal(waiting.delivery, "waiting_target");
  assert.notEqual(waiting.state, "succeeded");
  assert.equal(waiting.receipt.state, "accepted");
});

test("同一 commandId 重复投递：目标只认领一次，重复返回同一收据", () => {
  const source = setup("dev_source");
  const target = setup("dev_target");
  const command = createCommand({ kind: "turn.send", target: { deviceId: "dev_target" }, payload: { text: "hi" } });
  source.queue.enqueue({ command, targetDeviceId: "dev_target" });

  const first = target.queue.receive(command);
  assert.equal(first.ok, true);
  assert.equal(first.duplicate, false);
  assert.equal(first.record.state, "accepted");

  const second = target.queue.receive(command);
  assert.equal(second.duplicate, true);
  assert.equal(second.receipt.receiptId, first.receipt.receiptId);
  assert.equal(second.receipt.at, first.receipt.at);

  const claimed = target.queue.begin(command.id);
  assert.equal(claimed.ok, true);
  assert.equal(claimed.record.state, "dispatched");
  const secondClaim = target.queue.begin(command.id);
  assert.equal(secondClaim.ok, false);
  assert.equal(secondClaim.reason, "ALREADY_STARTED");

  const completed = target.queue.complete(command.id, { state: "succeeded", result: { output: "ok" } });
  assert.equal(completed.ok, true);
  const terminalReceipt = completed.receipt;
  assert.equal(terminalReceipt.state, "succeeded");

  const third = target.queue.receive(command);
  assert.equal(third.duplicate, true);
  assert.deepEqual(third.receipt, terminalReceipt, "重复投递必须返回同一终态收据");
  assert.equal(target.queue.complete(command.id, { state: "succeeded" }).duplicate, true);
});

test("过期：未执行命令到 TTL 后变 expired，收据不伪造成功", () => {
  const { queue } = setup();
  const command = createCommand({ kind: "interaction.answer", target: { deviceId: "dev_target" }, ttlMs: -1 });
  queue.enqueue({ command, targetDeviceId: "dev_target" });
  const expired = queue.expireDue({ at: Date.now() + 10 });
  assert.equal(expired.length, 1);
  assert.equal(expired[0].state, "expired");
  assert.equal(expired[0].receipt.state, "expired");
  assert.deepEqual(queue.pendingFor("dev_target"), []);
  assert.equal(queue.complete(command.id, { state: "succeeded" }).ok, false);
});

test("取代：未开始可取代；已 dispatch 有副作用风险，拒绝取代", () => {
  const { queue } = setup();
  const first = createCommand({ kind: "turn.send", target: { deviceId: "dev_target" }, payload: { text: "old" } });
  queue.enqueue({ command: first, targetDeviceId: "dev_target" });
  const superseded = queue.supersede(first.id, { by: "cmd_new" });
  assert.equal(superseded.ok, true);
  assert.equal(superseded.record.state, "superseded");
  assert.equal(superseded.record.supersededBy, "cmd_new");

  const second = createCommand({ kind: "turn.send", target: { deviceId: "dev_target" }, payload: { text: "running" } });
  queue.enqueue({ command: second, targetDeviceId: "dev_target" });
  queue.receive(second);
  queue.begin(second.id);
  const blocked = queue.supersede(second.id, { by: "cmd_stop" });
  assert.equal(blocked.ok, false);
  assert.equal(blocked.reason, "ALREADY_STARTED");
});

test("收据应用：unknown 可经远端收据核对收敛；不同终态不被静默覆盖", () => {
  const { queue } = setup("dev_source");
  const command = createCommand({ kind: "turn.stop", target: { deviceId: "dev_target" } });
  queue.enqueue({ command, targetDeviceId: "dev_target" });
  queue.markSent(command.id);
  const receipt = queue.get(command.id).receipt;

  const unknown = queue.complete(command.id, { state: "unknown", result: { reason: "core_crash" } });
  assert.equal(unknown.ok, true);
  assert.equal(unknown.record.state, "unknown");

  const reconciled = queue.acknowledge(command.id, { ...receipt, state: "succeeded", at: new Date().toISOString() });
  assert.equal(reconciled.ok, true);
  assert.equal(reconciled.reconciled, true);
  assert.equal(queue.get(command.id).state, "succeeded");

  const conflicting = queue.acknowledge(command.id, { ...receipt, state: "failed", at: new Date().toISOString() });
  assert.equal(conflicting.ok, false);
  assert.equal(conflicting.reason, "RECEIPT_CONFLICT");
  assert.equal(queue.get(command.id).state, "succeeded");
  assert.match(queue.get(command.id).lastError, /receipt_conflict/);
});

test("未知命令收据不会凭空创建记录", () => {
  const { queue } = setup("dev_source");
  const result = queue.acknowledge("cmd_missing", {
    receiptId: "r",
    commandId: "cmd_missing",
    state: "succeeded",
    at: new Date().toISOString(),
  });
  assert.equal(result.ok, false);
  assert.equal(result.reason, "UNKNOWN_COMMAND");
  assert.equal(queue.stats().total, 0);
});
