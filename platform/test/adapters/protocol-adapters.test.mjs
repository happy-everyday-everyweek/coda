/** AGT-111/112/113 的协议适配器测试：只验证已观测的协议事实与诚实降级。 */
import test from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { readFileSync } from "node:fs";

import {
  DeepSeekHarnessAdapter,
  createDeepSeekHarnessAdapter,
  deepseekHarnessCapabilityDeclaration,
} from "../../adapters/deepseek-harness.mjs";
import {
  CodexAppServerAdapter,
  codexAppServerCapabilityDeclaration,
} from "../../adapters/codex-app-server.mjs";
import {
  listProbeEvidence,
  listRecordings,
  loadProbeEvidence,
  probeEvidenceForMatrix,
  validateProbeEvidence,
  PROBE_TASK_IDS,
} from "../../adapters/probe-evidence.mjs";
import { CAPABILITY_KEYS } from "../../contracts/capabilities.mjs";

const FAKE_DSH = join(import.meta.dirname, "..", "..", "fixtures", "adapters", "fake-dsh-headless.mjs");
const FAKE_CODEX = join(import.meta.dirname, "..", "..", "fixtures", "adapters", "fake-codex-app-server.mjs");
const CODEX_HANDSHAKE = join(import.meta.dirname, "..", "..", "fixtures", "adapters", "codex-app-server-initialize.fixture.json");
const DSH_RECORDING = join(
  import.meta.dirname,
  "..", "..", "fixtures", "adapters",
  "deepseek-harness-headless-error.fixture.json",
);

function capture(adapter) {
  const events = [];
  adapter.emit = (event) => events.push(event);
  return events;
}

function noSupportedValues(capabilities) {
  const walk = (value, path = "") => {
    const bad = [];
    if (value === true) bad.push(path);
    else if (value && typeof value === "object") {
      for (const [k, v] of Object.entries(value)) bad.push(...walk(v, path ? `${path}.${k}` : k));
    }
    return bad;
  };
  return walk(capabilities);
}

test("勘察证据：8 个 agent 都有版本与入口记录，codex 的 initialize 握手是真实响应", () => {
  const all = listProbeEvidence();
  assert.ok(all.length >= 8, `勘察证据不足: ${all.length}`);
  assert.ok(all.some((p) => p.id === "qoder-cn"));
  assert.ok(all.some((p) => p.id === "codebuddy"));
  for (const item of all) {
    assert.ok(item.totalProbes > 0, `${item.id} 没有勘察记录`);
    // npm 安装的 agent 有 packageVersion；curl 安装的（fx/kimi/trae 等）允许为 null
    if (item.package) assert.ok(item.packageVersion, `${item.id} 缺少包版本`);
  }
  const codex = loadProbeEvidence("codex");
  assert.ok(codex.probes.some((p) => p.cmd === "codex app-server --help"), "缺少 app-server 帮助记录");
  const handshake = JSON.parse(readFileSync(CODEX_HANDSHAKE, "utf8"));
  assert.equal(handshake.source, "recording");
  const response = handshake.frames.find((f) => f.id === 1);
  assert.ok(response, "缺少 initialize 响应帧");
  assert.match(response.result.userAgent, /coda-probe/);
  assert.equal(response.result.platformFamily, "unix");
  assert.deepEqual(validateProbeEvidence(codex), []);
  assert.equal(validateProbeEvidence({ schemaVersion: 1, agent: {}, probes: [] }).length > 0, true);
});

test("勘察证据 → 任务映射与矩阵证据形状", () => {
  assert.equal(PROBE_TASK_IDS.claude, "AGT-111");
  assert.equal(PROBE_TASK_IDS.codex, "AGT-112");
  assert.equal(PROBE_TASK_IDS.opencode, "AGT-121");
  const claude = listProbeEvidence().find((p) => p.id === "claude");
  assert.ok(claude.flags.includes("--output-format"));
  assert.ok(claude.flags.includes("stream-json"));
  const matrixEvidence = probeEvidenceForMatrix("claude");
  assert.equal(matrixEvidence.kind, "probe");
  assert.match(matrixEvidence.ref, /fixtures\/adapters\/probes\/claude\.json/);
});

test("真实录制：DSH headless 失败路径 6 帧，source=recording", () => {
  const recordings = listRecordings();
  const dsh = recordings.find((r) => r.agentId === "deepseek-harness");
  assert.ok(dsh, "缺少 DSH 录制");
  assert.equal(dsh.events, 6);
  assert.equal(dsh.agentVersion, "0.2.2-rc.1");
  const fixture = JSON.parse(readFileSync(DSH_RECORDING, "utf8"));
  assert.deepEqual(
    fixture.events.map((e) => e.type),
    ["session", "status", "status", "status", "status", "final"],
  );
  assert.equal(fixture.events.at(-1).reason.error.code, "MISSING_CREDENTIAL");
  assert.equal(fixture.redaction.applied, true);
});

test("DSH 适配器：probe 检测 --json/--session-id，能力全 experimental/unverified", async () => {
  const adapter = new DeepSeekHarnessAdapter({ instanceId: "ai_dsh", command: process.execPath, baseArgs: [FAKE_DSH] });
  const probe = await adapter.probe();
  assert.equal(probe.installed, true);
  assert.equal(adapter.version, "0.2.2-fake");
  assert.equal(adapter.probing.jsonDetected, true);
  assert.equal(adapter.probing.resumeDetected, true);
  const decl = adapter.capabilities();
  assert.deepEqual(Object.keys(decl.capabilities).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl.capabilities), [], "不允许出现 supported(true) 断言");
  assert.equal(decl.capabilities.turn.nativeSteer, false);
  await adapter.dispose();
});

test("DSH 适配器：成功回合发出 session/status/message 事件", async () => {
  const adapter = new DeepSeekHarnessAdapter({ instanceId: "ai_dsh2", command: process.execPath, baseArgs: [FAKE_DSH] });
  await adapter.probe();
  const events = capture(adapter);
  await adapter.createSession({ sessionId: "ses_dsh" });
  const result = await adapter.send({ sessionId: "ses_dsh", text: "ping" });
  assert.equal(result.state, "succeeded");
  assert.equal(result.text, "pong");
  assert.equal(result.upstreamSessionId, "session-fake");
  assert.ok(events.some((e) => e.kind === "session"));
  assert.ok(events.some((e) => e.kind === "message" && e.payload.text === "pong"));
  assert.ok(events.some((e) => e.kind === "status" && e.payload.state === "running"));
  await adapter.dispose();
});

test("DSH 适配器：失败回合发 error 事件并返回 failed，不伪造成功", async (t) => {
  const previous = process.env.DSH_FAKE_MODE;
  process.env.DSH_FAKE_MODE = "error";
  t.after(() => {
    if (previous === undefined) delete process.env.DSH_FAKE_MODE;
    else process.env.DSH_FAKE_MODE = previous;
  });
  const adapter = new DeepSeekHarnessAdapter({ instanceId: "ai_dsh3", command: process.execPath, baseArgs: [FAKE_DSH] });
  await adapter.probe();
  const events = capture(adapter);
  await adapter.createSession({ sessionId: "ses_dsh3" });
  const result = await adapter.send({ sessionId: "ses_dsh3", text: "ping" });
  assert.equal(result.state, "failed");
  assert.equal(result.error.code, "MISSING_CREDENTIAL");
  assert.ok(events.some((e) => e.kind === "error"));
  await adapter.dispose();
});

test("DSH 适配器：无原生 steer/fork，必须显式报 UNSUPPORTED_CAPABILITY", async () => {
  const adapter = createDeepSeekHarnessAdapter({ instanceId: "ai_dsh4", command: process.execPath, baseArgs: [FAKE_DSH] });
  await adapter.probe();
  await assert.rejects(adapter.steer(), (err) => err.code === "UNSUPPORTED_CAPABILITY");
  await assert.rejects(adapter.fork(), (err) => err.code === "UNSUPPORTED_CAPABILITY");
  await adapter.dispose();
});

test("Codex app-server：probe 检测 app-server，initialize 握手返回真实形状", async () => {
  const adapter = new CodexAppServerAdapter({
    instanceId: "ai_codex",
    command: process.execPath,
    baseArgs: [FAKE_CODEX],
  });
  const probe = await adapter.probe();
  assert.equal(probe.installed, true);
  assert.equal(adapter.version, "0.167.0-fake");
  assert.equal(adapter.probing.appServerDetected, true);
  const handshake = await adapter.connect();
  assert.match(handshake.userAgent, /codex-app-server/);
  assert.equal(handshake.platformFamily, "unix");
  const decl = adapter.capabilities();
  assert.deepEqual(noSupportedValues(decl.capabilities), [], "握手/会话方法已验证也不能声明 supported");
  await adapter.dispose();
});

test("Codex app-server：thread/start → turn/start → 流式 delta → turn/completed", async () => {
  const adapter = new CodexAppServerAdapter({
    instanceId: "ai_codex2",
    command: process.execPath,
    baseArgs: [FAKE_CODEX],
  });
  await adapter.probe();
  const events = capture(adapter);
  await adapter.connect();
  const created = await adapter.createSession({ sessionId: "ses_cx" });
  assert.equal(created.providerThreadId, "thread-fake-1");
  const result = await adapter.send({ sessionId: "ses_cx", text: "ping" });
  assert.equal(result.state, "succeeded");
  assert.equal(result.text, "pong");
  assert.ok(events.some((e) => e.kind === "message" && e.payload.text === "po"));
  assert.ok(events.some((e) => e.kind === "reasoning"));
  assert.ok(events.some((e) => e.kind === "tool" && e.payload.tool.state === "completed"));
  assert.ok(events.some((e) => e.kind === "status" && e.payload.state === "idle"));
  assert.ok(events.some((e) => e.kind === "message" && e.payload.delta === false && e.payload.text === "pong"));
  await adapter.dispose();
});

test("Codex app-server：steer 与 interrupt 走 turn/steer、turn/interrupt", async () => {
  const adapter = new CodexAppServerAdapter({
    instanceId: "ai_codex3",
    command: process.execPath,
    baseArgs: [FAKE_CODEX],
  });
  await adapter.probe();
  await adapter.connect();
  await adapter.createSession({ sessionId: "ses_cx3" });
  const steerResult = await adapter.steer({ sessionId: "ses_cx3", text: "more", expectedTurnId: "turn-fake-1" });
  assert.ok(steerResult && typeof steerResult === "object");
  const stopped = await adapter.interrupt({ sessionId: "ses_cx3", turnId: "turn-fake-1" });
  assert.equal(stopped.stopped, true);
  assert.equal(stopped.trust, "upstream-confirmed");
  await adapter.dispose();
});

test("Codex 能力声明覆盖全部键且只到 experimental", () => {
  const decl = codexAppServerCapabilityDeclaration({ handshakeOk: true, sessionMethodsOk: true });
  assert.deepEqual(Object.keys(decl).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl), []);
  assert.equal(decl.turn.startEndEvents, "experimental");
  assert.equal(decl.streaming.text, "experimental");
  assert.equal(decl.turn.nativeSteer, "experimental");
});

test("DSH 能力声明覆盖全部键", () => {
  const decl = deepseekHarnessCapabilityDeclaration({ protocolDetected: true, resumeDetected: true });
  assert.deepEqual(Object.keys(decl).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl), []);
});
