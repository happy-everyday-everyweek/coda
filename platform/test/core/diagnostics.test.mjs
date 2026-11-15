/** CORE-114：有界日志、脱敏、健康检查、导出。 */
import test from "node:test";
import assert from "node:assert/strict";

import { createDiagnostics, redact, redactValue } from "../../core/diagnostics.mjs";
import { fixedClock } from "../../fixtures/core/helpers.mjs";

test("日志有界：超过 maxEntries 丢弃最旧条目", () => {
  const diagnostics = createDiagnostics({ clock: fixedClock(), maxEntries: 5 });
  for (let i = 0; i < 20; i += 1) diagnostics.log("info", `msg ${i}`);
  const entries = diagnostics.entries({ limit: 100 });
  assert.equal(entries.length, 5);
  assert.equal(entries.at(-1).message, "msg 19");
  assert.equal(entries[0].message, "msg 15");
  assert.equal(diagnostics.health().maxEntries, 5);
});

test("脱敏覆盖 Token / 密钥 / Authorization / 邮箱", () => {
  assert.equal(redact("Authorization: Bearer abcdefghijk").includes("abcdefghijk"), false);
  assert.equal(redact("sk-abcdefghijklmnop").includes("abcdefghijklmnop"), false);
  assert.equal(redact("api_key=abcd1234").includes("abcd1234"), false);
  assert.equal(redact("password: hunter2").includes("hunter2"), false);
  assert.equal(redact("user@example.com").includes("user@example.com"), false);
  assert.equal(redact("https://user:pass@host/x").includes("pass@"), false);
  assert.equal(redact("普通文本保持原样"), "普通文本保持原样");

  const deep = redactValue({
    nested: { password: "hunter2", apiToken: "zzz" },
    list: ["Bearer aaaaaaaa"],
    ok: 1,
  });
  assert.equal(deep.nested.password, "[REDACTED]");
  assert.equal(deep.nested.apiToken, "[REDACTED]");
  assert.equal(deep.list[0], "Bearer [REDACTED]");
  assert.equal(deep.ok, 1);
});

test("导出诊断不泄漏密钥，记录错误会降低健康状态", () => {
  const diagnostics = createDiagnostics({ clock: fixedClock(), maxEntries: 20 });
  diagnostics.log("info", "starting", { token: "sk-verysecretvalue", nested: { password: "p" } });
  assert.equal(diagnostics.health().ok, true);
  diagnostics.recordError(new Error("boom sk-verysecretvalue"));
  const text = diagnostics.exportJSON();
  assert.equal(text.includes("sk-verysecretvalue"), false);
  assert.equal(text.includes("verysecretvalue"), false);
  assert.ok(text.includes("starting"));
  assert.equal(diagnostics.health().ok, false);
  assert.equal(diagnostics.errors({ limit: 10 }).length, 1);
});

test("健康检查注册与执行", async () => {
  const diagnostics = createDiagnostics({ clock: fixedClock() });
  diagnostics.registerCheck("always-ok", () => true);
  diagnostics.registerCheck("fails", () => {
    throw new Error("check failed");
  });
  const result = await diagnostics.runChecks();
  assert.equal(result.items.length, 2);
  assert.equal(result.items.find((i) => i.name === "always-ok").ok, true);
  assert.equal(result.items.find((i) => i.name === "fails").ok, false);
  assert.equal(result.ok, false);
  assert.equal(diagnostics.health().checks.ok, false);

  const bundle = diagnostics.exportBundle({ includeEntries: false, extra: { token: "sk-hiddenvalue" } });
  assert.equal(JSON.stringify(bundle).includes("sk-hiddenvalue"), false);
});
