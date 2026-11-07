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
  assert.equal(all.length, 8);
  for (const item of all) {
    assert.ok(item.packageVersion, `${item.id} 缺少版本`);
    assert.ok(item.totalProbes > 0);
  }
  const codex = loadProbeEvidence("codex");
  const handshake = codex.probes.find((p) => p.cmd === "codex app-server");
  assert.ok(handshake, "缺少 app-server 握手记录");
  assert.equal(handshake.exitCode, 0);
  const response = JSON.parse(handshake.stdout.trim());
  assert.equal(response.id, 1);
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

test("Codex 适配器：probe 检测 app-server，initialize 握手返回真实形状", async () => {
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
  assert.deepEqual(noSupportedValues(decl.capabilities), [], "握手成功也不能声明 supported");
  await assert.rejects(adapter.createSession(), (err) => err.code === "BLOCKED");
  await assert.rejects(adapter.send(), (err) => err.code === "BLOCKED");
  await adapter.dispose();
});

test("Codex 能力声明覆盖全部键且不伪造会话方法", () => {
  const decl = codexAppServerCapabilityDeclaration({ handshakeOk: true });
  assert.deepEqual(Object.keys(decl).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl), []);
});

test("DSH 能力声明覆盖全部键", () => {
  const decl = deepseekHarnessCapabilityDeclaration({ protocolDetected: true, resumeDetected: true });
  assert.deepEqual(Object.keys(decl).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl), []);
});
