/** AGT-106：fixture 录制/脱敏/回放与失败场景库。 */
import test from "node:test";
import assert from "node:assert/strict";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import {
  FixtureError,
  ReplayAdapter,
  auditFixture,
  buildFixtureIndex,
  createFixture,
  createRecorder,
  loadFixture,
  redact,
  redactText,
  replayFixture,
  validateFixture,
} from "../../adapters/fixtures.mjs";

const FIXTURE_DIR = fileURLToPath(new URL("../../fixtures/adapters/", import.meta.url));
const HANDWRITTEN = ["acp-happy-path.fixture.json", "acp-stop-denied-permission.fixture.json", "acp-protocol-error.fixture.json"];

test("手写 fixture 全部合法，并明确标注来源为人工构造", () => {
  const fixtures = HANDWRITTEN.map((name) => loadFixture(join(FIXTURE_DIR, name)));
  for (const fixture of fixtures) {
    assert.equal(fixture.fixtureVersion, 1);
    assert.equal(fixture.source, "handwritten");
    assert.match(fixture.sourceNote, /人工构造/);
    assert.equal(fixture.redaction.applied, true);
    assert.ok(fixture.events.length > 0);
    assert.deepEqual(validateFixture(fixture), fixture);
    assert.equal(auditFixture(fixture).ok, true);
  }
  const index = buildFixtureIndex(fixtures);
  assert.equal(index.length, 3);
  // 人工构造的 fixture 不能证明真实支持
  assert.ok(index.every((entry) => entry.provesRealSupport === false));
  assert.ok(index.some((entry) => entry.kinds.includes("interaction")));
  assert.ok(index.some((entry) => entry.kinds.includes("error")));
});

test("脱敏覆盖 token / 手机号 / 邮箱 / home 路径", () => {
  const text = [
    "Authorization: Bearer sk-abcdefghijklmnop",
    "OPENAI_API_KEY=sk-1234567890abcdef",
    "phone 13800138000",
    "mail dev@example.com",
    "cwd /root/work/demo",
    "win C:\\Users\\alice\\proj",
  ].join("\n");
  const result = redactText(text);
  assert.ok(!result.text.includes("sk-abcdefghijklmnop"));
  assert.ok(!result.text.includes("sk-1234567890abcdef"));
  assert.ok(!result.text.includes("13800138000"));
  assert.ok(!result.text.includes("dev@example.com"));
  assert.ok(!result.text.includes("/root/"));
  assert.ok(!result.text.includes("alice"));
  assert.ok(result.text.includes("/home/[REDACTED_USER]/work/demo"));
  assert.ok(result.applied.length >= 4, `applied rules: ${result.applied.join(",")}`);
  assert.equal(redact("ghp_" + "a".repeat(24)).includes("ghp_"), false);
});

test("录制器只接受统一事件，且落盘内容已脱敏", () => {
  const recorder = createRecorder({ id: "acp-test", provider: "acp-generic", source: "recording" });
  recorder.record({
    kind: "message",
    sessionId: "ses_rec",
    seq: 1,
    ts: "2026-11-07T00:00:00.000Z",
    payload: { role: "assistant", text: "token sk-1234567890abcdef in /root/secret", delta: true },
  });
  assert.throws(
    () => recorder.record({ kind: "raw.upstream.frame", sessionId: "ses_rec", seq: 2, payload: {} }),
    (err) => err instanceof FixtureError && /invalid kind/.test(err.issues[0].message),
  );
  assert.throws(() => recorder.record({ kind: "message", seq: 2, payload: {} }), (err) => err instanceof FixtureError);

  const fixture = recorder.finish();
  assert.equal(fixture.source, "recording");
  assert.equal(fixture.redaction.applied, true);
  const dumped = JSON.stringify(fixture);
  assert.equal(dumped.includes("sk-1234567890abcdef"), false);
  assert.equal(dumped.includes("/root/"), false);
  assert.equal(auditFixture(fixture).ok, true);
  assert.equal(recorder.size(), 1);
  assert.throws(() => recorder.record({ kind: "message", sessionId: "s", seq: 3 }), /already finished/);
});

test("回放：重新编号、可覆盖 sessionId、保持 payload", () => {
  const fixture = loadFixture(join(FIXTURE_DIR, "acp-happy-path.fixture.json"));
  const events = replayFixture(fixture, { sessionId: "ses_replay" });
  assert.equal(events.length, fixture.events.length);
  assert.ok(events.every((e) => e.sessionId === "ses_replay"));
  assert.deepEqual(
    events.map((e) => e.seq),
    fixture.events.map((_, index) => index + 1),
  );
  assert.equal(events[0].payload.providerSessionId, "acp_sess_fixture_1");
  // 原 fixture 不被改写
  assert.equal(fixture.events[0].sessionId, "ses_fixture_happy");
});

test("ReplayAdapter 只回放，不假装能发消息", async () => {
  const fixture = loadFixture(join(FIXTURE_DIR, "acp-happy-path.fixture.json"));
  const adapter = new ReplayAdapter({ fixture });
  const probe = await adapter.probe();
  assert.equal(probe.installed, true);
  assert.equal(probe.details.fixtureId, "acp-happy-path");

  const seen = [];
  adapter.onEvent((event) => seen.push(event));
  const emitted = await adapter.play({ sessionId: "ses_play" });
  assert.equal(emitted.length, fixture.events.length);
  assert.ok(seen.some((e) => e.kind === "message" && e.payload.delta === true));
  assert.ok(seen.every((e) => e.sessionId === "ses_play" || e.kind === "session"));
  await assert.rejects(adapter.send({ sessionId: "ses_play", text: "x" }), (err) => err.code === "UNSUPPORTED_CAPABILITY");
  await adapter.dispose();
});

test("非法 fixture：seq 必须严格递增，recording 必须已脱敏", () => {
  assert.throws(
    () =>
      createFixture({
        id: "bad-seq",
        provider: "p",
        events: [
          { kind: "status", sessionId: "s", seq: 2, ts: "2026-11-07T00:00:00.000Z", payload: {} },
          { kind: "status", sessionId: "s", seq: 1, ts: "2026-11-07T00:00:01.000Z", payload: {} },
        ],
      }),
    /seq must strictly increase/,
  );
  assert.throws(
    () =>
      createFixture({
        id: "unredacted",
        provider: "p",
        source: "recording",
        redaction: { applied: false, rules: [] },
        events: [{ kind: "status", sessionId: "s", seq: 1, ts: "2026-11-07T00:00:00.000Z", payload: {} }],
      }),
    /must be redacted/,
  );
  assert.throws(() => createFixture({ id: "empty", provider: "p", events: [] }), /non-empty array/);
});
