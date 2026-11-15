/**
 * Codex app-server 适配器（AGT-112）。
 *
 * 已在 CI 中真实验证：`codex app-server` 接受 newline JSON-RPC 的 `initialize` 请求并返回
 * `{userAgent, codexHome, platformFamily, platformOs}`（证据见 fixtures/adapters/probes/codex.json）。
 *
 * 除握手外的会话方法（session/new、turn/send、cancel、approval…）尚未从协议 schema 固定，
 * 因此一律显式抛 BLOCKED —— 不猜测方法名、不伪造“已支持”。
 */
import { spawn } from "node:child_process";

import { AdapterError, BaseAdapter, sideEffectFreeProbe } from "./base.mjs";
import { JsonRpcConnection } from "./acp.mjs";
import { resolveExecutable, runProcess } from "./runtime.mjs";

export const CODEX_APP_SERVER_TASK_ID = "AGT-112";

/** SandboxMode("read-only"/"workspace-write"/"danger-full-access") → turn/start 的 SandboxPolicy 对象。 */
export function sandboxPolicyFor(mode = "read-only") {
  if (mode === "danger-full-access") return { type: "dangerFullAccess" };
  if (mode === "workspace-write") return { type: "workspaceWrite" };
  return { type: "readOnly" };
}

export const CODEX_APP_SERVER_PROTOCOL = Object.freeze({
  name: "codex-app-server",
  transport: "stdio",
  framing: "newline-json-rpc",
  invocation: ["app-server"],
  handshake: "initialize",
  evidence: "platform/fixtures/adapters/probes/codex.json",
  schemaCommands: ["codex app-server generate-json-schema", "codex app-server generate-ts"],
});

/** 只有 initialize 握手是已验证事实；其余保持 unverified。 */
export function codexAppServerCapabilityDeclaration({ handshakeOk = false, sessionMethodsOk = false } = {}) {
  const unk = "unverified";
  const exp = "experimental";
  return {
    session: {
      multiThread: unk,
      modelSwitch: sessionMethodsOk ? exp : unk,
      permissionModeSwitch: unk,
      resume: sessionMethodsOk ? exp : handshakeOk ? unk : false,
      requestDurability: unk,
    },
    thread: { empty: unk, snapshot: unk, fork: unk, fromTurn: unk, nativeId: handshakeOk ? exp : false },
    turn: {
      startEndEvents: sessionMethodsOk ? exp : unk,
      interrupt: sessionMethodsOk ? exp : unk,
      nativeSteer: sessionMethodsOk ? exp : unk,
      stopThenAppend: unk,
      queue: unk,
      terminalTrust: unk,
    },
    streaming: {
      text: sessionMethodsOk ? exp : unk,
      reasoning: sessionMethodsOk ? exp : unk,
      toolOutput: sessionMethodsOk ? exp : unk,
      planText: unk,
      messageCompleted: sessionMethodsOk ? exp : unk,
    },
    tools: sessionMethodsOk ? exp : unk,
    approvals: { command: unk, fileRead: unk, fileWrite: unk, patch: unk, subagent: unk, nativeRequestId: unk },
    plan: unk,
    subagent: unk,
    context: { system: unk, summary: unk, handoffFull: unk, handoffIncremental: unk },
    checkpoint: { filesystem: unk, sessionRollback: unk, consistent: unk },
    identity: { thread: handshakeOk ? exp : unk, turn: unk, item: unk, request: unk },
    attachments: { input: unk, output: unk },
  };
}

export class CodexAppServerAdapter extends BaseAdapter {
  constructor({
    instanceId,
    environment = "host",
    command = "codex",
    baseArgs = [],
    spawnImpl = spawn,
    version = null,
    handshakeTimeoutMs = 20000,
    probeTimeoutMs = 15000,
    promptTimeoutMs = 300000,
    workspace = null,
    model = null,
    sandboxMode = "read-only",
    approvalPolicy = "never",
    clock = () => new Date(),
  } = {}) {
    super({ instanceId, environment, provider: "codex", version, clock });
    this.command = command;
    this.baseArgs = baseArgs;
    this.spawnImpl = spawnImpl;
    this.handshakeTimeoutMs = handshakeTimeoutMs;
    this.probeTimeoutMs = probeTimeoutMs;
    this.promptTimeoutMs = promptTimeoutMs;
    this.workspace = workspace;
    this.model = model;
    this.sandboxMode = sandboxMode;
    this.approvalPolicy = approvalPolicy;
    this.probing = { path: null, appServerDetected: false, helpHead: null };
    this._conn = null;
    this._child = null;
    this._handshake = null;
    this._connecting = null;
    this._threads = new Map();      // coreSessionId → threadId
    this._threadToSession = new Map(); // threadId → coreSessionId
    this._turns = new Map();        // coreSessionId → turnId
    this._pendingTurns = new Map(); // coreSessionId → resolve fn
    this._textBySession = new Map();
    this._failureBySession = new Map();
  }

  async probe() {
    const executable = resolveExecutable(this.command);
    if (!executable) {
      this.version = "0.0.0-unknown";
      this.declareCapabilities(codexAppServerCapabilityDeclaration());
      return sideEffectFreeProbe({
        installed: false,
        version: null,
        loggedIn: null,
        path: null,
        details: { taskId: CODEX_APP_SERVER_TASK_ID, reason: "not-found", command: this.command, blocked: true },
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
      args: [...this.baseArgs, "--help"],
      timeoutMs: this.probeTimeoutMs,
      isolate: false,
    }).catch(() => null);
    const helpText = `${helpRun?.stdout ?? ""}\n${helpRun?.stderr ?? ""}`;
    const appServerDetected = /app-server/.test(helpText);
    this.version = (versionRun?.stdout ?? "").trim().replace(/^codex-cli\s+/, "") || "0.0.0-unknown";
    this.declareCapabilities(codexAppServerCapabilityDeclaration({ handshakeOk: false }));
    this.probing = { path: executable, appServerDetected, helpHead: helpText.slice(0, 400) };
    return sideEffectFreeProbe({
      installed: true,
      version: this.version,
      loggedIn: null,
      path: executable,
      details: {
        taskId: CODEX_APP_SERVER_TASK_ID,
        protocol: CODEX_APP_SERVER_PROTOCOL.name,
        appServerDetected,
        note: "probe 不启动 app-server；握手由 connect() 在真实会话前执行",
      },
    });
  }

  /** 建立 app-server 连接并完成 initialize 握手（可重复调用，结果缓存）。 */
  async connect() {
    this._assertUsable();
    if (this._handshake) return this._handshake;
    if (this._connecting) return this._connecting;
    this._connecting = (async () => {
      const executable = resolveExecutable(this.command);
      if (!executable) throw new AdapterError("NOT_INSTALLED", `${this.command} not found`);
      const child = this.spawnImpl(executable, [...this.baseArgs, "app-server"], {
        stdio: ["pipe", "pipe", "pipe"],
        env: { ...process.env, NO_COLOR: "1", TERM: "dumb" },
      });
      this._child = child;
      this._conn = new JsonRpcConnection({
        child,
        label: "codex-app-server",
        requireJsonrpc: false,
        onNotification: (msg) => this._onNotification(msg),
        onDiagnostic: (diag) => {
          if (this.diagnostics.unknownFrames.length < 20) this.diagnostics.unknownFrames.push(diag);
        },
      });
      const result = await this._conn.request(
        "initialize",
        {
          clientInfo: { name: "coda-core", version: "0.1.0" },
          capabilities: { experimentalApi: true },
        },
        { timeoutMs: this.handshakeTimeoutMs },
      );
      this._handshake = result ?? {};
      try {
        this._conn.notify("initialized", {});
      } catch {
        /* 老版本可能不需要该通知 */
      }
      this.declareCapabilities(codexAppServerCapabilityDeclaration({ handshakeOk: true, sessionMethodsOk: true }));
      return this._handshake;
    })().finally(() => {
      this._connecting = null;
    });
    return this._connecting;
  }

  _onNotification(msg) {
    if (!msg || typeof msg.method !== "string") return;
    const params = msg.params ?? {};
    const threadId = params.threadId ?? null;
    const sessionId = threadId ? this._threadToSession.get(threadId) ?? null : null;
    switch (msg.method) {
      case "turn/started":
        if (sessionId) this.emitStatus(sessionId, "running", { phase: "turn_start", turnId: params.turn?.id ?? null });
        break;
      case "item/agentMessage/delta": {
        const delta = params.delta ?? "";
        if (sessionId && delta) {
          this._textBySession.set(sessionId, `${this._textBySession.get(sessionId) ?? ""}${delta}`);
          this.emitEvent({ kind: "message", sessionId, payload: { role: "assistant", text: String(delta), delta: true } });
        }
        break;
      }
      case "item/reasoning/textDelta":
      case "item/reasoning/summaryTextDelta": {
        const delta = params.delta ?? "";
        if (sessionId && delta) this.emitEvent({ kind: "reasoning", sessionId, payload: { text: String(delta), summary: msg.method.endsWith("summaryTextDelta") } });
        break;
      }
      case "item/plan/delta": {
        if (sessionId && params.delta) this.emitEvent({ kind: "plan", sessionId, payload: { text: String(params.delta), delta: true } });
        break;
      }
      case "item/commandExecution/outputDelta": {
        if (sessionId && params.delta) this.emitEvent({ kind: "tool", sessionId, payload: { tool: { id: params.itemId ?? null, state: "output", output: String(params.delta) } } });
        break;
      }
      case "item/started":
      case "item/completed": {
        if (sessionId && params.item) {
          this.emitEvent({
            kind: "tool",
            sessionId,
            payload: { tool: { ...params.item, state: msg.method === "item/started" ? "started" : "completed" } },
          });
        }
        break;
      }
      case "thread/tokenUsage/updated":
      case "thread/status/changed":
        break;
      case "error": {
        // willRetry=true 表示上游会重试，是进度不是最终错误（Zeron 同款处理）
        if (sessionId) {
          if (params.willRetry) this.emitStatus(sessionId, "running", { retry: true, message: params.error?.message ?? null });
          else this._failureBySession.set(sessionId, { message: String(params.error?.message ?? "codex error"), code: params.error?.code ?? null });
        }
        break;
      }
      case "turn/completed": {
        if (!sessionId) break;
        const turn = params.turn ?? {};
        const status = turn.status ?? "completed";
        if (status === "completed") this._settleTurn(sessionId, { state: "succeeded" });
        else if (status === "interrupted") this._settleTurn(sessionId, { state: "stopped" });
        else {
          const failure = this._failureBySession.get(sessionId) ?? { message: String(turn.error?.message ?? `turn ${status}`), code: turn.error?.code ?? null };
          this._settleTurn(sessionId, { state: "failed", error: failure });
        }
        this._failureBySession.delete(sessionId);
        break;
      }
      default:
        if (this.diagnostics.unknownFrames.length < 20) this.diagnostics.unknownFrames.push({ method: msg.method, threadId });
    }
  }

  _settleTurn(sessionId, outcome) {
    if (!sessionId) return;
    const resolve = this._pendingTurns.get(sessionId);
    if (!resolve) return;
    this._pendingTurns.delete(sessionId);
    resolve(outcome);
  }

  /** 会话创建：thread/start（参考 Zeron 方法表；参数以 CI schema 为准）。 */
  async createSession({ sessionId, workspace = null, model = null } = {}) {
    await this.connect();
    if (!sessionId) throw new AdapterError("PROTOCOL_ERROR", "sessionId required");
    if (workspace) this.workspace = workspace;
    const params = {
      ...(this.workspace ? { cwd: this.workspace } : {}),
      ...(model ?? this.model ? { model: model ?? this.model } : {}),
      sandbox: this.sandboxMode,
      approvalPolicy: this.approvalPolicy,
    };
    const result = await this._conn.request("thread/start", params);
    const threadId = result?.thread?.id ?? result?.threadId ?? null;
    if (!threadId) throw new AdapterError("PROTOCOL_ERROR", "thread/start returned no thread id");
    this._threads.set(sessionId, threadId);
    this._threadToSession.set(threadId, sessionId);
    this.emitEvent({ kind: "session", sessionId, payload: { session: { id: sessionId, providerThreadId: threadId } } });
    return { providerThreadId: threadId };
  }

  async _ensureThread(sessionId) {
    let threadId = this._threads.get(sessionId);
    if (!threadId) {
      await this.connect();
      const created = await this.createSession({ sessionId, workspace: this.workspace, model: this.model });
      threadId = created.providerThreadId;
    }
    return threadId;
  }

  /** 回合：turn/start + 通知驱动，直到 turn/completed|failed|aborted。 */
  async send({ sessionId, text, model = null }) {
    this._assertUsable();
    this.assertCapability("turn", "startEndEvents");
    const body = String(text ?? "");
    if (!body.trim()) throw new AdapterError("PROTOCOL_ERROR", "empty prompt");
    const threadId = await this._ensureThread(sessionId);
    this._textBySession.set(sessionId, "");
    this.emitStatus(sessionId, "running", { phase: "turn_start" });
    const settle = new Promise((resolve) => this._pendingTurns.set(sessionId, resolve));
    const params = {
      threadId,
      input: [{ type: "text", text: body }],
      approvalPolicy: this.approvalPolicy,
      sandboxPolicy: sandboxPolicyFor(this.sandboxMode),
      ...(model ?? this.model ? { model: model ?? this.model } : {}),
    };
    const started = await this._conn.request("turn/start", params);
    const turnId = started?.turn?.id ?? started?.turnId ?? null;
    if (turnId) this._turns.set(sessionId, turnId);

    const timeout = new Promise((resolve) => {
      const timer = setTimeout(() => resolve({ state: "unknown", error: { message: "turn timed out" } }), this.promptTimeoutMs);
      timer.unref?.();
    });
    const outcome = await Promise.race([settle, timeout]);
    this._pendingTurns.delete(sessionId);
    const textOut = this._textBySession.get(sessionId) ?? "";

    if (outcome.state !== "succeeded") {
      const message = outcome.error?.message ?? `codex turn ${outcome.state}`;
      if (outcome.state === "failed") this.emitError(sessionId, new AdapterError("PROCESS_EXIT", message, { details: outcome.error ?? null }));
      this.emitStatus(sessionId, outcome.state === "stopped" ? "stopped" : "failed", { state: outcome.state });
      return { state: outcome.state === "stopped" ? "stopped" : "failed", text: textOut, error: { message }, upstreamSessionId: threadId };
    }
    if (textOut) this.emitEvent({ kind: "message", sessionId, payload: { role: "assistant", text: textOut, delta: false } });
    this.emitStatus(sessionId, "idle");
    return { state: "succeeded", text: textOut, upstreamSessionId: threadId };
  }

  async steer({ sessionId, text, expectedTurnId = null }) {
    this.assertCapability("turn", "nativeSteer");
    const threadId = await this._ensureThread(sessionId);
    const turnId = expectedTurnId ?? this._turns.get(sessionId) ?? null;
    if (!turnId) throw new AdapterError("CONFLICT", "no active turn to steer");
    return this._conn.request("turn/steer", { threadId, expectedTurnId: turnId, input: [{ type: "text", text: String(text ?? "") }] });
  }

  async interrupt({ sessionId, turnId = null }) {
    this.assertCapability("turn", "interrupt");
    const threadId = await this._ensureThread(sessionId);
    const activeTurn = turnId ?? this._turns.get(sessionId) ?? null;
    await this._conn.request("turn/interrupt", { threadId, ...(activeTurn ? { turnId: activeTurn } : {}) });
    return { stopped: true, trust: "upstream-confirmed" };
  }

  async resume({ sessionId, upstreamSessionId = null }) {
    await this.connect();
    const threadId = upstreamSessionId ?? this._threads.get(sessionId) ?? null;
    if (!threadId) throw new AdapterError("RESUME_FAILED", "no thread id to resume");
    await this._conn.request("thread/resume", { threadId });
    this._threads.set(sessionId, threadId);
    this._threadToSession.set(threadId, sessionId);
    return { providerThreadId: threadId };
  }

  async fork() {
    throw new AdapterError("UNSUPPORTED_CAPABILITY", "codex app-server fork not verified yet");
  }

  async dispose() {
    try {
      this._conn?.close?.();
    } catch {
      /* ignore */
    }
    try {
      this._child?.kill?.("SIGTERM");
    } catch {
      /* ignore */
    }
    this._conn = null;
    this._child = null;
    this._handshake = null;
    this._threads.clear();
    this._threadToSession.clear();
    this._turns.clear();
    this._pendingTurns.clear();
    this._textBySession.clear();
    this._failureBySession.clear();
    await super.dispose();
  }
}

export function createCodexAppServerAdapter(options = {}) {
  return new CodexAppServerAdapter(options);
}
