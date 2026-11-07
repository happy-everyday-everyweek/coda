/** Claude stream-json（AGT-111）与 ACP 画像（AGT-119/120/121/122/126/129）测试。 */
import test from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { readFileSync } from "node:fs";

import {
  ClaudeStreamJsonAdapter,
  claudeStreamCapabilityDeclaration,
  mapClaudeFrame,
} from "../../adapters/claude-stream-json.mjs";
import {
  ACP_PROFILES,
  AcpProfileError,
  agentCapabilitiesFromFixture,
  capabilitiesFromAcpHandshake,
  createAcpProfileAdapter,
  getAcpProfile,
} from "../../adapters/acp-profiles.mjs";
import { AcpAdapter } from "../../adapters/acp.mjs";
import { CAPABILITY_KEYS } from "../../contracts/capabilities.mjs";

const FAKE = join(import.meta.dirname, "..", "..", "fixtures", "adapters", "fake-claude-stream.mjs");
const CLAUDE_FIXTURE = join(import.meta.dirname, "..", "..", "fixtures", "adapters", "claude-stream-json-noauth.fixture.json");
const GEMINI_FIXTURE = join(import.meta.dirname, "..", "..", "fixtures", "adapters", "gemini-acp-handshake.fixture.json");
const OPENCODE_FIXTURE = join(import.meta.dirname, "..", "..", "fixtures", "adapters", "opencode-acp-handshake.fixture.json");

function capture(adapter) {
  const events = [];
  adapter.emit = (event) => events.push(event);
  return events;
}

function noSupportedValues(value, path = "") {
  const bad = [];
  if (value === true) bad.push(path);
  else if (value && typeof value === "object") for (const [k, v] of Object.entries(value)) bad.push(...noSupportedValues(v, path ? `${path}.${k}` : path));
  return bad;
}

test("Claude 真实录制：init/assistant/result 三帧，session_id 可提取", () => {
  const fixture = JSON.parse(readFileSync(CLAUDE_FIXTURE, "utf8"));
  assert.equal(fixture.source, "recording");
  assert.equal(fixture.frames.length, 3);
  assert.deepEqual(fixture.frames.map((f) => f.type), ["system", "assistant", "result"]);
  const init = fixture.frames[0];
  assert.equal(init.subtype, "init");
  assert.ok(init.session_id);
  assert.ok(Array.isArray(init.tools));
  assert.ok(init.model);
});

test("mapClaudeFrame：init → session/status，assistant 文本与工具，result 结束", () => {
  const frame = JSON.parse(readFileSync(CLAUDE_FIXTURE, "utf8")).frames[0];
  const events = [];
  const mapped = mapClaudeFrame(frame, { sessionId: "ses_c", emit: (e) => events.push(e) });
  assert.equal(mapped.sessionId, frame.session_id);
  assert.ok(events.some((e) => e.kind === "session" && e.payload.session.providerThreadId === frame.session_id));
  assert.ok(events.some((e) => e.kind === "status"));

  const events2 = [];
  const asst = mapClaudeFrame({ type: "assistant", message: { content: [{ type: "text", text: "hi" }, { type: "tool_use", id: "t1", name: "Bash", input: {} }] } }, { sessionId: "ses_c", emit: (e) => events2.push(e) });
  assert.equal(asst.text, "hi");
  assert.ok(events2.some((e) => e.kind === "message"));
  assert.ok(events2.some((e) => e.kind === "tool" && e.payload.tool.state === "started"));

  const events3 = [];
  const done = mapClaudeFrame({ type: "result", subtype: "success", is_error: false, result: "pong", session_id: "s" }, { sessionId: "ses_c", emit: (e) => events3.push(e) });
  assert.equal(done.finished, true);
  assert.equal(done.finalText, "pong");
  assert.ok(events3.some((e) => e.kind === "status" && e.payload.state === "idle"));
});

test("Claude 适配器：probe 检测 stream-json，成功回合产出 message/tool 事件", async () => {
  const adapter = new ClaudeStreamJsonAdapter({ instanceId: "ai_claude", command: process.execPath, baseArgs: [FAKE] });
  const probe = await adapter.probe();
  assert.equal(probe.installed, true);
  assert.equal(adapter.probing.streamJsonDetected, true);
  const decl = adapter.capabilities();
  assert.deepEqual(Object.keys(decl.capabilities).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl.capabilities), []);

  const events = capture(adapter);
  await adapter.createSession({ sessionId: "ses_c1" });
  const result = await adapter.send({ sessionId: "ses_c1", text: "ping" });
  assert.equal(result.state, "succeeded");
  assert.equal(result.text, "pong");
  assert.equal(result.upstreamSessionId, "claude-fake-session");
  assert.ok(events.some((e) => e.kind === "message" && e.payload.text === "po"));
  assert.ok(events.some((e) => e.kind === "tool" && e.payload.tool.state === "completed"));
  assert.ok(events.some((e) => e.kind === "session"));
  await adapter.dispose();
});

test("Claude 适配器：未登录失败回合不伪造成功", async (t) => {
  const prev = process.env.CLAUDE_FAKE_MODE;
  process.env.CLAUDE_FAKE_MODE = "error";
  t.after(() => { if (prev === undefined) delete process.env.CLAUDE_FAKE_MODE; else process.env.CLAUDE_FAKE_MODE = prev; });
  const adapter = new ClaudeStreamJsonAdapter({ instanceId: "ai_claude2", command: process.execPath, baseArgs: [FAKE] });
  await adapter.probe();
  const events = capture(adapter);
  await adapter.createSession({ sessionId: "ses_c2" });
  const result = await adapter.send({ sessionId: "ses_c2", text: "ping" });
  assert.equal(result.state, "failed");
  assert.match(result.error.message, /Not logged in/);
  assert.ok(events.some((e) => e.kind === "error"));
  await adapter.dispose();
});

test("ACP 画像：国际与国产 Agent 的命令与任务映射完整", () => {
  const ids = Object.keys(ACP_PROFILES);
  assert.deepEqual(ids.sort(), ["antigravity", "codebuddy", "fx", "gemini", "grok", "hermes", "kimi", "openclaw", "opencode", "qoder"]);
  assert.deepEqual(ACP_PROFILES.grok.args, ["--no-auto-update", "agent", "--no-leader", "stdio"]);
  assert.deepEqual(ACP_PROFILES.opencode.args, ["acp"]);
  assert.equal(ACP_PROFILES.gemini.taskId, "AGT-122");
  assert.equal(ACP_PROFILES.opencode.taskId, "AGT-121");
  assert.equal(ACP_PROFILES.fx.taskId, "AGT-129");
  assert.equal(ACP_PROFILES.qoder.taskId, "AGT-114");
  assert.equal(ACP_PROFILES.qoder.command, "qodercn");
  assert.equal(ACP_PROFILES.codebuddy.taskId, "AGT-117");
  assert.equal(ACP_PROFILES.kimi.taskId, "AGT-128");
  assert.equal(ACP_PROFILES.kimi.args[0], "acp");
  assert.equal(ACP_PROFILES.openclaw.domestic, false);
  assert.equal(ACP_PROFILES.codebuddy.domestic, true);
  assert.throws(() => getAcpProfile("nope"), AcpProfileError);
  const adapter = createAcpProfileAdapter({ profileId: "opencode", instanceId: "ai_oc" });
  assert.ok(adapter instanceof AcpAdapter);
  assert.equal(adapter.command, "opencode");
  assert.deepEqual(adapter.args, ["acp"]);
});

test("ACP 握手 → 能力声明：只把握手里明确能力标 experimental，不出现 supported", () => {
  const gemini = agentCapabilitiesFromFixture(JSON.parse(readFileSync(GEMINI_FIXTURE, "utf8")));
  assert.equal(gemini.agentInfo.name, "gemini-cli");
  const geminiCaps = capabilitiesFromAcpHandshake(gemini.capabilities, { taskId: "AGT-122" });
  assert.deepEqual(Object.keys(geminiCaps).filter((k) => k !== "meta").sort(), [...CAPABILITY_KEYS].sort());
  assert.equal(geminiCaps.session.resume, "experimental");
  assert.deepEqual(noSupportedValues({ ...geminiCaps, meta: undefined }), []);

  const opencode = agentCapabilitiesFromFixture(JSON.parse(readFileSync(OPENCODE_FIXTURE, "utf8")));
  const ocCaps = capabilitiesFromAcpHandshake(opencode.capabilities, { taskId: "AGT-121" });
  assert.equal(ocCaps.thread.fork, "experimental");
  assert.equal(ocCaps.session.resume, "experimental");
  assert.equal(ocCaps.attachments.input, "experimental");
  assert.deepEqual(noSupportedValues({ ...ocCaps, meta: undefined }), []);
});

test("Claude 能力声明覆盖全部键且没有 supported", () => {
  const decl = claudeStreamCapabilityDeclaration({ protocolDetected: true });
  assert.deepEqual(Object.keys(decl).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl), []);
});

test("headless 画像：Claude/Qoder/CodeBuddy 命令与能力诚实", async () => {
  const { HEADLESS_PROFILES, createHeadlessProfileAdapter, getHeadlessProfile } = await import("../../adapters/headless-profiles.mjs");
  assert.deepEqual(Object.keys(HEADLESS_PROFILES).sort(), ["claude", "codebuddy", "qoder"]);
  assert.equal(HEADLESS_PROFILES.qoder.taskId, "AGT-114");
  assert.equal(HEADLESS_PROFILES.qoder.command, "qodercn");
  assert.equal(HEADLESS_PROFILES.codebuddy.taskId, "AGT-117");
  assert.equal(HEADLESS_PROFILES.claude.protocolConfirmed, true);
  assert.equal(HEADLESS_PROFILES.qoder.protocolConfirmed, false);
  assert.equal(getHeadlessProfile("claude").command, "claude");
  assert.throws(() => getHeadlessProfile("nope"), /unknown headless profile/);

  const adapter = createHeadlessProfileAdapter({ profileId: "codebuddy", instanceId: "ai_cb", command: process.execPath, baseArgs: [FAKE] });
  await adapter.probe();
  const decl = adapter.capabilities();
  assert.equal(decl.provider, "codebuddy");
  assert.deepEqual(Object.keys(decl.capabilities).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl.capabilities), []);
  await adapter.dispose();
});

test("CodeBuddy 真实录制：与 Claude 同族（system init/assistant/result），映射器可解析", () => {
  const { mapClaudeFrame } = { mapClaudeFrame: null };
  // 用 Claude 适配器导出的映射器校验 CodeBuddy 帧形状
  return import("../../adapters/claude-stream-json.mjs").then(({ mapClaudeFrame }) => {
    const fixture = JSON.parse(readFileSync(join(import.meta.dirname, "..", "..", "fixtures", "adapters", "codebuddy-headless-stream-json.fixture.json"), "utf8"));
    assert.equal(fixture.source, "recording");
    assert.equal(fixture.agentId, "codebuddy");
    const types = fixture.frames.map((f) => f.type);
    assert.deepEqual(types, ["system", "system", "assistant", "system", "result"]);
    const init = fixture.frames[0];
    const events = [];
    const mapped = mapClaudeFrame(init, { sessionId: "ses_cb", emit: (e) => events.push(e) });
    assert.equal(mapped.sessionId, init.session_id);
    assert.ok(events.some((e) => e.kind === "session"));
    const result = fixture.frames.at(-1);
    const events2 = [];
    const done = mapClaudeFrame(result, { sessionId: "ses_cb", emit: (e) => events2.push(e) });
    assert.equal(done.finished, true);
    assert.ok(events2.some((e) => e.kind === "error"));
  });
});
