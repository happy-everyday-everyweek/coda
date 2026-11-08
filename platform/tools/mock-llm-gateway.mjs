#!/usr/bin/env node
/**
 * 本地 LLM 测试网关（零依赖）。
 *
 * 目的：真实 Agent CLI 通常只缺「可用的模型后端」；启动一个兼容 Anthropic Messages /
 * OpenAI Chat Completions / OpenAI Responses 的本地网关，就能让它们跑完整回合，
 * 从而验证协议、流式事件、工具循环与退出码，而不是只验证 --version/--help。
 *
 * 明确边界：回复是固定文本，不代表模型质量；它验证的是 **真实 Agent ↔ 网关协议**。
 *
 * 用法：
 *   node tools/mock-llm-gateway.mjs --port 0 --log /tmp/gateway.jsonl
 *   → 打印 LISTENING <port>
 *
 *   POST /v1/messages            Anthropic Messages（SSE / JSON）
 *   POST /v1/messages/count_tokens
 *   POST /v1/chat/completions    OpenAI Chat Completions（SSE / JSON）
 *   POST /v1/responses           OpenAI Responses（SSE / JSON，Codex 用）
 *   GET  /v1/models
 *   GET  /health
 */
import { createServer } from "node:http";
import { appendFileSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const DEFAULT_REPLY = "CODA_GATEWAY_OK";

let counter = 0;
const nextId = (prefix) => `${prefix}_${Date.now().toString(36)}${(counter += 1).toString(36)}`;

function sseHead(res) {
  res.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-cache",
    connection: "keep-alive",
    "x-accel-buffering": "no",
  });
}

function sse(res, event, data) {
  if (event) res.write(`event: ${event}\n`);
  res.write(`data: ${JSON.stringify(data)}\n\n`);
}

function json(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(text),
  });
  res.end(text);
}

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString("utf8");
  if (!raw) return {};
  try {
    return JSON.parse(raw);
  } catch {
    return { _raw: raw };
  }
}

/** 从 Anthropic / OpenAI / Responses 三种请求里取最后一条用户文本。 */
export function lastUserText(body) {
  const textOf = (content) => {
    if (typeof content === "string") return content;
    if (Array.isArray(content)) {
      return content
        .map((part) => {
          if (typeof part === "string") return part;
          if (part?.type === "text") return part.text || "";
          if (part?.type === "input_text") return part.text || "";
          return "";
        })
        .join("");
    }
    return "";
  };
  if (Array.isArray(body?.messages)) {
    for (let i = body.messages.length - 1; i >= 0; i -= 1) {
      const message = body.messages[i];
      if (message?.role === "user") return textOf(message.content);
    }
    return textOf(body.messages.at(-1)?.content);
  }
  const input = body?.input;
  if (typeof input === "string") return input;
  if (Array.isArray(input)) {
    for (let i = input.length - 1; i >= 0; i -= 1) {
      if (input[i]?.role === "user") return textOf(input[i].content);
    }
  }
  return "";
}

/**
 * @param {object} options
 * @param {string} [options.reply] 固定回复文本
 * @param {number} [options.delayMs] 每个 delta 之间的延迟，默认 5ms
 * @param {string} [options.logFile] JSONL 记录
 * @param {string} [options.captureDir] 每个请求的完整 body（排查协议用）
 */
export function createGateway(options = {}) {
  const reply = options.reply ?? DEFAULT_REPLY;
  const delayMs = options.delayMs ?? 5;
  const logFile = options.logFile;
  const captureDir = options.captureDir;

  const record = (entry, body) => {
    if (logFile) appendFileSync(logFile, `${JSON.stringify(entry)}\n`);
    if (captureDir) {
      mkdirSync(captureDir, { recursive: true });
      const safe = `${Date.now()}-${(counter += 1)}-${entry.path.replace(/[^a-z0-9]+/gi, "_")}.json`;
      writeFileSync(join(captureDir, safe), JSON.stringify(body, null, 2));
    }
  };

  const handler = async (req, res) => {
    const url = new URL(req.url, "http://localhost");
    const path = url.pathname;
    if (req.method === "GET" && (path === "/health" || path === "/")) {
      return json(res, 200, { ok: true, service: "coda-mock-llm-gateway", reply });
    }
    // Kimi Code 的自定义 registry（api.json）也由网关提供，便于一键导入。
    if (req.method === "GET" && (path === "/api.json" || path === "/registry.json")) {
      const origin = `http://${req.headers.host ?? "127.0.0.1"}`;
      return json(res, 200, {
        coda: {
          id: "coda",
          name: "Coda Gateway",
          api: `${origin}/v1`,
          type: "openai",
          models: {
            "coda-gateway": {
              id: "coda-gateway",
              name: "Coda Gateway",
              limit: { context: 131072, output: 8192 },
              tool_call: true,
              reasoning: false,
            },
          },
        },
      });
    }
    if (req.method === "GET" && path === "/v1/models") {
      return json(res, 200, {
        object: "list",
        data: [
          { id: "coda-gateway", object: "model", owned_by: "coda" },
          { id: "claude-sonnet-4-5", object: "model", owned_by: "coda" },
          { id: "gpt-5-codex", object: "model", owned_by: "coda" },
        ],
      });
    }
    if (req.method !== "POST") return json(res, 404, { error: { message: "not found" } });

    const body = await readBody(req);
    const model = body.model || "coda-gateway";
    const stream = body.stream === true;
    const userText = lastUserText(body);
    record({ ts: new Date().toISOString(), path, model, stream, lastUser: userText.slice(0, 200), reply }, body);

    // 有些 CLI（如 CodeBuddy）把 base_url 直接当根，请求 /chat/completions；
    // 这里同时接受带 /v1 与不带 /v1 的形式。
    const bare = path.replace(/^\/v1(?=\/)/, "");
    if (bare === "/messages/count_tokens") {
      return json(res, 200, { input_tokens: Math.max(1, Math.ceil(userText.length / 4)) });
    }

    if (bare === "/messages") return anthropic(res, body, { model, stream, reply, delayMs });
    if (bare === "/chat/completions") return chatCompletions(res, body, { model, stream, reply, delayMs });
    if (bare === "/responses") return responses(res, body, { model, stream, reply, delayMs });
    return json(res, 404, { error: { message: `unsupported path: ${path}` } });
  };

  return createServer((req, res) => {
    handler(req, res).catch((error) => {
      if (!res.headersSent) json(res, 500, { error: { message: error.message } });
      else res.end();
    });
  });
}

async function anthropic(res, body, { model, stream, reply, delayMs }) {
  const id = nextId("msg");
  if (!stream) {
    return json(res, 200, {
      id,
      type: "message",
      role: "assistant",
      model,
      content: [{ type: "text", text: reply }],
      stop_reason: "end_turn",
      stop_sequence: null,
      usage: { input_tokens: 12, output_tokens: 8 },
    });
  }
  sseHead(res);
  sse(res, "message_start", {
    type: "message_start",
    message: {
      id,
      type: "message",
      role: "assistant",
      model,
      content: [],
      stop_reason: null,
      stop_sequence: null,
      usage: { input_tokens: 12, output_tokens: 1 },
    },
  });
  sse(res, "content_block_start", {
    type: "content_block_start",
    index: 0,
    content_block: { type: "text", text: "" },
  });
  for (const piece of chunks(reply, 8)) {
    sse(res, "content_block_delta", {
      type: "content_block_delta",
      index: 0,
      delta: { type: "text_delta", text: piece },
    });
    await sleep(delayMs);
  }
  sse(res, "content_block_stop", { type: "content_block_stop", index: 0 });
  sse(res, "message_delta", {
    type: "message_delta",
    delta: { stop_reason: "end_turn", stop_sequence: null },
    usage: { output_tokens: 8 },
  });
  sse(res, "message_stop", { type: "message_stop" });
  res.end();
}

async function chatCompletions(res, body, { model, stream, reply, delayMs }) {
  const id = nextId("chatcmpl");
  const created = Math.floor(Date.now() / 1000);
  const usage = { prompt_tokens: 12, completion_tokens: 8, total_tokens: 20 };
  if (!stream) {
    return json(res, 200, {
      id,
      object: "chat.completion",
      created,
      model,
      choices: [
        {
          index: 0,
          message: { role: "assistant", content: reply },
          finish_reason: "stop",
        },
      ],
      usage,
    });
  }
  sseHead(res);
  const base = { id, object: "chat.completion.chunk", created, model };
  sse(res, null, { ...base, choices: [{ index: 0, delta: { role: "assistant", content: "" }, finish_reason: null }] });
  for (const piece of chunks(reply, 8)) {
    sse(res, null, { ...base, choices: [{ index: 0, delta: { content: piece }, finish_reason: null }] });
    await sleep(delayMs);
  }
  sse(res, null, { ...base, choices: [{ index: 0, delta: {}, finish_reason: "stop" }] });
  sse(res, null, { ...base, choices: [], usage });
  res.write("data: [DONE]\n\n");
  res.end();
}

async function responses(res, body, { model, stream, reply, delayMs }) {
  const responseId = nextId("resp");
  const messageId = nextId("msg");
  const createdAt = Math.floor(Date.now() / 1000);
  const outputItem = {
    id: messageId,
    type: "message",
    status: "completed",
    role: "assistant",
    content: [{ type: "output_text", text: reply, annotations: [] }],
  };
  const response = {
    id: responseId,
    object: "response",
    created_at: createdAt,
    status: "completed",
    model,
    output: [outputItem],
    parallel_tool_calls: true,
    tool_choice: "auto",
    tools: [],
    usage: { input_tokens: 12, output_tokens: 8, total_tokens: 20 },
  };
  if (!stream) return json(res, 200, response);

  sseHead(res);
  sse(res, "response.created", {
    type: "response.created",
    response: { ...response, status: "in_progress", output: [] },
  });
  sse(res, "response.output_item.added", {
    type: "response.output_item.added",
    output_index: 0,
    item: { id: messageId, type: "message", status: "in_progress", role: "assistant", content: [] },
  });
  sse(res, "response.content_part.added", {
    type: "response.content_part.added",
    item_id: messageId,
    output_index: 0,
    content_index: 0,
    part: { type: "output_text", text: "", annotations: [] },
  });
  for (const piece of chunks(reply, 8)) {
    sse(res, "response.output_text.delta", {
      type: "response.output_text.delta",
      item_id: messageId,
      output_index: 0,
      content_index: 0,
      delta: piece,
    });
    await sleep(delayMs);
  }
  sse(res, "response.output_text.done", {
    type: "response.output_text.done",
    item_id: messageId,
    output_index: 0,
    content_index: 0,
    text: reply,
  });
  sse(res, "response.content_part.done", {
    type: "response.content_part.done",
    item_id: messageId,
    output_index: 0,
    content_index: 0,
    part: { type: "output_text", text: reply, annotations: [] },
  });
  sse(res, "response.output_item.done", {
    type: "response.output_item.done",
    output_index: 0,
    item: outputItem,
  });
  sse(res, "response.completed", { type: "response.completed", response });
  res.end();
}

function chunks(text, size) {
  const out = [];
  for (let i = 0; i < text.length; i += size) out.push(text.slice(i, i + size));
  return out.length ? out : [""];
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const isMain = process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href;

if (isMain) {
  const args = process.argv.slice(2);
  const option = (name, fallback) => {
    const index = args.indexOf(name);
    return index >= 0 ? args[index + 1] : fallback;
  };
  const port = Number(option("--port", "0"));
  const host = option("--host", "127.0.0.1");
  const reply = option("--reply", process.env.CODA_GATEWAY_REPLY ?? DEFAULT_REPLY);
  const delayMs = Number(option("--delay-ms", "5"));
  const logFile = option("--log", undefined);
  const captureDir = option("--capture", undefined);
  const server = createGateway({ reply, delayMs, logFile, captureDir });
  server.listen(port, host, () => {
    const address = server.address();
    console.log(`LISTENING ${address.port}`);
    console.error(`mock LLM gateway on http://${host}:${address.port} reply=${reply}`);
  });
  const shutdown = () => server.close(() => process.exit(0));
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}
