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

test("CodeBuddy HTTP：discoverOpenApi + 派发 job + SSE 转录 + 终态", async () => {
  const calls = [];
  function jsonResponse(status, body) {
    const text = JSON.stringify(body);
    return {
      ok: status < 400,
      status,
      text: async () => text,
      clone() { return { json: async () => body }; },
      body: null,
    };
  }
  function sseResponse(records) {
    const payload = `${records.map((r) => `data: ${JSON.stringify(r)}\n\n`).join("")}`;
    const encoder = new TextEncoder();
    let sent = false;
    return {
      ok: true,
      status: 200,
      text: async () => payload,
      clone() { return { json: async () => null }; },
      body: {
        getReader() {
          return {
            async read() {
              if (sent) return { done: true, value: undefined };
              sent = true;
              return { done: false, value: encoder.encode(payload) };
            },
          };
        },
      },
    };
  }
  const fetchImpl = async (url, init = {}) => {
    calls.push(`${init.method ?? "GET"} ${url}`);
    if (url.endsWith("/api/openapi.json")) return jsonResponse(200, { openapi: "3.1.0", info: { title: "CodeBuddy" }, paths: {} });
    if (url.endsWith("/api/v1/jobs") && (init.method ?? "GET") === "POST") {
      return jsonResponse(200, { data: { id: "job_1", sessionId: "sess_1", state: "working" } });
    }
    if (url.endsWith("/api/v1/jobs/job_1/stream")) {
      return sseResponse([
        { type: "assistant.message", text: "po" },
        { type: "assistant.message.delta", text: "ng" },
        { type: "result.done" },
      ]);
    }
    if (url.endsWith("/api/v1/jobs/job_1") && (init.method ?? "GET") === "GET") {
      return jsonResponse(200, { data: { id: "job_1", state: "done" } });
    }
    if (url.endsWith("/api/v1/jobs/job_1/reply") && init.method === "POST") return jsonResponse(200, { data: { delivered: true, saved: false, notice: "ok" } });
    if (url.endsWith("/api/v1/jobs/job_1/stop") && init.method === "POST") return jsonResponse(200, { data: { stopped: true } });
    return jsonResponse(404, { error: "not found" });
  };
  const adapter = new CodeBuddyHttpAdapter({
    instanceId: "ai_cbhttp2",
    command: process.execPath,
    baseArgs: [FAKE],
    fetchImpl,
    spawnImpl: () => fakeChild(),
    port: 8799,
    workspace: "/work/demo",
    apiKey: "test-key",
  });
  await adapter.probe();
  const spec = await adapter.discoverOpenApi();
  assert.equal(spec.openapi.openapi, "3.1.0");

  const events = [];
  adapter.emit = (event) => events.push(event);
  await adapter.createSession({ sessionId: "ses_cb" });
  const result = await adapter.send({ sessionId: "ses_cb", text: "ping" });
  assert.equal(result.state, "succeeded");
  assert.equal(result.text, "pong");
  assert.equal(result.upstreamSessionId, "job_1");
  assert.ok(events.some((e) => e.kind === "message" && e.payload.text === "po"));
  assert.ok(calls.some((c) => c === "POST http://127.0.0.1:8799/api/v1/jobs"));

  const steer = await adapter.steer({ sessionId: "ses_cb", text: "more" });
  assert.equal(steer.delivered, true);
  const stopped = await adapter.interrupt({ sessionId: "ses_cb" });
  assert.equal(stopped.stopped, true);
  assert.equal(stopped.trust, "upstream-confirmed");
  await adapter.dispose();
});

test("CodeBuddy HTTP 能力声明覆盖全部键", () => {
  const decl = codebuddyHttpCapabilityDeclaration({ openapiDiscovered: true });
  assert.deepEqual(Object.keys(decl).sort(), [...CAPABILITY_KEYS].sort());
  assert.deepEqual(noSupportedValues(decl), []);
});
