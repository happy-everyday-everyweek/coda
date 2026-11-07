/** 安装目录（AGT-105/INST）测试：命令有据、阻塞有原因、不伪造可安装性。 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  INSTALL_CATALOG,
  INSTALL_CATALOG_VERSION,
  installPlanFor,
  listInstallCatalog,
  normalizePlatform,
} from "../../adapters/install-catalog.mjs";

test("安装目录：覆盖国际与国产 Agent，且每条都有任务 ID", () => {
  assert.equal(INSTALL_CATALOG_VERSION, 1);
  const all = listInstallCatalog();
  assert.ok(all.length >= 13, `目录条目过少: ${all.length}`);
  for (const entry of all) {
    assert.match(entry.taskId, /^AGT-\d+$/, `${entry.id} 缺少任务 ID`);
    assert.equal(typeof entry.agent, "string");
  }
  const domestic = listInstallCatalog({ domestic: true }).map((e) => e.id);
  assert.ok(domestic.includes("qoder-cn"));
  assert.ok(domestic.includes("codebuddy"));
  assert.ok(domestic.includes("kimi"));
  assert.ok(domestic.includes("trae"));
  assert.ok(domestic.includes("workbuddy"));
  const international = listInstallCatalog({ domestic: false }).map((e) => e.id);
  assert.ok(international.includes("codex"));
  assert.ok(international.includes("fx"));
  assert.ok(!international.includes("qoder-cn"));
});

test("安装计划：按平台给出命令，缺失时明确不可安装", () => {
  const codex = installPlanFor("codex", { platform: "linux" });
  assert.equal(codex.installable, true);
  assert.match(codex.command, /codex\/install\.sh/);
  assert.deepEqual(codex.probe, ["--version", "--help"]);

  const claudeWin = installPlanFor("claude-code", { platform: "windows" });
  assert.match(claudeWin.command, /install\.ps1/);

  // fx 没有 Windows 安装器，也没有 npm 兜底 → 不可安装
  const fxWin = installPlanFor("fx", { platform: "windows" });
  assert.equal(fxWin.installable, false);
  assert.match(fxWin.reason, /no documented command/);

  // 阻塞项必须给出原因，不能返回假命令
  const wb = installPlanFor("workbuddy");
  assert.equal(wb.installable, false);
  assert.match(wb.reason, /无本地 CLI|WMA/);

  const trae = installPlanFor("trae", { platform: "linux" });
  assert.equal(trae.installable, false);
  assert.match(trae.reason, /旗舰|企业版/);

  assert.throws(() => installPlanFor("nope"), /unknown agent/);
});

test("平台归一：macOS 复用 POSIX 安装命令", () => {
  assert.equal(normalizePlatform("macos"), "linux");
  assert.equal(normalizePlatform("darwin"), "linux");
  assert.equal(normalizePlatform("windows"), "windows");
  assert.equal(normalizePlatform("linux"), "linux");
});

test("需要登录的国产 Agent 标注认证方式", () => {
  const qoder = listInstallCatalog().find((e) => e.id === "qoder-cn");
  assert.match(qoder.needsAuth, /QODERCN_PERSONAL_ACCESS_TOKEN/);
  assert.equal(qoder.domestic, true);
  const kimi = listInstallCatalog().find((e) => e.id === "kimi");
  assert.match(kimi.protocol, /ACP/);
});
