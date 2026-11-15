import test from "node:test";
import assert from "node:assert/strict";
import { makeId } from "../../contracts/ids.mjs";
import { createCommand } from "../../contracts/commands.mjs";
import { createAuditLog } from "../../pa/audit.mjs";
import { createPaRunner } from "../../pa/runner.mjs";
import { createPaStore } from "../../pa/store.mjs";
import { createPaTools } from "../../pa/tools.mjs";
import { fakeClock, fakeCore, makeTempDir, scriptedLlm } from "./helpers.mjs";

function setup(t) {
  const store = createPaStore({ dataDir: makeTempDir(t) });
  t.after(() => store.close());
  const audit = createAuditLog(store);
  return { store, audit };
}

test("PA-104: 查询会话只读核心投影，不产生命令", async (t) => {
  const { audit } = setup(t);
  const core = fakeCore({ sessions: [{ id: "ses_demo1", title: "demo" }] });
  const tools = createPaTools({ nodeId: "dev_a", core, audit });
  const receipt = await tools.listSessions();
  assert.equal(receipt.ok, true);
  assert.equal(receipt.transport, "core.snapshot");
  assert.equal(receipt.sessions.length, 1);
  assert.equal(receipt.sessions[0].id, "ses_demo1");
  assert.equal(core.received.length, 0);
  assert.equal(core.sessions.size, 1);
  const entries = audit.list({ action: "sessions.list" });
  assert.equal(entries.length, 1);
  assert.equal(entries[0].decision, "query");
  assert.equal(entries[0].actor, "pa");
  assert.equal(entries[0].sourceNode, "dev_a");
});

test("PA-104: 创建 Agent 会话经 core.command 投递并返回收据", async (t) => {
  const { audit } = setup(t);
  const core = fakeCore();
  const tools = createPaTools({ nodeId: "dev_a", core, audit });
  const receipt = await tools.createSession({ agentInstanceId: "ai_demo" });
  assert.equal(receipt.ok, true);
  assert.equal(receipt.state, "succeeded");
  assert.match(receipt.commandId, /^cmd_[0-9a-f]{32}$/);
  assert.equal(core.received.length, 1);
  const command = core.received[0];
  assert.equal(command.source, "pa");
  assert.equal(command.kind, "session.create");
  assert.equal(command.target.nodeId, "dev_a");
  assert.equal(command.payload.agentInstanceId, "ai_demo");
  const entry = audit.list({ action: "session.create" })[0];
  assert.equal(entry.actor, "pa");
  assert.equal(entry.decision, "dispatch");
  assert.equal(entry.sourceNode, "dev_a");
  assert.equal(entry.targetNode, "dev_a");
  assert.equal(entry.commandId, command.id);
  assert.equal(entry.result.state, "succeeded");
});

test("PA-104: 发送与停止 Agent 会话都返回命令收据", async (t) => {
  const { audit } = setup(t);
  const core = fakeCore();
  const tools = createPaTools({ nodeId: "dev_a", core, audit });
  const created = await tools.createSession({ agentInstanceId: "ai_demo" });
  const sessionId = created.result.sessionId;
  const sent = await tools.sendTurn({ sessionId, text: "请检查构建" });
  const stopped = await tools.stopTurn({ sessionId });
  assert.equal(sent.ok, true);
  assert.equal(sent.result.queued, true);
  assert.equal(stopped.ok, true);
  assert.equal(stopped.result.stopped, true);
  assert.equal(core.received.length, 3);
  assert.deepEqual(
    core.received.map((command) => command.kind),
    ["session.create", "turn.send", "turn.stop"],
  );
  assert.equal(audit.list({ action: "turn.send" })[0].targetSessionId, sessionId);
  assert.equal(audit.list({ action: "turn.stop" })[0].targetSessionId, sessionId);
});

test("PA-104 / SEC-005: 自动审批审计区分 PA 与用户决定", async (t) => {
  const { audit } = setup(t);
  const core = fakeCore();
  const tools = createPaTools({ nodeId: "dev_a", core, audit });
  const interactionId = makeId("int");
  const paDecision = await tools.answerInteraction({
    interactionId,
    sessionId: "ses_demo1",
    decision: "approve",
    actor: "pa",
  });
  const userDecision = await tools.answerInteraction({
    interactionId,
    sessionId: "ses_demo1",
    decision: "deny",
    actor: "user",
  });
  assert.equal(paDecision.ok, true);
  assert.equal(userDecision.ok, true);
  const paEntries = audit.list({ action: "interaction.answer", actor: "pa" });
  const userEntries = audit.list({ action: "interaction.answer", actor: "user" });
  assert.equal(paEntries.length, 1);
  assert.equal(paEntries[0].decision, "approve");
  assert.equal(userEntries.length, 1);
  assert.equal(userEntries[0].decision, "deny");
  assert.equal(paEntries[0].commandId === userEntries[0].commandId, false);
});

test("PA-104: 没有核心时返回 unavailable 收据并留下失败审计，不抛异常", async (t) => {
  const { audit } = setup(t);
  const tools = createPaTools({ nodeId: "dev_a", audit });
  const query = await tools.listSessions();
  const created = await tools.createSession({ agentInstanceId: "ai_demo" });
  assert.equal(query.ok, false);
  assert.equal(query.state, "unavailable");
  assert.equal(created.ok, false);
  assert.equal(created.state, "unavailable");
  assert.match(created.error, /core offline/);
  const entries = audit.list({ action: "session.create" });
  assert.equal(entries.length, 1);
  assert.equal(entries[0].result.state, "unavailable");
  assert.equal(entries[0].actor, "pa");
});

test("PA-104: 核心拒绝命令时返回 failed 收据并记录错误", async (t) => {
  const { audit } = setup(t);
  const core = fakeCore({ failKinds: ["turn.stop"], failError: "core command failed" });
  const tools = createPaTools({ nodeId: "dev_a", core, audit });
  const receipt = await tools.stopTurn({ sessionId: "ses_demo1" });
  assert.equal(receipt.ok, false);
  assert.equal(receipt.state, "failed");
  assert.match(receipt.error, /core command failed/);
  const entry = audit.list({ action: "turn.stop" })[0];
  assert.equal(entry.result.state, "failed");
  assert.match(entry.error, /core command failed/);
  assert.equal(entry.commandId, receipt.commandId);
});

test("PA-104 / PA-011: 同一 commandId 重放只执行一次并返回同一收据", async (t) => {
  const { audit } = setup(t);
  const core = fakeCore();
  const tools = createPaTools({ nodeId: "dev_a", core, audit });
  const command = createCommand({
    kind: "turn.send",
    source: "pa",
    target: { nodeId: "dev_a", sessionId: "ses_demo1" },
    payload: { sessionId: "ses_demo1", text: "retry once", attachments: [] },
  });
  const first = await tools.dispatchCommand(command);
  const second = await tools.dispatchCommand(command);
  assert.equal(first.ok, true);
  assert.equal(first.duplicate, false);
  assert.equal(second.ok, true);
  assert.equal(second.duplicate, true);
  assert.equal(first.commandId, second.commandId);
  assert.equal(core.executions, 1);
  assert.equal(core.results.get(command.id).commandId, command.id);
});

test("PA-104: LLM 工具循环经统一命令层创建会话并写入审计", async (t) => {
  const dir = makeTempDir(t);
  const core = fakeCore();
  const llm = scriptedLlm([
    { toolCalls: [{ name: "createSession", args: { agentInstanceId: "ai_demo" } }] },
    { text: "会话已创建，我会继续跟进。" },
  ]);
  const runner = createPaRunner({ dataDir: dir, nodeId: "dev_a", core, llm, clock: fakeClock() });
  const result = await runner.sendUserMessage("帮我开个会话");
  assert.equal(result.status, "replied");
  assert.equal(result.toolResults.length, 1);
  assert.equal(result.toolResults[0].ok, true);
  assert.equal(core.sessions.size, 1);
  assert.equal(result.reply.replyTo, result.userMessage.id);
  // PA-010：工具过程不复制成 PA 的第二条完整会话，PA 会话只有 user + assistant 两条。
  assert.equal(runner.history().length, 2);
  assert.equal(llm.calls.length, 2);
  assert.ok(llm.calls[1].messages.some((message) => String(message.content).includes("PA tool results")));
  const entries = runner.audit.list({ action: "session.create" });
  assert.equal(entries.length, 1);
  assert.equal(entries[0].actor, "pa");
  assert.equal(entries[0].decision, "dispatch");
  assert.equal(entries[0].sourceNode, "dev_a");
});

test("PA-104: 参数校验失败不投递命令，未知工具明确失败", async (t) => {
  const { audit } = setup(t);
  const core = fakeCore();
  const tools = createPaTools({ nodeId: "dev_a", core, audit });
  const invalid = await tools.createSession({ agentInstanceId: "not-an-agent-id" });
  const unknown = await tools.invoke("deleteEverything", {});
  assert.equal(invalid.ok, false);
  assert.equal(invalid.state, "failed");
  assert.match(invalid.error, /ai_ ID/);
  assert.equal(unknown.ok, false);
  assert.match(unknown.error, /unknown PA tool/);
  assert.equal(core.received.length, 0);
});
