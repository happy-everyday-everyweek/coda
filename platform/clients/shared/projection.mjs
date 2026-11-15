/**
 * 客户端共享只读投影（TUI / Web UI 共用）。
 *
 * 约定（SPEC 12 / 13.3）：
 * - 客户端只消费 `core.subscribe` 的快照和有序事件，不读核心数据库。
 * - 投影是幂等的：同一快照 + 同一事件序列得到同一个 canonical 结果。
 * - 不按 Agent 名称分支；能力一律来自会话/能力声明。
 *
 * 事件载荷（与 CONTRACT.md 配套的最小约定）：
 *   session      { session } 或 { id, state, ... }
 *   message      { message } / { delta:true, message:{id,text} }
 *   status       { status } / { sessionId, state, detail }
 *   turn         { turn:{ id, state, sessionId } }
 *   interaction  { interaction } / { interactionId, status, answer }
 *   terminal     { terminal } / { terminalId, chunk }
 *   artifact     { artifact }
 *   model        { model } / { current, options }
 *   notification { notification }
 *   error        { error:{ message, code } }
 */

export const MAX_TERMINAL_CHARS = 20_000;
export const MAX_ARTIFACT_DIFF_CHARS = 20_000;
const MAX_NOTIFICATIONS = 100;
const MAX_SESSION_ERRORS = 20;

export function createProjectionState(meta = {}) {
  return {
    seq: 0,
    protocol: {
      version: meta.protocolVersion ?? null,
      coreVersion: meta.coreVersion ?? null,
      negotiated: false,
    },
    connection: { status: "idle", error: null, seq: 0 },
    localNode: meta.localNode ?? null,
    sessions: new Map(),
    messages: new Map(),
    interactions: new Map(),
    terminals: new Map(),
    artifacts: new Map(),
    notifications: [],
    models: { current: null, options: [] },
    capabilities: null,
    lastEvent: null,
    _order: 0,
  };
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function normalizeAttachments(value) {
  return asArray(value)
    .filter((a) => a && typeof a === "object")
    .map((a) => ({
      id: a.id ?? a.artifactId ?? null,
      name: a.name ?? a.filename ?? "attachment",
      size: Number.isFinite(a.size) ? a.size : null,
      mime: a.mime ?? null,
      artifactId: a.artifactId ?? a.id ?? null,
    }));
}

function normalizeOptions(value) {
  return asArray(value)
    .filter((o) => o && (typeof o === "string" || typeof o === "object"))
    .map((o, i) =>
      typeof o === "string"
        ? { id: o, label: o }
        : { id: o.id ?? o.value ?? `option_${i + 1}`, label: o.label ?? o.name ?? String(o.id ?? o.value ?? i + 1) },
    );
}

function normalizeModels(value) {
  if (!value) return { current: null, options: [] };
  if (Array.isArray(value)) {
    const options = normalizeOptions(value);
    return { current: options[0]?.id ?? null, options };
  }
  const options = normalizeOptions(value.options ?? value.available ?? value.models);
  return { current: value.current ?? value.selected ?? options[0]?.id ?? null, options };
}

function trimMiddleText(value, max) {
  const text = typeof value === "string" ? value : "";
  if (text.length <= max) return { text, truncated: false };
  return { text: text.slice(text.length - max), truncated: true };
}

function touch(state, next) {
  state._order = Math.max(state._order, Number.isFinite(next?.order) ? next.order : 0);
}

export function upsertSession(state, raw) {
  if (!raw || typeof raw !== "object") return null;
  const id = raw.id ?? raw.sessionId;
  if (!id) return null;
  const prev = state.sessions.get(id) ?? { id };
  const next = { ...prev, ...raw, id };
  if (next.capabilities === undefined) next.capabilities = prev.capabilities ?? null;
  state.sessions.set(id, next);
  return next;
}

export function upsertMessage(state, raw, { delta = false, event = null } = {}) {
  if (!raw || typeof raw !== "object") return null;
  const id = raw.id ?? raw.messageId ?? null;
  if (!id) return null;
  const prev = state.messages.get(id) ?? null;
  const sessionId = raw.sessionId ?? prev?.sessionId ?? event?.sessionId ?? null;
  const incomingText = typeof raw.text === "string" ? raw.text : null;
  let text = prev?.text ?? "";
  if (delta) text += incomingText ?? "";
  else if (incomingText !== null) text = incomingText;

  const next = {
    ...(prev ?? {}),
    ...raw,
    id,
    sessionId,
    text,
    order: prev?.order ?? (Number.isFinite(raw.order) ? raw.order : ++state._order),
    streaming: raw.streaming ?? (delta ? true : prev?.streaming ?? false),
    role: raw.role ?? prev?.role ?? "assistant",
    ts: raw.ts ?? prev?.ts ?? null,
    attachments: normalizeAttachments(raw.attachments ?? prev?.attachments),
  };
  state.messages.set(id, next);
  touch(state, next);
  return next;
}

export function upsertInteraction(state, raw) {
  if (!raw || typeof raw !== "object") return null;
  const id = raw.id ?? raw.interactionId;
  if (!id) return null;
  const prev = state.interactions.get(id) ?? { id };
  const next = {
    ...prev,
    ...raw,
    id,
    sessionId: raw.sessionId ?? prev.sessionId ?? null,
    kind: raw.kind ?? prev.kind ?? "approval",
    prompt: raw.prompt ?? raw.title ?? prev.prompt ?? "",
    options: normalizeOptions(raw.options ?? prev.options),
    status: raw.status ?? (raw.answer ? "answered" : prev.status ?? "pending"),
  };
  state.interactions.set(id, next);
  return next;
}

export function upsertTerminal(state, raw) {
  if (!raw || typeof raw !== "object") return null;
  const id = raw.id ?? raw.terminalId;
  if (!id) return null;
  const prev = state.terminals.get(id) ?? { id };
  const chunk = typeof raw.chunk === "string" ? raw.chunk : null;
  let output = typeof raw.output === "string" ? raw.output : prev.output ?? "";
  const next = {
    ...prev,
    ...raw,
    id,
    sessionId: raw.sessionId ?? prev.sessionId ?? null,
    title: raw.title ?? prev.title ?? "terminal",
    status: raw.status ?? prev.status ?? "open",
    node: raw.node ?? prev.node ?? null,
    environment: raw.environment ?? prev.environment ?? null,
  };

  if (chunk !== null) {
    const baseLength = Number.isFinite(prev.fullLength) ? prev.fullLength : (prev.output ?? "").length;
    next.fullLength = baseLength + chunk.length;
    output = (prev.output ?? "") + chunk;
  } else if (Number.isFinite(raw.fullLength)) {
    next.fullLength = raw.fullLength;
  } else {
    next.fullLength = next.fullLength ?? output.length;
  }

  if (output.length > MAX_TERMINAL_CHARS) {
    output = output.slice(output.length - MAX_TERMINAL_CHARS);
    next.truncated = true;
  }
  if (raw.truncated === true) next.truncated = true;
  next.output = output;
  state.terminals.set(id, next);
  return next;
}

export function upsertArtifact(state, raw) {
  if (!raw || typeof raw !== "object") return null;
  const id = raw.id ?? raw.artifactId;
  if (!id) return null;
  const prev = state.artifacts.get(id) ?? { id };
  const next = {
    ...prev,
    ...raw,
    id,
    sessionId: raw.sessionId ?? prev.sessionId ?? null,
    name: raw.name ?? prev.name ?? id,
    kind: raw.kind ?? prev.kind ?? "file",
    mime: raw.mime ?? prev.mime ?? "application/octet-stream",
    size: Number.isFinite(raw.size) ? raw.size : prev.size ?? null,
    sha256: raw.sha256 ?? prev.sha256 ?? null,
    node: raw.node ?? prev.node ?? null,
    environment: raw.environment ?? prev.environment ?? null,
    meta: raw.meta ?? prev.meta ?? null,
  };
  if (typeof next.diff === "string" && next.diff.length > MAX_ARTIFACT_DIFF_CHARS) {
    next.diff = next.diff.slice(0, MAX_ARTIFACT_DIFF_CHARS);
    next.diffTruncated = true;
  }
  state.artifacts.set(id, next);
  return next;
}

/** 用快照整体替换投影（刷新、重新对齐、首次订阅）。 */
export function resetFromSnapshot(state, seq, snapshot) {
  const snap = snapshot ?? {};
  const nextSeq = Number.isInteger(seq) ? seq : Number.isInteger(snap.seq) ? snap.seq : 0;

  state.sessions.clear();
  state.messages.clear();
  state.interactions.clear();
  state.terminals.clear();
  state.artifacts.clear();
  state.notifications = [];
  state._order = 0;
  state.seq = nextSeq;
  state.connection.seq = nextSeq;

  for (const raw of asArray(snap.sessions)) upsertSession(state, raw);

  if (Array.isArray(snap.messages)) {
    for (const raw of snap.messages) upsertMessage(state, raw);
  } else if (snap.messages && typeof snap.messages === "object") {
    for (const [sessionId, list] of Object.entries(snap.messages)) {
      for (const raw of asArray(list)) upsertMessage(state, { sessionId, ...raw });
    }
  }

  for (const raw of asArray(snap.interactions)) upsertInteraction(state, raw);
  for (const raw of asArray(snap.terminals)) upsertTerminal(state, raw);
  for (const raw of asArray(snap.artifacts)) upsertArtifact(state, raw);
  for (const raw of asArray(snap.notifications)) {
    state.notifications.push({ ...raw });
  }
  if (state.notifications.length > MAX_NOTIFICATIONS) {
    state.notifications = state.notifications.slice(-MAX_NOTIFICATIONS);
  }

  state.models = normalizeModels(snap.models ?? { current: snap.currentModel, options: snap.modelOptions });
  state.capabilities = snap.capabilities ?? null;
  return state;
}

function applySessionEvent(state, p, event) {
  const raw = p.session ?? (p.id || p.sessionId ? p : null);
  if (raw) {
    const merged = upsertSession(state, raw);
    if (merged && p.state && raw.state === undefined) merged.state = p.state;
  }
}

function applyStatusEvent(state, p, event) {
  const s = p.status ?? p;
  const id = s.sessionId ?? s.id ?? event.sessionId;
  if (!id) return;
  const prev = state.sessions.get(id) ?? { id };
  const next = { ...prev };
  if (s.state) next.state = s.state;
  if (s.detail !== undefined) next.detail = s.detail;
  if (s.updatedAt) next.updatedAt = s.updatedAt;
  if (s.model) next.model = s.model;
  state.sessions.set(id, next);
}

function applyTurnEvent(state, p, event) {
  const t = p.turn ?? p;
  const id = t.sessionId ?? event.sessionId;
  if (!id) return;
  const prev = state.sessions.get(id) ?? { id };
  const turn = { ...(prev.turn ?? {}), ...t, id: t.id ?? prev.turn?.id ?? null };
  const next = { ...prev, turn, turnState: t.state ?? prev.turnState ?? turn.state ?? null };
  if (t.model) next.model = t.model;
  state.sessions.set(id, next);
}

function applyInteractionEvent(state, p) {
  const raw = p.interaction ?? (p.interactionId || p.id ? p : null);
  if (raw) upsertInteraction(state, raw);
}

function applyTerminalEvent(state, p) {
  const raw = p.terminal ?? (p.terminalId || p.id ? p : null);
  if (raw) upsertTerminal(state, raw);
}

function applyArtifactEvent(state, p) {
  const raw = p.artifact ?? (p.id || p.artifactId ? p : null);
  if (raw) upsertArtifact(state, raw);
}

function applyModelEvent(state, p) {
  const m = p.model ?? p;
  if (typeof m === "string") {
    state.models.current = m;
  } else if (m && typeof m === "object") {
    if (typeof m.id === "string") state.models.current = m.id;
    if (m.current) state.models.current = m.current;
    if (Array.isArray(m.options)) state.models.options = normalizeOptions(m.options);
  }
  if (Array.isArray(p.options)) state.models.options = normalizeOptions(p.options);
  if (typeof p.current === "string") state.models.current = p.current;

  const sessionId = p.sessionId ?? (typeof m === "object" ? m?.sessionId : null);
  if (sessionId && state.models.current) {
    const prev = state.sessions.get(sessionId) ?? { id: sessionId };
    state.sessions.set(sessionId, { ...prev, model: state.models.current });
  }
}

function applyNotificationEvent(state, p) {
  const raw = p.notification ?? p;
  if (!raw || typeof raw !== "object") return;
  state.notifications.push({ ...raw });
  if (state.notifications.length > MAX_NOTIFICATIONS) {
    state.notifications = state.notifications.slice(-MAX_NOTIFICATIONS);
  }
}

function applyErrorEvent(state, p, event) {
  const err = p.error ?? p;
  const id = event.sessionId ?? err.sessionId ?? null;
  if (!id) return;
  const prev = state.sessions.get(id) ?? { id };
  const errors = asArray(prev.errors).slice(-MAX_SESSION_ERRORS + 1);
  errors.push({
    message: err.message ?? String(err ?? "unknown error"),
    code: err.code ?? null,
    ts: event.ts ?? null,
  });
  state.sessions.set(id, { ...prev, errors });
}

/** 应用一个有序事件；返回是否应用、是否出现 seq 缺口、是否重复。 */
export function applyEvent(state, event) {
  if (!event || typeof event !== "object" || !Number.isInteger(event.seq)) {
    return { applied: false, gap: false, duplicate: false };
  }
  if (event.seq <= state.seq) {
    return { applied: false, gap: false, duplicate: true };
  }
  const gap = event.seq > state.seq + 1;
  state.seq = event.seq;
  state.connection.seq = event.seq;
  state.lastEvent = { kind: event.kind, seq: event.seq, ts: event.ts ?? null };

  const p = event.payload ?? {};
  switch (event.kind) {
    case "session":
      applySessionEvent(state, p, event);
      break;
    case "message":
      upsertMessage(state, p.message ?? p, { delta: p.delta === true || p.message?.delta === true, event });
      break;
    case "status":
      applyStatusEvent(state, p, event);
      break;
    case "turn":
      applyTurnEvent(state, p, event);
      break;
    case "interaction":
      applyInteractionEvent(state, p);
      break;
    case "terminal":
      applyTerminalEvent(state, p);
      break;
    case "artifact":
      applyArtifactEvent(state, p);
      break;
    case "model":
      applyModelEvent(state, p);
      break;
    case "notification":
      applyNotificationEvent(state, p);
      break;
    case "error":
      applyErrorEvent(state, p, event);
      break;
    default:
      // reasoning / tool / plan / subagent / run 等事件不进入消息投影；
      // 客户端只把它们计入最后事件，具体视图按需读取，避免伪造未声明的 UI。
      break;
  }
  return { applied: true, gap };
}

/** 投影 → 稳定的可比较对象（三端契约测试用）。 */
export function canonicalProjection(state) {
  const sessions = [...state.sessions.values()]
    .map((s) => ({
      id: s.id,
      title: s.title ?? null,
      state: s.state ?? "unknown",
      agent: s.agent ?? s.agentInstance ?? null,
      model: s.model ?? null,
      workspace: s.workspace ?? null,
      node: s.node ?? s.nodeId ?? null,
      environment: s.environment ?? null,
      updatedAt: s.updatedAt ?? null,
      turnState: s.turnState ?? s.turn?.state ?? null,
      capabilities: s.capabilities ?? null,
      unread: Number.isFinite(s.unread) ? s.unread : 0,
    }))
    .sort((a, b) => a.id.localeCompare(b.id));

  const messages = [...state.messages.values()]
    .map((m) => ({
      id: m.id,
      sessionId: m.sessionId ?? null,
      role: m.role ?? "assistant",
      text: m.text ?? "",
      streaming: !!m.streaming,
      ts: m.ts ?? null,
      order: Number.isFinite(m.order) ? m.order : 0,
      attachments: normalizeAttachments(m.attachments),
    }))
    .sort((a, b) => a.order - b.order || a.id.localeCompare(b.id));

  const interactions = [...state.interactions.values()]
    .map((i) => ({
      id: i.id,
      sessionId: i.sessionId ?? null,
      kind: i.kind ?? "approval",
      prompt: i.prompt ?? "",
      options: normalizeOptions(i.options),
      status: i.status ?? "pending",
      answer: i.answer ?? null,
      expiresAt: i.expiresAt ?? null,
      source: i.source ?? i.origin ?? null,
    }))
    .sort((a, b) => a.id.localeCompare(b.id));

  const terminals = [...state.terminals.values()]
    .map((t) => ({
      id: t.id,
      sessionId: t.sessionId ?? null,
      title: t.title ?? "terminal",
      status: t.status ?? "open",
      node: t.node ?? null,
      environment: t.environment ?? null,
      output: t.output ?? "",
      fullLength: Number.isFinite(t.fullLength) ? t.fullLength : (t.output ?? "").length,
      truncated: !!t.truncated,
    }))
    .sort((a, b) => a.id.localeCompare(b.id));

  const artifacts = [...state.artifacts.values()]
    .map((a) => {
      const trimmed = trimMiddleText(a.diff, MAX_ARTIFACT_DIFF_CHARS);
      return {
        id: a.id,
        sessionId: a.sessionId ?? null,
        name: a.name,
        kind: a.kind,
        mime: a.mime,
        size: a.size,
        sha256: a.sha256,
        node: a.node,
        environment: a.environment,
        meta: a.meta ?? null,
        diff: trimmed.text || null,
        diffTruncated: !!(a.diffTruncated || trimmed.truncated),
      };
    })
    .sort((a, b) => a.id.localeCompare(b.id));

  return {
    protocol: {
      version: state.protocol.version,
      coreVersion: state.protocol.coreVersion,
      negotiated: state.protocol.negotiated,
    },
    connection: {
      status: state.connection.status,
      error: state.connection.error,
      seq: state.connection.seq,
    },
    capabilities: state.capabilities,
    sessions,
    messages,
    interactions,
    models: {
      current: state.models.current ?? null,
      options: normalizeOptions(state.models.options),
    },
    terminals,
    artifacts,
    notifications: state.notifications.map((n) => ({
      id: n.id ?? null,
      level: n.level ?? "info",
      text: n.text ?? n.message ?? "",
      sessionId: n.sessionId ?? null,
      ts: n.ts ?? null,
      read: !!n.read,
    })),
    counts: {
      sessions: sessions.length,
      messages: messages.length,
      pendingInteractions: interactions.filter((i) => i.status === "pending").length,
      terminals: terminals.length,
      artifacts: artifacts.length,
      notifications: state.notifications.length,
    },
  };
}
