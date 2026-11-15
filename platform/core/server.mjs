/**
 * CORE-103：本地 UI RPC（JSON-RPC 2.0 风格，契约 v1）。
 *
 * - `core.hello` 版本协商 → `welcome`；版本不匹配返回 VERSION_MISMATCH。
 * - `core.subscribe` 返回 snapshot{seq}，随后事件经 core.subscribe(fn) 推送（seq 严格 +1）。
 * - `core.resume{fromSeq}`：连续则回放事件；出现缺口则返回新 snapshot 重新对齐。
 * - `core.command`：先持久化收据（accepted）再执行，重复 commandId 返回原收据。
 *
 * 这里不实现传输层（stdio/WebSocket），只做「消息 → 响应」的纯函数式分派，
 * 便于 TUI/GUI/Web 用同一套逻辑接入，也便于契约测试。
 */
import {
  METHODS,
  CORE_VERSION,
  PROTOCOL_VERSION,
  RPC_ERRORS,
  checkProtocolVersion,
  rpcError,
  rpcResult,
  welcome,
} from "../contracts/rpc.mjs";
import { makeId } from "../contracts/ids.mjs";
import { EVENT_KINDS } from "../contracts/events.mjs";
import { supports } from "../contracts/capabilities.mjs";
import {
  asCoreError,
  ConflictError,
  CoreError,
  ExpiredError,
  NotFoundError,
  UnauthorizedError,
  UnsupportedCapabilityError,
  ValidationError,
  VersionMismatchError,
} from "./errors.mjs";
import {
  createDevice,
  createPaMessage,
  createPaProfile,
  createRun,
  createSession,
  createTurn,
} from "./domain.mjs";
import { redact } from "./diagnostics.mjs";

const CORE_SCOPED = new Set(["notification", "session"]);

function nowIso(clock) {
  const d = clock();
  return (d instanceof Date ? d : new Date(d)).toISOString();
}

function nowIsoMs(clock) {
  const d = clock();
  return (d instanceof Date ? d : new Date(d)).getTime();
}

function requireSessionId(target = {}, payload = {}) {
  const sessionId = target.sessionId ?? payload.sessionId ?? null;
  if (!sessionId) throw new ValidationError("sessionId is required");
  return sessionId;
}

function normalizeCommandParams(params = {}) {
  const src = params?.command && typeof params.command === "object" ? params.command : params ?? {};
  const kind = src.kind ?? src.commandKind ?? null;
  if (!kind) throw new ValidationError("command kind is required");
  return {
    id: src.id ?? src.commandId ?? params.commandId ?? null,
    kind,
    source: src.source ?? "ui",
    target: src.target ?? params.target ?? {},
    payload: src.payload ?? params.payload ?? {},
    ttlMs: src.ttlMs ?? params.ttlMs,
    expiresAt: src.expiresAt ?? params.expiresAt,
    supersedes: src.supersedes ?? params.supersedes ?? null,
  };
}

/**
 * @param {object} ctx 由 core/index.mjs 组装的内部上下文
 */
export function createRpcServer(ctx) {
  const {
    journal,
    receipts,
    bus,
    diagnostics,
    notifications,
    terminal,
    artifacts,
    supervisor,
    workspaces,
    clock = () => new Date(),
    adapters = [],
    localDeviceId,
    localEnvironmentId,
    dataDir = null,
    startedAtMs = Date.now(),
    authToken = null,
    lifecycle = null,
    recoveryReport = null,
  } = ctx;

  let authorized = authToken === null || authToken === undefined;
  let closed = false;
  const sessionAdapters = new Map();
  const adapterLastSession = new Map();
  const unsubscribers = [];
  const terminalWatchers = new Map();

  /* -------------------------------------------------- payload normalization */

  const MESSAGE_STREAM_LIMIT = 500;
  const messageStreams = new Map();
  let messageOrder = 0;

  function interactionStatus(state) {
    if (state === "answered" || state === "expired" || state === "superseded" || state === "cancelled") return state;
    return "pending";
  }

  function localDeviceName() {
    return journal.getObject("device", localDeviceId)?.name ?? localDeviceId ?? null;
  }

  function localEnvironmentName() {
    return journal.getObject("environment", localEnvironmentId)?.name ?? localEnvironmentId ?? null;
  }

  function enrichArtifact(raw) {
    const metadata = raw.metadata && typeof raw.metadata === "object" ? raw.metadata : {};
    const out = {
      ...raw,
      id: raw.id,
      sessionId: raw.sessionId ?? null,
      name: raw.name ?? "artifact",
      kind: raw.kind ?? metadata.kind ?? "file",
      mime: raw.mime ?? "application/octet-stream",
      size: Number.isFinite(raw.size) ? raw.size : null,
      sha256: raw.sha256 ?? null,
      node: raw.node ?? raw.nodeId ?? localDeviceName(),
      environment: raw.environment ?? localEnvironmentName(),
      meta: raw.meta ?? metadata,
      chunkSize: raw.chunkSize ?? null,
      chunkCount: raw.chunkCount ?? null,
      available: raw.available ?? null,
    };
    if (typeof raw.diff === "string" && raw.diff.length <= 20_000) out.diff = raw.diff;
    else if (typeof metadata.diff === "string" && metadata.diff.length <= 20_000) out.diff = metadata.diff;
    if (typeof raw.content === "string" && raw.content.length <= 20_000) out.content = raw.content;
    else if (typeof metadata.content === "string" && metadata.content.length <= 20_000) out.content = metadata.content;
    return out;
  }

  /**
   * 统一事件载荷（对客户端 projection.mjs 的契约形状）：
   * - message 带稳定 id 与 `{message}` 包装；
   * - interaction 带 `{interaction}` 包装 + interactionId；
   * - turn 带 `{turn}`；status 带 `{status}`；artifact 带节点/环境/kind。
   * 原始字段一律保留，避免破坏其它读取方。
   */
  function normalizePayload(kind, sessionId, payload = {}) {
    const p = { ...payload };
    if (kind === "message") {
      const raw = p.message && typeof p.message === "object" ? { ...p.message } : {};
      const role = raw.role ?? p.role ?? "assistant";
      const turnId = raw.turnId ?? p.turnId ?? null;
      const delta = raw.delta === true || p.delta === true;
      const key = `${sessionId ?? ""}:${turnId ?? ""}:${role}`;
      const existing = messageStreams.get(key) ?? null;
      const useStream = delta || (existing && turnId);
      const stream = useStream && existing ? existing : { id: raw.id ?? makeId("evt"), order: ++messageOrder };
      if (!delta) messageStreams.delete(key);
      else if (messageStreams.size >= MESSAGE_STREAM_LIMIT) messageStreams.clear();
      else messageStreams.set(key, stream);
      const message = {
        ...raw,
        id: raw.id ?? stream.id,
        sessionId: raw.sessionId ?? sessionId ?? null,
        role,
        text: raw.text ?? p.text ?? "",
        streaming: delta,
        delta,
        turnId,
        order: Number.isFinite(raw.order) ? raw.order : stream.order,
        ts: raw.ts ?? null,
      };
      return { ...p, delta, message, messageId: message.id };
    }
    if (kind === "interaction") {
      const raw =
        p.interaction && typeof p.interaction === "object"
          ? { ...p.interaction }
          : p.request && typeof p.request === "object"
            ? { ...p.request }
            : {};
      const id = raw.id ?? raw.requestId ?? p.interactionId ?? p.requestId ?? null;
      if (!id) return p;
      const state = raw.state ?? p.state ?? "pending";
      const interaction = {
        ...raw,
        id,
        sessionId: raw.sessionId ?? sessionId ?? null,
        kind: raw.kind ?? raw.interactionKind ?? p.interactionKind ?? "approval",
        prompt: raw.prompt ?? p.prompt ?? "",
        options: Array.isArray(raw.options) ? raw.options : Array.isArray(p.options) ? p.options : [],
        status: interactionStatus(state),
        state,
        answer: raw.answer ?? p.answer ?? null,
        expiresAt: raw.expiresAt ?? p.expiresAt ?? null,
        providerRequestId: raw.providerRequestId ?? p.providerRequestId ?? null,
      };
      return { ...p, interaction, interactionId: id, requestId: id, state };
    }
    if (kind === "turn") {
      if (p.turn && typeof p.turn === "object") {
        return {
          ...p,
          turn: {
            ...p.turn,
            id: p.turn.id ?? p.turnId ?? null,
            sessionId: p.turn.sessionId ?? sessionId ?? null,
          },
        };
      }
      if (p.turnId) {
        return {
          ...p,
          turn: {
            id: p.turnId,
            sessionId: sessionId ?? null,
            state: p.state ?? null,
            stopReason: p.stopReason ?? null,
            providerTurnId: p.providerTurnId ?? null,
            terminalConfidence: p.terminalConfidence ?? null,
          },
        };
      }
      return p;
    }
    if (kind === "status") {
      return {
        ...p,
        status: {
          sessionId: p.sessionId ?? sessionId ?? null,
          state: p.state ?? null,
          detail: p.detail ?? null,
          ...(p.model ? { model: p.model } : {}),
        },
      };
    }
    if (kind === "artifact") {
      const raw = p.artifact && typeof p.artifact === "object" ? p.artifact : null;
      if (!raw || !raw.id) return p;
      return { ...p, artifact: enrichArtifact(raw) };
    }
    return p;
  }

  /** 唯一的事件写入入口：先归一化载荷，再落库/推送。 */
  function append(kind, sessionId, payload = {}, ts = nowIso(clock)) {
    return journal.appendEvent({ kind, sessionId, payload: normalizePayload(kind, sessionId, payload), ts });
  }

  function terminalSummary(view, extra = {}) {
    return {
      id: view.id,
      sessionId: view.sessionId ?? null,
      title: view.name,
      status: view.status === "running" ? "open" : "closed",
      node: localDeviceName(),
      environment: localEnvironmentName(),
      fullLength: view.bytes ?? 0,
      truncated: (view.droppedBytes ?? 0) > 0,
      cols: view.cols,
      rows: view.rows,
      pty: false,
      resize: "unsupported",
      ...extra,
    };
  }

  /**
   * C-07 / TERM-001：把管道终端的增量输出转成有界 terminal 事件。
   * 客户端按 `{terminal, chunk}` 累积并限长；重连时由 snapshot.terminals 回放最近输出。
   */
  function watchTerminal(view, sessionId) {
    if (!sessionId || terminalWatchers.has(view.id)) return;
    let offset = 0;
    const timer = setInterval(() => {
      try {
        const replay = terminal.read(view.id, { fromOffset: offset, maxBytes: 64 * 1024 });
        if (replay.chunks.length > 0) {
          offset = replay.nextOffset;
          const current = terminal.list().find((t) => t.id === view.id) ?? view;
          const chunk = replay.chunks.map((c) => c.data).join("");
          // chunk 同时放进 terminal 对象：客户端投影只读 `payload.terminal`。
          emitEvent("terminal", sessionId, {
            terminal: {
              ...terminalSummary(current, { status: replay.status === "running" ? "open" : "closed" }),
              chunk,
            },
            chunk,
          });
        }
        if (replay.status !== "running") {
          clearInterval(timer);
          terminalWatchers.delete(view.id);
        }
      } catch {
        clearInterval(timer);
        terminalWatchers.delete(view.id);
      }
    }, 100);
    timer.unref?.();
    terminalWatchers.set(view.id, timer);
  }

  function stopTerminalWatcher(terminalId) {
    const timer = terminalWatchers.get(terminalId);
    if (timer) {
      clearInterval(timer);
      terminalWatchers.delete(terminalId);
    }
  }

  /* ------------------------------------------------------------ event paths */

  function publish(event) {
    try {
      bus.publish(event);
    } catch (err) {
      diagnostics?.recordError?.(err, { where: "bus.publish", seq: event?.seq });
    }
    try {
      notifications?.handleEvent?.(event);
    } catch (err) {
      diagnostics?.log?.("warn", "notification mapping failed", { error: String(err?.message ?? err) });
    }
  }

  function emitEvent(kind, sessionId, payload = {}) {
    if (closed) throw new ConflictError("core is closed");
    const event = append(kind, sessionId, payload);
    publish(event);
    return event;
  }

  function setSessionState(sessionId, state, extra = {}) {
    const session = journal.getSession(sessionId);
    if (!session) return null;
    if (session.state === state) return null;
    const event = journal.transaction(() => {
      const next = journal.upsertSession({ ...session, ...extra, state, updatedAt: nowIso(clock) });
      return append("session", sessionId, { session: next, state, ...extra });
    });
    publish(event);
    return event;
  }

  function adapterForSession(session) {
    if (!session) return null;
    if (session.agentInstanceId) {
      const direct = adapters.find((a) => (a.instanceId ?? a.id) === session.agentInstanceId);
      if (direct) return direct;
    }
    const bound = sessionAdapters.get(session.id);
    if (bound) return bound;
    if (adapters.length === 1) return adapters[0];
    return null;
  }

  function bindAdapter(adapter, sessionId) {
    if (!adapter || !sessionId) return;
    sessionAdapters.set(sessionId, adapter);
    const key = adapter.instanceId ?? adapter.id ?? "unknown";
    adapterLastSession.set(key, sessionId);
  }

  function declarationOf(adapter) {
    if (!adapter) return null;
    if (typeof adapter.capabilities === "function") return adapter.capabilities();
    return adapter.declaration ?? adapter.capabilities ?? null;
  }

  function ingestAdapterEvent(adapter, raw) {
    if (closed) return null;
    try {
      if (!raw || typeof raw !== "object" || !EVENT_KINDS.includes(raw.kind)) {
        diagnostics?.log?.("warn", "adapter emitted unknown event kind", { kind: raw?.kind ?? null });
        return null;
      }
      const key = adapter?.instanceId ?? adapter?.id ?? "unknown";
      const sessionId = raw.sessionId ?? adapterLastSession.get(key) ?? null;
      if (!CORE_SCOPED.has(raw.kind) && !sessionId) {
        diagnostics?.log?.("warn", "adapter event without sessionId dropped", { kind: raw.kind });
        return null;
      }
      const payload = raw.payload && typeof raw.payload === "object" ? { ...raw.payload } : {};
      if (sessionId) adapterLastSession.set(key, sessionId);
      const event = append(raw.kind, sessionId, payload);
      publish(event);
      applyDerivedState(event);
      return event;
    } catch (err) {
      diagnostics?.log?.("warn", "adapter event failed to persist", {
        error: String(err?.message ?? err),
        kind: raw?.kind ?? null,
      });
      return null;
    }
  }

  /** 由事件推导会话/回合状态；不改变上游结论，只同步投影。 */
  function applyDerivedState(event) {
    const payload = event.payload ?? {};
    if (event.kind === "status" && event.sessionId) {
      const state = payload.state;
      if (["idle", "running", "queued", "stopped"].includes(state)) {
        setSessionState(event.sessionId, state);
      }
      return;
    }
    if (event.kind === "turn" && event.sessionId) {
      const turnState = payload.state ?? payload.turn?.state;
      if (turnState === "running" || turnState === "queued") {
        setSessionState(event.sessionId, "running");
      } else if (turnState === "completed") {
        const reason = payload.stopReason ?? payload.turn?.stopReason ?? null;
        setSessionState(event.sessionId, reason === "interrupted" ? "stopped" : "idle");
      } else if (turnState === "failed") {
        setSessionState(event.sessionId, "failed");
      } else if (turnState === "unknown") {
        setSessionState(event.sessionId, "unknown");
      }
      return;
    }
    if (event.kind === "session" && event.sessionId) {
      const state = payload.state ?? payload.session?.state;
      if (state) setSessionState(event.sessionId, state);
    }
  }

  function captureAdapter(adapter) {
    if (!adapter) return;
    if (typeof adapter.onEvent === "function") {
      const off = adapter.onEvent((event) => ingestAdapterEvent(adapter, event));
      if (typeof off === "function") unsubscribers.push(off);
      return;
    }
    if (adapter._listeners instanceof Set) {
      const listener = (event) => ingestAdapterEvent(adapter, event);
      adapter._listeners.add(listener);
      unsubscribers.push(() => adapter._listeners.delete(listener));
      return;
    }
    if (typeof adapter.events === "function") {
      const iterator = adapter.events()[Symbol.asyncIterator]();
      const pump = async () => {
        while (!closed) {
          const next = await iterator.next();
          if (next.done) break;
          ingestAdapterEvent(adapter, next.value);
        }
      };
      pump().catch((err) => diagnostics?.log?.("warn", "adapter event pump ended", { error: String(err?.message ?? err) }));
    }
  }

  /* --------------------------------------------------------------- helpers */

  /** 从事件日志还原排队 turn 的文本（排队 turn 未写入 turn 表，文本在 queued 事件里）。 */
  function queuedTurnText(sessionId, turnId) {
    const events = journal.readSince(1, { limit: 100000, sessionId });
    for (const event of events) {
      const payload = event.payload ?? {};
      if (payload?.turn?.id === turnId && typeof payload?.text === "string") return payload.text;
    }
    return null;
  }

  const draining = new Set();

  /** AGT-107：把 queued turn 依次派发；同一会话避免重入。 */
  async function drainQueue(sessionId) {
    if (draining.has(sessionId)) return;
    draining.add(sessionId);
    try {
      for (;;) {
        const active = journal.findActiveTurn(sessionId);
        // 注意 findActiveTurn 包含 queued；正在排队的那条不应阻止派发
        if (active && ["running", "stopping"].includes(active.state)) break;
        const queued = journal
          .listTurns(sessionId, { states: ["queued"] })
          .sort((a, b) => (a.index ?? 0) - (b.index ?? 0));
        const next = queued[0];
        if (!next) break;
        const session = requireSession(sessionId);
        const adapter = adapterForSession(session);
        if (!adapter || typeof adapter.send !== "function") break;
        const text = queuedTurnText(sessionId, next.id) ?? "";
        const now = nowIso(clock);
        const running = journal.upsertTurn({ ...next, state: "running", updatedAt: now });
        const started = journal.transaction(() =>
          append("turn", sessionId, { turn: running, state: "running", runId: next.runId, queuedDispatch: true }),
        );
        publish(started);
        bindAdapter(adapter, sessionId);
        try {
          await adapter.send({ sessionId, turnId: next.id, runId: next.runId, text, attachments: [] });
        } catch (err) {
          const reason = redact(err?.message ?? String(err));
          const failed = journal.transaction(() => {
            const updated = journal.upsertTurn({
              ...journal.getTurn(next.id),
              state: "failed",
              stopReason: "adapter-error",
              updatedAt: nowIso(clock),
              endedAt: nowIso(clock),
            });
            return append("turn", sessionId, { turn: updated, state: "failed", error: { message: reason } });
          });
          publish(failed);
        }
      }
    } finally {
      draining.delete(sessionId);
    }
  }

  function requireSession(sessionId) {
    const session = journal.getSession(sessionId);
    if (!session) throw new NotFoundError(`unknown session: ${sessionId}`, { sessionId });
    return session;
  }

  function emitStatusForCommand(command, receipt) {
    const sessionId = command?.target?.sessionId ?? null;
    if (!sessionId) return;
    const session = journal.getSession(sessionId);
    if (!session) return;
    try {
      // 注意：客户端的 status 事件会把 `state` 当作会话状态；命令收据状态放 commandState，
      // 避免把 "succeeded" 写进会话状态。
      emitEvent("status", sessionId, {
        commandId: receipt.commandId,
        commandKind: receipt.kind,
        commandState: receipt.state,
        terminal: receipt.terminal,
        state: session.state ?? null,
      });
    } catch (err) {
      diagnostics?.log?.("warn", "command status event failed", { error: String(err?.message ?? err) });
    }
  }

  async function workspaceForSession(session) {
    if (!session?.workspaceId) return null;
    return journal.getObject("workspace", session.workspaceId);
  }

  /* ------------------------------------------------------------- handlers */

  const handlers = {
    async "session.create"(command) {
      const p = command.payload ?? {};
      const nodeId = p.nodeId ?? localDeviceId;
      const environmentId = p.environmentId ?? localEnvironmentId;
      let adapter = p.agentInstanceId
        ? adapters.find((a) => (a.instanceId ?? a.id) === p.agentInstanceId) ?? null
        : adapters[0] ?? null;
      const agentInstanceId = p.agentInstanceId ?? adapter?.instanceId ?? adapter?.id ?? null;
      if (!agentInstanceId) {
        throw new UnsupportedCapabilityError("no agent instance available to create a session");
      }

      let workspaceId = p.workspaceId ?? null;
      let workspace = null;
      if (workspaceId) {
        workspace = journal.getObject("workspace", workspaceId);
        if (!workspace) throw new NotFoundError(`unknown workspace: ${workspaceId}`, { workspaceId });
      } else {
        const workspacePath = p.path ?? p.cwd ?? process.cwd();
        workspace = await workspaces.create({ deviceId: nodeId, environmentId, path: workspacePath });
        journal.putObject("workspace", workspace);
        workspaceId = workspace.id;
      }

      const sessionCreatedAt = nowIso(clock);
      const session = createSession({
        nodeId,
        environmentId,
        agentInstanceId,
        workspaceId,
        title: p.title ?? null,
        model: p.model ?? null,
        options: p.options ?? {},
        state: "idle",
        createdAt: sessionCreatedAt,
        updatedAt: sessionCreatedAt,
        lastActivityAt: sessionCreatedAt,
      });

      const event = journal.transaction(() => {
        journal.upsertSession(session);
        return append("session", session.id, { session, state: "idle" });
      });
      publish(event);
      bindAdapter(adapter, session.id);

      let providerThreadId = null;
      if (adapter && typeof adapter.createSession === "function") {
        const result = await adapter.createSession({ sessionId: session.id, workspace, model: session.model });
        providerThreadId = result?.providerThreadId ?? null;
        if (providerThreadId) {
          const updated = journal.transaction(() => {
            const next = journal.upsertSession({ ...journal.getSession(session.id), providerThreadId, updatedAt: nowIso(clock) });
            return append("session", session.id, { session: next, providerThreadId });
          });
          publish(updated);
        }
      }

      return {
        sessionId: session.id,
        workspaceId,
        agentInstanceId,
        providerThreadId,
        session: journal.getSession(session.id),
      };
    },

    async "session.resume"(command) {
      const sessionId = requireSessionId(command.target, command.payload);
      const session = requireSession(sessionId);
      const adapter = adapterForSession(session);
      if (!adapter || typeof adapter.resume !== "function") {
        throw new UnsupportedCapabilityError("agent adapter does not support resume", { sessionId });
      }
      const result = await adapter.resume({ sessionId });
      bindAdapter(adapter, sessionId);
      const providerThreadId = result?.providerThreadId ?? session.providerThreadId ?? null;
      const updated = journal.transaction(() => {
        const next = journal.upsertSession({ ...session, providerThreadId, updatedAt: nowIso(clock) });
        return append("session", sessionId, { session: next, state: "idle", resumed: true });
      });
      publish(updated);
      return { sessionId, providerThreadId, resumed: true };
    },

    async "session.fork"(command) {
      const sessionId = requireSessionId(command.target, command.payload);
      const session = requireSession(sessionId);
      const adapter = adapterForSession(session);
      if (!adapter || typeof adapter.fork !== "function") {
        throw new UnsupportedCapabilityError("agent adapter does not support fork", { sessionId });
      }
      const forked = await adapter.fork({ sessionId });
      const forkedAt = nowIso(clock);
      const child = createSession({
        nodeId: session.nodeId,
        environmentId: session.environmentId,
        agentInstanceId: session.agentInstanceId,
        workspaceId: session.workspaceId,
        providerThreadId: forked?.providerThreadId ?? null,
        model: session.model,
        options: session.options,
        title: session.title ? `${session.title} (fork)` : null,
        state: "idle",
        createdAt: forkedAt,
        updatedAt: forkedAt,
        lastActivityAt: forkedAt,
      });
      const event = journal.transaction(() => {
        journal.upsertSession(child);
        return append("session", child.id, { session: child, state: "idle", forkedFrom: sessionId });
      });
      publish(event);
      bindAdapter(adapter, child.id);
      return { sessionId: child.id, forkedFrom: sessionId, providerThreadId: forked?.providerThreadId ?? null };
    },

    async "turn.send"(command) {
      const sessionId = requireSessionId(command.target, command.payload);
      const session = requireSession(sessionId);
      const adapter = adapterForSession(session);
      if (!adapter || typeof adapter.send !== "function") {
        throw new UnsupportedCapabilityError("no agent adapter can send a turn", { sessionId });
      }
      const text = command.payload?.text ?? "";
      if (typeof text !== "string") throw new ValidationError("turn.send payload.text must be a string");

      // AGT-107/C-14：忙碌时 mode=queue → 持久化排队，不打断当前回合；当前回合结束后按序派发。
      const activeTurn = journal.findActiveTurn(sessionId);
      if (activeTurn && command.payload?.mode === "queue") {
        const now = nowIso(clock);
        const activeRun =
          journal.listRuns(sessionId).find((r) => ["queued", "running", "stopping"].includes(r.state)) ?? null;
        const run = activeRun ?? createRun({ sessionId, state: "running", createdAt: now, updatedAt: now });
        const index = journal.listTurns(sessionId).length + 1;
        const turn = createTurn({ runId: run.id, sessionId, index, state: "queued", createdAt: now, updatedAt: now });
        const persisted = journal.transaction(() => {
          journal.upsertRun({ ...run, state: "running", updatedAt: nowIso(clock) });
          journal.upsertTurn(turn);
          return append("turn", sessionId, { turn, state: "queued", runId: run.id, text, queued: true });
        });
        publish(persisted);
        const position = journal.listTurns(sessionId, { states: ["queued"] }).length;
        return { sessionId, runId: run.id, turnId: turn.id, state: "queued", queued: true, queuePosition: position };
      }

      let run = journal.listRuns(sessionId).find((r) => ["queued", "running", "stopping"].includes(r.state)) ?? null;
      const now = nowIso(clock);
      if (!run) {
        run = createRun({ sessionId, state: "running", createdAt: now, updatedAt: now });
      }
      const index = journal.listTurns(sessionId).length + 1;
      // 先持久化意图（queued），再做外部 I/O；适配器的 running 事件到达后才推进状态。
      const turn = createTurn({ runId: run.id, sessionId, index, state: "queued", createdAt: now, updatedAt: now });

      const persisted = journal.transaction(() => {
        journal.upsertRun({ ...run, state: "running", updatedAt: nowIso(clock) });
        journal.upsertTurn(turn);
        return append("turn", sessionId, { turn, state: "queued", runId: run.id, text });
      });
      publish(persisted);
      bindAdapter(adapter, sessionId);

      let result;
      try {
        result = await adapter.send({ sessionId, turnId: turn.id, runId: run.id, text, attachments: command.payload?.attachments ?? [] });
      } catch (err) {
        const reason = redact(err?.message ?? String(err));
        const failed = journal.transaction(() => {
          const next = journal.upsertTurn({
            ...journal.getTurn(turn.id),
            state: "failed",
            stopReason: "adapter-error",
            updatedAt: nowIso(clock),
            endedAt: nowIso(clock),
          });
          return append("turn", sessionId, { turn: next, state: "failed", error: { message: reason } });
        });
        publish(failed);
        setSessionState(sessionId, "failed");
        throw asCoreError(err, "adapter send failed");
      }

      const current = journal.getTurn(turn.id);
      // 当前回合结束后按序派发排队的 turn（不阻塞当前响应语义）
      await drainQueue(sessionId);
      const awaiting = result?.state === "awaiting_interaction" || !!result?.requestId;
      return {
        sessionId,
        runId: run.id,
        turnId: turn.id,
        state: current?.state ?? "queued",
        providerTurnId: result?.providerTurnId ?? null,
        awaitingApproval: awaiting,
        requestId: result?.requestId ?? null,
      };
    },

    async "turn.steer"(command) {
      const sessionId = requireSessionId(command.target, command.payload);
      const session = requireSession(sessionId);
      const adapter = adapterForSession(session);
      if (!adapter) throw new UnsupportedCapabilityError("no agent adapter can steer", { sessionId });
      const text = command.payload?.text ?? "";
      const declaration = declarationOf(adapter);
      const nativeSteer = !!declaration && supports(declaration, "turn", "steer");
      const turn = command.target?.turnId
        ? journal.getTurn(command.target.turnId)
        : journal.findActiveTurn(sessionId);

      let strategy = "native";
      if (nativeSteer && typeof adapter.steer === "function") {
        await adapter.steer({ sessionId, turnId: turn?.id ?? null, text });
      } else {
        // SPEC 8.3：无原生插话 → 停止当前回合、保留结果、恢复会话、追加新指令。
        strategy = "replace-resume";
        if (typeof adapter.interrupt !== "function" || typeof adapter.resume !== "function" || typeof adapter.send !== "function") {
          throw new UnsupportedCapabilityError("adapter supports neither native steer nor stop-resume fallback", {
            sessionId,
          });
        }
        await adapter.interrupt({ sessionId, turnId: turn?.id ?? null });
        await adapter.resume({ sessionId });
        await adapter.send({ sessionId, turnId: null, text });
      }
      emitEvent("status", sessionId, { strategy, steered: true, turnId: turn?.id ?? null });
      return { sessionId, strategy, turnId: turn?.id ?? null, nativeSteer };
    },

    async "turn.stop"(command) {
      const sessionId = requireSessionId(command.target, command.payload);
      const session = requireSession(sessionId);
      const turn = command.target?.turnId ? journal.getTurn(command.target.turnId) : journal.findActiveTurn(sessionId);
      if (!turn) {
        // C-15：空闲会话收到停止是幂等 no-op，不报 NOT_FOUND，也不伪造停止效果。
        return {
          sessionId,
          stopped: false,
          reason: "no-active-turn",
          state: journal.getSession(sessionId)?.state ?? null,
        };
      }
      const adapter = adapterForSession(session);
      if (!adapter || typeof adapter.interrupt !== "function") {
        throw new UnsupportedCapabilityError("agent adapter does not support interrupt", { sessionId });
      }

      const stopping = journal.transaction(() => {
        const next = journal.upsertTurn({ ...turn, state: "stopping", updatedAt: nowIso(clock) });
        return append("turn", sessionId, { turn: next, state: "stopping" });
      });
      publish(stopping);

      await adapter.interrupt({ sessionId, turnId: turn.id });
      const after = journal.getTurn(turn.id);
      if (after && ["queued", "running", "stopping"].includes(after.state)) {
        const done = journal.transaction(() => {
          const next = journal.upsertTurn({
            ...after,
            state: "completed",
            stopReason: "interrupted",
            terminalConfidence: "confirmed",
            stoppedBy: command.source,
            updatedAt: nowIso(clock),
            endedAt: nowIso(clock),
          });
          return append("turn", sessionId, { turn: next, state: "completed", stopReason: "interrupted" });
        });
        publish(done);
      }
      setSessionState(sessionId, "stopped");
      return { sessionId, turnId: turn.id, stopped: true, state: journal.getTurn(turn.id)?.state ?? null };
    },

    async "interaction.answer"(command) {
      // C-06：客户端按 `target.interactionId` 回答；核心同时接受 requestId，
      // 并允许省略 sessionId（从 interaction 记录反查所属会话）。
      const interactionId =
        command.target?.interactionId ?? command.payload?.interactionId ?? command.target?.requestId ?? command.payload?.requestId ?? null;
      if (!interactionId) throw new ValidationError("interaction.answer requires target.interactionId or target.requestId");
      const request = journal.getInteraction(interactionId);
      if (!request) throw new NotFoundError(`unknown interaction: ${interactionId}`, { interactionId });
      const sessionId = command.target?.sessionId ?? command.payload?.sessionId ?? request.sessionId ?? null;
      if (!sessionId) throw new ValidationError("interaction.answer requires sessionId or an interaction bound to a session");
      const session = requireSession(sessionId);
      if (request.sessionId && request.sessionId !== sessionId) {
        throw new ConflictError("interaction belongs to a different session", { interactionId, sessionId });
      }
      if (request.state === "expired") throw new ExpiredError("interaction already expired", { interactionId });
      if (request.state !== "pending") {
        // 重复回答：幂等返回已处理结果，避免多端竞争时报错。
        return {
          sessionId,
          interactionId,
          requestId: interactionId,
          state: "succeeded",
          duplicate: true,
          answer: request.answer ?? null,
          interaction: normalizePayload("interaction", sessionId, { request }).interaction,
        };
      }
      if (request.expiresAt && Date.parse(request.expiresAt) <= nowIsoMs(clock)) {
        const expired = journal.transaction(() => {
          const next = journal.upsertInteraction({ ...request, state: "expired" });
          return append("interaction", sessionId, { request: next, state: "expired" });
        });
        publish(expired);
        throw new ExpiredError("interaction expired", { interactionId });
      }

      const adapter = adapterForSession(session);
      if (!adapter || typeof adapter.answerInteraction !== "function") {
        throw new UnsupportedCapabilityError("agent adapter cannot answer interactions", { sessionId });
      }
      await adapter.answerInteraction({
        requestId: interactionId,
        providerRequestId: request.providerRequestId ?? null,
        answer: command.payload?.answer ?? null,
      });

      const after = journal.getInteraction(interactionId) ?? request;
      const answered = journal.transaction(() => {
        const next = journal.upsertInteraction({
          ...after,
          state: "answered",
          answer: command.payload?.answer ?? null,
          answeredAt: nowIso(clock),
          answeredBy: command.source,
        });
        return append("interaction", sessionId, { request: next, state: "answered", answer: next.answer });
      });
      publish(answered);
      return { sessionId, interactionId, requestId: interactionId, state: "succeeded", duplicate: false, answer: answered.payload.answer };
    },

    async "model.set"(command) {
      const sessionId = requireSessionId(command.target, command.payload);
      const session = requireSession(sessionId);
      const model = command.payload?.model ?? null;
      if (typeof model !== "string" || model.length === 0) throw new ValidationError("model.set requires payload.model");
      const adapter = adapterForSession(session);
      if (adapter && typeof adapter.setModel === "function") {
        await adapter.setModel({ sessionId, model });
      }
      const event = journal.transaction(() => {
        const next = journal.upsertSession({ ...session, model, updatedAt: nowIso(clock) });
        return append("session", sessionId, { session: next, model });
      });
      publish(event);
      // 客户端按 status.model 更新会话模型（contracts 没有独立 model 事件类型）。
      emitEvent("status", sessionId, { state: journal.getSession(sessionId)?.state ?? "idle", model, detail: "model changed" });
      return { sessionId, model, state: "succeeded", current: model, options: [] };
    },

    async "workspace.create"(command) {
      const p = command.payload ?? {};
      const workspacePath = p.path ?? p.cwd ?? null;
      if (!workspacePath) throw new ValidationError("workspace.create requires payload.path");
      const nodeId = p.deviceId ?? localDeviceId;
      const environmentId = p.environmentId ?? localEnvironmentId;
      const workspace = await workspaces.create({
        deviceId: nodeId,
        environmentId,
        path: workspacePath,
        label: p.label ?? null,
      });
      journal.putObject("workspace", workspace);
      return { workspaceId: workspace.id, workspace };
    },

    async "terminal.open"(command) {
      const sessionId = command.target?.sessionId ?? command.payload?.sessionId ?? null;
      const session = sessionId ? requireSession(sessionId) : null;
      const workspace = await workspaceForSession(session);
      const view = terminal.open({
        sessionId,
        cwd: command.payload?.cwd ?? workspace?.path ?? process.cwd(),
        command: command.payload?.command ?? null,
        args: command.payload?.args ?? [],
        env: command.payload?.env ?? {},
        cols: command.payload?.cols ?? 80,
        rows: command.payload?.rows ?? 24,
        name: command.payload?.title ?? command.payload?.name ?? null,
      });
      if (sessionId) {
        emitEvent("terminal", sessionId, { terminal: { ...terminalSummary(view), chunk: "" }, chunk: "" });
        watchTerminal(view, sessionId);
      }
      return {
        terminal: view,
        terminalId: view.id,
        sessionId,
        capabilities: terminal.capabilities(),
        state: "succeeded",
      };
    },

    async "terminal.resize"(command) {
      const terminalId = command.target?.terminalId ?? command.payload?.terminalId ?? null;
      if (!terminalId) throw new ValidationError("terminal.resize requires target.terminalId");
      const result = terminal.resize(terminalId, command.payload?.cols ?? 80, command.payload?.rows ?? 24);
      const sessionId = command.target?.sessionId ?? terminal.list().find((t) => t.id === terminalId)?.sessionId ?? null;
      if (sessionId) {
        const current = terminal.list().find((t) => t.id === terminalId);
        emitEvent("terminal", sessionId, {
          terminal: current ? terminalSummary(current, { resize: "unsupported" }) : { id: terminalId },
          resize: result,
          action: "resize",
        });
      }
      // 明确降级，不当作错误：调用方必须展示「不支持调整尺寸」。
      return { ...result, terminalId, state: "succeeded" };
    },

    async "terminal.close"(command) {
      const terminalId = command.target?.terminalId ?? command.payload?.terminalId ?? null;
      if (!terminalId) throw new ValidationError("terminal.close requires target.terminalId");
      const sessionId = command.target?.sessionId ?? terminal.list().find((t) => t.id === terminalId)?.sessionId ?? null;
      const view = terminal.close(terminalId);
      stopTerminalWatcher(terminalId);
      if (sessionId) {
        emitEvent("terminal", sessionId, { terminal: terminalSummary(view, { status: "closed" }), action: "close" });
      }
      return { terminal: view, terminalId, closed: true, state: "succeeded" };
    },

    async "artifact.put"(command) {
      const p = command.payload ?? {};
      const sessionId = command.target?.sessionId ?? p.sessionId ?? null;
      if (sessionId) requireSession(sessionId);
      const metadata = { ...(p.metadata ?? {}) };
      if (typeof p.kind === "string") metadata.kind = p.kind;
      if (typeof p.diff === "string") metadata.diff = p.diff;
      if (typeof p.content === "string") metadata.content = p.content;
      // 只接受领域前缀的节点 ID；第三方客户端给的展示名不进入 Artifact 领域对象。
      const nodeId = typeof p.nodeId === "string" && p.nodeId.startsWith("dev_") ? p.nodeId : null;
      let record;
      const common = {
        sessionId,
        nodeId,
        mime: p.mime ?? (typeof p.diff === "string" ? "text/x-diff" : "application/octet-stream"),
        metadata,
      };
      if (typeof p.filePath === "string" && p.filePath.length > 0) {
        record = await artifacts.putFile(p.filePath, { ...common, name: p.name ?? null });
      } else if (typeof p.data === "string") {
        record = artifacts.putBuffer(Buffer.from(p.data, "base64"), { ...common, name: p.name ?? "artifact" });
      } else if (typeof p.diff === "string") {
        record = artifacts.putBuffer(Buffer.from(p.diff, "utf8"), {
          ...common,
          name: p.name ?? "change.diff",
          mime: p.mime ?? "text/x-diff",
        });
      } else if (typeof p.content === "string") {
        record = artifacts.putBuffer(Buffer.from(p.content, "utf8"), {
          ...common,
          name: p.name ?? (p.kind === "diff" ? "change.diff" : "artifact.txt"),
          mime: p.mime ?? (p.kind === "diff" ? "text/x-diff" : "text/plain; charset=utf-8"),
        });
      } else if (typeof p.text === "string") {
        record = artifacts.putBuffer(Buffer.from(p.text, "utf8"), {
          ...common,
          name: p.name ?? "artifact.txt",
          mime: p.mime ?? "text/plain; charset=utf-8",
        });
      } else {
        throw new ValidationError("artifact.put requires payload.filePath / payload.data / payload.content / payload.text");
      }
      const enriched = enrichArtifact(record);
      if (sessionId) emitEvent("artifact", sessionId, { artifact: enriched });
      return {
        artifactId: record.id,
        artifact: enriched,
        sha256: enriched.sha256,
        size: enriched.size,
        chunkSize: enriched.chunkSize,
        chunkCount: enriched.chunkCount,
        state: "succeeded",
      };
    },

    async "pa.send"(command) {
      const p = command.payload ?? {};
      const profile = journal.getObject("pa", p.profileId ?? "pa_default") ?? createPaProfile({ id: p.profileId ?? undefined, name: p.name ?? "PA" });
      journal.putObject("pa", profile);
      const seq = (journal.listObjects("pa_message", { limit: 10_000 }).filter((m) => m.profileId === profile.id).length ?? 0) + 1;
      const message = createPaMessage({
        profileId: profile.id,
        seq,
        sourceNodeId: p.sourceNodeId ?? localDeviceId,
        replyTo: p.replyTo ?? null,
        text: String(p.text ?? ""),
        createdAt: nowIso(clock),
      });
      journal.putObject("pa_message", message);
      return { profileId: profile.id, messageId: message.id, seq };
    },

    async "device.pair"(command) {
      const p = command.payload ?? {};
      const device = createDevice({
        id: p.deviceId ?? undefined,
        name: p.name ?? "paired device",
        platform: p.platform ?? "unknown",
        publicKey: p.publicKey ?? null,
        online: true,
        authorized: true,
        version: p.version ?? null,
        createdAt: nowIso(clock),
      });
      journal.putObject("device", device);
      return { deviceId: device.id, device, paired: true };
    },
  };

  /* --------------------------------------------------------- command entry */

  async function dispatchCommand(rawCommand) {
    if (closed) throw new ConflictError("core is closed");
    const accepted = receipts.accept({
      id: rawCommand.id ?? undefined,
      kind: rawCommand.kind,
      source: rawCommand.source ?? "ui",
      target: rawCommand.target ?? {},
      payload: rawCommand.payload ?? {},
      ttlMs: rawCommand.ttlMs,
      expiresAt: rawCommand.expiresAt,
      supersedes: rawCommand.supersedes ?? null,
    });

    if (accepted.deduped || accepted.receipt.terminal) {
      // 幂等重投：返回原收据，绝不再次执行副作用。
      diagnostics?.log?.("debug", "duplicate command ignored", {
        commandId: accepted.receipt.commandId,
        state: accepted.receipt.state,
      });
      return { receipt: accepted.receipt, result: accepted.receipt.result, deduped: true };
    }

    const dispatched = receipts.dispatch(accepted.receipt.commandId);
    if (dispatched.receipt.terminal) {
      emitStatusForCommand(rawCommand, dispatched.receipt);
      return { receipt: dispatched.receipt, result: null, deduped: false };
    }

    const handler = handlers[accepted.receipt.kind];
    if (typeof handler !== "function") {
      const settled = receipts.settle(accepted.receipt.commandId, "failed", {
        error: { reason: "unsupported-command", kind: accepted.receipt.kind },
      });
      emitStatusForCommand(rawCommand, settled);
      throw new UnsupportedCapabilityError(`unsupported command kind: ${accepted.receipt.kind}`, {
        kind: accepted.receipt.kind,
        receipt: settled,
      });
    }

    let result;
    try {
      result = await handler({ ...rawCommand, commandId: accepted.receipt.commandId });
    } catch (err) {
      const coreErr = asCoreError(err);
      const settleState = coreErr.code === "UNKNOWN_RESULT" ? "unknown" : "failed";
      const settled = receipts.settle(accepted.receipt.commandId, settleState, {
        error: coreErr.toJSON(),
      });
      emitStatusForCommand(rawCommand, settled);
      if (coreErr.data && typeof coreErr.data === "object") coreErr.data.receipt = settled;
      else coreErr.data = { receipt: settled };
      throw coreErr;
    }

    const settled = receipts.settle(accepted.receipt.commandId, "succeeded", { result: result ?? null });
    emitStatusForCommand(rawCommand, settled);
    return { receipt: settled, result: result ?? null, deduped: false };
  }

  /* ------------------------------------------------------------- snapshots */

  function notificationSnapshots(limit = 100) {
    return notifications.list({ limit }).map((n) => ({
      ...n,
      text: n.body ?? n.title ?? "",
      ts: n.createdAt ?? null,
    }));
  }

  function enrichSession(session) {
    if (!session) return session;
    const nodeObject = session.nodeId ? journal.getObject("device", session.nodeId) : null;
    const environmentObject = session.environmentId
      ? journal.getObject("environment", session.environmentId)
      : null;
    const workspaceObject = session.workspaceId ? journal.getObject("workspace", session.workspaceId) : null;
    const adapter = adapters.find((a) => (a.instanceId ?? a.id) === session.agentInstanceId) ?? null;
    const declaration = declarationOf(adapter);
    const agentObject = adapter
      ? {
          id: adapter.instanceId ?? adapter.id ?? session.agentInstanceId,
          provider: adapter.provider ?? declaration?.provider ?? null,
          version: adapter.version ?? declaration?.version ?? null,
          environment: adapter.environment ?? null,
        }
      : session.agentInstanceId
        ? { id: session.agentInstanceId }
        : null;
    return {
      ...session,
      sessionId: session.id,
      // 客户端视图（TUI/Web）直接插值这四个字段，保持可读字符串。
      node: nodeObject?.name ?? session.nodeId ?? null,
      environment: environmentObject?.name ?? session.environmentId ?? null,
      workspace: workspaceObject?.path ?? null,
      agent: agentObject ? `${agentObject.provider ?? agentObject.id}${agentObject.version ? ` ${agentObject.version}` : ""}` : null,
      // 需要详情的调用方读 *Object，避免把对象塞进展示字段。
      nodeObject,
      environmentObject,
      workspaceObject,
      agentObject,
      capabilities: declaration?.capabilities ?? null,
      models: { current: session.model ?? null, options: [] },
    };
  }

  function buildModels() {
    const sessions = journal.listSessions({ limit: 200 });
    const current = sessions.find((s) => s.model)?.model ?? null;
    return { current, options: [] };
  }

  function buildCapabilities() {
    return { protocolVersion: PROTOCOL_VERSION, instances: buildCapabilityMatrix() };
  }

  function terminalSnapshots({ sessionId = null, limit = 50 } = {}) {
    const views = terminal.list().filter((view) => !sessionId || view.sessionId === sessionId).slice(0, limit);
    return views.map((view) => {
      let output = "";
      let truncated = !!view.truncated;
      try {
        const offset = Math.max(0, (view.bytes ?? 0) - 20_000);
        const replay = terminal.read(view.id, { fromOffset: offset, maxBytes: 20_000 });
        output = replay.chunks.map((c) => c.data).join("");
        truncated = truncated || replay.truncated;
      } catch {
        // 终端已被移除：只返回元数据
      }
      return { ...terminalSummary(view), output, fullLength: view.bytes ?? output.length, truncated };
    });
  }

  function interactionSnapshots({ sessionId = null, limit = 200 } = {}) {
    return journal
      .listInteractions({ sessionId, limit })
      .map((request) => normalizePayload("interaction", request.sessionId ?? sessionId, { request }).interaction)
      .filter(Boolean);
  }

  /**
   * 有界快照（C-01/C-02/C-03/C-09/C-10/C-17）。
   * 客户端刷新/首连只需要这一份即可恢复会话、最近消息、未决审批、终端回放摘要、成果与通知。
   */
  function makeSnapshot({ sessionId = null, limit = 200 } = {}) {
    if (sessionId) {
      const base = journal.snapshot({ sessionId, limit });
      const projection = base.session ?? {};
      const messages = (projection.events ?? [])
        .map((event) => event.payload?.message)
        .filter((message) => message && typeof message === "object");
      return {
        ...base,
        protocolVersion: PROTOCOL_VERSION,
        sessions: (base.sessions ?? []).map(enrichSession),
        messages,
        interactions: (projection.interactions ?? []).map(
          (request) => normalizePayload("interaction", sessionId, { request }).interaction,
        ),
        terminals: terminalSnapshots({ sessionId }),
        artifacts: (projection.artifacts ?? []).map(enrichArtifact),
        notifications: notificationSnapshots().filter((n) => !n.sessionId || n.sessionId === sessionId),
        models: { current: projection.session?.model ?? null, options: [] },
        capabilities: buildCapabilities(),
      };
    }
    const base = journal.snapshot({ limit });
    return {
      ...base,
      protocolVersion: PROTOCOL_VERSION,
      sessions: (base.sessions ?? []).map(enrichSession),
      messages: journal.recentMessages({ limit: Math.min(500, Math.max(50, limit * 2)) }),
      interactions: interactionSnapshots({ limit: 200 }),
      terminals: terminalSnapshots(),
      artifacts: artifacts.list().slice(0, 100).map(enrichArtifact),
      notifications: notificationSnapshots(),
      models: buildModels(),
      capabilities: buildCapabilities(),
    };
  }

  function buildDiagnostics() {
    const health = diagnostics?.health?.() ?? { ok: true };
    return {
      coreVersion: ctx.coreVersion ?? CORE_VERSION,
      protocolVersion: PROTOCOL_VERSION,
      pid: process.pid,
      dataDir,
      startedAtMs,
      uptimeMs: Math.max(0, Date.now() - startedAtMs),
      schemaVersion: journal.schemaVersion(),
      seq: journal.latestSeq(),
      counts: journal.projectionCounts(),
      commands: receipts.countByState(),
      notifications: notifications?.diagnostics?.() ?? null,
      terminals: terminal?.list?.() ?? [],
      terminalsCapabilities: terminal?.capabilities?.() ?? null,
      supervised: supervisor?.list?.() ?? [],
      adapters: adapters.map((a) => ({
        instanceId: a.instanceId ?? a.id ?? null,
        provider: a.provider ?? null,
        version: a.version ?? null,
        environment: a.environment ?? null,
      })),
      recovery: recoveryReport ?? null,
      lifecycle: lifecycle?.health?.() ?? null,
      health,
    };
  }

  function buildCapabilityMatrix() {
    return adapters.map((adapter) => ({
      instanceId: adapter.instanceId ?? adapter.id ?? null,
      provider: adapter.provider ?? null,
      declaration: declarationOf(adapter),
    }));
  }

  /* ----------------------------------------------------------------- RPC */

  async function handleRpc(msg) {
    if (closed) return rpcError(msg?.id ?? null, RPC_ERRORS.INTERNAL, "core is closed");
    if (!msg || typeof msg !== "object" || Array.isArray(msg)) {
      return rpcError(null, RPC_ERRORS.INVALID_REQUEST, "request must be a JSON object");
    }
    if (msg.jsonrpc !== "2.0") {
      return rpcError(msg.id ?? null, RPC_ERRORS.INVALID_REQUEST, "jsonrpc must be '2.0'");
    }
    if (typeof msg.method !== "string") {
      return rpcError(msg.id ?? null, RPC_ERRORS.INVALID_REQUEST, "method is required");
    }
    if (msg.id === undefined) {
      // 通知：不需要响应。
      return null;
    }

    const params = msg.params && typeof msg.params === "object" ? msg.params : {};
    try {
      if (authToken !== null && authToken !== undefined) {
        if (msg.method !== METHODS.hello && !authorized) {
          throw new UnauthorizedError("hello with a valid auth token is required");
        }
      }

      switch (msg.method) {
        case METHODS.hello: {
          const protocolVersion = params.protocolVersion ?? PROTOCOL_VERSION;
          const check = checkProtocolVersion(protocolVersion);
          if (!check.ok) {
            throw new VersionMismatchError(check.message, {
              clientProtocol: protocolVersion,
              supported: PROTOCOL_VERSION,
            });
          }
          if (authToken !== null && authToken !== undefined) {
            const token = params.auth?.token ?? params.token ?? null;
            if (token !== authToken) throw new UnauthorizedError("invalid auth token");
            authorized = true;
          }
          return rpcResult(msg.id, {
            ...welcome({ clientType: params.clientType ?? params.client ?? "unknown" }),
            capabilities: { snapshot: true, resume: true, events: true },
          });
        }

        case METHODS.subscribe: {
          const sessionId = params.sessionId ?? null;
          if (sessionId) requireSession(sessionId);
          const fromSeq = Number.isInteger(params.fromSeq) ? params.fromSeq : 0;
          // C-01：带 fromSeq 的订阅与 resume 语义一致（连续回放 / 缺口快照）。
          if (fromSeq > 0) {
            const decision = journal.checkResume(fromSeq, { sessionId });
            if (decision.mode === "snapshot") {
              const snapshot = makeSnapshot({ sessionId, limit: params.limit ?? 200 });
              return rpcResult(msg.id, {
                type: "snapshot",
                reason: decision.reason,
                fromSeq,
                seq: snapshot.seq,
                snapshot,
                gap: true,
                resumed: false,
              });
            }
            const events = journal.readSince(fromSeq, { limit: params.limit ?? 1000, sessionId });
            const nextSeq = events.length > 0 ? events[events.length - 1].seq : decision.latestSeq;
            return rpcResult(msg.id, {
              type: "events",
              reason: decision.reason,
              fromSeq,
              seq: nextSeq,
              latestSeq: decision.latestSeq,
              nextSeq,
              events,
              resumed: true,
              hasMore: nextSeq < decision.latestSeq,
            });
          }
          const snapshot = makeSnapshot({ sessionId, limit: params.limit ?? 200 });
          return rpcResult(msg.id, {
            type: "snapshot",
            seq: snapshot.seq,
            snapshot,
            subscribed: true,
            push: "events",
          });
        }

        case METHODS.resume: {
          const fromSeq = params.fromSeq;
          if (!Number.isInteger(fromSeq) || fromSeq < 0) {
            throw new ValidationError("resume requires a non-negative integer params.fromSeq");
          }
          const sessionId = params.sessionId ?? null;
          if (sessionId) requireSession(sessionId);
          const decision = journal.checkResume(fromSeq, { sessionId });
          if (decision.mode === "snapshot") {
            const snapshot = makeSnapshot({ sessionId, limit: params.limit ?? 200 });
            return rpcResult(msg.id, {
              type: "snapshot",
              reason: decision.reason,
              fromSeq,
              seq: snapshot.seq,
              snapshot,
              gap: true,
            });
          }
          const events = journal.readSince(fromSeq, {
            limit: params.limit ?? 1000,
            sessionId,
          });
          const nextSeq = events.length > 0 ? events[events.length - 1].seq : Math.max(fromSeq, decision.latestSeq);
          return rpcResult(msg.id, {
            type: "events",
            reason: decision.reason,
            fromSeq,
            seq: nextSeq,
            latestSeq: decision.latestSeq,
            nextSeq,
            events,
            hasMore: nextSeq < decision.latestSeq,
          });
        }

        case METHODS.snapshot: {
          const snapshot = makeSnapshot({ sessionId: params.sessionId ?? null, limit: params.limit ?? 200 });
          return rpcResult(msg.id, { type: "snapshot", seq: snapshot.seq, snapshot });
        }

        case METHODS.command: {
          const normalized = normalizeCommandParams(params);
          const outcome = await dispatchCommand(normalized);
          // 客户端既读顶层常用字段，也读完整 result（C-13）。
          const flat = {};
          if (outcome.result && typeof outcome.result === "object") {
            for (const key of [
              "sessionId",
              "turnId",
              "terminalId",
              "artifactId",
              "interactionId",
              "workspaceId",
              "model",
              "duplicate",
              "stopped",
              "reason",
            ]) {
              if (outcome.result[key] !== undefined) flat[key] = outcome.result[key];
            }
          }
          return rpcResult(msg.id, {
            commandId: outcome.receipt.commandId,
            kind: outcome.receipt.kind,
            state: outcome.receipt.state,
            receipt: outcome.receipt,
            result: outcome.result,
            deduped: outcome.deduped,
            ...flat,
          });
        }

        case METHODS.diagnostics:
          return rpcResult(msg.id, buildDiagnostics());

        case METHODS.capabilityMatrix:
          return rpcResult(msg.id, { matrix: buildCapabilityMatrix(), protocolVersion: PROTOCOL_VERSION });

        /* ----------------------------------------------------- provisional RPC
         * C-07 / C-08：终端回放与成果分块读取。
         * 注意：这些方法名尚未写入 contracts/rpc.mjs 的 METHODS（本任务禁止改 contracts），
         * 已在交付报告中列为需要 Lead 合并的契约增量；方法行为本身已实现并有测试覆盖。
         */
        case "core.terminal.read": {
          const terminalId = params.terminalId ?? params.id ?? null;
          if (!terminalId) throw new ValidationError("core.terminal.read requires params.terminalId");
          const replay = terminal.read(terminalId, {
            fromOffset: Number.isInteger(params.fromOffset) ? params.fromOffset : 0,
            maxBytes: Number.isInteger(params.maxBytes) ? params.maxBytes : undefined,
          });
          const view = terminal.list().find((t) => t.id === terminalId) ?? null;
          return rpcResult(msg.id, {
            terminalId,
            ...replay,
            terminal: view ? { ...terminalSummary(view), output: undefined } : null,
            capabilities: terminal.capabilities(),
          });
        }

        case "core.terminal.list":
          return rpcResult(msg.id, { terminals: terminalSnapshots(), capabilities: terminal.capabilities() });

        case "core.artifact.stat": {
          const artifactId = params.artifactId ?? params.id ?? null;
          if (!artifactId) throw new ValidationError("core.artifact.stat requires params.artifactId");
          return rpcResult(msg.id, { artifact: enrichArtifact(artifacts.stat(artifactId)) });
        }

        case "core.artifact.readChunk": {
          const artifactId = params.artifactId ?? params.id ?? null;
          const index = params.index ?? params.chunk ?? 0;
          if (!artifactId) throw new ValidationError("core.artifact.readChunk requires params.artifactId");
          const chunk = artifacts.readChunk(artifactId, index);
          return rpcResult(msg.id, {
            artifactId,
            index: chunk.index,
            offset: chunk.offset,
            length: chunk.length,
            last: chunk.last,
            chunkCount: chunk.chunkCount,
            sha256: chunk.sha256,
            dataBase64: chunk.data.toString("base64"),
          });
        }

        case "core.artifact.get": {
          const artifactId = params.artifactId ?? params.id ?? null;
          if (!artifactId) throw new ValidationError("core.artifact.get requires params.artifactId");
          const record = artifacts.stat(artifactId);
          const maxBytes = Number.isInteger(params.maxBytes) ? Math.min(params.maxBytes, 1024 * 1024) : 256 * 1024;
          const content = record.available ? artifacts.readRange(artifactId, { offset: 0, length: maxBytes }).toString("utf8") : null;
          return rpcResult(msg.id, {
            artifact: enrichArtifact(record),
            content,
            truncated: record.available ? record.size > maxBytes : false,
            sha256: record.sha256,
          });
        }

        default:
          throw new CoreError("METHOD_NOT_FOUND", `unknown method: ${msg.method}`);
      }
    } catch (err) {
      const coreErr = asCoreError(err);
      if (coreErr.code === "INTERNAL") {
        diagnostics?.recordError?.(err, { method: msg.method });
      } else {
        diagnostics?.log?.("debug", "rpc error", {
          method: msg.method,
          code: coreErr.code,
          message: coreErr.message,
        });
      }
      return rpcError(msg.id, coreErr.rpcCode, coreErr.message, {
        coreCode: coreErr.code,
        ...(coreErr.data === undefined ? {} : coreErr.data),
      });
    }
  }

  /* -------------------------------------------------------------- lifecycle */

  function init() {
    for (const adapter of adapters) captureAdapter(adapter);
  }

  async function close() {
    if (closed) return;
    closed = true;
    for (const timer of terminalWatchers.values()) clearInterval(timer);
    terminalWatchers.clear();
    for (const off of unsubscribers) {
      try {
        off();
      } catch {
        // ignore
      }
    }
    unsubscribers.length = 0;
    for (const adapter of adapters) {
      try {
        await adapter.dispose?.();
      } catch (err) {
        diagnostics?.log?.("warn", "adapter dispose failed", { error: String(err?.message ?? err) });
      }
    }
  }

  return {
    handleRpc,
    dispatchCommand,
    makeSnapshot,
    buildDiagnostics,
    buildCapabilityMatrix,
    close,
    init,
    get closed() {
      return closed;
    },
    adapterForSession,
    bindAdapter,
    get adapters() {
      return adapters;
    },
  };
}
