/**
 * 平台安全存储与秘密生命周期（SEC-101 / SEC-001 / SEC-006）。
 *
 * 约束：
 * - 秘密文件 0600、所在目录 0700；读取时拒绝符号链接与过宽权限。
 * - 写入采用「同目录临时文件 + fsync + rename」原子替换，避免半截文件。
 * - 日志、诊断、同步都不得出现秘密值；对外只暴露元数据。
 * - 这里不是 OS Keychain 的替代品：真机上应优先使用系统安全存储，
 *   本模块是跨平台可用的参考实现与降级路径（见 SPEC 14.3）。
 */
import {
  chmodSync,
  closeSync,
  existsSync,
  fsyncSync,
  lstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { randomBytes, createHash } from "node:crypto";
import { dirname, isAbsolute, join, resolve } from "node:path";

import { redact, redactValue, findSecretKinds, REDACTED } from "./redact.mjs";

export const SECRET_FILE_MODE = 0o600;
export const SECRET_DIR_MODE = 0o700;
export const SECRET_STORE_SCHEMA_VERSION = 1;
const IS_POSIX = process.platform !== "win32";

export class SecretError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "SecretError";
    this.code = code;
    this.details = details;
  }
}

function chmodIfPossible(path, mode) {
  if (!IS_POSIX) return;
  try {
    chmodSync(path, mode);
  } catch (err) {
    throw new SecretError("CHMOD_FAILED", `cannot set mode ${mode.toString(8)} on ${path}: ${err.message}`);
  }
}

/** 创建（或修正）0700 目录。 */
export function ensureSecureDir(dir) {
  if (typeof dir !== "string" || dir.length === 0) {
    throw new SecretError("INVALID_DIR", "secret directory path required");
  }
  const resolved = resolve(dir);
  mkdirSync(resolved, { recursive: true, mode: SECRET_DIR_MODE });
  if (IS_POSIX) {
    const st = statSync(resolved);
    if ((st.mode & 0o777) !== SECRET_DIR_MODE) chmodSync(resolved, SECRET_DIR_MODE);
  }
  return resolved;
}

/** 拒绝符号链接：秘密路径必须指向真实普通文件。 */
function assertNotSymlink(path) {
  if (!existsSync(path)) return;
  const st = lstatSync(path);
  if (st.isSymbolicLink()) {
    throw new SecretError("SYMLINK_SECRET_PATH", `secret path must not be a symlink: ${path}`);
  }
  if (!st.isFile()) {
    throw new SecretError("NOT_A_SECRET_FILE", `secret path is not a regular file: ${path}`);
  }
}

export function modeOf(path) {
  try {
    return statSync(path).mode & 0o777;
  } catch {
    return null;
  }
}

/** 校验 0600（或更严格）；返回模式或抛错。 */
export function assertSecretPermissions(path, { allowInsecure = false, expectedMode = SECRET_FILE_MODE } = {}) {
  const mode = modeOf(path);
  if (mode === null) throw new SecretError("SECRET_NOT_FOUND", `secret file not found: ${path}`);
  if (!IS_POSIX || allowInsecure) return { path, mode };
  if ((mode & 0o077) !== 0) {
    throw new SecretError(
      "INSECURE_SECRET_PERMISSIONS",
      `secret file ${path} is mode ${mode.toString(8)}; expected ${expectedMode.toString(8)} (chmod ${expectedMode.toString(8)} '${path}')`,
      { mode, expectedMode },
    );
  }
  return { path, mode };
}

/**
 * 原子写秘密文件。父目录自动 0700，文件 0600。
 * @returns {{path:string, mode:number, bytes:number, sha256:string}}
 */
export function writeSecretFile(file, data, { mode = SECRET_FILE_MODE, encoding = "utf8" } = {}) {
  if (typeof file !== "string" || file.length === 0) {
    throw new SecretError("INVALID_PATH", "secret file path required");
  }
  if (!isAbsolute(file)) throw new SecretError("INVALID_PATH", "secret file path must be absolute");
  const parent = dirname(file);
  ensureSecureDir(parent);
  assertNotSymlink(file);
  const payload = typeof data === "string" || Buffer.isBuffer(data) ? data : String(data);
  const tmp = join(parent, `.${randomBytes(8).toString("hex")}.secret.tmp`);
  let fd = null;
  try {
    fd = openSync(tmp, "wx", mode);
    if (IS_POSIX) chmodSync(tmp, mode);
    writeFileSync(fd, payload, { encoding });
    fsyncSync(fd);
    closeSync(fd);
    fd = null;
    renameSync(tmp, file);
    chmodIfPossible(file, mode);
  } catch (err) {
    try {
      if (fd !== null) closeSync(fd);
    } catch {
      /* ignore */
    }
    try {
      if (existsSync(tmp)) unlinkSync(tmp);
    } catch {
      /* ignore */
    }
    if (err instanceof SecretError) throw err;
    throw new SecretError("SECRET_WRITE_FAILED", `cannot write secret file ${file}: ${err.message}`);
  }
  const bytes = Buffer.byteLength(payload, encoding);
  const sha256 = createHash("sha256").update(payload).digest("hex");
  return { path: file, mode: modeOf(file), bytes, sha256 };
}

/** 读取秘密文件；默认拒绝过宽权限与符号链接。 */
export function readSecretFile(file, { allowInsecure = false, encoding = "utf8", maxBytes = 1024 * 1024 } = {}) {
  const resolved = resolve(file);
  assertNotSymlink(resolved);
  assertSecretPermissions(resolved, { allowInsecure });
  const st = statSync(resolved);
  if (st.size > maxBytes) {
    throw new SecretError("SECRET_FILE_TOO_LARGE", `secret file too large: ${st.size} bytes`);
  }
  return readFileSync(resolved, { encoding });
}

/**
 * 契约入口（CONTRACT.md §8）：读取一份秘密映射。
 * - 传目录：读取 `<dir>/secrets.json`
 * - 传文件：读取该 JSON 文件
 * 支持两种格式：扁平 `{KEY: value}` 与 store 格式 `{schemaVersion, entries}`。
 */
export function loadSecrets(path, { allowInsecure = false } = {}) {
  const resolved = resolve(path);
  let file = resolved;
  if (existsSync(resolved) && statSync(resolved).isDirectory()) file = join(resolved, "secrets.json");
  if (!existsSync(file)) return Object.freeze({});
  const raw = readSecretFile(file, { allowInsecure });
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    throw new SecretError("SECRET_PARSE_FAILED", `invalid secrets JSON at ${file}: ${err.message}`);
  }
  if (parsed && typeof parsed === "object" && parsed.schemaVersion !== undefined) {
    if (parsed.schemaVersion !== SECRET_STORE_SCHEMA_VERSION) {
      throw new SecretError(
        "SECRET_STORE_SCHEMA_VERSION",
        `unsupported secrets schema ${parsed.schemaVersion}; expected ${SECRET_STORE_SCHEMA_VERSION}`,
      );
    }
    const entries = parsed.entries && typeof parsed.entries === "object" ? parsed.entries : {};
    const values = {};
    for (const [key, entry] of Object.entries(entries)) {
      values[key] = entry && typeof entry === "object" && "value" in entry ? entry.value : entry;
    }
    return Object.freeze(values);
  }
  return Object.freeze(parsed && typeof parsed === "object" ? parsed : {});
}

/** 只显示首 3 位和末 4 位；短秘密完全隐藏。 */
export function maskSecret(value) {
  if (typeof value !== "string" || value.length === 0) return REDACTED;
  if (value.length <= 8) return "•".repeat(Math.min(8, value.length));
  return `${value.slice(0, 3)}…${value.slice(-4)}`;
}

/** 扫描对象/字符串中可识别的凭据，返回值的位置与类型，绝不返回值本身。 */
export function scanSecrets(value, { maxDepth = 6 } = {}) {
  const findings = [];
  const seen = new WeakSet();
  const walk = (v, path, depth) => {
    if (depth > maxDepth) return;
    if (typeof v === "string") {
      for (const kind of findSecretKinds(v)) findings.push({ path, kind });
      return;
    }
    if (!v || typeof v !== "object") return;
    if (seen.has(v)) return;
    seen.add(v);
    if (Array.isArray(v)) {
      v.forEach((item, i) => walk(item, `${path}[${i}]`, depth + 1));
      return;
    }
    for (const [key, child] of Object.entries(v)) walk(child, path ? `${path}.${key}` : key, depth + 1);
  };
  walk(value, "$", 0);
  return findings;
}

/** 若对象里出现可识别凭据则抛错；用于防止秘密进入同步/日志。 */
export function assertNoSecrets(value, { where = "payload" } = {}) {
  const findings = scanSecrets(value);
  if (findings.length > 0) {
    throw new SecretError(
      "SECRET_LEAK",
      `refusing to export ${where}: ${findings.length} credential-like value(s) detected at ${findings
        .slice(0, 3)
        .map((f) => f.path)
        .join(", ")}`,
      { findings: findings.map((f) => ({ path: f.path, kind: f.kind })) },
    );
  }
  return true;
}

const KEY_PATTERN = /^[A-Za-z0-9_.:-]{1,128}$/;

/**
 * 带生命周期的秘密仓库。值为 JSON 可序列化数据；对外只暴露元数据。
 */
export function createSecretStore({ dir, filename = "secrets.json", clock = Date.now, now } = {}) {
  if (!dir) throw new SecretError("INVALID_DIR", "createSecretStore requires {dir}");
  const iso = () => (now ? now() : new Date(clock()).toISOString());
  const directory = ensureSecureDir(dir);
  const file = join(directory, filename);
  let state = { schemaVersion: SECRET_STORE_SCHEMA_VERSION, entries: {} };
  if (existsSync(file)) {
    const raw = readSecretFile(file);
    const parsed = JSON.parse(raw);
    if (parsed?.schemaVersion !== SECRET_STORE_SCHEMA_VERSION) {
      throw new SecretError(
        "SECRET_STORE_SCHEMA_VERSION",
        `unsupported secrets schema ${parsed?.schemaVersion}`,
      );
    }
    state = { schemaVersion: SECRET_STORE_SCHEMA_VERSION, entries: parsed.entries ?? {} };
  }
  let closed = false;

  const save = () => {
    writeSecretFile(file, JSON.stringify(state, null, 2));
  };
  const assertOpen = () => {
    if (closed) throw new SecretError("STORE_CLOSED", "secret store is closed");
  };
  const isExpired = (entry, at = clock()) =>
    !!entry?.expiresAt && Date.parse(entry.expiresAt) <= at;

  save();

  return {
    get path() {
      return file;
    },
    set(key, value, { expiresAt = null, tags = [] } = {}) {
      assertOpen();
      if (!KEY_PATTERN.test(key)) throw new SecretError("INVALID_SECRET_KEY", `invalid secret key: ${key}`);
      const at = iso();
      const existing = state.entries[key];
      const entry = {
        value: value === undefined ? null : structuredClone(value),
        createdAt: existing?.createdAt ?? at,
        updatedAt: at,
        rotatedAt: existing ? at : null,
        expiresAt,
        tags: Array.isArray(tags) ? [...tags] : [],
      };
      if (expiresAt !== null && Number.isNaN(Date.parse(expiresAt))) {
        throw new SecretError("INVALID_EXPIRY", `invalid expiresAt: ${expiresAt}`);
      }
      state.entries[key] = entry;
      save();
      return { key, updatedAt: entry.updatedAt, expiresAt, tags: entry.tags };
    },
    get(key, { at = clock() } = {}) {
      assertOpen();
      const entry = state.entries[key];
      if (!entry) return null;
      if (isExpired(entry, at)) {
        delete state.entries[key];
        save();
        return null;
      }
      return entry.value === null || entry.value === undefined ? entry.value : structuredClone(entry.value);
    },
    has(key, opts) {
      return this.get(key, opts) !== null;
    },
    delete(key) {
      assertOpen();
      if (!state.entries[key]) return false;
      delete state.entries[key];
      save();
      return true;
    },
    rotate(key, value, opts = {}) {
      const result = this.set(key, value, opts);
      const current = state.entries[key];
      return { ...result, rotatedAt: current?.rotatedAt ?? null };
    },
    keys() {
      return Object.keys(state.entries);
    },
    list() {
      return Object.entries(state.entries).map(([key, entry]) => ({
        key,
        createdAt: entry.createdAt,
        updatedAt: entry.updatedAt,
        expiresAt: entry.expiresAt ?? null,
        tags: [...(entry.tags ?? [])],
      }));
    },
    gc({ at = clock() } = {}) {
      assertOpen();
      const removed = [];
      for (const [key, entry] of Object.entries(state.entries)) {
        if (isExpired(entry, at)) {
          delete state.entries[key];
          removed.push(key);
        }
      }
      if (removed.length) save();
      return removed;
    },
    /** 诊断导出只给元数据，不含任何值。 */
    diagnostics() {
      return {
        path: file,
        mode: modeOf(file),
        count: Object.keys(state.entries).length,
        entries: this.list(),
      };
    },
    assertSecure() {
      return assertSecretPermissions(file);
    },
    destroy() {
      state = { schemaVersion: SECRET_STORE_SCHEMA_VERSION, entries: {} };
      try {
        if (existsSync(file)) unlinkSync(file);
      } finally {
        closed = true;
      }
      return true;
    },
    close() {
      closed = true;
    },
  };
}

/**
 * 诊断导出（SEC-006）：
 * - 必须显式确认（用户确认后才允许导出）。
 * - 大工具载荷截断但保留关联 ID；秘密值被替换。
 */
export function exportDiagnostics({
  entries = [],
  confirmed = false,
  generatedAt,
  maxStringBytes = 2048,
  maxBytes = 256 * 1024,
  includeCorrelationIds = true,
} = {}) {
  if (confirmed !== true) {
    throw new SecretError(
      "EXPORT_NOT_CONFIRMED",
      "diagnostics export requires explicit user confirmation (pass {confirmed:true})",
    );
  }
  if (!Array.isArray(entries)) throw new SecretError("INVALID_ENTRIES", "entries must be an array");
  const at = generatedAt ?? new Date().toISOString();
  const filtered = [];
  const correlationIds = [];
  let truncated = 0;
  let redactedFields = 0;
  let used = 0;
  for (const raw of entries) {
    const entry = raw && typeof raw === "object" ? raw : { data: raw };
    if (entry.id) correlationIds.push(String(entry.id));
    const result = redactValue(entry, { maxString: maxStringBytes, maxBytes: maxBytes - used });
    redactedFields += result.stats.redactedFields;
    truncated += result.stats.truncated;
    used += Buffer.byteLength(JSON.stringify(result.value));
    if (used > maxBytes) {
      truncated += 1;
      filtered.push({
        id: entry.id ?? null,
        kind: entry.kind ?? "truncated",
        note: "[TRUNCATED:diagnostics budget reached]",
        correlationId: entry.id ?? null,
      });
      break;
    }
    filtered.push(result.value);
  }
  return {
    schemaVersion: 1,
    generatedAt: at,
    redaction: { applied: true, fieldsRedacted: redactedFields, marker: REDACTED },
    truncation: { entries: truncated, maxStringBytes, maxBytes },
    correlationIds: includeCorrelationIds ? correlationIds : [],
    entries: filtered,
  };
}

/** 把任意错误整理成可安全记录的形状（不含堆栈中的秘密）。 */
export function safeError(err) {
  if (!err) return null;
  return {
    name: err.name ?? "Error",
    code: err.code ?? null,
    message: redact(err.message ?? String(err)),
  };
}
