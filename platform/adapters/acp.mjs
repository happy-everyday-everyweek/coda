/**
 * 通用 ACP 适配器（AGT-104 / AGT-ACP-GENERIC）。
 *
 * 协议：JSON-RPC 2.0 over stdio（行分隔 JSON），方法名取 Agent Client Protocol：
 *   initialize / session/new / session/load / session/prompt / session/cancel
 *   Agent → Client: session/update（通知）、session/request_permission（请求）
 *
 * 诚实边界（TODO 5.4 / SPEC 8）：
 *   - 只在 fake ACP server（人工构造协议实现）上验证过，没有任何真实 Agent 二进制冒烟，
 *     因此能力一律 experimental/unverified，矩阵 release = experimental，绝不标 supported。
 *   - ACP 没有原生 steer / fork，对应入口直接返回 UNSUPPORTED_CAPABILITY，
 *     由 lifecycle.mjs 走“停止 → 恢复 → 追加”的显式降级。
 */
import { createInterface } from "node:readline";

import { AdapterError, BaseAdapter, classifyUpstreamError, sideEffectFreeProbe } from "./base.mjs";
import { InteractionRegistry, interactionEventPayload } from "./interactions.mjs";
import { runProcess, spawnProcess, terminateProcess } from "./runtime.mjs";
import { makeId, nowIso } from "../contracts/ids.mjs";

export const ACP_PROTOCOL_VERSION = 1;

export const ACP_METHODS = Object.freeze({
  initialize: "initialize",
  sessionNew: "session/new",
  sessionLoad: "session/load",
  sessionPrompt: "session/prompt",
  sessionCancel: "session/cancel",
  sessionUpdate: "session/update",
  requestPermission: "session/request_permission",
});

export const ACP_RPC_ERRORS = Object.freeze({
  parseError: -32700,
  invalidRequest: -32600,
  methodNotFound: -32601,
  invalidParams: -32602,
  internalError: -32603,
});

const MAX_FRAME_BYTES = 1024 * 1024;

function sessionKeyOf(session) {
  if (!session) return null;
  return typeof session === "string" ? session : session?.path ?? null;
}

function turnStateFor(stopReason) {
  if (stopReason === "cancelled") return "stopped";
  if (stopReason === "max_tokens") return "completed";
  if (stopReason === "refusal") return "completed";
  return "completed";
}

/**
 * ACP 能力声明。所有值都是 experimental/unverified：
 * 只有 fake server 验证过，没有真实 Agent。
 */
export function acpCapabilityDeclaration({ agentCapabilities = null } = {}) {
  const canLoad = agentCapabilities?.loadSession === true;
  return {
    session: {
      multiThread: false,
      modelSwitch: false,
      permissionModeSwitch: false,
      resume: canLoad ? "experimental" : "unverified",
      requestDurability: "unverified",
    },
    thread: {
      empty: "experimental",
      snapshot: false,
      fork: false,
      fromTurn: false,
      nativeId: "experimental",
    },
    turn: {
      startEndEvents: "experimental",
      interrupt: "experimental",
      nativeSteer: false,
      stopThenAppend: "experimental",
      queue: false,
      terminalTrust: "experimental",
    },
    streaming: {
      text: "experimental",
      reasoning: "experimental",
      toolOutput: "experimental",
      planText: "experimental",
      messageCompleted: "experimental",
    },
    tools: "experimental",
    approvals: {
      command: "experimental",
      fileRead: "experimental",
      fileWrite: "experimental",
      patch: "experimental",
      subagent: "unverified",
      nativeRequestId: "experimental",
    },
    plan: "experimental",
    subagent: "unsupported",
    context: {
      system: "unsupported",
      summary: "unsupported",
      handoffFull: "unsupported",
      handoffIncremental: "unsupported",
    },
    checkpoint: { filesystem: "unsupported", sessionRollback: "unsupported", consistent: false },
    identity: {
      thread: "experimental",
      turn: "experimental",
      item: "unverified",
      request: "experimental",
    },
    attachments: { input: "unverified", output: "unverified" },
  };
}

/** 行分隔 JSON-RPC 连接（stdio）。 */
export class JsonRpcConnection {
  constructor({ child, label = "acp", maxFrameBytes = MAX_FRAME_BYTES, requireJsonrpc = true, onRequest = null, onNotification = null, onDiagnostic = null } = {}) {
    this.requireJsonrpc = requireJsonrpc;
    this.child = child;
    this.label = label;
    this.maxFrameBytes = maxFrameBytes;
    this.onRequest = onRequest;
    this.onNotification = onNotification;
    this.onDiagnostic = onDiagnostic;
    this.closed = false;
    this.closeError = null;
    this._nextId = 1;
    this._pending = new Map();
    this._handlers = new Set();
    this._reader = createInterface({ input: child.stdout, crlfDelay: Infinity });
    this._reader.on("line", (line) => this._onLine(line));
    this._reader.on("close", () => this.fail(new AdapterError("PROCESS_EXIT", "acp stdout closed")));
    child.on?.("close", (code, signal) => {
      this.fail(
        new AdapterError("PROCESS_EXIT", `acp process exited (code=${code}, signal=${signal})`, {
          details: { code, signal, stderr: this.stderrTail ?? null },
        }),
      );
    });
    child.on?.("error", (err) => {
      this.fail(new AdapterError("PROCESS_EXIT", `acp process error: ${err.message}`, { cause: err }));
    });
    this.stderrTail = "";
    child.stderr?.setEncoding?.("utf8");
    child.stderr?.on?.("data", (chunk) => {
      this.stderrTail = `${this.stderrTail}${chunk}`.slice(-4096);
    });
  }

  _write(message) {
    if (this.closed) throw this.closeError ?? new AdapterError("DISPOSED", "acp connection closed");
    const line = `${JSON.stringify(message)}\n`;
    try {
      this.child.stdin.write(line);
    } catch (err) {
      throw new AdapterError("PROTOCOL_ERROR", `failed to write to acp process: ${err.message}`, { cause: err });
    }
  }

  _onLine(line) {
    const text = line.trim();
    if (!text) return;
    if (text.length > this.maxFrameBytes) {
      this.onDiagnostic?.({ kind: "frame-too-large", bytes: text.length });
      return;
    }
    let message;
    try {
      message = JSON.parse(text);
    } catch (err) {
      this.onDiagnostic?.({ kind: "unparsable-frame", error: err.message });
      return;
    }
    if (this.requireJsonrpc && message && typeof message === "object" && message.jsonrpc !== "2.0") {
      this.onDiagnostic?.({ kind: "non-jsonrpc-frame", message: Object.keys(message).slice(0, 8) });
      return;
    }
    if (message && typeof message.method === "string") {
      if (message.id !== undefined && message.id !== null) this._handleRequest(message);
      else this.onNotification?.(message);
      return;
    }
    if (message && message.id !== undefined) {
      this._handleResponse(message);
      return;
    }
    this.onDiagnostic?.({ kind: "unknown-frame", keys: Object.keys(message ?? {}) });
  }

  _handleResponse(message) {
    const entry = this._pending.get(message.id);
    if (!entry) {
      this.onDiagnostic?.({ kind: "unexpected-response", id: message.id });
      return;
    }
    this._pending.delete(message.id);
    clearTimeout(entry.timer);
    if (message.error) {
      entry.reject(
        new AdapterError(classifyUpstreamError(message.error.message), message.error.message ?? "upstream error", {
          details: { rpcCode: message.error.code, data: message.error.data ?? null },
        }),
      );
      return;
    }
    entry.resolve(message.result ?? {});
  }

  _handleRequest(message) {
    if (!this.onRequest) {
      this._respondSafely(message.id, ACP_RPC_ERRORS.methodNotFound, `unsupported client method: ${message.method}`);
      return;
    }
    Promise.resolve()
      .then(() => this.onRequest(message))
      .then((result) => {
        if (result !== undefined) this._write({ jsonrpc: "2.0", id: message.id, result });
      })
      .catch((err) => {
        // 连接已关闭时不再回包，也不能产生未处理的 rejection。
        if (this.closed) return;
        this._respondSafely(message.id, ACP_RPC_ERRORS.internalError, err?.message ?? String(err));
      });
  }

  _respondSafely(id, code, message) {
    try {
      if (!this.closed) this.respondError(id, code, message);
    } catch {
      /* 进程已退出：协议回包失败不影响适配器状态 */
    }
  }

  request(method, params = {}, { timeoutMs = 60_000 } = {}) {
    if (this.closed) return Promise.reject(this.closeError ?? new AdapterError("DISPOSED", "acp connection closed"));
    const id = this._nextId++;
    return new Promise((resolve, reject) => {
      const timer =
        Number.isFinite(timeoutMs) && timeoutMs > 0
          ? setTimeout(() => {
              this._pending.delete(id);
              reject(new AdapterError("TIMEOUT", `acp request timed out: ${method}`, { details: { method, timeoutMs } }));
            }, timeoutMs)
          : null;
      timer?.unref?.();
      this._pending.set(id, { method, resolve, reject, timer });
      try {
        this._write({ jsonrpc: "2.0", id, method, params });
      } catch (err) {
        this._pending.delete(id);
        if (timer) clearTimeout(timer);
        reject(err);
      }
    });
  }

  notify(method, params = {}) {
    this._write({ jsonrpc: "2.0", method, params });
  }

  respond(id, result) {
    this._write({ jsonrpc: "2.0", id, result });
  }

  respondError(id, code, message) {
    this._write({ jsonrpc: "2.0", id, error: { code, message } });
  }

  fail(error) {
    if (this.closed) return;
    this.closed = true;
    this.closeError = error;
    for (const [id, entry] of this._pending) {
      this._pending.delete(id);
      clearTimeout(entry.timer);
      entry.reject(error);
    }
    try {
      this._reader.close();
    } catch {
      /* ignore */
    }
  }

  close(error = null) {
    this.fail(error ?? new AdapterError("DISPOSED", "acp connection closed by adapter"));
    try {
      this.child?.stdin?.end?.();
    } catch {
      /* ignore */
    }
  }
}

export class AcpAdapter extends BaseAdapter {
  constructor({
    instanceId,
    environment = "host",
    command = "acp-agent",
    args = [],
    probeArgs = ["--version"],
    provider = "acp-generic",
    version = null,
    cwd = null,
    env = null,
    allowEnv = [],
    overrideEnv = {},
    pathAllowlist = null,
    promptTimeoutMs = 120_000,
    requestTimeoutMs = 60_000,
    permissionTimeoutMs = 5 * 60 * 1000,
    killGraceMs = 300,
    maxFrameBytes = MAX_FRAME_BYTES,
    clock = () => Date.now(),
  } = {}) {
    super({ instanceId, environment, provider, version, clock: () => new Date(clock()) });
    this.command = command;
    this.args = [...args];
    this.probeArgs = [...probeArgs];
    this.cwd = cwd;
    this.env = env;
    this.allowEnv = allowEnv;
    this.overrideEnv = overrideEnv;
    this.pathAllowlist = pathAllowlist;
    this.promptTimeoutMs = promptTimeoutMs;
    this.requestTimeoutMs = requestTimeoutMs;
    this.permissionTimeoutMs = permissionTimeoutMs;
    this.killGraceMs = killGraceMs;
    this.maxFrameBytes = maxFrameBytes;
    this._child = null;
    this._conn = null;
    this._closing = false;
    this._agentCapabilities = null;
    this._sessionsByProvider = new Map();
    this._turns = new Map();
    this._initResult = null;

    this.interactions = new InteractionRegistry({
      clock,
      onChange: (interaction) => {
        if (this._disposed || this._closing) return;
        // interaction 是运行期事实；这里只做展示/审批投影，权威状态仍在 core。
        this.emitEvent({ kind: "interaction", sessionId: interaction.sessionId, payload: interactionEventPayload(interaction) });
      },
    });
  }

  // ------------------------------------------------------------ 探测

  async probe() {
    let result = null;
    let error = null;
    try {
      result = await runProcess({
        command: this.command,
        args: this.probeArgs,
        cwd: this.cwd,
        env: this.env,
        allowEnv: this.allowEnv,
        overrideEnv: this.overrideEnv,
        pathAllowlist: this.pathAllowlist,
        timeoutMs: 5000,
        maxOutputBytes: 16 * 1024,
      });
    } catch (err) {
      error = err;
    }
    const installed = !!result && !result.timedOut && result.code !== null;
    const raw = `${result?.stdout ?? ""}${result?.stderr ?? ""}`.trim();
    const match = /(\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?)/.exec(raw);
    this.version = installed ? match?.[1] ?? "unknown" : "0.0.0-unknown";
    this.declareCapabilities(acpCapabilityDeclaration({ agentCapabilities: this._agentCapabilities }));
    return sideEffectFreeProbe({
      installed,
      version: installed ? this.version : null,
      loggedIn: null,
      path: installed ? result.command : null,
      details: {
        probeArgs: this.probeArgs,
        exitCode: result?.code ?? null,
        timedOut: result?.timedOut ?? false,
        rawVersionOutput: raw.slice(0, 512),
        error: error ? { code: error.code, message: error.message } : null,
      },
    });
  }

  // ------------------------------------------------------------ 连接与握手

  async _connect() {
    this._assertUsable();
    if (this._conn && !this._conn.closed) return this._conn;
    const child = spawnProcess({
      command: this.command,
      args: this.args,
      cwd: this.cwd,
      env: this.env,
      allowEnv: this.allowEnv,
      overrideEnv: this.overrideEnv,
      pathAllowlist: this.pathAllowlist,
    });
    this._child = child;
    const conn = new JsonRpcConnection({
      child,
      maxFrameBytes: this.maxFrameBytes,
      onNotification: (msg) => this._handleNotification(msg),
      onRequest: (msg) => this._handleAgentRequest(msg),
      onDiagnostic: (diag) => this._recordDiagnostic(diag),
    });
    this._conn = conn;
    const init = await conn.request(
      ACP_METHODS.initialize,
      {
        protocolVersion: ACP_PROTOCOL_VERSION,
        clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false },
      },
      { timeoutMs: this.requestTimeoutMs },
    );
    this._initResult = init;
    this._agentCapabilities = init?.agentCapabilities ?? null;
    this.declareCapabilities(acpCapabilityDeclaration({ agentCapabilities: this._agentCapabilities }));
    return conn;
  }

  _recordDiagnostic(diag) {
    const entry = { ...diag, at: nowIso() };
    this.diagnostics.unknownFrames.push(entry);
    if (this.diagnostics.unknownFrames.length > 50) this.diagnostics.unknownFrames.shift();
  }

  _sessionIdFor(providerSessionId) {
    return this._sessionsByProvider.get(providerSessionId) ?? null;
  }

  // ------------------------------------------------------------ 会话

  async createSession({ sessionId, workspace = null, model = null, options = {} } = {}) {
    this._assertUsable();
    const conn = await this._connect();
    const cwd = sessionKeyOf(workspace) ?? this.cwd ?? process.cwd();
    const result = await conn.request(
      ACP_METHODS.sessionNew,
      { cwd, mcpServers: options.mcpServers ?? [] },
      { timeoutMs: this.requestTimeoutMs },
    );
    const providerSessionId = result?.sessionId ?? null;
    if (!providerSessionId) {
      throw new AdapterError("PROTOCOL_ERROR", "session/new returned no sessionId", { details: { result } });
    }
    const record = this.registerSession({ sessionId: sessionId ?? makeId("ses"), providerSessionId, workspace, model, options });
    this._sessionsByProvider.set(providerSessionId, record.sessionId);
    this.emitEvent({
      kind: "session",
      payload: { state: "created", sessionId: record.sessionId, providerSessionId, cwd, provider: this.provider },
    });
    return record;
  }

  async resume({ sessionId } = {}) {
    this._assertUsable();
    const record = this.getSession(sessionId);
    if (!record.providerSessionId) {
      throw new AdapterError("SESSION_NOT_FOUND", "session has no provider session reference to resume");
    }
    const conn = await this._connect();
    if (this._agentCapabilities?.loadSession !== true) {
      throw new AdapterError(
        "UNSUPPORTED_CAPABILITY",
        "upstream ACP agent did not declare loadSession; core must not pretend the session resumed",
        { details: { providerSessionId: record.providerSessionId } },
      );
    }
    await conn.request(
      ACP_METHODS.sessionLoad,
      { sessionId: record.providerSessionId, cwd: sessionKeyOf(record.workspace) ?? this.cwd ?? process.cwd(), mcpServers: [] },
      { timeoutMs: this.requestTimeoutMs },
    );
    record.state = "idle";
    return { sessionId: record.sessionId, providerSessionId: record.providerSessionId, resumed: true };
  }

  async fork() {
    throw new AdapterError(
      "UNSUPPORTED_CAPABILITY",
      "ACP has no native session fork; core must create a new session with an explicit summary",
    );
  }

  // ------------------------------------------------------------ 回合

  promptBlocks({ text = null, attachments = [] } = {}) {
    const blocks = [];
    if (text) blocks.push({ type: "text", text: String(text) });
    for (const attachment of attachments) {
      const mime = attachment?.mimeType ?? null;
      if (attachment?.data && mime && mime.startsWith("image/")) {
        blocks.push({ type: "image", data: attachment.data, mimeType: mime });
        continue;
      }
      // 路径引用不是授权（SPEC 8.2）：这里只是文本降级，不读取文件。
      blocks.push({
        type: "text",
        text: `[attachment reference: ${attachment?.name ?? attachment?.path ?? "unnamed"} (${
          attachment?.size ?? "unknown"
        } bytes); the agent must read it itself, this reference is not an access grant]`,
      });
    }
    return blocks;
  }

  async send({ sessionId, text = null, attachments = [], turnId = null, timeoutMs = null } = {}) {
    this._assertUsable();
    const record = this.getSession(sessionId);
    if (!record.providerSessionId) throw new AdapterError("SESSION_NOT_FOUND", "session has no provider session reference");
    if (this._turns.has(sessionId)) {
      throw new AdapterError("TURN_IN_PROGRESS", "a turn is already running; queue or stop first", {
        details: { sessionId },
      });
    }
    const conn = await this._connect();
    const id = turnId ?? makeId("turn");
    const turn = { turnId: id, state: "running", startedAt: nowIso(), stopReason: null };
    this._turns.set(sessionId, turn);
    record.state = "running";
    this.emitEvent({ kind: "turn", sessionId, payload: { turnId: id, state: "running", startedAt: turn.startedAt } });
    this.emitStatus(sessionId, "running", { turnId: id });

    try {
      const result = await conn.request(
        ACP_METHODS.sessionPrompt,
        { sessionId: record.providerSessionId, prompt: this.promptBlocks({ text, attachments }) },
        { timeoutMs: timeoutMs ?? this.promptTimeoutMs },
      );
      const stopReason = result?.stopReason ?? "end_turn";
      const state = turn.state === "stopping" ? "stopped" : turnStateFor(stopReason);
      turn.state = state;
      turn.stopReason = stopReason;
      record.state = state === "stopped" ? "stopped" : "idle";
      this.emitEvent({
        kind: "turn",
        sessionId,
        payload: { turnId: id, state, stopReason, endedAt: nowIso() },
      });
      this.emitStatus(sessionId, record.state, { turnId: id, stopReason });
      this._turns.delete(sessionId);
      return { turnId: id, state, stopReason, providerSessionId: record.providerSessionId };
    } catch (error) {
      const err = error instanceof AdapterError ? error : new AdapterError(classifyUpstreamError(error?.message), error?.message ?? String(error), { cause: error });
      const stopReason = err.code === "TIMEOUT" ? "timeout" : err.code === "PROCESS_EXIT" ? "process-exit" : "error";
      turn.state = err.code === "TIMEOUT" || err.code === "PROCESS_EXIT" ? "unknown" : "failed";
      turn.stopReason = stopReason;
      record.state = turn.state === "unknown" ? "unknown" : "failed";
      if (!this._disposed && !this._closing) {
        if (err.code !== "DISPOSED") {
          this.emitEvent({ kind: "turn", sessionId, payload: { turnId: id, state: turn.state, stopReason, endedAt: nowIso() } });
          this.emitError(sessionId, err, { turnId: id });
          this.emitStatus(sessionId, record.state, { turnId: id, error: err.code });
        }
      }
      this._turns.delete(sessionId);
      throw err;
    }
  }

  async steer() {
    throw new AdapterError(
      "UNSUPPORTED_CAPABILITY",
      "ACP has no native steer; use lifecycle steerOrFallback (stop → resume → append)",
    );
  }

  async interrupt({ sessionId, turnId = null } = {}) {
    this._assertUsable();
    const record = this.getSession(sessionId);
    const turn = this._turns.get(sessionId);
    if (!turn) {
      this.emitStatus(sessionId, record.state);
      return { sessionId, state: record.state, upstreamConfirmed: false, alreadyIdle: record.state !== "running" };
    }
    if (turnId && turn.turnId !== turnId) {
      throw new AdapterError("PROTOCOL_ERROR", `turn mismatch: running ${turn.turnId}, requested ${turnId}`);
    }
    turn.state = "stopping";
    const cancelled = this.interactions.cancelForSession(sessionId, "turn-stopped");
    const conn = this._conn;
    if (!conn || conn.closed) {
      // 进程已不可用：只能给出“未知”，不能显示成已停止（ADP-005）。
      turn.state = "unknown";
      record.state = "unknown";
      if (!this._disposed && !this._closing) {
        this.emitEvent({ kind: "turn", sessionId, payload: { turnId: turn.turnId, state: "unknown", reason: "process-unavailable" } });
        this.emitStatus(sessionId, "unknown", { turnId: turn.turnId });
      }
      return { sessionId, turnId: turn.turnId, state: "unknown", upstreamConfirmed: false, closedInteractions: cancelled.length };
    }
    this.emitEvent({ kind: "turn", sessionId, payload: { turnId: turn.turnId, state: "stopping" } });
    conn.notify(ACP_METHODS.sessionCancel, { sessionId: record.providerSessionId });
    return {
      sessionId,
      turnId: turn.turnId,
      state: "stopping",
      upstreamConfirmed: "pending",
      closedInteractions: cancelled.length,
    };
  }

  // ------------------------------------------------------------ 交互

  /** 回答审批/提问；optionId 必须是上游给出的原生 ID。 */
  answerInteraction(interactionId, { optionId = null, text = null, by = "user", reason = null } = {}) {
    return this.interactions.answer(interactionId, { optionId, text, by, reason });
  }

  pendingInteractions(sessionId = null) {
    return this.interactions.pending(sessionId);
  }

  // ------------------------------------------------------------ 上行消息处理

  async _handleAgentRequest(message) {
    if (message.method === ACP_METHODS.requestPermission) {
      return this._handlePermissionRequest(message);
    }
    this._recordDiagnostic({ kind: "unsupported-agent-request", method: message.method });
    throw new AdapterError("UNSUPPORTED_CAPABILITY", `unsupported agent request: ${message.method}`);
  }

  async _handlePermissionRequest(message) {
    const params = message.params ?? {};
    const sessionId = this._sessionIdFor(params.sessionId) ?? params.sessionId ?? null;
    if (!sessionId) {
      this._recordDiagnostic({ kind: "permission-without-session", params });
      return { outcome: { outcome: "cancelled" } };
    }
    const interaction = this.interactions.createPermission({
      sessionId,
      nativeRequestId: String(message.id),
      title: params.toolCall?.title ?? "Permission required",
      toolCall: params.toolCall ?? {},
      options: (params.options ?? []).map((o) => ({
        optionId: o.optionId,
        label: o.name ?? o.label ?? o.optionId,
        kind: o.kind ?? "other",
      })),
      source: params.source?.origin ? params.source : { origin: params.subagentId ? "subagent" : "main", subagentId: params.subagentId ?? null },
      expiresAt: params.expiresAt ?? null,
      ttlMs: this.permissionTimeoutMs,
    });

    const settled = await this.interactions
      .waitForAnswer(interaction.interactionId, this.permissionTimeoutMs)
      .catch(() => {
        if (interaction.state === "pending") this.interactions.expire(interaction.interactionId);
        return interaction;
      });
    return this.interactions.upstreamOutcome(settled);
  }

  _handleNotification(message) {
    if (message.method === ACP_METHODS.sessionUpdate) {
      this._mapSessionUpdate(message.params ?? {});
      return;
    }
    this._recordDiagnostic({ kind: "unknown-notification", method: message.method });
    const sessionId = this._sessionIdFor(message.params?.sessionId ?? null);
    if (sessionId && !this._disposed && !this._closing) {
      this.emitEvent({
        kind: "status",
        sessionId,
        payload: { state: "upstream-notice", source: "acp", method: message.method },
      });
    }
  }

  /** ACP session/update → 统一事件。未知 update 只进诊断（ADP-013）。 */
  _mapSessionUpdate(params) {
    const providerSessionId = params.sessionId ?? null;
    const sessionId = this._sessionIdFor(providerSessionId) ?? providerSessionId;
    const update = params.update ?? params;
    const type = update?.sessionUpdate ?? update?.type ?? null;
    if (!sessionId || !type) {
      this._recordDiagnostic({ kind: "malformed-session-update", params });
      return;
    }
    switch (type) {
      case "agent_message_chunk": {
        const text = update.content?.text ?? "";
        if (update.content && update.content.type && update.content.type !== "text") {
          this._recordDiagnostic({ kind: "non-text-message-chunk", contentType: update.content.type });
          break;
        }
        this.emitEvent({ kind: "message", sessionId, payload: { role: "assistant", text, delta: true } });
        break;
      }
      case "agent_thought_chunk":
        this.emitEvent({
          kind: "reasoning",
          sessionId,
          payload: { text: update.content?.text ?? "", delta: true },
        });
        break;
      case "user_message_chunk":
        this.emitEvent({
          kind: "message",
          sessionId,
          payload: { role: "user", text: update.content?.text ?? "", delta: true },
        });
        break;
      case "tool_call":
        this.emitEvent({
          kind: "tool",
          sessionId,
          payload: {
            toolCallId: update.toolCallId ?? update.id ?? null,
            title: update.title ?? null,
            toolKind: update.kind ?? null,
            status: update.status ?? "pending",
            started: true,
            rawInput: update.rawInput ?? null,
          },
        });
        break;
      case "tool_call_update":
        this.emitEvent({
          kind: "tool",
          sessionId,
          payload: {
            toolCallId: update.toolCallId ?? update.id ?? null,
            status: update.status ?? "updated",
            started: false,
            output: update.rawOutput ?? update.content ?? null,
          },
        });
        break;
      case "plan":
        this.emitEvent({
          kind: "plan",
          sessionId,
          payload: {
            entries: (update.entries ?? []).map((e) => ({
              content: e.content ?? null,
              status: e.status ?? "pending",
              priority: e.priority ?? null,
            })),
          },
        });
        break;
      case "available_commands_update":
        this.emitEvent({
          kind: "status",
          sessionId,
          payload: { state: "commands.updated", count: (update.availableCommands ?? []).length },
        });
        break;
      default:
        this._recordDiagnostic({ kind: "unknown-session-update", type });
        this.emitEvent({ kind: "status", sessionId, payload: { state: "unknown", source: "acp", updateType: type } });
        break;
    }
  }

  // ------------------------------------------------------------ 诊断与释放

  diagnosticsSnapshot() {
    return {
      provider: this.provider,
      instanceId: this.instanceId,
      version: this.version,
      sessions: this.listSessions().length,
      activeTurns: [...this._turns.keys()],
      pendingInteractions: this.interactions.pending().length,
      unknownFrames: this.diagnostics.unknownFrames.slice(-20),
      stderrTail: this._conn?.stderrTail ?? null,
    };
  }

  async dispose() {
    if (this._disposed) return;
    this._closing = true;
    for (const interaction of this.interactions.pending()) {
      try {
        this.interactions.supersede(interaction.interactionId, "adapter-disposed");
      } catch {
        /* ignore */
      }
    }
    try {
      this._conn?.close(new AdapterError("DISPOSED", "adapter disposed"));
    } catch {
      /* ignore */
    }
    if (this._child) {
      try {
        await terminateProcess(this._child, { graceMs: this.killGraceMs });
      } catch {
        /* ignore */
      }
    }
    this._child = null;
    this._conn = null;
    this._turns.clear();
    this._sessionsByProvider.clear();
    await super.dispose();
    this._closing = false;
  }
}

export function createAcpAdapter(options) {
  return new AcpAdapter(options);
}
