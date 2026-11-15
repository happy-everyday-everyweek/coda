/**
 * 审批 / 提问归一化（AGT-108 / ADP-010 / ADP-011 / SPEC 9.7）。
 *
 * 关键语义：
 *   - 保留上游原生请求 ID（nativeRequestId）与原生选项 ID（optionId），
 *     绝不用显示文案反推有效答案。
 *   - 过期请求不可误答；已答请求不可重复答。
 *   - 记录决定者与依据（用户 / PA 自动 / 子 Agent），用于审计。
 *   - 子 Agent 发起的请求必须带真实来源，不能伪装成主 Agent。
 */
import { INTERACTION_STATES, makeId, nowIso } from "../contracts/ids.mjs";

export const INTERACTION_KINDS = ["permission", "question"];
export const INTERACTION_ORIGINS = ["main", "subagent", "background"];
export const PERMISSION_OPTION_KINDS = ["allow_once", "allow_always", "reject_once", "reject_always", "other"];
export const INVALIDATION_REASONS = ["expired", "turn-stopped", "session-stopped", "superseded", "adapter-disposed"];

const TERMINAL_STATES = new Set(["answered", "expired", "superseded", "cancelled"]);

export class InteractionError extends Error {
  constructor(code, message, details = null) {
    super(message);
    this.name = "InteractionError";
    this.code = code;
    this.details = details;
  }
}

function assertKind(kind) {
  if (!INTERACTION_KINDS.includes(kind)) {
    throw new InteractionError("INVALID", `unknown interaction kind: ${kind}`);
  }
}

function normalizeSource(source = {}) {
  const origin = source?.origin ?? "main";
  if (!INTERACTION_ORIGINS.includes(origin)) {
    throw new InteractionError("INVALID", `unknown interaction origin: ${origin}`);
  }
  return {
    origin,
    subagentId: source?.subagentId ?? null,
    parentInteractionId: source?.parentInteractionId ?? null,
  };
}

function normalizeOptions(options = []) {
  if (!Array.isArray(options)) throw new InteractionError("INVALID", "options must be an array");
  const seen = new Set();
  return options.map((option, index) => {
    if (!option || typeof option !== "object") {
      throw new InteractionError("INVALID", `option[${index}] must be an object`);
    }
    const optionId = option.optionId ?? option.id ?? null;
    if (!optionId || typeof optionId !== "string") {
      throw new InteractionError("INVALID", `option[${index}] requires a native optionId`);
    }
    if (seen.has(optionId)) throw new InteractionError("INVALID", `duplicate optionId: ${optionId}`);
    seen.add(optionId);
    return {
      optionId,
      label: option.label ?? option.name ?? optionId,
      kind: option.kind ?? "other",
      description: option.description ?? null,
    };
  });
}

function normalizeExpiry(expiresAt, ttlMs) {
  if (expiresAt) {
    const parsed = Date.parse(expiresAt);
    if (Number.isNaN(parsed)) throw new InteractionError("INVALID", "expiresAt must be ISO-8601");
    return new Date(parsed).toISOString();
  }
  return new Date(Date.now() + ttlMs).toISOString();
}

/** 权限请求归一化；options 保留原生 ID。 */
export function normalizePermissionRequest({
  sessionId,
  nativeRequestId = null,
  title = "Permission required",
  body = null,
  toolCall = {},
  options = [],
  source = {},
  expiresAt = null,
  ttlMs = 5 * 60 * 1000,
  interactionId = null,
} = {}) {
  if (!sessionId) throw new InteractionError("INVALID", "sessionId required");
  const normalizedOptions = normalizeOptions(options);
  if (normalizedOptions.length === 0) {
    throw new InteractionError("INVALID", "permission request requires at least one native option");
  }
  return {
    interactionId: interactionId ?? makeId("int"),
    kind: "permission",
    sessionId,
    nativeRequestId,
    title,
    body,
    tool: {
      toolCallId: toolCall?.toolCallId ?? null,
      kind: toolCall?.kind ?? null,
      title: toolCall?.title ?? null,
      rawInput: toolCall?.rawInput ?? null,
    },
    options: normalizedOptions,
    freeText: false,
    multiple: false,
    source: normalizeSource(source),
    createdAt: nowIso(),
    expiresAt: normalizeExpiry(expiresAt, ttlMs),
    state: "pending",
    answer: null,
    decision: null,
  };
}

/** 结构化提问归一化；无选项时降级为自由文本问题。 */
export function normalizeQuestion({
  sessionId,
  nativeRequestId = null,
  title = "Question",
  body = null,
  options = [],
  allowFreeText = true,
  multiple = false,
  source = {},
  expiresAt = null,
  ttlMs = 5 * 60 * 1000,
  interactionId = null,
} = {}) {
  if (!sessionId) throw new InteractionError("INVALID", "sessionId required");
  const normalizedOptions = normalizeOptions(options);
  return {
    interactionId: interactionId ?? makeId("int"),
    kind: "question",
    sessionId,
    nativeRequestId,
    title,
    body,
    tool: null,
    options: normalizedOptions,
    freeText: normalizedOptions.length === 0 ? true : !!allowFreeText,
    multiple: normalizedOptions.length ? !!multiple : false,
    source: normalizeSource(source),
    createdAt: nowIso(),
    expiresAt: normalizeExpiry(expiresAt, ttlMs),
    state: "pending",
    answer: null,
    decision: null,
  };
}

/** 统一事件 payload（适配器用它挂到 interaction 事件上）。 */
export function interactionEventPayload(interaction, { state = null, reason = null } = {}) {
  return {
    state: state ?? interaction.state,
    interactionId: interaction.interactionId,
    sessionId: interaction.sessionId,
    nativeRequestId: interaction.nativeRequestId,
    kind: interaction.kind,
    title: interaction.title,
    body: interaction.body,
    tool: interaction.tool,
    options: interaction.options.map((o) => ({ optionId: o.optionId, label: o.label, kind: o.kind })),
    freeText: interaction.freeText,
    multiple: interaction.multiple,
    source: interaction.source,
    expiresAt: interaction.expiresAt,
    answer: interaction.answer,
    decision: interaction.decision,
    reason,
  };
}

/** 过期判断（可注入时钟）。 */
export function isInteractionExpired(interaction, at = Date.now()) {
  return interaction?.state === "pending" && Date.parse(interaction.expiresAt) <= at;
}

/**
 * 交互登记表。只保存运行期请求，不持久化（持久化归 core）。
 */
export class InteractionRegistry {
  constructor({ clock = () => Date.now(), defaultTtlMs = 5 * 60 * 1000, maxRecords = 500, onChange = null } = {}) {
    this.clock = clock;
    this.defaultTtlMs = defaultTtlMs;
    this.maxRecords = maxRecords;
    this.onChange = onChange;
    this.records = new Map();
    this.audit = [];
    this._waiters = new Map();
  }

  createPermission(request) {
    return this.add(normalizePermissionRequest({ ttlMs: this.defaultTtlMs, ...request }));
  }

  createQuestion(request) {
    return this.add(normalizeQuestion({ ttlMs: this.defaultTtlMs, ...request }));
  }

  add(interaction) {
    assertKind(interaction?.kind);
    if (!INTERACTION_STATES.includes(interaction.state)) {
      throw new InteractionError("INVALID", `illegal interaction state: ${interaction.state}`);
    }
    this.records.set(interaction.interactionId, interaction);
    this._trim();
    this._notify(interaction);
    return interaction;
  }

  get(interactionId) {
    return this.records.get(interactionId) ?? null;
  }

  mustGet(interactionId) {
    const record = this.get(interactionId);
    if (!record) throw new InteractionError("NOT_FOUND", `unknown interaction: ${interactionId}`);
    return record;
  }

  list({ sessionId = null, state = null } = {}) {
    return [...this.records.values()].filter(
      (i) => (sessionId === null || i.sessionId === sessionId) && (state === null || i.state === state),
    );
  }

  pending(sessionId = null) {
    return this.list({ sessionId, state: "pending" });
  }

  findByNativeId(nativeRequestId) {
    return [...this.records.values()].find((i) => i.nativeRequestId === nativeRequestId) ?? null;
  }

  /**
   * 回答一个请求。只用原生 optionId；文案不参与匹配。
   * @returns {{interaction: object, answer: object}}
   */
  answer(interactionId, { optionId = null, text = null, by = "user", reason = null } = {}) {
    const interaction = this.mustGet(interactionId);
    const now = this.clock();

    if (isInteractionExpired(interaction, now)) {
      this._transition(interaction, "expired", { reason: "expired" });
      throw new InteractionError("EXPIRED", "interaction expired before answer", {
        interactionId,
        nativeRequestId: interaction.nativeRequestId,
      });
    }
    if (interaction.state !== "pending") {
      throw new InteractionError("ALREADY_ANSWERED", `interaction already ${interaction.state}`, {
        interactionId,
        state: interaction.state,
      });
    }

    const answer = this._validateAnswer(interaction, { optionId, text });
    interaction.answer = answer;
    interaction.decision = { by, at: new Date(now).toISOString(), reason };
    this.audit.push({
      interactionId,
      nativeRequestId: interaction.nativeRequestId,
      sessionId: interaction.sessionId,
      kind: interaction.kind,
      by,
      at: interaction.decision.at,
      choice: { optionId: answer.optionId, text: answer.text },
    });
    this._transition(interaction, "answered", { reason });
    return { interaction, answer };
  }

  _validateAnswer(interaction, { optionId, text }) {
    const ids = new Set(interaction.options.map((o) => o.optionId));
    if (Array.isArray(optionId)) {
      if (!interaction.multiple) throw new InteractionError("INVALID", "multiple selection not allowed");
      for (const id of optionId) {
        if (!ids.has(id)) throw new InteractionError("UNKNOWN_OPTION", `unknown optionId: ${id}`, { offered: [...ids] });
      }
      return { optionId: [...optionId], text: null };
    }
    if (optionId !== null && optionId !== undefined) {
      if (!ids.has(optionId)) {
        throw new InteractionError("UNKNOWN_OPTION", `unknown optionId: ${optionId}`, { offered: [...ids] });
      }
      return { optionId, text: interaction.freeText && text ? String(text) : null };
    }
    if (text !== null && text !== undefined && String(text).length > 0) {
      if (!interaction.freeText) {
        throw new InteractionError("INVALID", "free-text answer not allowed for this interaction");
      }
      return { optionId: null, text: String(text) };
    }
    throw new InteractionError("INVALID", "answer requires optionId or text");
  }

  expire(interactionId) {
    const interaction = this.mustGet(interactionId);
    if (interaction.state !== "pending") return interaction;
    this._transition(interaction, "expired", { reason: "expired" });
    return interaction;
  }

  /** 按当前时钟批量过期。 */
  expireDue() {
    const now = this.clock();
    const expired = [];
    for (const interaction of this.records.values()) {
      if (isInteractionExpired(interaction, now)) {
        this._transition(interaction, "expired", { reason: "expired" });
        expired.push(interaction);
      }
    }
    return expired;
  }

  /** 回合停止时关闭该会话所有未决请求（SPEC 8.3 第 7 条）。 */
  cancelForSession(sessionId, reason = "turn-stopped", state = "cancelled") {
    const cancelled = [];
    for (const interaction of this.records.values()) {
      if (interaction.sessionId !== sessionId || interaction.state !== "pending") continue;
      this._transition(interaction, state, { reason });
      cancelled.push(interaction);
    }
    return cancelled;
  }

  supersede(interactionId, reason = "superseded") {
    const interaction = this.mustGet(interactionId);
    if (interaction.state !== "pending") return interaction;
    this._transition(interaction, "superseded", { reason });
    return interaction;
  }

  /** 上游响应形状：selected / cancelled。 */
  upstreamOutcome(interaction) {
    if (interaction.state === "answered" && interaction.answer?.optionId) {
      return { outcome: { outcome: "selected", optionId: interaction.answer.optionId } };
    }
    return { outcome: { outcome: "cancelled" } };
  }

  /** 等待某个请求被回答（测试与适配器内部用）。 */
  waitForAnswer(interactionId, timeoutMs = 5000) {
    const existing = this.mustGet(interactionId);
    if (existing.state !== "pending") return Promise.resolve(existing);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this._waiters.delete(interactionId);
        reject(new InteractionError("EXPIRED", "timed out waiting for answer", { interactionId }));
      }, timeoutMs);
      timer.unref?.();
      this._waiters.set(interactionId, (interaction) => {
        clearTimeout(timer);
        resolve(interaction);
      });
    });
  }

  toJSON() {
    return {
      pending: this.pending().map((i) => interactionEventPayload(i)),
      answered: this.list({ state: "answered" }).map((i) => interactionEventPayload(i)),
      audit: [...this.audit],
    };
  }

  _transition(interaction, state, { reason = null } = {}) {
    if (!INTERACTION_STATES.includes(state)) throw new InteractionError("INVALID", `illegal interaction state: ${state}`);
    interaction.state = state;
    if (reason) interaction.stateReason = reason;
    this._notify(interaction);
    const waiter = this._waiters.get(interaction.interactionId);
    if (waiter && TERMINAL_STATES.has(state)) {
      this._waiters.delete(interaction.interactionId);
      waiter(interaction);
    }
  }

  _notify(interaction) {
    this.onChange?.(interaction);
  }

  _trim() {
    if (this.records.size <= this.maxRecords) return;
    for (const [id, interaction] of this.records) {
      if (this.records.size <= this.maxRecords) break;
      if (TERMINAL_STATES.has(interaction.state)) this.records.delete(id);
    }
  }
}
