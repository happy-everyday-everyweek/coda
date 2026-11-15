/** Codex `exec --json` 适配器（AGT-112）：只用真实观测的帧与诚实降级。 */
import test from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { readFileSync } from "node:fs";

import { CodexExecAdapter, codexExecCapabilityDeclaration } from "../../adapters/codex-exec.mjs";
import { CAPABILITY_KEYS } from "../../contracts/capabilities.mjs";

const FAKE = join(import.meta.dirname, "..", "..", "fixtures", "adapters", "fake-codex-exec.mjs");
const RECORDING = join(import.meta.dirname, "..", "..", "fixtures", "adapters", "codex-exec-noauth.fixture.json");

function capture(adapter) {
  const events = [];
  adapter.emit = (event) => events.push(event);
  return events;
}

function noSupportedValues(value, path = "") {
  const bad = [];
  if (value === true) bad.push(path);
  else if (value && typeof value === "object") {
    for (const [k, v] of Object.entries(value)) bad.push(...noSupportedValues(v, path ? `${path}.${k}` : k));
  }
  return bad;
}

test("真实录制：codex exec 无凭据 14 帧，含 thread.started/turn.started/error", () => {
  const fixture = JSON.parse(readFileSync(RECORDING, "utf8"));
  assert.equal(fixture.source, "recording");
  assert.equal(fixture.agentVersion, "0.167.0");
  const types = new Set(fixture.events.map((e) => e.type));
  assert.ok(types.has("thread.started"));
  assert.ok(types.has("turn.started"));
  assert.ok(types.has("error"));
  assert.equal(fixture.redaction.applied, true);
});

test("CodexExec：probe 检测 exec 与 --json", async () => {
  const adapter = new CodexExecAdapter({ instanceId: "ai_codexexec", command: process.execPath, baseArgs: [FAKE] });
  const probe = await adapter.probe();
  assert.equal(probe.installed, true);
  assert.equal(adapter.version, "0.167.0-fake");
  assert.equal(adapter.probing.execDetected, true);
  assert.equal(adapter.probing.jsonDetected, true);
  const decl = adapter.capabilities();
  assert.deepEqual(Object.keys(decl.capabilities).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl.capabilities), []);
  await adapter.dispose();
});

test("CodexExec：成功回合发出 session/message/status 事件并带回 thread id", async () => {
  const adapter = new CodexExecAdapter({ instanceId: "ai_codexexec2", command: process.execPath, baseArgs: [FAKE] });
  await adapter.probe();
  const events = capture(adapter);
  await adapter.createSession({ sessionId: "ses_cx" });
  const result = await adapter.send({ sessionId: "ses_cx", text: "ping" });
  assert.equal(result.state, "succeeded");
  assert.equal(result.text, "pong");
  assert.equal(result.upstreamSessionId, "thread-fake-1");
  assert.ok(events.some((e) => e.kind === "session" && e.payload.session.providerThreadId === "thread-fake-1"));
  assert.ok(events.some((e) => e.kind === "message" && e.payload.text === "pong"));
  assert.ok(events.some((e) => e.kind === "reasoning"));
  await adapter.dispose();
});

test("CodexExec：无登录态失败回合不伪造成功", async (t) => {
  const previous = process.env.CODEX_FAKE_MODE;
  process.env.CODEX_FAKE_MODE = "error";
  t.after(() => {
    if (previous === undefined) delete process.env.CODEX_FAKE_MODE;
    else process.env.CODEX_FAKE_MODE = previous;
  });
  const adapter = new CodexExecAdapter({ instanceId: "ai_codexexec3", command: process.execPath, baseArgs: [FAKE] });
  await adapter.probe();
  const events = capture(adapter);
  await adapter.createSession({ sessionId: "ses_cx3" });
  const result = await adapter.send({ sessionId: "ses_cx3", text: "ping" });
  assert.equal(result.state, "failed");
  assert.match(result.error.message, /401|Unauthorized/);
  assert.ok(events.some((e) => e.kind === "error"));
  await adapter.dispose();
});

test("CodexExec：无原生 steer/fork，显式报 UNSUPPORTED_CAPABILITY", async () => {
  const adapter = new CodexExecAdapter({ instanceId: "ai_codexexec4", command: process.execPath, baseArgs: [FAKE] });
  await adapter.probe();
  await assert.rejects(adapter.steer(), (err) => err.code === "UNSUPPORTED_CAPABILITY");
  await assert.rejects(adapter.fork(), (err) => err.code === "UNSUPPORTED_CAPABILITY");
  await adapter.dispose();
});

test("CodexExec 能力声明覆盖全部键且没有 supported", () => {
  const decl = codexExecCapabilityDeclaration({ protocolDetected: true });
  assert.deepEqual(Object.keys(decl).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl), []);
});
