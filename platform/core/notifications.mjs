/**
 * CORE-113：通知服务。
 *
 * - 领域事件（回合完成/失败、审批/提问、连接状态）→ 通知记录。
 * - 系统通知只做「日志适配」：参考实现不弹系统横幅，默认写入诊断日志；
 *   宿主平台可通过 `systemAdapters` 注入真实系统通知实现。
 * - 不把 unknown/stopped 伪装成完成或失败；未知就用 warn 级 info 通知。
 */
import { makeId } from "../contracts/ids.mjs";
import { NOTIFICATION_TYPES } from "./domain.mjs";
import { ValidationError } from "./errors.mjs";

const LEVELS = ["debug", "info", "warn", "error"];

const DEFAULT_TITLES = {
  completed: "任务已完成",
  failed: "任务失败",
  approval: "需要审批",
  connection: "连接状态变化",
  turn: "回合状态",
  info: "通知",
};

function truncate(text, max = 500) {
  const str = String(text ?? "");
  return str.length > max ? `${str.slice(0, max)}…` : str;
}

export class NotificationService {
  #records = [];
  #connections = new Map();

  constructor({
    clock = () => new Date(),
    journal = null,
    bus = null,
    logger = null,
    systemAdapters = [],
    maxRecords = 500,
    dedupeWindowMs = 1_000,
  } = {}) {
    this.clock = clock;
    this.journal = journal;
    this.bus = bus;
    this.logger = logger;
    this.systemAdapters = Array.isArray(systemAdapters) ? systemAdapters : [];
    this.maxRecords = Math.max(1, maxRecords);
    this.dedupeWindowMs = Math.max(0, dedupeWindowMs);
    this.lastEventSeq = 0;
  }

  now() {
    const d = this.clock();
    return d instanceof Date ? new Date(d.getTime()) : new Date(d);
  }

  nowIso() {
    return this.now().toISOString();
  }

  get size() {
    return this.#records.length;
  }

  /**
   * 记录一条通知。
   * @param {{type?:string, title?:string, body?:string, level?:string, sessionId?:string|null,
   *   key?:string|null, payload?:object}} input
   */
  record({ type = "info", title = null, body = null, level = "info", sessionId = null, key = null, payload = {} } = {}) {
    if (!NOTIFICATION_TYPES.includes(type)) {
      throw new ValidationError(`unknown notification type: ${type}`);
    }
    const normalizedLevel = LEVELS.includes(level) ? level : "info";
    if (key) {
      const existing = this.#records.find(
        (r) => r.key === key && this.now().getTime() - Date.parse(r.createdAt) < this.dedupeWindowMs,
      );
      if (existing) return { ...existing, deduped: true };
    }
    const record = {
      id: makeId("evt"),
      type,
      title: truncate(title ?? DEFAULT_TITLES[type] ?? DEFAULT_TITLES.info, 200),
      body: body === null ? null : truncate(body),
      level: normalizedLevel,
      sessionId,
      key,
      payload,
      read: false,
      createdAt: this.nowIso(),
    };
    this.#records.push(record);
    if (this.#records.length > this.maxRecords) {
      this.#records.splice(0, this.#records.length - this.maxRecords);
    }
    if (this.journal) {
      try {
        // 通知也是对外的领域状态：以事件形式持久化，投影再落到 notifications 表，
        // 这样「事件日志是唯一真相」的前提在重放校验时仍然成立。
        const event = this.journal.appendEvent({
          kind: "notification",
          ts: record.createdAt,
          payload: {
            notification: {
              id: record.id,
              type: record.type,
              sessionId: record.sessionId,
              title: record.title,
              body: record.body,
              // 客户端通知投影读 `text`；与 snapshot.notifications 保持同一形状。
              text: record.body ?? record.title,
              level: record.level,
              read: false,
              createdAt: record.createdAt,
            },
          },
        });
        this.journal.pruneNotifications(this.maxRecords);
        // 通知事件也要进订阅流，否则客户端看到的 seq 会出现假缺口。
        this.bus?.publish?.(event);
      } catch {
        // 持久化失败不应阻断通知扇出；诊断日志会记录核心状态。
      }
    }
    this.#dispatchSystem(record);
    return record;
  }

  #dispatchSystem(record) {
    for (const adapter of this.systemAdapters) {
      try {
        adapter.notify?.(record);
      } catch (err) {
        this.logger?.warn?.("system notification adapter failed", { error: String(err?.message ?? err) });
      }
    }
    // 默认「系统通知」= 有界日志适配。
    const line = `[notify:${record.type}] ${record.title}${record.body ? ` — ${record.body}` : ""}`;
    if (record.level === "error") this.logger?.error?.(line, { sessionId: record.sessionId });
    else if (record.level === "warn") this.logger?.warn?.(line, { sessionId: record.sessionId });
    else this.logger?.info?.(line, { sessionId: record.sessionId });
  }

  /** 连接状态只在真正变化时通知（避免重连抖动刷屏）。 */
  setConnection({ peerId, online, reason = null } = {}) {
    if (typeof peerId !== "string" || peerId.length === 0) throw new ValidationError("peerId is required");
    const previous = this.#connections.get(peerId);
    if (previous === !!online) return null;
    this.#connections.set(peerId, !!online);
    return this.record({
      type: "connection",
      title: online ? "对端已连接" : "对端已断开",
      body: reason,
      level: online ? "info" : "warn",
      key: `connection:${peerId}`,
      payload: { peerId, online: !!online, previous },
    });
  }

  /** 领域事件 → 通知。返回生成的通知（可能为 null）。 */
  handleEvent(event) {
    if (!event || typeof event !== "object") return null;
    this.lastEventSeq = Math.max(this.lastEventSeq, Number(event.seq ?? 0));
    const payload = event.payload ?? {};
    if (event.kind === "turn") {
      const state = payload.state ?? payload.turn?.state;
      const sessionId = event.sessionId ?? payload.turn?.sessionId ?? null;
      const reason = payload.stopReason ?? payload.turn?.stopReason ?? null;
      if (state === "completed") {
        return this.record({
          type: "completed",
          title: "任务已完成",
          body: reason ? `结束原因：${reason}` : null,
          level: "info",
          sessionId,
          key: sessionId ? `turn:${sessionId}:completed` : null,
          payload: { turnId: payload.turnId ?? payload.turn?.id ?? null, seq: event.seq },
        });
      }
      if (state === "failed") {
        return this.record({
          type: "failed",
          title: "任务失败",
          body: payload.error?.message ?? reason ?? null,
          level: "error",
          sessionId,
          key: sessionId ? `turn:${sessionId}:failed` : null,
          payload: { turnId: payload.turnId ?? payload.turn?.id ?? null, seq: event.seq },
        });
      }
      if (state === "unknown") {
        return this.record({
          type: "info",
          title: "回合结果未知",
          body: "核心未能确认上游结果，请检查会话状态",
          level: "warn",
          sessionId,
          payload: { turnId: payload.turnId ?? payload.turn?.id ?? null, state, seq: event.seq },
        });
      }
      if (state === "stopped" || reason === "interrupted") {
        return this.record({
          type: "info",
          title: "回合已停止",
          level: "info",
          sessionId,
          key: sessionId ? `turn:${sessionId}:stopped` : null,
          payload: { turnId: payload.turnId ?? payload.turn?.id ?? null, seq: event.seq },
        });
      }
      return null;
    }

    if (event.kind === "interaction") {
      const state = payload.state ?? payload.request?.state;
      if (state === "pending") {
        const kind = payload.kind ?? payload.interactionKind ?? payload.request?.kind ?? "approval";
        return this.record({
          type: "approval",
          title: DEFAULT_TITLES.approval,
          body: payload.prompt ?? payload.request?.prompt ?? null,
          level: "warn",
          sessionId: event.sessionId ?? null,
          key: `interaction:${payload.requestId ?? payload.request?.id}:pending`,
          payload: { requestId: payload.requestId ?? payload.request?.id ?? null, kind, seq: event.seq },
        });
      }
      return null;
    }

    if (event.kind === "notification" && payload.title) {
      return this.record({
        type: NOTIFICATION_TYPES.includes(payload.type) ? payload.type : "info",
        title: payload.title,
        body: payload.body ?? null,
        level: payload.level ?? "info",
        sessionId: event.sessionId ?? null,
        payload: { seq: event.seq },
      });
    }

    if (event.kind === "error") {
      return this.record({
        type: "failed",
        title: "核心或 Agent 错误",
        body: payload.message ?? payload.error?.message ?? null,
        level: "error",
        sessionId: event.sessionId ?? null,
        payload: { seq: event.seq },
      });
    }

    return null;
  }

  list({ limit = 100, unreadOnly = false, type = null } = {}) {
    let items = [...this.#records].reverse();
    if (unreadOnly) items = items.filter((r) => !r.read);
    if (type) items = items.filter((r) => r.type === type);
    return items.slice(0, limit);
  }

  markRead(id) {
    const record = this.#records.find((r) => r.id === id);
    if (!record) return null;
    record.read = true;
    try {
      this.journal?.markNotificationRead(id);
    } catch {
      // ignore
    }
    return record;
  }

  markAllRead() {
    for (const record of this.#records) record.read = true;
    return this.#records.length;
  }

  unreadCount() {
    return this.#records.filter((r) => !r.read).length;
  }

  byType() {
    const counts = Object.fromEntries(NOTIFICATION_TYPES.map((t) => [t, 0]));
    for (const record of this.#records) counts[record.type] = (counts[record.type] ?? 0) + 1;
    return counts;
  }

  /** 诊断摘要。 */
  diagnostics() {
    return {
      total: this.#records.length,
      unread: this.unreadCount(),
      byType: this.byType(),
      connections: Object.fromEntries(this.#connections),
      lastEventSeq: this.lastEventSeq,
    };
  }
}

export function createNotificationService(options) {
  return new NotificationService(options);
}
