/** JSON-RPC 2.0 风格信封与错误码（契约 v1）。 */
import { nowIso } from "./ids.mjs";

export const PROTOCOL_VERSION = 1;
export const MIN_CLIENT_PROTOCOL = 1;
export const CORE_VERSION = "0.1.0";

export const RPC_ERRORS = {
  PARSE_ERROR: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL: -32603,
  VERSION_MISMATCH: -32001,
  UNAUTHORIZED: -32002,
  NOT_FOUND: -32003,
  CONFLICT: -32004,
  EXPIRED: -32005,
  UNKNOWN_RESULT: -32006,
  UNSUPPORTED_CAPABILITY: -32007,
};

/** 核心方法名（版本化契约，客户端不得自造）。 */
export const METHODS = {
  hello: "core.hello",
  subscribe: "core.subscribe",
  resume: "core.resume",
  snapshot: "core.snapshot",
  command: "core.command",
  diagnostics: "core.diagnostics",
  capabilityMatrix: "core.capabilityMatrix",
  // provisional：终端回放与成果读取（CORE-111/112，已由 core/server.mjs 实现并有契约测试）
  terminalRead: "core.terminal.read",
  terminalList: "core.terminal.list",
  artifactStat: "core.artifact.stat",
  artifactReadChunk: "core.artifact.readChunk",
  artifactGet: "core.artifact.get",
};

export function rpcRequest(method, params = {}, id = crypto.randomUUID()) {
  return { jsonrpc: "2.0", id, method, params };
}

export function rpcResult(id, result) {
  return { jsonrpc: "2.0", id, result };
}

export function rpcError(id, code, message, data) {
  return { jsonrpc: "2.0", id, error: { code, message, ...(data === undefined ? {} : { data }) } };
}

export function rpcNotification(method, params = {}) {
  return { jsonrpc: "2.0", method, params };
}

/** 订阅推送：有序事件包装。 */
export function eventNotification(event) {
  return rpcNotification("core.event", { event, ts: nowIso() });
}

export function isRpcRequest(msg) {
  return (
    !!msg &&
    typeof msg === "object" &&
    msg.jsonrpc === "2.0" &&
    typeof msg.method === "string" &&
    msg.id !== undefined
  );
}

export function checkProtocolVersion(clientVersion) {
  if (!Number.isInteger(clientVersion) || clientVersion < MIN_CLIENT_PROTOCOL || clientVersion > PROTOCOL_VERSION) {
    return { ok: false, code: RPC_ERRORS.VERSION_MISMATCH, message: `unsupported protocol version ${clientVersion}` };
  }
  return { ok: true };
}

export function welcome(auth = {}) {
  return {
    protocolVersion: PROTOCOL_VERSION,
    coreVersion: CORE_VERSION,
    ...auth,
    ts: nowIso(),
  };
}
