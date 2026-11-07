/**
 * 同步通道（SYNC-102 / SYNC-111）。
 *
 * - `createMemoryTransportPair()`：进程内、可断开重连的双向通道，用于两端收敛测试，
 *   不依赖端口/网络。
 * - `createTcpServer` / `connectTcp`：真实局域网 TCP 通道（NDJSON 分帧），测试绑定
 *   `127.0.0.1:0` 并读回实际端口，不联网、不绑固定端口。
 *
 * 传输层只负责搬运字符串，不理解加密与业务；加密由 crypto.mjs 叠加。
 */
import net from "node:net";

export class TransportError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "TransportError";
    this.code = code;
    this.details = details;
  }
}

function drainEndpoint(endpoint) {
  if (endpoint.draining) return;
  endpoint.draining = true;
  queueMicrotask(() => {
    endpoint.draining = false;
    while (!endpoint.closed && endpoint.queue.length > 0 && endpoint.dataHandlers.size > 0) {
      const item = endpoint.queue.shift();
      for (const handler of [...endpoint.dataHandlers]) {
        try {
          handler(item);
        } catch {
          // 上层（crypto/service）自己处理协议错误；传输层不吞掉通道。
        }
      }
    }
  });
}

/**
 * 进程内成对通道。`close()` 会同时关闭对端（等价于 TCP RST），用于分区测试；
 * 重连时创建新的 pair。
 */
export function createMemoryTransportPair() {
  const a = { closed: false, queue: [], dataHandlers: new Set(), closeHandlers: new Set(), draining: false };
  const b = { closed: false, queue: [], dataHandlers: new Set(), closeHandlers: new Set(), draining: false };
  a.peer = b;
  b.peer = a;

  function endpoint(self) {
    return {
      kind: "memory",
      send(data) {
        if (self.closed) return false;
        const text = typeof data === "string" ? data : String(data);
        if (!self.peer || self.peer.closed) return false;
        self.peer.queue.push(text);
        drainEndpoint(self.peer);
        return true;
      },
      onData(handler) {
        self.dataHandlers.add(handler);
        drainEndpoint(self);
        return () => self.dataHandlers.delete(handler);
      },
      onClose(handler) {
        self.closeHandlers.add(handler);
        return () => self.closeHandlers.delete(handler);
      },
      close(info = { reason: "local_close" }) {
        if (self.closed) return;
        self.closed = true;
        self.queue.length = 0;
        for (const handler of [...self.closeHandlers]) handler(info);
        const peer = self.peer;
        if (peer && !peer.closed) {
          peer.closed = true;
          peer.queue.length = 0;
          for (const handler of [...peer.closeHandlers]) handler({ reason: "peer_close", ...info });
        }
      },
      describe() {
        return { kind: "memory" };
      },
    };
  }

  return { left: endpoint(a), right: endpoint(b) };
}

function wrapSocket(socket, { host = null, port = null, maxLineBytes = 2 * 1024 * 1024 } = {}) {
  const dataHandlers = new Set();
  const closeHandlers = new Set();
  const pendingLines = [];
  let buffer = "";
  let closed = false;
  let closeInfo = null;

  const notifyClose = (info) => {
    if (closed) return;
    closed = true;
    closeInfo = info ?? { reason: "socket_closed" };
    for (const handler of [...closeHandlers]) handler(closeInfo);
  };

  socket.setNoDelay?.(true);
  socket.on("data", (chunk) => {
    buffer += chunk.toString("utf8");
    if (buffer.length > maxLineBytes) {
      socket.destroy(new TransportError("LINE_TOO_LARGE", "transport line exceeds limit"));
      return;
    }
    let index;
    while ((index = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, index);
      buffer = buffer.slice(index + 1);
      if (line.length === 0) continue;
      pendingLines.push(line);
      if (dataHandlers.size > 0) {
        while (pendingLines.length) {
          const next = pendingLines.shift();
          for (const handler of [...dataHandlers]) handler(next);
        }
      }
    }
  });
  socket.on("error", (err) => notifyClose({ reason: "socket_error", code: err.code ?? null }));
  socket.on("close", () => notifyClose({ reason: "socket_closed" }));

  return {
    kind: "tcp",
    remote: { host, port },
    send(data) {
      if (closed || socket.destroyed) throw new TransportError("TRANSPORT_CLOSED", "tcp transport is closed");
      socket.write(`${typeof data === "string" ? data : String(data)}\n`);
    },
    onData(handler) {
      dataHandlers.add(handler);
      while (pendingLines.length) {
        const line = pendingLines.shift();
        for (const h of [...dataHandlers]) h(line);
      }
      return () => dataHandlers.delete(handler);
    },
    onClose(handler) {
      closeHandlers.add(handler);
      if (closed) handler(closeInfo);
      return () => closeHandlers.delete(handler);
    },
    close(info = { reason: "local_close" }) {
      if (socket.destroyed) return;
      socket.destroy();
      notifyClose(info);
    },
    describe() {
      return { kind: "tcp", host, port };
    },
  };
}

/** 启动本地 TCP 监听（host 默认 127.0.0.1，port 0 表示由系统分配）。 */
export async function createTcpServer({ host = "127.0.0.1", port = 0, maxLineBytes } = {}) {
  const server = net.createServer();
  const pending = [];
  const waiters = [];
  server.on("connection", (socket) => {
    const transport = wrapSocket(socket, { host: socket.remoteAddress, port: socket.remotePort, maxLineBytes });
    const waiter = waiters.shift();
    if (waiter) waiter.resolve(transport);
    else pending.push(transport);
  });
  await new Promise((resolve, reject) => {
    const onError = (err) => {
      server.off("listening", onListening);
      reject(err);
    };
    const onListening = () => {
      server.off("error", onError);
      resolve();
    };
    server.once("error", onError);
    server.once("listening", onListening);
    server.listen({ host, port });
  });
  const address = server.address();
  return {
    port: address.port,
    host: address.address,
    accept({ timeoutMs = 10_000 } = {}) {
      if (pending.length) return Promise.resolve(pending.shift());
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          const index = waiters.findIndex((w) => w.resolve === resolve);
          if (index >= 0) waiters.splice(index, 1);
          reject(new TransportError("ACCEPT_TIMEOUT", "timed out waiting for incoming connection"));
        }, timeoutMs);
        timer.unref?.();
        waiters.push({
          resolve: (transport) => {
            clearTimeout(timer);
            resolve(transport);
          },
        });
      });
    },
    close() {
      for (const transport of pending.splice(0)) transport.close({ reason: "server_close" });
      for (const waiter of waiters.splice(0)) waiter.resolve(null);
      server.close();
    },
  };
}

/** 连接本地/局域网 TCP 端点。 */
export async function connectTcp({ host = "127.0.0.1", port, timeoutMs = 5000 } = {}) {
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new TransportError("INVALID_PORT", `invalid port ${port}`);
  }
  const socket = net.createConnection({ host, port });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new TransportError("CONNECT_TIMEOUT", `connect timeout to ${host}:${port}`));
    }, timeoutMs);
    timer.unref?.();
    const cleanup = () => {
      clearTimeout(timer);
      socket.off("connect", onConnect);
      socket.off("error", onError);
    };
    const onConnect = () => {
      cleanup();
      resolve();
    };
    const onError = (err) => {
      cleanup();
      reject(new TransportError("CONNECT_FAILED", `cannot connect to ${host}:${port}: ${err.message}`));
    };
    socket.once("connect", onConnect);
    socket.once("error", onError);
  });
  return wrapSocket(socket, { host, port });
}
