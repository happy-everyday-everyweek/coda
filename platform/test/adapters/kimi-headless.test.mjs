/** Kimi headless（AGT-128）：真实 meta 帧形状 + 诚实降级。 */
import test from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { readFileSync } from "node:fs";

import { KimiHeadlessAdapter, kimiHeadlessCapabilityDeclaration, mapKimiFrame } from "../../adapters/kimi-headless.mjs";
import { CAPABILITY_KEYS } from "../../contracts/capabilities.mjs";

const FAKE = join(import.meta.dirname, "..", "..", "fixtures", "adapters", "fake-kimi.mjs");
const RECORDING = join(import.meta.dirname, "..", "..", "fixtures", "adapters", "kimi-headless-stream-json.fixture.json");

function noSupportedValues(value, path = "") {
  const bad = [];
  if (value === true) bad.push(path);
  else if (value && typeof value === "object") for (const [k, v] of Object.entries(value)) bad.push(...noSupportedValues(v, path ? `${path}.${k}` : path));
  return bad;
}

test("Kimi 真实录制：只有 system.version 元帧，stderr 报需要登录", () => {
  const fixture = JSON.parse(readFileSync(RECORDING, "utf8"));
  assert.equal(fixture.agentVersion, "2.5.2");
  assert.equal(fixture.frames.length, 1);
  assert.equal(fixture.frames[0].type, "system.version");
  assert.match(fixture.stderr, /No model configured/);
  const events = [];
  const mapped = mapKimiFrame(fixture.frames[0], { sessionId: "ses_k", emit: (e) => events.push(e) });
  assert.equal(mapped.text, "");
  assert.ok(events.some((e) => e.kind === "status" && e.payload.version === "2.5.2"));
});

test("Kimi 适配器：probe 检测 stream-json，成功回合产出 message", async () => {
  const adapter = new KimiHeadlessAdapter({ instanceId: "ai_kimi", command: process.execPath, baseArgs: [FAKE] });
  const probe = await adapter.probe();
  assert.equal(probe.installed, true);
  assert.equal(adapter.version, "2.5.2-fake");
  assert.equal(adapter.probing.streamJsonDetected, true);
  const decl = adapter.capabilities();
  assert.deepEqual(Object.keys(decl.capabilities).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl.capabilities), []);

  const events = [];
  adapter.emit = (e) => events.push(e);
  await adapter.createSession({ sessionId: "ses_k1" });
  const result = await adapter.send({ sessionId: "ses_k1", text: "ping" });
  assert.equal(result.state, "succeeded");
  assert.equal(result.text, "pong");
  await adapter.dispose();
});

test("Kimi 适配器：无登录失败不伪造成功", async (t) => {
  process.env.KIMI_FAKE_MODE = "error";
  t.after(() => { delete process.env.KIMI_FAKE_MODE; });
  const adapter = new KimiHeadlessAdapter({ instanceId: "ai_kimi2", command: process.execPath, baseArgs: [FAKE] });
  await adapter.probe();
  await adapter.createSession({ sessionId: "ses_k2" });
  const result = await adapter.send({ sessionId: "ses_k2", text: "ping" });
  assert.equal(result.state, "failed");
  assert.match(result.error.message, /No model configured/);
  await adapter.dispose();
});

test("Kimi 能力声明覆盖全部键且无 supported", () => {
  const decl = kimiHeadlessCapabilityDeclaration({ protocolDetected: true });
  assert.deepEqual(Object.keys(decl).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl), []);
});
