/** AGT-107：排队、插话降级（stop → resume → append）、停止、结果未知。 */
import test from "node:test";
import assert from "node:assert/strict";

import { SessionLifecycle } from "../../adapters/lifecycle.mjs";
import { MockAdapter } from "../../adapters/mock.mjs";

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function setup({ instanceId = "mock_lc", ...options } = {}) {
  const adapter = new MockAdapter({ instanceId, ...options });
  await adapter.probe();
  await adapter.createSession({ sessionId: "ses_lc", providerSessionId: "prov_lc_1" });
  const lifecycle = new SessionLifecycle({ adapter, sessionId: "ses_lc" });
  return { adapter, lifecycle };
}

test("排队：FIFO、按 messageId 去重、不丢消息", async () => {
  const { adapter, lifecycle } = await setup({ stepDelayMs: 5, script: { chunks: ["a", "b", "c"] } });
  const turnPromise = lifecycle.runTurn({ messageId: "m1", text: "first" });
  const first = lifecycle.enqueue({ messageId: "m2", text: "second" });
  const duplicate = lifecycle.enqueue({ messageId: "m2", text: "second" });
  lifecycle.enqueue({ messageId: "m3", text: "third" });

  assert.equal(first.duplicate, false);
  assert.equal(first.position, 1);
  assert.equal(duplicate.duplicate, true);
  assert.deepEqual(lifecycle.queuedIds(), ["m2", "m3"]);

  const result = await turnPromise;
  assert.equal(result.state, "completed");
  assert.equal(lifecycle.dequeue().messageId, "m2");
  assert.equal(lifecycle.dequeue().messageId, "m3");
  assert.equal(lifecycle.hasQueued(), false);

  const sendCommand = lifecycle.commands.find((c) => c.kind === "turn.send");
  assert.equal(sendCommand.state, "succeeded");
  await adapter.dispose();
});

test("停止：状态来自上游结算，trust=upstream-confirmed", async () => {
  const { adapter, lifecycle } = await setup({ stepDelayMs: 20, script: { chunks: ["x", "y", "z"] } });
  const turnPromise = lifecycle.runTurn({ messageId: "m1", text: "go" });
  await sleep(5);
  const record = await lifecycle.stop({ reason: "user" });
  assert.equal(record.state, "stopped");
  assert.equal(record.trust, "upstream-confirmed");
  assert.equal(record.stopReason, "cancelled");
  const result = await turnPromise;
  assert.equal(result.state, "stopped");
  assert.ok(lifecycle.commands.some((c) => c.kind === "turn.stop" && c.state === "succeeded"));
  await adapter.dispose();
});

test("无运行中回合时 stop 是幂等的 alreadyIdle", async () => {
  const { adapter, lifecycle } = await setup();
  const record = await lifecycle.stop();
  assert.equal(record.alreadyIdle, true);
  await adapter.dispose();
});

test("插话降级：stop → resume → append，并记录原会话/原回合/新消息", async () => {
  const { adapter, lifecycle } = await setup({ stepDelayMs: 10, script: { chunks: ["a", "b", "c"] } });
  const firstTurn = lifecycle.runTurn({ messageId: "m1", text: "first" });
  await sleep(5);

  const steer = await lifecycle.steer({ messageId: "m2", text: "insert now" });
  assert.equal(steer.strategy, "stop-resume-append");
  assert.equal(steer.ok, true);
  assert.equal(steer.createdNewSession, false);
  assert.deepEqual(
    steer.steps.map((s) => s.step),
    ["stop", "resume", "append"],
  );
  assert.equal(steer.originalProviderSessionId, "prov_lc_1");
  assert.equal(steer.resumedProviderSessionId, "prov_lc_1");
  assert.equal(steer.newMessageId, "m2");
  assert.equal(steer.stopReason, "cancelled");
  assert.ok(steer.preservedOutput >= 1, "partial output must be recorded");

  const first = await firstTurn;
  assert.equal(first.state, "stopped");
  assert.equal(steer.result.state, "completed");

  const kinds = lifecycle.commands.map((c) => c.kind);
  assert.ok(kinds.includes("turn.stop"));
  assert.ok(kinds.includes("session.resume"));
  assert.equal(lifecycle.commands.filter((c) => c.kind === "turn.send").length, 2);
  assert.equal(lifecycle.snapshot().steerRecords.length, 1);
  await adapter.dispose();
});

test("原生插话可用时优先 native-steer，不降级", async () => {
  const { adapter, lifecycle } = await setup({ supportsSteer: true, stepDelayMs: 10, script: { chunks: ["a", "b"] } });
  const turnPromise = lifecycle.runTurn({ messageId: "m1", text: "first" });
  await sleep(5);
  const result = await lifecycle.steer({ messageId: "m2", text: "insert" });
  assert.equal(result.strategy, "native-steer");
  assert.equal(result.ok, true);
  assert.equal(lifecycle.steerRecords.length, 0);
  await turnPromise;
  assert.ok(lifecycle.commands.some((c) => c.kind === "turn.steer" && c.state === "succeeded"));
  await adapter.dispose();
});

test("恢复失败：抛 RESUME_FAILED，不新建空白会话顶替", async () => {
  const { adapter, lifecycle } = await setup({ stepDelayMs: 10, resumeFails: true, script: { chunks: ["a", "b"] } });
  const firstTurn = lifecycle.runTurn({ messageId: "m1", text: "first" });
  await sleep(5);
  await assert.rejects(
    lifecycle.steer({ messageId: "m2", text: "insert" }),
    (err) => err.code === "RESUME_FAILED" && /refusing to create a blank session/.test(err.message),
  );
  const first = await firstTurn;
  assert.equal(first.state, "stopped");
  assert.equal(adapter.listSessions().length, 1, "no substitute session may be created");
  const record = lifecycle.steerRecords.at(-1);
  assert.equal(record.ok, false);
  assert.equal(record.createdNewSession, false);
  assert.deepEqual(record.steps.at(-1), { step: "resume", ok: false, code: "PROTOCOL_ERROR" });
  await adapter.dispose();
});

test("结果未知：markResultUnknown 不改写成成功或失败", async () => {
  const { adapter, lifecycle } = await setup({ stepDelayMs: 10, script: { chunks: ["a", "b", "c"] } });
  const turnPromise = lifecycle.runTurn({ messageId: "m1", text: "go" });
  await sleep(5);
  const unknown = lifecycle.markResultUnknown({ reason: "process-crashed" });
  assert.equal(unknown.state, "unknown");
  assert.equal(unknown.changed, true);
  assert.equal(lifecycle.snapshot().turn.state, "unknown");
  await turnPromise;
  await adapter.dispose();
});

test("队列满时显式报错，不静默丢弃", async () => {
  const { adapter, lifecycle } = await setup({ stepDelayMs: 20, script: { chunks: ["a", "b", "c", "d"] } });
  lifecycle.maxQueue = 1;
  const turnPromise = lifecycle.runTurn({ messageId: "m1", text: "first" });
  lifecycle.enqueue({ messageId: "m2", text: "second" });
  assert.throws(
    () => lifecycle.enqueue({ messageId: "m3", text: "third" }),
    (err) => err.code === "TURN_IN_PROGRESS",
  );
  await turnPromise;
  await adapter.dispose();
});
