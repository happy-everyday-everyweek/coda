/** INST-107：Web UI 本地资产安装/校验。 */
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { WEB_ASSET_VERSION, checkWebAssets, installWebAssets, listWebAssets } from "../../tools/install-web-assets.mjs";

test("Web 资产安装：复制 public 并生成 version.json", () => {
  const out = mkdtempSync(join(tmpdir(), "coda-webassets-"));
  try {
    assert.ok(listWebAssets().length >= 2);
    const manifest = installWebAssets(out);
    assert.equal(manifest.assetVersion, WEB_ASSET_VERSION);
    assert.ok(manifest.files.some((f) => f.path === "index.html"));
    const marker = JSON.parse(readFileSync(join(out, "version.json"), "utf8"));
    assert.equal(marker.assetVersion, WEB_ASSET_VERSION);
    assert.ok(checkWebAssets(out).ok);
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test("Web 资产校验：篡改后失败", () => {
  const out = mkdtempSync(join(tmpdir(), "coda-webassets-"));
  try {
    installWebAssets(out);
    const index = join(out, "index.html");
    writeFileSync(index, `${readFileSync(index, "utf8")}<!-- tampered -->`);
    const result = checkWebAssets(out);
    assert.equal(result.ok, false);
    assert.ok(result.issues.some((i) => /hash mismatch index\.html/.test(i)));
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});
