#!/usr/bin/env node
/**
 * TUI-101 / TUI-102：终端客户端（纯终端环境，不依赖 GUI）。
 *
 * 连接方式：
 *   node clients/tui/main.mjs --core stdio:<cmd>     # 连接真实/外部核心
 *   node clients/tui/main.mjs --mock                 # 内置确定性 Mock Core（演示/离线）
 *   node clients/tui/main.mjs --mock --script '<json>'  # 可编程驱动（测试/脚本）
 *
 * 非 TTY 且未提供 --script 时安全退出（不读取 stdin、不启动交互循环）。
 * 客户端只使用 CONTRACT.md §8 的 handleRpc/subscribe，没有 Agent 名称分支。
 */

import { readFile, writeFile } from "node:fs/promises";
import readline from "node:readline";
import { pathToFileURL } from "node:url";

import {
  RpcClient,
  RpcError,
  createInProcessTransport,
  createStdioTransport,
} from "../shared/rpc-client.mjs";
import { createMockCore } from "../shared/mock-core.mjs";
import {
  safeText,
  resolveUiOptions,
  renderHeader,
  renderFooter,
  renderSessions,
  renderSessionHeader,
  renderMessages,
  renderInteractions,
  renderTerminalList,
  renderTerminal,
  renderDiff,
  renderArtifacts,
  renderMessageAttachments,
  renderStatus,
  renderHelp,
} from "./views.mjs";

const USAGE = [
  "Coda TUI",
  "",
  "用法:",
  "  node clients/tui/main.mjs --core stdio:<cmd>",
  "  node clients/tui/main.mjs --mock",
  "  node clients/tui/main.mjs --mock --script '<json|path>'",
  "",
  "选项:",
  "  --core stdio:<cmd>     以 stdio 行 JSON 启动/连接核心",
  "  --mock                 使用内置 Mock Core（离线演示）",
  "  --script <json|path>   可编程驱动：JSON 数组或文件路径",
  "  --from-seq <n>         断点续传起始 seq（默认 0）",
  "  --protocol-version <n> 客户端协议版本（默认 1）",
  "  --timeout-ms <n>       RPC 超时（默认 15000）",
  "  --no-color             关闭 ANSI（非 TTY 自动关闭）",
  "  --help                 帮助",
  "",
  "示例 script:",
  '  [{"action":"select","sessionId":"ses_local"},{"action":"send","text":"检查构建"},',
  '   {"action":"wait","until":{"assistantReply":true}},{"action":"projection"}]',
].join("\n");

export function parseArgs(argv = []) {
  const args = {
    core: null,
    mock: false,
    script: null,
    scriptFile: null,
    fromSeq: 0,
    protocolVersion: undefined,
    timeoutMs: undefined,
    color: null,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--core") args.core = argv[++i] ?? null;
    else if (arg.startsWith("--core=")) args.core = arg.slice("--core=".length);
    else if (arg === "--mock") args.mock = true;
    else if (arg === "--script") args.script = argv[++i] ?? null;
    else if (arg.startsWith("--script=")) args.script = arg.slice("--script=".length);
    else if (arg === "--script-file") args.scriptFile = argv[++i] ?? null;
    else if (arg.startsWith("--script-file=")) args.scriptFile = arg.slice("--script-file=".length);
    else if (arg === "--from-seq") args.fromSeq = Number(argv[++i] ?? 0);
    else if (arg.startsWith("--from-seq=")) args.fromSeq = Number(arg.slice("--from-seq=".length));
    else if (arg === "--protocol-version") args.protocolVersion = Number(argv[++i]);
    else if (arg.startsWith("--protocol-version=")) args.protocolVersion = Number(arg.slice("--protocol-version=".length));
    else if (arg === "--timeout-ms") args.timeoutMs = Number(argv[++i]);
    else if (arg.startsWith("--timeout-ms=")) args.timeoutMs = Number(arg.slice("--timeout-ms=".length));
    else if (arg === "--color") args.color = true;
    else if (arg === "--no-color") args.color = false;
    else if (arg === "--help" || arg === "-h") args.help = true;
  }
  return args;
}

function parseScriptText(text) {
  const value = JSON.parse(text);
  if (Array.isArray(value)) return value;
  if (value && Array.isArray(value.steps)) return value.steps;
  throw new Error("script 必须是 JSON 数组或 { steps: [...] }");
}

/** 把内联 JSON / 文件路径 / 数组统一成步骤数组；null 表示交互模式。 */
export async function resolveScript(script) {
  if (script == null) return null;
  if (Array.isArray(script)) return script;
  if (typeof script === "object" && Array.isArray(script.steps)) return script.steps;
  if (typeof script === "string") {
    const trimmed = script.trim();
    if (!trimmed) return [];
    if (trimmed.startsWith("[") || trimmed.startsWith("{")) return parseScriptText(trimmed);
    return parseScriptText(await readFile(script, "utf8"));
  }
  throw new Error("无效的 --script（需要 JSON 字符串或文件路径）");
}

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function lastLine(text) {
  const lines = safeText(text).split("\n");
  return lines[lines.length - 1] ?? "";
}

function findArtifact(projection, id) {
  return (projection?.artifacts ?? []).find((a) => a.id === id) ?? null;
}

function findTerminal(projection, id) {
  return (projection?.terminals ?? []).find((t) => t.id === id) ?? null;
}

export async function runTui({
  core = null,
  transport = null,
  client = null,
  script = null,
  stdout = process.stdout,
  stdin = process.stdin,
  fromSeq = 0,
  protocolVersion,
  clientVersion = "0.1.0",
  timeoutMs,
  color = null,
  useAnsi = null,
} = {}) {
  const steps = await resolveScript(script);
  const scriptMode = Array.isArray(steps);
  const write = (text) => stdout.write(text);

  if (!scriptMode && stdin?.isTTY !== true) {
    write(
      "coda tui: 非交互终端，已安全退出（不读取 stdin、不启动核心/终端）。\n" +
        "使用 --script <json> 进行可编程驱动，或在真实 TTY 中运行。\n",
    );
    return { exitCode: 0, reason: "non-tty", projection: null };
  }

  let rpc = client;
  let ownRpc = false;
  if (!rpc) {
    let resolvedTransport = transport;
    if (!resolvedTransport) {
      if (core) resolvedTransport = createInProcessTransport(core);
      else {
        write("coda tui: 需要 --core stdio:<cmd> 或 --mock\n");
        return { exitCode: 2, reason: "no-core" };
      }
    }
    rpc = new RpcClient({
      transport: resolvedTransport,
      clientType: "tui",
      clientVersion,
      ...(Number.isInteger(protocolVersion) ? { protocolVersion } : {}),
      ...(Number.isFinite(timeoutMs) ? { timeoutMs } : {}),
    });
    ownRpc = true;
  }

  const drafts = new Map();
  const answered = new Set();
  let selectedSessionId = null;

  const projection = () => rpc.canonicalProjection();
  const record = (value) => {
    if (scriptMode) write(`${JSON.stringify(value)}\n`);
  };

  function ensureSession(explicit = null) {
    if (explicit) {
      selectedSessionId = explicit;
      return explicit;
    }
    const p = projection();
    if (selectedSessionId && p.sessions.some((s) => s.id === selectedSessionId)) return selectedSessionId;
    selectedSessionId = p.sessions[0]?.id ?? null;
    return selectedSessionId;
  }

  async function performCreate(step = {}) {
    try {
      const result = await rpc.command("session.create", {}, {
        title: step.title ?? null,
        workspace: step.workspace ?? null,
        agent: step.agent ?? null,
        model: step.model ?? null,
      });
      const sessionId = result?.sessionId ?? result?.result?.sessionId ?? null;
      if (sessionId) selectedSessionId = sessionId;
      return { ok: true, sessionId };
    } catch (err) {
      return { ok: false, code: err.code ?? 0, message: err.message ?? String(err) };
    }
  }

  async function performSend(text, mode, extra = {}) {
    const sessionId = ensureSession(extra.sessionId ?? null);
    if (!sessionId) return { ok: false, code: "NO_SESSION", message: "没有可发送的会话" };
    const body = String(text ?? "");
    if (!body.trim()) return { ok: false, code: "EMPTY_MESSAGE", message: "消息为空" };
    drafts.set(sessionId, { text: body, attachments: extra.attachments ?? [] });
    const kind = mode === "steer" ? "turn.steer" : "turn.send";
    let result;
    try {
      result = await rpc.command(kind, { sessionId }, {
        text: body,
        mode,
        ...(extra.attachments ? { attachments: extra.attachments } : {}),
      });
    } catch (err) {
      return {
        ok: false,
        sessionId,
        text: body,
        code: err.code ?? 0,
        message: err.message ?? String(err),
        draftRetained: drafts.get(sessionId)?.text ?? body,
      };
    }
    const state = result.state ?? "accepted";
    if (state === "failed" || state === "unknown") {
      return {
        ok: false,
        sessionId,
        text: body,
        commandId: result.commandId ?? null,
        state,
        code: result.error?.code ?? "SEND_FAILED",
        message: result.error?.message ?? "发送失败",
        draftRetained: drafts.get(sessionId)?.text ?? body,
      };
    }
    drafts.delete(sessionId);
    return { ok: true, sessionId, text: body, commandId: result.commandId ?? null, state, mode };
  }

  async function performAnswer(interactionId, answer) {
    if (!interactionId) return { ok: false, code: "INVALID_PARAMS", message: "缺少 interactionId" };
    if (answered.has(interactionId)) {
      return { ok: true, duplicate: true, interactionId };
    }
    let result;
    try {
      result = await rpc.command("interaction.answer", { interactionId }, { answer });
    } catch (err) {
      return { ok: false, interactionId, code: err.code ?? 0, message: err.message ?? String(err) };
    }
    if (result.state === "failed" || result.state === "unknown") {
      return {
        ok: false,
        interactionId,
        state: result.state,
        code: result.error?.code ?? "ANSWER_FAILED",
        message: result.error?.message ?? "回答失败",
      };
    }
    answered.add(interactionId);
    return {
      ok: true,
      duplicate: result.duplicate === true,
      interactionId,
      state: result.state ?? "succeeded",
    };
  }

  async function performStop(sessionId = null) {
    const id = ensureSession(sessionId);
    if (!id) return { ok: false, code: "NO_SESSION", message: "没有可停止的会话" };
    try {
      const result = await rpc.command("turn.stop", { sessionId: id }, {});
      return { ok: result.state !== "failed" && result.state !== "unknown", sessionId: id, state: result.state ?? "accepted" };
    } catch (err) {
      return { ok: false, sessionId: id, code: err.code ?? 0, message: err.message ?? String(err) };
    }
  }

  async function performModel(model, sessionId = null) {
    const id = ensureSession(sessionId);
    if (!id) return { ok: false, code: "NO_SESSION", message: "没有会话" };
    try {
      const result = await rpc.command("model.set", { sessionId: id }, { model });
      return { ok: result.state !== "failed", sessionId: id, model, state: result.state ?? "accepted" };
    } catch (err) {
      return { ok: false, sessionId: id, model, code: err.code ?? 0, message: err.message ?? String(err) };
    }
  }

  async function saveTerminal(id, filePath) {
    const terminal = findTerminal(projection(), id);
    if (!terminal) return { ok: false, code: "NOT_FOUND", message: `未知终端 ${id}` };
    if (!filePath) return { ok: false, code: "INVALID_PARAMS", message: "缺少保存路径" };
    const output = terminal.output ?? "";
    await writeFile(filePath, output, "utf8");
    return { ok: true, id, path: filePath, bytes: Buffer.byteLength(output), truncated: terminal.truncated };
  }

  async function saveArtifact(id, filePath) {
    const artifact = findArtifact(projection(), id);
    if (!artifact) return { ok: false, code: "NOT_FOUND", message: `未知成果 ${id}` };
    if (!filePath) return { ok: false, code: "INVALID_PARAMS", message: "缺少保存路径" };
    const content = artifact.diff ?? "";
    await writeFile(filePath, content, "utf8");
    return { ok: true, id, path: filePath, bytes: Buffer.byteLength(content), truncated: artifact.diffTruncated };
  }

  function matchesUntil(until) {
    if (!until || typeof until !== "object") return false;
    const p = projection();
    if (until.seqAtLeast !== undefined) return p.connection.seq >= Number(until.seqAtLeast);
    if (until.messageTextIncludes !== undefined) {
      return p.messages.some((m) => m.text.includes(String(until.messageTextIncludes)));
    }
    if (until.messageCountAtLeast !== undefined) return p.messages.length >= Number(until.messageCountAtLeast);
    if (until.assistantReply !== undefined) {
      const sessionId = until.sessionId ?? selectedSessionId;
      return p.messages.some((m) => m.role === "assistant" && (!sessionId || m.sessionId === sessionId) && !m.streaming);
    }
    if (until.sessionState !== undefined) {
      const target = typeof until.sessionState === "object" ? until.sessionState : { state: until.sessionState };
      return p.sessions.some(
        (s) => (!target.id || s.id === target.id) && s.state === target.state && (!target.sessionId || s.id === target.sessionId),
      );
    }
    if (until.interactionStatus !== undefined) {
      const target = typeof until.interactionStatus === "object" ? until.interactionStatus : {};
      return p.interactions.some((i) => (!target.id || i.id === target.id) && i.status === target.status);
    }
    if (until.terminalId !== undefined) return p.terminals.some((t) => t.id === until.terminalId);
    return false;
  }

  async function waitStep(step) {
    if (Number.isFinite(step.ms)) {
      await delay(step.ms);
      return { ok: true };
    }
    if (!step.until) return { ok: true };
    const timeout = Number.isFinite(step.timeoutMs) ? step.timeoutMs : 5_000;
    const started = Date.now();
    while (Date.now() - started < timeout) {
      if (matchesUntil(step.until)) return { ok: true };
      await delay(10);
    }
    return { ok: false, code: "TIMEOUT", message: `等待超时: ${JSON.stringify(step.until)}` };
  }

  // 连接。版本不匹配 / 握手失败保留错误码，不伪造已连接。
  try {
    await rpc.open({ fromSeq });
  } catch (err) {
    const code = err instanceof RpcError ? err.code : err?.code ?? 0;
    const message = err?.message ?? String(err);
    if (scriptMode) {
      record({ type: "error", phase: "connect", code, message });
      record({ type: "projection", value: projection() });
    } else {
      write(`连接核心失败: [${code}] ${message}\n`);
    }
    if (ownRpc) rpc.close();
    return { exitCode: 1, reason: "connect-failed", error: { code, message }, projection: projection() };
  }

  ensureSession();

  if (scriptMode) {
    // 记录事件流（含 message delta），供契约测试验证流式渲染，而不只是最终快照。
    const offScriptEvents = rpc.onEvent((event) => {
      record({
        type: "event",
        seq: event.seq,
        kind: event.kind,
        sessionId: event.sessionId ?? null,
        delta: event.payload?.delta === true || event.payload?.message?.delta === true,
      });
    });
    let quit = false;
    for (const step of steps) {
      if (quit) break;
      const action = step?.action ?? step?.type;
      try {
        switch (action) {
          case "select": {
            const id = step.sessionId ?? step.id;
            const exists = projection().sessions.some((s) => s.id === id);
            if (!exists) {
              record({ type: "error", action, code: "NOT_FOUND", message: `未知会话 ${id}` });
              break;
            }
            selectedSessionId = id;
            record({ type: "selected", sessionId: id });
            break;
          }
          case "create": {
            const result = await performCreate(step);
            record({ type: "create", ...result });
            break;
          }
          case "send":
          case "queue":
          case "steer": {
            const mode = action === "send" ? (step.mode ?? "send") : action;
            const result = await performSend(step.text ?? step.message, mode, step);
            record({ type: "send", action, ...result });
            break;
          }
          case "stop": {
            const result = await performStop(step.sessionId ?? null);
            record({ type: "stop", ...result });
            break;
          }
          case "answer": {
            const result = await performAnswer(step.interactionId ?? step.id, {
              optionId: step.optionId ?? null,
              text: step.text ?? null,
            });
            record({ type: "answer", ...result });
            break;
          }
          case "model": {
            const result = await performModel(step.model ?? step.modelId, step.sessionId ?? null);
            record({ type: "model", ...result });
            break;
          }
          case "wait": {
            const result = await waitStep(step);
            record({ type: "wait", ...result });
            break;
          }
          case "projection": {
            record({ type: "projection", value: projection() });
            break;
          }
          case "render": {
            const p = projection();
            const view = step.view ?? "status";
            let text;
            if (view === "sessions") text = renderSessions(p, { ...uiOptions(), selectedSessionId });
            else if (view === "messages") text = renderMessages(p, step.sessionId ?? ensureSession(), { ...uiOptions(), limit: step.limit ?? 200 });
            else if (view === "status") text = renderStatus(p, { ...uiOptions(), selectedSessionId });
            else if (view === "interactions") text = renderInteractions(p, { ...uiOptions(), sessionId: step.sessionId ?? null });
            else if (view === "terminal") text = renderTerminal(findTerminal(p, step.id ?? step.terminalId), {
              ...uiOptions(),
              maxLines: step.maxLines ?? 20,
              maxChars: step.maxChars ?? 12_000,
            });
            else if (view === "diff") text = renderDiff(findArtifact(p, step.id ?? step.artifactId), {
              ...uiOptions(),
              maxLines: step.maxLines ?? 200,
            });
            else if (view === "artifacts") text = renderArtifacts(p, { sessionId: step.sessionId ?? null });
            else if (view === "attachments") text = renderMessageAttachments(p, step.sessionId ?? ensureSession());
            else if (view === "help") text = renderHelp(uiOptions());
            else text = `未知视图: ${view}`;
            record({ type: "render", view, id: step.id ?? step.terminalId ?? step.artifactId ?? null, text });
            break;
          }
          case "saveTerminal":
          case "save-terminal": {
            record({
              type: "saved",
              kind: "terminal",
              ...(await saveTerminal(step.id ?? step.terminalId, step.path)),
            });
            break;
          }
          case "saveArtifact":
          case "save-artifact": {
            record({
              type: "saved",
              kind: "artifact",
              ...(await saveArtifact(step.id ?? step.artifactId, step.path)),
            });
            break;
          }
          case "quit":
            quit = true;
            break;
          default:
            record({ type: "error", action, code: "INVALID_PARAMS", message: `未知 script action: ${action}` });
        }
      } catch (err) {
        record({
          type: "error",
          action,
          code: err?.code ?? 0,
          message: err?.message ?? String(err),
        });
      }
    }
    record({ type: "projection", value: projection() });
    offScriptEvents();
    if (ownRpc) rpc.close();
    return { exitCode: 0, reason: "script-complete", projection: projection() };
  }

  // 交互模式
  function uiOptions() {
    return resolveUiOptions({
      width: stdout.columns ?? process.stdout.columns,
      isTTY: stdout.isTTY === true,
      color: useAnsi ?? color,
    });
  }
  const ansi = useAnsi ?? (color ?? (stdout.isTTY === true && process.env.NO_COLOR === undefined));
  let renderTimer = null;
  let closed = false;

  function renderInteractive() {
    const p = projection();
    const ui = uiOptions();
    const lines = [
      renderHeader(p, ui),
      "",
      renderSessions(p, { ...ui, selectedSessionId }),
      "",
      selectedSessionId ? renderMessages(p, selectedSessionId, { ...ui, limit: 80 }) : "（未选择会话）",
      "",
      renderInteractions(p, { ...ui, sessionId: selectedSessionId }),
      "",
      renderTerminalList(p, { ...ui, sessionId: selectedSessionId }),
      "",
      renderArtifacts(p, { ...ui, sessionId: selectedSessionId }),
      "",
      renderFooter(p, { ...ui }),
    ];
    if (ansi) write("\u001B[2J\u001B[H");
    write(`${lines.join("\n")}\n`);
  }

  function scheduleRender() {
    if (closed || renderTimer) return;
    renderTimer = setTimeout(() => {
      renderTimer = null;
      if (!closed) renderInteractive();
    }, 30);
    renderTimer.unref?.();
  }

  const offEvents = rpc.onEvent((event) => {
    if (closed) return;
    if (event.kind === "message" && (event.payload?.delta === true || event.payload?.message?.delta === true)) {
      const id = event.payload?.message?.id;
      const message = projection().messages.find((m) => m.id === id);
      if (message) {
        const tail = lastLine(message.text).slice(-120);
        write(`\r\u001B[K  … ${tail}`);
      }
      return;
    }
    scheduleRender();
  });

  renderInteractive();
  write("coda> ");

  const rl = readline.createInterface({ input: stdin, output: stdout, terminal: false });

  async function handleLine(rawLine) {
    const line = rawLine.trim();
    if (!line) {
      write("coda> ");
      return;
    }
    if (line === "/quit" || line === "/exit") {
      closed = true;
      rl.close();
      return;
    }
    if (line === "/help") {
      write(`${renderHelp(uiOptions())}\n`);
      write("coda> ");
      return;
    }
    if (line === "/sessions") {
      write(`${renderSessions(projection(), { ...uiOptions(), selectedSessionId })}\n`);
      write("coda> ");
      return;
    }
    if (line.startsWith("/use ")) {
      const id = line.slice(5).trim();
      const exists = projection().sessions.some((s) => s.id === id);
      write(exists ? `已切换: ${id}\n` : `未知会话: ${id}\n`);
      if (exists) selectedSessionId = id;
      renderInteractive();
      write("coda> ");
      return;
    }
    if (line === "/status") {
      write(`${renderStatus(projection(), { ...uiOptions(), selectedSessionId })}\n`);
      write("coda> ");
      return;
    }
    if (line === "/artifacts") {
      write(`${renderArtifacts(projection(), { sessionId: selectedSessionId })}\n`);
      write("coda> ");
      return;
    }
    if (line.startsWith("/terminal ")) {
      const id = line.slice(10).trim();
      write(`${renderTerminal(findTerminal(projection(), id))}\n`);
      write("coda> ");
      return;
    }
    if (line.startsWith("/diff ")) {
      const id = line.slice(6).trim();
      write(`${renderDiff(findArtifact(projection(), id))}\n`);
      write("coda> ");
      return;
    }
    if (line === "/stop") {
      const result = await performStop();
      write(result.ok ? `已请求停止（${result.state}）\n` : `停止失败: ${result.message}\n`);
      scheduleRender();
      write("coda> ");
      return;
    }
    if (line.startsWith("/model ")) {
      const model = line.slice(7).trim();
      const result = await performModel(model);
      write(result.ok ? `模型已切换: ${model}\n` : `模型切换失败: ${result.message}\n`);
      write("coda> ");
      return;
    }
    if (line.startsWith("/answer ")) {
      const parts = line.slice(8).trim().split(/\s+/);
      const interactionId = parts[0];
      const optionId = parts[1] ?? null;
      const text = parts.slice(2).join(" ") || null;
      const result = await performAnswer(interactionId, { optionId, text });
      if (result.ok) write(result.duplicate ? "该审批/提问已回答（去重）\n" : `已提交回答: ${interactionId}\n`);
      else write(`回答失败: ${result.message}\n`);
      scheduleRender();
      write("coda> ");
      return;
    }
    if (line.startsWith("/send ") || line.startsWith("/queue ") || line.startsWith("/steer ")) {
      const [command, ...rest] = line.split(" ");
      const mode = command.slice(1);
      const result = await performSend(rest.join(" "), mode);
      if (result.ok) write(`已发送（${result.state}）\n`);
      else write(`发送失败: ${result.message}${result.draftRetained ? "；草稿已保留" : ""}\n`);
      write("coda> ");
      return;
    }
    if (line.startsWith("/save-terminal ")) {
      const parts = line.slice(15).trim().split(/\s+/);
      const result = await saveTerminal(parts[0], parts.slice(1).join(" "));
      write(result.ok ? `已保存: ${result.path}（${result.bytes} B）\n` : `保存失败: ${result.message}\n`);
      write("coda> ");
      return;
    }
    if (line.startsWith("/save-artifact ")) {
      const parts = line.slice(15).trim().split(/\s+/);
      const result = await saveArtifact(parts[0], parts.slice(1).join(" "));
      write(result.ok ? `已保存: ${result.path}（${result.bytes} B）\n` : `保存失败: ${result.message}\n`);
      write("coda> ");
      return;
    }
    if (line.startsWith("/")) {
      write(`未知命令: ${line.split(" ")[0]}（/help 查看帮助）\n`);
      write("coda> ");
      return;
    }

    const result = await performSend(line, "send");
    if (result.ok) write(`已发送（${result.state}）\n`);
    else write(`发送失败: ${result.message}${result.draftRetained ? "；草稿已保留" : ""}\n`);
    write("coda> ");
  }

  rl.on("line", (line) => {
    handleLine(line).catch((err) => {
      write(`错误: ${err?.message ?? String(err)}\n`);
      write("coda> ");
    });
  });

  await new Promise((resolve) => rl.once("close", resolve));
  closed = true;
  if (renderTimer) clearTimeout(renderTimer);
  offEvents();
  if (ownRpc) rpc.close();
  return { exitCode: 0, reason: "interactive-exit" };
}

export async function main(argv = process.argv.slice(2), io = {}) {
  const args = parseArgs(argv);
  const stdout = io.stdout ?? process.stdout;
  const stdin = io.stdin ?? process.stdin;

  if (args.help) {
    stdout.write(`${USAGE}\n`);
    return 0;
  }

  if (!args.script && !args.scriptFile && stdin?.isTTY !== true) {
    stdout.write(
      "coda tui: 非交互终端，已安全退出（不读取 stdin、不启动核心/终端）。\n" +
        "使用 --script <json> 进行可编程驱动，或在真实 TTY 中运行。\n",
    );
    return 0;
  }

  let core = null;
  let transport = null;
  if (args.mock) {
    core = createMockCore();
  } else if (args.core?.startsWith("stdio:")) {
    transport = createStdioTransport(args.core.slice("stdio:".length), {
      cwd: process.cwd(),
      onStderr: (chunk) => stdout.write(chunk),
      onDiagnostic: (diag) => {
        if (diag.direction === "parse") stdout.write(`[core] 无法解析的帧: ${diag.error}\n`);
      },
    });
  } else if (args.core) {
    stdout.write(`不支持的 --core 连接方式: ${args.core}（请使用 stdio:<cmd>）\n`);
    return 2;
  } else {
    stdout.write(`${USAGE}\n`);
    return 2;
  }

  const result = await runTui({
    core,
    transport,
    script: args.scriptFile ?? args.script,
    stdout,
    stdin,
    fromSeq: args.fromSeq,
    protocolVersion: args.protocolVersion,
    timeoutMs: args.timeoutMs,
    color: args.color,
  });
  return result.exitCode ?? 0;
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  main()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((err) => {
      console.error(err);
      process.exitCode = 1;
    });
}
