/**
 * CORE-101：核心错误类型与 RPC 错误映射。
 *
 * 约定：
 * - 内部错误一律用 `CoreError`（带稳定的 `code` 字符串）表达，不要抛裸字符串。
 * - 只有 `toRpcError()` 会把内部错误翻译成契约 v1 的 JSON-RPC 错误码。
 * - 「结果未知」必须是独立错误码（UNKNOWN_RESULT），禁止降级成成功或一般失败。
 */
import { RPC_ERRORS } from "../contracts/rpc.mjs";

/** 内部错误码 → 契约错误码。 */
export const CORE_ERROR_CODES = {
  INVALID_ARGUMENT: RPC_ERRORS.INVALID_PARAMS,
  METHOD_NOT_FOUND: RPC_ERRORS.METHOD_NOT_FOUND,
  VERSION_MISMATCH: RPC_ERRORS.VERSION_MISMATCH,
  UNAUTHORIZED: RPC_ERRORS.UNAUTHORIZED,
  NOT_FOUND: RPC_ERRORS.NOT_FOUND,
  CONFLICT: RPC_ERRORS.CONFLICT,
  EXPIRED: RPC_ERRORS.EXPIRED,
  UNKNOWN_RESULT: RPC_ERRORS.UNKNOWN_RESULT,
  UNSUPPORTED_CAPABILITY: RPC_ERRORS.UNSUPPORTED_CAPABILITY,
  INTERNAL: RPC_ERRORS.INTERNAL,
  CLOSED: RPC_ERRORS.INTERNAL,
};

export class CoreError extends Error {
  /**
   * @param {string} code 见 CORE_ERROR_CODES
   * @param {string} message 面向用户的确定性信息
   * @param {object} [options] { data, cause, retryable }
   */
  constructor(code, message, { data, cause, retryable = false } = {}) {
    if (!Object.hasOwn(CORE_ERROR_CODES, code)) {
      throw new TypeError(`unknown core error code: ${code}`);
    }
    super(message, cause === undefined ? undefined : { cause });
    this.name = "CoreError";
    this.code = code;
    this.data = data;
    this.retryable = retryable;
  }

  get rpcCode() {
    return CORE_ERROR_CODES[this.code];
  }

  toRpcError() {
    return {
      code: this.rpcCode,
      message: this.message,
      ...(this.data === undefined ? {} : { data: { coreCode: this.code, ...this.data } }),
    };
  }

  toJSON() {
    return {
      code: this.code,
      message: this.message,
      retryable: this.retryable,
      ...(this.data === undefined ? {} : { data: this.data }),
    };
  }
}

export class ValidationError extends CoreError {
  constructor(message, data) {
    super("INVALID_ARGUMENT", message, { data });
    this.name = "ValidationError";
  }
}

export class NotFoundError extends CoreError {
  constructor(message, data) {
    super("NOT_FOUND", message, { data });
    this.name = "NotFoundError";
  }
}

export class ConflictError extends CoreError {
  constructor(message, data) {
    super("CONFLICT", message, { data });
    this.name = "ConflictError";
  }
}

export class ExpiredError extends CoreError {
  constructor(message, data) {
    super("EXPIRED", message, { data });
    this.name = "ExpiredError";
  }
}

export class UnknownResultError extends CoreError {
  constructor(message, data) {
    super("UNKNOWN_RESULT", message, { data });
    this.name = "UnknownResultError";
  }
}

export class UnsupportedCapabilityError extends CoreError {
  constructor(message, data) {
    super("UNSUPPORTED_CAPABILITY", message, { data });
    this.name = "UnsupportedCapabilityError";
  }
}

export class UnauthorizedError extends CoreError {
  constructor(message, data) {
    super("UNAUTHORIZED", message, { data });
    this.name = "UnauthorizedError";
  }
}

export class VersionMismatchError extends CoreError {
  constructor(message, data) {
    super("VERSION_MISMATCH", message, { data });
    this.name = "VersionMismatchError";
  }
}

export class ClosedError extends CoreError {
  constructor(message = "core is closed", data) {
    super("CLOSED", message, { data });
    this.name = "ClosedError";
  }
}

/** 把任意异常规整成 CoreError（保留原始 cause 与堆栈）。 */
export function asCoreError(err, fallbackMessage = "internal error") {
  if (err instanceof CoreError) return err;
  const message = err instanceof Error ? err.message : String(err);
  return new CoreError("INTERNAL", message || fallbackMessage, { cause: err });
}

/** 判断一个错误是否属于「结果未知」这一类。 */
export function isUnknownResult(err) {
  return err instanceof CoreError && err.code === "UNKNOWN_RESULT";
}
