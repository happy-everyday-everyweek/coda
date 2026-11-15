/** AGT-108：审批/提问归一化、原生 ID、过期、子 Agent 来源、审计。 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  InteractionError,
  InteractionRegistry,
  interactionEventPayload,
  normalizePermissionRequest,
  normalizeQuestion,
} from "../../adapters/interactions.mjs";

const permission = (overrides = {}) =>
  normalizePermissionRequest({
    sessionId: "ses_int",
    nativeRequestId: "42",
    title: "Run npm test",
    toolCall: { toolCallId: "tc_1", kind: "execute", title: "Run npm test" },
    options: [
      { optionId: "allow-once", label: "Allow once", kind: "allow_once" },
      { optionId: "reject-once", label: "Reject", kind: "reject_once" },
    ],
    ttlMs: 60_000,
    ...overrides,
  });

test("权限请求保留原生请求 ID 与原生选项 ID", () => {
  const interaction = permission();
  assert.equal(interaction.kind, "permission");
  assert.equal(interaction.nativeRequestId, "42");
  assert.deepEqual(
    interaction.options.map((o) => o.optionId),
    ["allow-once", "reject-once"],
  );
  assert.equal(interaction.options[0].label, "Allow once");
  assert.equal(interaction.state, "pending");
  assert.equal(interaction.freeText, false);
  assert.ok(Date.parse(interaction.expiresAt) > Date.now());
});

test("回答只用原生 optionId：文案不能当作答案", () => {
  const registry = new InteractionRegistry();
  const interaction = registry.createPermission({
    sessionId: "ses_int",
    nativeRequestId: "7",
    options: [
      { optionId: "allow-once", label: "Allow once" },
      { optionId: "reject-once", label: "Reject" },
    ],
  });
  assert.throws(
    () => registry.answer(interaction.interactionId, { optionId: "Allow once" }),
    (err) => err instanceof InteractionError && err.code === "UNKNOWN_OPTION",
  );
  assert.equal(interaction.state, "pending", "a rejected answer must not consume the request");

  const { interaction: answered, answer } = registry.answer(interaction.interactionId, {
    optionId: "allow-once",
    by: "user",
  });
  assert.equal(answered.state, "answered");
  assert.deepEqual(answer, { optionId: "allow-once", text: null });
  assert.deepEqual(registry.audit.at(-1).choice, { optionId: "allow-once", text: null });
  assert.equal(registry.audit.at(-1).by, "user");
});

test("重复回答被拒绝（ALREADY_ANSWERED）", () => {
  const registry = new InteractionRegistry();
  const interaction = registry.createPermission({ sessionId: "ses_int", options: [{ optionId: "allow-once" }] });
  registry.answer(interaction.interactionId, { optionId: "allow-once" });
  assert.throws(
    () => registry.answer(interaction.interactionId, { optionId: "allow-once" }),
    (err) => err.code === "ALREADY_ANSWERED",
  );
});

test("过期请求不可误答（EXPIRED）", () => {
  const registry = new InteractionRegistry();
  const expired = registry.add(permission({ expiresAt: new Date(Date.now() - 5000).toISOString() }));
  assert.throws(
    () => registry.answer(expired.interactionId, { optionId: "allow-once" }),
    (err) => err.code === "EXPIRED",
  );
  assert.equal(expired.state, "expired");
  assert.equal(registry.audit.length, 0, "expired request must not record a decision");
});

test("expireDue / cancelForSession 关闭未决请求（回合停止语义）", () => {
  const registry = new InteractionRegistry();
  const stale = registry.add(permission({ expiresAt: new Date(Date.now() - 1).toISOString() }));
  const pending = registry.add(permission({ nativeRequestId: "43" }));
  assert.equal(registry.expireDue().length, 1);
  assert.equal(stale.state, "expired");

  const cancelled = registry.cancelForSession("ses_int", "turn-stopped");
  assert.equal(cancelled.length, 1);
  assert.equal(pending.state, "cancelled");
  assert.equal(pending.stateReason, "turn-stopped");
  assert.equal(registry.pending().length, 0);
});

test("结构化提问：选项 + 自由文本；无选项时降级为自由文本", () => {
  const registry = new InteractionRegistry();
  const withOptions = registry.createQuestion({
    sessionId: "ses_int",
    title: "Which file?",
    options: [
      { optionId: "a", label: "src/a.mjs" },
      { optionId: "b", label: "src/b.mjs" },
    ],
    allowFreeText: true,
  });
  assert.equal(withOptions.freeText, true);
  const chosen = registry.answer(withOptions.interactionId, { optionId: "b", text: "and b" });
  assert.deepEqual(chosen.answer, { optionId: "b", text: "and b" });

  const freeOnly = registry.createQuestion({ sessionId: "ses_int", title: "Describe" });
  assert.equal(freeOnly.options.length, 0);
  assert.equal(freeOnly.freeText, true);
  const text = registry.answer(freeOnly.interactionId, { text: "a plain answer" });
  assert.deepEqual(text.answer, { optionId: null, text: "a plain answer" });

  const strict = normalizeQuestion({
    sessionId: "ses_int",
    options: [{ optionId: "x" }],
    allowFreeText: false,
  });
  assert.equal(strict.freeText, false);
});

test("子 Agent 来源必须可见，不能伪装成主 Agent", () => {
  const interaction = permission({
    source: { origin: "subagent", subagentId: "sa_1" },
  });
  assert.equal(interaction.source.origin, "subagent");
  assert.equal(interaction.source.subagentId, "sa_1");
  const payload = interactionEventPayload(interaction);
  assert.equal(payload.source.origin, "subagent");
  assert.equal(payload.nativeRequestId, "42");
  assert.deepEqual(
    payload.options.map((o) => o.optionId),
    ["allow-once", "reject-once"],
  );
  assert.equal(payload.tool.toolCallId, "tc_1");

  assert.throws(
    () => permission({ source: { origin: "hacker" } }),
    (err) => err instanceof InteractionError && err.code === "INVALID",
  );
});

test("上游响应形状：selected / cancelled", () => {
  const registry = new InteractionRegistry();
  const selected = registry.createPermission({ sessionId: "ses_int", options: [{ optionId: "allow-once" }] });
  registry.answer(selected.interactionId, { optionId: "allow-once" });
  assert.deepEqual(registry.upstreamOutcome(selected), { outcome: { outcome: "selected", optionId: "allow-once" } });

  const cancelled = registry.createPermission({ sessionId: "ses_int", options: [{ optionId: "allow-once" }] });
  assert.deepEqual(registry.upstreamOutcome(cancelled), { outcome: { outcome: "cancelled" } });
});

test("请求必须有原生选项，否则归一化直接失败", () => {
  assert.throws(
    () => normalizePermissionRequest({ sessionId: "ses_int", options: [] }),
    (err) => err.code === "INVALID",
  );
  assert.throws(() => normalizePermissionRequest({ options: [{ optionId: "a" }] }), (err) => err.code === "INVALID");
  assert.throws(
    () => normalizePermissionRequest({ sessionId: "ses_int", options: [{ label: "no id" }] }),
    (err) => err.code === "INVALID",
  );
});
