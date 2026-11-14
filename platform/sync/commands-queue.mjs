/**
 * 命令投递、收据、过期/取代与离线队列（SYNC-106 / SYNC-008 / SYNC-006）。
 *
 * 与 contracts/commands.mjs 的关系：
 * - 状态词表沿用合同（accepted/dispatched/succeeded/failed/expired/superseded/unknown）；
 * - 额外暴露 `delivery`：queued | waiting_target | sent | delivered | acked，
 *   「等待目标在线」是 accepted + delivery=waiting_target，绝不是成功。
 *
 * 幂等：同一 commandId 的重复投递只返回既有收据，不再次执行；终态收据不可重写
 * （`unknown` 只能通过显式 reconcile 变成 succeeded/failed）。
 */
import { createHash } from "node:crypto";

import { COMMAND_STATES, isTerminal } from "../contracts/commands.mjs";

export const DELIVERY_STATES = Object.freeze(["queued", "waiting_target", "sent", "delivered", "acked"]);
const TERMINAL = new Set(["succeeded", "failed", "expired", "superseded", "unknown"]);
const EXECUTION_RESULTS = new Set(["succeeded", "failed", "unknown"]);

export class CommandQueueError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "CommandQueueError";
    this.code = code;
    this.details = details;
  }
}

function clone(value) {
  return value === undefined ? undefined : structuredClone(value);
}

function receiptIdFor(commandId) {
  return `rcpt_${createHash("sha256").update(commandId).digest("hex").slice(0, 24)}`;
}

function makeReceipt(record, state, result = null, at = new Date().toISOString()) {
  return {
    receiptId: receiptIdFor(record.commandId),
    commandId: record.commandId,
    state,
    at,
    targetDeviceId: record.targetDeviceId,
    result,
  };
}

function assertKnownState(state) {
  if (!COMMAND_STATES.includes(state)) {
    throw new CommandQueueError("INVALID_STATE", `unknown command state ${state}`);
  }
}

/**
 * @param {object} opts
 * @param {object} opts.storage 同步存储（storage.mjs）
 * @param {string} opts.deviceId 本机 deviceId
 * @param {() => number} [opts.clock]
 */
export function createCommandQueue({ storage, deviceId, clock = Date.now } = {}) {
  if (!storage) throw new CommandQueueError("STORAGE_REQUIRED", "createCommandQueue requires {storage}");
  if (!deviceId) throw new CommandQueueError("DEVICE_REQUIRED", "createCommandQueue requires {deviceId}");
  const iso = () => new Date(clock()).toISOString();

  const save = (record) => {
    storage.putCommand(record);
    return clone(record);
  };

  const load = (commandId) => {
    const record = storage.getCommand(commandId);
    if (!record) throw new CommandQueueError("UNKNOWN_COMMAND", `unknown command ${commandId}`);
    return record;
  };

  const setReceipt = (record, state, result = null, at = iso()) => {
    record.receipt = makeReceipt(record, state, result, at);
    return record.receipt;
  };

  const queue = {
    /**
     * 来源节点写入命令意图（持久化后才允许发送外部 I/O）。
     * @returns {object} 命令记录快照
     */
    enqueue({ command, targetDeviceId = null, delivery = "queued" } = {}) {
      if (!command?.id) throw new CommandQueueError("INVALID_COMMAND", "command.id required");
      const existing = storage.getCommand(command.id);
      if (existing) return { ...clone(existing), duplicate: true };
      const target = targetDeviceId ?? command.target?.deviceId ?? null;
      if (!target) throw new CommandQueueError("TARGET_REQUIRED", "command target deviceId required");
      const record = {
        commandId: command.id,
        kind: command.kind,
        source: command.source ?? "ui",
        targetDeviceId: target,
        payload: clone(command.payload ?? {}),
        command: clone(command),
        state: "accepted",
        delivery,
        attempts: 0,
        createdAt: command.createdAt ?? iso(),
        expiresAt: command.expiresAt ?? new Date(clock() + 60_000).toISOString(),
        sentAt: null,
        deliveredAt: null,
        startedAt: null,
        completedAt: null,
        supersededBy: null,
        lastError: null,
        receipt: null,
      };
      setReceipt(record, "accepted", null, record.createdAt);
      return save(record);
    },

    get(commandId) {
      return storage.getCommand(commandId);
    },

    receiptFor(commandId) {
      const record = storage.getCommand(commandId);
      return record?.receipt ? clone(record.receipt) : null;
    },

    /** 目标离线：进入「等待目标在线」，状态仍是 accepted。 */
    markWaiting(targetDeviceId) {
      const records = storage.listCommands({ targetDeviceId });
      let changed = 0;
      for (const record of records) {
        if (!isTerminal(record) && record.state === "accepted" && record.delivery !== "delivered") {
          record.delivery = "waiting_target";
          storage.putCommand(record);
          changed += 1;
        }
      }
      return changed;
    },

    /** 已交给通道；不代表目标已接受。 */
    markSent(commandId) {
      const record = load(commandId);
      if (isTerminal(record)) return { ok: false, reason: "TERMINAL", record: clone(record) };
      record.delivery = record.delivery === "delivered" ? "delivered" : "sent";
      record.attempts += 1;
      record.sentAt = iso();
      return { ok: true, record: save(record) };
    },

    /**
     * 目标节点接收命令：幂等。重复投递返回同一收据，不再次执行。
     */
    receive(command) {
      if (!command?.id) throw new CommandQueueError("INVALID_COMMAND", "command.id required");
      const target = command.target?.deviceId ?? deviceId;
      if (target !== deviceId) {
        return { ok: false, reason: "WRONG_TARGET", receipt: null };
      }
      const existing = storage.getCommand(command.id);
      if (existing) {
        return {
          ok: true,
          duplicate: true,
          record: clone(existing),
          receipt: existing.receipt ? clone(existing.receipt) : null,
        };
      }
      const record = {
        commandId: command.id,
        kind: command.kind,
        source: command.source ?? "remote",
        targetDeviceId: deviceId,
        payload: clone(command.payload ?? {}),
        command: clone(command),
        state: "accepted",
        delivery: "delivered",
        attempts: 1,
        createdAt: command.createdAt ?? iso(),
        expiresAt: command.expiresAt ?? new Date(clock() + 60_000).toISOString(),
        sentAt: null,
        deliveredAt: iso(),
        startedAt: null,
        completedAt: null,
        supersededBy: null,
        lastError: null,
        receipt: null,
      };
      record.receipt = makeReceipt(record, "accepted", null, record.deliveredAt);
      storage.putCommand(record);
      return { ok: true, duplicate: false, record: clone(record), receipt: clone(record.receipt) };
    },

    /**
     * 目标开始执行（原子认领）：只有第一次返回 ok。第二次返回 ALREADY_STARTED，
     * 上层据此保证「同一命令只执行一次」。
     */
    begin(commandId) {
      const record = load(commandId);
      if (isTerminal(record)) return { ok: false, reason: "TERMINAL", record: clone(record) };
      if (record.state === "dispatched") {
        return { ok: false, reason: "ALREADY_STARTED", record: clone(record) };
      }
      record.state = "dispatched";
      record.delivery = "delivered";
      record.startedAt = iso();
      record.receipt = makeReceipt(record, "dispatched", null, record.startedAt);
      return { ok: true, record: save(record) };
    },

    /** 执行完成；只能执行一次终态写入。 */
    complete(commandId, { state, result = null } = {}) {
      if (!EXECUTION_RESULTS.has(state)) {
        throw new CommandQueueError("INVALID_RESULT", `complete state must be succeeded|failed|unknown, got ${state}`);
      }
      const record = load(commandId);
      if (isTerminal(record)) {
        if (record.state === state) {
          return { ok: true, duplicate: true, receipt: clone(record.receipt), record: clone(record) };
        }
        return { ok: false, reason: "TERMINAL", state: record.state, receipt: clone(record.receipt) };
      }
      if (state === "succeeded" && record.state !== "dispatched") {
        return { ok: false, reason: "NOT_STARTED", record: clone(record) };
      }
      record.state = state;
      record.completedAt = iso();
      record.receipt = makeReceipt(record, state, result, record.completedAt);
      if (state === "succeeded") record.delivery = "acked";
      return { ok: true, duplicate: false, receipt: clone(record.receipt), record: save(record) };
    },

    /** 结果未知只能显式核对后收敛，不允许把 unknown 改写成成功而不留痕。 */
    reconcile(commandId, { state, result = null, note = null } = {}) {
      if (state !== "succeeded" && state !== "failed") {
        throw new CommandQueueError("INVALID_RESULT", "reconcile state must be succeeded|failed");
      }
      const record = load(commandId);
      if (record.state !== "unknown") {
        return { ok: false, reason: "NOT_UNKNOWN", state: record.state, record: clone(record) };
      }
      record.state = state;
      record.completedAt = iso();
      record.receipt = makeReceipt(record, state, { ...(result ?? {}), reconciled: true, note }, record.completedAt);
      record.lastError = note;
      return { ok: true, receipt: clone(record.receipt), record: save(record) };
    },

    expire(commandId, { reason = "expired" } = {}) {
      const record = load(commandId);
      if (isTerminal(record)) return { ok: false, reason: "TERMINAL", record: clone(record) };
      record.state = "expired";
      record.completedAt = iso();
      record.receipt = makeReceipt(record, "expired", { reason }, record.completedAt);
      return { ok: true, receipt: clone(record.receipt), record: save(record) };
    },

    /** 到期待处理；离线目标上线前不会执行。 */
    expireDue({ at = clock(), targetDeviceId = null } = {}) {
      const records = storage.listCommands(targetDeviceId ? { targetDeviceId } : {});
      const expired = [];
      for (const record of records) {
        if (isTerminal(record)) continue;
        if (Date.parse(record.expiresAt) <= at) {
          record.state = "expired";
          record.completedAt = new Date(at).toISOString();
          record.receipt = makeReceipt(record, "expired", { reason: "ttl_elapsed" }, record.completedAt);
          storage.putCommand(record);
          expired.push(clone(record));
        }
      }
      return expired;
    },

    /**
     * 用新命令取代尚未执行的旧命令。已 dispatched 的命令可能有副作用，不允许取代
     * （调用方应改为投递 stop 命令并标明 supersedes）。
     */
    supersede(commandId, { by = null, reason = "superseded" } = {}) {
      const record = load(commandId);
      if (isTerminal(record)) return { ok: false, reason: "TERMINAL", record: clone(record) };
      if (record.state === "dispatched") {
        return { ok: false, reason: "ALREADY_STARTED", record: clone(record) };
      }
      record.state = "superseded";
      record.supersededBy = by;
      record.completedAt = iso();
      record.receipt = makeReceipt(record, "superseded", { supersededBy: by, reason }, record.completedAt);
      return { ok: true, receipt: clone(record.receipt), record: save(record) };
    },

    /** 目标离线队列（FIFO），包含 waiting_target / queued / sent 但未终态。 */
    pendingFor(targetDeviceId, { at = clock() } = {}) {
      return storage
        .listCommands({ targetDeviceId })
        .filter((record) => !isTerminal(record) && Date.parse(record.expiresAt) > at)
        .sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.commandId.localeCompare(b.commandId));
    },

    /**
     * 应用远端收据（来源节点）。
     * - 本端 unknown 时采纳远端终态（核对结果）；
     * - 本端已有不同终态时保留本地并标记冲突，不静默覆盖。
     */
    acknowledge(commandId, receipt) {
      const record = storage.getCommand(commandId);
      if (!record) return { ok: false, reason: "UNKNOWN_COMMAND" };
      if (!receipt || receipt.commandId !== commandId) {
        return { ok: false, reason: "INVALID_RECEIPT" };
      }
      assertKnownState(receipt.state);
      if (isTerminal(record)) {
        if (record.state === receipt.state) {
          return { ok: true, changed: false, receipt: clone(record.receipt) };
        }
        if (record.state === "unknown" && receipt.state !== "unknown") {
          record.state = receipt.state;
          record.receipt = clone(receipt);
          record.completedAt = receipt.at ?? iso();
          return { ok: true, changed: true, reconciled: true, receipt: clone(record.receipt), record: save(record) };
        }
        if (receipt.state === "unknown") {
          return { ok: true, changed: false, receipt: clone(record.receipt) };
        }
        record.lastError = `receipt_conflict: local=${record.state} remote=${receipt.state}`;
        storage.putCommand(record);
        return { ok: false, reason: "RECEIPT_CONFLICT", record: clone(record) };
      }
      record.state = receipt.state;
      record.receipt = clone(receipt);
      if (TERMINAL.has(receipt.state)) record.completedAt = receipt.at ?? iso();
      if (receipt.state === "dispatched") record.startedAt = receipt.at ?? iso();
      if (receipt.state === "succeeded") record.delivery = "acked";
      return { ok: true, changed: true, receipt: clone(record.receipt), record: save(record) };
    },

    stats() {
      const records = storage.listCommands({});
      const byState = {};
      const byDelivery = {};
      for (const record of records) {
        byState[record.state] = (byState[record.state] ?? 0) + 1;
        byDelivery[record.delivery] = (byDelivery[record.delivery] ?? 0) + 1;
      }
      return { total: records.length, byState, byDelivery };
    },
  };

  return queue;
}

export { isTerminal };
