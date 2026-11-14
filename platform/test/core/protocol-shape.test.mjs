/** 客户端契约形状（C-01/C-02/C-04/C-05/C-06/C-08）在真实 core 上的回归测试。 */
import test from "node:test";
import assert from "node:assert/strict";

import { METHODS, rpcRequest } from "../../contracts/rpc.mjs";
import { createMockCore } from "../../core/index.mjs";
import { cleanupDir, fixedClock, makeTempDir } from "../../fixtures/core/helpers.mjs";

async function setup(t, { script = {} } = {}) {
  const dir = makeTempDir("coda-shape-");
  const core = createMockCore({ dataDir: dir, clock: fixedClock(), lock: false, script });
  t.after(async () => {
    await core.close();
    cleanupDir(dir);
  });
  const events = [];
  const off = core.subscribe((event) => events.push(event));
  t.after(() => off());
  return { dir, core, events };
}

async function createSession(core, dir) {
  const created = await core.handleRpc(
    rpcRequest(METHODS.command, { kind: "session.create", payload: { title: "shape", path: dir } }),
  );
  assert.equal(created.error, undefined, JSON.stringify(created.error));
  return created.result.result.sessionId;
}

test("空闲会话 turn.stop 是幂等 no-op（C-15）", async (t) => {
  const { dir, core } = await setup(t);
  const sessionId = await createSession(core, dir);
  const stopped = await core.handleRpc(
    rpcRequest(METHODS.command, { kind: "turn.stop", target: { sessionId }, payload: {} }),
  );
  assert.equal(stopped.error, undefined, JSON.stringify(stopped.error));
  assert.equal(stopped.result.state, "succeeded");
  assert.equal(stopped.result.stopped, false);
  assert.equal(stopped.result.reason, "no-active-turn");
});

test("message 事件带稳定 message id 与 {message} 包装", async (t) => {
  const { dir, core, events } = await setup(t, { script: { messages: ["hello"] } });
  const sessionId = await createSession(core, dir);
  const sent = await core.handleRpc(
    rpcRequest(METHODS.command, { kind: "turn.send", target: { sessionId }, payload: { text: "go" } }),
  );
  assert.equal(sent.error, undefined, JSON.stringify(sent.error));

  const messageEvents = events.filter((event) => event.kind === "message");
  assert.ok(messageEvents.length >= 2, `expected message events, got ${messageEvents.length}`);
  for (const event of messageEvents) {
    assert.ok(event.payload.message, "message payload must be wrapped in {message}");
    assert.equal(typeof event.payload.message.id, "string");
    assert.equal(event.payload.message.sessionId, sessionId);
    assert.equal(typeof event.payload.message.role, "string");
  }
  const ids = new Set(messageEvents.map((event) => event.payload.message.id));
  assert.equal(ids.size, 1, "deltas and final chunk must share one message id");
  const final = messageEvents.at(-1).payload.message;
  assert.equal(final.text, "hello");
  assert.equal(final.streaming, false);
});

test("interaction 事件带 {interaction} 包装，answer 接受 target.interactionId", async (t) => {
  const { dir, core, events } = await setup(t, { script: { needsApproval: true } });
  const sessionId = await createSession(core, dir);
  const sent = await core.handleRpc(
    rpcRequest(METHODS.command, { kind: "turn.send", target: { sessionId }, payload: { text: "go" } }),
  );
  assert.equal(sent.error, undefined, JSON.stringify(sent.error));

  const pending = events.find(
    (event) => event.kind === "interaction" && event.payload.interaction?.status === "pending",
  );
  assert.ok(pending, "expected pending interaction event");
  assert.equal(typeof pending.payload.interactionId, "string");
  assert.equal(pending.payload.interaction.id, pending.payload.interactionId);
  assert.equal(pending.payload.interaction.sessionId, sessionId);
  assert.ok(Array.isArray(pending.payload.interaction.options));
  assert.ok(pending.payload.interaction.prompt.length > 0);

  // C-06：客户端只发 target.interactionId，不传 sessionId。
  const answered = await core.handleRpc(
    rpcRequest(METHODS.command, {
      kind: "interaction.answer",
      target: { interactionId: pending.payload.interactionId },
      payload: { answer: "allow" },
    }),
  );
  assert.equal(answered.error, undefined, JSON.stringify(answered.error));
  assert.equal(answered.result.state, "succeeded");
  assert.equal(answered.result.interactionId, pending.payload.interactionId);
  assert.ok(
    events.some((event) => event.kind === "interaction" && event.payload.interaction?.status === "answered"),
  );

  // 重复回答是幂等成功（多端竞争），不是 CONFLICT。
  const duplicate = await core.handleRpc(
    rpcRequest(METHODS.command, {
      kind: "interaction.answer",
      target: { interactionId: pending.payload.interactionId },
      payload: { answer: "allow" },
    }),
  );
  assert.equal(duplicate.error, undefined);
  assert.equal(duplicate.result.duplicate, true);
});

test("subscribe 带 fromSeq 时连续回放事件，缺口返回 snapshot", async (t) => {
  const { dir, core } = await setup(t);
  await createSession(core, dir);
  const latest = core.services.journal.latestSeq();
  assert.ok(latest > 0);

  const upToDate = await core.handleRpc(rpcRequest(METHODS.subscribe, { fromSeq: latest }));
  assert.equal(upToDate.result.type, "events");
  assert.deepEqual(upToDate.result.events, []);
  assert.equal(upToDate.result.seq, latest);
  assert.equal(upToDate.result.resumed, true);

  const fromZero = await core.handleRpc(rpcRequest(METHODS.subscribe, { fromSeq: 1 }));
  assert.equal(fromZero.result.type, "events");
  assert.equal(fromZero.result.events[0].seq, 2);
  assert.equal(fromZero.result.seq, latest);
  assert.deepEqual(
    fromZero.result.events.map((event) => event.seq),
    Array.from({ length: latest - 1 }, (_, i) => i + 2),
  );

  const ahead = await core.handleRpc(rpcRequest(METHODS.subscribe, { fromSeq: latest + 10 }));
  assert.equal(ahead.result.type, "snapshot");
  assert.equal(ahead.result.gap, true);
  assert.ok(Array.isArray(ahead.result.snapshot.sessions));
});

test("全局快照带消息/审批/终端/成果/模型/能力/通知与会话展开字段", async (t) => {
  const { dir, core } = await setup(t, { script: { messages: ["snap"] } });
  const sessionId = await createSession(core, dir);
  await core.handleRpc(
    rpcRequest(METHODS.command, { kind: "turn.send", target: { sessionId }, payload: { text: "hi" } }),
  );
  const put = await core.handleRpc(
    rpcRequest(METHODS.command, {
      kind: "artifact.put",
      target: { sessionId },
      payload: { diff: "diff --git a/x b/x", kind: "diff", name: "x.diff" },
    }),
  );
  assert.equal(put.error, undefined, JSON.stringify(put.error));

  const snapshotResponse = await core.handleRpc(rpcRequest(METHODS.snapshot, {}));
  const snapshot = snapshotResponse.result.snapshot;
  assert.ok(Array.isArray(snapshot.sessions) && snapshot.sessions.length === 1);
  assert.ok(Array.isArray(snapshot.messages) && snapshot.messages.length >= 1);
  assert.ok(Array.isArray(snapshot.interactions));
  assert.ok(Array.isArray(snapshot.terminals));
  assert.ok(Array.isArray(snapshot.artifacts) && snapshot.artifacts.length === 1);
  assert.ok(Array.isArray(snapshot.notifications));
  assert.ok(snapshot.models && typeof snapshot.models === "object");
  assert.ok(snapshot.capabilities?.instances?.length >= 1);

  const session = snapshot.sessions[0];
  assert.equal(session.id, sessionId);
  assert.equal(session.sessionId, sessionId);
  assert.equal(session.nodeObject?.id, "dev_local");
  assert.equal(session.environmentObject?.id, "env_local_host");
  assert.equal(typeof session.node, "string");
  assert.equal(typeof session.environment, "string");
  assert.equal(typeof session.agent, "string");
  assert.ok(session.agentObject?.id);
  assert.ok("capabilities" in session);
  assert.ok(session.models);

  assert.equal(snapshot.artifacts[0].kind, "diff");
  assert.equal(snapshot.artifacts[0].diff.includes("diff --git"), true);
});

test("成果内容可经 provisional RPC 读取（C-08）", async (t) => {
  const { dir, core } = await setup(t);
  const sessionId = await createSession(core, dir);
  const put = await core.handleRpc(
    rpcRequest(METHODS.command, {
      kind: "artifact.put",
      target: { sessionId },
      payload: { content: "hello artifact", kind: "file", name: "a.txt" },
    }),
  );
  assert.equal(put.error, undefined, JSON.stringify(put.error));
  const artifactId = put.result.artifactId;
  assert.ok(artifactId.startsWith("art_"));

  const stat = await core.handleRpc(rpcRequest("core.artifact.stat", { artifactId }));
  assert.equal(stat.result.artifact.size, 14);
  assert.equal(stat.result.artifact.sessionId, sessionId);

  const chunk = await core.handleRpc(rpcRequest("core.artifact.readChunk", { artifactId, index: 0 }));
  assert.equal(chunk.error, undefined);
  assert.equal(Buffer.from(chunk.result.dataBase64, "base64").toString("utf8"), "hello artifact");

  const got = await core.handleRpc(rpcRequest("core.artifact.get", { artifactId }));
  assert.equal(got.result.content, "hello artifact");
  assert.equal(got.result.artifact.available, true);
});

test("终端打开返回回放能力，resize 明确降级（C-07）", async (t) => {
  const { dir, core } = await setup(t);
  const sessionId = await createSession(core, dir);
  const opened = await core.handleRpc(
    rpcRequest(METHODS.command, {
      kind: "terminal.open",
      target: { sessionId },
      payload: { command: process.execPath, args: ["-e", "setTimeout(() => {}, 300)"], title: "t" },
    }),
  );
  assert.equal(opened.error, undefined, JSON.stringify(opened.error));
  const terminalId = opened.result.terminalId;
  assert.ok(terminalId);
  assert.equal(opened.result.result.capabilities.pty, false);

  const listed = await core.handleRpc(rpcRequest("core.terminal.list", {}));
  assert.ok(listed.result.terminals.some((terminal) => terminal.id === terminalId));

  const resized = await core.handleRpc(
    rpcRequest(METHODS.command, {
      kind: "terminal.resize",
      target: { sessionId, terminalId },
      payload: { cols: 120, rows: 40 },
    }),
  );
  assert.equal(resized.error, undefined);
  assert.equal(resized.result.result.supported, false);
  assert.equal(resized.result.result.mode, "pipes");
});
