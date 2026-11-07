/**
 * CORE-114：诊断（有界日志、脱敏、健康检查、导出）。
 *
 * 脱敏是出口约束：写入前和导出前都会过滤，避免 Token/密钥/Authorization 进入诊断包。
 * 日志有界：超过 maxEntries 丢弃最旧条目，绝不无界增长。
 */
import { makeId } from "../contracts/ids.mjs";
import { ValidationError } from "./errors.mjs";

const LEVELS = ["debug", "info", "warn", "error"];
const LEVEL_WEIGHT = { debug: 10, info: 20, warn: 30, error: 40 };

const SECRET_KEY_RE = /(token|secret|password|passwd|authorization|api[_-]?key|apikey|credential|cookie|private[_-]?key)/i;

const PATTERNS = [
  // Authorization: Bearer xxx / Basic xxx
  [/\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{6,}/gi, "$1 [REDACTED]"],
  // 常见厂商 key 前缀
  [/\b(sk|pk|rk|ghp|gho|ghs|ghu|glpat|xox[abprs]|AKIA)[-_][A-Za-z0-9_-]{6,}/g, "[REDACTED_KEY]"],
  // JWT
  [/\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}\b/g, "[REDACTED_JWT]"],
  // key=value / key: value 形式
  [/\b(api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|passwd|client[_-]?secret)\b(\s*[:=]\s*)("[^"]*"|'[^']*'|[^\s,;&]+)/gi, "$1$2[REDACTED]"],
  // URL 里的 user:pass@host
  [/\b([a-z][a-z0-9+.-]*:\/\/)[^/\s:@]+:[^/\s@]+@/gi, "$1[REDACTED]@"],
  // 邮箱（隐私）
  [/\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/g, "[REDACTED_EMAIL]"],
];

/** 文本脱敏（幂等，可安全重复调用）。 */
export function redact(input) {
  if (input === null || input === undefined) return input;
  let text = typeof input === "string" ? input : String(input);
  for (const [pattern, replacement] of PATTERNS) {
    text = text.replace(pattern, replacement);
  }
  return text;
}

/** 深度脱敏：对象按敏感键名整体替换，字符串走 redact。 */
export function redactValue(value, { depth = 0, key = null, maxDepth = 6 } = {}) {
  if (key !== null && SECRET_KEY_RE.test(key)) return "[REDACTED]";
  if (value === null || value === undefined) return value;
  if (typeof value === "string") return redact(value);
  if (typeof value === "number" || typeof value === "boolean") return value;
  if (typeof value === "bigint") return value.toString();
  if (typeof value === "function") return "[function]";
  if (depth >= maxDepth) return "[max-depth]";
  if (Array.isArray(value)) return value.slice(0, 200).map((item) => redactValue(item, { depth: depth + 1, maxDepth }));
  if (value instanceof Error) {
    return {
      name: value.name,
      message: redact(value.message),
      code: value.code ?? null,
      stack: value.stack ? redact(String(value.stack).split("\n").slice(0, 20).join("\n")) : null,
    };
  }
  if (typeof value === "object") {
    const out = {};
    let count = 0;
    for (const [k, v] of Object.entries(value)) {
      if (count >= 200) {
        out.__truncated__ = true;
        break;
      }
      out[k] = redactValue(v, { depth: depth + 1, key: k, maxDepth });
      count += 1;
    }
    return out;
  }
  return redact(String(value));
}

export function createDiagnostics({
  clock = () => new Date(),
  maxEntries = 200,
  maxErrors = 20,
  version = "0.1.0",
  startedAtMs = Date.now(),
} = {}) {
  if (!Number.isInteger(maxEntries) || maxEntries < 1) throw new ValidationError("maxEntries must be a positive integer");
  /** @type {Array<object>} */
  let entries = [];
  /** @type {Array<object>} */
  let errors = [];
  let lastError = null;
  let seq = 0;
  const checks = new Map();
  let checksResult = { ok: true, at: null, items: [] };

  const now = () => {
    const d = clock();
    return d instanceof Date ? d : new Date(d);
  };
  const nowIso = () => now().toISOString();

  function log(level, message, fields = {}) {
    const normalized = LEVELS.includes(level) ? level : "info";
    const entry = {
      id: makeId("evt"),
      seq: ++seq,
      ts: nowIso(),
      level: normalized,
      message: redact(typeof message === "string" ? message : String(message)),
      fields: redactValue(fields ?? {}),
    };
    entries.push(entry);
    if (entries.length > maxEntries) entries.splice(0, entries.length - maxEntries);
    if (normalized === "error") {
      errors.push({ ...entry, stack: fields?.stack ? redact(String(fields.stack)) : undefined });
      if (errors.length > maxErrors) errors.splice(0, errors.length - maxErrors);
    }
    return entry;
  }

  const logger = {
    debug: (message, fields) => log("debug", message, fields),
    info: (message, fields) => log("info", message, fields),
    warn: (message, fields) => log("warn", message, fields),
    error: (message, fields) => log("error", message, fields),
    log,
  };

  function recordError(err, fields = {}) {
    const payload = {
      name: err?.name ?? "Error",
      message: redact(err?.message ?? String(err)),
      code: err?.code ?? err?.rpcCode ?? null,
      stack: err?.stack ? redact(String(err.stack)) : null,
      ...(fields ?? {}),
    };
    lastError = { ...payload, at: nowIso() };
    return log("error", payload.message, payload);
  }

  function registerCheck(name, fn) {
    if (typeof name !== "string" || typeof fn !== "function") {
      throw new ValidationError("registerCheck(name, fn) requires a string and a function");
    }
    checks.set(name, fn);
    return () => checks.delete(name);
  }

  async function runChecks() {
    const items = [];
    for (const [name, fn] of checks) {
      const startedAt = Date.now();
      try {
        const result = await fn();
        items.push({
          name,
          ok: result === undefined ? true : !!(result === true || result?.ok !== false),
          detail: redactValue(result ?? true),
          durationMs: Date.now() - startedAt,
        });
      } catch (err) {
        items.push({ name, ok: false, detail: redact(err?.message ?? String(err)), durationMs: Date.now() - startedAt });
      }
    }
    checksResult = { ok: items.every((i) => i.ok), at: nowIso(), items };
    return checksResult;
  }

  function health() {
    return {
      ok: checksResult.ok && errors.length === 0,
      version,
      pid: process.pid,
      startedAtMs,
      uptimeMs: Math.max(0, Date.now() - startedAtMs),
      entries: entries.length,
      maxEntries,
      errors: errors.length,
      lastError,
      checks: checksResult,
      at: nowIso(),
    };
  }

  function exportBundle({ includeEntries = true, extra = {} } = {}) {
    return {
      version,
      generatedAt: nowIso(),
      health: health(),
      ...(includeEntries
        ? { entries: entries.map((e) => redactValue(e)), errors: errors.map((e) => redactValue(e)) }
        : {}),
      ...redactValue(extra),
    };
  }

  function exportJSON(options) {
    return JSON.stringify(exportBundle(options), null, 2);
  }

  function close() {
    entries = [];
    errors = [];
    checks.clear();
  }

  return {
    log,
    logger,
    recordError,
    registerCheck,
    runChecks,
    health,
    exportBundle,
    exportJSON,
    entries: ({ limit = 100, level = null } = {}) => {
      let items = [...entries];
      if (level) {
        const min = LEVEL_WEIGHT[level] ?? 0;
        items = items.filter((e) => (LEVEL_WEIGHT[e.level] ?? 0) >= min);
      }
      return items.slice(-limit);
    },
    errors: ({ limit = 20 } = {}) => errors.slice(-limit),
    get version() {
      return version;
    },
    close,
  };
}

export { LEVELS as DIAGNOSTIC_LEVELS };
