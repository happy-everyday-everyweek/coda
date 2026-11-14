/** QA-105 支撑：发布清单生成与校验。 */
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { buildReleaseManifest, checkReleaseManifest, hashFile } from "../../tools/release-manifest.mjs";

test("发布清单：覆盖适配器/核心/契约等文件并带 sha256", () => {
  const manifest = buildReleaseManifest({ clock: () => new Date("2026-11-07T00:00:00Z") });
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.name, "coda-platform");
  // 门槛随实现增减微调，这里守住“覆盖多个子目录”的量级即可。
  assert.ok(manifest.files.length > 90, `文件过少: ${manifest.files.length}`);
  const dirs = new Set(manifest.files.map((f) => f.path.split("/")[0]));
  for (const dir of ["adapters", "contracts", "core", "clients", "tools"]) {
    assert.ok(dirs.has(dir), `清单缺少目录: ${dir}`);
  }
  assert.ok(manifest.files.some((f) => f.path === "adapters/index.mjs"));
  assert.ok(manifest.files.some((f) => f.path === "core/server.mjs"));
  assert.ok(manifest.files.some((f) => f.path === "package.json"));
  for (const file of manifest.files.slice(0, 5)) {
    assert.match(file.sha256, /^[a-f0-9]{64}$/);
    assert.ok(file.bytes > 0);
  }
});

test("发布清单校验：临时清单缺文件时报错", () => {
  const dir = mkdtempSync(join(tmpdir(), "coda-manifest-"));
  try {
    const manifest = buildReleaseManifest();
    const path = join(dir, "release-manifest.json");
    writeFileSync(path, JSON.stringify({ ...manifest, files: manifest.files.slice(0, 3) }));
    const result = checkReleaseManifest(path);
    assert.equal(result.ok, false);
    assert.ok(result.issues.some((i) => /new file not in manifest/.test(i)));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("hashFile 稳定", () => {
  const dir = mkdtempSync(join(tmpdir(), "coda-hash-"));
  try {
    const file = join(dir, "a.txt");
    writeFileSync(file, "hello");
    assert.equal(hashFile(file), hashFile(file));
    assert.match(hashFile(file), /^2cf24dba5fb0a30e/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
