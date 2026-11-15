/** AGT-104：通用 ACP 适配器全流程（创建 / 流式 / 停止 / 审批 / 恢复 / 错误）。 */
import test from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";

import { AcpAdapter, acpCapabilityDeclaration } from "../../adapters/acp.mjs";
import { supports } from "../../contracts/capabilities.mjs";

const SERVER = fileURLToPath(new URL("../../fixtures/adapters/fake-acp-server.mjs", import.meta.url));

function makeAdapter(scenario = "happy", env = {}, options = {}) {
  return new AcpAdapter({
    instanceId: `ai_acp_${scenario}`,
    command: process.execPath,
    args: [SERVER],
    probeArgs: [SERVER, "--version"],
    overrideEnv: { FAKE_ACP_SCENARIO: scenario, FAKE_ACP_CHUNK_DELAY_MS: "0", ...env },
    ...options,
  });
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitFor(predicate, timeoutMs = 3000, label = "condition") {
  const started = Date.now();
  while (true) {
    const value = predicate();
    if (value) return value;
    if (Date.now() - started > timeoutMs) throw new Error(`waitFor timeout: ${label}`);
    await sleep(10);
  }
}

async function withAdapter(scenario, env, fn) {
  const adapter = makeAdapter(scenario, env);
  try {
    const probe = await adapter.probe();
    return await fn(adapter, probe);
  } finally {
    await adapter.dispose();
  }
}

test("probe 只跑 --version：无副作用，不建会话", async () => {
  await withAdapter("happy", {}, async (adapter, probe) => {
    assert.equal(probe.installed, true);
    assert.equal(probe.sideEffectFree, true);
    assert.equal(probe.version, "1.0.0");
    assert.ok(probe.path, "resolved executable path");
    assert.equal(adapter.listSessions().length, 0, "probe must not create a session");
    assert.ok(adapter.capabilities());
  });
});

test("完整happy链路：initialize → session/new → 流式 message/tool/plan → 回合完成", async () => {
  await withAdapter("happy", {}, async (adapter) => {
    const events = [];
    adapter.onEvent((event) => events.push(event));
    const session = await adapter.createSession({ sessionId: "ses_acp_happy", workspace: "/tmp/demo" });
    assert.match(session.providerSessionId, /^acp_sess_/);

    const result = await adapter.send({ sessionId: "ses_acp_happy", text: "run the tests" });
    assert.equal(result.state, "completed");
    assert.equal(result.stopReason, "end_turn");
    assert.equal(result.providerSessionId, session.providerSessionId);

    const kinds = events.map((e) => e.kind);
    assert.ok(kinds.includes("session"));
    assert.ok(kinds.includes("turn"));
    assert.ok(kinds.includes("message"));
    assert.ok(kinds.includes("tool"));
    assert.ok(kinds.includes("plan"));

    const assistantText = events
      .filter((e) => e.kind === "message" && e.payload.role === "assistant")
      .map((e) => e.payload.text)
      .join("");
    assert.ok(assistantText.includes("Look"));
    assert.ok(assistantText.includes("streaming"));
    assert.ok(events.filter((e) => e.kind === "message").every((e) => e.payload.delta === true));

    const tool = events.filter((e) => e.kind === "tool");
    assert.equal(tool[0].payload.toolCallId, "tc_1");
    assert.equal(tool[0].payload.started, true);
    assert.equal(tool[1].payload.status, "completed");

    // 未知 sessionUpdate 只进诊断，不崩溃（ADP-013）
    assert.ok(
      adapter.diagnostics.unknownFrames.some((f) => f.kind === "unknown-session-update" && f.type === "agent_future_thing"),
    );
  });
});

test("审批：原生请求 ID + 原生选项 ID，允许后继续流式", async () => {
  await withAdapter("approval", {}, async (adapter) => {
    const events = [];
    adapter.onEvent((event) => events.push(event));
    await adapter.createSession({ sessionId: "ses_acp_approval", workspace: "/tmp/demo" });
    const sendPromise = adapter.send({ sessionId: "ses_acp_approval", text: "run tests" });
    // 提前挂一个兜底 catch：断言失败时不要让未处理的 rejection 冒到测试进程
    sendPromise.catch(() => {});

    const interaction = await waitFor(
      () => adapter.pendingInteractions("ses_acp_approval")[0],
      3000,
      "pending permission",
    );
    assert.equal(interaction.kind, "permission");
    assert.equal(interaction.sessionId, "ses_acp_approval");
    assert.ok(interaction.nativeRequestId, "native request id must be preserved");
    assert.deepEqual(
      interaction.options.map((o) => o.optionId),
      ["allow-once", "allow-always", "reject-once"],
    );
    assert.equal(interaction.tool.toolCallId, "tc_1");

    // 用文案而不是原生 optionId 回答必须失败（SPEC 9.7）
    assert.throws(
      () => adapter.answerInteraction(interaction.interactionId, { optionId: "Allow once" }),
      (err) => err.code === "UNKNOWN_OPTION",
    );

    await adapter.answerInteraction(interaction.interactionId, { optionId: "allow-once", by: "user" });
    const result = await sendPromise;
    assert.equal(result.stopReason, "end_turn");

    const answered = events.filter((e) => e.kind === "interaction" && e.payload.state === "answered");
    assert.equal(answered.length, 1);
    assert.deepEqual(answered[0].payload.answer, { optionId: "allow-once", text: null });
    assert.equal(answered[0].payload.decision.by, "user");
  });
});

test("审批：拒绝时回合以 refusal 收尾，且不发后续消息", async () => {
  await withAdapter("approval", {}, async (adapter) => {
    await adapter.createSession({ sessionId: "ses_acp_deny", workspace: "/tmp/demo" });
    const sendPromise = adapter.send({ sessionId: "ses_acp_deny", text: "run tests" });
    sendPromise.catch(() => {});
    const interaction = await waitFor(() => adapter.pendingInteractions("ses_acp_deny")[0], 3000);
    await adapter.answerInteraction(interaction.interactionId, { optionId: "reject-once" });
    const result = await sendPromise;
    assert.equal(result.stopReason, "refusal");
    assert.equal(result.state, "completed");
  });
});

test("停止：session/cancel 让回合以 cancelled 结算，并保留已产生输出", async () => {
  await withAdapter("cancel", {}, async (adapter) => {
    const events = [];
    adapter.onEvent((event) => events.push(event));
    await adapter.createSession({ sessionId: "ses_acp_cancel", workspace: "/tmp/demo" });
    const sendPromise = adapter.send({ sessionId: "ses_acp_cancel", text: "long task" });
    sendPromise.catch(() => {});
    await waitFor(() => events.some((e) => e.kind === "message"), 3000, "first chunk");

    const stop = await adapter.interrupt({ sessionId: "ses_acp_cancel" });
    assert.equal(stop.upstreamConfirmed, "pending");
    assert.equal(stop.state, "stopping");

    const result = await sendPromise;
    assert.equal(result.state, "stopped");
    assert.equal(result.stopReason, "cancelled");
    assert.ok(events.some((e) => e.kind === "turn" && e.payload.state === "stopping"));
    assert.ok(events.some((e) => e.kind === "turn" && e.payload.state === "stopped"));
    assert.ok(events.some((e) => e.kind === "message"), "partial output must be preserved");
  });
});

test("协议错误：配额/限流被分类为 QUOTA_EXCEEDED 并让回合失败", async () => {
  await withAdapter("error", {}, async (adapter) => {
    const events = [];
    adapter.onEvent((event) => events.push(event));
    await adapter.createSession({ sessionId: "ses_acp_error", workspace: "/tmp/demo" });
    await assert.rejects(
      adapter.send({ sessionId: "ses_acp_error", text: "go" }),
      (err) => err.code === "QUOTA_EXCEEDED",
    );
    const errorEvent = events.find((e) => e.kind === "error");
    assert.ok(errorEvent);
    assert.equal(errorEvent.payload.code, "QUOTA_EXCEEDED");
    assert.ok(events.some((e) => e.kind === "turn" && e.payload.state === "failed"));
    assert.equal(adapter.listSessions()[0].state, "failed");
  });
});

test("进程崩溃：结果未知（unknown），不得伪装成完成或失败", async () => {
  await withAdapter("crash", {}, async (adapter) => {
    const events = [];
    adapter.onEvent((event) => events.push(event));
    await adapter.createSession({ sessionId: "ses_acp_crash", workspace: "/tmp/demo" });
    await assert.rejects(
      adapter.send({ sessionId: "ses_acp_crash", text: "go" }),
      (err) => err.code === "PROCESS_EXIT",
    );
    assert.ok(events.some((e) => e.kind === "turn" && e.payload.state === "unknown"));
  });
});

test("恢复：未声明 loadSession 时明确拒绝；声明后用 session/load 恢复", async () => {
  await withAdapter("happy", {}, async (adapter) => {
    await adapter.createSession({ sessionId: "ses_acp_resume_no", workspace: "/tmp/demo" });
    await assert.rejects(adapter.resume({ sessionId: "ses_acp_resume_no" }), (err) => err.code === "UNSUPPORTED_CAPABILITY");
  });

  await withAdapter("happy", { FAKE_ACP_LOAD: "1" }, async (adapter) => {
    await adapter.createSession({ sessionId: "ses_acp_resume_ok", workspace: "/tmp/demo" });
    const resumed = await adapter.resume({ sessionId: "ses_acp_resume_ok" });
    assert.equal(resumed.resumed, true);
    assert.match(resumed.providerSessionId, /^acp_sess_/);
  });
});

test("ACP 无原生 steer/fork：显式 UNSUPPORTED_CAPABILITY，交给 lifecycle 降级", async () => {
  await withAdapter("happy", {}, async (adapter) => {
    await adapter.createSession({ sessionId: "ses_acp_unsupported", workspace: "/tmp/demo" });
    await assert.rejects(adapter.steer({ sessionId: "ses_acp_unsupported", text: "x" }), (err) => err.code === "UNSUPPORTED_CAPABILITY");
    await assert.rejects(adapter.fork({ sessionId: "ses_acp_unsupported" }), (err) => err.code === "UNSUPPORTED_CAPABILITY");
  });
});

test("dispose 后入口 DISPOSED，进程资源释放", async () => {
  const adapter = makeAdapter("happy");
  await adapter.probe();
  await adapter.createSession({ sessionId: "ses_acp_dispose", workspace: "/tmp/demo" });
  await adapter.dispose();
  await assert.rejects(adapter.send({ sessionId: "ses_acp_dispose", text: "x" }), (err) => err.code === "DISPOSED");
  assert.equal(adapter._conn, null);
  assert.equal(adapter._child, null);
});

test("能力声明诚实：没有任何 supported 断言", () => {
  const caps = acpCapabilityDeclaration({ agentCapabilities: { loadSession: false } });
  const declaration = { capabilities: caps };
  assert.equal(supports(declaration, "turn", "nativeSteer"), false);
  assert.equal(supports(declaration, "session", "resume"), false); // unverified 不算支持
  assert.equal(supports(declaration, "streaming", "text"), true); // 仅 experimental
  assert.equal(supports(declaration, "subagent"), false);
  assert.equal(caps.approvals.nativeRequestId, "experimental");
  assert.equal(acpCapabilityDeclaration({ agentCapabilities: { loadSession: true } }).session.resume, "experimental");
});
