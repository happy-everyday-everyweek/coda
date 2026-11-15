/**
 * CORE-104：SQLite 事件日志、迁移、事务边界与投影。
 *
 * 不变量（CONTRACT.md 第 3 节）：
 * 1. 每个事件有全局单调递增 seq，从 1 开始，严格 +1。
 * 2. 事件写入与投影更新在同一个事务边界内；投影失败则事件不落库。
 * 3. 会话投影可从事件重建；replay 用于验证投影一致性。
 * 4. 断点回放：seq 连续则 replay，出现缺口则要求客户端重新取 snapshot。
 *
 * 实现说明：所有权威表存放在 `dataDir/core.sqlite`，客户端不得直接读库。
 */
import { DatabaseSync } from "node:sqlite";
import { mkdirSync } from "node:fs";
import path from "node:path";

import { makeId } from "../contracts/ids.mjs";
import { EVENT_KINDS } from "../contracts/events.mjs";
import { ClosedError, ValidationError } from "./errors.mjs";

/** 只允许 notification / session 事件没有 sessionId（与 contracts/events.mjs 一致）。 */
const CORE_SCOPED = new Set(["notification", "session"]);

const prune = (obj) => {
  const out = {};
  for (const [k, v] of Object.entries(obj ?? {})) {
    if (v !== undefined) out[k] = v;
  }
  return out;
};

function parseJson(text, fallback) {
  if (typeof text !== "string" || text.length === 0) return fallback;
  try {
    return JSON.parse(text);
  } catch {
    return fallback;
  }
}

/* ------------------------------------------------------------------ schema */

export const MIGRATIONS = [
  {
    version: 1,
    up(db) {
      db.exec(`
        CREATE TABLE meta (
          key TEXT PRIMARY KEY,
          value TEXT NOT NULL
        );

        CREATE TABLE events (
          seq INTEGER PRIMARY KEY,
          id TEXT NOT NULL UNIQUE,
          kind TEXT NOT NULL,
          session_id TEXT,
          ts TEXT NOT NULL,
          payload TEXT NOT NULL
        );
        CREATE INDEX idx_events_session ON events(session_id, seq);

        CREATE TABLE sessions (
          session_id TEXT PRIMARY KEY,
          node_id TEXT,
          state TEXT NOT NULL DEFAULT 'idle',
          last_seq INTEGER NOT NULL DEFAULT 0,
          updated_at TEXT,
          json TEXT NOT NULL
        );
        CREATE INDEX idx_sessions_updated ON sessions(updated_at DESC);

        CREATE TABLE runs (
          run_id TEXT PRIMARY KEY,
          session_id TEXT,
          state TEXT NOT NULL,
          json TEXT NOT NULL
        );
        CREATE INDEX idx_runs_session ON runs(session_id);

        CREATE TABLE turns (
          turn_id TEXT PRIMARY KEY,
          session_id TEXT,
          run_id TEXT,
          state TEXT NOT NULL,
          json TEXT NOT NULL
        );
        CREATE INDEX idx_turns_session ON turns(session_id, state);

        CREATE TABLE interactions (
          request_id TEXT PRIMARY KEY,
          session_id TEXT,
          state TEXT NOT NULL,
          json TEXT NOT NULL
        );
        CREATE INDEX idx_interactions_session ON interactions(session_id, state);

        CREATE TABLE artifacts (
          artifact_id TEXT PRIMARY KEY,
          session_id TEXT,
          sha256 TEXT,
          json TEXT NOT NULL
        );
        CREATE INDEX idx_artifacts_session ON artifacts(session_id);

        CREATE TABLE notifications (
          notification_id TEXT PRIMARY KEY,
          session_id TEXT,
          type TEXT NOT NULL,
          created_at TEXT NOT NULL,
          read INTEGER NOT NULL DEFAULT 0,
          json TEXT NOT NULL
        );
        CREATE INDEX idx_notifications_created ON notifications(created_at DESC);

        CREATE TABLE objects (
          kind TEXT NOT NULL,
          id TEXT NOT NULL,
          json TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          PRIMARY KEY (kind, id)
        );

        CREATE TABLE commands (
          command_id TEXT PRIMARY KEY,
          kind TEXT NOT NULL,
          source TEXT NOT NULL,
          target TEXT NOT NULL,
          payload TEXT NOT NULL,
          state TEXT NOT NULL,
          supersedes TEXT,
          superseded_by TEXT,
          created_at TEXT NOT NULL,
          expires_at TEXT NOT NULL,
          dispatched_at TEXT,
          settled_at TEXT,
          attempts INTEGER NOT NULL DEFAULT 0,
          result TEXT,
          error TEXT
        );
        CREATE INDEX idx_commands_state ON commands(state, created_at);
      `);
    },
  },
];

export class Journal {
  #db = null;
  #closed = false;
  #txDepth = 0;
  #savepointSeq = 0;

  constructor({ dataDir, clock = () => new Date(), file = "core.sqlite", migrations = MIGRATIONS } = {}) {
    if (!dataDir) throw new ValidationError("Journal requires dataDir");
    this.dataDir = dataDir;
    this.file = file;
    this.path = path.join(dataDir, file);
    this.clock = clock;
    this.migrations = migrations;
  }

  get db() {
    if (!this.#db) throw new ClosedError("journal is not open");
    return this.#db;
  }

  get closed() {
    return this.#closed;
  }

  now() {
    const d = this.clock();
    return d instanceof Date ? new Date(d.getTime()) : new Date(d);
  }

  nowIso() {
    return this.now().toISOString();
  }

  open() {
    if (this.#db) return this;
    mkdirSync(this.dataDir, { recursive: true });
    const db = new DatabaseSync(this.path);
    db.exec("PRAGMA journal_mode = WAL");
    db.exec("PRAGMA synchronous = NORMAL");
    db.exec("PRAGMA foreign_keys = ON");
    db.exec("PRAGMA busy_timeout = 5000");
    this.#db = db;
    this.#closed = false;
    this.#migrate();
    this.db
      .prepare("INSERT INTO meta(key, value) VALUES('event_seq', '0') ON CONFLICT(key) DO NOTHING")
      .run();
    this.db
      .prepare("INSERT INTO meta(key, value) VALUES('created_at', ?) ON CONFLICT(key) DO NOTHING")
      .run(this.nowIso());
    return this;
  }

  close() {
    if (this.#closed || !this.#db) {
      this.#closed = true;
      return;
    }
    try {
      this.#db.close();
    } finally {
      this.#db = null;
      this.#closed = true;
    }
  }

  #assertOpen() {
    if (!this.#db) throw new ClosedError("journal is not open");
  }

  #migrate() {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version INTEGER PRIMARY KEY,
        applied_at TEXT NOT NULL
      );
    `);
    const current = Number(
      this.db.prepare("SELECT COALESCE(MAX(version), 0) AS v FROM schema_migrations").get().v ?? 0,
    );
    for (const migration of [...this.migrations].sort((a, b) => a.version - b.version)) {
      if (migration.version <= current) continue;
      this.db.exec("BEGIN IMMEDIATE");
      try {
        migration.up(this.db, { nowIso: this.nowIso() });
        this.db
          .prepare("INSERT INTO schema_migrations(version, applied_at) VALUES(?, ?)")
          .run(migration.version, this.nowIso());
        this.db.exec("COMMIT");
      } catch (err) {
        try {
          this.db.exec("ROLLBACK");
        } catch {
          // ignore rollback failure, 原始错误更有意义
        }
        throw err;
      }
    }
  }

  /** 当前 schema 版本（用于 CORE-004 迁移测试与诊断）。 */
  schemaVersion() {
    this.#assertOpen();
    return Number(this.db.prepare("SELECT COALESCE(MAX(version), 0) AS v FROM schema_migrations").get().v ?? 0);
  }

  getMeta(key) {
    this.#assertOpen();
    const row = this.db.prepare("SELECT value FROM meta WHERE key = ?").get(key);
    return row ? row.value : null;
  }

  setMeta(key, value) {
    this.#assertOpen();
    this.db
      .prepare("INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
      .run(key, String(value));
  }

  /* ------------------------------------------------------------ transaction */

  /**
   * 同步事务（SQLite 是同步 API）。可嵌套：内层用 SAVEPOINT。
   * @template T
   * @param {() => T} fn
   * @returns {T}
   */
  transaction(fn) {
    this.#assertOpen();
    const result = this.#txDepth > 0 ? this.#runSavepoint(fn) : this.#runOuter(fn);
    if (result && typeof result.then === "function") {
      throw new TypeError("journal.transaction() does not support async functions");
    }
    return result;
  }

  #runOuter(fn) {
    this.db.exec("BEGIN IMMEDIATE");
    this.#txDepth = 1;
    try {
      const value = fn();
      this.db.exec("COMMIT");
      return value;
    } catch (err) {
      try {
        this.db.exec("ROLLBACK");
      } catch {
        // ignore
      }
      throw err;
    } finally {
      this.#txDepth = 0;
    }
  }

  #runSavepoint(fn) {
    const name = `sp_${++this.#savepointSeq}`;
    this.db.exec(`SAVEPOINT ${name}`);
    this.#txDepth += 1;
    try {
      const value = fn();
      this.db.exec(`RELEASE ${name}`);
      return value;
    } catch (err) {
      try {
        this.db.exec(`ROLLBACK TO ${name}`);
        this.db.exec(`RELEASE ${name}`);
      } catch {
        // ignore
      }
      throw err;
    } finally {
      this.#txDepth -= 1;
    }
  }

  /* ---------------------------------------------------------------- events */

  latestSeq() {
    this.#assertOpen();
    const row = this.db.prepare("SELECT COALESCE(MAX(seq), 0) AS v FROM events").get();
    return Number(row.v ?? 0);
  }

  oldestSeq() {
    this.#assertOpen();
    const row = this.db.prepare("SELECT MIN(seq) AS v FROM events").get();
    return row?.v === null || row?.v === undefined ? null : Number(row.v);
  }

  eventCount() {
    this.#assertOpen();
    return Number(this.db.prepare("SELECT COUNT(*) AS n FROM events").get().n ?? 0);
  }

  #nextSeq() {
    this.db
      .prepare("UPDATE meta SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT) WHERE key = 'event_seq'")
      .run();
    const row = this.db.prepare("SELECT CAST(value AS INTEGER) AS v FROM meta WHERE key = 'event_seq'").get();
    return Number(row.v);
  }

  /**
   * 追加事件（事务内）+ 更新投影。
   * @param {object} input
   * @param {string} input.kind 见 contracts/events.mjs
   * @param {string|null} [input.sessionId]
   * @param {object} [input.payload]
   * @param {string} [input.ts]
   * @param {(event: object) => void} [input.project] 额外投影（仍在同一事务内）
   * @returns {object} 持久化后的事件（含 id/seq）
   */
  appendEvent({ kind, sessionId = null, payload = {}, ts = this.nowIso(), project = null } = {}) {
    this.#assertOpen();
    if (!EVENT_KINDS.includes(kind)) throw new ValidationError(`unknown event kind: ${kind}`);
    if (!CORE_SCOPED.has(kind) && !sessionId) throw new ValidationError(`event ${kind} requires sessionId`);
    if (sessionId !== null && typeof sessionId !== "string") throw new ValidationError("sessionId must be string or null");
    if (payload === null || typeof payload !== "object" || Array.isArray(payload)) {
      throw new ValidationError("payload must be a plain object");
    }

    return this.transaction(() => {
      const seq = this.#nextSeq();
      const id = makeId("evt");
      this.db
        .prepare("INSERT INTO events(seq, id, kind, session_id, ts, payload) VALUES(?, ?, ?, ?, ?, ?)")
        .run(seq, id, kind, sessionId, ts, JSON.stringify(payload));
      const event = { id, seq, kind, sessionId, payload, ts };

      if (sessionId) {
        // 保证会话行存在，并推进 last_seq（断点回放依据）。
        if (!this.#sessionExists(sessionId)) {
          this.upsertSession({ id: sessionId, state: "idle", createdAt: ts, lastActivityAt: ts });
        }
        this.db
          .prepare(
            "UPDATE sessions SET last_seq = CASE WHEN last_seq < ? THEN ? ELSE last_seq END, updated_at = COALESCE(updated_at, ?) WHERE session_id = ?",
          )
          .run(seq, seq, ts, sessionId);
      }

      this.applyEventToProjection(event);
      if (typeof project === "function") project(event);
      return event;
    });
  }

  readSince(fromSeq, { limit = 1000, sessionId = null } = {}) {
    this.#assertOpen();
    if (!Number.isInteger(fromSeq) || fromSeq < 0) throw new ValidationError("fromSeq must be a non-negative integer");
    if (!Number.isInteger(limit) || limit < 1) throw new ValidationError("limit must be a positive integer");
    const rows = sessionId
      ? this.db
          .prepare(
            "SELECT seq, id, kind, session_id, ts, payload FROM events WHERE seq > ? AND session_id = ? ORDER BY seq ASC LIMIT ?",
          )
          .all(fromSeq, sessionId, limit)
      : this.db
          .prepare("SELECT seq, id, kind, session_id, ts, payload FROM events WHERE seq > ? ORDER BY seq ASC LIMIT ?")
          .all(fromSeq, limit);
    return rows.map((r) => ({
      id: r.id,
      seq: Number(r.seq),
      kind: r.kind,
      sessionId: r.session_id,
      ts: r.ts,
      payload: parseJson(r.payload, {}),
    }));
  }

  getEvent(seq) {
    this.#assertOpen();
    if (!Number.isInteger(seq) || seq < 1) throw new ValidationError("seq must be a positive integer");
    const r = this.db.prepare("SELECT seq, id, kind, session_id, ts, payload FROM events WHERE seq = ?").get(seq);
    if (!r) return null;
    return {
      id: r.id,
      seq: Number(r.seq),
      kind: r.kind,
      sessionId: r.session_id,
      ts: r.ts,
      payload: parseJson(r.payload, {}),
    };
  }

  /** 最近的消息事件（按 message.id 去重，升序返回）；用于有界快照。 */
  recentMessages({ limit = 200 } = {}) {
    this.#assertOpen();
    if (!Number.isInteger(limit) || limit < 1) throw new ValidationError("limit must be a positive integer");
    const rows = this.db
      .prepare("SELECT seq, payload FROM events WHERE kind = 'message' ORDER BY seq DESC LIMIT ?")
      .all(limit);
    const seen = new Set();
    const out = [];
    for (const row of rows) {
      const payload = parseJson(row.payload, {});
      const message = payload.message;
      if (!message || typeof message !== "object" || !message.id || seen.has(message.id)) continue;
      seen.add(message.id);
      out.push({ ...message, order: Number.isFinite(message.order) ? message.order : Number(row.seq) });
    }
    return out.reverse();
  }

  /**
   * 断点续传判定（SPEC 11.6 / 13.3）。
   * @returns {{mode:"replay"|"snapshot", fromSeq:number, latestSeq:number, reason:string}}
   */
  checkResume(fromSeq, { sessionId = null } = {}) {
    this.#assertOpen();
    if (!Number.isInteger(fromSeq) || fromSeq < 0) throw new ValidationError("fromSeq must be a non-negative integer");
    const latest = sessionId ? Number(this.getSession(sessionId)?.lastSeq ?? 0) : this.latestSeq();
    if (fromSeq === latest) {
      return { mode: "replay", fromSeq, latestSeq: latest, reason: "up-to-date" };
    }
    if (fromSeq > latest) {
      return { mode: "snapshot", fromSeq, latestSeq: latest, reason: "client-ahead" };
    }
    const oldest = this.oldestSeq();
    if (oldest !== null && fromSeq < oldest - 1) {
      return { mode: "snapshot", fromSeq, latestSeq: latest, reason: "trimmed" };
    }
    return { mode: "replay", fromSeq, latestSeq: latest, reason: "contiguous" };
  }

  /** 全局快照（不含全部历史事件，客户端据此建立基线）。 */
  snapshot({ limit = 200, sessionId = null } = {}) {
    this.#assertOpen();
    if (sessionId) {
      const projection = this.sessionProjection(sessionId, { eventLimit: 200 });
      return {
        version: 1,
        seq: projection.lastSeq,
        generatedAt: this.nowIso(),
        sessionId,
        sessions: projection.session ? [projection.session] : [],
        session: projection,
      };
    }
    return {
      version: 1,
      seq: this.latestSeq(),
      generatedAt: this.nowIso(),
      sessions: this.listSessions({ limit }),
    };
  }

  /* ------------------------------------------------------------ projection */

  applyEventToProjection(event) {
    this.#assertOpen();
    const payload = event.payload ?? {};
    try {
      switch (event.kind) {
        case "session": {
          if (payload.session && typeof payload.session === "object") {
            this.upsertSession({ ...payload.session, id: payload.session.id ?? event.sessionId });
          } else if (event.sessionId) {
            this.upsertSession({
              ...prune({
                id: event.sessionId,
                state: payload.state,
                title: payload.title,
                model: payload.model,
                providerThreadId: payload.providerThreadId,
                nodeId: payload.nodeId,
                environmentId: payload.environmentId,
                agentInstanceId: payload.agentInstanceId,
                workspaceId: payload.workspaceId,
                lastActivityAt: event.ts,
              }),
            });
          }
          break;
        }
        case "turn": {
          if (payload.turn && typeof payload.turn === "object") {
            this.upsertTurn({ ...payload.turn, id: payload.turn.id ?? payload.turnId });
          } else if (payload.turnId) {
            this.upsertTurn(
              prune({
                id: payload.turnId,
                sessionId: event.sessionId,
                runId: payload.runId,
                state: payload.state,
                providerTurnId: payload.providerTurnId,
                terminalConfidence: payload.terminalConfidence,
                stopReason: payload.stopReason,
                updatedAt: event.ts,
                endedAt: payload.endedAt,
              }),
            );
          }
          break;
        }
        case "run": {
          if (payload.run && typeof payload.run === "object") {
            this.upsertRun({ ...payload.run, id: payload.run.id ?? payload.runId });
          } else if (payload.runId) {
            this.upsertRun(
              prune({
                id: payload.runId,
                sessionId: event.sessionId,
                state: payload.state,
                reason: payload.reason,
                replacementOf: payload.replacementOf,
                updatedAt: event.ts,
                endedAt: payload.endedAt,
              }),
            );
          }
          break;
        }
        case "interaction": {
          if (payload.request && typeof payload.request === "object") {
            this.upsertInteraction({ ...payload.request, id: payload.request.id ?? payload.requestId });
          } else if (payload.requestId) {
            this.upsertInteraction(
              prune({
                id: payload.requestId,
                sessionId: event.sessionId,
                turnId: payload.turnId,
                kind: payload.interactionKind ?? payload.kind,
                state: payload.state,
                prompt: payload.prompt,
                answeredAt: payload.answeredAt,
              }),
            );
          }
          break;
        }
        case "notification": {
          if (payload.notification && typeof payload.notification === "object") {
            this.addNotification(payload.notification);
          } else if (payload.notificationId || payload.title) {
            this.addNotification({
              id: payload.notificationId,
              type: payload.notificationType ?? payload.type ?? "info",
              sessionId: event.sessionId,
              title: payload.title,
              body: payload.body,
              level: payload.level,
              createdAt: event.ts,
            });
          }
          break;
        }
        case "artifact": {
          if (payload.artifact && typeof payload.artifact === "object") {
            this.upsertArtifact(payload.artifact);
          } else if (payload.artifactId) {
            this.upsertArtifact({
              id: payload.artifactId,
              sessionId: event.sessionId,
              nodeId: payload.nodeId,
              sha256: payload.sha256,
              size: payload.size,
              mime: payload.mime,
              name: payload.name,
              createdAt: event.ts,
            });
          }
          break;
        }
        default:
          break;
      }
    } catch {
      // 投影失败不应把适配器异常帧变成核心崩溃；事件仍持久化，重建时会暴露问题。
    }
  }

  /** 从事件重建投影并比对；用 SAVEPOINT 回滚，绝不破坏现有数据。 */
  verifyReplay() {
    this.#assertOpen();
    const name = `verify_${++this.#savepointSeq}`;
    this.db.exec(`SAVEPOINT ${name}`);
    try {
      const before = this.projectionCounts();
      this.db.exec(
        "DELETE FROM sessions; DELETE FROM runs; DELETE FROM turns; DELETE FROM interactions; DELETE FROM artifacts; DELETE FROM notifications;",
      );
      let replayed = 0;
      for (const row of this.db.prepare("SELECT seq, id, kind, session_id, ts, payload FROM events ORDER BY seq ASC").all()) {
        this.applyEventToProjection({
          id: row.id,
          seq: Number(row.seq),
          kind: row.kind,
          sessionId: row.session_id,
          ts: row.ts,
          payload: parseJson(row.payload, {}),
        });
        replayed += 1;
      }
      const after = this.projectionCounts();
      // notifications 是有界的派生缓存（会按容量裁剪），不参与重放一致性判定。
      const ok = ["sessions", "runs", "turns", "interactions", "artifacts"].every((k) => before[k] === after[k]);
      return { ok, replayed, before, after };
    } finally {
      this.db.exec(`ROLLBACK TO ${name}`);
      this.db.exec(`RELEASE ${name}`);
    }
  }

  projectionCounts() {
    this.#assertOpen();
    const one = (sql) => Number(this.db.prepare(sql).get().n ?? 0);
    return {
      events: this.eventCount(),
      latestSeq: this.latestSeq(),
      sessions: one("SELECT COUNT(*) AS n FROM sessions"),
      runs: one("SELECT COUNT(*) AS n FROM runs"),
      turns: one("SELECT COUNT(*) AS n FROM turns"),
      interactions: one("SELECT COUNT(*) AS n FROM interactions"),
      artifacts: one("SELECT COUNT(*) AS n FROM artifacts"),
      notifications: one("SELECT COUNT(*) AS n FROM notifications"),
    };
  }

  /* ---------------------------------------------------------------- session */

  #sessionExists(id) {
    return !!this.db.prepare("SELECT 1 AS x FROM sessions WHERE session_id = ?").get(id);
  }

  upsertSession(input = {}) {
    this.#assertOpen();
    const id = input.id ?? input.sessionId;
    if (typeof id !== "string" || id.length === 0) throw new ValidationError("session id is required");
    const existing = this.getSession(id);
    const now = this.nowIso();
    const merged = {
      ...(existing ?? {}),
      ...prune(input),
      id,
      state: input.state ?? existing?.state ?? "idle",
      createdAt: existing?.createdAt ?? input.createdAt ?? now,
      updatedAt: input.updatedAt ?? now,
      lastActivityAt: input.lastActivityAt ?? existing?.lastActivityAt ?? null,
      options: input.options ?? existing?.options ?? {},
      archived: input.archived ?? existing?.archived ?? false,
      unread: input.unread ?? existing?.unread ?? 0,
      lastSeq: Math.max(Number(existing?.lastSeq ?? 0), Number(input.lastSeq ?? 0)),
    };
    const { lastSeq, ...json } = merged;
    this.db
      .prepare(
        `INSERT INTO sessions(session_id, node_id, state, last_seq, updated_at, json)
         VALUES(?, ?, ?, ?, ?, ?)
         ON CONFLICT(session_id) DO UPDATE SET
           node_id = excluded.node_id,
           state = excluded.state,
           last_seq = excluded.last_seq,
           updated_at = excluded.updated_at,
           json = excluded.json`,
      )
      .run(
        id,
        merged.nodeId ?? null,
        merged.state ?? "idle",
        Number(lastSeq ?? 0),
        merged.updatedAt ?? now,
        JSON.stringify(json),
      );
    return this.getSession(id);
  }

  getSession(id) {
    this.#assertOpen();
    const row = this.db
      .prepare("SELECT session_id, node_id, state, last_seq, updated_at, json FROM sessions WHERE session_id = ?")
      .get(id);
    if (!row) return null;
    const json = parseJson(row.json, {});
    return {
      ...json,
      id: row.session_id,
      nodeId: row.node_id ?? json.nodeId ?? null,
      state: row.state,
      lastSeq: Number(row.last_seq),
      updatedAt: row.updated_at ?? json.updatedAt ?? null,
    };
  }

  listSessions({ limit = 200, nodeId = null, includeArchived = false } = {}) {
    this.#assertOpen();
    if (!Number.isInteger(limit) || limit < 1) throw new ValidationError("limit must be a positive integer");
    const rows = this.db
      .prepare(
        `SELECT session_id, node_id, state, last_seq, updated_at, json FROM sessions
         WHERE (? IS NULL OR node_id = ?)
         ORDER BY COALESCE(updated_at, '') DESC, session_id ASC
         LIMIT ?`,
      )
      .all(nodeId, nodeId, limit);
    return rows
      .map((row) => {
        const json = parseJson(row.json, {});
        return {
          ...json,
          id: row.session_id,
          nodeId: row.node_id ?? json.nodeId ?? null,
          state: row.state,
          lastSeq: Number(row.last_seq),
          updatedAt: row.updated_at ?? json.updatedAt ?? null,
        };
      })
      .filter((s) => includeArchived || !s.archived);
  }

  sessionProjection(sessionId, { eventLimit = 200 } = {}) {
    const session = this.getSession(sessionId);
    if (!session) return { sessionId, session: null, runs: [], turns: [], interactions: [], artifacts: [], events: [], lastSeq: 0 };
    return {
      sessionId,
      session,
      runs: this.listRuns(sessionId),
      turns: this.listTurns(sessionId),
      interactions: this.listInteractions({ sessionId }),
      artifacts: this.listArtifacts(sessionId),
      events: this.readSince(Math.max(0, session.lastSeq - eventLimit), { limit: eventLimit, sessionId }),
      lastSeq: session.lastSeq,
    };
  }

  /* -------------------------------------------------------------------- run */

  upsertRun(input = {}) {
    this.#assertOpen();
    const id = input.id ?? input.runId;
    if (typeof id !== "string" || id.length === 0) throw new ValidationError("run id is required");
    const existing = this.getRun(id);
    const now = this.nowIso();
    const merged = {
      ...(existing ?? {}),
      ...prune(input),
      id,
      state: input.state ?? existing?.state ?? "queued",
      createdAt: existing?.createdAt ?? input.createdAt ?? now,
      updatedAt: input.updatedAt ?? now,
    };
    this.db
      .prepare(
        `INSERT INTO runs(run_id, session_id, state, json) VALUES(?, ?, ?, ?)
         ON CONFLICT(run_id) DO UPDATE SET session_id = excluded.session_id, state = excluded.state, json = excluded.json`,
      )
      .run(id, merged.sessionId ?? null, merged.state, JSON.stringify(merged));
    return this.getRun(id);
  }

  getRun(id) {
    this.#assertOpen();
    const row = this.db.prepare("SELECT json FROM runs WHERE run_id = ?").get(id);
    return row ? parseJson(row.json, null) : null;
  }

  listRuns(sessionId) {
    this.#assertOpen();
    return this.db
      .prepare("SELECT json FROM runs WHERE session_id = ? ORDER BY rowid ASC")
      .all(sessionId)
      .map((r) => parseJson(r.json, null))
      .filter(Boolean);
  }

  /* ------------------------------------------------------------------- turn */

  upsertTurn(input = {}) {
    this.#assertOpen();
    const id = input.id ?? input.turnId;
    if (typeof id !== "string" || id.length === 0) throw new ValidationError("turn id is required");
    const existing = this.getTurn(id);
    const now = this.nowIso();
    const merged = {
      ...(existing ?? {}),
      ...prune(input),
      id,
      state: input.state ?? existing?.state ?? "queued",
      createdAt: existing?.createdAt ?? input.createdAt ?? now,
      updatedAt: input.updatedAt ?? now,
    };
    this.db
      .prepare(
        `INSERT INTO turns(turn_id, session_id, run_id, state, json) VALUES(?, ?, ?, ?, ?)
         ON CONFLICT(turn_id) DO UPDATE SET session_id = excluded.session_id, run_id = excluded.run_id, state = excluded.state, json = excluded.json`,
      )
      .run(id, merged.sessionId ?? null, merged.runId ?? null, merged.state, JSON.stringify(merged));
    return this.getTurn(id);
  }

  getTurn(id) {
    this.#assertOpen();
    const row = this.db.prepare("SELECT json FROM turns WHERE turn_id = ?").get(id);
    return row ? parseJson(row.json, null) : null;
  }

  listTurns(sessionId, { states = null } = {}) {
    this.#assertOpen();
    const rows = this.db
      .prepare("SELECT json, state FROM turns WHERE session_id = ? ORDER BY rowid ASC")
      .all(sessionId);
    return rows
      .filter((r) => !states || states.includes(r.state))
      .map((r) => parseJson(r.json, null))
      .filter(Boolean);
  }

  findActiveTurn(sessionId) {
    return this.listTurns(sessionId, { states: ["queued", "running", "stopping"] }).at(-1) ?? null;
  }

  /* ------------------------------------------------------------- interaction */

  upsertInteraction(input = {}) {
    this.#assertOpen();
    const id = input.id ?? input.requestId;
    if (typeof id !== "string" || id.length === 0) throw new ValidationError("interaction id is required");
    const existing = this.getInteraction(id);
    const now = this.nowIso();
    const merged = {
      ...(existing ?? {}),
      ...prune(input),
      id,
      state: input.state ?? existing?.state ?? "pending",
      createdAt: existing?.createdAt ?? input.createdAt ?? now,
    };
    this.db
      .prepare(
        `INSERT INTO interactions(request_id, session_id, state, json) VALUES(?, ?, ?, ?)
         ON CONFLICT(request_id) DO UPDATE SET session_id = excluded.session_id, state = excluded.state, json = excluded.json`,
      )
      .run(id, merged.sessionId ?? null, merged.state, JSON.stringify(merged));
    return this.getInteraction(id);
  }

  getInteraction(id) {
    this.#assertOpen();
    const row = this.db.prepare("SELECT json FROM interactions WHERE request_id = ?").get(id);
    return row ? parseJson(row.json, null) : null;
  }

  listInteractions({ sessionId = null, state = null, limit = 200 } = {}) {
    this.#assertOpen();
    const rows = this.db
      .prepare(
        `SELECT json, state FROM interactions
         WHERE (? IS NULL OR session_id = ?) AND (? IS NULL OR state = ?)
         ORDER BY rowid ASC LIMIT ?`,
      )
      .all(sessionId, sessionId, state, state, limit);
    return rows.map((r) => parseJson(r.json, null)).filter(Boolean);
  }

  /* ---------------------------------------------------------------- artifact */

  upsertArtifact(input = {}) {
    this.#assertOpen();
    const id = input.id ?? input.artifactId;
    if (typeof id !== "string" || id.length === 0) throw new ValidationError("artifact id is required");
    const existing = this.getArtifact(id);
    const merged = { ...(existing ?? {}), ...prune(input), id };
    this.db
      .prepare(
        `INSERT INTO artifacts(artifact_id, session_id, sha256, json) VALUES(?, ?, ?, ?)
         ON CONFLICT(artifact_id) DO UPDATE SET session_id = excluded.session_id, sha256 = excluded.sha256, json = excluded.json`,
      )
      .run(id, merged.sessionId ?? null, merged.sha256 ?? null, JSON.stringify(merged));
    return this.getArtifact(id);
  }

  getArtifact(id) {
    this.#assertOpen();
    const row = this.db.prepare("SELECT json FROM artifacts WHERE artifact_id = ?").get(id);
    return row ? parseJson(row.json, null) : null;
  }

  listArtifacts(sessionId) {
    this.#assertOpen();
    return this.db
      .prepare("SELECT json FROM artifacts WHERE session_id = ? ORDER BY artifact_id ASC")
      .all(sessionId)
      .map((r) => parseJson(r.json, null))
      .filter(Boolean);
  }

  /* ------------------------------------------------------------ notification */

  addNotification(input = {}) {
    this.#assertOpen();
    const id = input.id ?? input.notificationId ?? makeId("evt");
    const createdAt = input.createdAt ?? this.nowIso();
    const record = {
      id,
      type: input.type ?? "info",
      sessionId: input.sessionId ?? null,
      title: input.title ?? "",
      body: input.body ?? null,
      level: input.level ?? "info",
      read: !!input.read,
      createdAt,
    };
    this.db
      .prepare(
        `INSERT INTO notifications(notification_id, session_id, type, created_at, read, json) VALUES(?, ?, ?, ?, ?, ?)
         ON CONFLICT(notification_id) DO UPDATE SET read = excluded.read, json = excluded.json`,
      )
      .run(id, record.sessionId, record.type, createdAt, record.read ? 1 : 0, JSON.stringify(record));
    return record;
  }

  listNotifications({ limit = 100, unreadOnly = false } = {}) {
    this.#assertOpen();
    const rows = this.db
      .prepare(
        `SELECT json, read FROM notifications
         WHERE (? = 0 OR read = 0)
         ORDER BY created_at DESC LIMIT ?`,
      )
      .all(unreadOnly ? 1 : 0, limit);
    return rows.map((r) => ({ ...parseJson(r.json, {}), read: !!r.read }));
  }

  markNotificationRead(id) {
    this.#assertOpen();
    const row = this.db.prepare("SELECT json FROM notifications WHERE notification_id = ?").get(id);
    if (!row) return null;
    const record = { ...parseJson(row.json, {}), read: true };
    this.db
      .prepare("UPDATE notifications SET read = 1, json = ? WHERE notification_id = ?")
      .run(JSON.stringify(record), id);
    return record;
  }

  unreadNotificationCount() {
    this.#assertOpen();
    return Number(this.db.prepare("SELECT COUNT(*) AS n FROM notifications WHERE read = 0").get().n ?? 0);
  }

  /** 通知持久化也必须有界：只保留最近 keep 条。 */
  pruneNotifications(keep = 1000) {
    this.#assertOpen();
    const limit = Number.isInteger(keep) && keep >= 0 ? keep : 1000;
    this.db
      .prepare("DELETE FROM notifications WHERE rowid NOT IN (SELECT rowid FROM notifications ORDER BY rowid DESC LIMIT ?)")
      .run(limit);
    return limit;
  }

  /* ----------------------------------------------------------------- objects */

  putObject(kind, obj) {
    this.#assertOpen();
    if (typeof kind !== "string" || kind.length === 0) throw new ValidationError("object kind is required");
    const id = obj?.id;
    if (typeof id !== "string" || id.length === 0) throw new ValidationError("object id is required");
    this.db
      .prepare(
        `INSERT INTO objects(kind, id, json, updated_at) VALUES(?, ?, ?, ?)
         ON CONFLICT(kind, id) DO UPDATE SET json = excluded.json, updated_at = excluded.updated_at`,
      )
      .run(kind, id, JSON.stringify(obj), this.nowIso());
    return obj;
  }

  getObject(kind, id) {
    this.#assertOpen();
    const row = this.db.prepare("SELECT json FROM objects WHERE kind = ? AND id = ?").get(kind, id);
    return row ? parseJson(row.json, null) : null;
  }

  listObjects(kind, { limit = 500 } = {}) {
    this.#assertOpen();
    return this.db
      .prepare("SELECT json FROM objects WHERE kind = ? ORDER BY updated_at DESC, id ASC LIMIT ?")
      .all(kind, limit)
      .map((r) => parseJson(r.json, null))
      .filter(Boolean);
  }

  deleteObject(kind, id) {
    this.#assertOpen();
    this.db.prepare("DELETE FROM objects WHERE kind = ? AND id = ?").run(kind, id);
  }
}

/** 便捷工厂：打开一个 journal。 */
export function openJournal(options) {
  const journal = new Journal(options);
  journal.open();
  return journal;
}
