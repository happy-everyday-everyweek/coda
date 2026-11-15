/**
 * Kimi Code headless 适配器（AGT-128，实验）。
 *
 * 真实观测（CI，kimi 2.5.2，无登录）：
 *   `kimi -p "<prompt>" --output-format stream-json`
 *   stdout: JSONL，帧形如 `{"role":"meta","type":"system.version","version":"2.5.2"}`
 *   stderr: `No model configured. Run kimi and use /login ...`
 * 证据：fixtures/adapters/kimi-headless-stream-json.fixture.json（真实 meta 帧 + 错误）。
 *
 * 与 Claude 不同：Kimi 的 `-p` 是带值参数，prompt 必须紧跟；帧用 role+type，不是 Claude 的 type 家族。
 * 成功回合（assistant 帧形状）尚未录制 → 能力最高 experimental，不声明 supported。
 */
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

import { AdapterError, BaseAdapter, sideEffectFreeProbe } from "./base.mjs";
import { resolveExecutable, runProcess } from "./runtime.mjs";

export const KIMI_HEADLESS_TASK_ID = "AGT-128";
export const KIMI_HEADLESS_PROTOCOL = Object.freeze({
  name: "kimi-headless-jsonl",
  invocation: ["-p", "<prompt>", "--output-format", "stream-json"],
  frameShape: "role+type",
  evidence: "platform/fixtures/adapters/kimi-headless-stream-json.fixture.json",
});

export function kimiHeadlessCapabilityDeclaration({ protocolDetected = false } = {}) {
  const unk = "unverified";
  const exp = "experimental";
  return {
    session: { multiThread: false, modelSwitch: unk, permissionModeSwitch: unk, resume: unk, requestDurability: unk },
    thread: { empty: false, snapshot: false, fork: false, fromTurn: false, nativeId: unk },
    turn: { startEndEvents: protocolDetected ? exp : unk, interrupt: protocolDetected ? exp : unk, nativeSteer: false, stopThenAppend: unk, queue: false, terminalTrust: unk },
    streaming: { text: protocolDetected ? exp : unk, reasoning: unk, toolOutput: unk, planText: unk, messageCompleted: protocolDetected ? exp : unk },
    tools: unk,
    approvals: { command: unk, fileRead: unk, fileWrite: unk, patch: unk, subagent: unk, nativeRequestId: false },
    plan: unk,
    subagent: unk,
    context: { system: unk, summary: unk, handoffFull: false, handoffIncremental: false },
    checkpoint: { filesystem: false, sessionRollback: false, consistent: false },
    identity: { thread: unk, turn: unk, item: unk, request: false },
    attachments: { input: unk, output: unk },
  };
}

/** 一行 Kimi JSONL → 统一事件（帧形状来自真实 meta 帧与官方 help 的推断，未知帧只进诊断）。 */
export function mapKimiFrame(frame, { sessionId, emit }) {
  const out = { text: "", failure: null, finished: false };
  const type = String(frame?.type ?? "");
  const role = String(frame?.role ?? "");
  if (type.startsWith("system.version") || role === "meta") {
    emit({ kind: "status", payload: { state: "running", version: frame.version ?? null } });
    return out;
  }
  if (role === "assistant" || type.includes("assistant") || type.includes("message")) {
    const text = String(frame.text ?? frame.content ?? frame.message ?? "");
    if (text) {
      out.text = text;
      emit({ kind: "message", payload: { role: "assistant", text, delta: false } });
    }
    if (type.includes("result") || type.includes("done") || frame.finish === true) out.finished = true;
    return out;
  }
  if (type.includes("error") || role === "error") {
    out.failure = { message: String(frame.message ?? frame.error ?? "kimi error") };
    emit({ kind: "error", payload: out.failure });
    return out;
  }
  if (type.includes("result") || type.includes("done")) out.finished = true;
  return out;
}

export class KimiHeadlessAdapter extends BaseAdapter {
  constructor({ instanceId, environment = "host", command = "kimi", baseArgs = [], spawnImpl = spawn, version = null, probeTimeoutMs = 15000, clock = () => new Date() } = {}) {
    super({ instanceId, environment, provider: "kimi", version, clock });
    this.command = command;
    this.baseArgs = baseArgs;
    this.spawnImpl = spawnImpl;
    this.probeTimeoutMs = probeTimeoutMs;
    this.probing = { path: null, streamJsonDetected: false };
    this._children = new Map();
  }

  async probe() {
    const executable = resolveExecutable(this.command);
    if (!executable) {
      this.version = "0.0.0-unknown";
      this.declareCapabilities(kimiHeadlessCapabilityDeclaration());
      return sideEffectFreeProbe({ installed: false, version: null, loggedIn: null, path: null, details: { taskId: KIMI_HEADLESS_TASK_ID, reason: "not-found", command: this.command, blocked: true } });
    }
    const versionRun = await runProcess({ command: executable, args: [...this.baseArgs, "--version"], timeoutMs: this.probeTimeoutMs, isolate: false }).catch(() => null);
    const helpRun = await runProcess({ command: executable, args: [...this.baseArgs, "--help"], timeoutMs: this.probeTimeoutMs, isolate: false }).catch(() => null);
    const helpText = `${helpRun?.stdout ?? ""}\n${helpRun?.stderr ?? ""}`;
    const streamJsonDetected = helpText.includes("stream-json") && helpText.includes("--output-format");
    this.version = (versionRun?.stdout ?? "").trim() || "0.0.0-unknown";
    this.declareCapabilities(kimiHeadlessCapabilityDeclaration({ protocolDetected: streamJsonDetected }));
    this.probing = { path: executable, streamJsonDetected };
    return sideEffectFreeProbe({ installed: true, version: this.version, loggedIn: null, path: executable, details: { taskId: KIMI_HEADLESS_TASK_ID, protocol: KIMI_HEADLESS_PROTOCOL.name, streamJsonDetected } });
  }

  async createSession() {
    return { providerThreadId: null };
  }

  async send({ sessionId, text }) {
    this._assertUsable();
    this.assertCapability("turn", "startEndEvents");
    const body = String(text ?? "");
    if (!body.trim()) throw new AdapterError("PROTOCOL_ERROR", "empty prompt");
    const child = this.spawnImpl(this.command, [...this.baseArgs, "-p", body, "--output-format", "stream-json"], { stdio: ["ignore", "pipe", "pipe"] });
    this._children.set(sessionId, child);
    this.emitStatus(sessionId, "running");
    let stdout = "";
    let stderr = "";
    child.stdout?.setEncoding?.("utf8");
    child.stderr?.setEncoding?.("utf8");
    child.stdout?.on?.("data", (chunk) => { stdout += chunk; });
    child.stderr?.on?.("data", (chunk) => { stderr += chunk; });
    const closeInfo = await new Promise((resolve) => {
      child.on?.("error", (err) => resolve({ error: err }));
      child.on?.("close", (code, signal) => resolve({ code, signal }));
    });
    this._children.delete(sessionId);

    let textOut = "";
    let failure = null;
    let finished = false;
    for (const line of stdout.split("\n")) {
      if (!line.trim()) continue;
      let frame;
      try { frame = JSON.parse(line); } catch { continue; }
      const mapped = mapKimiFrame(frame, { sessionId, emit: (event) => this.emitEvent({ ...event, sessionId }) });
      textOut += mapped.text;
      if (mapped.failure) failure = mapped.failure;
      finished = finished || mapped.finished;
    }
    if (failure || closeInfo.code !== 0) {
      const message = failure?.message ?? stderr.trim() ?? `kimi exited (code=${closeInfo.code})`;
      if (!failure) this.emitError(sessionId, new AdapterError("PROCESS_EXIT", message, { details: { code: closeInfo.code } }));
      this.emitStatus(sessionId, "failed");
      return { state: "failed", text: textOut, error: { message } };
    }
    this.emitStatus(sessionId, "idle");
    return { state: "succeeded", text: textOut, finished };
  }

  async interrupt({ sessionId }) {
    const child = this._children.get(sessionId);
    if (!child) return { stopped: false, reason: "no-active-turn" };
    try { child.kill?.("SIGTERM"); } catch { /* ignore */ }
    this.emitStatus(sessionId, "stopped");
    return { stopped: true, trust: "process-termination" };
  }

  async steer() { throw new AdapterError("UNSUPPORTED_CAPABILITY", "kimi -p one-shot has no steer"); }
  async fork() { throw new AdapterError("UNSUPPORTED_CAPABILITY", "kimi -p has no fork"); }
  async resume() { return { providerThreadId: null }; }

  async dispose() {
    for (const child of this._children.values()) { try { child.kill?.("SIGKILL"); } catch { /* ignore */ } }
    this._children.clear();
    await super.dispose();
  }
}

export function createKimiHeadlessAdapter(options = {}) {
  return new KimiHeadlessAdapter(options);
}
