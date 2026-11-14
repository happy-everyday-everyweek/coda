/** SYNC-108 / SYNC-005 / SYNC-013：分区、重复、乱序、分叉、重连后的收敛。 */
import test from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";

import { advanceCursor, contentHash, createSyncCursor } from "../../sync/cursors.mjs";
import { createInteractionsSync } from "../../sync/interactions-sync.mjs";
import { createMemoryStorage } from "../../sync/storage.mjs";
import { createMemoryTransportPair } from "../../sync/transport.mjs";
import { waitFor, connectPair, createFileNode, disconnectPair, makeTmpDir, pairNodes } from "./helpers.mjs";

test("首次连接：快照 + 增量；在线订阅与事件窗口；非所属节点不得追加", async (t) => {
  const dir = makeTmpDir(t);
  const a = createFileNode(t, { dir: join(dir, "a"), name: "A" });
  const b = createFileNode(t, { dir: join(dir, "b"), name: "B" });
  pairNodes(a, b);

  const session = a.service.appendSession({ title: "build the thing" });
  a.service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "one" } });
  a.service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "two" } });

  const eventsAtB = [];
  b.service.subscribe((event) => {
    if (event.type === "event") eventsAtB.push(event);
  });

  await connectPair(a, b);
  await waitFor(() => b.service.dump().events[session.id]?.length === 2, { label: "initial snapshot/delta" });
  const sessionsAtB = b.service.dump().sessions;
  assert.equal(sessionsAtB.find((s) => s.id === session.id).title, "build the thing");
  assert.equal(sessionsAtB.find((s) => s.id === session.id).originDeviceId, a.identity.deviceId);

  a.service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "three" } });
  await waitFor(() => b.service.dump().events[session.id].length === 3, { label: "live delta" });
  assert.ok(eventsAtB.some((e) => e.event.payload?.text === "three"));

  assert.throws(
    () => b.service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "hijack" } }),
    /only the origin device/,
  );
});

test("分区：双方继续本地工作，重连后收敛且重复重连不产生重复", async (t) => {
  const dir = makeTmpDir(t);
  const paId = "pa_sync_shared";
  const a = createFileNode(t, { dir: join(dir, "a"), name: "A", paId });
  const b = createFileNode(t, { dir: join(dir, "b"), name: "B", paId });
  pairNodes(a, b);
  const session = a.service.appendSession({ title: "long task" });
  a.service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "before" } });
  await connectPair(a, b);
  await waitFor(() => b.service.dump().events[session.id]?.length === 1);

  await disconnectPair(a, b);
  await waitFor(() => !a.service.isPeerOnline(b.identity.deviceId) && !b.service.isPeerOnline(a.identity.deviceId), {
    label: "partitioned",
  });

  a.service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "during-a1" } });
  a.service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "during-a2" } });
  const paA = a.service.appendPaMessage({ text: "note from A" });
  const paB = b.service.appendPaMessage({ text: "note from B" });

  await connectPair(a, b);
  await waitFor(() => b.service.dump().events[session.id]?.length === 3, { label: "catch-up events" });
  await waitFor(() => a.service.dump().paMessages.length === 2 && b.service.dump().paMessages.length === 2, {
    label: "PA convergence",
  });
  assert.deepEqual(
    a.service.dump().paMessages.map((m) => m.content),
    b.service.dump().paMessages.map((m) => m.content),
  );
  assert.ok(a.service.dump().paMessages.some((m) => m.id === paB.id));
  assert.ok(b.service.dump().paMessages.some((m) => m.id === paA.id));

  await disconnectPair(a, b);
  await connectPair(a, b);
  await new Promise((resolve) => setTimeout(resolve, 100));
  assert.equal(a.service.dump().events[session.id].length, 3);
  assert.equal(b.service.dump().events[session.id].length, 3);
  assert.equal(new Set(b.service.dump().events[session.id].map((e) => e.id)).size, 3);
  assert.equal(a.service.dump().paMessages.length, 2);
});

test("重复与分叉：同一事件重复幂等；同 seq 不同内容两端得到同一赢家", () => {
  const event = { id: "evt_same", sessionId: "ses_x", seq: 1, kind: "message", payload: { text: "v1" }, ts: "2026-01-01T00:00:00.000Z" };
  const one = createMemoryStorage();
  const first = one.appendEvent(event);
  const again = one.appendEvent(event);
  assert.equal(first.duplicate, false);
  assert.equal(again.duplicate, true);
  assert.equal(one.listEvents("ses_x").length, 1);

  const variant = { ...event, payload: { text: "v2" } };
  const left = createMemoryStorage();
  const right = createMemoryStorage();
  left.appendEvent(event);
  left.appendEvent(variant);
  right.appendEvent(variant);
  right.appendEvent(event);
  const winnerLeft = left.listEvents("ses_x")[0];
  const winnerRight = right.listEvents("ses_x")[0];
  assert.equal(winnerLeft.seq, 1);
  assert.deepEqual(winnerLeft, winnerRight);
  assert.equal(winnerLeft.conflict, true);
  assert.equal(contentHash(winnerLeft), contentHash(winnerRight));
});

test("乱序与缺口：游标拒绝跳号；乱序投递后经补洞收敛", async (t) => {
  const cursor = createSyncCursor({ peerId: "dev_peer" });
  const gap = advanceCursor(cursor, 3);
  assert.equal(gap.advanced, false);
  assert.equal(gap.gap, 2);
  assert.equal(advanceCursor(cursor, 1).advanced, true);
  assert.equal(advanceCursor(cursor, 2).advanced, true);
  assert.equal(advanceCursor(cursor, 3).advanced, true);
  assert.equal(cursor.remoteSeq, 3);

  const dir = makeTmpDir(t);
  const a = createFileNode(t, { dir: join(dir, "a"), name: "A" });
  const b = createFileNode(t, { dir: join(dir, "b"), name: "B" });
  pairNodes(a, b);
  const session = a.service.appendSession({ title: "reorder" });

  const { left, right } = createMemoryTransportPair();
  await Promise.all([a.service.attachTransport(left), b.service.attachTransport(right)]);
  await waitFor(() => b.service.dump().sessions.some((entry) => entry.id === session.id), { label: "peer linked" });
  await waitFor(() => b.service.isPeerOnline(a.identity.deviceId));

  const opSeqBefore = a.storage.opSeq;
  // 暂停 A→B 的投递（背压），本地照常写 op；不产生“丢帧”，因此不会破坏加密帧序号。
  assert.equal(a.service.pauseDelivery(b.identity.deviceId), true);
  a.service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "e1" } });
  a.service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "e2" } });
  a.service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "e3" } });
  const ops = a.storage.listOpsAfter(opSeqBefore);
  assert.equal(ops.length, 3);
  assert.equal(a.service.resumeDelivery(b.identity.deviceId), true);

  // 故意先投递第 3 条：接收端检测缺口，向对端请求补洞（快照），随后到达的 1/2 被去重。
  await b.service.receiveOps(a.identity.deviceId, [ops[2]]);
  await waitFor(() => b.service.dump().events[session.id]?.length === 3, { label: "gap filled by snapshot" });
  await b.service.receiveOps(a.identity.deviceId, [ops[0], ops[1]]);
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(b.service.dump().events[session.id].length, 3);
  assert.deepEqual(
    b.service.dump().events[session.id].map((e) => e.seq),
    [1, 2, 3],
  );
});

test("审批竞争：并发回答只有一个赢家，另一端标记 superseded", () => {
  const storageA = createMemoryStorage();
  const storageB = createMemoryStorage();
  const interactionsA = createInteractionsSync({ storage: storageA, deviceId: "dev_a" });
  const interactionsB = createInteractionsSync({ storage: storageB, deviceId: "dev_b" });
  const request = interactionsA.register({ sessionId: "ses_1", kind: "approval", prompt: "run tests?" });
  interactionsB.applyRequest(request);

  const answerA = interactionsA.answer(request.id, {
    deviceId: "dev_a",
    value: "allow",
    at: "2026-01-01T00:00:02.000Z",
  });
  const answerB = interactionsB.answer(request.id, {
    deviceId: "dev_b",
    value: "deny",
    at: "2026-01-01T00:00:01.000Z",
  });
  // 各自本地先到者暂时生效。
  assert.equal(answerA.accepted, true);
  assert.equal(answerB.accepted, true);

  interactionsA.applyAnswer(request.id, answerB.record.answers.find((a) => a.deviceId === "dev_b"));
  interactionsB.applyAnswer(request.id, answerA.record.answers.find((a) => a.deviceId === "dev_a"));

  const finalA = interactionsA.get(request.id);
  const finalB = interactionsB.get(request.id);
  assert.equal(finalA.status, "answered");
  assert.equal(finalB.status, "answered");
  assert.deepEqual(finalA.winner, finalB.winner);
  assert.equal(finalA.winner.deviceId, "dev_b");
  assert.equal(finalA.winner.value, "deny");
  assert.deepEqual(
    finalA.answers.map((a) => ({ id: a.deviceId, status: a.status })).sort((x, y) => x.id.localeCompare(y.id)),
    finalB.answers.map((a) => ({ id: a.deviceId, status: a.status })).sort((x, y) => x.id.localeCompare(y.id)),
  );
  const superseded = finalA.answers.filter((a) => a.status === "superseded");
  assert.equal(superseded.length, 1);
  assert.equal(superseded[0].deviceId, "dev_a");
});

test("审批跨端：B 端回答后 A 端通过同步看到唯一结果", async (t) => {
  const dir = makeTmpDir(t);
  const a = createFileNode(t, { dir: join(dir, "a"), name: "A" });
  const b = createFileNode(t, { dir: join(dir, "b"), name: "B" });
  pairNodes(a, b);
  const request = a.service.registerInteraction({ sessionId: "ses_remote", kind: "approval", prompt: "deploy?" });
  await connectPair(a, b);
  await waitFor(() => b.service.interactions.get(request.id), { label: "interaction synced" });

  const answered = b.service.answerInteraction(request.id, { value: "allow" });
  assert.equal(answered.accepted, true);
  await waitFor(() => a.service.interactions.get(request.id)?.status === "answered", { label: "answer synced" });
  const final = a.service.interactions.get(request.id);
  assert.equal(final.winner.value, "allow");
  assert.equal(final.winner.deviceId, b.identity.deviceId);
  assert.equal(final.answers.length, 1);
});

test("PA 并发：不互相覆盖、顺序与回复归属可重复验证", async (t) => {
  const dir = makeTmpDir(t);
  const paId = "pa_sync_shared";
  const a = createFileNode(t, { dir: join(dir, "a"), name: "A", paId });
  const b = createFileNode(t, { dir: join(dir, "b"), name: "B", paId });
  pairNodes(a, b);
  await connectPair(a, b);

  const fromA = a.service.appendPaMessage({ text: "hello from A" });
  await waitFor(() => b.service.dump().paMessages.length === 1);
  const reply = b.service.appendPaMessage({ text: "reply from B", replyTo: fromA.id });
  await waitFor(() => a.service.dump().paMessages.length === 2, { label: "PA reply synced" });

  const aView = a.service.dump().paMessages;
  const bView = b.service.dump().paMessages;
  assert.deepEqual(aView.map((m) => m.id), bView.map((m) => m.id));
  assert.deepEqual(aView.map((m) => m.content), ["hello from A", "reply from B"]);
  assert.equal(aView.find((m) => m.id === reply.id).replyTo, fromA.id);
  assert.equal(aView.find((m) => m.id === reply.id).sourceNode, b.identity.deviceId);

  // 再重连一次，顺序必须完全一致（可重放）。
  await disconnectPair(a, b);
  await connectPair(a, b);
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.deepEqual(a.service.dump().paMessages.map((m) => m.id), b.service.dump().paMessages.map((m) => m.id));
});

test("离线命令队列：等待目标在线；上线后只执行一次；过期的命令不执行", async (t) => {
  const dir = makeTmpDir(t);
  const executions = [];
  const b = createFileNode(t, {
    dir: join(dir, "b"),
    name: "B",
    commandHandler: async (command) => {
      executions.push(command.id);
      return { state: "succeeded", result: { ok: true } };
    },
  });
  const a = createFileNode(t, { dir: join(dir, "a"), name: "A" });
  pairNodes(a, b);
  await connectPair(a, b);
  await waitFor(() => a.service.isPeerOnline(b.identity.deviceId));
  await disconnectPair(a, b);
  await waitFor(() => !a.service.isPeerOnline(b.identity.deviceId));

  const waiting = a.service.sendCommand({
    kind: "turn.stop",
    targetDeviceId: b.identity.deviceId,
    payload: { reason: "user" },
  });
  assert.equal(waiting.state, "accepted");
  assert.equal(waiting.delivery, "waiting_target");
  assert.notEqual(waiting.state, "succeeded");
  assert.equal(executions.length, 0);

  await connectPair(a, b);
  await waitFor(() => a.service.commands.get(waiting.commandId)?.state === "succeeded", {
    label: "offline command executed",
  });
  assert.deepEqual(executions, [waiting.commandId]);

  // 重复投递同一 commandId：不产生第二次执行，收据一致。
  const duplicateCommand = a.service.commands.get(waiting.commandId).command;
  const receiptBefore = a.service.commands.get(waiting.commandId).receipt;
  b.service.receiveEnvelope(a.identity.deviceId, { t: "cmd.deliver", command: duplicateCommand });
  b.service.receiveEnvelope(a.identity.deviceId, { t: "cmd.deliver", command: duplicateCommand });
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(executions.length, 1);
  assert.deepEqual(b.service.commands.get(waiting.commandId).receipt, receiptBefore);

  const expiredCommand = a.service.sendCommand({
    kind: "turn.send",
    targetDeviceId: b.identity.deviceId,
    payload: { text: "too late" },
    ttlMs: -1,
  });
  assert.equal(expiredCommand.state, "expired");
  assert.ok(!executions.includes(expiredCommand.commandId));
});

test("历史窗口：默认只同步最近事件；按需补取；原节点离线时明确要求在线", async (t) => {
  const dir = makeTmpDir(t);
  const a = createFileNode(t, { dir: join(dir, "a"), name: "A", eventWindow: 2 });
  const b = createFileNode(t, { dir: join(dir, "b"), name: "B", eventWindow: 2 });
  pairNodes(a, b);
  const session = a.service.appendSession({ title: "history" });
  for (let i = 1; i <= 5; i += 1) {
    a.service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: `m${i}` } });
  }
  const second = a.service.appendSession({ title: "older" });
  for (let i = 1; i <= 5; i += 1) {
    a.service.appendEvent({ sessionId: second.id, kind: "message", payload: { text: `o${i}` } });
  }

  await connectPair(a, b);
  await waitFor(() => b.service.dump().events[session.id]?.length === 2, { label: "window only" });
  assert.deepEqual(
    b.service.dump().events[session.id].map((e) => e.seq),
    [4, 5],
  );
  const coverage = b.service.getHistoryCoverage(session.id);
  assert.equal(coverage.truncated, true);
  assert.equal(coverage.requiresOriginOnline, true);

  const fetched = await b.service.fetchHistory(session.id, { beforeSeq: 4, limit: 10 });
  assert.equal(fetched.available, true);
  assert.deepEqual(fetched.events.map((e) => e.seq), [1, 2, 3]);
  assert.equal(b.service.dump().events[session.id].length, 5);

  await disconnectPair(a, b);
  await waitFor(() => !b.service.isPeerOnline(a.identity.deviceId));
  const offline = await b.service.fetchHistory(second.id, { beforeSeq: 4 });
  assert.equal(offline.available, false);
  assert.equal(offline.requiresOriginOnline, true);
  assert.match(offline.message, /origin device/);

  // 已同步的窗口仍可离线阅读。
  assert.equal(b.service.dump().events[session.id].length, 5);
  assert.equal(b.service.dump().events[second.id].length, 2);
});
