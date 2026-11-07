#!/usr/bin/env node
/**
 * Web UI 本地资产安装/校验（INST-107）。
 *
 * 用法：
 *   node tools/install-web-assets.mjs --out <dir>   # 复制 clients/web/public + version.json
 *   node tools/install-web-assets.mjs --check <dir> # 校验已安装资产的哈希与版本
 *
 * 只做本地文件复制与校验；不联网、不涉及签名。
 */
import { createHash } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const platformDir = join(here, "..");
const publicDir = join(platformDir, "clients", "web", "public");
export const WEB_ASSET_VERSION = "0.1.0";

export function listWebAssets(dir = publicDir) {
  const files = [];
  const walk = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const full = join(current, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.isFile()) files.push(full);
    }
  };
  walk(dir);
  return files.sort();
}

export function hashFile(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

export function installWebAssets(outDir) {
  if (!existsSync(publicDir)) throw new Error(`missing web assets: ${publicDir}`);
  mkdirSync(outDir, { recursive: true });
  const files = listWebAssets().map((source) => {
    const rel = relative(publicDir, source);
    const dest = join(outDir, rel);
    mkdirSync(dirname(dest), { recursive: true });
    copyFileSync(source, dest);
    return { path: rel, bytes: statSync(dest).size, sha256: hashFile(dest) };
  });
  const manifest = { assetVersion: WEB_ASSET_VERSION, installedAt: new Date().toISOString(), files };
  writeFileSync(join(outDir, "version.json"), JSON.stringify(manifest, null, 2) + "\n");
  return manifest;
}

export function checkWebAssets(outDir) {
  const marker = join(outDir, "version.json");
  if (!existsSync(marker)) return { ok: false, issues: ["version.json missing"] };
  const manifest = JSON.parse(readFileSync(marker, "utf8"));
  const issues = [];
  if (manifest.assetVersion !== WEB_ASSET_VERSION) issues.push(`assetVersion ${manifest.assetVersion} !== ${WEB_ASSET_VERSION}`);
  for (const file of manifest.files ?? []) {
    const path = join(outDir, file.path);
    if (!existsSync(path)) { issues.push(`missing ${file.path}`); continue; }
    const hash = hashFile(path);
    if (hash !== file.sha256) issues.push(`hash mismatch ${file.path}`);
  }
  return { ok: issues.length === 0, issues, manifest };
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isMain) {
  const args = process.argv.slice(2);
  const outIndex = args.indexOf("--out");
  const checkIndex = args.indexOf("--check");
  if (outIndex >= 0 && args[outIndex + 1]) {
    const manifest = installWebAssets(args[outIndex + 1]);
    console.log(`installed ${manifest.files.length} assets → ${args[outIndex + 1]}`);
  } else if (checkIndex >= 0 && args[checkIndex + 1]) {
    const result = checkWebAssets(args[checkIndex + 1]);
    if (!result.ok) { console.error(result.issues.join("\n")); process.exit(1); }
    console.log("web assets OK");
  } else {
    console.error("usage: --out <dir> | --check <dir>");
    process.exit(2);
  }
}
