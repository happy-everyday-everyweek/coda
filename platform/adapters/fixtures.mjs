/**
 * 真实协议 fixture 的录制、脱敏与回放（AGT-106 / QA-003 / SPEC 14.3）。
 *
 * 硬规则：
 *   - 录制器只接受「统一事件」，原始上游帧永远不进 fixture（CONTRACT §4）。
 *   - 落盘前必须脱敏：token / 绝对路径 / home / 用户名 / 手机号 / 邮箱。
 *   - source 必须标明是真实录制（recording）还是人工构造（handwritten）；
 *     人工构造的 fixture 不能当作“真实 Agent 已支持”的证据（TODO 5.4）。
 */
import { readFileSync } from "node:fs";

import { validateEvent } from "../contracts/events.mjs";
import { CAPABILITY_KEYS } from "../contracts/capabilities.mjs";
import { BaseAdapter, AdapterError, sideEffectFreeProbe } from "./base.mjs";

export const FIXTURE_VERSION = 1;
export const FIXTURE_SOURCES = ["recording", "handwritten"];

export class FixtureError extends Error {
  constructor(issues) {
    const list = Array.isArray(issues) ? issues : [issues];
    super(`invalid fixture: ${list.map((i) => `${i.path}: ${i.message}`).join("; ")}`);
    this.name = "FixtureError";
    this.code = "FIXTURE_INVALID";
    this.issues = list;
  }
}

// ------------------------------------------------------------------ 脱敏

const RULE_DEFS = [
  {
    id: "private-key",
    source: "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----",
    flags: "g",
    replacement: "[REDACTED_PRIVATE_KEY]",
  },
  { id: "bearer", source: "(Bearer\\s+)[A-Za-z0-9._~+/=-]{8,}", flags: "gi", replacement: "$1[REDACTED_TOKEN]" },
  {
    id: "jwt",
    source: "\\beyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\b",
    flags: "g",
    replacement: "[REDACTED_JWT]",
  },
  { id: "openai-key", source: "\\bsk-[A-Za-z0-9_-]{10,}\\b", flags: "g", replacement: "[REDACTED_TOKEN]" },
  { id: "github-token", source: "\\bgh[pousr]_[A-Za-z0-9]{16,}\\b", flags: "g", replacement: "[REDACTED_TOKEN]" },
  { id: "slack-token", source: "\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b", flags: "g", replacement: "[REDACTED_TOKEN]" },
  { id: "aws-key", source: "\\bAKIA[0-9A-Z]{16}\\b", flags: "g", replacement: "[REDACTED_TOKEN]" },
  {
    id: "env-assignment",
    source: "\\b([A-Z][A-Z0-9_]*(?:TOKEN|SECRET|PASSWORD|API_?KEY|CREDENTIAL)[A-Z0-9_]*)\\s*[=:]\\s*[^\\s\"']+",
    flags: "g",
    replacement: "$1=[REDACTED]",
  },
  { id: "email", source: "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", flags: "g", replacement: "[REDACTED_EMAIL]" },
  {
    id: "phone-cn",
    source: "(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)",
    flags: "g",
    replacement: "[REDACTED_PHONE]",
  },
  {
    id: "phone-intl",
    source: "\\+\\d{1,3}[ -]?\\d{2,4}[ -]?\\d{3,4}[ -]?\\d{3,4}",
    flags: "g",
    replacement: "[REDACTED_PHONE]",
  },
  {
    id: "windows-home",
    source: "([A-Za-z]:\\\\Users\\\\)[^\\\\\\s\"']+",
    flags: "g",
    replacement: "$1[REDACTED_USER]",
  },
  { id: "mac-home", source: "(/Users/)[^/\\s\"']+", flags: "g", replacement: "$1[REDACTED_USER]" },
  { id: "linux-home", source: "(/home/)[^/\\s\"']+", flags: "g", replacement: "$1[REDACTED_USER]" },
  {
    id: "root-home",
    source: "(^|[\\s\"'=(])/root(?=[/\\s\"')]|$)",
    flags: "g",
    replacement: "$1/home/[REDACTED_USER]",
  },
];

export const REDACTION_RULE_IDS = Object.freeze(RULE_DEFS.map((r) => r.id));

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * 脱敏一段文本。
 * @returns {{text: string, applied: string[]}}
 */
export function redactText(text, { knownNames = [], rules = null } = {}) {
  let out = String(text ?? "");
  const applied = [];
  const active = rules ? RULE_DEFS.filter((r) => rules.includes(r.id)) : RULE_DEFS;
  for (const rule of active) {
    const pattern = new RegExp(rule.source, rule.flags);
    if (!pattern.test(out)) continue;
    const replacement = new RegExp(rule.source, rule.flags);
    out = out.replace(replacement, rule.replacement);
    applied.push(rule.id);
  }
  for (const name of knownNames) {
    if (!name || name.length < 2) continue;
    const pattern = new RegExp(escapeRegExp(name), "g");
    if (!pattern.test(out)) continue;
    out = out.replace(new RegExp(escapeRegExp(name), "g"), "[REDACTED_USER]");
    if (!applied.includes("known-name")) applied.push("known-name");
  }
  return { text: out, applied };
}

/** 便捷版本：只返回脱敏后的文本。 */
export function redact(text, options = {}) {
  return redactText(text, options).text;
}

/** 递归脱敏（对象/数组/字符串）。 */
export function redactValue(value, options = {}) {
  if (typeof value === "string") return redactText(value, options).text;
  if (Array.isArray(value)) return value.map((v) => redactValue(v, options));
  if (value && typeof value === "object") {
    const out = {};
    for (const [key, v] of Object.entries(value)) out[key] = redactValue(v, options);
    return out;
  }
  return value;
}

/** 审计 fixture 是否仍含疑似秘密；用于录制后自检（已脱敏占位符不算泄漏）。 */
export function auditFixture(fixture) {
  const leaks = [];
  for (const [index, event] of (fixture?.events ?? []).entries()) {
    const text = JSON.stringify(event).replace(/\[REDACTED_[A-Z_]+\]/g, "");
    for (const rule of RULE_DEFS) {
      const pattern = new RegExp(rule.source, rule.flags);
      if (pattern.test(text)) leaks.push({ path: `events[${index}].payload`, rule: rule.id });
    }
  }
  return { ok: leaks.length === 0, leaks };
}

// ------------------------------------------------------------------ 录制

/**
 * 录制器：只存脱敏后的统一事件序列。
 * @returns {{record: Function, finish: Function, toJSON: Function, size: Function}}
 */
export function createRecorder({
  id,
  provider,
  protocol = null,
  source = "recording",
  notes = "",
  environment = null,
  redactOptions = {},
  now = () => new Date().toISOString(),
} = {}) {
  if (!id || !provider) throw new FixtureError([{ path: "$", message: "recorder requires id and provider" }]);
  if (!FIXTURE_SOURCES.includes(source)) {
    throw new FixtureError([{ path: "source", message: `must be one of ${FIXTURE_SOURCES.join(", ")}` }]);
  }
  const events = [];
  let finished = false;

  function record(event) {
    if (finished) throw new FixtureError([{ path: "events", message: "recorder already finished" }]);
    const errors = validateEvent(event);
    if (errors.length) {
      throw new FixtureError(errors.map((message) => ({ path: `events[${events.length}]`, message })));
    }
    const safe = redactValue(
      {
        kind: event.kind,
        sessionId: event.sessionId ?? null,
        payload: event.payload ?? {},
        seq: event.seq,
        ts: event.ts,
      },
      redactOptions,
    );
    events.push(safe);
    return safe;
  }

  function finish({ notes: extraNotes = null } = {}) {
    finished = true;
    return createFixture({
      id,
      provider,
      protocol,
      source,
      notes: extraNotes ?? notes,
      environment,
      recordedAt: now(),
      events,
    });
  }

  return {
    record,
    finish,
    toJSON: () => finish(),
    size: () => events.length,
  };
}

/** 构造并校验一个 fixture 对象。 */
export function createFixture({
  id,
  provider,
  protocol = null,
  source = "handwritten",
  notes = "",
  environment = null,
  recordedAt = new Date().toISOString(),
  events = [],
  redaction = null,
} = {}) {
  const fixture = {
    fixtureVersion: FIXTURE_VERSION,
    id,
    provider,
    protocol,
    source,
    sourceNote:
      source === "handwritten"
        ? "人工构造（非真实 Agent 录制）：用于验证适配器映射与回放，不作为“真实 Agent 已支持”的证据"
        : "真实协议录制（已脱敏）",
    environment,
    recordedAt,
    redaction: redaction ?? { applied: true, rules: [...REDACTION_RULE_IDS] },
    notes,
    events,
  };
  validateFixture(fixture);
  return fixture;
}

// ------------------------------------------------------------------ 校验

export function validateFixture(fixture) {
  const issues = [];
  if (!fixture || typeof fixture !== "object") throw new FixtureError([{ path: "$", message: "must be an object" }]);
  if (fixture.fixtureVersion !== FIXTURE_VERSION) {
    issues.push({ path: "fixtureVersion", message: `must be ${FIXTURE_VERSION}` });
  }
  for (const field of ["id", "provider", "source"]) {
    if (!fixture[field]) issues.push({ path: field, message: "required" });
  }
  if (!FIXTURE_SOURCES.includes(fixture.source)) {
    issues.push({ path: "source", message: `must be one of ${FIXTURE_SOURCES.join(", ")}` });
  }
  if (!Array.isArray(fixture.events) || fixture.events.length === 0) {
    issues.push({ path: "events", message: "must be a non-empty array" });
  }
  if (fixture.source === "recording" && fixture.redaction?.applied !== true) {
    issues.push({ path: "redaction.applied", message: "recording fixtures must be redacted" });
  }
  let lastSeq = 0;
  for (const [index, event] of (fixture.events ?? []).entries()) {
    const errors = validateEvent(event);
    for (const message of errors) issues.push({ path: `events[${index}]`, message });
    if (Number.isInteger(event?.seq)) {
      if (event.seq <= lastSeq) issues.push({ path: `events[${index}].seq`, message: "seq must strictly increase" });
      lastSeq = event.seq;
    }
  }
  if (issues.length) throw new FixtureError(issues);
  return fixture;
}

/** 解析 fixture（对象或 JSON 文本）。 */
export function parseFixture(input) {
  if (typeof input === "string") {
    let parsed;
    try {
      parsed = JSON.parse(input);
    } catch (err) {
      throw new FixtureError([{ path: "$", message: `invalid JSON: ${err.message}` }]);
    }
    return validateFixture(parsed);
  }
  return validateFixture(input);
}

/** 从文件读取 fixture。 */
export function loadFixture(filePath) {
  let text;
  try {
    text = readFileSync(filePath, "utf8");
  } catch (err) {
    throw new FixtureError([{ path: filePath, message: `cannot read fixture: ${err.message}` }]);
  }
  return parseFixture(text);
}

// ------------------------------------------------------------------ 回放

/**
 * 回放 fixture 为统一事件序列。
 * @returns {Array<object>} 逐条事件副本（seq 默认重新编号，保证严格递增）
 */
export function replayFixture(fixture, { sessionId = null, normalizeSeq = true } = {}) {
  validateFixture(fixture);
  let seq = 0;
  return fixture.events.map((event) => {
    seq += 1;
    return {
      kind: event.kind,
      sessionId: sessionId ?? event.sessionId ?? null,
      payload: structuredClone(event.payload ?? {}),
      seq: normalizeSeq ? seq : event.seq,
      ts: event.ts,
    };
  });
}

/** fixture 能证明的（映射层）能力；一律 experimental，不代表真实 Agent 已支持。 */
export function fixtureCapabilities(fixture) {
  const kinds = new Set((fixture?.events ?? []).map((e) => e.kind));
  const mark = (ok) => (ok ? "experimental" : false);
  const hasInteraction = kinds.has("interaction");
  const caps = {
    session: {
      multiThread: false,
      modelSwitch: false,
      permissionModeSwitch: false,
      resume: mark(kinds.has("session")),
      requestDurability: mark(hasInteraction),
    },
    thread: {
      empty: mark(true),
      snapshot: false,
      fork: false,
      fromTurn: false,
      nativeId: mark(kinds.has("session")),
    },
    turn: {
      startEndEvents: mark(kinds.has("turn")),
      interrupt: mark(kinds.has("turn")),
      nativeSteer: false,
      stopThenAppend: false,
      queue: false,
      terminalTrust: mark(kinds.has("turn")),
    },
    streaming: {
      text: mark(kinds.has("message")),
      reasoning: mark(kinds.has("reasoning")),
      toolOutput: mark(kinds.has("tool")),
      planText: mark(kinds.has("plan")),
      messageCompleted: mark(kinds.has("message")),
    },
    tools: mark(kinds.has("tool")),
    approvals: {
      command: mark(hasInteraction),
      fileRead: mark(hasInteraction),
      fileWrite: mark(hasInteraction),
      patch: false,
      subagent: false,
      nativeRequestId: mark(hasInteraction),
    },
    plan: mark(kinds.has("plan")),
    subagent: mark(kinds.has("subagent")),
    context: { system: false, summary: false, handoffFull: false, handoffIncremental: false },
    checkpoint: { filesystem: false, sessionRollback: false, consistent: false },
    identity: {
      thread: mark(true),
      turn: mark(kinds.has("turn")),
      item: false,
      request: mark(hasInteraction),
    },
    attachments: { input: mark(kinds.has("artifact")), output: mark(kinds.has("artifact")) },
  };
  // 契约要求覆盖全部键（CAPABILITY_KEYS），缺失即 bug。
  const missing = CAPABILITY_KEYS.filter((k) => !Object.hasOwn(caps, k));
  if (missing.length) throw new FixtureError([{ path: "capabilities", message: `missing keys: ${missing.join(", ")}` }]);
  return caps;
}

/** fixture 索引（QA-103 需要的可检索摘要）。 */
export function buildFixtureIndex(fixtures = []) {
  return fixtures.map((f) => ({
    id: f.id,
    provider: f.provider,
    protocol: f.protocol ?? null,
    source: f.source,
    eventCount: f.events.length,
    kinds: [...new Set(f.events.map((e) => e.kind))].sort(),
    provesRealSupport: f.source === "recording",
    notes: f.notes ?? "",
  }));
}

// ------------------------------------------------------------------ 回放适配器

/**
 * 回放适配器：把 fixture 当作一个只读的“上游”，用于 UI/核心联调与契约测试。
 * 它不伪造任何实时能力：send/steer/interrupt 全部返回 UNSUPPORTED_CAPABILITY。
 */
export class ReplayAdapter extends BaseAdapter {
  constructor({ fixture, instanceId = null, environment = "fixture", delayMs = 0 } = {}) {
    const parsed = parseFixture(fixture);
    super({ instanceId: instanceId ?? `replay-${parsed.id}`, environment, provider: parsed.provider });
    this.fixture = parsed;
    this.delayMs = delayMs;
  }

  async probe() {
    this.version = `fixture@${this.fixture.fixtureVersion}`;
    this.declareCapabilities(fixtureCapabilities(this.fixture));
    return sideEffectFreeProbe({
      installed: true,
      version: this.version,
      loggedIn: null,
      details: { fixtureId: this.fixture.id, source: this.fixture.source, protocol: this.fixture.protocol ?? null },
    });
  }

  async createSession({ sessionId, workspace = null, model = null, options = {} } = {}) {
    this._assertUsable();
    return this.registerSession({ sessionId, workspace, model, options });
  }

  /** 逐条回放；`sessionId` 可覆盖 fixture 内的会话 ID。 */
  async play({ sessionId = null, delayMs = this.delayMs } = {}) {
    this._assertUsable();
    const events = replayFixture(this.fixture, { sessionId, normalizeSeq: false });
    const emitted = [];
    for (const event of events) {
      const target = event.sessionId ?? null;
      emitted.push(this.emitEvent({ kind: event.kind, sessionId: target, payload: event.payload }));
      if (delayMs > 0) await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
    return emitted;
  }

  async send() {
    throw new AdapterError(
      "UNSUPPORTED_CAPABILITY",
      "replay adapter is read-only: fixture playback cannot send prompts to a live agent",
    );
  }
}
