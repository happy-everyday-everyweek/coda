/**
 * PA 独立存储（PA-103）：node:sqlite，仅保存 PA 自己的资料、单会话消息、审计和迁移记录。
 *
 * 边界（platform/CONTRACT.md §1）：
 * - 不读 Coding Agent 的数据库、配置或会话；PA 库文件位于独立的 dataDir。
 * - 不保存 API Key / Token；profile 校验会拒绝秘密字段（SPEC 14.3）。
 * - 只被 pa/ 内部使用，客户端与适配器不得直接打开本库。
 */
import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { DatabaseSync } from "node:sqlite";

const SCHEMA_SQL = `
CREATE TABLE IF NOT EXISTS pa_profile (
  id TEXT PRIMARY KEY,
  data TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pa_message (
  id TEXT PRIMARY KEY,
  pa_id TEXT NOT NULL,
  source_node TEXT NOT NULL,
  seq INTEGER NOT NULL,
  role TEXT NOT NULL,
  hlc TEXT NOT NULL,
  ts TEXT NOT NULL,
  reply_to TEXT,
  origin TEXT NOT NULL,
  data TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pa_message_hlc ON pa_message(hlc);
CREATE INDEX IF NOT EXISTS idx_pa_message_node_seq ON pa_message(source_node, seq);
CREATE TABLE IF NOT EXISTS pa_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pa_audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  at TEXT NOT NULL,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  decision TEXT NOT NULL,
  source_node TEXT NOT NULL,
  target_node TEXT,
  target_session_id TEXT,
  command_id TEXT,
  result_state TEXT,
  data TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pa_audit_at ON pa_audit(at);
CREATE INDEX IF NOT EXISTS idx_pa_audit_command ON pa_audit(command_id);
CREATE TABLE IF NOT EXISTS pa_ack (
  profile_id TEXT NOT NULL,
  notice_version TEXT NOT NULL,
  acknowledged_at TEXT NOT NULL,
  acknowledged_by TEXT NOT NULL,
  PRIMARY KEY (profile_id, notice_version)
);
CREATE TABLE IF NOT EXISTS pa_migration (
  id TEXT PRIMARY KEY,
  at TEXT NOT NULL,
  status TEXT NOT NULL,
  data TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pa_cursor (
  node_id TEXT PRIMARY KEY,
  hlc TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
`;

function parseJson(text, label) {
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`corrupt PA ${label} record: ${error.message}`);
  }
}

export function createPaStore({ dataDir = null, dbPath = null, now = () => new Date().toISOString() } = {}) {
  let resolvedPath = dbPath;
  if (!resolvedPath) {
    if (!dataDir) throw new Error("createPaStore requires dataDir or dbPath");
    resolvedPath = dataDir === ":memory:" ? ":memory:" : join(dataDir, "pa.sqlite");
  }
  if (resolvedPath !== ":memory:") mkdirSync(dirname(resolvedPath), { recursive: true });

  const db = new DatabaseSync(resolvedPath);
  db.exec("PRAGMA journal_mode = WAL");
  db.exec("PRAGMA busy_timeout = 5000");
  db.exec(SCHEMA_SQL);

  let txDepth = 0;
  let closed = false;

  function ensureOpen() {
    if (closed) throw new Error("PA store is closed");
  }

  function transactionSync(fn) {
    ensureOpen();
    if (typeof fn !== "function") throw new Error("transaction requires a function");
    const depth = txDepth;
    const savepoint = `pa_sp_${depth}`;
    db.exec(depth === 0 ? "BEGIN IMMEDIATE" : `SAVEPOINT ${savepoint}`);
    txDepth += 1;
    try {
      const result = fn();
      if (result && typeof result.then === "function") {
        throw new Error("PA store transactions must be synchronous");
      }
      txDepth -= 1;
      if (txDepth === 0) db.exec("COMMIT");
      else db.exec(`RELEASE ${savepoint}`);
      return result;
    } catch (error) {
      txDepth -= 1;
      if (txDepth === 0) db.exec("ROLLBACK");
      else db.exec(`ROLLBACK TO ${savepoint}; RELEASE ${savepoint}`);
      throw error;
    }
  }

  const store = {
    dbPath: resolvedPath,
    transaction: transactionSync,

    getProfile() {
      ensureOpen();
      const row = db.prepare("SELECT data FROM pa_profile LIMIT 1").get();
      return row ? parseJson(row.data, "profile") : null;
    },

    deleteProfile(id = null) {
      ensureOpen();
      const result = id
        ? db.prepare("DELETE FROM pa_profile WHERE id = ?").run(id)
        : db.prepare("DELETE FROM pa_profile").run();
      return Number(result.changes);
    },

    saveProfile(profile) {
      ensureOpen();
      if (!profile || typeof profile.id !== "string") throw new Error("saveProfile requires a profile with id");
      db.prepare(
        "INSERT INTO pa_profile (id, data, updated_at) VALUES (?, ?, ?) " +
          "ON CONFLICT(id) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at",
      ).run(profile.id, JSON.stringify(profile), profile.updatedAt ?? now());
      return profile;
    },

    listMessages() {
      ensureOpen();
      return db
        .prepare("SELECT data FROM pa_message")
        .all()
        .map((row) => parseJson(row.data, "message"));
    },

    getMessage(id) {
      ensureOpen();
      const row = db.prepare("SELECT data FROM pa_message WHERE id = ?").get(id);
      return row ? parseJson(row.data, "message") : null;
    },

    hasMessage(id) {
      ensureOpen();
      return db.prepare("SELECT 1 AS present FROM pa_message WHERE id = ?").get(id) !== undefined;
    },

    insertMessage(message) {
      ensureOpen();
      if (!message || typeof message.id !== "string") throw new Error("insertMessage requires a message with id");
      const result = db
        .prepare(
          "INSERT OR IGNORE INTO pa_message " +
            "(id, pa_id, source_node, seq, role, hlc, ts, reply_to, origin, data) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .run(
          message.id,
          message.paId,
          message.sourceNode,
          message.seq,
          message.role,
          message.hlc,
          message.ts,
          message.replyTo ?? null,
          message.origin ?? "local",
          JSON.stringify(message),
        );
      return Number(result.changes) > 0;
    },

    insertMessages(messages) {
      ensureOpen();
      if (!Array.isArray(messages)) throw new Error("insertMessages requires an array");
      return transactionSync(() => {
        const inserted = [];
        for (const message of messages) {
          if (store.insertMessage(message)) inserted.push(message.id);
        }
        return inserted;
      });
    },

    countMessages() {
      ensureOpen();
      return Number(db.prepare("SELECT COUNT(*) AS count FROM pa_message").get().count);
    },

    deleteMessages(ids) {
      ensureOpen();
      if (!Array.isArray(ids)) throw new Error("deleteMessages requires an array");
      if (ids.length === 0) return 0;
      return transactionSync(() => {
        const stmt = db.prepare("DELETE FROM pa_message WHERE id = ?");
        let removed = 0;
        for (const id of ids) removed += Number(stmt.run(id).changes);
        return removed;
      });
    },

    nextSeq(sourceNode) {
      ensureOpen();
      const row = db
        .prepare("SELECT COALESCE(MAX(seq), 0) + 1 AS next FROM pa_message WHERE source_node = ?")
        .get(sourceNode);
      return Number(row.next);
    },

    metaGet(key) {
      ensureOpen();
      const row = db.prepare("SELECT value FROM pa_meta WHERE key = ?").get(key);
      return row ? row.value : null;
    },

    metaSet(key, value) {
      ensureOpen();
      db.prepare(
        "INSERT INTO pa_meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
      ).run(key, String(value));
    },

    appendAudit(entry) {
      ensureOpen();
      if (!entry || typeof entry !== "object") throw new Error("appendAudit requires an entry");
      const result = db
        .prepare(
          "INSERT INTO pa_audit " +
            "(at, actor, action, decision, source_node, target_node, target_session_id, command_id, result_state, data) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .run(
          entry.at,
          entry.actor,
          entry.action,
          entry.decision,
          entry.sourceNode,
          entry.targetNode ?? null,
          entry.targetSessionId ?? null,
          entry.commandId ?? null,
          entry.result?.state ?? null,
          JSON.stringify(entry),
        );
      return { ...entry, id: Number(result.lastInsertRowid) };
    },

    listAudit({ limit = 50, offset = 0, actor = null, action = null, commandId = null, since = null } = {}) {
      ensureOpen();
      const safeLimit = Number.isInteger(limit) ? Math.min(Math.max(limit, 1), 500) : 50;
      const safeOffset = Number.isInteger(offset) && offset > 0 ? offset : 0;
      const clauses = [];
      const params = [];
      if (actor) {
        clauses.push("actor = ?");
        params.push(actor);
      }
      if (action) {
        clauses.push("action = ?");
        params.push(action);
      }
      if (commandId) {
        clauses.push("command_id = ?");
        params.push(commandId);
      }
      if (since) {
        clauses.push("at >= ?");
        params.push(since);
      }
      const where = clauses.length > 0 ? ` WHERE ${clauses.join(" AND ")}` : "";
      params.push(safeLimit, safeOffset);
      return db
        .prepare(`SELECT data FROM pa_audit${where} ORDER BY at DESC, id DESC LIMIT ? OFFSET ?`)
        .all(...params)
        .map((row) => parseJson(row.data, "audit"));
    },

    countAudit(filter = {}) {
      ensureOpen();
      const { actor = null, action = null, commandId = null } = filter;
      const clauses = [];
      const params = [];
      if (actor) {
        clauses.push("actor = ?");
        params.push(actor);
      }
      if (action) {
        clauses.push("action = ?");
        params.push(action);
      }
      if (commandId) {
        clauses.push("command_id = ?");
        params.push(commandId);
      }
      const where = clauses.length > 0 ? ` WHERE ${clauses.join(" AND ")}` : "";
      return Number(db.prepare(`SELECT COUNT(*) AS count FROM pa_audit${where}`).get(...params).count);
    },

    insertAck({ profileId, noticeVersion, acknowledgedAt, acknowledgedBy }) {
      ensureOpen();
      const result = db
        .prepare(
          "INSERT OR IGNORE INTO pa_ack (profile_id, notice_version, acknowledged_at, acknowledged_by) VALUES (?, ?, ?, ?)",
        )
        .run(profileId, noticeVersion, acknowledgedAt, acknowledgedBy);
      return Number(result.changes) > 0;
    },

    getAck(profileId, noticeVersion) {
      ensureOpen();
      return (
        db
          .prepare(
            "SELECT profile_id AS profileId, notice_version AS noticeVersion, acknowledged_at AS acknowledgedAt, " +
              "acknowledged_by AS acknowledgedBy FROM pa_ack WHERE profile_id = ? AND notice_version = ?",
          )
          .get(profileId, noticeVersion) ?? null
      );
    },

    insertMigration(record) {
      ensureOpen();
      if (!record || typeof record.id !== "string") throw new Error("insertMigration requires a record with id");
      db.prepare("INSERT INTO pa_migration (id, at, status, data) VALUES (?, ?, ?, ?)").run(
        record.id,
        record.at,
        record.status,
        JSON.stringify(record),
      );
      return record;
    },

    updateMigration(id, patch) {
      ensureOpen();
      const row = db.prepare("SELECT data FROM pa_migration WHERE id = ?").get(id);
      if (!row) throw new Error(`migration not found: ${id}`);
      const next = { ...parseJson(row.data, "migration"), ...patch, id };
      db.prepare("UPDATE pa_migration SET status = ?, data = ? WHERE id = ?").run(next.status, JSON.stringify(next), id);
      return next;
    },

    getMigration(id) {
      ensureOpen();
      const row = db.prepare("SELECT data FROM pa_migration WHERE id = ?").get(id);
      return row ? parseJson(row.data, "migration") : null;
    },

    listMigrations() {
      ensureOpen();
      return db
        .prepare("SELECT data FROM pa_migration ORDER BY at DESC, id DESC")
        .all()
        .map((row) => parseJson(row.data, "migration"));
    },

    close() {
      if (closed) return;
      closed = true;
      db.close();
    },
  };

  return store;
}
