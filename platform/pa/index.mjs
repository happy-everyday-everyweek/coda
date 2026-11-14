/**
 * PA 模块入口（platform/CONTRACT.md §8：createPaRunner({core, store})）。
 *
 * 依赖方向：pa/ → contracts/ + node 内置模块。
 * pa/ 不得 import adapters/、core/、clients/；协调能力只经 tools.mjs 的统一命令层。
 */
export * from "./schema.mjs";
export * from "./merge.mjs";
export { createPaStore } from "./store.mjs";
export { createPaRunner } from "./runner.mjs";
export { createPaTools } from "./tools.mjs";
export {
  AUTONOMY_NOTICE,
  AUTONOMY_NOTICE_VERSION,
  AUTONOMY_MODES,
  PA_EXTRA_AUTONOMY_MODES,
  PA_PAUSE_SWITCH,
  AUDIT_ACTORS,
  AUDIT_DECISIONS,
  createAuditLog,
  describeAutonomyControls,
  summarizeForAudit,
} from "./audit.mjs";
export {
  LEGACY_ANDROID_FORMAT,
  LEGACY_ANDROID_VERSION,
  LEGACY_SOURCE_NODE,
  parseLegacyExport,
  readLegacyExportFile,
  validateLegacyExport,
  buildLegacyMigrationPlan,
  dryRunLegacyMigration,
  importLegacyMigration,
  rollbackLegacyMigration,
  listLegacyMigrations,
  migrationStatus,
} from "./migration.mjs";
