/**
 * DeepSeek Harness（DSH）适配器（AGT-113）。
 *
 * 已在本机真实观测并录制的入口：
 *   `dsh --profile headless --json [--session-id <id>] [--approval-policy never] "<task>"`
 *   → NDJSON 事件：session / status(turn_start|step_start|step_end|turn_end) / final
 *
 * 证据：fixtures/adapters/deepseek-harness-headless-error.fixture.json（真实录制，失败路径，
 * 因为启动环境没有模型凭据）。成功路径尚未验证，所以能力全部停在 experimental/unverified：
 * 不声明 supported，不伪造流式 token（该入口只在 final 一次性给文本）。
 */
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

import { AdapterError, BaseAdapter, sideEffectFreeProbe } from "./base.mjs";
import { resolveExecutable, runProcess } from "./runtime.mjs";

export const DEEPSEEK_HARNESS_TASK_ID = "AGT-113";

export const DSH_HEADLESS_PROTOCOL = Object.freeze({
  name: "dsh-headless-json",
  transport: "stdio",
  invocation: ["--profile", "headless", "--json"],
  detectFlags: ["--json", "--session-id", "--approval-policy"],
  eventTypes: ["session", "status", "final"],
  statusPhases: ["turn_start", "step_start", "step_end", "turn_end"],
  evidence: "platform/fixtures/adapters/deepseek-harness-headless-error.fixture.json",
});

const UNKNOWN_FRAME_LIMIT = 20;

/** 全部 unverified/experimental：只有失败路径的真实录制，没有成功会话证据。 */
export function deepseekHarnessCapabilityDeclaration({ protocolDetected = false, resumeDetected = false } = {}) {
  const exp = "experimental";
  const unk = "unverified";
  return {
    session: {
      multiThread: false,
      modelSwitch: false,
      permissionModeSwitch: protocolDetected ? exp : false,
      resume: resumeDetected ? exp : unk,
      requestDurability: unk,
    },
    thread: { empty: protocolDetected ? exp : false, snapshot: false, fork: false, fromTurn: false, nativeId: protocolDetected ? exp : unk },
    turn: {
      startEndEvents: protocolDetected ? exp : unk,
      interrupt: protocolDetected ? exp : unk, // 只能杀进程，stop 的来源是 process-termination
      nativeSteer: false,
      stopThenAppend: unk,
      queue: false,
      terminalTrust: exp,
    },
    streaming: {
      text: protocolDetected ? exp : unk, // 只有 final 一次性文本，不是 token 级
      reasoning: false,
      toolOutput: unk,
      planText: false,
      messageCompleted: protocolDetected ? exp : unk,
    },
    tools: unk,
    approvals: {
      command: false,
      fileRead: false,
      fileWrite: false,
      patch: false,
      subagent: false,
      nativeRequestId: false,
    },
    plan: false,
    subagent: false,
    context: { system: unk, summary: false, handoffFull: false, handoffIncremental: false },
    checkpoint: { filesystem: false, sessionRollback: false, consistent: false },
    identity: { thread: false, turn: unk, item: false, request: false },
    attachments: { input: false, output: false },
  };
}

export class DeepSeekHarnessAdapter extends BaseAdapter {
  constructor({
    instanceId,
    environment = "host",
    command = "dsh",
    version = null,
    profile = "headless",
    extraArgs = [],
    baseArgs = [],
    spawnImpl = spawn,
    probeTimeoutMs = 15000,
    clock = () => new Date(),
  } = {}) {
    super({ instanceId, environment, provider: "deepseek-harness", version, clock });
    this.command = command;
    this.profile = profile;
    this.extraArgs = extraArgs;
    this.baseArgs = baseArgs;
    this.spawnImpl = spawnImpl;
    this.probeTimeoutMs = probeTimeoutMs;
    this.probing = { path: null, jsonDetected: false, resumeDetected: false, helpHead: null };
    /** core sessionId → 上游 dsh sessionId（resume 用）。 */
    this._upstream = new Map();
    this._children = new Map();
    this._active = new Map();
  }

  async probe() {
    const executable = resolveExecutable(this.command);
    if (!executable) {
      this.version = "0.0.0-unknown";
      this.declareCapabilities(deepseekHarnessCapabilityDeclaration());
      return sideEffectFreeProbe({
        installed: false,
        version: null,
        loggedIn: null,
        path: null,
        details: { taskId: DEEPSEEK_HARNESS_TASK_ID, reason: "not-found", command: this.command, blocked: true },
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
      args: [...this.baseArgs, "--profile", this.profile, "--help"],
      timeoutMs: this.probeTimeoutMs,
      isolate: false,
    }).catch(() => null);
    const helpText = `${helpRun?.stdout ?? ""}\n${helpRun?.stderr ?? ""}`;
    const jsonDetected = helpText.includes("--json");
    const resumeDetected = helpText.includes("--session-id");
    this.version = (versionRun?.stdout ?? "").trim() || "0.0.0-unknown";
    this.declareCapabilities(
      deepseekHarnessCapabilityDeclaration({ protocolDetected: jsonDetected, resumeDetected }),
    );
    this.probing = {
      path: executable,
      jsonDetected,
      resumeDetected,
      helpHead: helpText.slice(0, 400),
    };
    return sideEffectFreeProbe({
      installed: true,
      version: this.version,
      loggedIn: null,
      path: executable,
      details: {
        taskId: DEEPSEEK_HARNESS_TASK_ID,
        protocol: DSH_HEADLESS_PROTOCOL.name,
        jsonDetected,
        resumeDetected,
        note: "loggedIn 为 null：headless 入口不提供无副作用的登录探测",
      },
    });
  }

  async createSession({ sessionId } = {}) {
    this._assertUsable();
    this._active.set(sessionId ?? "", { turns: [] });
    return { providerThreadId: null };
  }

  async send({ sessionId, text, upstreamSessionId = null }) {
    this._assertUsable();
    this.assertCapability("turn", "startEndEvents");
    const body = String(text ?? "");
    if (!body.trim()) throw new AdapterError("PROTOCOL_ERROR", "empty prompt");
    const resumeId = upstreamSessionId ?? this._upstream.get(sessionId) ?? null;
    const args = [...this.baseArgs, ...this.extraArgs, "--profile", this.profile, "--json", "--approval-policy", "never"];
    if (resumeId) args.push("--session-id", resumeId);
    args.push(body);

    const started = Date.now();
    const child = this.spawnImpl(this.command, args, { stdio: ["ignore", "pipe", "pipe"] });
    this._children.set(sessionId, child);
    this.emitStatus(sessionId, "running", { phase: "turn_start" });

    let finalText = "";
    let finalReason = null;
    let stderrTail = "";
    child.stderr?.setEncoding?.("utf8");
    child.stderr?.on?.("data", (chunk) => {
      stderrTail = `${stderrTail}${chunk}`.slice(-2000);
    });

    const reader = createInterface({ input: child.stdout, crlfDelay: Infinity });
    const lines = [];
    reader.on("line", (line) => lines.push(line));

    const closeInfo = await new Promise((resolve) => {
      child.on?.("error", (err) => resolve({ error: err }));
      child.on?.("close", (code, signal) => resolve({ code, signal }));
    });
    reader.close();
    this._children.delete(sessionId);

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
        case "session": {
          const upstream = frame.sessionId ?? null;
          if (upstream) this._upstream.set(sessionId, upstream);
          this.emitEvent({
            kind: "session",
            payload: { session: { id: sessionId, providerThreadId: upstream, cwd: frame.cwd ?? null } },
          });
          break;
        }
        case "status":
          this.emitStatus(sessionId, frame.phase === "turn_end" ? "idle" : "running", {
            phase: frame.phase,
            turn: frame.turn ?? null,
            step: frame.step ?? null,
            ...(frame.reason ? { reason: frame.reason } : {}),
          });
          break;
        case "final":
          finalReason = frame.reason ?? null;
          finalText = String(frame.text ?? "");
          break;
        default:
          if (this.diagnostics.unknownFrames.length < UNKNOWN_FRAME_LIMIT) {
            this.diagnostics.unknownFrames.push({ type: frame.type ?? null });
          }
      }
    }

    const ok = finalReason?.kind !== "error" && closeInfo.code === 0;
    if (finalText) {
      this.emitEvent({ kind: "message", sessionId, payload: { role: "assistant", text: finalText, delta: false } });
    }
    if (!ok) {
      const message = finalReason?.error?.message ?? stderrTail.trim() ?? `dsh exited (code=${closeInfo.code}, signal=${closeInfo.signal})`;
      this.emitError(sessionId, new AdapterError("PROCESS_EXIT", message, {
        details: { code: closeInfo.code, signal: closeInfo.signal, reason: finalReason },
      }));
      this.emitStatus(sessionId, "failed", { code: finalReason?.error?.code ?? null });
      return { state: "failed", text: finalText, error: { message, code: finalReason?.error?.code ?? null }, durationMs: Date.now() - started, upstreamSessionId: this._upstream.get(sessionId) ?? null };
    }
    this.emitStatus(sessionId, "idle");
    return { state: "succeeded", text: finalText, durationMs: Date.now() - started, upstreamSessionId: this._upstream.get(sessionId) ?? null };
  }

  async interrupt({ sessionId }) {
    const child = this._children.get(sessionId);
    if (!child) return { stopped: false, reason: "no-active-turn" };
    try {
      if (process.platform !== "win32" && child.pid) process.kill(-child.pid, "SIGTERM");
      else child.kill("SIGTERM");
    } catch {
      child.kill?.("SIGKILL");
    }
    this.emitStatus(sessionId, "stopped", { trust: "process-termination" });
    return { stopped: true, trust: "process-termination" };
  }

  async resume({ sessionId, upstreamSessionId = null }) {
    if (upstreamSessionId) this._upstream.set(sessionId, upstreamSessionId);
    return { providerThreadId: upstreamSessionId ?? this._upstream.get(sessionId) ?? null };
  }

  async steer() {
    throw new AdapterError("UNSUPPORTED_CAPABILITY", "dsh headless has no native steer; core must use stop→resume→append");
  }

  async fork() {
    throw new AdapterError("UNSUPPORTED_CAPABILITY", "dsh headless has no native fork");
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

export function createDeepSeekHarnessAdapter(options = {}) {
  return new DeepSeekHarnessAdapter(options);
}
