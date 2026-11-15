/**
 * Claude Code stream-json 适配器（AGT-111）。
 *
 * 真实观测（CI，claude 2.1.319，无登录态）：
 *   `claude -p --output-format stream-json --verbose "<prompt>"`
 *   → JSONL: system(init) / assistant / user / result
 * 证据：fixtures/adapters/claude-stream-json-noauth.fixture.json（真实 init/assistant/result 帧）。
 * 成功路径未录制，能力最高 experimental；参考 Zeron crates/harness/src/claude/mod.rs。
 */
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

import { AdapterError, BaseAdapter, sideEffectFreeProbe } from "./base.mjs";
import { resolveExecutable, runProcess } from "./runtime.mjs";

export const CLAUDE_STREAM_TASK_ID = "AGT-111";
export const CLAUDE_STREAM_PROTOCOL = Object.freeze({
  name: "claude-stream-json",
  transport: "stdio",
  invocation: ["-p", "--output-format", "stream-json", "--verbose"],
  detectFlags: ["--output-format", "stream-json", "--permission-mode", "--resume", "--session-id"],
  frameTypes: ["system", "assistant", "user", "result", "stream_event"],
  evidence: "platform/fixtures/adapters/claude-stream-json-noauth.fixture.json",
});

const UNKNOWN_FRAME_LIMIT = 20;

export function claudeStreamCapabilityDeclaration({ protocolDetected = false } = {}) {
  const unk = "unverified";
  const exp = "experimental";
  return {
    session: { multiThread: false, modelSwitch: protocolDetected ? exp : unk, permissionModeSwitch: protocolDetected ? exp : unk, resume: protocolDetected ? exp : unk, requestDurability: unk },
    thread: { empty: false, snapshot: false, fork: false, fromTurn: false, nativeId: protocolDetected ? exp : unk },
    turn: { startEndEvents: protocolDetected ? exp : unk, interrupt: protocolDetected ? exp : unk, nativeSteer: false, stopThenAppend: unk, queue: false, terminalTrust: unk },
    streaming: { text: protocolDetected ? exp : unk, reasoning: protocolDetected ? exp : unk, toolOutput: protocolDetected ? exp : unk, planText: unk, messageCompleted: protocolDetected ? exp : unk },
    tools: protocolDetected ? exp : unk,
    approvals: { command: unk, fileRead: unk, fileWrite: unk, patch: unk, subagent: unk, nativeRequestId: unk },
    plan: unk,
    subagent: protocolDetected ? exp : unk,
    context: { system: unk, summary: unk, handoffFull: false, handoffIncremental: false },
    checkpoint: { filesystem: false, sessionRollback: false, consistent: false },
    identity: { thread: protocolDetected ? exp : unk, turn: unk, item: unk, request: false },
    attachments: { input: unk, output: unk },
  };
}

/** 一行 stream-json 帧 → 统一事件（可能多条）。 */
export function mapClaudeFrame(frame, { sessionId, emit }) {
  const out = { events: 0, text: "", finalText: null, sessionId: null, failure: null, finished: false };
  switch (frame?.type) {
    case "system":
      if (frame.subtype === "init") {
        out.sessionId = frame.session_id ?? null;
        emit({ kind: "session", payload: { session: { id: sessionId, providerThreadId: frame.session_id ?? null, cwd: frame.cwd ?? null, model: frame.model ?? null } } });
        emit({ kind: "status", payload: { state: "running", model: frame.model ?? null, permissionMode: frame.permissionMode ?? null } });
      }
      break;
    case "assistant": {
      for (const block of frame.message?.content ?? []) {
        if (block?.type === "text" && block.text) {
          out.text += block.text;
          emit({ kind: "message", payload: { role: "assistant", text: block.text, delta: true } });
        } else if (block?.type === "thinking" || block?.type === "reasoning") {
          emit({ kind: "reasoning", payload: { text: String(block.thinking ?? block.text ?? "") } });
        } else if (block?.type === "tool_use") {
          emit({ kind: "tool", payload: { tool: { id: block.id, name: block.name, input: block.input ?? null, state: "started" }, parentToolUseId: frame.parent_tool_use_id ?? null } });
        }
      }
      break;
    }
    case "user":
      for (const block of frame.message?.content ?? []) {
        if (block?.type === "tool_result") {
          emit({ kind: "tool", payload: { tool: { id: block.tool_use_id, state: "completed", isError: !!block.is_error, content: truncate(block.content) } } });
        }
      }
      break;
    case "result":
      out.finished = true;
      if (frame.is_error) {
        out.failure = { message: String(frame.result ?? frame.subtype ?? "claude result error"), subtype: frame.subtype ?? null };
        emit({ kind: "error", payload: out.failure });
      } else if (frame.result) {
        // result.result 是整回合最终文本（与 delta 累积重复），交给调用方择优，避免重复拼两遍
        out.finalText = String(frame.result);
        emit({ kind: "message", payload: { role: "assistant", text: String(frame.result), delta: false } });
      }
      emit({ kind: "status", payload: { state: frame.is_error ? "failed" : "idle", subtype: frame.subtype ?? null, durationMs: frame.duration_ms ?? null } });
      break;
    default:
      break;
  }
  return out;
}

function truncate(value, max = 2000) {
  const text = typeof value === "string" ? value : JSON.stringify(value ?? null);
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

export class ClaudeStreamJsonAdapter extends BaseAdapter {
  constructor({ instanceId, environment = "host", command = "claude", provider = "claude-code", taskId = "AGT-111", baseArgs = [], extraArgs = [], permissionMode = null, model = null, spawnImpl = spawn, version = null, probeTimeoutMs = 15000, clock = () => new Date() } = {}) {
    super({ instanceId, environment, provider, version, clock });
    this.command = command; this.taskId = taskId; this.baseArgs = baseArgs; this.extraArgs = extraArgs;
    this.permissionMode = permissionMode; this.model = model;
    this.spawnImpl = spawnImpl; this.probeTimeoutMs = probeTimeoutMs;
    this.probing = { path: null, streamJsonDetected: false, resumeDetected: false, helpHead: null };
    this._upstream = new Map(); this._children = new Map();
  }

  async probe() {
    const executable = resolveExecutable(this.command);
    if (!executable) {
      this.version = "0.0.0-unknown";
      this.declareCapabilities(claudeStreamCapabilityDeclaration());
      return sideEffectFreeProbe({ installed: false, version: null, loggedIn: null, path: null, details: { taskId: this.taskId, reason: "not-found", command: this.command, blocked: true } });
    }
    const versionRun = await runProcess({ command: executable, args: [...this.baseArgs, "--version"], timeoutMs: this.probeTimeoutMs, isolate: false }).catch(() => null);
    const helpRun = await runProcess({ command: executable, args: [...this.baseArgs, "--help"], timeoutMs: this.probeTimeoutMs, isolate: false }).catch(() => null);
    const helpText = `${helpRun?.stdout ?? ""}\n${helpRun?.stderr ?? ""}`;
    const streamJsonDetected = helpText.includes("stream-json") && helpText.includes("--output-format");
    const resumeDetected = helpText.includes("--resume") || helpText.includes("--session-id");
    this.version = (versionRun?.stdout ?? "").trim() || "0.0.0-unknown";
    this.declareCapabilities(claudeStreamCapabilityDeclaration({ protocolDetected: streamJsonDetected }));
    this.probing = { path: executable, streamJsonDetected, resumeDetected, helpHead: helpText.slice(0, 400) };
    return sideEffectFreeProbe({ installed: true, version: this.version, loggedIn: null, path: executable, details: { taskId: this.taskId, protocol: CLAUDE_STREAM_PROTOCOL.name, streamJsonDetected, resumeDetected, note: "loggedIn 不提供无副作用探测" } });
  }

  async createSession({ sessionId } = {}) {
    this._upstream.set(sessionId ?? "", null);
    return { providerThreadId: null };
  }

  async send({ sessionId, text, model = null }) {
    this._assertUsable();
    this.assertCapability("turn", "startEndEvents");
    const body = String(text ?? "");
    if (!body.trim()) throw new AdapterError("PROTOCOL_ERROR", "empty prompt");
    const resumeId = this._upstream.get(sessionId) ?? null;
    const args = [...this.baseArgs, "-p", "--output-format", "stream-json", "--verbose"];
    const useModel = model ?? this.model;
    if (useModel) args.push("--model", useModel);
    if (this.permissionMode) args.push("--permission-mode", this.permissionMode);
    if (resumeId) args.push("--resume", resumeId);
    args.push(...this.extraArgs, body);

    const child = this.spawnImpl(this.command, args, { stdio: ["ignore", "pipe", "pipe"] });
    this._children.set(sessionId, child);
    this.emitStatus(sessionId, "running", { phase: "turn_start" });
    const lines = [];
    let stderrTail = "";
    child.stderr?.setEncoding?.("utf8");
    child.stderr?.on?.("data", (chunk) => { stderrTail = `${stderrTail}${chunk}`.slice(-2000); });
    const reader = createInterface({ input: child.stdout, crlfDelay: Infinity });
    reader.on("line", (line) => lines.push(line));
    const closeInfo = await new Promise((resolve) => {
      child.on?.("error", (err) => resolve({ error: err }));
      child.on?.("close", (code, signal) => resolve({ code, signal }));
    });
    reader.close();
    this._children.delete(sessionId);

    let textOut = "";
    let failure = null;
    let finished = false;
    for (const line of lines) {
      let frame;
      try { frame = JSON.parse(line); } catch {
        if (this.diagnostics.unknownFrames.length < UNKNOWN_FRAME_LIMIT) this.diagnostics.unknownFrames.push({ line: String(line).slice(0, 400) });
        continue;
      }
      const mapped = mapClaudeFrame(frame, { sessionId, emit: (event) => this.emitEvent({ ...event, sessionId }) });
      if (mapped.sessionId) this._upstream.set(sessionId, mapped.sessionId);
      textOut += mapped.text;
      if (mapped.finalText !== null && mapped.finalText !== undefined) textOut = mapped.finalText;
      if (mapped.failure) failure = mapped.failure;
      finished = finished || mapped.finished;
    }

    if (failure || closeInfo.code !== 0 || !finished) {
      const message = failure?.message ?? stderrTail.trim() ?? `claude exited (code=${closeInfo.code}, signal=${closeInfo.signal})`;
      if (!failure) this.emitError(sessionId, new AdapterError("PROCESS_EXIT", message, { details: { code: closeInfo.code, signal: closeInfo.signal } }));
      this.emitStatus(sessionId, "failed", { subtype: failure?.subtype ?? null });
      return { state: "failed", text: textOut, error: { message, subtype: failure?.subtype ?? null }, upstreamSessionId: this._upstream.get(sessionId) ?? null };
    }
    this.emitStatus(sessionId, "idle");
    return { state: "succeeded", text: textOut, upstreamSessionId: this._upstream.get(sessionId) ?? null };
  }

  async interrupt({ sessionId }) {
    const child = this._children.get(sessionId);
    if (!child) return { stopped: false, reason: "no-active-turn" };
    try { child.kill?.("SIGTERM"); } catch { /* ignore */ }
    this.emitStatus(sessionId, "stopped", { trust: "process-termination" });
    return { stopped: true, trust: "process-termination" };
  }

  async resume({ sessionId, upstreamSessionId = null }) {
    if (upstreamSessionId) this._upstream.set(sessionId, upstreamSessionId);
    return { providerThreadId: this._upstream.get(sessionId) ?? null };
  }

  async steer() { throw new AdapterError("UNSUPPORTED_CAPABILITY", "one-shot -p mode has no native steer"); }
  async fork() { throw new AdapterError("UNSUPPORTED_CAPABILITY", "claude -p has no native fork"); }

  async dispose() {
    for (const child of this._children.values()) { try { child.kill?.("SIGKILL"); } catch { /* ignore */ } }
    this._children.clear();
    await super.dispose();
  }
}

export function createClaudeStreamJsonAdapter(options = {}) {
  return new ClaudeStreamJsonAdapter(options);
}
