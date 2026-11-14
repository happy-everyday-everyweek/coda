/** SEC-101 / SEC-001 / SEC-006：秘密文件权限、生命周期、脱敏与诊断导出过滤。 */
import test from "node:test";
import assert from "node:assert/strict";
import { chmodSync, existsSync, mkdirSync, readFileSync, statSync, symlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import {
  assertNoSecrets,
  createSecretStore,
  exportDiagnostics,
  loadSecrets,
  maskSecret,
  readSecretFile,
  scanSecrets,
  writeSecretFile,
} from "../../security/secrets.mjs";
import { containsSecret, findSecretKinds, redact, redactUrl, redactValue } from "../../security/redact.mjs";
import { makeTmpDir } from "../sync/helpers.mjs";

const OPENAI_KEY = "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789";

test("秘密文件：目录 0700、文件 0600、原子写、拒绝过宽权限与符号链接", (t) => {
  const dir = makeTmpDir(t);
  const file = join(dir, "secrets", "secrets.json");
  const written = writeSecretFile(file, JSON.stringify({ OPENAI_API_KEY: OPENAI_KEY }));
  assert.equal(written.mode, 0o600);
  assert.equal(statSync(join(dir, "secrets")).mode & 0o777, 0o700);
  assert.equal(statSync(file).mode & 0o777, 0o600);
  assert.equal(JSON.parse(readSecretFile(file)).OPENAI_API_KEY, OPENAI_KEY);

  chmodSync(file, 0o644);
  assert.throws(() => readSecretFile(file), (err) => err.code === "INSECURE_SECRET_PERMISSIONS");
  assert.equal(JSON.parse(readSecretFile(file, { allowInsecure: true })).OPENAI_API_KEY, OPENAI_KEY);

  const target = join(dir, "real.json");
  writeSecretFile(target, "{}");
  const link = join(dir, "link.json");
  symlinkSync(target, link);
  assert.throws(() => readSecretFile(link), (err) => err.code === "SYMLINK_SECRET_PATH");
});

test("loadSecrets：支持文件、目录与 store 格式；返回只读映射", (t) => {
  const dir = makeTmpDir(t);
  const flatFile = join(dir, "flat.json");
  writeSecretFile(flatFile, JSON.stringify({ API_KEY: "abc" }));
  const flat = loadSecrets(flatFile);
  assert.equal(flat.API_KEY, "abc");
  assert.throws(() => {
    flat.API_KEY = "mutated";
  }, TypeError);

  const secretDir = join(dir, "store");
  const store = createSecretStore({ dir: secretDir });
  store.set("PROVIDER_TOKEN", "token-value");
  const fromDir = loadSecrets(secretDir);
  assert.equal(fromDir.PROVIDER_TOKEN, "token-value");
  store.close();
});

test("秘密生命周期：过期、轮换、GC、诊断只给元数据、销毁", (t) => {
  const dir = makeTmpDir(t);
  let now = Date.parse("2026-01-01T00:00:00.000Z");
  const store = createSecretStore({ dir: join(dir, "secrets"), clock: () => now });
  store.set("API_KEY", "value-1", { tags: ["prod"] });
  store.set("SHORT_LIVED", "value-2", { expiresAt: new Date(now + 1_000).toISOString() });
  assert.equal(store.get("API_KEY"), "value-1");
  assert.equal(store.has("SHORT_LIVED"), true);

  const metadata = store.list();
  assert.equal(metadata.length, 2);
  assert.ok(!JSON.stringify(metadata).includes("value-1"));
  assert.equal(maskSecret("value-1234567890"), "val…7890");
  assert.equal(maskSecret("short"), "•••••");

  now += 2_000;
  assert.equal(store.get("SHORT_LIVED"), null);
  assert.deepEqual(store.gc(), []);

  const rotated = store.rotate("API_KEY", "value-3");
  assert.ok(rotated.rotatedAt);
  assert.equal(store.get("API_KEY"), "value-3");

  const diagnostics = JSON.stringify(store.diagnostics());
  assert.ok(!diagnostics.includes("value-3"));
  assert.equal(store.diagnostics().mode, 0o600);

  store.destroy();
  assert.equal(existsSync(join(dir, "secrets", "secrets.json")), false);
});

test("日志脱敏：常见凭据格式、对象敏感键、循环引用与截断", () => {
  const text = [
    `Authorization: Bearer ${"a".repeat(24)}`,
    `openai=${OPENAI_KEY}`,
    "github=ghp_abcdefghijklmnopqrstuvwxyz0123456789",
    "aws=AKIAIOSFODNN7EXAMPLE",
    "url=https://user:password@example.com/path?token=supersecret&x=1",
    "密码: hunter2",
    "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkq\n-----END PRIVATE KEY-----",
  ].join("\n");
  const cleaned = redact(text);
  for (const secret of [OPENAI_KEY, "ghp_abcdefghijklmnopqrstuvwxyz0123456789", "AKIAIOSFODNN7EXAMPLE", "hunter2"]) {
    assert.ok(!cleaned.includes(secret), `未脱敏: ${secret}`);
  }
  assert.ok(cleaned.includes("[REDACTED:"));
  assert.ok(cleaned.includes("https://"));
  assert.ok(cleaned.includes("example.com"));
  assert.ok(!cleaned.includes("user:password"));
  assert.ok(!cleaned.includes("supersecret"));
  assert.equal(containsSecret(text), true);
  assert.equal(containsSecret("普通文本，没有凭据"), false);
  assert.ok(findSecretKinds(text).includes("openai-key"));
  const redactedUrl = redactUrl("https://user:pass@example.com/x?token=abc");
  assert.ok(!redactedUrl.includes("user"));
  assert.ok(!redactedUrl.includes("pass"));
  assert.ok(!redactedUrl.includes("abc"));
  assert.ok(redactedUrl.includes("REDACTED"));

  const circular = { apiKey: OPENAI_KEY, nested: { token: "abcdef0123456789" }, list: [1, 2] };
  circular.self = circular;
  const filtered = redactValue(circular).value;
  assert.ok(!JSON.stringify(filtered).includes(OPENAI_KEY));
  assert.equal(filtered.self, "[Circular]");
  assert.equal(typeof filtered.nested.token, "string");
  assert.ok(filtered.nested.token.includes("REDACTED"));

  const long = redactValue({ text: "x".repeat(500) }, { maxString: 100 });
  assert.match(long.value.text, /TRUNCATED/);

  assert.throws(() => assertNoSecrets({ text: OPENAI_KEY }), (err) => err.code === "SECRET_LEAK");
  assert.equal(assertNoSecrets({ text: "nothing secret" }), true);
  assert.ok(scanSecrets({ nested: { value: OPENAI_KEY } }).some((finding) => finding.kind === "openai-key"));
});

test("SEC-006：诊断导出必须确认；大载荷截断但保留关联 ID；秘密被过滤", () => {
  assert.throws(() => exportDiagnostics({ entries: [] }), (err) => err.code === "EXPORT_NOT_CONFIRMED");

  const entries = [
    { id: "evt_tool_1", kind: "tool", data: { command: "cat", output: "x".repeat(10_000) } },
    { id: "evt_tool_2", kind: "tool", data: { apiKey: OPENAI_KEY, note: "ok" } },
  ];
  const exported = exportDiagnostics({ entries, confirmed: true, maxStringBytes: 128 });
  assert.deepEqual(exported.correlationIds, ["evt_tool_1", "evt_tool_2"]);
  const serialized = JSON.stringify(exported);
  assert.ok(!serialized.includes(OPENAI_KEY));
  assert.ok(exported.truncation.entries >= 1);
  assert.equal(exported.redaction.applied, true);
  assert.match(serialized, /TRUNCATED/);
  assert.equal(exported.entries[0].id, "evt_tool_1");
});
