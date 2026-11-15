/**
 * CORE-106：Mock Core / Mock Adapter 与可重复协议场景。
 *
 * 目的：三种界面（TUI/GUI/Web）用同一套 Mock 场景验证「相同输入 → 相同状态、相同事件语义」，
 * 不依赖任何真实 Agent 进程。这里不伪造真实能力：能力声明来自构造参数，未声明的能力为 false。
 *
 * 场景支持：会话创建、流式消息、工具输出、计划、审批/提问、停止、崩溃（结果未知）。
 */
import { makeId, nowIso } from "../contracts/ids.mjs";
import { makeCapabilityDeclaration } from "../contracts/capabilities.mjs";
import { ProviderAdapter } from "../contracts/adapter.mjs";
import { METHODS, rpcRequest } from "../contracts/rpc.mjs";
import { NotFoundError, ValidationError } from "./errors.mjs";

export const MOCK_CAPABILITIES = {
  session: { multiThread: false, modelSwitch: true, permissionMode: false, resume: true, persistentRequests: true },
  thread: { empty: true, snapshot: false, fork: true, fromTurn: false, nativeThreadId: true },
  turn: { start: true, end: true, interrupt: true, steer: true, queue: true, trustedTerminal: true },
  streaming: { content: true, reasoning: true, toolOutput: true, planText: true, messageComplete: true },
  tools: { id: true, start: true, end: true, output: true, mcp: false, dynamicCallback: false },
  approvals: { command: true, fileRead: true, fileWrite: true, patch: false, subagent: false, nativeRequestId: true },
  plan: { todo: false, structured: true, executable: false, questions: true },
  subagent: { lifecycle: false, thread: false, wait: false, close: false, fork: false },
  context: { system: true, summary: false, fullHandoff: false, incrementalHandoff: false },
  checkpoint: { fileSnapshot: false, sessionRollback: false, consistent: false },
  identity: { thread: true, turn: true, entry: false, request: true },
  attachments: { input: ["text"], maxBytes: 1024 * 1024, output: true },
};

export const DEFAULT_SCRIPT = {
  reasoning: null,
  plan: null,
  messages: ["mock reply"],
  tool: null,
  needsApproval: false,
  approvalPrompt: "允许执行 mock 工具吗？",
  fail: false,
  crash: false,
  delayMs: 0,
};

export class MockAdapter extends ProviderAdapter {
  #counter = 0;
  #trace = [];
  #pendingInteractions = new Map();
  #crashed = false;
  #disposed = false;
  #turnEndWaiters = [];
  #sessionId = null;

  constructor({
    instanceId = makeId("ai"),
    provider = "mock",
    version = "1.0.0",
    environment = "host",
    capabilities = MOCK_CAPABILITIES,
    script = {},
    clock = () => new Date(),
    latencyMs = 0,
    sessionId = null,
  } = {}) {
    super({ instanceId, environment });
    this.provider = provider;
    this.version = version;
    this.clock = clock;
    this.latencyMs = Math.max(0, latencyMs);
    this.script = { ...DEFAULT_SCRIPT, ...script };
    this.declaration = makeCapabilityDeclaration({ provider, version, environment, capabilities });
    this.#sessionId = sessionId;
    this.threadSeq = 0;
    this.turnSeq = 0;
  }

  get crashed() {
    return this.#crashed;
  }

  get disposed() {
    return this.#disposed;
  }

  /** 事件订阅（core 优先用这个，而不是 async iterator）。 */
  onEvent(fn) {
    if (typeof fn !== "function") throw new ValidationError("onEvent(fn) requires a function");
    this._listeners.add(fn);
    return () => this._listeners.delete(fn);
  }

  async probe() {
    return { installed: true, version: this.version, loggedIn: true, path: "mock://adapter", details: { provider: this.provider } };
  }

  capabilities() {
    return this.declaration;
  }

  async createSession({ sessionId = null, workspace = null, model = null } = {}) {
    if (this.#crashed) throw new Error("mock adapter crashed");
    this.#sessionId = sessionId ?? this.#sessionId ?? makeId("ses");
    this.threadSeq += 1;
    this.providerThreadId = `mock-thread-${this.threadSeq}`;
    this.emit({
      kind: "session",
      sessionId: this.#sessionId,
      payload: {
        sessionId: this.#sessionId,
        state: "idle",
        providerThreadId: this.providerThreadId,
        model,
        workspace: workspace?.path ?? null,
      },
    });
    return { sessionId: this.#sessionId, providerThreadId: this.providerThreadId, model };
  }

  #emit(event) {
    const enriched = {
      ts: nowIso(),
      ...event,
      payload: { ...(event.payload ?? {}), mock: true },
    };
    this.#trace.push(enriched);
    if (enriched.kind === "turn" && ["completed", "failed", "unknown", "stopped"].includes(enriched.payload?.state)) {
      const waiters = this.#turnEndWaiters;
      this.#turnEndWaiters = [];
      for (const resolve of waiters) resolve(enriched);
    }
    if (!this.#disposed) this.emit(enriched);
    return enriched;
  }

  emit(event) {
    super.emit(event);
  }

  #nextRequestId() {
    this.#counter += 1;
    return `mock-request-${this.#counter}`;
  }

  async send({ sessionId = this.#sessionId, turnId = null, text = "", attachments = [] } = {}) {
    if (this.#crashed) throw new Error("mock adapter crashed");
    if (!sessionId) throw new ValidationError("send requires sessionId");
    this.#sessionId = sessionId;
    this.turnSeq += 1;
    const providerTurnId = `mock-turn-${this.turnSeq}`;
    this.#emit({ kind: "turn", sessionId, payload: { turnId, providerTurnId, state: "running", text } });
    this.#emit({ kind: "status", sessionId, payload: { state: "running", providerTurnId } });

    const script = this.script;
    if (script.plan) {
      this.#emit({ kind: "plan", sessionId, payload: { text: String(script.plan), turnId } });
    }
    if (script.reasoning) {
      this.#emit({ kind: "reasoning", sessionId, payload: { text: String(script.reasoning), turnId, delta: false } });
    }
    if (script.tool) {
      const toolId = `mock-tool-${this.turnSeq}`;
      this.#emit({ kind: "tool", sessionId, payload: { toolId, name: script.tool.name ?? "mock_tool", state: "started", input: script.tool.input ?? {}, turnId } });
      this.#emit({ kind: "tool", sessionId, payload: { toolId, state: "finished", output: script.tool.output ?? "ok", turnId } });
    }

    if (script.fail) {
      const failure = this.#emit({
        kind: "turn",
        sessionId,
        payload: { turnId, providerTurnId, state: "failed", error: { message: "mock failure", retryable: false } },
      });
      return { state: "failed", turnId, error: failure.payload.error };
    }

    if (script.crash) {
      return this.crash({ sessionId, turnId, reason: "scripted crash" });
    }

    if (script.needsApproval) {
      const requestId = this.#nextRequestId();
      this.#pendingInteractions.set(requestId, { requestId, sessionId, turnId, providerTurnId });
      this.#emit({
        kind: "interaction",
        sessionId,
        payload: {
          requestId,
          interactionKind: "approval",
          state: "pending",
          turnId,
          prompt: script.approvalPrompt,
          options: [
            { id: "allow", label: "允许" },
            { id: "deny", label: "拒绝" },
          ],
        },
      });
      return { state: "awaiting_interaction", turnId, requestId, providerTurnId };
    }

    for (const chunk of [].concat(script.messages ?? [])) {
      this.#emit({ kind: "message", sessionId, payload: { role: "assistant", text: String(chunk), delta: true, turnId } });
    }
    // 收尾消息带完整文本且 delta=false，客户端按同一 message id 覆盖为最终内容。
    this.#emit({
      kind: "message",
      sessionId,
      payload: {
        role: "assistant",
        text: [].concat(script.messages ?? []).map(String).join(""),
        delta: false,
        streaming: false,
        complete: true,
        turnId,
      },
    });
    const finished = this.#emit({
      kind: "turn",
      sessionId,
      payload: { turnId, providerTurnId, state: "completed", terminalConfidence: "confirmed" },
    });
    return { state: "completed", turnId, providerTurnId, event: finished };
  }

  /** 回答审批/提问；未找到即报错（不伪造成功）。 */
  async answerInteraction({ requestId, answer = null } = {}) {
    const pending = this.#pendingInteractions.get(requestId);
    if (!pending) throw new NotFoundError(`unknown mock interaction: ${requestId}`, { requestId });
    this.#pendingInteractions.delete(requestId);
    this.#emit({
      kind: "interaction",
      sessionId: pending.sessionId,
      payload: { requestId, state: "answered", answer, turnId: pending.turnId },
    });
    const approved = answer === null || answer === "allow" || answer === true || answer?.optionId === "allow";
    if (!approved) {
      this.#emit({
        kind: "turn",
        sessionId: pending.sessionId,
        payload: { turnId: pending.turnId, state: "completed", stopReason: "denied", terminalConfidence: "confirmed" },
      });
      return { requestId, approved: false, state: "completed" };
    }
    this.#emit({
      kind: "message",
      sessionId: pending.sessionId,
      payload: { role: "assistant", text: "approved and continued", delta: false, turnId: pending.turnId },
    });
    this.#emit({
      kind: "turn",
      sessionId: pending.sessionId,
      payload: { turnId: pending.turnId, state: "completed", terminalConfidence: "confirmed", approved: true },
    });
    return { requestId, approved: true, state: "completed" };
  }

  async interrupt({ sessionId = this.#sessionId, turnId = null } = {}) {
    if (!sessionId) throw new ValidationError("interrupt requires sessionId");
    this.#emit({
      kind: "turn",
      sessionId,
      payload: { turnId, state: "completed", stopReason: "interrupted", terminalConfidence: "confirmed" },
    });
    this.#emit({ kind: "status", sessionId, payload: { state: "stopped" } });
    return { stopped: true, turnId };
  }

  async steer({ sessionId = this.#sessionId, turnId = null, text = "" } = {}) {
    if (this.#crashed) throw new Error("mock adapter crashed");
    this.#emit({ kind: "message", sessionId, payload: { role: "user", text: String(text), delta: false, steered: true, turnId } });
    this.#emit({ kind: "turn", sessionId, payload: { turnId, state: "completed", terminalConfidence: "confirmed", steered: true } });
    return { steered: true, turnId };
  }

  async resume({ sessionId = this.#sessionId } = {}) {
    if (this.#crashed) throw new Error("mock adapter crashed");
    this.#sessionId = sessionId ?? this.#sessionId;
    this.#emit({ kind: "status", sessionId: this.#sessionId, payload: { state: "resumed", providerThreadId: this.providerThreadId ?? null } });
    return { sessionId: this.#sessionId, providerThreadId: this.providerThreadId ?? null };
  }

  async fork({ sessionId = this.#sessionId } = {}) {
    if (this.#crashed) throw new Error("mock adapter crashed");
    this.threadSeq += 1;
    const providerThreadId = `mock-thread-${this.threadSeq}`;
    this.#emit({ kind: "status", sessionId, payload: { state: "forked", providerThreadId } });
    return { sessionId, providerThreadId, forked: true };
  }

  /** 模拟崩溃：发出 error + 结果未知，之后 send 会失败。 */
  crash({ sessionId = this.#sessionId, turnId = null, reason = "mock crash" } = {}) {
    this.#crashed = true;
    if (sessionId) {
      this.#emit({ kind: "error", sessionId, payload: { message: reason, code: "MOCK_CRASH", turnId } });
      this.#emit({
        kind: "turn",
        sessionId,
        payload: { turnId, state: "unknown", reason, terminalConfidence: "unknown" },
      });
    }
    return { crashed: true, state: "unknown", turnId };
  }

  /** 测试用：等待一个终态 turn 事件（已发生则立即返回）。 */
  waitForTurnEnd({ timeoutMs = 2_000 } = {}) {
    const terminal = new Set(["completed", "failed", "unknown", "stopped"]);
    const existing = [...this.#trace]
      .reverse()
      .find((e) => e.kind === "turn" && terminal.has(e.payload?.state));
    if (existing) return Promise.resolve(existing);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("mock turn did not finish in time")), timeoutMs);
      timer.unref?.();
      this.#turnEndWaiters.push((event) => {
        clearTimeout(timer);
        resolve(event);
      });
    });
  }

  trace() {
    return this.#trace.map((e) => ({ ...e, payload: { ...e.payload } }));
  }

  pendingInteractions() {
    return [...this.#pendingInteractions.values()];
  }

  async dispose() {
    this.#disposed = true;
    this._listeners.clear();
    this.#pendingInteractions.clear();
    this.#turnEndWaiters = [];
  }
}

export function createMockAdapter(options) {
  return new MockAdapter(options);
}

/* --------------------------------------------------------------- scenarios */

/** 规范化转写：抹掉 id/时间戳，三种界面可比对同一语义。 */
export function normalizeTranscript(events) {
  const idPrefixes = ["ses", "turn", "run", "int", "cmd", "evt", "ai", "ws", "dev", "env", "ad", "art", "pm", "pa"];
  const idRe = new RegExp(`\\b(${idPrefixes.join("|")})_[A-Za-z0-9]+`, "g");
  const mapValue = (value, key = null, depth = 0) => {
    if (typeof value === "string") return value.replace(idRe, "<$1>");
    if (Array.isArray(value)) return value.map((v) => mapValue(v, key, depth + 1));
    if (value && typeof value === "object") {
      const out = {};
      for (const [k, v] of Object.entries(value)) {
        if (["ts", "createdAt", "updatedAt", "lastActivityAt", "startedAt", "endedAt", "stoppedAt", "answeredAt", "expiresAt", "at", "mock"].includes(k)) continue;
        if (["turnId", "sessionId", "runId", "requestId", "providerTurnId", "providerThreadId", "toolId", "id"].includes(k)) {
          out[k] = typeof v === "string" ? "<id>" : v;
          continue;
        }
        out[k] = mapValue(v, k, depth + 1);
      }
      return out;
    }
    return value;
  };
  return events.map((event) => ({
    kind: event.kind,
    sessionId: event.sessionId ? "<session>" : null,
    payload: mapValue(event.payload ?? {}),
  }));
}

function delay(ms) {
  return ms > 0 ? new Promise((resolve) => setTimeout(resolve, ms)) : Promise.resolve();
}

/**
 * 跑一个三界面通用契约场景：hello → subscribe → 创建会话 → 发送 → （审批）→ 结束。
 * @returns {Promise<{ui:string, welcome:object, snapshot:object, events:object[], transcript:object[], receipts:object[], session:object}>}
 */
export async function runUiScenario({
  core,
  ui = "tui",
  text = "hello mock",
  approve = true,
  expectApproval = false,
  timeoutMs = 3_000,
  clientType = ui,
} = {}) {
  if (!core || typeof core.handleRpc !== "function") throw new ValidationError("runUiScenario requires a core");
  const events = [];
  const unsubscribe = core.subscribe((event) => events.push(event));

  try {
    const hello = await core.handleRpc(
      rpcRequest(METHODS.hello, { protocolVersion: 1, client: ui, clientType }),
    );
    if (hello.error) throw new Error(`hello failed: ${hello.error.message}`);

    const subscribed = await core.handleRpc(
      rpcRequest(METHODS.subscribe, { clientType, limit: 100 }),
    );
    if (subscribed.error) throw new Error(`subscribe failed: ${subscribed.error.message}`);

    const created = await core.handleRpc(
      rpcRequest(METHODS.command, {
        kind: "session.create",
        payload: { title: "mock scenario session" },
      }),
    );
    if (created.error) throw new Error(`session.create failed: ${created.error.message}`);
    const sessionId = created.result?.result?.sessionId ?? created.result?.result?.session?.id ?? null;
    if (!sessionId) throw new Error("session.create returned no sessionId");

    const sent = await core.handleRpc(
      rpcRequest(METHODS.command, { kind: "turn.send", target: { sessionId }, payload: { text } }),
    );
    if (sent.error) throw new Error(`turn.send failed: ${sent.error.message}`);

    if (expectApproval || sent.result?.result?.awaitingApproval) {
      const deadline = Date.now() + timeoutMs;
      let requestId = null;
      while (Date.now() < deadline) {
        const pending = events.find(
          (e) => e.kind === "interaction" && (e.payload?.state === "pending" || e.payload?.request?.state === "pending"),
        );
        if (pending) {
          requestId = pending.payload?.requestId ?? pending.payload?.request?.id ?? null;
          break;
        }
        await delay(5);
      }
      if (!requestId) throw new Error("expected an approval interaction but none arrived");
      const answered = await core.handleRpc(
        rpcRequest(METHODS.command, {
          kind: "interaction.answer",
          target: { sessionId, requestId },
          payload: { answer: approve ? "allow" : "deny" },
        }),
      );
      if (answered.error) throw new Error(`interaction.answer failed: ${answered.error.message}`);
    }

    const deadline = Date.now() + timeoutMs;
    const terminal = new Set(["completed", "failed", "unknown", "stopped"]);
    while (Date.now() < deadline) {
      const finished = events.find(
        (e) => e.kind === "turn" && terminal.has(e.payload?.state ?? e.payload?.turn?.state),
      );
      if (finished) break;
      await delay(5);
    }

    const snapshot = core.state;
    return {
      ui,
      welcome: hello.result,
      snapshot,
      snapshotSeq: subscribed.result?.seq ?? null,
      events,
      transcript: normalizeTranscript(events),
      receipts: events
        .filter((e) => e.kind === "status" && e.payload?.commandId)
        .map((e) => ({ commandId: e.payload.commandId, state: e.payload.commandState ?? e.payload.state })),
      session: snapshot.sessions?.find((s) => s.id === sessionId) ?? null,
    };
  } finally {
    unsubscribe();
  }
}
