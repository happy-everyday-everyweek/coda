/**
 * TUI / Web UI 共用的核心 RPC 客户端（contracts/rpc.mjs v1）。
 *
 * 依赖边界（CONTRACT.md §8）：
 * - 只通过 `core.handleRpc(msg)` 与 `core.subscribe(fn)`（或 stdio 子进程）通信；
 * - 不读核心数据库、不 import core 内部模块、不按 Agent 名称分支；
 * - 握手失败、版本不匹配、超时、断线与 seq 缺口都在这里显式暴露。
 *
 * 连接入口：
 *   createInProcessTransport(core)      // 测试 / 嵌入式客户端
 *   createStdioTransport("node core/index.mjs") // --core stdio:<cmd>
 */

import { spawn } from "node:child_process";
import readline from "node:readline";
import { randomUUID } from "node:crypto";

import {
  METHODS,
  MIN_CLIENT_PROTOCOL,
  PROTOCOL_VERSION,
  RPC_ERRORS,
  rpcRequest,
  rpcError,
  eventNotification,
} from "../../contracts/rpc.mjs";
import { makeId } from "../../contracts/ids.mjs";
import {
  createProjectionState,
  resetFromSnapshot,
  applyEvent,
  canonicalProjection,
} from "./projection.mjs";

export class RpcError extends Error {
  constructor(code, message, data) {
    super(message ?? `rpc error ${code}`);
    this.name = "RpcError";
    this.code = code;
    if (data !== undefined) this.data = data;
  }
}

function callUnsubscribe(value) {
  if (typeof value === "function") value();
  else if (value && typeof value.unsubscribe === "function") value.unsubscribe();
}

/** 把核心对象包装成 transport；close() 只退订，不关闭核心（核心归调用方所有）。 */
export function createInProcessTransport(core, { onDiagnostic = null } = {}) {
  if (!core || typeof core.handleRpc !== "function") {
    throw new TypeError("createInProcessTransport: core.handleRpc is required");
  }
  if (typeof core.subscribe !== "function") {
    throw new TypeError("createInProcessTransport: core.subscribe is required");
  }

  const listeners = new Set();
  const closeHooks = new Set();
  let closed = false;

  const deliver = (message) => {
    if (closed || !message || typeof message !== "object") return;
    for (const fn of listeners) {
      try {
        fn(message);
      } catch (err) {
        onDiagnostic?.({ direction: "client-listener", error: String(err?.message ?? err) });
      }
    }
  };

  const offCore = core.subscribe((item) => {
    if (item && item.jsonrpc === "2.0") deliver(item);
    else deliver(eventNotification(item));
  });

  return {
    kind: "in-process",
    send(message) {
      if (closed) throw new Error("transport closed");
      Promise.resolve()
        .then(() => core.handleRpc(message))
        .then((response) => {
          if (response) deliver(response);
        })
        .catch((err) => {
          deliver(rpcError(message.id, RPC_ERRORS.INTERNAL, err?.message ?? String(err)));
        });
    },
    onMessage(fn) {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    onClose(fn) {
      closeHooks.add(fn);
      return () => closeHooks.delete(fn);
    },
    close() {
      if (closed) return;
      closed = true;
      callUnsubscribe(offCore);
      for (const fn of closeHooks) {
        try {
          fn();
        } catch {
          /* ignore close hook errors */
        }
      }
      closeHooks.clear();
      listeners.clear();
    },
  };
}

/**
 * `--core stdio:<cmd>`：按行 JSON 的子进程 transport。
 * 核心 stderr 原样转发，不参与协议。
 */
export function createStdioTransport(commandLine, { cwd, env, spawnFn = spawn, onStderr = null, onDiagnostic = null } = {}) {
  if (typeof commandLine !== "string" || commandLine.trim() === "") {
    throw new TypeError("createStdioTransport: command line is required");
  }

  const child = spawnFn(commandLine, {
    shell: true,
    cwd,
    env: env ?? process.env,
    stdio: ["pipe", "pipe", "pipe"],
  });

  const listeners = new Set();
  const closeHooks = new Set();
  let closed = false;

  const deliver = (message) => {
    if (closed) return;
    for (const fn of listeners) fn(message);
  };

  const notifyClose = () => {
    if (closed) return;
    closed = true;
    for (const fn of closeHooks) {
      try {
        fn();
      } catch {
        /* ignore */
      }
    }
    closeHooks.clear();
    listeners.clear();
  };

  const rl = readline.createInterface({ input: child.stdout, crlfDelay: Infinity });
  rl.on("line", (line) => {
    const text = line.trim();
    if (!text) return;
    try {
      deliver(JSON.parse(text));
    } catch (err) {
      onDiagnostic?.({ direction: "parse", line: text.slice(0, 500), error: String(err?.message ?? err) });
    }
  });

  child.stderr?.setEncoding?.("utf8");
  child.stderr?.on?.("data", (chunk) => onStderr?.(String(chunk)));
  child.on?.("error", (err) => {
    onDiagnostic?.({ direction: "spawn", error: String(err?.message ?? err) });
    notifyClose();
  });
  child.on?.("exit", (code, signal) => {
    onDiagnostic?.({ direction: "exit", code, signal });
    notifyClose();
  });

  return {
    kind: "stdio",
    child,
    send(message) {
      if (closed) throw new Error("transport closed");
      child.stdin.write(`${JSON.stringify(message)}\n`);
    },
    onMessage(fn) {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    onClose(fn) {
      closeHooks.add(fn);
      return () => closeHooks.delete(fn);
    },
    close() {
      if (closed) return;
      closed = true;
      try {
        child.stdin.end();
      } catch {
        /* ignore */
      }
      try {
        child.kill();
      } catch {
        /* ignore */
      }
      try {
        rl.close();
      } catch {
        /* ignore */
      }
      for (const fn of closeHooks) {
        try {
          fn();
        } catch {
          /* ignore */
        }
      }
      closeHooks.clear();
      listeners.clear();
    },
  };
}

export class RpcClient {
  constructor({
    transport,
    clientType = "client",
    clientVersion = "0.1.0",
    protocolVersion = PROTOCOL_VERSION,
    clientInfo = {},
    timeoutMs = 15_000,
    autoResume = true,
  } = {}) {
    if (!transport || typeof transport.send !== "function" || typeof transport.onMessage !== "function") {
      throw new TypeError("RpcClient: transport.send/onMessage are required");
    }
    this.transport = transport;
    this.clientType = clientType;
    this.clientVersion = clientVersion;
    this.protocolVersion = protocolVersion;
    this.clientInfo = clientInfo;
    this.timeoutMs = timeoutMs;
    this.autoResume = autoResume;

    this.projection = createProjectionState();
    this.connected = false;
    this.subscribed = false;
    this.welcome = null;

    this._pending = new Map();
    this._eventListeners = new Set();
    this._closed = false;
    this._recovering = false;

    this._offMessage = transport.onMessage((message) => this._handle(message));
    this._offClose =
      typeof transport.onClose === "function"
        ? transport.onClose(() => this._failPending(new RpcError(RPC_ERRORS.INTERNAL, "transport closed")))
        : null;
  }

  _setConnection(status, error = null) {
    this.projection.connection = {
      status,
      error: error ? { code: error.code ?? RPC_ERRORS.INTERNAL, message: error.message ?? String(error) } : null,
      seq: this.projection.seq,
    };
  }

  onEvent(fn) {
    this._eventListeners.add(fn);
    return () => this._eventListeners.delete(fn);
  }

  _failPending(error) {
    for (const [, pending] of this._pending) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this._pending.clear();
  }

  _handle(message) {
    if (!message || typeof message !== "object") return;

    if (message.id !== undefined && this._pending.has(message.id)) {
      const pending = this._pending.get(message.id);
      this._pending.delete(message.id);
      clearTimeout(pending.timer);
      if (message.error) {
        pending.reject(
          new RpcError(message.error.code, message.error.message ?? "rpc error", message.error.data),
        );
      } else {
        pending.resolve(message.result);
      }
      return;
    }

    if (message.method === "core.event") {
      const event = message.params?.event ?? message.params;
      if (!event || typeof event !== "object") return;
      const fromSeq = this.projection.seq;
      // 缺口：不要先应用越界事件（delta 会被重复追加），先 resume 再按序应用。
      if (event.seq > fromSeq + 1 && this.autoResume) {
        void this._recoverGap(fromSeq);
        return;
      }
      const { gap } = applyEvent(this.projection, event);
      for (const fn of this._eventListeners) {
        try {
          fn(event);
        } catch {
          /* listener errors must not break the connection */
        }
      }
      if (gap && this.autoResume) void this._recoverGap(fromSeq);
    }
  }

  async _recoverGap(fromSeq) {
    if (this._recovering || this._closed) return;
    this._recovering = true;
    this._setConnection("reconnecting");
    try {
      await this.resume({ fromSeq });
      this._setConnection("ready");
    } catch (err) {
      this._setConnection("error", err);
    } finally {
      this._recovering = false;
    }
  }

  request(method, params = {}, { timeoutMs = this.timeoutMs } = {}) {
    if (this._closed) {
      return Promise.reject(new RpcError(RPC_ERRORS.INTERNAL, "client closed"));
    }
    const id = randomUUID();
    const message = rpcRequest(method, params, id);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this._pending.delete(id);
        reject(new RpcError(RPC_ERRORS.INTERNAL, `RPC timeout: ${method}`));
      }, timeoutMs);
      timer.unref?.();
      this._pending.set(id, { resolve, reject, timer, method });
      try {
        this.transport.send(message);
      } catch (err) {
        clearTimeout(timer);
        this._pending.delete(id);
        reject(new RpcError(RPC_ERRORS.INTERNAL, err?.message ?? String(err)));
      }
    });
  }

  /** hello 协商；错误保留 code（VERSION_MISMATCH 等）。 */
  async connect() {
    if (this.connected) return this.welcome;
    this._setConnection("connecting");
    try {
      const result = await this.request(METHODS.hello, {
        protocolVersion: this.protocolVersion,
        clientType: this.clientType,
        clientVersion: this.clientVersion,
        ...this.clientInfo,
      });
      if (!result || typeof result !== "object" || !Number.isInteger(result.protocolVersion)) {
        throw new RpcError(RPC_ERRORS.INVALID_REQUEST, "core hello returned an invalid welcome");
      }
      if (result.protocolVersion < MIN_CLIENT_PROTOCOL || result.protocolVersion > PROTOCOL_VERSION) {
        throw new RpcError(
          RPC_ERRORS.VERSION_MISMATCH,
          `core protocol ${result.protocolVersion} is not supported by this client`,
        );
      }
      this.welcome = result;
      this.connected = true;
      this.projection.protocol = {
        version: result.protocolVersion,
        coreVersion: result.coreVersion ?? null,
        negotiated: true,
      };
      this._setConnection("ready");
      return result;
    } catch (err) {
      this._setConnection("error", err);
      throw err;
    }
  }

  _loadResult(result) {
    if (!result || typeof result !== "object") {
      throw new RpcError(RPC_ERRORS.INVALID_REQUEST, "core returned an invalid subscription result");
    }
    const seq = Number.isInteger(result.seq) ? result.seq : null;
    const snapshot =
      result.snapshot ?? (result.state && typeof result.state === "object" ? result.state : null);

    if (snapshot && (Array.isArray(snapshot.sessions) || Number.isInteger(snapshot.seq))) {
      resetFromSnapshot(this.projection, seq ?? snapshot.seq ?? 0, snapshot);
      return { mode: "snapshot", seq: this.projection.seq, gap: result.gap === true };
    }
    if (Array.isArray(result.events)) {
      for (const event of result.events) applyEvent(this.projection, event);
      if (seq !== null) {
        this.projection.seq = Math.max(this.projection.seq, seq);
        this.projection.connection.seq = this.projection.seq;
      }
      return { mode: "events", seq: this.projection.seq, gap: false, events: result.events.length };
    }
    if (Array.isArray(result.sessions)) {
      resetFromSnapshot(this.projection, seq ?? 0, result);
      return { mode: "snapshot", seq: this.projection.seq, gap: result.gap === true };
    }
    if (seq !== null) {
      this.projection.seq = Math.max(this.projection.seq, seq);
      this.projection.connection.seq = this.projection.seq;
    }
    return { mode: "empty", seq: this.projection.seq, gap: result.gap === true };
  }

  async subscribe({ fromSeq = 0, sessionId = null } = {}) {
    const result = await this.request(METHODS.subscribe, {
      fromSeq,
      sessionId,
      clientType: this.clientType,
    });
    const loaded = this._loadResult(result);
    this.subscribed = true;
    return loaded;
  }

  /** 断点续传：连续时回放事件，缺口时核心返回 snapshot 重新对齐。 */
  async resume({ fromSeq = this.projection.seq, sessionId = null } = {}) {
    const result = await this.request(METHODS.resume, {
      fromSeq,
      sessionId,
      clientType: this.clientType,
    });
    return this._loadResult(result);
  }

  async snapshot() {
    const result = await this.request(METHODS.snapshot, {});
    return this._loadResult(result);
  }

  /** 首次连接：hello → subscribe(fromSeq)。 */
  async open({ fromSeq = 0 } = {}) {
    await this.connect();
    const loaded = await this.subscribe({ fromSeq });
    if (loaded.mode === "empty" && fromSeq > 0) {
      await this.resume({ fromSeq });
    }
    return loaded;
  }

  async command(kind, target = {}, payload = {}, options = {}) {
    const commandId = options.commandId ?? makeId("cmd");
    const result = await this.request(
      METHODS.command,
      {
        commandId,
        kind,
        target,
        payload,
        source: this.clientType,
        ...(Number.isFinite(options.ttlMs) ? { ttlMs: options.ttlMs } : {}),
        ...(options.supersedes ? { supersedes: options.supersedes } : {}),
      },
      { timeoutMs: options.timeoutMs ?? this.timeoutMs },
    );
    return { commandId, ...(result ?? {}) };
  }

  canonicalProjection() {
    return canonicalProjection(this.projection);
  }

  close() {
    if (this._closed) return;
    this._closed = true;
    this._failPending(new RpcError(RPC_ERRORS.INTERNAL, "client closed"));
    try {
      this._offMessage?.();
    } catch {
      /* ignore */
    }
    try {
      this._offClose?.();
    } catch {
      /* ignore */
    }
    try {
      this.transport.close();
    } catch {
      /* ignore */
    }
    this._eventListeners.clear();
  }
}
