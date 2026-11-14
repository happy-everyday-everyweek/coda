/**
 * CORE-105：持久命令收据。
 *
 * 不变量（CONTRACT.md 第 3 节 / SPEC 11.5）：
 * - 命令先落库为 `accepted`，再执行外部 I/O；结果写回后才进入终态。
 * - 同一 commandId 重复投递只返回原收据，不产生新副作用（幂等）。
 * - `accepted → dispatched → succeeded|failed|expired|superseded|unknown`。
 * - 「结果未知」是合法终态：超时/崩溃/进程退出后不得伪造成成功或失败。
 *
 * 收据与事件日志共用同一个 SQLite 连接，因此可以在同一事务里「写事件 + 结算收据」。
 */
import { COMMAND_KINDS, COMMAND_STATES, transition } from "../contracts/commands.mjs";
import { makeId } from "../contracts/ids.mjs";
import { ClosedError, ConflictError, NotFoundError, ValidationError } from "./errors.mjs";

const TERMINAL = new Set(["succeeded", "failed", "expired", "superseded", "unknown"]);
const SETTLE_TARGETS = new Set(["succeeded", "failed", "expired", "superseded", "unknown"]);

const parseJson = (text, fallback) => {
  if (typeof text !== "string" || text.length === 0) return fallback;
  try {
    return JSON.parse(text);
  } catch {
    return fallback;
  }
};

/** 数据库行 → 对外收据（客户端只读这个形状）。 */
export function rowToReceipt(row) {
  if (!row) return null;
  const state = row.state;
  return {
    commandId: row.command_id,
    kind: row.kind,
    source: row.source,
    target: parseJson(row.target, {}),
    payload: parseJson(row.payload, {}),
    state,
    supersedes: row.supersedes ?? null,
    supersededBy: row.superseded_by ?? null,
    createdAt: row.created_at,
    expiresAt: row.expires_at,
    dispatchedAt: row.dispatched_at ?? null,
    settledAt: row.settled_at ?? null,
    attempts: Number(row.attempts ?? 0),
    result: parseJson(row.result, null),
    error: parseJson(row.error, null),
    terminal: TERMINAL.has(state),
    receipt: {
      state,
      at: row.settled_at ?? row.dispatched_at ?? row.created_at,
      result: parseJson(row.result, null),
      error: parseJson(row.error, null),
    },
  };
}

export class ReceiptStore {
  constructor({ journal, clock = () => new Date() } = {}) {
    if (!journal) throw new ValidationError("ReceiptStore requires a journal");
    this.journal = journal;
    this.clock = clock;
  }

  now() {
    const d = this.clock();
    return d instanceof Date ? new Date(d.getTime()) : new Date(d);
  }

  nowIso() {
    return this.now().toISOString();
  }

  #db() {
    // journal.db 在关闭后会抛 ClosedError，正好符合需要。
    return this.journal.db;
  }

  /**
   * 持久化命令意图（幂等）。
   * @param {object} input
   * @param {string} [input.id] 客户端可自带 commandId（跨端重投同一 ID）
   * @param {string} input.kind 见 COMMAND_KINDS
   * @param {object} [input.target]
   * @param {object} [input.payload]
   * @param {string} [input.source]
   * @param {number} [input.ttlMs]
   * @param {string} [input.expiresAt]
   * @param {string} [input.supersedes]
   * @returns {{receipt: object, deduped: boolean, created: boolean}}
   */
  accept(input = {}) {
    const db = this.#db();
    if (!COMMAND_KINDS.includes(input.kind)) {
      throw new ValidationError(`unknown command kind: ${input.kind}`);
    }
    const id = input.id ?? input.commandId ?? makeId("cmd");
    const existing = this.get(id);
    if (existing) {
      return { receipt: existing, deduped: true, created: false };
    }

    const createdAt = input.createdAt ?? this.nowIso();
    const ttlMs = Number.isFinite(input.ttlMs) ? Number(input.ttlMs) : 60_000;
    const expiresAt = input.expiresAt ?? new Date(Date.parse(createdAt) + ttlMs).toISOString();
    const source = input.source ?? "ui";
    const target = input.target ?? {};
    const payload = input.payload ?? {};
    const supersedes = input.supersedes ?? null;
    const expired = Date.parse(expiresAt) <= this.now().getTime();
    const state = expired ? "expired" : "accepted";
    const settledAt = expired ? this.nowIso() : null;

    const result = this.journal.transaction(() => {
      db.prepare(
        `INSERT INTO commands(
           command_id, kind, source, target, payload, state, supersedes, superseded_by,
           created_at, expires_at, dispatched_at, settled_at, attempts, result, error
         ) VALUES(?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, ?, 0, NULL, ?)`,
      ).run(
        id,
        input.kind,
        source,
        JSON.stringify(target),
        JSON.stringify(payload),
        state,
        supersedes,
        createdAt,
        expiresAt,
        settledAt,
        expired ? JSON.stringify({ reason: "expired-before-accept" }) : null,
      );
      if (supersedes) {
        this.#supersedeWithinTx(supersedes, id);
      }
      return this.get(id);
    });

    return { receipt: result, deduped: false, created: true };
  }

  /** accepted → dispatched。返回 {receipt, deduped}；已终态时幂等返回。 */
  dispatch(id) {
    const db = this.#db();
    const existing = this.get(id);
    if (!existing) throw new NotFoundError(`unknown command: ${id}`, { commandId: id });
    if (existing.terminal) return { receipt: existing, deduped: true };
    if (existing.state !== "accepted") {
      // 已经 dispatched：视为重复投递，不增加副作用。
      return { receipt: existing, deduped: true };
    }
    if (Date.parse(existing.expiresAt) <= this.now().getTime()) {
      return { receipt: this.#settleRow(existing, "expired", { error: { reason: "expired-before-dispatch" } }), deduped: false };
    }
    return this.journal.transaction(() => {
      const next = this.#applyTransition(existing, "dispatched");
      db.prepare(
        `UPDATE commands SET state = ?, dispatched_at = ?, attempts = attempts + 1 WHERE command_id = ?`,
      ).run(next, this.nowIso(), id);
      return { receipt: this.get(id), deduped: false };
    });
  }

  /**
   * 结算命令。state 必须是终态；已终态时幂等返回原收据。
   * @param {string} id
   * @param {"succeeded"|"failed"|"expired"|"superseded"|"unknown"} state
   * @param {{result?: any, error?: any}} [outcome]
   */
  settle(id, state, { result = null, error = null } = {}) {
    if (!SETTLE_TARGETS.has(state)) {
      throw new ValidationError(`settle() requires a terminal state, got ${state}`);
    }
    const existing = this.get(id);
    if (!existing) throw new NotFoundError(`unknown command: ${id}`, { commandId: id });
    if (existing.terminal) return existing;
    if (existing.state !== "dispatched") {
      throw new ConflictError(`command ${id} must be dispatched before settle (state=${existing.state})`, {
        commandId: id,
        state: existing.state,
      });
    }
    // 用契约状态机校验转移（accepted → dispatched 已在 dispatch() 完成）。
    this.#applyTransition(existing, state);
    return this.#settleRow(existing, state, { result, error });
  }

  /** 结果未知：只能从 dispatched 进入，且永不重试副作用。 */
  markUnknown(id, { reason = "result unknown", result = null } = {}) {
    const existing = this.get(id);
    if (!existing) throw new NotFoundError(`unknown command: ${id}`, { commandId: id });
    if (existing.terminal) return existing;
    if (existing.state !== "dispatched") {
      throw new ConflictError(`command ${id} was never dispatched; cannot mark unknown`, {
        commandId: id,
        state: existing.state,
      });
    }
    this.#applyTransition(existing, "unknown");
    return this.#settleRow(existing, "unknown", { error: { reason } });
  }

  /** 新命令取代尚未执行的旧命令（已发生的副作用不可撤销）。 */
  supersede(oldId, { supersededBy = null } = {}) {
    const existing = this.get(oldId);
    if (!existing) throw new NotFoundError(`unknown command: ${oldId}`, { commandId: oldId });
    if (existing.terminal) return existing;
    // 契约状态机不允许 accepted 直接跳到 superseded（只能先 dispatched），
    // 但 SPEC 11.5 要求新命令可以让尚未执行的旧命令失效，因此这里显式放开 accepted。
    if (existing.state === "dispatched") this.#applyTransition(existing, "superseded");
    else if (existing.state !== "accepted") {
      throw new ConflictError(`cannot supersede command in state ${existing.state}`, {
        commandId: oldId,
        state: existing.state,
      });
    }
    return this.#settleRow(existing, "superseded", {
      result: { supersededBy },
      error: null,
      supersededBy,
    });
  }

  #supersedeWithinTx(oldId, newId) {
    const existing = this.get(oldId);
    if (!existing || existing.terminal) return;
    const db = this.#db();
    db.prepare(
      `UPDATE commands SET state = 'superseded', superseded_by = ?, settled_at = ?, result = ? WHERE command_id = ?`,
    ).run(newId, this.nowIso(), JSON.stringify({ supersededBy: newId }), oldId);
  }

  #applyTransition(receipt, next) {
    // 用契约状态机做转移校验，但时间戳来自注入的 clock。
    const command = { state: receipt.state };
    transition(command, next);
    if (!COMMAND_STATES.includes(command.state)) {
      throw new ValidationError(`invalid command state: ${command.state}`);
    }
    return command.state;
  }

  #settleRow(existing, state, { result = null, error = null, supersededBy = null } = {}) {
    const db = this.#db();
    const settledAt = this.nowIso();
    return this.journal.transaction(() => {
      db.prepare(
        `UPDATE commands SET state = ?, settled_at = ?, result = ?, error = ?, superseded_by = COALESCE(?, superseded_by) WHERE command_id = ?`,
      ).run(
        state,
        settledAt,
        result === null ? null : JSON.stringify(result),
        error === null ? null : JSON.stringify(error),
        supersededBy,
        existing.commandId,
      );
      return this.get(existing.commandId);
    });
  }

  get(id) {
    const db = this.#db();
    if (!id) return null;
    return rowToReceipt(db.prepare("SELECT * FROM commands WHERE command_id = ?").get(id));
  }

  list({ state = null, limit = 200 } = {}) {
    const db = this.#db();
    const rows = state
      ? db.prepare("SELECT * FROM commands WHERE state = ? ORDER BY created_at ASC LIMIT ?").all(state, limit)
      : db.prepare("SELECT * FROM commands ORDER BY created_at ASC LIMIT ?").all(limit);
    return rows.map(rowToReceipt);
  }

  countByState() {
    const db = this.#db();
    const out = Object.fromEntries(COMMAND_STATES.map((s) => [s, 0]));
    for (const row of db.prepare("SELECT state, COUNT(*) AS n FROM commands GROUP BY state").all()) {
      out[row.state] = Number(row.n);
    }
    return out;
  }

  /**
   * 过期扫描：未执行的 accepted 过期 → expired；已 dispatched 又过期 → unknown（不能假装没执行）。
   * @returns {{expired: string[], unknown: string[]}}
   */
  expireDue() {
    const db = this.#db();
    const now = this.now().getTime();
    const expired = [];
    const unknown = [];
    for (const row of db
      .prepare("SELECT * FROM commands WHERE state IN ('accepted', 'dispatched') AND expires_at <= ?")
      .all(new Date(now).toISOString())) {
      if (row.state === "accepted") {
        this.#settleRow(rowToReceipt(row), "expired", { error: { reason: "expired-before-dispatch" } });
        expired.push(row.command_id);
      } else {
        this.#settleRow(rowToReceipt(row), "unknown", {
          error: { reason: "dispatched command outlived its ttl; external result unknown" },
        });
        unknown.push(row.command_id);
      }
    }
    return { expired, unknown };
  }

  /**
   * CORE-108 崩溃恢复：dispatched 的命令结果不可知 → unknown；accepted 且过期 → expired。
   * 未过期的 accepted 保留，等待重新派发。
   */
  recoverAfterCrash({ reason = "core restarted before result was committed" } = {}) {
    const db = this.#db();
    const now = this.now().getTime();
    const unknown = [];
    const expired = [];
    const pending = [];
    for (const row of db.prepare("SELECT * FROM commands WHERE state IN ('accepted', 'dispatched')").all()) {
      const receipt = rowToReceipt(row);
      if (row.state === "dispatched") {
        this.#settleRow(receipt, "unknown", { error: { reason } });
        unknown.push(row.command_id);
      } else if (Date.parse(row.expires_at) <= now) {
        this.#settleRow(receipt, "expired", { error: { reason: "expired while core was down" } });
        expired.push(row.command_id);
      } else {
        pending.push(row.command_id);
      }
    }
    return { unknown, expired, pending };
  }

  /** 未完成命令（诊断/恢复用）。 */
  listPending() {
    const db = this.#db();
    return db
      .prepare("SELECT * FROM commands WHERE state IN ('accepted', 'dispatched') ORDER BY created_at ASC")
      .all()
      .map(rowToReceipt);
  }
}
