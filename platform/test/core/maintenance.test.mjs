/** INST-105/106：版本标记、升级计划、备份/回滚、卸载预览。 */
import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import {
  MaintenanceError,
  backupDataDir,
  planUpgrade,
  readVersionMarker,
  rollbackFromBackup,
  uninstallCoreData,
  uninstallPreview,
  writeVersionMarker,
} from "../../core/maintenance.mjs";
import { createCore } from "../../core/index.mjs";
import { cleanupDir, fixedClock, makeTempDir } from "../../fixtures/core/helpers.mjs";

test("核心启动写入版本标记", async (t) => {
  const dir = makeTempDir("coda-maintenance-");
  const core = createCore({ dataDir: dir, clock: fixedClock(), lock: false });
  t.after(async () => { await core.close(); cleanupDir(dir); });
  const marker = readVersionMarker(dir);
  assert.ok(marker, "未写入 version.json");
  assert.equal(typeof marker.schemaVersion, "number");
  assert.equal(typeof marker.coreVersion, "string");
});

test("升级计划：新目录/一致/需要迁移/降级", () => {
  const dir = makeTempDir("coda-upgrade-");
  try {
    assert.equal(planUpgrade({ dataDir: dir, targetSchemaVersion: 2 }).reason, "up-to-date");
    writeVersionMarker(dir, { schemaVersion: 1, coreVersion: "0.1.0" });
    assert.equal(planUpgrade({ dataDir: dir, targetSchemaVersion: 1 }).needsMigration, false);
    const migrate = planUpgrade({ dataDir: dir, targetSchemaVersion: 2, targetCoreVersion: "0.2.0" });
    assert.equal(migrate.needsMigration, true);
    assert.equal(migrate.needsBackup, true);
    const downgrade = planUpgrade({ dataDir: dir, targetSchemaVersion: 0 });
    assert.equal(downgrade.ok, false);
    assert.match(downgrade.reason, /downgrade/);
  } finally {
    cleanupDir(dir);
  }
});

test("备份与回滚：文件级恢复", () => {
  const dir = makeTempDir("coda-backup-");
  const backups = makeTempDir("coda-backup-store-");
  try {
    mkdirSync(join(dir, "profile"), { recursive: true });
    writeFileSync(join(dir, "profile", "settings.json"), '{"theme":"dark"}');
    writeFileSync(join(dir, "core.sqlite"), "v1");
    const backup = backupDataDir(dir, backups, { clock: () => new Date("2026-11-07T00:00:00Z") });
    assert.ok(existsSync(join(backup, "core.sqlite")));
    writeFileSync(join(dir, "core.sqlite"), "v2-corrupted");
    const result = rollbackFromBackup(backup, dir);
    assert.ok(result.restored >= 2);
    assert.equal(readFileSync(join(dir, "core.sqlite"), "utf8"), "v1");
  } finally {
    cleanupDir(dir);
    cleanupDir(backups);
  }
});

test("卸载：需要确认，预览列出目标且声明用户数据不动", () => {
  const dir = makeTempDir("coda-uninstall-");
  try {
    writeVersionMarker(dir, { schemaVersion: 1, coreVersion: "0.1.0" });
    writeFileSync(join(dir, "core.sqlite"), "data");
    const preview = uninstallPreview(dir);
    assert.ok(preview.targets.some((t) => t.kind === "journal"));
    assert.ok(preview.userDataUntouched.length > 0);
    assert.throws(() => uninstallCoreData(dir), MaintenanceError);
    uninstallCoreData(dir, { confirm: true });
    assert.equal(existsSync(dir), false);
  } finally {
    cleanupDir(dir);
  }
});
