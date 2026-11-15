/**
 * 同步游标、Lamport 时钟与确定性合并（SYNC-104 / SYNC-013）。
 *
 * - 每个对端、每条流各有一个 SyncCursor：远端进度、已确认、快照版本。
 * - 游标只前进；缺口交给上层请求快照/补洞，不允许静默跳过。
 * - PA 消息是多写入者追加日志：用 (lamport, author, id) 形成全序，
 *   重复按 id 去重，同 id 不同内容按内容哈希确定性取一个（不依赖到达顺序）。
 */
import { createHash } from "node:crypto";
import { makeId } from "../contracts/ids.mjs";

export const CURSOR_SCHEMA_VERSION = 1;

export class CursorError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "CursorError";
    this.code = code;
    this.details = details;
  }
}

function iso(at) {
  return new Date(at).toISOString();
}

export function createSyncCursor({ peerId, stream = "ops", now = Date.now } = {}) {
  if (typeof peerId !== "string" || peerId.length === 0) {
    throw new CursorError("PEER_REQUIRED", "cursor requires peerId");
  }
  return {
    schemaVersion: CURSOR_SCHEMA_VERSION,
    id: makeId("cur"),
    peerId,
    stream,
    remoteSeq: 0,
    ackSeq: 0,
    snapshotVersion: 0,
    updatedAt: iso(now()),
    lastError: null,
  };
}

/** 前进一格；跳号返回 gap，不修改游标。 */
export function advanceCursor(cursor, seq, { at = Date.now() } = {}) {
  if (!Number.isInteger(seq) || seq < 1) {
    throw new CursorError("INVALID_SEQ", "seq must be a positive integer");
  }
  if (seq <= cursor.remoteSeq) return { cursor, advanced: false, skipped: true, gap: 0 };
  if (seq !== cursor.remoteSeq + 1) {
    return {
      cursor,
      advanced: false,
      skipped: false,
      gap: seq - cursor.remoteSeq - 1,
      expected: cursor.remoteSeq + 1,
    };
  }
  cursor.remoteSeq = seq;
  cursor.updatedAt = iso(at);
  return { cursor, advanced: true, skipped: false, gap: 0 };
}

export function acknowledgeCursor(cursor, seq, { at = Date.now() } = {}) {
  if (!Number.isInteger(seq) || seq < 0) throw new CursorError("INVALID_SEQ", "ack seq must be >= 0");
  if (seq > cursor.ackSeq) {
    cursor.ackSeq = seq;
    cursor.updatedAt = iso(at);
  }
  return cursor;
}

/** 合并两个游标（重连/快照后可能同时存在旧副本）；结果是确定的最大进度。 */
export function mergeCursors(a, b, { at = Date.now() } = {}) {
  if (!a) return b ?? null;
  if (!b) return a;
  if (a.peerId !== b.peerId || a.stream !== b.stream) {
    throw new CursorError("CURSOR_MISMATCH", "cannot merge cursors from different peers/streams");
  }
  const pickUpdated = a.remoteSeq > b.remoteSeq ? a : b.remoteSeq > a.remoteSeq ? b : a.updatedAt >= b.updatedAt ? a : b;
  return {
    ...pickUpdated,
    remoteSeq: Math.max(a.remoteSeq, b.remoteSeq),
    ackSeq: Math.max(a.ackSeq, b.ackSeq),
    snapshotVersion: Math.max(a.snapshotVersion ?? 0, b.snapshotVersion ?? 0),
    updatedAt: iso(at),
  };
}

export function serializeCursor(cursor) {
  return JSON.stringify({ ...cursor, schemaVersion: CURSOR_SCHEMA_VERSION });
}

export function parseCursor(json) {
  let parsed;
  try {
    parsed = typeof json === "string" ? JSON.parse(json) : json;
  } catch {
    throw new CursorError("CURSOR_PARSE_FAILED", "cursor is not valid JSON");
  }
  if (!parsed || typeof parsed !== "object") throw new CursorError("CURSOR_PARSE_FAILED", "cursor must be an object");
  if (parsed.schemaVersion !== CURSOR_SCHEMA_VERSION) {
    throw new CursorError(
      "CURSOR_SCHEMA_MISMATCH",
      `unsupported cursor schema ${parsed.schemaVersion}; expected ${CURSOR_SCHEMA_VERSION}. ` +
        "Upgrade Coda; existing data is left untouched.",
      { schemaVersion: parsed.schemaVersion, expected: CURSOR_SCHEMA_VERSION },
    );
  }
  if (typeof parsed.peerId !== "string" || !Number.isInteger(parsed.remoteSeq) || parsed.remoteSeq < 0) {
    throw new CursorError("CURSOR_INVALID", "cursor is missing peerId/remoteSeq");
  }
  return { ...parsed };
}

export function canResumeFrom(cursor, earliestRetainedSeq) {
  if (!cursor) return false;
  return cursor.remoteSeq + 1 >= earliestRetainedSeq;
}

/** Lamport 时钟：本地事件 tick，收到远端事件 observe。 */
export function createLamportClock(initial = 0) {
  let value = Number.isInteger(initial) && initial >= 0 ? initial : 0;
  return {
    tick() {
      value += 1;
      return value;
    },
    observe(remote) {
      if (Number.isInteger(remote) && remote > value) value = remote;
      return value;
    },
    value() {
      return value;
    },
  };
}

/** 稳定序列化（对象键排序），用于内容哈希。 */
export function stableStringify(value) {
  if (value === null || value === undefined) return "null";
  if (typeof value === "number" || typeof value === "boolean") return JSON.stringify(value);
  if (typeof value === "string") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map((v) => stableStringify(v)).join(",")}]`;
  if (typeof value === "object") {
    const keys = Object.keys(value).sort();
    return `{${keys.map((k) => `${JSON.stringify(k)}:${stableStringify(value[k])}`).join(",")}}`;
  }
  return JSON.stringify(String(value));
}

export function contentHash(value) {
  return createHash("sha256").update(stableStringify(value)).digest("hex");
}

/**
 * PA 全序键：
 * - 规范消息（pa/schema.mjs）用 HLC 字符串（零填充，字典序即时间序）+ sourceNode + id；
 * - 旧格式消息回退到 lamport + author + id。
 * 纯字符串比较，避免 locale 差异。
 */
export function paOrderKey(message) {
  const clock =
    typeof message?.hlc === "string"
      ? message.hlc
      : String(Number.isInteger(message?.lamport) ? message.lamport : 0).padStart(16, "0");
  const node = message?.sourceNode ?? message?.author ?? "";
  return `${clock}|${node}|${message?.id ?? ""}`;
}

export function sortPaMessages(messages) {
  return [...messages].sort((a, b) => paOrderKey(a).localeCompare(paOrderKey(b)));
}

/**
 * 合并两端的 PA 日志。
 * - 以 id 去重；
 * - 同 id 不同内容（异常分叉）按哈希字典序取小者，并标记 conflict，保证两端一致；
 * - 输出按 HLC/sourceNode/id（旧数据为 lamport/author/id）全序排列，与到达顺序无关。
 */
export function mergePaLog(local = [], remote = []) {
  const map = new Map();
  const ingest = (message) => {
    if (!message || typeof message.id !== "string" || message.id.length === 0) return;
    const hash = contentHash(message);
    const existing = map.get(message.id);
    if (!existing) {
      map.set(message.id, { message: { ...message }, hash, conflict: !!message.conflict });
      return;
    }
    if (existing.hash === hash) return;
    const keepIncoming = hash < existing.hash;
    const winner = keepIncoming ? message : existing.message;
    const loserHash = keepIncoming ? existing.hash : hash;
    map.set(message.id, {
      message: { ...winner, conflict: true, alternateHash: loserHash },
      hash: keepIncoming ? hash : existing.hash,
      conflict: true,
    });
  };
  for (const message of local ?? []) ingest(message);
  for (const message of remote ?? []) ingest(message);
  return sortPaMessages([...map.values()].map((entry) => entry.message));
}

export function orderOps(ops = []) {
  return [...ops].sort((a, b) => a.seq - b.seq);
}

export function nextExpectedSeq(cursor) {
  return (cursor?.remoteSeq ?? 0) + 1;
}
