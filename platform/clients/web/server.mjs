#!/usr/bin/env node
/**
 * WEB-101 / WEB-102：本地 Web UI 与 RPC 桥。
 *
 * 安全默认值（SPEC 12.4 / WEB-002 / LIFE-009）：
 * - 默认只绑定 127.0.0.1；即使绑定局域网地址，远程请求也默认拒绝（--allow-remote 才放行）。
 * - 所有 /api/*（含 /api/events）都要求配对令牌；仅访问端口不构成授权。
 * - 未显式 --allow-local-anonymous 时，本机请求同样需要令牌。
 * - POST 校验 Origin，防跨站请求。
 *
 * 刷新恢复：服务端维护一份实时投影，新连接先拿 snapshot；SSE 带 fromSeq/Last-Event-ID
 * 时，如果事件缓冲仍连续则回放已确认事件，否则发 snapshot 重新对齐。
 *
 * 客户端只调用 core.handleRpc/subscribe（或 stdio 子进程），不读核心数据库。
 */

import http from "node:http";
import path from "node:path";
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { readFile } from "node:fs/promises";
import { fileURLToPath, pathToFileURL } from "node:url";

import { PROTOCOL_VERSION, RPC_ERRORS } from "../../contracts/rpc.mjs";
import {
  RpcClient,
  createInProcessTransport,
  createStdioTransport,
} from "../shared/rpc-client.mjs";
import { canonicalProjection } from "../shared/projection.mjs";
import { createMockCore } from "../shared/mock-core.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_PUBLIC_DIR = path.join(HERE, "public");
const MAX_BODY_BYTES = 256 * 1024;
const MAX_RECENT_EVENTS = 500;
const SSE_HEARTBEAT_MS = 15_000;

const STATIC_FILES = new Map([
  ["/", "index.html"],
  ["/index.html", "index.html"],
  ["/app.mjs", "app.mjs"],
  ["/styles.css", "styles.css"],
]);

const CONTENT_TYPES = new Map([
  [".html", "text/html; charset=utf-8"],
  [".mjs", "text/javascript; charset=utf-8"],
  [".js", "text/javascript; charset=utf-8"],
  [".css", "text/css; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".svg", "image/svg+xml"],
  [".ico", "image/x-icon"],
]);

const SECURITY_HEADERS = {
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "no-referrer",
  "X-Frame-Options": "DENY",
};

const HTML_CSP =
  "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
  "connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

class HttpError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = "HttpError";
    this.status = status;
    this.code = code;
  }
}

export function isLoopback(address) {
  if (!address) return false;
  if (address === "localhost") return true;
  if (address === "::1") return true;
  if (address.startsWith("::ffff:")) return isLoopback(address.slice("::ffff:".length));
  return address.startsWith("127.");
}

function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const ah = createHash("sha256").update(a).digest();
  const bh = createHash("sha256").update(b).digest();
  return timingSafeEqual(ah, bh);
}

function cookieValue(header, name) {
  if (typeof header !== "string") return null;
  for (const part of header.split(";")) {
    const [k, ...rest] = part.trim().split("=");
    if (k === name) return decodeURIComponent(rest.join("="));
  }
  return null;
}

export function extractToken({ headers = {}, url = null } = {}) {
  const auth = headers.authorization;
  if (typeof auth === "string" && /^Bearer\s+/i.test(auth)) return auth.replace(/^Bearer\s+/i, "").trim();
  if (typeof headers["x-coda-token"] === "string") return headers["x-coda-token"];
  if (url && typeof url.searchParams?.get === "function") {
    const fromQuery = url.searchParams.get("token");
    if (fromQuery) return fromQuery;
  }
  return cookieValue(headers.cookie, "coda_token");
}

/**
 * 授权判定（导出以便单测远程/本机矩阵）。
 * @returns {{ok:true, mode:string}|{ok:false, status:number, code:number, message:string, mode:string}}
 */
export function evaluateAccess({
  remoteAddress = "",
  headers = {},
  url = null,
  token = "",
  allowLocalAnonymous = false,
  allowRemote = false,
} = {}) {
  const local = isLoopback(remoteAddress);
  const provided = extractToken({ headers, url });

  if (provided !== null && provided !== "") {
    if (!token || !safeEqual(provided, token)) {
      return {
        ok: false,
        status: 401,
        code: RPC_ERRORS.UNAUTHORIZED,
        message: "invalid pairing token",
        mode: "token",
      };
    }
    if (!local && !allowRemote) {
      return {
        ok: false,
        status: 403,
        code: RPC_ERRORS.UNAUTHORIZED,
        message: "remote access disabled; start the server with --allow-remote",
        mode: "remote",
      };
    }
    return { ok: true, mode: local ? "local-token" : "remote-token" };
  }

  if (!local) {
    return {
      ok: false,
      status: 401,
      code: RPC_ERRORS.UNAUTHORIZED,
      message: "pairing token required for remote access",
      mode: "remote",
    };
  }
  if (allowLocalAnonymous) return { ok: true, mode: "anonymous-local" };
  return {
    ok: false,
    status: 401,
    code: RPC_ERRORS.UNAUTHORIZED,
    message: "pairing token required",
    mode: "local",
  };
}

function sendJson(res, status, body, extraHeaders = {}) {
  if (res.writableEnded) return;
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    ...SECURITY_HEADERS,
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Content-Length": Buffer.byteLength(payload),
    ...extraHeaders,
  });
  res.end(payload);
}

function sendRaw(res, status, body, headers = {}) {
  if (res.writableEnded) return;
  res.writeHead(status, { ...SECURITY_HEADERS, ...headers });
  res.end(body);
}

async function readJson(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new HttpError(413, RPC_ERRORS.INVALID_REQUEST, "request body too large");
    chunks.push(chunk);
  }
  if (size === 0) return {};
  const text = Buffer.concat(chunks).toString("utf8");
  try {
    return JSON.parse(text);
  } catch {
    throw new HttpError(400, RPC_ERRORS.PARSE_ERROR, "invalid JSON body");
  }
}

function rpcErrorStatus(code) {
  switch (code) {
    case RPC_ERRORS.NOT_FOUND:
      return 404;
    case RPC_ERRORS.UNAUTHORIZED:
      return 401;
    case RPC_ERRORS.EXPIRED:
      return 410;
    case RPC_ERRORS.VERSION_MISMATCH:
    case RPC_ERRORS.CONFLICT:
      return 409;
    case RPC_ERRORS.UNSUPPORTED_CAPABILITY:
      return 422;
    case RPC_ERRORS.INVALID_PARAMS:
      return 400;
    default:
      return 500;
  }
}

function sendRpcError(res, err) {
  sendJson(res, rpcErrorStatus(err?.code), {
    ok: false,
    error: { code: err?.code ?? RPC_ERRORS.INTERNAL, message: err?.message ?? String(err) },
  });
}

function checkOrigin(req) {
  const origin = req.headers.origin;
  if (!origin) return true;
  try {
    const parsed = new URL(origin);
    if (isLoopback(parsed.hostname)) return true;
    return parsed.host === req.headers.host;
  } catch {
    return false;
  }
}

function sanitizeFilename(name) {
  return String(name ?? "artifact")
    .replace(/[\r\n"\\/:*?<>|]+/g, "_")
    .slice(0, 200) || "artifact";
}

function sseWrite(res, fields) {
  if (res.writableEnded) return;
  let chunk = "";
  for (const [key, value] of Object.entries(fields)) {
    if (value === undefined || value === null) continue;
    if (key === "id") chunk += `id: ${value}\n`;
    else if (key === "event") chunk += `event: ${value}\n`;
    else chunk += `${key}: ${value}\n`;
  }
  res.write(`${chunk}\n`);
}

/**
 * 启动 Web 服务。
 * @param {object} options
 * @param {object} [options.core] 进程内核心（测试/嵌入）
 * @param {object} [options.transport] 自定义 transport（stdio 等）
 */
export async function startWebServer({
  core = null,
  transport = null,
  port = 0,
  host = "127.0.0.1",
  token = null,
  allowLocalAnonymous = false,
  allowRemote = false,
  publicDir = DEFAULT_PUBLIC_DIR,
  log = () => {},
  timeoutMs = 15_000,
} = {}) {
  if (!core && !transport) throw new Error("startWebServer requires core or transport");

  const pairingToken = token || randomBytes(24).toString("base64url");
  const generatedToken = !token;
  const rpc = new RpcClient({
    transport: transport ?? createInProcessTransport(core),
    clientType: "web",
    timeoutMs,
  });
  await rpc.open();

  const projectionState = rpc.projection;
  const recentEvents = [];
  const sseClients = new Set();
  const answeredInteractions = new Set();
  let closed = false;

  function broadcast(event) {
    const payload = JSON.stringify({ event });
    for (const client of sseClients) {
      sseWrite(client.res, { id: event.seq, event: "event", data: payload });
    }
  }

  const offEvent = rpc.onEvent((event) => {
    recentEvents.push(event);
    if (recentEvents.length > MAX_RECENT_EVENTS) recentEvents.shift();
    broadcast(event);
  });

  const heartbeat = setInterval(() => {
    for (const client of sseClients) {
      if (!client.res.writableEnded) client.res.write(": ping\n\n");
    }
  }, SSE_HEARTBEAT_MS);
  heartbeat.unref?.();

  function snapshotMessage() {
    return {
      seq: projectionState.seq,
      protocol: {
        version: PROTOCOL_VERSION,
        coreVersion: rpc.welcome?.coreVersion ?? null,
      },
      state: canonicalProjection(projectionState),
    };
  }

  function openSse(req, res, url) {
    res.writeHead(200, {
      ...SECURITY_HEADERS,
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-store, no-cache, must-revalidate",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    });
    res.flushHeaders?.();

    const rawFromSeq = url.searchParams.get("fromSeq") ?? req.headers["last-event-id"] ?? "0";
    const fromSeq = Number(rawFromSeq);
    const client = { res, fromSeq: Number.isFinite(fromSeq) ? fromSeq : 0 };
    sseClients.add(client);

    const currentSeq = projectionState.seq;
    const oldest = recentEvents[0]?.seq ?? currentSeq + 1;
    const canReplay =
      client.fromSeq > 0 && client.fromSeq <= currentSeq && client.fromSeq >= oldest - 1;

    if (canReplay) {
      sseWrite(res, {
        event: "resumed",
        data: JSON.stringify({ fromSeq: client.fromSeq, seq: currentSeq }),
      });
      for (const event of recentEvents) {
        if (event.seq > client.fromSeq) {
          sseWrite(res, { id: event.seq, event: "event", data: JSON.stringify({ event }) });
        }
      }
    } else {
      sseWrite(res, {
        id: currentSeq,
        event: "snapshot",
        data: JSON.stringify(snapshotMessage()),
      });
    }
    res.write(": connected\n\n");

    const cleanup = () => {
      sseClients.delete(client);
    };
    req.on("close", cleanup);
    res.on("close", cleanup);
    res.on("error", cleanup);
  }

  async function serveStatic(res, fileName) {
    const absolute = path.join(publicDir, fileName);
    const normalized = path.normalize(absolute);
    if (!normalized.startsWith(path.normalize(publicDir))) {
      throw new HttpError(403, RPC_ERRORS.UNAUTHORIZED, "forbidden");
    }
    let data;
    try {
      data = await readFile(normalized);
    } catch {
      return sendJson(res, 404, { ok: false, error: { code: RPC_ERRORS.NOT_FOUND, message: "not found" } });
    }
    const ext = path.extname(fileName);
    const headers = {
      "Content-Type": CONTENT_TYPES.get(ext) ?? "application/octet-stream",
      "Cache-Control": fileName === "index.html" ? "no-store" : "no-cache",
      "Content-Length": data.length,
    };
    if (ext === ".html") headers["Content-Security-Policy"] = HTML_CSP;
    sendRaw(res, 200, data, headers);
  }

  async function handle(req, res) {
    const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);
    const remoteAddress = req.socket?.remoteAddress ?? "";

    if (req.method === "GET" && url.pathname === "/health") {
      return sendJson(res, 200, { ok: true, protocolVersion: PROTOCOL_VERSION, seq: projectionState.seq });
    }
    if (req.method === "GET" && STATIC_FILES.has(url.pathname)) {
      return serveStatic(res, STATIC_FILES.get(url.pathname));
    }
    if (req.method === "GET" && url.pathname === "/favicon.ico") {
      res.writeHead(204, SECURITY_HEADERS);
      return res.end();
    }

    // 配对端点：用请求体内的 token 换取访问权，不要求已有 Authorization。
    if (req.method === "POST" && url.pathname === "/api/pair") {
      const body = await readJson(req);
      const candidate = typeof body.token === "string" ? body.token : "";
      if (!candidate || !safeEqual(candidate, pairingToken)) {
        return sendJson(res, 401, {
          ok: false,
          error: { code: RPC_ERRORS.UNAUTHORIZED, message: "invalid pairing token" },
        });
      }
      return sendJson(res, 200, {
        ok: true,
        protocolVersion: PROTOCOL_VERSION,
        coreVersion: rpc.welcome?.coreVersion ?? null,
        seq: projectionState.seq,
      });
    }

    if (url.pathname.startsWith("/api/")) {
      const access = evaluateAccess({
        remoteAddress,
        headers: req.headers,
        url,
        token: pairingToken,
        allowLocalAnonymous,
        allowRemote,
      });
      if (!access.ok) {
        return sendJson(res, access.status, {
          ok: false,
          error: { code: access.code, message: access.message, mode: access.mode },
        });
      }
    }

    if (req.method === "GET" && url.pathname === "/api/events") {
      return openSse(req, res, url);
    }

    if (req.method === "GET" && url.pathname === "/api/state") {
      return sendJson(res, 200, {
        ok: true,
        seq: projectionState.seq,
        ...snapshotMessage(),
      });
    }

    if (req.method === "POST" && url.pathname === "/api/command") {
      if (!checkOrigin(req)) return sendJson(res, 403, { ok: false, error: { code: RPC_ERRORS.UNAUTHORIZED, message: "origin not allowed" } });
      const body = await readJson(req);
      if (!body || typeof body.kind !== "string") {
        return sendJson(res, 400, {
          ok: false,
          error: { code: RPC_ERRORS.INVALID_PARAMS, message: "kind is required" },
        });
      }
      try {
        const receipt = await rpc.command(body.kind, body.target ?? {}, body.payload ?? {}, {
          commandId: body.commandId,
          ttlMs: body.ttlMs,
          supersedes: body.supersedes,
        });
        return sendJson(res, 200, {
          ok: true,
          receipt,
          state: canonicalProjection(projectionState),
          seq: projectionState.seq,
        });
      } catch (err) {
        return sendRpcError(res, err);
      }
    }

    if (req.method === "POST" && url.pathname === "/api/answer") {
      if (!checkOrigin(req)) return sendJson(res, 403, { ok: false, error: { code: RPC_ERRORS.UNAUTHORIZED, message: "origin not allowed" } });
      const body = await readJson(req);
      const interactionId = body.interactionId;
      if (typeof interactionId !== "string" || !interactionId) {
        return sendJson(res, 400, {
          ok: false,
          error: { code: RPC_ERRORS.INVALID_PARAMS, message: "interactionId is required" },
        });
      }
      if (answeredInteractions.has(interactionId)) {
        return sendJson(res, 200, { ok: true, duplicate: true, interactionId });
      }
      const current = projectionState.interactions.get(interactionId);
      if (!current) {
        return sendJson(res, 404, {
          ok: false,
          error: { code: RPC_ERRORS.NOT_FOUND, message: `unknown interaction ${interactionId}` },
        });
      }
      if (current.status !== "pending") {
        answeredInteractions.add(interactionId);
        return sendJson(res, 200, { ok: true, duplicate: true, interactionId, status: current.status });
      }
      try {
        const receipt = await rpc.command(
          "interaction.answer",
          { interactionId },
          { answer: body.answer ?? { optionId: body.optionId ?? null, text: body.text ?? null } },
        );
        if (receipt.state === "failed" || receipt.state === "unknown") {
          return sendJson(res, 409, {
            ok: false,
            duplicate: false,
            error: {
              code: receipt.error?.code ?? RPC_ERRORS.UNKNOWN_RESULT,
              message: receipt.error?.message ?? "answer failed",
            },
          });
        }
        answeredInteractions.add(interactionId);
        return sendJson(res, 200, {
          ok: true,
          duplicate: receipt.duplicate === true,
          interactionId,
          receipt,
          state: canonicalProjection(projectionState),
        });
      } catch (err) {
        return sendRpcError(res, err);
      }
    }

    const terminalMatch = url.pathname.match(/^\/api\/terminal\/([^/]+)$/);
    if (req.method === "GET" && terminalMatch) {
      const id = decodeURIComponent(terminalMatch[1]);
      const terminal = projectionState.terminals.get(id);
      if (!terminal) {
        return sendJson(res, 404, {
          ok: false,
          error: { code: RPC_ERRORS.NOT_FOUND, message: `unknown terminal ${id}` },
        });
      }
      return sendJson(res, 200, {
        ok: true,
        terminal: {
          id: terminal.id,
          sessionId: terminal.sessionId ?? null,
          title: terminal.title ?? "terminal",
          status: terminal.status ?? "open",
          node: terminal.node ?? null,
          environment: terminal.environment ?? null,
          output: terminal.output ?? "",
          fullLength: terminal.fullLength ?? (terminal.output ?? "").length,
          truncated: terminal.truncated === true,
        },
      });
    }

    const artifactDownloadMatch = url.pathname.match(/^\/api\/artifacts\/([^/]+)\/download$/);
    if (req.method === "GET" && artifactDownloadMatch) {
      const id = decodeURIComponent(artifactDownloadMatch[1]);
      const artifact = projectionState.artifacts.get(id);
      if (!artifact) {
        return sendJson(res, 404, {
          ok: false,
          error: { code: RPC_ERRORS.NOT_FOUND, message: `unknown artifact ${id}` },
        });
      }
      const content = artifact.content ?? artifact.diff ?? "";
      const filename = sanitizeFilename(artifact.name);
      return sendRaw(res, 200, content, {
        "Content-Type": artifact.mime ?? "application/octet-stream",
        "Content-Disposition": `attachment; filename="${filename}"; filename*=UTF-8''${encodeURIComponent(String(artifact.name ?? filename))}`,
        "Content-Length": Buffer.byteLength(content),
        "Cache-Control": "no-store",
      });
    }

    const artifactMatch = url.pathname.match(/^\/api\/artifacts\/([^/]+)$/);
    if (req.method === "GET" && artifactMatch) {
      const id = decodeURIComponent(artifactMatch[1]);
      const artifact = projectionState.artifacts.get(id);
      if (!artifact) {
        return sendJson(res, 404, {
          ok: false,
          error: { code: RPC_ERRORS.NOT_FOUND, message: `unknown artifact ${id}` },
        });
      }
      return sendJson(res, 200, {
        ok: true,
        artifact: {
          id: artifact.id,
          sessionId: artifact.sessionId ?? null,
          name: artifact.name,
          kind: artifact.kind,
          mime: artifact.mime,
          size: artifact.size ?? null,
          sha256: artifact.sha256 ?? null,
          node: artifact.node ?? null,
          environment: artifact.environment ?? null,
          meta: artifact.meta ?? null,
          truncated: artifact.diffTruncated === true,
        },
      });
    }

    return sendJson(res, 404, {
      ok: false,
      error: { code: RPC_ERRORS.NOT_FOUND, message: "not found" },
    });
  }

  const server = http.createServer((req, res) => {
    Promise.resolve()
      .then(() => handle(req, res))
      .catch((err) => {
        if (res.writableEnded) return;
        if (err instanceof HttpError) {
          return sendJson(res, err.status, {
            ok: false,
            error: { code: err.code, message: err.message },
          });
        }
        log(`[web] request error: ${err?.message ?? err}`);
        return sendJson(res, 500, {
          ok: false,
          error: { code: RPC_ERRORS.INTERNAL, message: "internal error" },
        });
      });
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
    server.listen(port, host);
  });

  const address = server.address();
  const actualPort = address && typeof address === "object" ? address.port : port;
  const displayHost = host === "0.0.0.0" || host === "::" ? "127.0.0.1" : host;
  const url = `http://${displayHost}:${actualPort}/`;

  log(`LISTENING ${actualPort}`);
  if (generatedToken) log(`PAIRING_TOKEN ${pairingToken}`);
  log(`URL ${url}`);

  return {
    server,
    rpc,
    token: pairingToken,
    port: actualPort,
    host,
    url,
    pairingUrl: `${url}?token=${encodeURIComponent(pairingToken)}`,
    get state() {
      return canonicalProjection(projectionState);
    },
    async close() {
      if (closed) return;
      closed = true;
      clearInterval(heartbeat);
      for (const client of sseClients) {
        try {
          client.res.end();
        } catch {
          /* ignore */
        }
      }
      sseClients.clear();
      offEvent();
      await new Promise((resolve) => {
        server.close(() => resolve());
        server.closeAllConnections?.();
      });
      rpc.close();
    },
  };
}

export function parseArgs(argv = []) {
  const args = {
    port: 0,
    host: "127.0.0.1",
    core: null,
    mock: false,
    token: null,
    allowLocalAnonymous: false,
    allowRemote: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--port") args.port = Number(argv[++i] ?? 0);
    else if (arg.startsWith("--port=")) args.port = Number(arg.slice("--port=".length));
    else if (arg === "--host") args.host = argv[++i] ?? "127.0.0.1";
    else if (arg.startsWith("--host=")) args.host = arg.slice("--host=".length);
    else if (arg === "--core") args.core = argv[++i] ?? null;
    else if (arg.startsWith("--core=")) args.core = arg.slice("--core=".length);
    else if (arg === "--mock") args.mock = true;
    else if (arg === "--token") args.token = argv[++i] ?? null;
    else if (arg.startsWith("--token=")) args.token = arg.slice("--token=".length);
    else if (arg === "--allow-local-anonymous") args.allowLocalAnonymous = true;
    else if (arg === "--allow-remote") args.allowRemote = true;
    else if (arg === "--help" || arg === "-h") args.help = true;
  }
  if (!Number.isInteger(args.port) || args.port < 0 || args.port > 65535) {
    throw new Error(`invalid --port: ${args.port}`);
  }
  return args;
}

const USAGE = [
  "Coda Web UI",
  "",
  "用法:",
  "  node clients/web/server.mjs --core stdio:<cmd> [--port 0] [--host 127.0.0.1]",
  "  node clients/web/server.mjs --mock [--port 0]",
  "",
  "安全:",
  "  所有 /api/* 与 /api/events 需要配对令牌；默认只监听 127.0.0.1。",
  "  远程访问需 --allow-remote 且携带令牌；明文局域网 HTTP 默认不获得管理权限。",
  "  --allow-local-anonymous 仅用于本机开发，远程仍被拒绝。",
  "",
  "选项:",
  "  --port <n>      端口，0 表示随机端口（会打印 LISTENING <port>）",
  "  --host <addr>   监听地址，默认 127.0.0.1",
  "  --core stdio:<cmd>  连接外部核心",
  "  --mock          使用内置 Mock Core",
  "  --token <t>     指定配对令牌（默认随机生成并打印）",
  "  --allow-remote  允许非回环请求（仍需令牌）",
  "  --allow-local-anonymous  允许本机无令牌访问（危险，仅开发）",
].join("\n");

export async function main(argv = process.argv.slice(2)) {
  let args;
  try {
    args = parseArgs(argv);
  } catch (err) {
    console.error(err.message);
    return 2;
  }
  if (args.help) {
    console.log(USAGE);
    return 0;
  }

  let core = null;
  let transport = null;
  if (args.mock) {
    core = createMockCore();
  } else if (args.core?.startsWith("stdio:")) {
    transport = createStdioTransport(args.core.slice("stdio:".length), {
      cwd: process.cwd(),
      onStderr: (chunk) => process.stderr.write(chunk),
      onDiagnostic: (diag) => {
        if (diag.direction === "parse") process.stderr.write(`[core] 无法解析的帧: ${diag.error}\n`);
      },
    });
  } else if (args.core) {
    console.error(`不支持的 --core 连接方式: ${args.core}（请使用 stdio:<cmd>）`);
    return 2;
  } else {
    console.log(USAGE);
    return 2;
  }

  const instance = await startWebServer({
    core,
    transport,
    port: args.port,
    host: args.host,
    token: args.token,
    allowLocalAnonymous: args.allowLocalAnonymous,
    allowRemote: args.allowRemote,
    log: (line) => console.log(line),
  });

  const shutdown = () => {
    instance.close().finally(() => {
      process.exit(0);
    });
  };
  process.once("SIGINT", shutdown);
  process.once("SIGTERM", shutdown);

  if (!args.allowLocalAnonymous) {
    console.log("提示: 在浏览器打开上面的 URL；首次访问需要 PAIRING_TOKEN（或使用 --token 指定的令牌）。");
  }
  return instance;
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  main().catch((err) => {
    console.error(err);
    process.exitCode = 1;
  });
}
