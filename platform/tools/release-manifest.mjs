#!/usr/bin/env node
/**
 * 发布清单生成（QA-105 无签名发布流的基础）。
 *
 * 产出：`dist/release-manifest.json`
 *   { name, version, node, generatedAt, files:[{path,bytes,sha256}], webAssets:{assetVersion,files[]} }
 *
 * 用法：
 *   node tools/release-manifest.mjs            # 生成
 *   node tools/release-manifest.mjs --check    # 校验现有清单与磁盘一致
 *
 * 说明：签名与代码公证不在本脚本范围；签名变量由发布工作流注入（见 docs/RELEASE-NOTES-DRAFT.md）。
 */
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const platformDir = join(here, "..");
const OUT_DIR = join(platformDir, "dist");
const OUT_FILE = join(OUT_DIR, "release-manifest.json");

export const RELEASE_DIRS = ["adapters", "contracts", "core", "sync", "pa", "security", "clients", "tools"];
export const RELEASE_FILES = ["package.json", "README.md", "CONTRACT.md"];

export function hashFile(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function listFiles() {
  const files = [];
  const add = (full) => {
    const rel = relative(platformDir, full);
    files.push({ path: rel, bytes: statSync(full).size, sha256: hashFile(full) });
  };
  for (const file of RELEASE_FILES) {
    const full = join(platformDir, file);
    if (existsSync(full)) add(full);
  }
  for (const dir of RELEASE_DIRS) {
    const root = join(platformDir, dir);
    if (!existsSync(root)) continue;
    const walk = (current) => {
      for (const entry of readdirSync(current, { withFileTypes: true })) {
        const full = join(current, entry.name);
        if (entry.isDirectory()) walk(full);
        else if (entry.isFile()) add(full);
      }
    };
    walk(root);
  }
  return files.sort((a, b) => a.path.localeCompare(b.path));
}

export function buildReleaseManifest({ clock = () => new Date() } = {}) {
  const pkg = JSON.parse(readFileSync(join(platformDir, "package.json"), "utf8"));
  const webMarker = join(platformDir, "dist", "web", "version.json");
  let webAssets = null;
  if (existsSync(webMarker)) {
    const marker = JSON.parse(readFileSync(webMarker, "utf8"));
    webAssets = { assetVersion: marker.assetVersion, files: marker.files ?? [] };
  }
  return {
    schemaVersion: 1,
    name: pkg.name,
    version: pkg.version,
    node: ">=24",
    generatedAt: (clock() instanceof Date ? clock() : new Date(clock())).toISOString(),
    files: listFiles(),
    webAssets,
  };
}

export function checkReleaseManifest(manifestPath = OUT_FILE) {
  if (!existsSync(manifestPath)) return { ok: false, issues: ["manifest missing"] };
  const existing = JSON.parse(readFileSync(manifestPath, "utf8"));
  const current = buildReleaseManifest();
  const issues = [];
  const byPath = new Map(current.files.map((f) => [f.path, f]));
  for (const file of existing.files ?? []) {
    const actual = byPath.get(file.path);
    if (!actual) issues.push(`missing file: ${file.path}`);
    else if (actual.sha256 !== file.sha256) issues.push(`hash changed: ${file.path}`);
  }
  for (const file of current.files) {
    if (!(existing.files ?? []).some((f) => f.path === file.path)) issues.push(`new file not in manifest: ${file.path}`);
  }
  if (existing.version !== current.version) issues.push(`version changed: ${existing.version} → ${current.version}`);
  return { ok: issues.length === 0, issues };
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isMain) {
  if (process.argv.includes("--check")) {
    const result = checkReleaseManifest();
    if (!result.ok) { console.error(result.issues.join("\n")); process.exit(1); }
    console.log("release manifest is up to date");
  } else {
    mkdirSync(OUT_DIR, { recursive: true });
    const manifest = buildReleaseManifest();
    writeFileSync(OUT_FILE, JSON.stringify(manifest, null, 2) + "\n");
    console.log(`manifest: ${OUT_FILE}`);
    console.log(`files: ${manifest.files.length}`);
  }
}
