/**
 * Codex CLI 的 `exec --json` 适配器（AGT-112，与 app-server 驱动并存）。
 *
 * 真实观测（CI，codex 0.167.0，无登录态）：
 *   `codex exec --json --skip-git-repo-check "<prompt>"`
 *   → JSONL: thread.started / turn.started / item.* / error / turn.completed|failed
 * 证据：fixtures/adapters/codex-exec-noauth.fixture.json（真实录制，失败路径）。
 *
 * 成功路径（模型输出、审批）没有真实录制，因此能力声明最高只到 experimental：
 * 不声明 supported，不伪造 token 级流式（item 完成才可见文本）。
 */
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

import { AdapterError, BaseAdapter, sideEffectFreeProbe } from "./base.mjs";
import { resolveExecutable, runProcess } from "./runtime.mjs";

export const CODEX_EXEC_TASK_ID = "AGT-112";

export const CODEX_EXEC_PROTOCOL = Object.freeze({
  name: "codex-exec-jsonl",
  transport: "stdio",
  invocation: ["exec", "--json", "--skip-git-repo-check"],
  detectFlags: ["exec", "--json", "--output-last-message", "--sandbox"],
  eventTypes: ["thread.started", "turn.started", "item.started", "item.completed", "error", "turn.completed", "turn.failed"],
  evidence: "platform/fixtures/adapters/codex-exec-noauth.fixture.json",
});

const UNKNOWN_FRAME_LIMIT = 20;

export function codexExecCapabilityDeclaration({ protocolDetected = false } = {}) {
  const unk = "unverified";
  const exp = "experimental";
  return {
    session: {
      multiThread: false,
      modelSwitch: protocolDetected ? exp : unk,
      permissionModeSwitch: false,
      resume: protocolDetected ? exp : unk,
      requestDurability: unk,
    },
    thread: { empty: false, snapshot: false, fork: false, fromTurn: false, nativeId: protocolDetected ? exp : unk },
    turn: {
      startEndEvents: protocolDetected ? exp : unk,
      interrupt: protocolDetected ? exp : unk,
      nativeSteer: false,
      stopThenAppend: unk,
      queue: false,
      terminalTrust: unk,
    },
    streaming: {
      text: protocolDetected ? exp : unk,
      reasoning: protocolDetected ? exp : unk,
      toolOutput: protocolDetected ? exp : unk,
      planText: false,
      messageCompleted: protocolDetected ? exp : unk,
    },
    tools: unk,
    approvals: { command: false, fileRead: false, fileWrite: false, patch: false, subagent: false, nativeRequestId: false },
    plan: false,
    subagent: unk,
    context: { system: unk, summary: false, handoffFull: false, handoffIncremental: false },
    checkpoint: { filesystem: false, sessionRollback: false, consistent: false },
    identity: { thread: protocolDetected ? exp : unk, turn: unk, item: unk, request: false },
    attachments: { input: false, output: unk },
  };
}

export class CodexExecAdapter extends BaseAdapter {
  constructor({
    instanceId,
    environment = "host",
    command = "codex",
    baseArgs = [],
    extraArgs = [],
    workspace = null,
    model = null,
    sandbox = "read-only",
    spawnImpl = spawn,
    version = null,
    probeTimeoutMs = 15000,
    clock = () => new Date(),
  } = {}) {
    super({ instanceId, environment, provider: "codex", version, clock });
    this.command = command;
    this.baseArgs = baseArgs;
    this.extraArgs = extraArgs;
    this.workspace = workspace;
    this.model = model;
    this.sandbox = sandbox;
    this.spawnImpl = spawnImpl;
    this.probeTimeoutMs = probeTimeoutMs;
    this.probing = { path: null, execDetected: false, jsonDetected: false, helpHead: null };
    this._upstream = new Map();
    this._children = new Map();
  }

  async probe() {
    const executable = resolveExecutable(this.command);
    if (!executable) {
      this.version = "0.0.0-unknown";
      this.declareCapabilities(codexExecCapabilityDeclaration());
      return sideEffectFreeProbe({
        installed: false,
        version: null,
        loggedIn: null,
        path: null,
        details: { taskId: CODEX_EXEC_TASK_ID, reason: "not-found", command: this.command, blocked: true },
      });
    }
    const versionRun = await runProcess({
      command: executable,
      args: [...this.baseArgs, "--version"],
      timeoutMs: this.probeTimeoutMs,
      isolate: false,
    }).catch(() => null);
    const helpRun = await runProcess({
      command: executable,
      args: [...this.baseArgs, "exec", "--help"],
      timeoutMs: this.probeTimeoutMs,
      isolate: false,
    }).catch(() => null);
    const helpText = `${helpRun?.stdout ?? ""}\n${helpRun?.stderr ?? ""}`;
    const execDetected = /codex exec|Usage: codex exec/i.test(helpText);
    const jsonDetected = helpText.includes("--json");
    this.version = (versionRun?.stdout ?? "").trim().replace(/^codex-cli\s+/, "") || "0.0.0-unknown";
    this.declareCapabilities(codexExecCapabilityDeclaration({ protocolDetected: execDetected && jsonDetected }));
    this.probing = { path: executable, execDetected, jsonDetected, helpHead: helpText.slice(0, 400) };
    return sideEffectFreeProbe({
      installed: true,
      version: this.version,
      loggedIn: null,
      path: executable,
      details: {
        taskId: CODEX_EXEC_TASK_ID,
        protocol: CODEX_EXEC_PROTOCOL.name,
        execDetected,
        jsonDetected,
        note: "probe 只跑 --version 与 exec --help，不启动会话",
      },
    });
  }

  async createSession({ sessionId, workspace = null } = {}) {
    this._assertUsable();
    if (workspace) this.workspace = workspace;
    this._upstream.set(sessionId ?? "", null);
    return { providerThreadId: null };
  }

  async send({ sessionId, text, model = null }) {
    this._assertUsable();
    this.assertCapability("turn", "startEndEvents");
    const body = String(text ?? "");
    if (!body.trim()) throw new AdapterError("PROTOCOL_ERROR", "empty prompt");
    const resumeId = this._upstream.get(sessionId) ?? null;
    const args = [...this.baseArgs, "exec", "--json", "--skip-git-repo-check", "--sandbox", this.sandbox];
    if (this.workspace) args.push("--cd", this.workspace);
    const useModel = model ?? this.model;
    if (useModel) args.push("-m", useModel);
    if (resumeId) args.push("resume", resumeId);
    args.push(...this.extraArgs, body);

    const child = this.spawnImpl(this.command, args, { stdio: ["ignore", "pipe", "pipe"] });
    this._children.set(sessionId, child);
    this.emitStatus(sessionId, "running", { phase: "turn_start" });

    const lines = [];
    let stderrTail = "";
    child.stderr?.setEncoding?.("utf8");
    child.stderr?.on?.("data", (chunk) => {
      stderrTail = `${stderrTail}${chunk}`.slice(-2000);
    });
    const reader = createInterface({ input: child.stdout, crlfDelay: Infinity });
    reader.on("line", (line) => lines.push(line));
    const closeInfo = await new Promise((resolve) => {
      child.on?.("error", (err) => resolve({ error: err }));
      child.on?.("close", (code, signal) => resolve({ code, signal }));
    });
    reader.close();
    this._children.delete(sessionId);

    let finalText = "";
    let failure = null;
    let completed = false;
    for (const line of lines) {
      let frame;
      try {
        frame = JSON.parse(line);
      } catch {
        if (this.diagnostics.unknownFrames.length < UNKNOWN_FRAME_LIMIT) {
          this.diagnostics.unknownFrames.push({ line: String(line).slice(0, 400) });
        }
        continue;
      }
      switch (frame.type) {
        case "thread.started": {
          const upstream = frame.thread_id ?? null;
          if (upstream) this._upstream.set(sessionId, upstream);
          this.emitEvent({ kind: "session", payload: { session: { id: sessionId, providerThreadId: upstream } } });
          break;
        }
        case "turn.started":
          this.emitStatus(sessionId, "running", { phase: "turn_start" });
          break;
        case "item.completed":
        case "item.started": {
          const item = frame.item ?? {};
          const type = item.type ?? "unknown";
          if (type === "agent_message") {
            const textValue = String(item.text ?? "");
            if (textValue) {
              finalText += textValue;
              this.emitEvent({ kind: "message", sessionId, payload: { role: "assistant", text: textValue, delta: frame.type === "item.started" } });
            }
          } else if (type === "reasoning") {
            this.emitEvent({ kind: "reasoning", sessionId, payload: { text: String(item.text ?? "") } });
          } else if (frame.type === "item.completed") {
            this.emitEvent({ kind: "tool", sessionId, payload: { tool: item } });
          }
          break;
        }
        case "error":
          failure = { message: String(frame.message ?? "codex error"), code: frame.code ?? null };
          this.emitError(sessionId, new AdapterError("PROCESS_EXIT", failure.message, { details: { frame } }));
          break;
        case "turn.completed":
          completed = true;
          break;
        case "turn.failed":
          failure = failure ?? { message: String(frame.error?.message ?? "codex turn failed"), code: frame.error?.code ?? null };
          break;
        default:
          if (this.diagnostics.unknownFrames.length < UNKNOWN_FRAME_LIMIT) {
            this.diagnostics.unknownFrames.push({ type: frame.type ?? null });
          }
      }
    }

    const ok = completed && closeInfo.code === 0;
    if (!ok) {
      const message = failure?.message ?? stderrTail.trim() ?? `codex exited (code=${closeInfo.code}, signal=${closeInfo.signal})`;
      this.emitStatus(sessionId, "failed", { code: failure?.code ?? null });
      return { state: "failed", text: finalText, error: { message, code: failure?.code ?? null }, upstreamSessionId: this._upstream.get(sessionId) ?? null };
    }
    this.emitStatus(sessionId, "idle");
    return { state: "succeeded", text: finalText, upstreamSessionId: this._upstream.get(sessionId) ?? null };
  }

  async interrupt({ sessionId }) {
    const child = this._children.get(sessionId);
    if (!child) return { stopped: false, reason: "no-active-turn" };
    try {
      child.kill?.("SIGTERM");
    } catch {
      /* ignore */
    }
    this.emitStatus(sessionId, "stopped", { trust: "process-termination" });
    return { stopped: true, trust: "process-termination" };
  }

  async resume({ sessionId, upstreamSessionId = null }) {
    if (upstreamSessionId) this._upstream.set(sessionId, upstreamSessionId);
    return { providerThreadId: this._upstream.get(sessionId) ?? null };
  }

  async steer() {
    throw new AdapterError("UNSUPPORTED_CAPABILITY", "codex exec has no steer; use app-server turn/steer or stop→resume→append");
  }

  async fork() {
    throw new AdapterError("UNSUPPORTED_CAPABILITY", "codex exec has no native fork");
  }

  async dispose() {
    for (const child of this._children.values()) {
      try {
        child.kill?.("SIGKILL");
      } catch {
        /* ignore */
      }
    }
    this._children.clear();
    await super.dispose();
  }
}

export function createCodexExecAdapter(options = {}) {
  return new CodexExecAdapter(options);
}
