/**
 * 旧 Android PA 会话迁移（PA-107 交付物，对应 TODO PA-108；见 BLOCKED.md 的编号说明）。
 *
 * 旧实现（只读参考）：app/src/main/java/com/coda/mobileui/personal/CodaBotStore.kt
 *   messages.json = [{ id, fromUser, text, timestamp, replyToId, replyToText, replyToFromUser }]
 *   profile      = SharedPreferences("coda_bot_profile")
 * 桌面不能直接读 Android 私有目录，因此迁移输入是显式导出的 JSON：
 *   { format: "coda.android.pa.export", version: 1, profile: {...}, messages: [...] }
 * 也接受旧版 messages.json 的裸数组（profile 缺失）。
 *
 * 流程：dry-run 校验（不写）→ 导入（单事务 + 本地备份）→ 回滚（按 migrationId）。
 * 迁移生成的 ID 是确定性的：UUID 旧 ID → pm_<uuid>；非 UUID → pm_<稳定哈希>。
 * 重复导入同一份导出不会产生重复消息；导入中途失败会整体回滚，记录标 failed 可重试。
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { join } from "node:path";
import { nowIso } from "../contracts/ids.mjs";
import {
  PA_DEFAULT_PROFILE,
  createPaMessage,
  createPaProfile,
  formatHlc,
  isUuid,
  legacyIdToMessageId,
} from "./schema.mjs";

export const LEGACY_ANDROID_FORMAT = "coda.android.pa.export";
export const LEGACY_ANDROID_VERSION = 1;
export const LEGACY_SOURCE_NODE = "dev_legacy_android";

const LEGACY_PROFILE_STRING_FIELDS = [
  "name",
  "avatarSkinId",
  "avatarUri",
  "persona",
  "systemPrompt",
  "userName",
  "userAbout",
  "plannerProviderId",
  "plannerModel",
  "replyProviderId",
  "replyModel",
];
const LEGACY_PROFILE_FIELDS = [...LEGACY_PROFILE_STRING_FIELDS, "bubbleStyle", "petAnimation"];
const SECRET_KEY_PATTERN = /(api[_-]?key|secret|token|passwd|password|credential|authorization|cookie)/i;
const PROFILE_COMPARE_FIELDS = Object.keys(PA_DEFAULT_PROFILE);

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function currentIso(now) {
  return typeof now === "function" ? now() : now;
}

function normalizeTimestamp(value) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? null : parsed;
  }
  return null;
}

/** 把导出 JSON 或旧 messages.json 数组规范化为统一形状。 */
export function parseLegacyExport(input) {
  if (Array.isArray(input)) {
    return { format: LEGACY_ANDROID_FORMAT, version: LEGACY_ANDROID_VERSION, profile: null, messages: input };
  }
  if (!isPlainObject(input)) {
    throw new Error("legacy PA export must be an object or a messages array");
  }
  if (!Array.isArray(input.messages)) {
    throw new Error("legacy PA export requires a messages array");
  }
  return {
    format: input.format ?? LEGACY_ANDROID_FORMAT,
    version: input.version ?? LEGACY_ANDROID_VERSION,
    profile: isPlainObject(input.profile) ? input.profile : null,
    messages: input.messages,
  };
}

export function readLegacyExportFile(path) {
  const text = readFileSync(path, "utf8");
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new Error(`legacy PA export is not valid JSON: ${error.message}`);
  }
  return parseLegacyExport(parsed);
}

/**
 * 只读校验：不访问 store、不写文件。
 * errors 阻止导入；warnings 允许导入但会在报告中显示。
 */
export function validateLegacyExport(input, { sourceNode = LEGACY_SOURCE_NODE } = {}) {
  const errors = [];
  const warnings = [];
  let data;
  try {
    data = parseLegacyExport(input);
  } catch (error) {
    return { ok: false, errors: [{ index: null, field: null, message: error.message }], warnings: [], data: null, counts: null };
  }

  if (data.format !== LEGACY_ANDROID_FORMAT) {
    errors.push({ index: null, field: "format", message: `unsupported format: ${String(data.format)}` });
  }
  if (data.version !== LEGACY_ANDROID_VERSION) {
    errors.push({ index: null, field: "version", message: `unsupported version: ${String(data.version)}` });
  }
  if (typeof sourceNode !== "string" || !/^dev_[A-Za-z0-9_.-]+$/.test(sourceNode)) {
    errors.push({ index: null, field: "sourceNode", message: "sourceNode must be a dev_ ID" });
  }

  const profile = data.profile;
  if (profile) {
    for (const key of Object.keys(profile)) {
      if (SECRET_KEY_PATTERN.test(key)) {
        errors.push({ index: null, field: `profile.${key}`, message: "secrets must not be migrated" });
      }
    }
    for (const key of LEGACY_PROFILE_FIELDS) {
      if (!Object.hasOwn(profile, key)) continue;
      const value = profile[key];
      if (LEGACY_PROFILE_STRING_FIELDS.includes(key)) {
        if (typeof value !== "string") {
          warnings.push({ index: null, field: `profile.${key}`, message: "expected string; field skipped" });
        } else if ((key === "name" || key === "userName") && value.trim().length === 0) {
          warnings.push({ index: null, field: `profile.${key}`, message: "empty value; field skipped" });
        }
      } else if (key === "bubbleStyle") {
        if (!Number.isInteger(value) || value < 0) {
          warnings.push({ index: null, field: "profile.bubbleStyle", message: "expected non-negative integer; field skipped" });
        }
      } else if (key === "petAnimation") {
        if (typeof value !== "boolean") {
          warnings.push({ index: null, field: "profile.petAnimation", message: "expected boolean; field skipped" });
        }
      }
    }
  }

  const messages = data.messages;
  const seen = new Set();
  const normalized = [];
  for (let index = 0; index < messages.length; index += 1) {
    const raw = messages[index];
    if (!isPlainObject(raw)) {
      errors.push({ index, field: null, message: "message must be an object" });
      continue;
    }
    const legacyId = raw.id;
    if (typeof legacyId !== "string" || legacyId.length === 0) {
      errors.push({ index, field: "id", message: "id must be a non-empty string" });
      continue;
    }
    if (seen.has(legacyId)) {
      errors.push({ index, field: "id", message: `duplicate legacy message id: ${legacyId}` });
      continue;
    }
    seen.add(legacyId);
    if (typeof raw.text !== "string") {
      errors.push({ index, field: "text", message: "text must be a string" });
      continue;
    }
    if (typeof raw.fromUser !== "boolean") {
      errors.push({ index, field: "fromUser", message: "fromUser must be a boolean" });
      continue;
    }
    const timestamp = normalizeTimestamp(raw.timestamp);
    if (
      timestamp === null ||
      !Number.isSafeInteger(timestamp) ||
      timestamp < 0 ||
      timestamp > 999_999_999_999_999
    ) {
      errors.push({ index, field: "timestamp", message: "timestamp must be epoch milliseconds or an ISO timestamp" });
      continue;
    }
    if (raw.fromUser && raw.text.trim().length === 0) {
      errors.push({ index, field: "text", message: "user message text must not be empty" });
      continue;
    }
    if (raw.replyToId !== undefined && raw.replyToId !== null && typeof raw.replyToId !== "string") {
      errors.push({ index, field: "replyToId", message: "replyToId must be a string" });
      continue;
    }
    normalized.push({
      legacyId,
      fromUser: raw.fromUser,
      text: raw.text,
      timestamp,
      replyToId: typeof raw.replyToId === "string" && raw.replyToId.length > 0 ? raw.replyToId : null,
    });
  }

  for (let index = 0; index < normalized.length; index += 1) {
    const message = normalized[index];
    if (message.replyToId && !seen.has(message.replyToId)) {
      warnings.push({
        index,
        field: "replyToId",
        message: `reply target not found (${message.replyToId}); replyTo will be dropped`,
      });
    }
  }

  const counts = {
    messages: normalized.length,
    user: normalized.filter((message) => message.fromUser).length,
    assistant: normalized.filter((message) => !message.fromUser).length,
    withReplyTo: normalized.filter((message) => message.replyToId !== null).length,
    danglingReplyTo: warnings.filter((warning) => warning.field === "replyToId").length,
  };

  return {
    ok: errors.length === 0,
    errors,
    warnings,
    data: { ...data, profile },
    normalized,
    counts,
  };
}

function buildLegacyProfilePatch(legacyProfile) {
  const patch = {};
  if (!legacyProfile) return patch;
  for (const key of LEGACY_PROFILE_STRING_FIELDS) {
    const value = legacyProfile[key];
    if (typeof value !== "string") continue;
    if ((key === "name" || key === "userName") && value.trim().length === 0) continue;
    patch[key] = value.trim();
  }
  if (Number.isInteger(legacyProfile.bubbleStyle) && legacyProfile.bubbleStyle >= 0) {
    patch.bubbleStyle = legacyProfile.bubbleStyle;
  }
  if (typeof legacyProfile.petAnimation === "boolean") patch.petAnimation = legacyProfile.petAnimation;
  return patch;
}

function profileChanges(before, after) {
  const changes = [];
  for (const field of PROFILE_COMPARE_FIELDS) {
    const from = before?.[field] ?? null;
    const to = after?.[field] ?? null;
    if (from !== to) changes.push({ field, from, to });
  }
  return changes;
}

function planMessages(normalized, sourceNode, paId) {
  const messages = [];
  let lastPhysical = -1;
  let counter = 0;
  const idByLegacyId = new Map();
  for (const message of normalized) {
    idByLegacyId.set(message.legacyId, legacyIdToMessageId(message.legacyId, sourceNode));
  }
  for (let index = 0; index < normalized.length; index += 1) {
    const legacy = normalized[index];
    let physical = Math.max(legacy.timestamp, lastPhysical);
    if (physical === lastPhysical) counter += 1;
    else counter = 0;
    if (counter > 999999) {
      physical = lastPhysical + 1;
      counter = 0;
    }
    lastPhysical = physical;
    const hlc = formatHlc({ physical, counter, nodeId: sourceNode });
    const replyTo = legacy.replyToId ? idByLegacyId.get(legacy.replyToId) ?? null : null;
    const mapped = createPaMessage({
      id: idByLegacyId.get(legacy.legacyId),
      paId,
      sourceNode,
      role: legacy.fromUser ? "user" : "assistant",
      content: legacy.text,
      attachments: [],
      replyTo,
      seq: index + 1,
      ts: new Date(legacy.timestamp).toISOString(),
      hlc,
      origin: "migration",
    });
    messages.push({ ...mapped, legacyId: legacy.legacyId });
  }
  return messages;
}

/**
 * 生成迁移计划（纯计算，不写 store）。paId 在 import 时绑定到目标 profile。
 */
export function buildLegacyMigrationPlan(
  input,
  { store = null, sourceNode = LEGACY_SOURCE_NODE, now = nowIso } = {},
) {
  const validation = validateLegacyExport(input, { sourceNode });
  if (!validation.ok) {
    return { ok: false, phase: "validation", report: { ...validation, counts: validation.counts } };
  }
  const profileBefore = store ? store.getProfile() : null;
  const patch = buildLegacyProfilePatch(validation.data.profile);
  const base = profileBefore ?? PA_DEFAULT_PROFILE;
  const merged = { ...base, ...patch };
  const planAt = currentIso(now);
  const profileAfter = profileBefore
    ? createPaProfile({
        ...merged,
        id: profileBefore.id,
        createdAt: profileBefore.createdAt,
        updatedAt: planAt,
      })
    : createPaProfile({ ...merged, createdAt: planAt, updatedAt: planAt });

  const messages = planMessages(validation.normalized, sourceNode, profileAfter.id);
  const existing = store ? store.listMessages() : [];
  const existingIds = new Set(existing.map((message) => message.id));
  const existingNodeSeqs = new Set(
    existing.filter((message) => message.sourceNode === sourceNode).map((message) => message.seq),
  );
  const duplicateIds = messages.filter((message) => existingIds.has(message.id)).map((message) => message.id);
  const seqCollisions = messages
    .filter((message) => existingNodeSeqs.has(message.seq) && !existingIds.has(message.id))
    .map((message) => ({ id: message.id, seq: message.seq }));
  const changes = profileChanges(profileBefore, profileAfter);

  const preview = messages.slice(0, 10).map((message) => ({
    legacyId: message.legacyId,
    id: message.id,
    role: message.role,
    seq: message.seq,
    hlc: message.hlc,
    replyTo: message.replyTo,
  }));

  const report = {
    ok: true,
    format: validation.data.format,
    version: validation.data.version,
    counts: validation.counts,
    warnings: validation.warnings,
    errors: [],
    sourceNode,
    preview,
    wouldInsert: messages.length - duplicateIds.length,
    duplicates: duplicateIds,
    seqCollisions,
    profileChanged: changes.length > 0,
    profileChanges: changes,
  };

  return { ok: true, phase: "planned", report, messages, profileBefore, profileAfter };
}

/** dry-run：只校验与预览，绝不写库/写文件。 */
export function dryRunLegacyMigration(input, options = {}) {
  const plan = buildLegacyMigrationPlan(input, options);
  return {
    ok: plan.ok,
    phase: "dry-run",
    report: plan.report,
    plannedMessages: plan.ok ? plan.messages.length : 0,
  };
}

/**
 * 导入：先写本地备份，再在单事务中插入消息并更新资料。
 * onProgress 仅供测试/观测；它抛出异常时事务整体回滚，记录标 failed 并可重试。
 */
export function importLegacyMigration(
  input,
  { store, dataDir = null, sourceNode = LEGACY_SOURCE_NODE, now = nowIso, onProgress = null } = {},
) {
  if (!store) throw new Error("importLegacyMigration requires a PA store");
  const plan = buildLegacyMigrationPlan(input, { store, sourceNode, now });
  if (!plan.ok) return { ok: false, phase: "validation", report: plan.report };

  const migrationId = randomUUID();
  const at = currentIso(now);
  let backupPath = null;
  if (dataDir) {
    const backupDir = join(dataDir, "migrations");
    mkdirSync(backupDir, { recursive: true });
    backupPath = join(backupDir, `${migrationId}.backup.json`);
    writeFileSync(
      backupPath,
      JSON.stringify(
        {
          format: "coda.pa.migration.backup",
          version: 1,
          migrationId,
          at,
          profile: plan.profileBefore,
          messages: store.listMessages(),
        },
        null,
        2,
      ),
    );
  }

  const baseRecord = {
    id: migrationId,
    at,
    status: "applying",
    format: LEGACY_ANDROID_FORMAT,
    version: LEGACY_ANDROID_VERSION,
    sourceNode,
    counts: plan.report.counts,
    insertedIds: [],
    profileBefore: plan.profileBefore,
    profileAfter: plan.profileAfter,
    backupPath,
    warnings: plan.report.warnings,
    errors: [],
  };
  store.insertMigration(baseRecord);

  try {
    const applied = store.transaction(() => {
      const insertedIds = [];
      plan.messages.forEach((planned, index) => {
        // legacyId 只用于报告/映射，不进入持久化消息。
        const { legacyId, ...message } = planned;
        if (store.insertMessage(message)) insertedIds.push(message.id);
        if (typeof onProgress === "function") {
          onProgress({ phase: "insert", migrationId, index, id: message.id, legacyId });
        }
      });
      store.saveProfile(plan.profileAfter);
      if (typeof onProgress === "function") onProgress({ phase: "profile", migrationId, profileId: plan.profileAfter.id });
      const record = store.updateMigration(migrationId, {
        status: "applied",
        appliedAt: currentIso(now),
        insertedIds,
        profileAfter: plan.profileAfter,
      });
      return { insertedIds, record };
    });

    return {
      ok: true,
      phase: "applied",
      migrationId,
      status: "applied",
      inserted: applied.insertedIds.length,
      skipped: plan.messages.length - applied.insertedIds.length,
      profile: plan.profileAfter,
      backupPath,
      report: { ...plan.report, insertedIds: applied.insertedIds },
    };
  } catch (error) {
    let record = null;
    try {
      record = store.updateMigration(migrationId, {
        status: "failed",
        failedAt: currentIso(now),
        error: error.message,
      });
    } catch {
      record = { ...baseRecord, status: "failed", error: error.message };
    }
    return {
      ok: false,
      phase: "failed",
      migrationId,
      status: "failed",
      recoverable: true,
      error: error.message,
      backupPath,
      record,
    };
  }
}

/**
 * 回滚：删除该次迁移插入的消息，并在资料未被用户改动时恢复迁移前资料。
 * 幂等：已回滚的迁移再次回滚返回 alreadyRolledBack。
 * 回滚后仍存在的、曾 replyTo 迁移消息的消息会成为 orphan（由 merge 视图标记），不会被伪造删除。
 */
export function rollbackLegacyMigration(
  { store, migrationId, forceProfileRestore = false, now = nowIso } = {},
) {
  if (!store) throw new Error("rollbackLegacyMigration requires a PA store");
  if (typeof migrationId !== "string" || migrationId.length === 0) {
    throw new Error("rollbackLegacyMigration requires migrationId");
  }
  const record = store.getMigration(migrationId);
  if (!record) return { ok: false, phase: "lookup", error: `migration not found: ${migrationId}` };
  if (record.status === "rolled_back") {
    return { ok: true, phase: "rollback", status: "rolled_back", alreadyRolledBack: true, migrationId };
  }
  if (record.status !== "applied") {
    return {
      ok: false,
      phase: "status",
      migrationId,
      status: record.status,
      error: `migration is ${record.status}, only applied migrations can be rolled back`,
    };
  }

  const insertedIds = Array.isArray(record.insertedIds) ? record.insertedIds : [];
  const insertedSet = new Set(insertedIds);
  const dependents = store
    .listMessages()
    .filter((message) => !insertedSet.has(message.id) && message.replyTo && insertedSet.has(message.replyTo))
    .map((message) => message.id);
  const currentProfile = store.getProfile();
  const profileChanged =
    currentProfile !== null &&
    record.profileAfter !== null &&
    currentProfile.updatedAt !== record.profileAfter.updatedAt;
  const restoreProfile = record.profileBefore !== null && (!profileChanged || forceProfileRestore);
  const deleteCreatedProfile =
    record.profileBefore === null && record.profileAfter !== null && (!profileChanged || forceProfileRestore);
  const warning =
    profileChanged && !forceProfileRestore
      ? "profile changed after import; current profile kept (use forceProfileRestore to restore)"
      : null;

  const result = store.transaction(() => {
    const removed = store.deleteMessages(insertedIds);
    let profileRestored = false;
    if (restoreProfile) {
      store.saveProfile({ ...record.profileBefore, updatedAt: currentIso(now) });
      profileRestored = true;
    } else if (deleteCreatedProfile) {
      store.deleteProfile(record.profileAfter.id);
      profileRestored = true;
    }
    const updated = store.updateMigration(migrationId, {
      status: "rolled_back",
      rolledBackAt: currentIso(now),
      removed,
      profileRestored,
      dependents,
      rollbackWarning: warning,
    });
    return { removed, profileRestored, updated };
  });

  return {
    ok: true,
    phase: "rollback",
    migrationId,
    status: "rolled_back",
    removed: result.removed,
    profileRestored: result.profileRestored,
    dependents,
    warning,
    record: result.updated,
  };
}

export function listLegacyMigrations({ store } = {}) {
  if (!store) throw new Error("listLegacyMigrations requires a PA store");
  return store.listMigrations();
}

export function migrationStatus({ store, migrationId } = {}) {
  if (!store) throw new Error("migrationStatus requires a PA store");
  return store.getMigration(migrationId);
}

export const __migrationInternals = { buildLegacyProfilePatch, profileChanges, planMessages, isUuid };
