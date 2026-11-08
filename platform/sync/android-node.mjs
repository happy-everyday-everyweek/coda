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

export const NODE_METHODS = Object.freeze({
  hello: "node.hello",
  snapshot: "node.snapshot",
  sessionNew: "node.session.new",
  sessionOpen: "node.session.open",
  turnSend: "node.turn.send",
  turnStop: "node.turn.stop",
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
    socket.setEncoding("utf8");
    socket.on("data", (chunk) => this._onData(chunk));
    socket.on("error", (error) => this._failAll(error));
    socket.on("close", () => {
      this._closed = true;
      this._failAll(new AndroidNodeError("android node disconnected"));
      this.emit("close");
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
            throw new AndroidNodeError(`unsupported node protocol ${hello?.protocolVersion}`);
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

  request(method, params = {}, { timeoutMs = 15000 } = {}) {
    if (this._closed) return Promise.reject(new AndroidNodeError("android node closed"));
    const id = this._nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this._pending.delete(id);
        reject(new AndroidNodeError(`rpc timeout: ${method}`));
      }, timeoutMs);
      this._pending.set(id, { resolve, reject, timer, method });
      this.socket.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
    });
  }

  // 便捷方法：与 Android 端 NodeBridge 的方法一一对应。
  snapshot() {
    return this.request(NODE_METHODS.snapshot);
  }

  newSession(workspace = "") {
    return this.request(NODE_METHODS.sessionNew, workspace ? { workspace } : {});
  }

  openSession(sessionId) {
    return this.request(NODE_METHODS.sessionOpen, { sessionId });
  }

  send(sessionId, text) {
    return this.request(NODE_METHODS.turnSend, { sessionId, text }, { timeoutMs: 30000 });
  }

  stop(sessionId) {
    return this.request(NODE_METHODS.turnStop, { sessionId });
  }

  answer(requestId, allow) {
    return this.request(NODE_METHODS.interactionAnswer, { requestId, allow: !!allow });
  }

  ping() {
    return this.request(NODE_METHODS.ping, {}, { timeoutMs: 5000 });
  }

  close() {
    if (this._closed) return;
    this._closed = true;
    this.socket.end();
  }

  _onData(chunk) {
    this._buffer += chunk;
    for (;;) {
      const index = this._buffer.indexOf("\n");
      if (index < 0) break;
      const line = this._buffer.slice(0, index);
      this._buffer = this._buffer.slice(index + 1);
      if (!line.trim()) continue;
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

  _failAll(error) {
    for (const [, entry] of this._pending) {
      clearTimeout(entry.timer);
      entry.reject(error);
    }
    this._pending.clear();
  }
}
