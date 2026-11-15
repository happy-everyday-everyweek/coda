/**
 * 同步侧持久化（SYNC-104 / REL-004）。
 *
 * 两种实现，接口一致：
 * - `createMemoryStorage()`：测试/短生命周期；
 * - `createFileStorage({path})`：原子 JSON 持久化（0600），用于重启恢复与收敛测试。
 *
 * 该存储只保存「已持久化的对象与命令收据」，不产生权威业务状态（CONTRACT §1）。
 * schema 版本不匹配时拒绝启动且不写旧库（REL-004：保留旧库与回退路径）。
 */
import { existsSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { createHash, randomUUID } from "node:crypto";

import { readSecretFile, writeSecretFile } from "../security/secrets.mjs";
import { contentHash, orderOps } from "./cursors.mjs";

export const STORAGE_SCHEMA_VERSION = 1;
const DEFAULT_MAX_OPS = 20_000;

export class SyncStorageError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "SyncStorageError";
    this.code = code;
    this.details = details;
  }
}

function emptyState() {
  return {
    schemaVersion: STORAGE_SCHEMA_VERSION,
    opSeq: 0,
    earliestOpSeq: 1,
    ops: [],
    sessions: {},
    events: {},
    paMessages: {},
    cursors: {},
    commands: {},
    interactions: {},
    artifacts: {},
    blobs: {},
    kv: {},
  };
}

function clone(value) {
  return value === undefined ? undefined : structuredClone(value);
}

function cursorKey(peerId, stream = "ops") {
  return `${peerId}|${stream}`;
}

function sortEvents(events) {
  return [...events].sort((a, b) => a.seq - b.seq);
}

/**
 * 插入/合并事件。同一 (sessionId, seq) 出现不同内容时（分叉），按内容哈希取小者，
 * 两端无论到达顺序如何都会得到同一结果。
 */
function putEventRecord(state, event) {
  if (!event?.sessionId) throw new SyncStorageError("INVALID_EVENT", "event requires sessionId");
  if (!Number.isInteger(event.seq) || event.seq < 1) throw new SyncStorageError("INVALID_EVENT", "event requires seq");
  const list = state.events[event.sessionId] ?? [];
  const existingIndex = list.findIndex((e) => e.seq === event.seq);
  const record = clone({ ...event, id: event.id ?? `evt_${randomUUID().replaceAll("-", "")}` });
  if (existingIndex >= 0) {
    const existing = list[existingIndex];
    if (contentHash(existing) === contentHash(record)) {
      return { event: clone(existing), duplicate: true, conflict: false, replaced: false };
    }
    const keepIncoming = contentHash(record) < contentHash(existing);
    const winner = keepIncoming ? record : existing;
    const loserHash = contentHash(keepIncoming ? existing : record);
    const merged = { ...winner, conflict: true, alternateHash: loserHash };
    list[existingIndex] = merged;
    state.events[event.sessionId] = sortEvents(list);
    return { event: clone(merged), duplicate: false, conflict: true, replaced: keepIncoming };
  }
  list.push(record);
  state.events[event.sessionId] = sortEvents(list);
  return { event: clone(record), duplicate: false, conflict: false, replaced: false };
}

function putPaRecord(state, message) {
  if (!message?.id) throw new SyncStorageError("INVALID_PA_MESSAGE", "PA message requires id");
  const existing = state.paMessages[message.id];
  if (!existing) {
    state.paMessages[message.id] = clone(message);
    return { message: clone(message), duplicate: false, conflict: false };
  }
  if (contentHash(existing) === contentHash(message)) {
    return { message: clone(existing), duplicate: true, conflict: false };
  }
  const keepIncoming = contentHash(message) < contentHash(existing);
  const winner = keepIncoming ? message : existing;
  const merged = {
    ...winner,
    conflict: true,
    alternateHash: contentHash(keepIncoming ? existing : message),
  };
  state.paMessages[message.id] = clone(merged);
  return { message: clone(merged), duplicate: false, conflict: true };
}

function createStorageApi(state, { persist = null, clock = Date.now, maxOps = DEFAULT_MAX_OPS } = {}) {
  const save = () => {
    if (persist) persist(state);
  };
  const iso = () => new Date(clock()).toISOString();

  return {
    get schemaVersion() {
      return state.schemaVersion;
    },
    get opSeq() {
      return state.opSeq;
    },
    earliestOpSeq() {
      return state.earliestOpSeq ?? 1;
    },

    // ── 操作日志 / 游标 ────────────────────────────────────────
    appendOp({ type, data, origin, ts = iso() }) {
      if (typeof type !== "string" || type.length === 0) {
        throw new SyncStorageError("INVALID_OP", "op type required");
      }
      const op = {
        opId: `op_${randomUUID().replaceAll("-", "")}`,
        origin,
        seq: state.opSeq + 1,
        type,
        data: clone(data),
        ts,
      };
      state.opSeq = op.seq;
      state.ops.push(op);
      if (state.ops.length > maxOps) {
        state.ops.splice(0, state.ops.length - maxOps);
        state.earliestOpSeq = state.ops[0]?.seq ?? state.opSeq;
      }
      save();
      return clone(op);
    },

    listOpsAfter(seq, { limit = Number.POSITIVE_INFINITY } = {}) {
      const from = Number.isInteger(seq) ? seq : 0;
      return orderOps(state.ops.filter((op) => op.seq > from))
        .slice(0, limit)
        .map(clone);
    },

    getOp(seq) {
      return clone(state.ops.find((op) => op.seq === seq) ?? null);
    },

    // ── 会话与事件 ────────────────────────────────────────────
    upsertSession(session) {
      if (!session?.id) throw new SyncStorageError("INVALID_SESSION", "session requires id");
      state.sessions[session.id] = clone(session);
      save();
      return clone(session);
    },
    getSession(id) {
      return clone(state.sessions[id] ?? null);
    },
    listSessions() {
      return Object.values(state.sessions).map(clone).sort((a, b) => a.id.localeCompare(b.id));
    },

    appendEvent(event) {
      const result = putEventRecord(state, event);
      save();
      return result;
    },
    putEvent(event) {
      return this.appendEvent(event);
    },
    listEvents(sessionId, { fromSeq = 1, beforeSeq = null, limit = Number.POSITIVE_INFINITY } = {}) {
      const list = (state.events[sessionId] ?? []).filter(
        (e) => e.seq >= fromSeq && (beforeSeq === null || e.seq < beforeSeq),
      );
      return list.slice(0, limit).map(clone);
    },
    eventRange(sessionId) {
      const list = state.events[sessionId] ?? [];
      if (list.length === 0) return null;
      return { firstSeq: list[0].seq, lastSeq: list[list.length - 1].seq, count: list.length };
    },

    // ── PA 日志 ───────────────────────────────────────────────
    appendPaMessage(message) {
      const result = putPaRecord(state, message);
      save();
      return result;
    },
    getPaMessage(id) {
      return clone(state.paMessages[id] ?? null);
    },
    listPaMessages() {
      return Object.values(state.paMessages).map(clone);
    },

    // ── 游标 ─────────────────────────────────────────────────
    getCursor(peerId, stream = "ops") {
      return clone(state.cursors[cursorKey(peerId, stream)] ?? null);
    },
    putCursor(cursor) {
      if (!cursor?.peerId) throw new SyncStorageError("INVALID_CURSOR", "cursor requires peerId");
      state.cursors[cursorKey(cursor.peerId, cursor.stream ?? "ops")] = clone(cursor);
      save();
      return clone(cursor);
    },
    listCursors() {
      return Object.values(state.cursors).map(clone);
    },

    // ── 命令与收据 ────────────────────────────────────────────
    getCommand(id) {
      return clone(state.commands[id] ?? null);
    },
    putCommand(record) {
      if (!record?.commandId) throw new SyncStorageError("INVALID_COMMAND", "command record requires commandId");
      state.commands[record.commandId] = clone(record);
      save();
      return clone(record);
    },
    listCommands({ targetDeviceId = null, state: wantedState = null } = {}) {
      return Object.values(state.commands)
        .filter((c) => (!targetDeviceId || c.targetDeviceId === targetDeviceId) && (!wantedState || c.state === wantedState))
        .map(clone)
        .sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.commandId.localeCompare(b.commandId));
    },
    listCommandReceipts() {
      return Object.values(state.commands)
        .filter((c) => c.receipt && c.state !== "accepted")
        .map((c) => ({ commandId: c.commandId, receipt: clone(c.receipt), state: c.state }));
    },

    // ── 审批/提问 ────────────────────────────────────────────
    getInteraction(id) {
      return clone(state.interactions[id] ?? null);
    },
    putInteraction(record) {
      if (!record?.id) throw new SyncStorageError("INVALID_INTERACTION", "interaction requires id");
      state.interactions[record.id] = clone(record);
      save();
      return clone(record);
    },
    listInteractions() {
      return Object.values(state.interactions).map(clone).sort((a, b) => a.id.localeCompare(b.id));
    },

    // ── 成果清单与分块 ────────────────────────────────────────
    getArtifact(id) {
      return clone(state.artifacts[id] ?? null);
    },
    putArtifact(record) {
      if (!record?.id) throw new SyncStorageError("INVALID_ARTIFACT", "artifact requires id");
      state.artifacts[record.id] = clone(record);
      save();
      return clone(record);
    },
    listArtifacts({ sessionId = null } = {}) {
      return Object.values(state.artifacts)
        .filter((a) => !sessionId || a.sessionId === sessionId)
        .map(clone)
        .sort((a, b) => a.id.localeCompare(b.id));
    },
    putBlob(id, buffer) {
      if (!Buffer.isBuffer(buffer)) throw new SyncStorageError("INVALID_BLOB", "blob must be a Buffer");
      const record = {
        base64: buffer.toString("base64"),
        size: buffer.length,
        sha256: createHash("sha256").update(buffer).digest("hex"),
      };
      state.blobs[id] = record;
      save();
      return { id, size: record.size, sha256: record.sha256 };
    },
    getBlob(id) {
      const record = state.blobs[id];
      return record ? Buffer.from(record.base64, "base64") : null;
    },
    hasBlob(id) {
      return Object.hasOwn(state.blobs, id);
    },
    deleteBlob(id) {
      if (!state.blobs[id]) return false;
      delete state.blobs[id];
      save();
      return true;
    },
    blobStats(id) {
      const record = state.blobs[id];
      return record ? { size: record.size, sha256: record.sha256 } : null;
    },

    // ── 通用 KV（历史覆盖、恢复标记等）────────────────────────
    kvGet(key, fallback = null) {
      return Object.hasOwn(state.kv, key) ? clone(state.kv[key]) : fallback;
    },
    kvSet(key, value) {
      state.kv[key] = clone(value);
      save();
      return clone(value);
    },
    kvDelete(key) {
      if (!Object.hasOwn(state.kv, key)) return false;
      delete state.kv[key];
      save();
      return true;
    },

    // ── 读模型 / 维护 ─────────────────────────────────────────
    dump() {
      return {
        schemaVersion: state.schemaVersion,
        opSeq: state.opSeq,
        ops: state.ops.map(clone),
        sessions: Object.values(state.sessions).map(clone),
        events: Object.fromEntries(Object.entries(state.events).map(([k, v]) => [k, v.map(clone)])),
        paMessages: Object.values(state.paMessages).map(clone),
        cursors: Object.values(state.cursors).map(clone),
        commands: Object.values(state.commands).map(clone),
        interactions: Object.values(state.interactions).map(clone),
        artifacts: Object.values(state.artifacts).map(clone),
        kv: clone(state.kv),
      };
    },
    stats() {
      return {
        schemaVersion: state.schemaVersion,
        opSeq: state.opSeq,
        ops: state.ops.length,
        sessions: Object.keys(state.sessions).length,
        events: Object.values(state.events).reduce((sum, list) => sum + list.length, 0),
        paMessages: Object.keys(state.paMessages).length,
        commands: Object.keys(state.commands).length,
        interactions: Object.keys(state.interactions).length,
        artifacts: Object.keys(state.artifacts).length,
        blobs: Object.keys(state.blobs).length,
      };
    },
    flush() {
      save();
    },
    close() {
      save();
    },
    /** 测试/诊断用：原始状态只读副本。 */
    rawState() {
      return clone(state);
    },
  };
}

export function createMemoryStorage({ clock = Date.now, maxOps } = {}) {
  return createStorageApi(emptyState(), { persist: null, clock, maxOps });
}

/** 原子持久化的文件存储。目录 0700、文件 0600。 */
export function createFileStorage({ path, clock = Date.now, maxOps } = {}) {
  if (!path) throw new SyncStorageError("PATH_REQUIRED", "createFileStorage requires {path}");
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  let state = emptyState();
  if (existsSync(path)) {
    const raw = readSecretFile(path);
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      throw new SyncStorageError("STORAGE_CORRUPT", `storage file is not valid JSON: ${path}`);
    }
    if (parsed?.schemaVersion !== STORAGE_SCHEMA_VERSION) {
      throw new SyncStorageError(
        "STORAGE_SCHEMA_MISMATCH",
        `unsupported storage schema ${parsed?.schemaVersion}; expected ${STORAGE_SCHEMA_VERSION}. ` +
          "Refusing to write; upgrade Coda or restore a backup.",
        { path, found: parsed?.schemaVersion, expected: STORAGE_SCHEMA_VERSION },
      );
    }
    state = { ...emptyState(), ...parsed };
    if (!Array.isArray(state.ops)) state.ops = [];
    state.earliestOpSeq = state.earliestOpSeq ?? state.ops[0]?.seq ?? 1;
  }
  let closed = false;
  const persist = (current) => {
    if (closed) return;
    writeSecretFile(path, JSON.stringify(current));
  };
  persist(state);
  const api = createStorageApi(state, { persist, clock, maxOps });
  // 用原型继承保留 api 上的 getter（opSeq / schemaVersion 必须实时）；
  // 直接对象展开会把 getter 求值成创建时的快照。
  const wrapped = Object.create(api);
  wrapped.path = path;
  wrapped.flush = () => api.flush();
  wrapped.close = () => {
    api.close();
    closed = true;
  };
  return wrapped;
}
