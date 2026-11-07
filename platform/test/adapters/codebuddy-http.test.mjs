/** CodeBuddy HTTP（AGT-117）：规范发现 + 诚实 BLOCKED。 */
import test from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";

import { CodeBuddyHttpAdapter, codebuddyHttpCapabilityDeclaration } from "../../adapters/codebuddy-http.mjs";
import { CAPABILITY_KEYS } from "../../contracts/capabilities.mjs";

const FAKE = join(import.meta.dirname, "..", "..", "fixtures", "adapters", "fake-codebuddy.mjs");

function fakeChild() {
  return { pid: 424242, kill() {}, on() {}, stdout: { on() {} }, stderr: { on() {}, setEncoding() {} } };
}

function noSupportedValues(value, path = "") {
  const bad = [];
  if (value === true) bad.push(path);
  else if (value && typeof value === "object") for (const [k, v] of Object.entries(value)) bad.push(...noSupportedValues(v, path ? `${path}.${k}` : path));
  return bad;
}

test("CodeBuddy HTTP：probe 检测 --serve，能力不声明 supported", async () => {
  const adapter = new CodeBuddyHttpAdapter({ instanceId: "ai_cbhttp", command: process.execPath, baseArgs: [FAKE] });
  const probe = await adapter.probe();
  assert.equal(probe.installed, true);
  assert.equal(adapter.version, "2.181.0-fake");
  assert.equal(adapter.probing.serveDetected, true);
  const decl = adapter.capabilities();
  assert.deepEqual(Object.keys(decl.capabilities).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl.capabilities), []);
  await adapter.dispose();
});

test("CodeBuddy HTTP：discoverOpenApi 抓规范，会话方法仍 BLOCKED", async () => {
  let calledUrl = null;
  const fetchImpl = async (url) => {
    calledUrl = url;
    return {
      ok: true,
      status: 200,
      clone() {
        return { json: async () => ({ openapi: "3.1.0", info: { title: "CodeBuddy Code API (Beta)" }, paths: { "/api/v1/health": {} } }) };
      },
    };
  };
  const adapter = new CodeBuddyHttpAdapter({
    instanceId: "ai_cbhttp2",
    command: process.execPath,
    baseArgs: [FAKE],
    fetchImpl,
    spawnImpl: () => fakeChild(),
    port: 8799,
  });
  await adapter.probe();
  const spec = await adapter.discoverOpenApi();
  assert.equal(spec.openapi.openapi, "3.1.0");
  assert.match(calledUrl, /\/api\/openapi\.json$/);
  const decl = adapter.capabilities();
  assert.equal(decl.capabilities.turn.startEndEvents, "experimental");
  assert.deepEqual(noSupportedValues(decl.capabilities), []);
  await assert.rejects(adapter.createSession(), (err) => err.code === "BLOCKED");
  await assert.rejects(adapter.send(), (err) => err.code === "BLOCKED");
  await adapter.dispose();
});

test("CodeBuddy HTTP 能力声明覆盖全部键", () => {
  const decl = codebuddyHttpCapabilityDeclaration({ openapiDiscovered: true });
  assert.deepEqual(Object.keys(decl).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl), []);
});
