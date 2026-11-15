/**
 * 核心维护：版本标记、升级计划、备份/回滚与卸载预览（INST-105 / INST-106 支撑）。
 *
 * 设计边界：
 *   - 只操作核心数据目录；不碰用户工作区、不删用户文件；
 *   - 升级/回滚是文件级快照，不保证在运行中安全（运行中升级由 lifecycle 单实例保证）；
 *   - 不负责签名与安装包；那属于发布流程（QA-105）。
 */
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const MAINTENANCE_VERSION = 1;
export const VERSION_MARKER = "version.json";

export class MaintenanceError extends Error {
  constructor(message, details = {}) {
    super(message);
    this.name = "MaintenanceError";
    this.details = details;
  }
}

/** 读取数据目录版本标记；没有则返回 null（老版本/全新目录）。 */
export function readVersionMarker(dataDir) {
  const path = join(dataDir, VERSION_MARKER);
  if (!existsSync(path)) return null;
  try {
    const marker = JSON.parse(readFileSync(path, "utf8"));
    if (!marker || typeof marker !== "object") throw new Error("not an object");
    return marker;
  } catch (err) {
    throw new MaintenanceError(`invalid version marker: ${err.message}`, { path });
  }
}

export function writeVersionMarker(dataDir, { schemaVersion, coreVersion, clock = () => new Date() } = {}) {
  if (!dataDir) throw new MaintenanceError("dataDir required");
  mkdirSync(dataDir, { recursive: true });
  const marker = {
    maintenanceVersion: MAINTENANCE_VERSION,
    schemaVersion: schemaVersion ?? null,
    coreVersion: coreVersion ?? null,
    updatedAt: (clock() instanceof Date ? clock() : new Date(clock())).toISOString(),
  };
  writeFileSync(join(dataDir, VERSION_MARKER), JSON.stringify(marker, null, 2) + "\n");
  return marker;
}

/** 升级计划：判断是否需要备份/迁移；不执行外部副作用。 */
export function planUpgrade({ dataDir, currentSchemaVersion, targetSchemaVersion, targetCoreVersion = null }) {
  const marker = readVersionMarker(dataDir);
  const from = marker?.schemaVersion ?? null;
  const to = targetSchemaVersion ?? null;
  const needsMigration = from !== null && to !== null && from !== to;
  const isNewer = from !== null && to !== null && from > to;
  return {
    ok: !isNewer,
    from: { schemaVersion: from, coreVersion: marker?.coreVersion ?? null },
    to: { schemaVersion: to, coreVersion: targetCoreVersion },
    needsBackup: needsMigration,
    needsMigration,
    reason: isNewer ? "downgrade requires rollback, not migration" : needsMigration ? "schema differs" : "up-to-date",
  };
}

/** 备份数据目录到 backupsDir/<timestamp>/；返回备份路径。 */
export function backupDataDir(dataDir, backupsDir, { clock = () => new Date() } = {}) {
  if (!existsSync(dataDir)) throw new MaintenanceError("dataDir does not exist", { dataDir });
  if (backupsDir === dataDir || backupsDir.startsWith(`${dataDir}/`)) {
    throw new MaintenanceError("backupsDir must be outside dataDir", { dataDir, backupsDir });
  }
  const stamp = (clock() instanceof Date ? clock() : new Date(clock())).toISOString().replace(/[:.]/g, "-");
  const target = join(backupsDir, stamp);
  mkdirSync(backupsDir, { recursive: true });
  cpSync(dataDir, target, { recursive: true });
  return target;
}

/** 回滚：把备份目录内容拷回数据目录（保留备份），返回恢复的文件数。 */
export function rollbackFromBackup(backupDir, dataDir) {
  if (!existsSync(backupDir)) throw new MaintenanceError("backup does not exist", { backupDir });
  mkdirSync(dataDir, { recursive: true });
  let restored = 0;
  const walk = (src, dest) => {
    for (const entry of readdirSync(src, { withFileTypes: true })) {
      const from = join(src, entry.name);
      const to = join(dest, entry.name);
      if (entry.isDirectory()) {
        mkdirSync(to, { recursive: true });
        walk(from, to);
      } else if (entry.isFile()) {
        cpSync(from, to);
        restored += 1;
      }
    }
  };
  walk(backupDir, dataDir);
  return { restored, dataDir };
}

/** 卸载预览：明确列出会删除/保留的内容，不执行删除。 */
export function uninstallPreview(dataDir, { retainsBackups = true } = {}) {
  const targets = [];
  const push = (path, kind) => {
    if (existsSync(path)) targets.push({ path, kind, bytes: statSync(path).isDirectory() ? null : statSync(path).size });
  };
  push(dataDir, "core-data");
  push(join(dataDir, "core.sqlite"), "journal");
  push(join(dataDir, VERSION_MARKER), "version-marker");
  return {
    dataDir,
    targets,
    keeps: retainsBackups ? ["backups/（若存在）"] : [],
    userDataUntouched: ["工作区文件", "Git 仓库", "终端输出缓存之外的用户文件"],
  };
}

/** 执行卸载（只删核心数据目录；需要显式 confirm）。 */
export function uninstallCoreData(dataDir, { confirm = false } = {}) {
  if (!confirm) throw new MaintenanceError("uninstall requires { confirm: true }");
  if (!existsSync(dataDir)) return { removed: 0 };
  rmSync(dataDir, { recursive: true, force: true });
  return { removed: 1 };
}
