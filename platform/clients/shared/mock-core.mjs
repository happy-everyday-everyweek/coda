/**
 * 客户端内置的确定性 Mock Core（`--mock` 用）。
 *
 * 它不是产品核心：只实现 CONTRACT.md §8 的 `handleRpc` / `subscribe` 两个入口，
 * 供无核心进程时演示 / 手工验收 TUI 与 Web。真实核心不存在时，客户端不得假装成功。
 */

import {
  METHODS,
  PROTOCOL_VERSION,
  RPC_ERRORS,
  checkProtocolVersion,
  welcome,
  rpcResult,
  rpcError,
} from "../../contracts/rpc.mjs";

export const MOCK_FAIL_SEND_TEXT = "FAIL_SEND";
const MAX_EVENTS = 1000;

const BASE_IDLE = "2026-11-07T00:00:00.000Z";

function buildDiff() {
  return [
    "diff --git a/src/app.mjs b/src/app.mjs",
    "index 1111111..2222222 100644",
    "--- a/src/app.mjs",
    "+++ b/src/app.mjs",
    "@@ -1,4 +1,5 @@",
    " export function start() {",
    "-  return legacyStart();",
    "+  // 由远程节点执行的修复",
    "+  return startCore();",
    " }",
  ].join("\n");
}

function buildTerminalOutput() {
  return Array.from({ length: 120 }, (_, i) => {
    const n = String(i + 1).padStart(3, "0");
    return `[build ${n}] compiling module src/module-${n}.mjs ... ok`;
  }).join("\n");
}

export function createMockCore({ now = null } = {}) {
  let seq = 0;
  let commandCounter = 0;
  let messageCounter = 4;
  let orderCounter = 4;
  let turnCounter = 0;

  const listeners = new Set();
  const events = [];
  const commands = [];
  const diff = buildDiff();
  const terminalOutput = buildTerminalOutput();

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

  let sessionCounter = 0;

  const sessions = [
    {
      id: "ses_local",
      title: "本地会话：契约演示",
      state: "idle",
      agent: "Claude Code",
      model: "claude-sonnet",
      workspace: "/work/demo",
      node: "node_local",
      environment: "host",
      updatedAt: BASE_IDLE,
      capabilities: { plan: false, steer: "experimental", approvals: true },
    },
    {
      id: "ses_remote",
      title: "远程节点：WSL 构建",
      state: "idle",
      agent: "Codex",
      model: "gpt-5-codex",
      workspace: "/home/dev/demo",
      node: "node_remote",
      environment: "wsl:Ubuntu",
      updatedAt: BASE_IDLE,
      capabilities: { plan: true, steer: false, approvals: true },
    },
  ];

  const messages = [
    { id: "msg_1", sessionId: "ses_local", role: "user", text: "请检查构建失败原因", ts: BASE_IDLE, order: 1 },
    { id: "msg_2", sessionId: "ses_local", role: "assistant", text: "我先看一下日志。", ts: BASE_IDLE, order: 2 },
    { id: "msg_3", sessionId: "ses_remote", role: "user", text: "在 WSL 里跑一下构建", ts: BASE_IDLE, order: 3 },
    {
      id: "msg_4",
      sessionId: "ses_remote",
      role: "assistant",
      text: "构建已完成，详见附件。",
      ts: BASE_IDLE,
      order: 4,
      attachments: [{ id: "art_diff", artifactId: "art_diff", name: "src/app.mjs.diff", size: diff.length, mime: "text/x-diff" }],
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
      output: terminalOutput,
      fullLength: terminalOutput.length,
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
      size: diff.length,
      sha256: "8f0c1b6b0d7f7f6d7a0b0f8f0c1b6b0d7f7f6d7a0b0f8f0c1b6b0d7f7f6d7a0b",
      node: "node_local",
      environment: "host",
      diff,
      content: diff,
      meta: { hunks: 1, additions: 2, deletions: 1 },
    },
  ];

  const notifications = [
    {
      id: "ntf_1",
      level: "info",
      text: "远程节点 node_remote 已连接",
      sessionId: null,
      ts: BASE_IDLE,
      read: false,
    },
  ];

  const models = {
    current: "claude-sonnet",
    options: [
      { id: "claude-sonnet", label: "Claude Sonnet" },
      { id: "claude-opus", label: "Claude Opus" },
      { id: "gpt-5-codex", label: "GPT-5 Codex" },
    ],
  };

  const ts = () => (typeof now === "function" ? now() : BASE_IDLE);

  function emit(kind, payload, sessionId = null) {
    const event = { kind, sessionId, payload, seq: ++seq, ts: ts() };
    events.push(event);
    if (events.length > MAX_EVENTS) events.shift();
    for (const fn of listeners) {
      try {
        fn(event);
      } catch {
        /* 一个订阅者出错不影响其他订阅者 */
      }
    }
    return event;
  }

  function findSession(id) {
    return sessions.find((s) => s.id === id) ?? null;
  }

  function findInteraction(id) {
    return interactions.find((i) => i.id === id) ?? null;
  }

  function snapshot() {
    return {
      seq,
      sessions: sessions.map((s) => ({ ...s, capabilities: { ...s.capabilities } })),
      messages: messages.map((m) => ({
        ...m,
        attachments: (m.attachments ?? []).map((a) => ({ ...a })),
      })),
      interactions: interactions.map((i) => ({ ...i, options: i.options.map((o) => ({ ...o })) })),
      terminals: terminals.map((t) => ({ ...t })),
      artifacts: artifacts.map((a) => ({ ...a })),
      notifications: notifications.map((n) => ({ ...n })),
      models: { current: models.current, options: models.options.map((o) => ({ ...o })) },
      capabilities,
    };
  }

  function subscribe({ fromSeq = 0 } = {}) {
    if (fromSeq <= 0) return { seq, snapshot: snapshot() };
    if (fromSeq > seq) return { seq, snapshot: snapshot(), gap: true };
    const oldest = events[0]?.seq ?? seq + 1;
    if (fromSeq < oldest - 1) return { seq, snapshot: snapshot(), gap: true };
    return { seq, events: events.filter((e) => e.seq > fromSeq), resumed: true };
  }

  function reply(id, result) {
    return rpcResult(id, result);
  }

  function fail(id, code, message) {
    return rpcError(id, code, message);
  }

  async function handleRpc(message) {
    if (!message || message.jsonrpc !== "2.0" || typeof message.method !== "string") {
      return fail(message?.id, RPC_ERRORS.INVALID_REQUEST, "invalid request");
    }
    switch (message.method) {
      case METHODS.hello: {
        const check = checkProtocolVersion(message.params?.protocolVersion);
        if (!check.ok) return fail(message.id, check.code, check.message);
        return reply(
          message.id,
          welcome({ clientType: message.params?.clientType ?? "unknown", mock: true }),
        );
      }

      case METHODS.subscribe:
        return reply(message.id, subscribe({ fromSeq: message.params?.fromSeq ?? 0 }));

      case METHODS.resume:
        return reply(message.id, subscribe({ fromSeq: message.params?.fromSeq ?? 0 }));

      case METHODS.snapshot:
        return reply(message.id, { seq, snapshot: snapshot() });

      case METHODS.diagnostics:
        return reply(message.id, {
          coreVersion: "mock-0.1.0",
          protocolVersion: PROTOCOL_VERSION,
          seq,
          sessions: sessions.length,
          commands: commands.length,
        });

      case METHODS.capabilityMatrix:
        return reply(message.id, { capabilities });

      case METHODS.command: {
        const commandId = message.params?.commandId ?? `cmd_mock_${++commandCounter}`;
        const kind = message.params?.kind;
        const target = message.params?.target ?? {};
        const payload = message.params?.payload ?? {};
        commands.push({ commandId, kind, target, payload, source: message.params?.source ?? "unknown" });

        if (kind === "session.create") {
          const title = String(payload.title ?? "新会话");
          const session = {
            id: `ses_new_${++sessionCounter}`,
            title,
            state: "idle",
            agent: String(payload.agent ?? "Mock Agent"),
            model: String(payload.model ?? "mock-model"),
            workspace: String(payload.workspace ?? "/work/new"),
            node: "node_local",
            environment: "host",
            updatedAt: ts(),
            capabilities: { plan: false, steer: "experimental", approvals: true },
          };
          sessions.push(session);
          emit("session", { session: { ...session } }, null);
          return reply(message.id, { commandId, state: "succeeded", sessionId: session.id, result: { sessionId: session.id } });
        }

        if (kind === "turn.send" || kind === "turn.steer") {
          const session = findSession(target.sessionId);
          if (!session) return fail(message.id, RPC_ERRORS.NOT_FOUND, `unknown session ${target.sessionId}`);
          const text = String(payload.text ?? "");

          if (text.includes(MOCK_FAIL_SEND_TEXT)) {
            session.state = "failed";
            session.updatedAt = ts();
            emit("status", { sessionId: session.id, state: "failed", detail: "模拟上游发送失败" }, session.id);
            return reply(message.id, {
              commandId,
              state: "failed",
              sessionId: session.id,
              error: { code: "MOCK_SEND_FAILED", message: "模拟发送失败" },
            });
          }

          session.state = "running";
          session.updatedAt = ts();
          emit("session", { session: { ...session } }, session.id);

          const userMessage = {
            id: `msg_${++messageCounter}`,
            sessionId: session.id,
            role: "user",
            text,
            ts: ts(),
            order: ++orderCounter,
          };
          messages.push(userMessage);
          emit("message", { message: { ...userMessage } }, session.id);
          emit("status", { sessionId: session.id, state: "running", detail: "已收到任务" }, session.id);

          const assistantId = `msg_${++messageCounter}`;
          const assistantMessage = {
            id: assistantId,
            sessionId: session.id,
            role: "assistant",
            text: "正在处理：",
            streaming: true,
            ts: ts(),
            order: ++orderCounter,
          };
          messages.push(assistantMessage);
          emit(
            "message",
            {
              delta: true,
              message: {
                id: assistantId,
                sessionId: session.id,
                role: "assistant",
                text: "正在处理：",
                streaming: true,
                ts: assistantMessage.ts,
                order: assistantMessage.order,
              },
            },
            session.id,
          );
          assistantMessage.text += text;
          emit(
            "message",
            {
              delta: true,
              message: { id: assistantId, sessionId: session.id, role: "assistant", text },
            },
            session.id,
          );
          assistantMessage.text = `已完成：${text}`;
          assistantMessage.streaming = false;
          assistantMessage.attachments = [
            { id: "art_diff", artifactId: "art_diff", name: "src/app.mjs.diff", size: diff.length },
          ];
          emit(
            "message",
            {
              message: {
                id: assistantId,
                sessionId: session.id,
                role: "assistant",
                text: assistantMessage.text,
                streaming: false,
                ts: ts(),
                attachments: assistantMessage.attachments,
              },
            },
            session.id,
          );

          session.state = "idle";
          session.turnState = "completed";
          session.updatedAt = ts();
          emit("turn", { turn: { id: `turn_${++turnCounter}`, sessionId: session.id, state: "completed" } }, session.id);
          emit("session", { session: { ...session } }, session.id);

          return reply(message.id, { commandId, state: "succeeded", sessionId: session.id });
        }

        if (kind === "turn.stop") {
          const session = findSession(target.sessionId);
          if (!session) return fail(message.id, RPC_ERRORS.NOT_FOUND, `unknown session ${target.sessionId}`);
          session.state = "stopped";
          session.turnState = "stopped";
          session.updatedAt = ts();
          emit("status", { sessionId: session.id, state: "stopped", detail: "用户停止" }, session.id);
          emit("turn", { turn: { id: `turn_${++turnCounter}`, sessionId: session.id, state: "stopped" } }, session.id);
          emit("session", { session: { ...session } }, session.id);
          return reply(message.id, { commandId, state: "succeeded", sessionId: session.id });
        }

        if (kind === "interaction.answer") {
          const interaction = findInteraction(target.interactionId ?? payload.interactionId);
          if (!interaction) {
            return fail(message.id, RPC_ERRORS.NOT_FOUND, `unknown interaction ${target.interactionId}`);
          }
          if (interaction.status !== "pending") {
            return reply(message.id, {
              commandId,
              state: "superseded",
              duplicate: true,
              interactionId: interaction.id,
            });
          }
          interaction.status = "answered";
          interaction.answer = payload.answer ?? payload;
          emit("interaction", { interaction: { ...interaction } }, interaction.sessionId);
          return reply(message.id, { commandId, state: "succeeded", interactionId: interaction.id });
        }

        if (kind === "model.set") {
          const session = findSession(target.sessionId);
          if (!session) return fail(message.id, RPC_ERRORS.NOT_FOUND, `unknown session ${target.sessionId}`);
          if (!models.options.some((o) => o.id === payload.model)) {
            return fail(message.id, RPC_ERRORS.UNSUPPORTED_CAPABILITY, `unknown model ${payload.model}`);
          }
          session.model = payload.model;
          session.updatedAt = ts();
          models.current = payload.model;
          emit("model", { model: { id: payload.model, sessionId: session.id } }, session.id);
          emit("session", { session: { ...session } }, session.id);
          return reply(message.id, { commandId, state: "succeeded", model: payload.model });
        }

        if (kind === "terminal.open") {
          const session = findSession(target.sessionId);
          if (!session) return fail(message.id, RPC_ERRORS.NOT_FOUND, `unknown session ${target.sessionId}`);
          const terminal = {
            id: `term_${terminals.length + 1}`,
            sessionId: session.id,
            title: payload.title ?? "terminal",
            status: "open",
            node: session.node,
            environment: session.environment,
            output: "",
            fullLength: 0,
            truncated: false,
          };
          terminals.push(terminal);
          emit("terminal", { terminal: { ...terminal } }, session.id);
          return reply(message.id, { commandId, state: "succeeded", terminalId: terminal.id });
        }

        if (kind === "terminal.close") {
          const terminal = terminals.find((t) => t.id === (payload.terminalId ?? target.terminalId));
          if (!terminal) return fail(message.id, RPC_ERRORS.NOT_FOUND, "unknown terminal");
          terminal.status = "closed";
          emit("terminal", { terminal: { ...terminal } }, terminal.sessionId);
          return reply(message.id, { commandId, state: "succeeded" });
        }

        if (kind === "artifact.put") {
          const artifact = {
            id: payload.id ?? `art_${artifacts.length + 1}`,
            sessionId: target.sessionId ?? null,
            name: payload.name ?? "artifact",
            kind: payload.kind ?? "file",
            mime: payload.mime ?? "application/octet-stream",
            size: payload.size ?? (payload.content ?? "").length,
            sha256: payload.sha256 ?? null,
            node: payload.node ?? "node_local",
            environment: payload.environment ?? "host",
            content: payload.content ?? "",
            meta: payload.meta ?? null,
          };
          artifacts.push(artifact);
          emit("artifact", { artifact: { ...artifact } }, artifact.sessionId);
          return reply(message.id, { commandId, state: "succeeded", artifactId: artifact.id });
        }

        return fail(message.id, RPC_ERRORS.UNSUPPORTED_CAPABILITY, `mock does not implement ${kind}`);
      }

      default:
        return fail(message.id, RPC_ERRORS.METHOD_NOT_FOUND, `unknown method ${message.method}`);
    }
  }

  return {
    handleRpc,
    subscribe(fn) {
      listeners.add(fn);
      return () => listeners.delete(fn);
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
    get sessions() {
      return sessions;
    },
    get interactions() {
      return interactions;
    },
    get terminals() {
      return terminals;
    },
    get artifacts() {
      return artifacts;
    },
  };
}
