/**
 * 审批/提问的跨端同步与单结果竞争（SYNC-107 / SYNC-007）。
 *
 * 竞争语义：
 * - 每个回答是一个不可变事实（answerId, deviceId, at, value），所有设备都保留全部回答；
 * - 生效结果 = 全序最小者：(at, deviceId, answerId)。因此即使分区期间两端各自回答，
 *   重连后两端也会得到同一个赢家；失败回答标记 `superseded`，不会覆盖赢家。
 * - 过期/取消只影响「尚无答案」的请求，不能撤销已经产生的回答。
 * - Agent 崩溃时 pending 全部结算为 cancelled，不留永远等待（REL-002）。
 */
import { randomUUID } from "node:crypto";

import { makeId } from "../contracts/ids.mjs";

export const INTERACTION_KINDS = Object.freeze(["approval", "question"]);
export const INTERACTION_STATUSES = Object.freeze([
  "pending",
  "answered",
  "expired",
  "superseded",
  "cancelled",
]);
export const DEFAULT_INTERACTION_TTL_MS = 10 * 60_000;

export class InteractionError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "InteractionError";
    this.code = code;
    this.details = details;
  }
}

function clone(value) {
  return value === undefined ? undefined : structuredClone(value);
}

function timeValue(iso) {
  const parsed = Date.parse(iso);
  return Number.isFinite(parsed) ? parsed : Number.POSITIVE_INFINITY;
}

/** 回答的全序：时间 → deviceId → answerId（码点序，避免 locale 漂移）。 */
export function compareAnswers(a, b) {
  const ta = timeValue(a?.at);
  const tb = timeValue(b?.at);
  if (ta !== tb) return ta - tb;
  const da = String(a?.deviceId ?? "");
  const db = String(b?.deviceId ?? "");
  if (da !== db) return da < db ? -1 : 1;
  const aa = String(a?.answerId ?? "");
  const ab = String(b?.answerId ?? "");
  return aa === ab ? 0 : aa < ab ? -1 : 1;
}

export function resolveCompetition(answers = []) {
  const valid = answers.filter((a) => a && typeof a.answerId === "string" && a.answerId.length > 0);
  if (valid.length === 0) return null;
  return [...valid].sort(compareAnswers)[0];
}

export function createInteractionsSync({ storage, deviceId, clock = Date.now } = {}) {
  if (!storage) throw new InteractionError("STORAGE_REQUIRED", "createInteractionsSync requires {storage}");
  if (!deviceId) throw new InteractionError("DEVICE_REQUIRED", "createInteractionsSync requires {deviceId}");
  const iso = () => new Date(clock()).toISOString();

  const load = (id) => {
    const record = storage.getInteraction(id);
    if (!record) throw new InteractionError("UNKNOWN_INTERACTION", `unknown interaction ${id}`);
    return record;
  };

  const recompute = (record) => {
    const winner = resolveCompetition(record.answers ?? []);
    record.winner = winner ? { ...clone(winner), status: "accepted" } : null;
    record.answers = (record.answers ?? []).map((answer) => ({
      ...answer,
      status: winner ? (answer.answerId === winner.answerId ? "accepted" : "superseded") : "pending",
    }));
    if (winner) {
      record.status = "answered";
      record.answeredAt = winner.at ?? null;
      record.resolvedAt = record.resolvedAt ?? winner.at ?? iso();
    } else if (!["cancelled", "expired"].includes(record.status)) {
      record.status = "pending";
    }
    record.updatedAt = iso();
    return record;
  };

  const appendAnswer = (record, answer) => {
    if (!answer || typeof answer.answerId !== "string" || answer.answerId.length === 0) {
      throw new InteractionError("INVALID_ANSWER", "answer requires answerId");
    }
    if (typeof answer.deviceId !== "string" || answer.deviceId.length === 0) {
      throw new InteractionError("INVALID_ANSWER", "answer requires deviceId");
    }
    const existing = (record.answers ?? []).find((a) => a.answerId === answer.answerId);
    if (existing) {
      recompute(record);
      return { changed: false, duplicate: true, winner: record.winner };
    }
    record.answers = [...(record.answers ?? []), { ...clone(answer), status: "pending" }];
    recompute(record);
    return { changed: true, duplicate: false, winner: record.winner };
  };

  return {
    register({
      sessionId,
      nativeId = null,
      kind = "approval",
      prompt = "",
      options = [],
      expiresAt = null,
      originDeviceId = deviceId,
      createdAt = iso(),
    } = {}) {
      if (!sessionId) throw new InteractionError("SESSION_REQUIRED", "interaction requires sessionId");
      if (!INTERACTION_KINDS.includes(kind)) {
        throw new InteractionError("INVALID_KIND", `interaction kind must be approval|question, got ${kind}`);
      }
      const record = {
        id: makeId("int"),
        sessionId,
        nativeId,
        kind,
        prompt: String(prompt),
        options: Array.isArray(options) ? clone(options) : [],
        originDeviceId,
        status: "pending",
        createdAt,
        updatedAt: createdAt,
        expiresAt: expiresAt ?? new Date(clock() + DEFAULT_INTERACTION_TTL_MS).toISOString(),
        answeredAt: null,
        resolvedAt: null,
        winner: null,
        answers: [],
        cancelledReason: null,
      };
      storage.putInteraction(record);
      return clone(record);
    },

    /** 远端请求（快照/操作）合并：请求字段以 origin 为准，回答取并集。 */
    applyRequest(remote) {
      if (!remote?.id) throw new InteractionError("INVALID_REQUEST", "interaction id required");
      const existing = storage.getInteraction(remote.id);
      if (!existing) {
        const record = {
          ...clone(remote),
          status: ["cancelled", "expired"].includes(remote.status) ? remote.status : "pending",
          answers: (remote.answers ?? []).map((a) => ({ ...a, status: a.status ?? "superseded" })),
        };
        recompute(record);
        if (["cancelled", "expired"].includes(remote.status) && !record.winner) record.status = remote.status;
        storage.putInteraction(record);
        return clone(record);
      }
      const merged = {
        ...existing,
        sessionId: remote.sessionId ?? existing.sessionId,
        nativeId: remote.nativeId ?? existing.nativeId,
        kind: remote.kind ?? existing.kind,
        prompt: remote.prompt ?? existing.prompt,
        options: remote.options ?? existing.options,
        originDeviceId: existing.originDeviceId,
        expiresAt: remote.expiresAt ?? existing.expiresAt,
      };
      for (const answer of remote.answers ?? []) {
        if (!merged.answers.some((a) => a.answerId === answer.answerId)) {
          merged.answers.push({ ...clone(answer), status: answer.status ?? "pending" });
        }
      }
      if (!merged.answers.length && ["cancelled", "expired"].includes(remote.status)) {
        merged.status = remote.status;
        if (remote.status === "cancelled") merged.cancelledReason = remote.cancelledReason ?? null;
      }
      recompute(merged);
      if (["cancelled", "expired"].includes(remote.status) && !merged.winner) merged.status = remote.status;
      storage.putInteraction(merged);
      return clone(merged);
    },

    /**
     * 本端回答。先到者生效；之后的回答保留为 superseded。
     */
    answer(interactionId, { deviceId: answeringDevice = deviceId, value = null, at = iso(), answerId = randomUUID() } = {}) {
      const record = load(interactionId);
      if (record.status === "cancelled") {
        return { accepted: false, status: record.status, reason: "CANCELLED", record: clone(record) };
      }
      if (record.status === "expired") {
        return { accepted: false, status: record.status, reason: "EXPIRED", record: clone(record) };
      }
      if ((record.answers ?? []).length === 0 && Date.parse(record.expiresAt) <= clock()) {
        record.status = "expired";
        record.updatedAt = iso();
        storage.putInteraction(record);
        return { accepted: false, status: "expired", reason: "EXPIRED", record: clone(record) };
      }
      const answer = { answerId, deviceId: answeringDevice, at, value: clone(value) };
      const result = appendAnswer(record, answer);
      storage.putInteraction(record);
      return {
        accepted: record.winner?.answerId === answerId,
        status: record.status,
        winner: clone(record.winner),
        superseded: record.winner?.answerId !== answerId,
        record: clone(record),
      };
    },

    /** 应用远端回答（幂等，按全序重新计算赢家）。 */
    applyAnswer(interactionId, answer) {
      const record = load(interactionId);
      const result = appendAnswer(record, answer);
      storage.putInteraction(record);
      return {
        ok: true,
        changed: result.changed,
        accepted: record.winner?.answerId === answer.answerId,
        status: record.status,
        winner: clone(record.winner),
        record: clone(record),
      };
    },

    expire({ at = clock() } = {}) {
      const expired = [];
      for (const record of storage.listInteractions()) {
        if (record.status !== "pending") continue;
        if ((record.answers ?? []).length > 0) continue;
        if (Date.parse(record.expiresAt) > at) continue;
        record.status = "expired";
        record.updatedAt = new Date(at).toISOString();
        storage.putInteraction(record);
        expired.push(clone(record));
      }
      return expired;
    },

    cancel(interactionId, { reason = "cancelled", at = iso() } = {}) {
      const record = load(interactionId);
      if (record.winner) {
        return { ok: false, reason: "ALREADY_ANSWERED", record: clone(record) };
      }
      record.status = "cancelled";
      record.cancelledReason = reason;
      record.updatedAt = at;
      storage.putInteraction(record);
      return { ok: true, record: clone(record) };
    },

    /** Agent 崩溃/回合失败：pending 全部结算，不留永远等待。 */
    settlePending({ reason = "agent_crashed", at = iso() } = {}) {
      const settled = [];
      for (const record of storage.listInteractions()) {
        if (record.status !== "pending") continue;
        record.status = "cancelled";
        record.cancelledReason = reason;
        record.updatedAt = at;
        storage.putInteraction(record);
        settled.push(clone(record));
      }
      return settled;
    },

    get(interactionId) {
      return clone(storage.getInteraction(interactionId));
    },

    list({ sessionId = null, status = null } = {}) {
      return storage
        .listInteractions()
        .filter((record) => (!sessionId || record.sessionId === sessionId) && (!status || record.status === status))
        .map(clone);
    },

    resolve(interactionId) {
      const record = storage.getInteraction(interactionId);
      if (!record) return null;
      return { status: record.status, winner: clone(record.winner), superseded: clone(record.answers.filter((a) => a.status === "superseded")) };
    },

    stats() {
      return storage.listInteractions().reduce(
        (acc, record) => {
          acc[record.status] = (acc[record.status] ?? 0) + 1;
          return acc;
        },
        { pending: 0, answered: 0, expired: 0, superseded: 0, cancelled: 0 },
      );
    },
  };
}
