/**
 * 适配器基类（AGT-101 / ADP-017）。
 *
 * 职责边界（platform/CONTRACT.md §1）：
 *   - 适配器只做「上游 Agent 协议 ↔ 统一事件 + 能力声明」，不保存会话权威状态。
 *   - 统一事件只能由 contracts/events.mjs 构造，禁止适配器自造 event 形状。
 *   - 能力声明是「实例 × 版本 × 执行环境」的运行期事实（SPEC 8.1）。
 *
 * 子类需要实现：probe()，以及真正支持的 createSession/send/steer/interrupt/resume/fork。
 * 未实现或上游不支持的入口一律抛 AdapterError(UNSUPPORTED_CAPABILITY)，
 * 不返回假成功（SPEC 8.2 “不得出现的行为”）。
 */
import { ProviderAdapter, probeResult } from "../contracts/adapter.mjs";
import { createEvent } from "../contracts/events.mjs";
import { CAPABILITY_KEYS, makeCapabilityDeclaration, supports, supportState } from "../contracts/capabilities.mjs";
import { makeId, nowIso } from "../contracts/ids.mjs";
import { RPC_ERRORS } from "../contracts/rpc.mjs";

export { ProviderAdapter, probeResult };

/** 稳定错误码：ADP-013 要求各类失败可区分。 */
export const ADAPTER_ERROR_CODES = Object.freeze([
  "NOT_INSTALLED",
  "VERSION_UNSUPPORTED",
  "NOT_AUTHENTICATED",
  "QUOTA_EXCEEDED",
  "MODEL_UNAVAILABLE",
  "PERMISSION_DENIED",
  "PROCESS_EXIT",
  "PROTOCOL_ERROR",
  "NETWORK_ERROR",
  "WORKSPACE_INVALID",
  "UNSUPPORTED_CAPABILITY",
  "SESSION_NOT_FOUND",
  "TURN_IN_PROGRESS",
  "RESUME_FAILED",
  "STOP_UNCONFIRMED",
  "NOT_PROBED",
  "NOT_IMPLEMENTED",
  "TIMEOUT",
  "BLOCKED",
  "DISPOSED",
  "UNKNOWN",
]);

/** 适配器错误码 → RPC 错误码，供 core 直接回给客户端。 */
const RPC_CODE_BY_ERROR = Object.freeze({
  NOT_INSTALLED: RPC_ERRORS.NOT_FOUND,
  VERSION_UNSUPPORTED: RPC_ERRORS.VERSION_MISMATCH,
  NOT_AUTHENTICATED: RPC_ERRORS.UNAUTHORIZED,
  PERMISSION_DENIED: RPC_ERRORS.UNAUTHORIZED,
  QUOTA_EXCEEDED: RPC_ERRORS.UNSUPPORTED_CAPABILITY,
  MODEL_UNAVAILABLE: RPC_ERRORS.NOT_FOUND,
  UNSUPPORTED_CAPABILITY: RPC_ERRORS.UNSUPPORTED_CAPABILITY,
  SESSION_NOT_FOUND: RPC_ERRORS.NOT_FOUND,
  TURN_IN_PROGRESS: RPC_ERRORS.CONFLICT,
  NOT_PROBED: RPC_ERRORS.CONFLICT,
  TIMEOUT: RPC_ERRORS.UNKNOWN_RESULT,
  DISPOSED: RPC_ERRORS.CONFLICT,
  BLOCKED: RPC_ERRORS.UNSUPPORTED_CAPABILITY,
});

export function rpcCodeFor(errorCode) {
  return RPC_CODE_BY_ERROR[errorCode] ?? RPC_ERRORS.INTERNAL;
}

export class AdapterError extends Error {
  constructor(code, message, { details = null, cause = null, retryable = false } = {}) {
    super(message);
    this.name = "AdapterError";
    this.code = ADAPTER_ERROR_CODES.includes(code) ? code : "UNKNOWN";
    this.details = details;
    this.retryable = !!retryable;
    if (cause) this.cause = cause;
  }

  rpcCode() {
    return rpcCodeFor(this.code);
  }

  /** 诊断/日志用；不含原始凭据（SPEC 14.3）。 */
  toJSON() {
    return {
      name: this.name,
      code: this.code,
      message: this.message,
      retryable: this.retryable,
      details: this.details ?? null,
    };
  }
}

/**
 * 把上游原始错误文本归一到稳定错误码（ADP-013）。
 * 只做分类，不修改原始错误上下文。
 */
export function classifyUpstreamError(text) {
  const s = String(text ?? "");
  if (/unauthor|not logged in|invalid api key|authentication|unauthenticated|\b401\b/i.test(s)) return "NOT_AUTHENTICATED";
  if (/quota|rate.?limit|too many requests|usage limit|insufficient|\b429\b/i.test(s)) return "QUOTA_EXCEEDED";
  if (/model.*(not found|unavailable|unknown)|no such model/i.test(s)) return "MODEL_UNAVAILABLE";
  if (/permission denied|EACCES|EPERM|not permitted/i.test(s)) return "PERMISSION_DENIED";
  if (/ENOENT|command not found|no such file or directory/i.test(s)) return "NOT_INSTALLED";
  if (/ECONNREFUSED|ECONNRESET|ETIMEDOUT|socket hang up|network|offline/i.test(s)) return "NETWORK_ERROR";
  if (/version.*(unsupported|incompatible|too old)|unsupported protocol/i.test(s)) return "VERSION_UNSUPPORTED";
  if (/timed? ?out|timeout/i.test(s)) return "TIMEOUT";
  return "UNKNOWN";
}

/** 能力值域：true/false/experimental/unsupported/unverified（SPEC 8 + 5.4）。 */
export const CAPABILITY_VALUES = Object.freeze([true, false, "experimental", "unsupported", "unverified"]);

/** 递归判断能力值是否合法；对象用于按子项细化（如 approvals: {command: true}）。 */
export function isValidCapabilityValue(value, depth = 0) {
  if (depth > 4) return false;
  if (CAPABILITY_VALUES.includes(value)) return true;
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return Object.values(value).every((v) => isValidCapabilityValue(v, depth + 1));
  }
  return false;
}

export class BaseAdapter extends ProviderAdapter {
  constructor({ instanceId, environment = "host", provider, version = null, clock = () => new Date() } = {}) {
    super({ instanceId, environment });
    if (!provider || typeof provider !== "string") throw new AdapterError("UNKNOWN", "provider is required");
    this.provider = provider;
    this.version = version ?? null;
    this.clock = clock;
    this._seq = 0;
    this._declared = null;
    this._sessions = new Map();
    this._disposed = false;
    this._closed = false;
    this._closedPromise = new Promise((resolve) => {
      this._resolveClosed = resolve;
    });
    /** 未知上游帧只进诊断（SPEC 9.10 / ADP-013），不直接进 UI。 */
    this.diagnostics = { unknownFrames: [] };
  }

  // ---------------------------------------------------------------- 生命周期

  _assertUsable() {
    if (this._disposed) throw new AdapterError("DISPOSED", "adapter already disposed");
  }

  /** 由子类在 probe() 成功后调用，返回带版本的运行期能力声明。 */
  declareCapabilities(capabilities) {
    const keys = capabilities && typeof capabilities === "object" ? capabilities : {};
    const missing = CAPABILITY_KEYS.filter((k) => !Object.hasOwn(keys, k));
    if (missing.length) {
      throw new AdapterError("PROTOCOL_ERROR", `capabilities missing keys: ${missing.join(", ")}`);
    }
    for (const [key, value] of Object.entries(keys)) {
      if (!isValidCapabilityValue(value)) {
        throw new AdapterError("PROTOCOL_ERROR", `capability ${key} has illegal value: ${JSON.stringify(value)}`);
      }
    }
    this._declared = makeCapabilityDeclaration({
      provider: this.provider,
      version: this.version ?? "0.0.0-unknown",
      environment: this.environment,
      capabilities: keys,
    });
    return this._declared;
  }

  /** @returns 运行期能力声明；必须先成功 probe()。 */
  capabilities() {
    if (!this._declared) throw new AdapterError("NOT_PROBED", "capabilities() requires a successful probe() first");
    return this._declared;
  }

  /** 能力不满足时抛 UNSUPPORTED_CAPABILITY，UI 据此显示不支持/降级。 */
  assertCapability(key, sub = null) {
    const decl = this.capabilities();
    if (supports(decl, key, sub)) return true;
    const state = supportState(decl, key, sub);
    throw new AdapterError(
      "UNSUPPORTED_CAPABILITY",
      `upstream does not support ${key}${sub ? `.${sub}` : ""} (state=${state})`,
      { details: { capability: key, sub, state } },
    );
  }

  // ---------------------------------------------------------------- 事件

  nextSeq() {
    this._seq += 1;
    return this._seq;
  }

  /** 统一事件构造：唯一入口，强制走 contracts/events.mjs 校验。 */
  emitEvent({ kind, sessionId = null, payload = {}, seq = null }) {
    this._assertUsable();
    const event = createEvent({ kind, sessionId, payload, seq: seq ?? this.nextSeq() });
    if (event.seq > this._seq) this._seq = event.seq;
    this.emit(event);
    return event;
  }

  /** 状态事件（state: idle/running/waiting/stopped/failed/unknown）。 */
  emitStatus(sessionId, state, extra = {}) {
    return this.emitEvent({ kind: "status", sessionId, payload: { state, ...extra } });
  }

  /** 把错误归一后作为 error 事件发出，返回 AdapterError 供调用方抛出。 */
  emitError(sessionId, error, extra = {}) {
    const err =
      error instanceof AdapterError
        ? error
        : new AdapterError(classifyUpstreamError(error?.message ?? error), String(error?.message ?? error), {
            cause: error,
          });
    this.emitEvent({
      kind: "error",
      sessionId,
      payload: {
        code: err.code,
        message: err.message,
        retryable: err.retryable,
        rpcCode: err.rpcCode(),
        details: err.details,
        ...extra,
      },
    });
    return err;
  }

  /** 订阅统一事件，返回取消订阅函数。 */
  onEvent(fn) {
    this._listeners.add(fn);
    return () => this._listeners.delete(fn);
  }

  /** 异步迭代事件流（推送式，无轮询）；dispose() 后结束。 */
  async *events() {
    const queue = [];
    const waiters = [];
    const listener = (event) => {
      const waiter = waiters.shift();
      if (waiter) waiter({ value: event, done: false });
      else queue.push(event);
    };
    this._listeners.add(listener);
    const closed = this._closedPromise.then(() => ({ value: undefined, done: true }));
    try {
      while (true) {
        if (queue.length) {
          yield queue.shift();
          continue;
        }
        if (this._closed) return;
        const result = await Promise.race([new Promise((resolve) => waiters.push(resolve)), closed]);
        if (!result || result.done) return;
        yield result.value;
      }
    } finally {
      this._listeners.delete(listener);
    }
  }

  /** 等待满足条件的第一个事件；超时抛 TIMEOUT（测试与回合结算辅助）。 */
  waitForEvent(predicate, timeoutMs = 5000) {
    return new Promise((resolve, reject) => {
      let off = null;
      const timer = setTimeout(() => {
        off?.();
        reject(new AdapterError("TIMEOUT", `waitForEvent timed out after ${timeoutMs}ms`));
      }, timeoutMs);
      timer.unref?.();
      off = this.onEvent((event) => {
        let matched = false;
        try {
          matched = predicate(event);
        } catch (err) {
          clearTimeout(timer);
          off?.();
          reject(err);
          return;
        }
        if (matched) {
          clearTimeout(timer);
          off?.();
          resolve(event);
        }
      });
    });
  }

  // ---------------------------------------------------------------- 会话登记

  registerSession({ sessionId, providerSessionId = null, workspace = null, model = null, options = {} }) {
    const id = sessionId ?? makeId("ses");
    const record = {
      sessionId: id,
      providerSessionId,
      workspace,
      model,
      options,
      state: "idle",
      createdAt: nowIso(),
    };
    this._sessions.set(id, record);
    return record;
  }

  getSession(sessionId) {
    const record = this._sessions.get(sessionId);
    if (!record) throw new AdapterError("SESSION_NOT_FOUND", `unknown session: ${sessionId}`);
    return record;
  }

  listSessions() {
    return [...this._sessions.values()];
  }

  // ---------------------------------------------------------------- 默认入口

  async probe() {
    throw new AdapterError("NOT_IMPLEMENTED", "probe() not implemented");
  }

  async createSession() {
    this._assertUsable();
    throw new AdapterError("NOT_IMPLEMENTED", "createSession() not implemented");
  }

  async send() {
    this._assertUsable();
    throw new AdapterError("NOT_IMPLEMENTED", "send() not implemented");
  }

  async steer() {
    this._assertUsable();
    throw new AdapterError("UNSUPPORTED_CAPABILITY", "steer() is not supported by this upstream");
  }

  async interrupt() {
    this._assertUsable();
    throw new AdapterError("UNSUPPORTED_CAPABILITY", "interrupt() is not supported by this upstream");
  }

  async resume() {
    this._assertUsable();
    throw new AdapterError("UNSUPPORTED_CAPABILITY", "resume() is not supported by this upstream");
  }

  async fork() {
    this._assertUsable();
    throw new AdapterError("UNSUPPORTED_CAPABILITY", "fork() is not supported by this upstream");
  }

  async dispose() {
    if (this._disposed) return;
    this._disposed = true;
    this._sessions.clear();
    this._listeners.clear();
    this._closed = true;
    this._resolveClosed?.();
    await this.onDispose();
  }

  /** 子类释放资源（进程/连接）的钩子。 */
  async onDispose() {}

  describe() {
    return {
      instanceId: this.instanceId,
      provider: this.provider,
      environment: this.environment,
      version: this.version,
      declared: this._declared,
    };
  }
}

/** 把 probe 结果补上统一的「无副作用」标记（AGT-105 / ADP-001）。 */
export function sideEffectFreeProbe({ installed, version = null, loggedIn = null, path = null, details = {} }) {
  return { ...probeResult({ installed, version, loggedIn, path, details }), sideEffectFree: true };
}
