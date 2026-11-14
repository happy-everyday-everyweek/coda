import test from "node:test";
import assert from "node:assert/strict";
import { makeId } from "../../contracts/ids.mjs";
import {
  PA_DEFAULT_PROFILE,
  PA_MESSAGE_ROLES,
  PA_SCHEMA_VERSION,
  PA_SINGLE_SESSION,
  assertSinglePaSession,
  compareHlc,
  comparePaMessages,
  createHybridClock,
  createPaMessage,
  createPaProfile,
  formatHlc,
  parseHlc,
  validatePaMessage,
  validatePaProfile,
} from "../../pa/schema.mjs";
import { FIXED_MS, hlcFor, makeMessage } from "./helpers.mjs";

test("PA-101: PAProfile 默认值、独立字段与秘密字段拒绝", () => {
  const profile = createPaProfile();
  assert.match(profile.id, /^pa_[0-9a-f]{32}$/);
  assert.equal(profile.name, PA_DEFAULT_PROFILE.name);
  assert.equal(profile.schemaVersion, PA_SCHEMA_VERSION);
  assert.deepEqual(validatePaProfile(profile), []);
  assert.throws(() => createPaProfile({ name: "   " }), /name is required/);
  assert.throws(() => createPaProfile({ apiKey: "sk-should-not-be-here" }), /secrets/);
  assert.throws(() => createPaProfile({ id: "ses_wrong_prefix" }), /pa_ prefixed ID/);
  assert.deepEqual(validatePaProfile(null), ["profile must be an object"]);
});

test("PA-101: PAMessage 单会话字段与附件引用", () => {
  const profile = createPaProfile();
  const artifactId = makeId("art");
  const message = makeMessage({
    paId: profile.id,
    attachments: [{ artifactId, name: "note.txt", size: 3 }],
  });
  assert.match(message.id, /^pm_[0-9a-f]{32}$/);
  assert.equal(message.paId, profile.id);
  assert.equal(message.sourceNode, "dev_a");
  assert.equal(message.role, "user");
  assert.equal(message.seq, 1);
  assert.equal(message.replyTo, null);
  assert.equal(message.attachments[0].artifactId, artifactId);
  assert.equal(message.attachments[0].mimeType, "application/octet-stream");
  assert.deepEqual(validatePaMessage(message), []);
  assert.deepEqual(PA_MESSAGE_ROLES, ["user", "assistant", "system"]);
  assert.equal(PA_SINGLE_SESSION, true);
});

test("PA-101: PAMessage 校验拒绝错误来源、角色、序号、replyTo 与 HLC 节点", () => {
  const profile = createPaProfile();
  const base = makeMessage({ paId: profile.id });
  const cases = [
    ["sourceNode", { ...base, sourceNode: "node_a" }, /sourceNode/],
    ["paId", { ...base, paId: "ses_other" }, /paId/],
    ["role", { ...base, role: "tool" }, /role/],
    ["seq", { ...base, seq: 0 }, /seq/],
    ["replyTo", { ...base, replyTo: base.id }, /itself/],
    ["hlc", { ...base, hlc: hlcFor("dev_b") }, /hlc node/],
    ["attachment", { ...base, attachments: [{ artifactId: "x", name: "a", size: 1 }] }, /art_ ID/],
    ["ts", { ...base, ts: "not-a-date" }, /ts/],
  ];
  for (const [label, candidate, pattern] of cases) {
    const errors = validatePaMessage(candidate);
    assert.ok(errors.some((error) => pattern.test(error)), `${label}: ${errors.join(" | ")}`);
  }
  assert.deepEqual(validatePaMessage({ ...base, replyTo: "pm_other" }), []);
  assert.ok(validatePaMessage({ ...base, content: "   " }).some((error) => /must not be empty/.test(error)));
});

test("PA-101: 确定性排序按 HLC 物理时间、计数器、节点、消息 ID", () => {
  const profile = createPaProfile();
  const a = makeMessage({ paId: profile.id, node: "dev_a", id: "pm_a", seq: 1, physical: FIXED_MS, counter: 0 });
  const b = makeMessage({ paId: profile.id, node: "dev_b", id: "pm_b", seq: 1, physical: FIXED_MS, counter: 0 });
  const c = makeMessage({ paId: profile.id, node: "dev_a", id: "pm_c", seq: 2, physical: FIXED_MS, counter: 1 });
  const sorted = [c, b, a].sort(comparePaMessages);
  assert.deepEqual(sorted.map((message) => message.id), ["pm_a", "pm_b", "pm_c"]);
  assert.ok(compareHlc(a.hlc, b.hlc) < 0);
  assert.ok(compareHlc(b.hlc, c.hlc) < 0);
});

test("PA-101: 混合逻辑时钟单调、可观察远端且不因时钟回拨倒退", () => {
  let wall = 1000;
  const clock = createHybridClock({ nodeId: "dev_a", now: () => wall });
  const first = clock.now();
  assert.equal(parseHlc(first).counter, 0);
  const second = clock.now();
  assert.ok(compareHlc(second, first) > 0);
  wall = 2000;
  const third = clock.now();
  assert.equal(parseHlc(third).physical, 2000);
  assert.equal(parseHlc(third).counter, 0);

  const remote = formatHlc({ physical: 5000, counter: 7, nodeId: "dev_b" });
  clock.observe(remote);
  wall = 1500; // 本机时钟回拨，但仍必须大于远端 HLC
  const afterObserve = clock.now();
  assert.ok(compareHlc(afterObserve, remote) > 0);
  assert.equal(parseHlc(afterObserve).physical, 5000);
  assert.equal(parseHlc(afterObserve).counter, 8);
  assert.throws(() => formatHlc({ physical: 1, counter: 0, nodeId: "bad:node" }), /nodeId/);
  assert.throws(() => parseHlc("not-an-hlc"), /invalid HLC/);
});

test("PA-101: 单会话不变量拒绝混入另一个 PA 的消息", () => {
  const profile = createPaProfile();
  const message = makeMessage({ paId: profile.id });
  assert.equal(assertSinglePaSession(profile.id, [message]), true);
  const foreign = { ...message, paId: createPaProfile().id, id: makeId("pm") };
  assert.throws(() => assertSinglePaSession(profile.id, [message, foreign]), /single-session/);
  assert.throws(() => assertSinglePaSession("ses_x", [message]), /pa_ profile ID/);
});

test("PA-101: 迁移 ID 映射对同一旧 ID 稳定", async () => {
  const { legacyIdToMessageId } = await import("../../pa/schema.mjs");
  const uuid = "11111111-1111-4111-8111-111111111111";
  assert.equal(legacyIdToMessageId(uuid, "dev_legacy"), legacyIdToMessageId(uuid, "dev_legacy"));
  assert.match(legacyIdToMessageId(uuid, "dev_legacy"), /^pm_[0-9a-f]{32}$/);
  assert.equal(legacyIdToMessageId("local-1", "dev_legacy"), legacyIdToMessageId("local-1", "dev_legacy"));
  assert.match(legacyIdToMessageId("local-1", "dev_legacy"), /^pm_[0-9a-f]{32}$/);
  assert.notEqual(legacyIdToMessageId("local-1", "dev_a"), legacyIdToMessageId("local-1", "dev_b"));
  assert.throws(() => createPaMessage({}), /invalid PA message/);
});
