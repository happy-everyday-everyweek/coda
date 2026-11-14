/**
 * CORE-102：核心领域对象与校验（SPEC 13.1）。
 *
 * 设计约束：
 * - 所有对象都是纯 JSON 可序列化结构，时间戳为 ISO-8601 UTC 字符串。
 * - ID 一律来自 `contracts/ids.mjs`（禁止自造前缀）。
 * - 工厂函数填默认值并做校验；`validateXxx()` 返回错误数组，便于 RPC 层聚合。
 * - 这里是「形状与合法性」，不是状态机；状态推进在 journal / server 层。
 */
import {
  makeId,
  nowIso,
  isIdOf,
  SESSION_STATES,
  TURN_STATES,
  RUN_STATES,
  INTERACTION_STATES,
} from "../contracts/ids.mjs";
import { EVENT_KINDS } from "../contracts/events.mjs";
import { COMMAND_KINDS, COMMAND_STATES } from "../contracts/commands.mjs";
import { ValidationError } from "./errors.mjs";

export const DEVICE_PLATFORMS = ["windows", "macos", "linux", "android", "ios", "unknown"];
export const ENVIRONMENT_KINDS = ["host", "wsl", "ssh", "container"];
export const ADAPTER_TYPES = ["acp", "cli", "app-server", "native", "mock"];
export const AGENT_HEALTH_STATES = ["unknown", "healthy", "unhealthy", "missing"];
export const INTERACTION_KINDS = ["approval", "question", "permission", "input"];
export const TERMINAL_CONFIDENCE = ["confirmed", "process-exit", "unknown"];
export const NOTIFICATION_TYPES = ["completed", "failed", "approval", "connection", "turn", "info"];

const SHA256_RE = /^[0-9a-f]{64}$/;
const ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/;

const isPlainObject = (v) => !!v && typeof v === "object" && !Array.isArray(v);
const isIso = (v) => typeof v === "string" && ISO_RE.test(v) && !Number.isNaN(Date.parse(v));
const isNonEmptyString = (v) => typeof v === "string" && v.length > 0;
const isNonNegativeInt = (v) => Number.isInteger(v) && v >= 0;

function pushId(errors, value, prefix, field) {
  if (!isIdOf(value, prefix)) errors.push(`${field} must be an id with prefix "${prefix}_"`);
}

function pushIso(errors, value, field, { nullable = false } = {}) {
  if (nullable && (value === null || value === undefined)) return;
  if (!isIso(value)) errors.push(`${field} must be an ISO-8601 UTC timestamp`);
}

function pushEnum(errors, value, allowed, field) {
  if (!allowed.includes(value)) errors.push(`${field} must be one of ${allowed.join("|")}`);
}

function pushObject(errors, value, field) {
  if (!isPlainObject(value)) errors.push(`${field} must be a plain object`);
}

function assert(validator, obj, label) {
  const errors = validator(obj);
  if (errors.length > 0) {
    throw new ValidationError(`${label} is invalid: ${errors.join("; ")}`, { object: label, errors });
  }
  return obj;
}

/* ------------------------------------------------------------------ Device */

export function createDevice({
  id = makeId("dev"),
  name,
  platform = "unknown",
  publicKey = null,
  online = false,
  authorized = false,
  version = null,
  createdAt = nowIso(),
  lastSeenAt = null,
} = {}) {
  return assert(validateDevice, {
    id,
    name: name ?? "device",
    platform,
    publicKey,
    online: !!online,
    authorized: !!authorized,
    version,
    createdAt,
    lastSeenAt,
  }, "Device");
}

export function validateDevice(d = {}) {
  const errors = [];
  pushId(errors, d.id, "dev", "id");
  if (!isNonEmptyString(d.name)) errors.push("name is required");
  pushEnum(errors, d.platform, DEVICE_PLATFORMS, "platform");
  if (d.publicKey !== null && typeof d.publicKey !== "string") errors.push("publicKey must be string or null");
  if (typeof d.online !== "boolean") errors.push("online must be boolean");
  if (typeof d.authorized !== "boolean") errors.push("authorized must be boolean");
  pushIso(errors, d.createdAt, "createdAt");
  pushIso(errors, d.lastSeenAt, "lastSeenAt", { nullable: true });
  return errors;
}

/* ---------------------------------------------------- ExecutionEnvironment */

export function createExecutionEnvironment({
  id = makeId("env"),
  deviceId,
  kind = "host",
  name = null,
  distro = null,
  available = true,
  metadata = {},
  createdAt = nowIso(),
} = {}) {
  return assert(validateExecutionEnvironment, {
    id,
    deviceId,
    kind,
    name: name ?? (kind === "host" ? "host" : kind),
    distro,
    available: !!available,
    metadata,
    createdAt,
  }, "ExecutionEnvironment");
}

export function validateExecutionEnvironment(e = {}) {
  const errors = [];
  pushId(errors, e.id, "env", "id");
  pushId(errors, e.deviceId, "dev", "deviceId");
  pushEnum(errors, e.kind, ENVIRONMENT_KINDS, "kind");
  if (!isNonEmptyString(e.name)) errors.push("name is required");
  if (e.distro !== null && e.distro !== undefined && typeof e.distro !== "string") errors.push("distro must be string or null");
  if (typeof e.available !== "boolean") errors.push("available must be boolean");
  pushObject(errors, e.metadata, "metadata");
  pushIso(errors, e.createdAt, "createdAt");
  return errors;
}

/* -------------------------------------------------------- AgentDefinition */

export function createAgentDefinition({
  id = makeId("ad"),
  provider,
  adapterType = "acp",
  protocol = null,
  displayName = null,
  homepage = null,
  createdAt = nowIso(),
} = {}) {
  return assert(validateAgentDefinition, {
    id,
    provider,
    adapterType,
    protocol,
    displayName: displayName ?? provider,
    homepage,
    createdAt,
  }, "AgentDefinition");
}

export function validateAgentDefinition(d = {}) {
  const errors = [];
  pushId(errors, d.id, "ad", "id");
  if (!isNonEmptyString(d.provider)) errors.push("provider is required");
  pushEnum(errors, d.adapterType, ADAPTER_TYPES, "adapterType");
  if (d.protocol !== null && d.protocol !== undefined && typeof d.protocol !== "string") errors.push("protocol must be string or null");
  if (!isNonEmptyString(d.displayName)) errors.push("displayName is required");
  pushIso(errors, d.createdAt, "createdAt");
  return errors;
}

/* ---------------------------------------------------------- AgentInstance */

export function createAgentInstance({
  id = makeId("ai"),
  definitionId,
  environmentId,
  version = "unknown",
  accountId = null,
  installPath = null,
  capabilities = null,
  health = {},
  createdAt = nowIso(),
  updatedAt = nowIso(),
} = {}) {
  return assert(validateAgentInstance, {
    id,
    definitionId,
    environmentId,
    version,
    accountId,
    installPath,
    capabilities,
    health: {
      state: "unknown",
      message: null,
      checkedAt: null,
      ...health,
    },
    createdAt,
    updatedAt,
  }, "AgentInstance");
}

export function validateAgentInstance(a = {}) {
  const errors = [];
  pushId(errors, a.id, "ai", "id");
  pushId(errors, a.definitionId, "ad", "definitionId");
  pushId(errors, a.environmentId, "env", "environmentId");
  if (!isNonEmptyString(a.version)) errors.push("version is required");
  if (a.accountId !== null && a.accountId !== undefined && typeof a.accountId !== "string") errors.push("accountId must be string or null");
  if (a.installPath !== null && a.installPath !== undefined && typeof a.installPath !== "string") errors.push("installPath must be string or null");
  if (a.capabilities !== null && a.capabilities !== undefined) pushObject(errors, a.capabilities, "capabilities");
  pushObject(errors, a.health, "health");
  if (isPlainObject(a.health)) pushEnum(errors, a.health.state, AGENT_HEALTH_STATES, "health.state");
  pushIso(errors, a.createdAt, "createdAt");
  pushIso(errors, a.updatedAt, "updatedAt");
  return errors;
}

/* -------------------------------------------------------------- Workspace */

export function emptyRepoInfo() {
  return {
    isRepo: false,
    root: null,
    branch: null,
    head: null,
    worktree: false,
    worktreePath: null,
    remote: null,
    degraded: false,
    error: null,
  };
}

export function createWorkspace({
  id = makeId("ws"),
  deviceId,
  environmentId,
  path,
  displayPath = null,
  label = null,
  repo = emptyRepoInfo(),
  createdAt = nowIso(),
  updatedAt = nowIso(),
} = {}) {
  const base = label ?? (typeof path === "string" ? path.split(/[\\/]/).filter(Boolean).pop() ?? "workspace" : "workspace");
  return assert(validateWorkspace, {
    id,
    deviceId,
    environmentId,
    path,
    displayPath: displayPath ?? path,
    label: base,
    repo: { ...emptyRepoInfo(), ...repo },
    createdAt,
    updatedAt,
  }, "Workspace");
}

/** WORK-001：工作区身份 = 节点 + 环境 + 规范化路径。 */
export function workspaceIdentity(ws) {
  if (!ws || typeof ws !== "object") throw new ValidationError("workspace is required");
  return `${ws.deviceId}::${ws.environmentId}::${ws.path}`;
}

export function validateWorkspace(w = {}) {
  const errors = [];
  pushId(errors, w.id, "ws", "id");
  pushId(errors, w.deviceId, "dev", "deviceId");
  pushId(errors, w.environmentId, "env", "environmentId");
  if (!isNonEmptyString(w.path)) errors.push("path is required");
  if (!isNonEmptyString(w.label)) errors.push("label is required");
  pushObject(errors, w.repo, "repo");
  if (isPlainObject(w.repo) && typeof w.repo.isRepo !== "boolean") errors.push("repo.isRepo must be boolean");
  pushIso(errors, w.createdAt, "createdAt");
  pushIso(errors, w.updatedAt, "updatedAt");
  return errors;
}

/* ---------------------------------------------------------------- Session */

export function createSession({
  id = makeId("ses"),
  nodeId,
  environmentId,
  agentInstanceId,
  workspaceId,
  providerThreadId = null,
  model = null,
  options = {},
  state = "idle",
  title = null,
  archived = false,
  unread = 0,
  createdAt = nowIso(),
  updatedAt = nowIso(),
  lastActivityAt = createdAt,
} = {}) {
  return assert(validateSession, {
    id,
    nodeId,
    environmentId,
    agentInstanceId,
    workspaceId,
    providerThreadId,
    model,
    options,
    state,
    title,
    archived: !!archived,
    unread,
    createdAt,
    updatedAt,
    lastActivityAt,
  }, "Session");
}

export function validateSession(s = {}) {
  const errors = [];
  pushId(errors, s.id, "ses", "id");
  pushId(errors, s.nodeId, "dev", "nodeId");
  pushId(errors, s.environmentId, "env", "environmentId");
  pushId(errors, s.agentInstanceId, "ai", "agentInstanceId");
  pushId(errors, s.workspaceId, "ws", "workspaceId");
  if (s.providerThreadId !== null && s.providerThreadId !== undefined && typeof s.providerThreadId !== "string") errors.push("providerThreadId must be string or null");
  if (s.model !== null && s.model !== undefined && typeof s.model !== "string") errors.push("model must be string or null");
  pushObject(errors, s.options, "options");
  pushEnum(errors, s.state, SESSION_STATES, "state");
  if (s.title !== null && s.title !== undefined && typeof s.title !== "string") errors.push("title must be string or null");
  if (typeof s.archived !== "boolean") errors.push("archived must be boolean");
  if (!isNonNegativeInt(s.unread)) errors.push("unread must be a non-negative integer");
  pushIso(errors, s.createdAt, "createdAt");
  pushIso(errors, s.updatedAt, "updatedAt");
  pushIso(errors, s.lastActivityAt, "lastActivityAt", { nullable: true });
  return errors;
}

/* -------------------------------------------------------------------- Run */

export function createRun({
  id = makeId("run"),
  sessionId,
  state = "queued",
  turns = [],
  replacementOf = null,
  reason = null,
  createdAt = nowIso(),
  updatedAt = createdAt,
  endedAt = null,
} = {}) {
  return assert(validateRun, {
    id,
    sessionId,
    state,
    turns,
    replacementOf,
    reason,
    createdAt,
    updatedAt,
    endedAt,
  }, "Run");
}

export function validateRun(r = {}) {
  const errors = [];
  pushId(errors, r.id, "run", "id");
  pushId(errors, r.sessionId, "ses", "sessionId");
  pushEnum(errors, r.state, RUN_STATES, "state");
  if (!Array.isArray(r.turns)) errors.push("turns must be an array");
  if (r.replacementOf !== null && r.replacementOf !== undefined) pushId(errors, r.replacementOf, "run", "replacementOf");
  pushIso(errors, r.createdAt, "createdAt");
  pushIso(errors, r.updatedAt, "updatedAt");
  pushIso(errors, r.endedAt, "endedAt", { nullable: true });
  return errors;
}

/* ------------------------------------------------------------------- Turn */

export function createTurn({
  id = makeId("turn"),
  runId,
  sessionId,
  index = 0,
  state = "queued",
  providerTurnId = null,
  terminalConfidence = "unknown",
  stopReason = null,
  createdAt = nowIso(),
  updatedAt = createdAt,
  endedAt = null,
} = {}) {
  return assert(validateTurn, {
    id,
    runId: runId ?? null,
    sessionId,
    index,
    state,
    providerTurnId,
    terminalConfidence,
    stopReason,
    createdAt,
    updatedAt,
    endedAt,
  }, "Turn");
}

export function validateTurn(t = {}) {
  const errors = [];
  pushId(errors, t.id, "turn", "id");
  pushId(errors, t.sessionId, "ses", "sessionId");
  if (t.runId !== null && t.runId !== undefined) pushId(errors, t.runId, "run", "runId");
  if (!isNonNegativeInt(t.index)) errors.push("index must be a non-negative integer");
  pushEnum(errors, t.state, TURN_STATES, "state");
  pushEnum(errors, t.terminalConfidence, TERMINAL_CONFIDENCE, "terminalConfidence");
  pushIso(errors, t.createdAt, "createdAt");
  pushIso(errors, t.updatedAt, "updatedAt");
  pushIso(errors, t.endedAt, "endedAt", { nullable: true });
  return errors;
}

/* ------------------------------------------------------------------ Event */

export function createEventRecord({
  id = makeId("evt"),
  seq,
  kind,
  sessionId = null,
  payload = {},
  ts = nowIso(),
}) {
  return assert(validateEventRecord, { id, seq, kind, sessionId, payload, ts }, "Event");
}

export function validateEventRecord(e = {}) {
  const errors = [];
  pushId(errors, e.id, "evt", "id");
  if (!Number.isInteger(e.seq) || e.seq < 1) errors.push("seq must be a positive integer");
  pushEnum(errors, e.kind, EVENT_KINDS, "kind");
  if (e.kind !== "notification" && e.kind !== "session") {
    pushId(errors, e.sessionId, "ses", "sessionId");
  }
  pushObject(errors, e.payload, "payload");
  pushIso(errors, e.ts, "ts");
  return errors;
}

/* ------------------------------------------------------ InteractionRequest */

export function createInteractionRequest({
  id = makeId("int"),
  sessionId,
  turnId = null,
  kind = "approval",
  providerRequestId = null,
  originNodeId = null,
  state = "pending",
  prompt = "",
  options = [],
  answer = null,
  createdAt = nowIso(),
  expiresAt = null,
  answeredAt = null,
  answeredBy = null,
} = {}) {
  return assert(validateInteractionRequest, {
    id,
    sessionId,
    turnId,
    kind,
    providerRequestId,
    originNodeId,
    state,
    prompt,
    options,
    answer,
    createdAt,
    expiresAt,
    answeredAt,
    answeredBy,
  }, "InteractionRequest");
}

export function validateInteractionRequest(r = {}) {
  const errors = [];
  pushId(errors, r.id, "int", "id");
  pushId(errors, r.sessionId, "ses", "sessionId");
  if (r.turnId !== null && r.turnId !== undefined) pushId(errors, r.turnId, "turn", "turnId");
  pushEnum(errors, r.kind, INTERACTION_KINDS, "kind");
  pushEnum(errors, r.state, INTERACTION_STATES, "state");
  if (typeof r.prompt !== "string") errors.push("prompt must be string");
  if (!Array.isArray(r.options)) errors.push("options must be an array");
  pushIso(errors, r.createdAt, "createdAt");
  pushIso(errors, r.expiresAt, "expiresAt", { nullable: true });
  pushIso(errors, r.answeredAt, "answeredAt", { nullable: true });
  return errors;
}

/** 审批/提问是否还能被回答（过期由调用方结合 clock 判断）。 */
export function interactionIsAnswerable(request, at = nowIso()) {
  if (!request || request.state !== "pending") return false;
  if (!request.expiresAt) return true;
  return Date.parse(request.expiresAt) > Date.parse(at);
}

/* ---------------------------------------------------------------- Command */

export function createCommandRecord({
  id = makeId("cmd"),
  kind,
  source = "ui",
  target = {},
  payload = {},
  state = "accepted",
  supersedes = null,
  createdAt = nowIso(),
  expiresAt = null,
  dispatchedAt = null,
  settledAt = null,
  attempts = 0,
  result = null,
  error = null,
} = {}) {
  return assert(validateCommandRecord, {
    id,
    kind,
    source,
    target,
    payload,
    state,
    supersedes,
    createdAt,
    expiresAt: expiresAt ?? new Date(Date.parse(createdAt) + 60_000).toISOString(),
    dispatchedAt,
    settledAt,
    attempts,
    result,
    error,
  }, "Command");
}

export function validateCommandRecord(c = {}) {
  const errors = [];
  pushId(errors, c.id, "cmd", "id");
  pushEnum(errors, c.kind, COMMAND_KINDS, "kind");
  if (!isNonEmptyString(c.source)) errors.push("source is required");
  pushObject(errors, c.target, "target");
  pushObject(errors, c.payload, "payload");
  pushEnum(errors, c.state, COMMAND_STATES, "state");
  if (c.supersedes !== null && c.supersedes !== undefined) pushId(errors, c.supersedes, "cmd", "supersedes");
  pushIso(errors, c.createdAt, "createdAt");
  pushIso(errors, c.expiresAt, "expiresAt");
  pushIso(errors, c.dispatchedAt, "dispatchedAt", { nullable: true });
  pushIso(errors, c.settledAt, "settledAt", { nullable: true });
  if (!isNonNegativeInt(c.attempts)) errors.push("attempts must be a non-negative integer");
  return errors;
}

/* --------------------------------------------------------------- Artifact */

export function createArtifact({
  id = makeId("art"),
  sessionId = null,
  nodeId = null,
  sha256,
  size,
  mime = "application/octet-stream",
  name = "artifact",
  metadata = {},
  cachePath = null,
  createdAt = nowIso(),
} = {}) {
  return assert(validateArtifact, {
    id,
    sessionId,
    nodeId,
    sha256,
    size,
    mime,
    name,
    metadata,
    cachePath,
    createdAt,
  }, "Artifact");
}

export function validateArtifact(a = {}) {
  const errors = [];
  pushId(errors, a.id, "art", "id");
  if (a.sessionId !== null && a.sessionId !== undefined) pushId(errors, a.sessionId, "ses", "sessionId");
  if (a.nodeId !== null && a.nodeId !== undefined) pushId(errors, a.nodeId, "dev", "nodeId");
  if (typeof a.sha256 !== "string" || !SHA256_RE.test(a.sha256)) errors.push("sha256 must be 64 lowercase hex chars");
  if (!isNonNegativeInt(a.size)) errors.push("size must be a non-negative integer");
  if (!isNonEmptyString(a.mime)) errors.push("mime is required");
  if (!isNonEmptyString(a.name)) errors.push("name is required");
  pushObject(errors, a.metadata, "metadata");
  pushIso(errors, a.createdAt, "createdAt");
  return errors;
}

/* ---------------------------------------------------------------- PA 元数据 */

export function createPaProfile({
  id = makeId("pa"),
  name = "PA",
  persona = "",
  options = {},
  createdAt = nowIso(),
  updatedAt = nowIso(),
} = {}) {
  return assert(validatePaProfile, { id, name, persona, options, createdAt, updatedAt }, "PAProfile");
}

export function validatePaProfile(p = {}) {
  const errors = [];
  pushId(errors, p.id, "pa", "id");
  if (!isNonEmptyString(p.name)) errors.push("name is required");
  if (typeof p.persona !== "string") errors.push("persona must be string");
  pushObject(errors, p.options, "options");
  pushIso(errors, p.createdAt, "createdAt");
  pushIso(errors, p.updatedAt, "updatedAt");
  return errors;
}

export function createPaMessage({
  id = makeId("pm"),
  profileId,
  seq = 1,
  sourceNodeId = null,
  replyTo = null,
  text = "",
  createdAt = nowIso(),
} = {}) {
  return assert(validatePaMessage, { id, profileId, seq, sourceNodeId, replyTo, text, createdAt }, "PAMessage");
}

export function validatePaMessage(m = {}) {
  const errors = [];
  pushId(errors, m.id, "pm", "id");
  pushId(errors, m.profileId, "pa", "profileId");
  if (!Number.isInteger(m.seq) || m.seq < 1) errors.push("seq must be a positive integer");
  if (m.sourceNodeId !== null && m.sourceNodeId !== undefined) pushId(errors, m.sourceNodeId, "dev", "sourceNodeId");
  if (m.replyTo !== null && m.replyTo !== undefined) pushId(errors, m.replyTo, "pm", "replyTo");
  if (typeof m.text !== "string") errors.push("text must be string");
  pushIso(errors, m.createdAt, "createdAt");
  return errors;
}

/* ------------------------------------------------------------- SyncCursor */

export function createSyncCursor({
  id = makeId("cur"),
  peerId,
  cursor = 0,
  snapshotVersion = 1,
  pending = [],
  updatedAt = nowIso(),
} = {}) {
  return assert(validateSyncCursor, { id, peerId, cursor, snapshotVersion, pending, updatedAt }, "SyncCursor");
}

export function validateSyncCursor(c = {}) {
  const errors = [];
  pushId(errors, c.id, "cur", "id");
  if (!isNonEmptyString(c.peerId)) errors.push("peerId is required");
  if (!isNonNegativeInt(c.cursor)) errors.push("cursor must be a non-negative integer");
  if (!isNonNegativeInt(c.snapshotVersion)) errors.push("snapshotVersion must be a non-negative integer");
  if (!Array.isArray(c.pending)) errors.push("pending must be an array");
  pushIso(errors, c.updatedAt, "updatedAt");
  return errors;
}
