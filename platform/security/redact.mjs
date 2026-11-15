/**
 * 日志与诊断脱敏（SEC-001 / SEC-006）。
 *
 * 目标：默认脱敏而不是默认明文。任何将要写日志、发同步消息、导出诊断的字符串
 * 都应先经过 `redact`；对象先经过 `redactValue`。
 *
 * 设计取舍：
 * - 纯函数、零依赖、永不抛错；对循环引用、超大对象做深度/长度限制。
 * - 识别常见凭据格式（OpenAI/Anthropic/GitHub/Slack/AWS/Google/JWT/Bearer/
 *   URL 内嵌凭据/查询参数/私钥块）；未知格式凭据无法保证识别，因此 SEC-001 的
 *   主防线仍是「不把秘密放进这些通道」（见 security/secrets.mjs）。
 */

export const REDACTED = "[REDACTED]";

/**
 * 模式以 source/flags 保存，使用时重新编译：避免全局正则的 lastIndex 状态泄漏。
 * `kind` 用于生成可读的 `[REDACTED:<kind>]`，便于排障时知道被过滤了什么类型。
 */
const SECRET_PATTERNS = [
  {
    kind: "private-key",
    source: "-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----[\\s\\S]*?-----END (?:[A-Z0-9 ]+ )?PRIVATE KEY-----",
    flags: "g",
  },
  { kind: "anthropic-key", source: "\\bsk-ant-[A-Za-z0-9_-]{16,}\\b", flags: "g" },
  { kind: "openai-key", source: "\\bsk-(?!ant-)[A-Za-z0-9_-]{20,}\\b", flags: "g" },
  { kind: "github-token", source: "\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}\\b", flags: "g" },
  { kind: "github-pat", source: "\\bgithub_pat_[A-Za-z0-9_]{20,}\\b", flags: "g" },
  { kind: "slack-token", source: "\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b", flags: "g" },
  { kind: "aws-access-key", source: "\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b", flags: "g" },
  { kind: "google-api-key", source: "\\bAIza[0-9A-Za-z_-]{35}\\b", flags: "g" },
  {
    kind: "jwt",
    source: "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b",
    flags: "g",
  },
  { kind: "bearer", source: "\\bBearer\\s+[A-Za-z0-9._~+/-]{12,}=*", flags: "gi" },
  { kind: "basic-auth", source: "\\bBasic\\s+[A-Za-z0-9+/=]{12,}", flags: "gi" },
  {
    kind: "url-credential",
    source: "\\b([a-z][a-z0-9+.-]*://)([^/\\s:@]+):([^/\\s@]+)@",
    flags: "gi",
  },
  {
    kind: "query-secret",
    source: "([?&](?:token|access_token|refresh_token|id_token|api[_-]?key|apikey|key|secret|sig|signature|password|code)=)[^&\\s#]+",
    flags: "gi",
  },
  {
    kind: "pairing-code",
    source: "(?:(?:pairing|配对)[ _-]?(?:code|码)\\s*[:=：]?\\s*)[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}",
    flags: "gi",
  },
  {
    kind: "password-field",
    source: "(?:password|passwd|pwd|密码|口令)\\s*[:=：]\\s*\\S+",
    flags: "gi",
  },
];

const SENSITIVE_KEY = /(?:api[-_]?key|apikey|token|secret|access[-_]?token|refresh[-_]?token|id[-_]?token|auth[-_]?token|client[-_]?secret|private[-_]?key|privatekey|pairing[-_]?code|verification[-_]?code|credential|authorization|cookie|password|passwd|pwd|bearer|session[-_]?key)/i;

const URL_PATTERN = /[a-z][a-z0-9+.-]*:\/\/[^\s<>"'`]+/gi;
const URL_CREDENTIAL_PATTERN = /([a-z][a-z0-9+.-]*:\/\/)([^/\s:@]+):([^/\s@]+)@/gi;

/** 仅按完整键名判断会误伤 `author` 之类，因此先做整词匹配，再做包含匹配的收紧版。 */
export function isSensitiveKey(key) {
  if (typeof key !== "string" || key.length === 0) return false;
  if (key === "author" || key === "authors" || key === "codeOfConduct") return false;
  return SENSITIVE_KEY.test(key);
}

function eachPattern(predicate, text) {
  for (const pattern of SECRET_PATTERNS) {
    const re = new RegExp(pattern.source, pattern.flags);
    if (predicate(re, pattern)) return pattern.kind;
  }
  return null;
}

/** 返回字符串中出现的凭据类型列表（去重）；用于诊断扫描，不返回匹配内容。 */
export function findSecretKinds(text) {
  if (typeof text !== "string" || text.length === 0) return [];
  const kinds = new Set();
  for (const pattern of SECRET_PATTERNS) {
    const re = new RegExp(pattern.source, pattern.flags);
    if (re.test(text)) kinds.add(pattern.kind);
  }
  return [...kinds];
}

/** string 中是否包含可识别凭据。 */
export function containsSecret(text) {
  return findSecretKinds(text).length > 0;
}

/**
 * 对字符串脱敏。保留结构（换行/前后文），替换为 `[REDACTED:<kind>]`。
 * @param {unknown} input
 * @param {{replacement?: string}} [opts]
 */
export function redact(input, opts = {}) {
  if (input === null || input === undefined) return "";
  const text = typeof input === "string" ? input : String(input);
  if (text.length === 0) return text;
  const replacement = opts.replacement ?? REDACTED;
  // 先处理完整 URL：保留 scheme/host/path，去掉 userinfo 与敏感查询参数。
  let out = text.replace(URL_PATTERN, (match) => redactUrl(match));
  for (const pattern of SECRET_PATTERNS) {
    const re = new RegExp(pattern.source, pattern.flags);
    out = out.replace(re, (...args) => {
      const match = args[0];
      if (pattern.kind === "url-credential") {
        // 保留 scheme，避免把 URL 结构一起吃掉。
        return `${args[1]}${replacement}:credentials@`;
      }
      if (pattern.kind === "query-secret") {
        return `${args[1]}${replacement}:query`;
      }
      if (pattern.kind === "pairing-code") {
        return "[REDACTED:pairing-code]";
      }
      if (pattern.kind === "password-field") {
        return "[REDACTED:password]";
      }
      return `[REDACTED:${pattern.kind}]`;
    });
    if (out.includes(replacement) && pattern.kind === "private-key") {
      // 私钥块可能跨行；replace 已处理，无需额外动作。
    }
  }
  return out;
}

const DEFAULTS = {
  maxDepth: 6,
  maxString: 8192,
  maxArray: 200,
  maxKeys: 200,
  maxBytes: 256 * 1024,
};

/**
 * 递归脱敏对象/数组。永不抛错：
 * - 循环引用 → `[Circular]`
 * - 超长字符串截断并标注
 * - Buffer 只报长度，不输出内容
 * - 敏感键名直接替换（即使值看起来无害）
 */
export function redactValue(value, opts = {}) {
  const cfg = { ...DEFAULTS, ...opts };
  const seen = new WeakSet();
  const stats = { redactedFields: 0, truncated: 0, bytes: 0 };
  const walk = (v, depth, path) => {
    if (stats.bytes > cfg.maxBytes) {
      stats.truncated += 1;
      return "[TRUNCATED:diagnostics-budget]";
    }
    if (v === null) return null;
    const t = typeof v;
    if (t === "string") {
      const redacted = redact(v);
      if (redacted !== v) stats.redactedFields += 1;
      const limit = Math.min(cfg.maxString, Math.max(16, cfg.maxString));
      if (redacted.length > limit) {
        stats.truncated += 1;
        const slice = redacted.slice(0, limit);
        stats.bytes += slice.length;
        return `${slice}…[TRUNCATED ${redacted.length - limit} chars]`;
      }
      stats.bytes += redacted.length;
      return redacted;
    }
    if (t === "number" || t === "boolean" || t === "bigint") return v;
    if (t === "undefined") return null;
    if (t === "function" || t === "symbol") return `[${t}]`;
    if (v instanceof Date) return v.toISOString();
    if (Buffer.isBuffer?.(v)) return `[Buffer ${v.length} bytes]`;
    if (v instanceof Error) {
      return { name: v.name, code: v.code ?? null, message: redact(v.message) };
    }
    if (Array.isArray(v)) {
      if (depth >= cfg.maxDepth) return "[MaxDepth]";
      if (seen.has(v)) return "[Circular]";
      seen.add(v);
      const arr = v.slice(0, cfg.maxArray).map((item, i) => walk(item, depth + 1, `${path}[${i}]`));
      if (v.length > cfg.maxArray) {
        stats.truncated += 1;
        arr.push(`[TRUNCATED ${v.length - cfg.maxArray} items]`);
      }
      seen.delete(v);
      return arr;
    }
    if (t === "object") {
      if (depth >= cfg.maxDepth) return "[MaxDepth]";
      if (seen.has(v)) return "[Circular]";
      seen.add(v);
      const out = {};
      const keys = Object.keys(v);
      for (const key of keys.slice(0, cfg.maxKeys)) {
        if (isSensitiveKey(key)) {
          out[key] = `[REDACTED:${key}]`;
          stats.redactedFields += 1;
          continue;
        }
        out[key] = walk(v[key], depth + 1, `${path}.${key}`);
      }
      if (keys.length > cfg.maxKeys) {
        stats.truncated += 1;
        out.__truncatedKeys = keys.length - cfg.maxKeys;
      }
      seen.delete(v);
      return out;
    }
    return String(v);
  };
  const result = walk(value, 0, "$");
  return { value: result, stats };
}

/** 便捷入口：只返回脱敏后的值。 */
export function sanitize(value, opts = {}) {
  return redactValue(value, opts).value;
}

/** 脱敏 URL：保留 scheme/host/path，去掉用户名密码与敏感查询参数。 */
export function redactUrl(input) {
  if (typeof input !== "string") return redact(input);
  try {
    const url = new URL(input);
    if (url.username || url.password) {
      url.username = "REDACTED";
      url.password = "REDACTED";
    }
    for (const key of [...url.searchParams.keys()]) {
      if (/(token|key|secret|sig|code|password)/i.test(key)) {
        url.searchParams.set(key, "REDACTED");
      }
    }
    return url.toString();
  } catch {
    // 非法 URL：仍清掉 userinfo，其余交给上层模式；不要递归调用 redact()。
    return String(input).replace(URL_CREDENTIAL_PATTERN, `$1[REDACTED:credentials]@`);
  }
}
