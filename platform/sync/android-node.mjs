/**
 * Android 受认证宿主桥的桌面端客户端（AGT-130 / SYNC-109 第一版）。
 *
 * 传输：TCP + NDJSON + JSON-RPC 2.0 风格信封（见 docs/ANDROID-NODE-BRIDGE.md）。
 * 认证：connect() 发送 node.hello 令牌；未认证连接只能调用 hello。
 *
 * 边界：客户端不伪造节点能力；hello 返回什么就暴露什么。终端/附件暂未声明。
 */
import net from "node:net";
import { EventEmitter } from "node:events";

export const ANDROID_NODE_PROTOCOL_VERSION = 1;

// 单帧上限：超过即视为协议错误并断开，避免对端用一条超长行撑爆内存。
export const MAX_FRAME_BYTES = 1024 * 1024;
// 同时在途的请求上限：没有它，一个卡死的节点会把待办表堆到内存耗尽。
export const MAX_PENDING_REQUESTS = 256;

export const NODE_METHODS = Object.freeze({
  hello: "node.hello",
  snapshot: "node.snapshot",
  sessionNew: "node.session.new",
  sessionOpen: "node.session.open",
  turnSend: "node.turn.send",
  turnStop: "node.turn.stop",
  messages: "node.messages",
  interactionAnswer: "node.interaction.answer",
  ping: "node.ping",
});

export const NODE_ERRORS = Object.freeze({
  PARSE: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL: -32603,
  VERSION_MISMATCH: -32001,
  UNAUTHORIZED: -32002,
  NOT_FOUND: -32003,
});

export class AndroidNodeError extends Error {
  constructor(message, { code = NODE_ERRORS.INTERNAL, data = null } = {}) {
    super(message);
    this.name = "AndroidNodeError";
    this.code = code;
    this.data = data;
  }
}

/**
 * 请求超时：副作用可能已经发生在远端。
 *
 * 按 CONTRACT §3，这种情况必须落在「结果未知」，既不能重试当成功，也不能当失败，
 * 否则一次超时后的盲目重试会在手机上执行两遍同一句话。
 */
export class AndroidNodeTimeoutError extends AndroidNodeError {
  constructor(message, { code = NODE_ERRORS.INTERNAL, data = null, commandId = null } = {}) {
    super(message, { code, data });
    this.name = "AndroidNodeTimeoutError";
    this.outcomeUnknown = true;
    this.commandId = commandId;
  }
}

export class AndroidNodeClient extends EventEmitter {
  constructor(socket, { host, port, node = null, capabilities = {}, sessions = [] } = {}) {
    super();
    this.socket = socket;
    this.host = host;
    this.port = port;
    this.node = node;
    this.capabilities = capabilities;
    this.sessions = sessions;
    this._buffer = "";
    this._nextId = 1;
    this._pending = new Map();
    this._closed = false;
    // 节点事件序号：服务端每条连接单调递增，客户端据此丢弃重放与乱序的旧帧。
    this.lastSeq = 0;
    socket.setEncoding("utf8");
    socket.on("data", (chunk) => this._onData(chunk));
    socket.on("error", (error) => this._failAll(error));
    socket.on("close", () => {
      this._closed = true;
      const reason = this.closeReason ?? "socket closed";
      this._failAll(new AndroidNodeError(`android node disconnected: ${reason}`));
      this.emit("close", reason);
    });
  }

  static connect({ host = "127.0.0.1", port = 17654, token, timeoutMs = 8000, protocolVersion = ANDROID_NODE_PROTOCOL_VERSION, clientName = "coda-desktop" } = {}) {
    if (!token) throw new AndroidNodeError("token required", { code: NODE_ERRORS.UNAUTHORIZED });
    return new Promise((resolve, reject) => {
      const socket = net.connect({ host, port });
      const timer = setTimeout(() => {
        socket.destroy();
        reject(new AndroidNodeError(`connect timeout: ${host}:${port}`));
      }, timeoutMs);
      socket.once("error", (error) => {
        clearTimeout(timer);
        reject(new AndroidNodeError(error.message));
      });
      socket.once("connect", async () => {
        clearTimeout(timer);
        const client = new AndroidNodeClient(socket, { host, port });
        try {
          const hello = await client.request(NODE_METHODS.hello, { protocolVersion, token, clientName });
          if (hello?.protocolVersion !== protocolVersion) {
            throw new AndroidNodeError(`unsupported node protocol ${hello?.protocolVersion}`, {
              code: NODE_ERRORS.VERSION_MISMATCH,
              data: { expected: protocolVersion, actual: hello?.protocolVersion ?? null },
            });
          }
          client.node = hello.node ?? null;
          client.capabilities = hello.capabilities ?? {};
          client.sessions = hello.sessions ?? [];
          resolve(client);
        } catch (error) {
          client.close();
          reject(error);
        }
      });
    });
  }

  /**
   * 发一条 RPC。
   *
   * commandId 是幂等的唯一依据：同一条命令重投必须带同一个 id，服务端返回原收据，
   * 不会把同一句话再执行一遍（CONTRACT §3、SPEC 11.5）。不传则由调用方按需生成。
   */
  request(method, params = {}, { timeoutMs = 15000, commandId = null } = {}) {
    if (this._closed) return Promise.reject(new AndroidNodeError("android node closed"));
    if (this._pending.size >= MAX_PENDING_REQUESTS) {
      return Promise.reject(new AndroidNodeError("too many pending requests", { code: NODE_ERRORS.INTERNAL }));
    }
    const id = this._nextId++;
    const body = commandId ? { ...params, commandId } : params;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this._pending.delete(id);
        reject(new AndroidNodeTimeoutError(`rpc timeout: ${method}`, { commandId }));
      }, timeoutMs);
      this._pending.set(id, { resolve, reject, timer, method });
      this.socket.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params: body })}\n`);
    });
  }

  // 便捷方法：与 Android 端 NodeBridge 的方法一一对应。
  snapshot() {
    return this.request(NODE_METHODS.snapshot);
  }

  newSession(workspace = "", { commandId = null } = {}) {
    return this.request(NODE_METHODS.sessionNew, workspace ? { workspace } : {}, { commandId });
  }

  openSession(sessionId, { commandId = null } = {}) {
    return this.request(NODE_METHODS.sessionOpen, { sessionId }, { commandId });
  }

  /** 拉某个会话的正文。长会话耗时更长，单独放宽超时；返回消息数组（与 Android 端一致）。 */
  async messages(sessionId) {
    const result = await this.request(NODE_METHODS.messages, { sessionId }, { timeoutMs: 20000 });
    return result?.messages ?? [];
  }

  send(sessionId, text, { commandId = null } = {}) {
    return this.request(NODE_METHODS.turnSend, { sessionId, text }, { timeoutMs: 30000, commandId });
  }

  stop(sessionId, { commandId = null } = {}) {
    return this.request(NODE_METHODS.turnStop, { sessionId }, { commandId });
  }

  answer(requestId, allow, { commandId = null } = {}) {
    return this.request(NODE_METHODS.interactionAnswer, { requestId, allow: !!allow }, { commandId });
  }

  ping() {
    return this.request(NODE_METHODS.ping, {}, { timeoutMs: 5000 });
  }

  close(reason = "client closed") {
    if (this._closed) return;
    this._closed = true;
    this.closeReason = reason;
    this.socket.end();
  }

  _onData(chunk) {
    this._buffer += chunk;
    for (;;) {
      const index = this._buffer.indexOf("\n");
      if (index < 0) {
        // 没有换行符还一直涨：对端要么坏了，要么在试内存上限。
        if (this._buffer.length > MAX_FRAME_BYTES) this._failProtocol("frame too large");
        break;
      }
      const line = this._buffer.slice(0, index);
      this._buffer = this._buffer.slice(index + 1);
      if (!line.trim()) continue;
      if (line.length > MAX_FRAME_BYTES) {
        this._failProtocol("frame too large");
        return;
      }
      let frame;
      try {
        frame = JSON.parse(line);
      } catch {
        this.emit("protocolError", new AndroidNodeError("invalid JSON frame"));
        continue;
      }
      this._onFrame(frame);
    }
  }

  _onFrame(frame) {
    if (frame.method === "node.event") {
      const params = frame.params ?? {};
      // seq 落后于已见值的事件是重放或乱序，丢弃而不是重复回显给界面。
      if (Number.isFinite(params.seq)) {
        if (params.seq <= this.lastSeq) {
          this.emit("duplicateEvent", params);
          return;
        }
        this.lastSeq = params.seq;
      }
      this.emit("event", params);
      this.emit(`event:${params.type}`, params);
      return;
    }
    const entry = this._pending.get(frame.id);
    if (!entry) return;
    this._pending.delete(frame.id);
    clearTimeout(entry.timer);
    if (frame.error) {
      entry.reject(new AndroidNodeError(frame.error.message ?? "node error", { code: frame.error.code, data: frame.error.data }));
    } else {
      entry.resolve(frame.result);
    }
  }

  _failProtocol(message) {
    this._buffer = "";
    this.emit("protocolError", new AndroidNodeError(message));
    this.close(message);
  }

  _failAll(error) {
    for (const [, entry] of this._pending) {
      clearTimeout(entry.timer);
      entry.reject(error);
    }
    this._pending.clear();
  }
}
