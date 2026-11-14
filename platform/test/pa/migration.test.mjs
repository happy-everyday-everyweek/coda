import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { createPaMessage, createPaProfile, legacyIdToMessageId } from "../../pa/schema.mjs";
import { createPaStore } from "../../pa/store.mjs";
import {
  LEGACY_SOURCE_NODE,
  dryRunLegacyMigration,
  importLegacyMigration,
  listLegacyMigrations,
  migrationStatus,
  readLegacyExportFile,
  rollbackLegacyMigration,
  validateLegacyExport,
} from "../../pa/migration.mjs";
import { FIXED_MS, hlcFor, makeTempDir } from "./helpers.mjs";

const UUID_USER = "11111111-1111-4111-8111-111111111111";
const UUID_REPLY = "22222222-2222-4222-8222-222222222222";
const UUID_THIRD = "33333333-3333-4333-8333-333333333333";

function legacyExport(overrides = {}) {
  return {
    format: "coda.android.pa.export",
    version: 1,
    profile: {
      name: "旧麦",
      avatarSkinId: "clawd",
      persona: "旧版 PA 人设",
      systemPrompt: "",
      userName: "主人",
      userAbout: "",
      plannerProviderId: "provider-a",
      plannerModel: "model-a",
      replyProviderId: "provider-b",
      replyModel: "model-b",
      bubbleStyle: 2,
      petAnimation: false,
    },
    messages: [
      { id: UUID_USER, fromUser: true, text: "第一句", timestamp: FIXED_MS, replyToId: "" },
      { id: UUID_REPLY, fromUser: false, text: "回复第一句", timestamp: FIXED_MS + 1000, replyToId: UUID_USER },
      { id: UUID_THIRD, fromUser: true, text: "第三句", timestamp: FIXED_MS + 2000, replyToId: "" },
    ],
    ...overrides,
  };
}

function setup(t, { withProfile = true } = {}) {
  const dir = makeTempDir(t);
  const store = createPaStore({ dataDir: dir });
  t.after(() => store.close());
  const profile = withProfile ? createPaProfile() : null;
  if (profile) store.saveProfile(profile);
  return { dir, store, profile };
}

test("PA-107: dry-run 校验并预览，不写消息、不改资料", (t) => {
  const { store, profile } = setup(t);
  const dry = dryRunLegacyMigration(legacyExport(), { store });
  assert.equal(dry.ok, true);
  assert.equal(dry.report.counts.messages, 3);
  assert.equal(dry.report.counts.user, 2);
  assert.equal(dry.report.counts.assistant, 1);
  assert.equal(dry.report.wouldInsert, 3);
  assert.equal(dry.report.preview.length, 3);
  assert.equal(dry.report.preview[0].legacyId, UUID_USER);
  assert.equal(dry.report.preview[0].id, legacyIdToMessageId(UUID_USER, LEGACY_SOURCE_NODE));
  assert.equal(dry.report.profileChanged, true);
  assert.ok(dry.report.profileChanges.some((change) => change.field === "name"));
  assert.equal(store.countMessages(), 0);
  assert.equal(store.getProfile().name, profile.name);
  assert.equal(store.getProfile().id, profile.id);
});

test("PA-107: 导入保留 replyTo、来源节点与资料，回滚可恢复", (t) => {
  const { dir, store, profile } = setup(t);
  const imported = importLegacyMigration(legacyExport(), { store, dataDir: dir });
  assert.equal(imported.ok, true);
  assert.equal(imported.status, "applied");
  assert.equal(imported.inserted, 3);
  assert.equal(imported.skipped, 0);
  assert.ok(existsSync(imported.backupPath));
  assert.equal(store.countMessages(), 3);

  const messages = store.listMessages().sort((left, right) => left.seq - right.seq);
  assert.equal(messages[0].sourceNode, LEGACY_SOURCE_NODE);
  assert.equal(messages[0].origin, "migration");
  assert.equal(messages[0].role, "user");
  assert.equal(messages[1].role, "assistant");
  assert.equal(messages[1].replyTo, messages[0].id);
  assert.equal(messages[0].paId, profile.id);
  assert.equal(store.getProfile().name, "旧麦");
  assert.equal(store.getProfile().userName, "主人");
  assert.equal(store.getProfile().bubbleStyle, 2);
  assert.equal(store.getProfile().petAnimation, false);
  assert.equal(migrationStatus({ store, migrationId: imported.migrationId }).status, "applied");

  const rolledBack = rollbackLegacyMigration({ store, migrationId: imported.migrationId });
  assert.equal(rolledBack.ok, true);
  assert.equal(rolledBack.status, "rolled_back");
  assert.equal(rolledBack.removed, 3);
  assert.equal(store.countMessages(), 0);
  assert.equal(store.getProfile().id, profile.id);
  assert.equal(store.getProfile().name, profile.name);
  assert.equal(rolledBack.profileRestored, true);
  const again = rollbackLegacyMigration({ store, migrationId: imported.migrationId });
  assert.equal(again.ok, true);
  assert.equal(again.alreadyRolledBack, true);
});

test("PA-107: 重复导入同一份导出不产生重复消息", (t) => {
  const { dir, store } = setup(t);
  const first = importLegacyMigration(legacyExport(), { store, dataDir: dir });
  const second = importLegacyMigration(legacyExport(), { store, dataDir: dir });
  assert.equal(first.inserted, 3);
  assert.equal(second.inserted, 0);
  assert.equal(second.skipped, 3);
  assert.equal(store.countMessages(), 3);
  assert.equal(listLegacyMigrations({ store }).length, 2);
});

test("PA-107: 导入中途失败整体回滚，记录 failed 后可重试", (t) => {
  const { dir, store, profile } = setup(t);
  const failed = importLegacyMigration(legacyExport(), {
    store,
    dataDir: dir,
    onProgress({ phase, index }) {
      if (phase === "insert" && index === 1) throw new Error("simulated disk full");
    },
  });
  assert.equal(failed.ok, false);
  assert.equal(failed.status, "failed");
  assert.equal(failed.recoverable, true);
  assert.match(failed.error, /simulated disk full/);
  assert.equal(store.countMessages(), 0);
  assert.equal(store.getProfile().id, profile.id);
  assert.equal(store.getProfile().name, profile.name);
  const records = listLegacyMigrations({ store });
  assert.equal(records[0].status, "failed");
  const blocked = rollbackLegacyMigration({ store, migrationId: failed.migrationId });
  assert.equal(blocked.ok, false);
  assert.equal(blocked.phase, "status");

  const retry = importLegacyMigration(legacyExport(), { store, dataDir: dir });
  assert.equal(retry.ok, true);
  assert.equal(retry.inserted, 3);
  assert.equal(store.countMessages(), 3);
});

test("PA-107: 结构错误（格式/重复 ID/空用户文本）拒绝导入", (t) => {
  const { store } = setup(t);
  const badFormat = dryRunLegacyMigration({ format: "unknown", version: 1, messages: [] }, { store });
  assert.equal(badFormat.ok, false);
  assert.ok(badFormat.report.errors.some((error) => error.field === "format"));

  const duplicate = legacyExport({
    messages: [
      { id: UUID_USER, fromUser: true, text: "a", timestamp: FIXED_MS },
      { id: UUID_USER, fromUser: false, text: "b", timestamp: FIXED_MS + 1 },
    ],
  });
  const duplicateDry = dryRunLegacyMigration(duplicate, { store });
  assert.equal(duplicateDry.ok, false);
  assert.ok(duplicateDry.report.errors.some((error) => /duplicate legacy message id/.test(error.message)));

  const emptyUser = legacyExport({
    messages: [{ id: "local-1", fromUser: true, text: "   ", timestamp: FIXED_MS }],
  });
  const emptyDry = dryRunLegacyMigration(emptyUser, { store });
  assert.equal(emptyDry.ok, false);
  assert.ok(emptyDry.report.errors.some((error) => /must not be empty/.test(error.message)));

  const imported = importLegacyMigration(duplicate, { store });
  assert.equal(imported.ok, false);
  assert.equal(imported.phase, "validation");
  assert.equal(store.countMessages(), 0);
});

test("PA-107: 悬空 replyTo 保留消息并降级为无引用；非 UUID ID 稳定映射", (t) => {
  const { store } = setup(t);
  const exportData = legacyExport({
    messages: [
      { id: "local-1", fromUser: true, text: "旧消息", timestamp: FIXED_MS, replyToId: "missing-id" },
    ],
  });
  const validation = validateLegacyExport(exportData);
  assert.equal(validation.ok, true);
  assert.equal(validation.counts.danglingReplyTo, 1);
  const dry = dryRunLegacyMigration(exportData, { store });
  assert.equal(dry.report.warnings.length, 1);
  const imported = importLegacyMigration(exportData, { store });
  assert.equal(imported.ok, true);
  const message = store.listMessages()[0];
  assert.equal(message.replyTo, null);
  assert.match(message.id, /^pm_[0-9a-f]{32}$/);
  assert.equal(message.id, legacyIdToMessageId("local-1", LEGACY_SOURCE_NODE));
});

test("PA-107: 接受旧版 messages.json 裸数组（无 profile）", (t) => {
  const { store } = setup(t);
  const rawArray = [
    { id: "local-raw-1", fromUser: true, text: "旧数组消息", timestamp: FIXED_MS, replyToId: "" },
  ];
  const parsed = validateLegacyExport(rawArray);
  assert.equal(parsed.ok, true);
  assert.equal(parsed.data.format, "coda.android.pa.export");
  const imported = importLegacyMigration(rawArray, { store });
  assert.equal(imported.ok, true);
  assert.equal(imported.inserted, 1);
  assert.equal(store.getProfile().name, "麦麦");
  const message = store.listMessages()[0];
  assert.equal(message.content, "旧数组消息");
  assert.equal(message.sourceNode, LEGACY_SOURCE_NODE);
});

test("PA-107: 目标库没有资料时导入创建资料，回滚删除该资料", (t) => {
  const { store } = setup(t, { withProfile: false });
  const imported = importLegacyMigration(legacyExport(), { store });
  assert.equal(imported.ok, true);
  assert.equal(store.getProfile().name, "旧麦");
  const rolledBack = rollbackLegacyMigration({ store, migrationId: imported.migrationId });
  assert.equal(rolledBack.ok, true);
  assert.equal(rolledBack.profileRestored, true);
  assert.equal(store.getProfile(), null);
});

test("PA-107: 回滚保留迁移之后的消息，并报告依赖的 orphan", (t) => {
  const { store, profile } = setup(t);
  const imported = importLegacyMigration(legacyExport(), { store });
  const migrated = store.listMessages().sort((left, right) => left.seq - right.seq)[0];
  const dependent = createPaMessage({
    paId: profile.id,
    sourceNode: "dev_a",
    role: "assistant",
    content: "迁移之后的新消息",
    replyTo: migrated.id,
    seq: 1,
    hlc: hlcFor("dev_a", FIXED_MS + 5000),
    ts: new Date(FIXED_MS + 5000).toISOString(),
  });
  store.insertMessage(dependent);
  const rolledBack = rollbackLegacyMigration({ store, migrationId: imported.migrationId });
  assert.equal(rolledBack.ok, true);
  assert.equal(rolledBack.removed, 3);
  assert.deepEqual(rolledBack.dependents, [dependent.id]);
  assert.notEqual(store.getMessage(dependent.id), null);
  assert.equal(store.getMessage(migrated.id), null);
});

test("PA-107: 读取导出文件与非法 JSON 报错", (t) => {
  const dir = makeTempDir(t);
  const validPath = join(dir, "pa-export.json");
  writeFileSync(validPath, JSON.stringify(legacyExport()));
  const parsed = readLegacyExportFile(validPath);
  assert.equal(parsed.messages.length, 3);
  const validation = validateLegacyExport(parsed);
  assert.equal(validation.ok, true);

  const invalidPath = join(dir, "broken.json");
  writeFileSync(invalidPath, "{not json");
  assert.throws(() => readLegacyExportFile(invalidPath), /not valid JSON/);
});
