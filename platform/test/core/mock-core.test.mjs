/** CORE-106：Mock Adapter / Mock Core 场景（会话、审批、停止、崩溃、三界面一致）。 */
import test from "node:test";
import assert from "node:assert/strict";

import { METHODS, RPC_ERRORS, rpcRequest } from "../../contracts/rpc.mjs";
import { createMockAdapter } from "../../core/mock.mjs";
import { createMockCore } from "../../core/index.mjs";
import { cleanupDir, fixedClock, makeTempDir } from "../../fixtures/core/helpers.mjs";

function newMockCore(t, { script = {}, clock = fixedClock() } = {}) {
  const dir = makeTempDir("coda-mock-");
  const core = createMockCore({ dataDir: dir, clock, lock: false, script });
  t.after(async () => {
    await core.close();
    cleanupDir(dir);
  });
  return { dir, core };
}

test("MockAdapter 能力声明诚实（未实现的能力为 false）", async () => {
  const adapter = createMockAdapter({ provider: "mock", version: "1.0.0" });
  const probe = await adapter.probe();
  assert.equal(probe.installed, true);
  assert.equal(probe.loggedIn, true);

  const declaration = adapter.capabilities();
  assert.equal(declaration.provider, "mock");
  assert.equal(declaration.capabilities.streaming.content, true);
  assert.equal(declaration.capabilities.approvals.patch, false);
  assert.equal(declaration.capabilities.plan.todo, false);
  assert.equal(declaration.capabilities.subagent.lifecycle, false);

  const events = [];
  adapter.onEvent((event) => events.push(event));
  await adapter.createSession({ sessionId: "ses_mock" });
  await adapter.send({ sessionId: "ses_mock", turnId: "turn_mock", text: "hi" });
  assert.ok(events.some((e) => e.kind === "message" && e.payload.text === "mock reply"));
  assert.ok(events.some((e) => e.kind === "turn" && e.payload.state === "completed"));
  await assert.rejects(() => adapter.answerInteraction({ requestId: "mock-request-404" }), /unknown mock interaction/);
  await adapter.dispose();
});

test("Mock Core：会话、流式事件与完成状态", async (t) => {
  const { core } = newMockCore(t, { script: { messages: ["hi there"], reasoning: "thinking", plan: "1. step" } });
  const result = await core.mock.runScenario({ ui: "tui" });

  assert.equal(result.welcome.protocolVersion, 1);
  assert.equal(result.snapshotSeq, 0);
  assert.equal(result.snapshot.sessions.length, 1);
  assert.equal(result.snapshot.sessions[0].state, "idle");
  assert.ok(result.events.some((e) => e.kind === "message" && e.payload.text === "hi there"));
  assert.ok(result.events.some((e) => e.kind === "reasoning"));
  assert.ok(result.events.some((e) => e.kind === "plan"));
  assert.ok(result.events.some((e) => e.kind === "turn" && e.payload.state === "completed"));

  const seqs = result.events.map((e) => e.seq);
  assert.deepEqual(seqs, [...seqs].sort((a, b) => a - b));
  assert.equal(new Set(seqs).size, seqs.length);
});

test("Mock Core：审批请求可回答，回答后回合完成", async (t) => {
  const { core } = newMockCore(t, { script: { needsApproval: true } });
  const result = await core.mock.runScenario({ ui: "web", approve: true });

  assert.ok(result.events.some((e) => e.kind === "interaction" && e.payload.state === "pending"));
  assert.ok(result.events.some((e) => e.kind === "interaction" && e.payload.state === "answered"));
  assert.ok(result.events.some((e) => e.kind === "message" && e.payload.text === "approved and continued"));
  assert.ok(result.events.some((e) => e.kind === "turn" && e.payload.state === "completed"));
  assert.equal(core.state.sessions[0].state, "idle");
});

test("Mock Core：拒绝审批不会伪造成功继续", async (t) => {
  const { core } = newMockCore(t, { script: { needsApproval: true } });
  const result = await core.mock.runScenario({ ui: "tui", approve: false });
  const finished = result.events.find((e) => e.kind === "turn" && e.payload.state === "completed");
  assert.ok(finished);
  assert.equal(finished.payload.stopReason, "denied");
  assert.equal(result.events.some((e) => e.kind === "message" && e.payload.text === "approved and continued"), false);
});

test("Mock Core：turn.stop 结束回合且会话为 stopped", async (t) => {
  const { core } = newMockCore(t, { script: { needsApproval: true } });
  const created = await core.handleRpc(
    rpcRequest(METHODS.command, { kind: "session.create", payload: { title: "stop test" } }),
  );
  assert.equal(created.error, undefined, JSON.stringify(created.error));
  const sessionId = created.result.result.sessionId;

  const sent = await core.handleRpc(
    rpcRequest(METHODS.command, { kind: "turn.send", target: { sessionId }, payload: { text: "do it" } }),
  );
  assert.equal(sent.error, undefined);
  assert.equal(sent.result.result.awaitingApproval, true);
  assert.ok(core.services.journal.findActiveTurn(sessionId));

  const stopped = await core.handleRpc(
    rpcRequest(METHODS.command, { kind: "turn.stop", target: { sessionId }, payload: {} }),
  );
  assert.equal(stopped.error, undefined, JSON.stringify(stopped.error));
  assert.equal(stopped.result.state, "succeeded");
  assert.equal(stopped.result.result.stopped, true);
  assert.equal(core.services.journal.getSession(sessionId).state, "stopped");
  assert.equal(core.services.journal.findActiveTurn(sessionId), null);
});

test("Mock Core：崩溃后回合与会话为 unknown，不被重试成成功", async (t) => {
  const { core } = newMockCore(t, { script: { crash: true } });
  const result = await core.mock.runScenario({ ui: "tui" });

  assert.ok(result.events.some((e) => e.kind === "error"));
  assert.ok(result.events.some((e) => e.kind === "turn" && e.payload.state === "unknown"));
  assert.equal(core.state.sessions[0].state, "unknown");

  // 崩溃后的适配器拒绝再次发送/建会话，命令返回失败而不是假装成功。
  const created = await core.handleRpc(rpcRequest(METHODS.command, { kind: "session.create", payload: {} }));
  assert.ok(created.error);
  assert.equal(created.error.code, RPC_ERRORS.INTERNAL);
  assert.ok(core.services.receipts.list({ state: "failed" }).length >= 1);
});

test("三种界面跑同一 Mock 场景得到相同语义", async (t) => {
  const transcripts = [];
  for (const ui of ["tui", "gui", "web"]) {
    const dir = makeTempDir("coda-mock-ui-");
    const core = createMockCore({
      dataDir: dir,
      clock: fixedClock(),
      lock: false,
      script: { messages: ["same reply"], reasoning: "same reasoning" },
    });
    try {
      const result = await core.mock.runScenario({ ui });
      transcripts.push(result.transcript);
    } finally {
      await core.close();
      cleanupDir(dir);
    }
  }
  assert.ok(transcripts[0].length > 0);
  assert.deepEqual(transcripts[0], transcripts[1]);
  assert.deepEqual(transcripts[0], transcripts[2]);
});
