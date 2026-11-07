/**
 * UI-101 / TUI-101 / TUI-102 / WEB-101 / WEB-102 契约测试。
 *
 * 自包含：本文件内联一个实现 handleRpc/subscribe 的 Mock Core，不依赖 core/ 或其他子代理产物；
 * 同一 Mock Core 场景分别驱动 TUI（script 模式，捕获 stdout 投影）与 Web（HTTP/SSE），
 * 比较 canonical 投影是否等价（允许渲染格式差异，不允许状态差异）。
 */

import test from "node:test";
import assert from "node:assert/strict";
import { Writable } from "node:stream";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import {
  METHODS,
  PROTOCOL_VERSION,
  RPC_ERRORS,
  checkProtocolVersion,
  welcome,
  rpcResult,
  rpcError,
} from "../../contracts/rpc.mjs";
import { RpcClient, createInProcessTransport } from "../../clients/shared/rpc-client.mjs";
import { MAX_TERMINAL_CHARS } from "../../clients/shared/projection.mjs";
import { runTui } from "../../clients/tui/main.mjs";
import { startWebServer, evaluateAccess } from "../../clients/web/server.mjs";

const BASE_TS = "2026-11-07T00:00:00.000Z";
const DIFF_TEXT = [
  "diff --git a/src/app.mjs b/src/app.mjs",
  "index 1111111..2222222 100644",
  "--- a/src/app.mjs",
  "+++ b/src/app.mjs",
  "@@ -1,3 +1,4 @@",
  " export function start() {",
  "-  return legacyStart();",
  "+  return startCore();",
  " }",
].join("\n");

const LONG_TERMINAL_OUTPUT = Array.from({ length: 900 }, (_, index) => {
  const n = String(index + 1).padStart(3, "0");
  return `[build ${n}] compiling module src/module-${n}.mjs ... ok`;
}).join("\n");

/**
 * 内联 Mock Core：实现 CONTRACT.md §8 的两个入口。
 * 事件 seq 严格 +1，快照/回放/缺口都按契约实现。
 */
function createInlineCore({ welcomeProtocol = null, failSendText = "FAIL_SEND" } = {}) {
  let seq = 0;
  let order = 4;
  let messageCounter = 4;
  let commandCounter = 0;
  let turnCounter = 0;

  const listeners = new Set();
  const events = [];
  const commands = [];
  const clone = (value) => JSON.parse(JSON.stringify(value));

  const capabilities = {
    provider: "mock",
    version: "mock-1.0.0",
    environment: "host",
    capabilities: {
      session: true,
      thread: true,
      turn: true,
      streaming: true,
      tools: true,
      approvals: true,
      plan: false,
      subagent: false,
      context: true,
      checkpoint: false,
      identity: true,
      attachments: true,
      steer: "experimental",
      terminal: true,
    },
  };

  const sessions = [
    {
      id: "ses_local",
      title: "本地会话",
      state: "idle",
      agent: "Claude Code",
      model: "claude-sonnet",
      workspace: "/work/demo",
      node: "node_local",
      environment: "host",
      updatedAt: BASE_TS,
      capabilities: { plan: false, steer: "experimental", approvals: true },
    },
    {
      id: "ses_remote",
      title: "远程会话",
      state: "idle",
      agent: "Codex",
      model: "gpt-5-codex",
      workspace: "/home/dev/demo",
      node: "node_remote",
      environment: "wsl:Ubuntu",
      updatedAt: BASE_TS,
      capabilities: { plan: true, steer: false, approvals: true, terminal: true },
    },
  ];

  const messages = [
    { id: "msg_1", sessionId: "ses_local", role: "user", text: "请检查构建失败原因", ts: BASE_TS, order: 1 },
    { id: "msg_2", sessionId: "ses_local", role: "assistant", text: "我先看一下日志。", ts: BASE_TS, order: 2 },
    { id: "msg_3", sessionId: "ses_remote", role: "user", text: "在 WSL 里跑一下构建", ts: BASE_TS, order: 3 },
    {
      id: "msg_4",
      sessionId: "ses_remote",
      role: "assistant",
      text: "构建已完成，详见附件。",
      ts: BASE_TS,
      order: 4,
      attachments: [{ id: "art_diff", artifactId: "art_diff", name: "src/app.mjs.diff", size: DIFF_TEXT.length, mime: "text/x-diff" }],
    },
  ];

  const interactions = [
    {
      id: "int_approval",
      sessionId: "ses_local",
      kind: "approval",
      prompt: "允许修改 src/app.mjs 吗？",
      options: [
        { id: "allow", label: "允许" },
        { id: "deny", label: "拒绝" },
      ],
      status: "pending",
      source: "node_local",
      expiresAt: "2026-11-07T01:00:00.000Z",
    },
    {
      id: "int_question",
      sessionId: "ses_remote",
      kind: "question",
      prompt: "选择部署目标",
      options: [
        { id: "staging", label: "staging" },
        { id: "prod", label: "production" },
      ],
      status: "pending",
      source: "node_remote",
      expiresAt: "2026-11-07T01:00:00.000Z",
    },
  ];

  const terminals = [
    {
      id: "term_1",
      sessionId: "ses_remote",
      title: "bash · WSL",
      status: "open",
      node: "node_remote",
      environment: "wsl:Ubuntu",
      output: LONG_TERMINAL_OUTPUT,
      fullLength: LONG_TERMINAL_OUTPUT.length,
      truncated: false,
    },
  ];

  const artifacts = [
    {
      id: "art_diff",
      sessionId: "ses_local",
      name: "src/app.mjs.diff",
      kind: "diff",
      mime: "text/x-diff",
      size: DIFF_TEXT.length,
      sha256: "a".repeat(64),
      node: "node_local",
      environment: "host",
      diff: DIFF_TEXT,
      content: DIFF_TEXT,
      meta: { hunks: 1, additions: 1, deletions: 1 },
    },
    {
      id: "art_file",
      sessionId: "ses_local",
      name: "report.txt",
      kind: "file",
      mime: "text/plain",
      size: 5,
      sha256: "b".repeat(64),
      node: "node_local",
      environment: "host",
      content: "hello",
      meta: null,
    },
  ];

  const notifications = [
    { id: "ntf_1", level: "info", text: "远程节点 node_remote 已连接", sessionId: null, ts: BASE_TS, read: false },
  ];

  const models = {
    current: "claude-sonnet",
    options: [
      { id: "claude-sonnet", label: "Claude Sonnet" },
      { id: "claude-opus", label: "Claude Opus" },
      { id: "gpt-5-codex", label: "GPT-5 Codex" },
    ],
  };

  function findSession(id) {
    return sessions.find((session) => session.id === id) ?? null;
  }

  function findInteraction(id) {
    return interactions.find((interaction) => interaction.id === id) ?? null;
  }

  function push(kind, payload, sessionId = null) {
    const event = { kind, sessionId, payload, seq: ++seq, ts: BASE_TS };
    events.push(event);
    for (const listener of [...listeners]) listener(event);
    return event;
  }

  function snapshot() {
    return {
      seq,
      sessions: clone(sessions),
      messages: clone(messages),
      interactions: clone(interactions),
      terminals: clone(terminals),
      artifacts: clone(artifacts),
      notifications: clone(notifications),
      models: { current: models.current, options: clone(models.options) },
      capabilities: clone(capabilities),
    };
  }

  function subscription(fromSeq = 0) {
    if (fromSeq <= 0) return { seq, snapshot: snapshot() };
    if (fromSeq > seq) return { seq, snapshot: snapshot(), gap: true };
    const oldest = events[0]?.seq ?? seq + 1;
    if (fromSeq < oldest - 1) return { seq, snapshot: snapshot(), gap: true };
    return { seq, events: events.filter((event) => event.seq > fromSeq), resumed: true };
  }

  async function handleRpc(message) {
    if (!message || message.jsonrpc !== "2.0" || typeof message.method !== "string") {
      return rpcError(message?.id, RPC_ERRORS.INVALID_REQUEST, "invalid request");
    }

    switch (message.method) {
      case METHODS.hello: {
        if (welcomeProtocol !== null) {
          return rpcResult(message.id, {
            ...welcome({ clientType: message.params?.clientType ?? "unknown" }),
            protocolVersion: welcomeProtocol,
          });
        }
        const check = checkProtocolVersion(message.params?.protocolVersion);
        if (!check.ok) return rpcError(message.id, check.code, check.message);
        return rpcResult(message.id, welcome({ clientType: message.params?.clientType ?? "unknown" }));
      }

      case METHODS.subscribe:
        return rpcResult(message.id, subscription(message.params?.fromSeq ?? 0));

      case METHODS.resume:
        return rpcResult(message.id, subscription(message.params?.fromSeq ?? 0));

      case METHODS.snapshot:
        return rpcResult(message.id, { seq, snapshot: snapshot() });

      case METHODS.diagnostics:
        return rpcResult(message.id, {
          protocolVersion: PROTOCOL_VERSION,
          seq,
          commands: commands.length,
        });

      case METHODS.command: {
        const commandId = message.params?.commandId ?? `cmd_inline_${++commandCounter}`;
        const kind = message.params?.kind;
        const target = message.params?.target ?? {};
        const payload = message.params?.payload ?? {};
        commands.push({ commandId, kind, target, payload });

        if (kind === "turn.send" || kind === "turn.steer") {
          const session = findSession(target.sessionId);
          if (!session) return rpcError(message.id, RPC_ERRORS.NOT_FOUND, "unknown session");
          const text = String(payload.text ?? "");

          if (text.includes(failSendText)) {
            session.state = "failed";
            session.updatedAt = BASE_TS;
            push("status", { sessionId: session.id, state: "failed", detail: "模拟发送失败" }, session.id);
            return rpcResult(message.id, {
              commandId,
              state: "failed",
              sessionId: session.id,
              error: { code: "MOCK_SEND_FAILED", message: "模拟发送失败" },
            });
          }

          session.state = "running";
          session.updatedAt = BASE_TS;
          push("session", { session: clone(session) }, session.id);

          const userMessage = {
            id: `msg_${++messageCounter}`,
            sessionId: session.id,
            role: "user",
            text,
            ts: BASE_TS,
            order: ++order,
          };
          messages.push(userMessage);
          push("message", { message: clone(userMessage) }, session.id);
          push("status", { sessionId: session.id, state: "running", detail: "已收到任务" }, session.id);

          const assistantId = `msg_${++messageCounter}`;
          const assistant = {
            id: assistantId,
            sessionId: session.id,
            role: "assistant",
            text: "正在处理：",
            streaming: true,
            ts: BASE_TS,
            order: ++order,
          };
          messages.push(assistant);
          push("message", { delta: true, message: clone(assistant) }, session.id);

          assistant.text += text;
          push("message", { delta: true, message: { id: assistantId, sessionId: session.id, role: "assistant", text } }, session.id);

          assistant.text = `已完成：${text}`;
          assistant.streaming = false;
          assistant.attachments = [{ id: "art_diff", artifactId: "art_diff", name: "src/app.mjs.diff", size: DIFF_TEXT.length }];
          push("message", { message: clone(assistant) }, session.id);

          session.state = "idle";
          session.turnState = "completed";
          session.updatedAt = BASE_TS;
          push("turn", { turn: { id: `turn_${++turnCounter}`, sessionId: session.id, state: "completed" } }, session.id);
          push("session", { session: clone(session) }, session.id);

          return rpcResult(message.id, { commandId, state: "succeeded", sessionId: session.id });
        }

        if (kind === "turn.stop") {
          const session = findSession(target.sessionId);
          if (!session) return rpcError(message.id, RPC_ERRORS.NOT_FOUND, "unknown session");
          session.state = "stopped";
          session.turnState = "stopped";
          session.updatedAt = BASE_TS;
          push("status", { sessionId: session.id, state: "stopped", detail: "用户停止" }, session.id);
          push("turn", { turn: { id: `turn_${++turnCounter}`, sessionId: session.id, state: "stopped" } }, session.id);
          push("session", { session: clone(session) }, session.id);
          return rpcResult(message.id, { commandId, state: "succeeded", sessionId: session.id });
        }

        if (kind === "interaction.answer") {
          const interaction = findInteraction(target.interactionId ?? payload.interactionId);
          if (!interaction) return rpcError(message.id, RPC_ERRORS.NOT_FOUND, "unknown interaction");
          if (interaction.status !== "pending") {
            return rpcResult(message.id, {
              commandId,
              state: "superseded",
              duplicate: true,
              interactionId: interaction.id,
            });
          }
          interaction.status = "answered";
          interaction.answer = payload.answer ?? payload;
          push("interaction", { interaction: clone(interaction) }, interaction.sessionId);
          return rpcResult(message.id, {
            commandId,
            state: "succeeded",
            interactionId: interaction.id,
          });
        }

        if (kind === "model.set") {
          const session = findSession(target.sessionId);
          if (!session) return rpcError(message.id, RPC_ERRORS.NOT_FOUND, "unknown session");
          if (!models.options.some((option) => option.id === payload.model)) {
            return rpcError(message.id, RPC_ERRORS.UNSUPPORTED_CAPABILITY, "unknown model");
          }
          session.model = payload.model;
          session.updatedAt = BASE_TS;
          models.current = payload.model;
          push("model", { model: { id: payload.model, sessionId: session.id } }, session.id);
          push("session", { session: clone(session) }, session.id);
          return rpcResult(message.id, { commandId, state: "succeeded", model: payload.model });
        }

        return rpcError(message.id, RPC_ERRORS.UNSUPPORTED_CAPABILITY, `mock does not implement ${kind}`);
      }

      default:
        return rpcError(message.id, RPC_ERRORS.METHOD_NOT_FOUND, `unknown method ${message.method}`);
    }
  }

  return {
    handleRpc,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    close() {
      listeners.clear();
    },
    snapshot,
    get seq() {
      return seq;
    },
    get commands() {
      return commands;
    },
    get events() {
      return events;
    },
  };
}

function captureWritable() {
  const chunks = [];
  const stream = new Writable({
    write(chunk, _encoding, callback) {
      chunks.push(Buffer.from(chunk));
      callback();
    },
  });
  stream.text = () => Buffer.concat(chunks).toString("utf8");
  return stream;
}

function parseRecords(text) {
  return text
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return null;
      }
    })
    .filter(Boolean);
}

function finalProjection(records) {
  for (let i = records.length - 1; i >= 0; i -= 1) {
    if (records[i].type === "projection") return records[i].value;
  }
  return null;
}

async function runScript(core, steps, options = {}) {
  const stdout = captureWritable();
  const result = await runTui({
    core,
    script: steps,
    stdout,
    stdin: { isTTY: false },
    color: false,
    ...options,
  });
  const records = parseRecords(stdout.text());
  return { result, records, stdout: stdout.text(), projection: finalProjection(records) };
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function fetchJson(url, { method = "GET", body = undefined, token = "test-token", headers = {} } = {}) {
  const response = await fetch(url, {
    method,
    headers: {
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let json = null;
  try {
    json = await response.json();
  } catch {
    json = null;
  }
  return { status: response.status, headers: response.headers, json };
}

function parseSseBlock(block) {
  let event = "message";
  let id = null;
  const dataLines = [];
  for (const line of block.split("\n")) {
    if (line.startsWith(":")) continue;
    const colon = line.indexOf(":");
    if (colon < 0) continue;
    const key = line.slice(0, colon);
    let value = line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (key === "event") event = value;
    else if (key === "id") id = value;
    else if (key === "data") dataLines.push(value);
  }
  if (!dataLines.length) return null;
  const text = dataLines.join("\n");
  let data = text;
  try {
    data = JSON.parse(text);
  } catch {
    /* keep raw text */
  }
  return { event, id, data };
}

function startSse(url, { token = "test-token" } = {}) {
  const controller = new AbortController();
  const records = [];
  const target = new URL(url);
  if (token) target.searchParams.set("token", token);
  const done = (async () => {
    let response;
    try {
      response = await fetch(target, { signal: controller.signal });
    } catch (err) {
      if (err.name === "AbortError") return;
      throw err;
    }
    if (!response.ok) throw new Error(`SSE HTTP ${response.status}`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    try {
      while (true) {
        const { value, done: streamDone } = await reader.read();
        if (streamDone) break;
        buffer += decoder.decode(value, { stream: true });
        let index;
        while ((index = buffer.indexOf("\n\n")) >= 0) {
          const block = buffer.slice(0, index);
          buffer = buffer.slice(index + 2);
          const parsed = parseSseBlock(block);
          if (parsed) records.push(parsed);
        }
      }
    } catch (err) {
      if (err.name !== "AbortError") throw err;
    }
  })();
  done.catch(() => {
    /* close() 会收集真实错误；这里避免未处理 rejection 干扰测试进程 */
  });
  return {
    records,
    done,
    async waitFor(predicate, timeout = 3000) {
      const started = Date.now();
      while (Date.now() - started < timeout) {
        const hit = records.find(predicate);
        if (hit) return hit;
        await delay(10);
      }
      throw new Error(`SSE wait timeout; events=${records.map((record) => record.event).join(",")}`);
    },
    async close() {
      controller.abort();
      await done.catch(() => {});
    },
  };
}

async function startTestServer(core, options = {}) {
  const logs = [];
  const server = await startWebServer({
    core,
    port: 0,
    token: "test-token",
    log: (line) => logs.push(line),
    ...options,
  });
  return { server, logs };
}

test("协议版本不匹配：客户端与核心两侧都拒绝", async () => {
  const asClient = await runScript(createInlineCore(), [{ action: "projection" }], { protocolVersion: 2 });
  assert.equal(asClient.result.exitCode, 1);
  const clientError = asClient.records.find((record) => record.type === "error" && record.phase === "connect");
  assert.ok(clientError, "应记录 connect 错误");
  assert.equal(clientError.code, RPC_ERRORS.VERSION_MISMATCH);

  const asCore = await runScript(createInlineCore({ welcomeProtocol: 2 }), [{ action: "projection" }]);
  assert.equal(asCore.result.exitCode, 1);
  const coreError = asCore.records.find((record) => record.type === "error" && record.phase === "connect");
  assert.ok(coreError);
  assert.equal(coreError.code, RPC_ERRORS.VERSION_MISMATCH);
  assert.equal(finalProjection(asCore.records).protocol.negotiated, false);
});

test("快照 + 断点续传 + 缺口重新对齐", async () => {
  const core = createInlineCore();
  const first = new RpcClient({ transport: createInProcessTransport(core), clientType: "tui" });
  const second = new RpcClient({ transport: createInProcessTransport(core), clientType: "web" });
  try {
    const opened = await first.open({ fromSeq: 0 });
    assert.equal(opened.mode, "snapshot");
    const baseSeq = first.projection.seq;

    await first.command("turn.send", { sessionId: "ses_local" }, { text: "断点续传测试", mode: "send" });
    const afterSeq = first.projection.seq;
    assert.ok(afterSeq > baseSeq);

    const replay = await first.resume({ fromSeq: afterSeq - 2 });
    assert.equal(replay.mode, "events");
    assert.ok(replay.events >= 2);

    const gap = await first.resume({ fromSeq: afterSeq + 1000 });
    assert.equal(gap.mode, "snapshot");
    assert.equal(first.projection.seq, core.seq);

    const fresh = await second.open({ fromSeq: 0 });
    assert.equal(fresh.mode, "snapshot");
    const partial = await second.resume({ fromSeq: afterSeq - 2 });
    assert.equal(partial.mode, "events");
    const realigned = await second.resume({ fromSeq: 999999 });
    assert.equal(realigned.mode, "snapshot");
    assert.equal(second.projection.seq, core.seq);
    assert.deepEqual(second.canonicalProjection(), first.canonicalProjection());
  } finally {
    first.close();
    second.close();
  }
});

test("UI-101：TUI 与 Web 在同一 Mock Core 上得到等价投影", async () => {
  const core = createInlineCore();
  const { server, logs } = await startTestServer(core);
  const sse = startSse(`${server.url}api/events`);
  try {
    assert.ok(logs.some((line) => /^LISTENING \d+$/.test(line)), "应打印 LISTENING <port>");
    await sse.waitFor((record) => record.event === "snapshot");

    const run = await runScript(core, [
      { action: "select", sessionId: "ses_local" },
      { action: "send", text: "检查构建" },
      { action: "wait", until: { assistantReply: true } },
      { action: "answer", interactionId: "int_approval", optionId: "allow" },
      { action: "model", model: "claude-opus" },
      { action: "stop", sessionId: "ses_local" },
      { action: "projection" },
    ]);

    assert.equal(run.result.exitCode, 0);
    const tuiProjection = run.projection;
    assert.ok(tuiProjection);
    assert.equal(tuiProjection.connection.status, "ready");
    assert.equal(tuiProjection.sessions.find((session) => session.id === "ses_local").state, "stopped");
    assert.equal(tuiProjection.sessions.find((session) => session.id === "ses_local").model, "claude-opus");
    assert.equal(tuiProjection.interactions.find((item) => item.id === "int_approval").status, "answered");
    assert.ok(tuiProjection.messages.some((message) => message.text.includes("已完成：检查构建")));

    const deltas = run.records.filter((record) => record.type === "event" && record.kind === "message" && record.delta);
    assert.ok(deltas.length >= 2, "应捕获 message delta（流式输出）");

    const finalSeq = tuiProjection.connection.seq;
    await sse.waitFor(
      (record) => record.event === "event" && Number(record.id) >= finalSeq,
      5000,
    );

    const state = await fetchJson(`${server.url}api/state`);
    assert.equal(state.status, 200);
    assert.deepEqual(state.json.state, tuiProjection);
  } finally {
    await sse.close();
    await server.close();
  }
});

test("发送失败恢复草稿；成功发送后清除", async () => {
  const core = createInlineCore();
  const run = await runScript(core, [
    { action: "select", sessionId: "ses_local" },
    { action: "send", text: "FAIL_SEND 这条会失败" },
    { action: "send", text: "这条会成功" },
    { action: "projection" },
  ]);

  const sends = run.records.filter((record) => record.type === "send");
  assert.equal(sends.length, 2);
  assert.equal(sends[0].ok, false);
  assert.equal(sends[0].state, "failed");
  assert.equal(sends[0].draftRetained, "FAIL_SEND 这条会失败");
  assert.equal(sends[1].ok, true);
  assert.equal(core.commands.filter((command) => command.kind === "turn.send").length, 2);

  const allText = run.projection.messages.map((message) => message.text).join("\n");
  assert.ok(!allText.includes("FAIL_SEND"), "失败的发送不应产生消息");
});

test("审批回答去重：TUI 与 Web 各自只向核心发送一次", async () => {
  const tuiCore = createInlineCore();
  const tuiRun = await runScript(tuiCore, [
    { action: "answer", interactionId: "int_approval", optionId: "allow" },
    { action: "answer", interactionId: "int_approval", optionId: "deny" },
    { action: "projection" },
  ]);
  const tuiAnswers = tuiRun.records.filter((record) => record.type === "answer");
  assert.equal(tuiAnswers[0].duplicate, false);
  assert.equal(tuiAnswers[1].duplicate, true);
  assert.equal(tuiCore.commands.filter((command) => command.kind === "interaction.answer").length, 1);

  const webCore = createInlineCore();
  const { server } = await startTestServer(webCore);
  try {
    const first = await fetchJson(`${server.url}api/answer`, {
      method: "POST",
      body: { interactionId: "int_approval", optionId: "allow" },
    });
    const second = await fetchJson(`${server.url}api/answer`, {
      method: "POST",
      body: { interactionId: "int_approval", optionId: "deny" },
    });
    assert.equal(first.status, 200);
    assert.equal(first.json.duplicate, false);
    assert.equal(second.status, 200);
    assert.equal(second.json.duplicate, true);
    assert.equal(webCore.commands.filter((command) => command.kind === "interaction.answer").length, 1);
  } finally {
    await server.close();
  }
});

test("排队与插话分别映射到 turn.send(mode=queue) / turn.steer", async () => {
  const core = createInlineCore();
  await runScript(core, [
    { action: "queue", text: "排队消息" },
    { action: "steer", text: "插话消息" },
  ]);
  const sends = core.commands.filter(
    (command) => command.kind === "turn.send" || command.kind === "turn.steer",
  );
  assert.equal(sends.length, 2);
  assert.equal(sends[0].kind, "turn.send");
  assert.equal(sends[0].payload.mode, "queue");
  assert.equal(sends[1].kind, "turn.steer");
  assert.equal(sends[1].payload.text, "插话消息");
});

test("停止状态：会话/回合都来自核心并显示已停止", async () => {
  const core = createInlineCore();
  const run = await runScript(core, [
    { action: "select", sessionId: "ses_local" },
    { action: "stop" },
    { action: "projection" },
    { action: "render", view: "status" },
  ]);

  const session = run.projection.sessions.find((item) => item.id === "ses_local");
  assert.equal(session.state, "stopped");
  assert.equal(session.turnState, "stopped");
  const statusRender = run.records.find((record) => record.type === "render" && record.view === "status");
  assert.ok(statusRender);
  assert.match(statusRender.text, /已停止/);
  assert.equal(core.commands.filter((command) => command.kind === "turn.stop").length, 1);
});

test("WEB-001：SSE 刷新恢复，关闭 UI/服务不停止核心", async () => {
  const core = createInlineCore();
  let server = (await startTestServer(core)).server;
  const driver = new RpcClient({ transport: createInProcessTransport(core), clientType: "test-driver" });
  try {
    await driver.open();
    await driver.command("turn.send", { sessionId: "ses_local" }, { text: "预热消息", mode: "send" });

    const before = await fetchJson(`${server.url}api/state`);
    const beforeSeq = before.json.seq;
    assert.ok(beforeSeq > 0);

    const sse = startSse(`${server.url}api/events?fromSeq=${beforeSeq}`);
    try {
      await sse.waitFor((record) => record.event === "resumed" || record.event === "snapshot");
      await driver.command("turn.send", { sessionId: "ses_local" }, { text: "刷新后可见", mode: "send" });
      await sse.waitFor(
        (record) =>
          record.event === "event" &&
          record.data?.event?.kind === "message" &&
          String(record.data?.event?.payload?.message?.text ?? "").includes("刷新后可见"),
        5000,
      );
    } finally {
      await sse.close();
    }

    const after = await fetchJson(`${server.url}api/state`);
    assert.ok(after.json.state.messages.some((message) => message.text.includes("刷新后可见")));

    // 用刷新后的 seq 重连：事件缓冲仍连续则回放，而不是丢事件。
    const replay = startSse(`${server.url}api/events?fromSeq=${beforeSeq}`);
    try {
      await replay.waitFor((record) => record.event === "resumed");
      const replayed = replay.records.filter((record) => record.event === "event");
      assert.ok(replayed.length > 0, "应回放断点后的事件");
    } finally {
      await replay.close();
    }

    // 关闭 UI（Web 服务）后核心继续；重新打开看到真实状态。
    await server.close();
    server = null;
    const reopened = await startTestServer(core);
    try {
      const state = await fetchJson(`${reopened.server.url}api/state`);
      assert.ok(state.json.state.messages.some((message) => message.text.includes("刷新后可见")));
      assert.ok(state.json.state.connection.seq >= beforeSeq);
    } finally {
      await reopened.server.close();
    }
  } finally {
    driver.close();
    if (server) await server.close();
  }
});

test("WEB-002：未授权访问被拒绝，局域网默认无管理权限", async () => {
  const url = new URL("http://127.0.0.1/api/state");
  assert.equal(
    evaluateAccess({ remoteAddress: "192.168.1.9", url, token: "t" }).ok,
    false,
    "远程无令牌必须拒绝",
  );
  const remoteWithToken = evaluateAccess({
    remoteAddress: "192.168.1.9",
    url,
    token: "t",
    headers: { authorization: "Bearer t" },
  });
  assert.equal(remoteWithToken.ok, false);
  assert.equal(remoteWithToken.status, 403);
  assert.equal(
    evaluateAccess({
      remoteAddress: "192.168.1.9",
      url,
      token: "t",
      allowRemote: true,
      headers: { authorization: "Bearer t" },
    }).ok,
    true,
  );
  assert.equal(evaluateAccess({ remoteAddress: "127.0.0.1", url, token: "t" }).status, 401);
  assert.equal(
    evaluateAccess({ remoteAddress: "127.0.0.1", url, token: "t", allowLocalAnonymous: true }).ok,
    true,
  );

  const core = createInlineCore();
  const { server } = await startTestServer(core);
  try {
    const unauthorized = await fetchJson(`${server.url}api/state`, { token: null });
    assert.equal(unauthorized.status, 401);
    const authorized = await fetchJson(`${server.url}api/state`);
    assert.equal(authorized.status, 200);
    const eventsUnauthorized = await fetch(`${server.url}api/events`);
    assert.equal(eventsUnauthorized.status, 401);
    await eventsUnauthorized.body?.cancel?.();
    const shell = await fetch(server.url);
    assert.equal(shell.status, 200);
    assert.match(await shell.text(), /Coda Web UI/);
  } finally {
    await server.close();
  }
});

test("TUI 非 TTY 且无 script 时安全退出", async () => {
  const stdout = captureWritable();
  const result = await runTui({
    core: createInlineCore(),
    stdout,
    stdin: { isTTY: false },
  });
  assert.equal(result.exitCode, 0);
  assert.equal(result.reason, "non-tty");
  assert.match(stdout.text(), /非交互终端/);
  assert.equal(result.projection, null);
});

test("TUI-102：终端/Diff/附件/远程来源渲染与保存", async () => {
  const core = createInlineCore();
  const dir = await mkdtemp(path.join(os.tmpdir(), "coda-tui-"));
  try {
    const run = await runScript(core, [
      { action: "render", view: "terminal", id: "term_1", maxLines: 5 },
      { action: "render", view: "diff", id: "art_diff" },
      { action: "render", view: "attachments", sessionId: "ses_remote" },
      { action: "render", view: "status" },
      { action: "saveArtifact", id: "art_diff", path: path.join(dir, "change.diff") },
      { action: "saveTerminal", id: "term_1", path: path.join(dir, "term.log") },
    ]);

    const renders = run.records.filter((record) => record.type === "render");
    const terminalText = renders.find((record) => record.view === "terminal").text;
    assert.match(terminalText, /输出已截断/);
    assert.match(terminalText, /远程节点 node_remote/);
    assert.match(terminalText, /save-terminal/);
    assert.match(terminalText, /外部终端/);

    const diffText = renders.find((record) => record.view === "diff").text;
    assert.match(diffText, /diff --git/);
    assert.match(diffText, /SHA-256/);
    assert.match(diffText, /save-artifact/);

    const attachmentsText = renders.find((record) => record.view === "attachments").text;
    assert.match(attachmentsText, /src\/app\.mjs\.diff/);

    const statusText = renders.find((record) => record.view === "status").text;
    assert.match(statusText, /plan=不支持/);
    assert.match(statusText, /远程节点 node_remote/);

    const saved = run.records.filter((record) => record.type === "saved");
    assert.equal(saved.length, 2);
    assert.ok(saved.every((record) => record.ok));
    const savedDiff = await readFile(path.join(dir, "change.diff"), "utf8");
    assert.match(savedDiff, /diff --git/);
    const savedTerminal = await readFile(path.join(dir, "term.log"), "utf8");
    assert.ok(savedTerminal.length > 0);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("WEB-102：终端有界回放、Diff/成果元数据与下载", async () => {
  const core = createInlineCore();
  const { server } = await startTestServer(core);
  try {
    const state = await fetchJson(`${server.url}api/state`);
    assert.equal(state.status, 200);
    const terminal = state.json.state.terminals.find((item) => item.id === "term_1");
    assert.ok(terminal);
    assert.equal(terminal.truncated, true);
    assert.ok(terminal.output.length <= MAX_TERMINAL_CHARS);
    assert.ok(terminal.fullLength > MAX_TERMINAL_CHARS);
    assert.match(terminal.output, /build 900/);

    const artifact = state.json.state.artifacts.find((item) => item.id === "art_diff");
    assert.match(artifact.diff, /diff --git/);
    assert.ok(artifact.sha256);

    const terminalDetail = await fetchJson(`${server.url}api/terminal/term_1`);
    assert.equal(terminalDetail.status, 200);
    assert.equal(terminalDetail.json.terminal.truncated, true);

    const meta = await fetchJson(`${server.url}api/artifacts/art_diff`);
    assert.equal(meta.status, 200);
    assert.equal(meta.json.artifact.name, "src/app.mjs.diff");
    assert.equal(meta.json.artifact.kind, "diff");

    const download = await fetch(`${server.url}api/artifacts/art_diff/download?token=test-token`);
    assert.equal(download.status, 200);
    assert.match(download.headers.get("content-disposition"), /attachment/);
    assert.match(await download.text(), /diff --git/);

    const fileDownload = await fetch(`${server.url}api/artifacts/art_file/download?token=test-token`);
    assert.equal(fileDownload.status, 200);
    assert.equal(await fileDownload.text(), "hello");

    const app = await fetch(`${server.url}app.mjs`);
    assert.equal(app.status, 200);
    assert.match(app.headers.get("content-type"), /text\/javascript/);
    assert.match(await app.text(), /\/api\/state/);
  } finally {
    await server.close();
  }
});
