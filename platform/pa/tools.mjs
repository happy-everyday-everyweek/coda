/**
 * PA 协调工具（PA-104，验收 PA-006 / SEC-005）。
 *
 * 唯一出口是共享核心的统一命令层：
 * - 变更类动作（创建会话 / 发送 / 停止 / 回答审批）一律用 contracts/commands.mjs 的
 *   createCommand 构造，经 `core.handleRpc("core.command", {command})` 投递；
 * - 查询会话读核心投影（`core.snapshot`），仍然只经过核心 RPC，不碰适配器内部；
 * - 这里不 import adapters/、不 import core/ 内部模块，不直接调用任何 Agent 进程。
 *
 * 每个动作返回收据（含 commandId、state、duplicate），并写审计。查询动作返回读收据，
 * 审计 decision 为 query，与 PA 的自动派发/审批决定分开。
 */
import { createCommand } from "../contracts/commands.mjs";
import { METHODS, rpcRequest } from "../contracts/rpc.mjs";
import { isIdOf, nowIso } from "../contracts/ids.mjs";
import { summarizeForAudit } from "./audit.mjs";

const TOOL_ALIASES = {
  listSessions: "listSessions",
  "sessions.list": "listSessions",
  createSession: "createSession",
  "session.create": "createSession",
  sendTurn: "sendTurn",
  "turn.send": "sendTurn",
  stopTurn: "stopTurn",
  "turn.stop": "stopTurn",
  answerInteraction: "answerInteraction",
  "interaction.answer": "answerInteraction",
};

function extractSessions(projection) {
  const candidates =
    projection?.state?.sessions ?? projection?.snapshot?.sessions ?? projection?.sessions ?? [];
  return Array.isArray(candidates) ? candidates : [];
}

function wrapRpcError(response) {
  const error = new Error(response?.error?.message ?? "core RPC failed");
  error.code = response?.error?.code ?? null;
  return error;
}

function normalizeReceipt(command, result) {
  const state = result?.state ?? result?.receipt?.state ?? "succeeded";
  return {
    ok: state === "succeeded",
    tool: command.kind,
    commandId: command.id,
    id: command.id,
    kind: command.kind,
    state,
    receipt: result?.receipt ?? null,
    result: result?.result === undefined ? result ?? null : result.result,
    // 核心用 deduped（见 core/server.mjs）；测试 fake 可用 duplicate。两者都表示幂等重投。
    duplicate: result?.duplicate === true || result?.deduped === true,
    at: nowIso(),
  };
}

export function createPaTools({
  nodeId,
  core = null,
  audit = null,
  profileId = null,
  defaultTtlMs = 60_000,
} = {}) {
  if (!isIdOf(nodeId, "dev")) throw new Error("createPaTools requires nodeId (dev_ ID)");

  const transport = (() => {
    if (!core) return null;
    if (typeof core.handleRpc !== "function") return null;
    return async (command) => {
      const response = await core.handleRpc(rpcRequest(METHODS.command, { command }, command.id));
      if (response?.error) throw wrapRpcError(response);
      return response?.result ?? response;
    };
  })();

  async function readProjection(method, params = {}) {
    if (!core) throw new Error("core offline: no command layer available");
    if (typeof core.handleRpc !== "function") {
      throw new Error("core exposes no read projection interface (core.handleRpc)");
    }
    const response = await core.handleRpc(rpcRequest(method, params));
    if (response?.error) throw wrapRpcError(response);
    return response?.result ?? response;
  }

  function record({ actor = "pa", action, decision, targetNode = null, targetSessionId = null, commandId = null, params = {}, receipt, error = null, at }) {
    if (!audit) return;
    try {
      return audit.append({
        actor,
        action,
        decision,
        sourceNode: nodeId,
        targetNode,
        targetSessionId,
        commandId,
        params,
        result: receipt
          ? { state: receipt.state, ok: receipt.ok, duplicate: receipt.duplicate === true }
          : { state: "failed", ok: false, duplicate: false },
        error,
        at: at ?? nowIso(),
      });
    } catch (auditError) {
      // 审计是 PA-006 的硬要求；把失败暴露给调用者，不静默吞掉。
      if (receipt) receipt.auditError = auditError.message;
      return null;
    }
  }

  function invalid(tool, message, params = {}, actor = "pa") {
    const at = nowIso();
    const receipt = {
      ok: false,
      tool,
      commandId: null,
      id: null,
      kind: tool,
      state: "failed",
      receipt: null,
      result: null,
      duplicate: false,
      error: message,
      at,
    };
    record({ actor, action: tool, decision: "dispatch", params, receipt, error: message, at });
    return receipt;
  }

  async function dispatchCommand(command, { actor = "pa", decision = "dispatch", params = null } = {}) {
    if (!command || typeof command !== "object") throw new Error("dispatchCommand requires a command object");
    const at = nowIso();
    if (!transport) {
      const receipt = {
        ok: false,
        tool: command.kind,
        commandId: command.id,
        id: command.id,
        kind: command.kind,
        state: "unavailable",
        receipt: null,
        result: null,
        duplicate: false,
        error: "core offline: command layer unavailable",
        at,
      };
      record({
        actor,
        action: command.kind,
        decision,
        targetNode: command.target?.nodeId ?? null,
        targetSessionId: command.target?.sessionId ?? null,
        commandId: command.id,
        params: params ?? command.payload ?? {},
        receipt,
        error: receipt.error,
        at,
      });
      return receipt;
    }
    try {
      const result = await transport(command);
      const receipt = normalizeReceipt(command, result);
      record({
        actor,
        action: command.kind,
        decision,
        targetNode: command.target?.nodeId ?? null,
        targetSessionId: command.target?.sessionId ?? null,
        commandId: command.id,
        params: params ?? command.payload ?? {},
        receipt,
        at: receipt.at,
      });
      return receipt;
    } catch (error) {
      const receipt = {
        ok: false,
        tool: command.kind,
        commandId: command.id,
        id: command.id,
        kind: command.kind,
        state: "failed",
        receipt: null,
        result: null,
        duplicate: false,
        error: error.message,
        code: error.code ?? null,
        at: nowIso(),
      };
      record({
        actor,
        action: command.kind,
        decision,
        targetNode: command.target?.nodeId ?? null,
        targetSessionId: command.target?.sessionId ?? null,
        commandId: command.id,
        params: params ?? command.payload ?? {},
        receipt,
        error: error.message,
        at: receipt.at,
      });
      return receipt;
    }
  }

  async function listSessions({ limit = 200 } = {}) {
    const at = nowIso();
    try {
      const projection = await readProjection(METHODS.snapshot, {});
      const sessions = extractSessions(projection).slice(0, Math.max(0, Number(limit) || 0));
      const receipt = {
        ok: true,
        tool: "sessions.list",
        commandId: null,
        id: null,
        kind: "sessions.list",
        state: "succeeded",
        transport: "core.snapshot",
        receipt: null,
        result: { sessions },
        sessions,
        duplicate: false,
        at,
      };
      record({
        action: "sessions.list",
        decision: "query",
        params: { limit },
        receipt,
        at,
      });
      return receipt;
    } catch (error) {
      const receipt = {
        ok: false,
        tool: "sessions.list",
        commandId: null,
        id: null,
        kind: "sessions.list",
        state: core ? "failed" : "unavailable",
        transport: "core.snapshot",
        receipt: null,
        result: { sessions: [] },
        sessions: [],
        duplicate: false,
        error: error.message,
        at,
      };
      record({ action: "sessions.list", decision: "query", params: { limit }, receipt, error: error.message, at });
      return receipt;
    }
  }

  async function createSession(args = {}) {
    const {
      agentInstanceId,
      workspaceId = null,
      path = null,
      cwd = null,
      title = null,
      model = null,
      options = {},
      environmentId = null,
      targetNode = nodeId,
      actor = "pa",
      ttlMs = defaultTtlMs,
    } = args;
    if (!isIdOf(agentInstanceId, "ai")) {
      return invalid("session.create", "createSession requires agentInstanceId (ai_ ID)", args, actor);
    }
    // 对齐 core/server.mjs 的 session.create 参数：workspaceId 或 path/cwd。
    const resolvedWorkspaceId = workspaceId ?? (args.workspace && typeof args.workspace.id === "string" ? args.workspace.id : null);
    const resolvedPath =
      path ?? cwd ?? (args.workspace && typeof args.workspace.path === "string" ? args.workspace.path : null);
    const command = createCommand({
      kind: "session.create",
      source: "pa",
      target: { nodeId: targetNode, agentInstanceId },
      payload: {
        agentInstanceId,
        nodeId: targetNode,
        environmentId,
        workspaceId: resolvedWorkspaceId,
        path: resolvedPath,
        title,
        model,
        options,
      },
      ttlMs,
    });
    return dispatchCommand(command, { actor });
  }

  async function sendTurn(args = {}) {
    const { sessionId, text, attachments = [], actor = "pa", ttlMs = defaultTtlMs } = args;
    if (!isIdOf(sessionId, "ses")) return invalid("turn.send", "sendTurn requires sessionId (ses_ ID)", args, actor);
    if (typeof text !== "string" || text.trim().length === 0) {
      return invalid("turn.send", "sendTurn requires non-empty text", args, actor);
    }
    if (!Array.isArray(attachments)) return invalid("turn.send", "attachments must be an array", args, actor);
    const command = createCommand({
      kind: "turn.send",
      source: "pa",
      target: { nodeId, sessionId },
      payload: { sessionId, text, attachments },
      ttlMs,
    });
    return dispatchCommand(command, { actor });
  }

  async function stopTurn(args = {}) {
    const { sessionId, turnId = null, actor = "pa", ttlMs = defaultTtlMs } = args;
    if (!isIdOf(sessionId, "ses")) return invalid("turn.stop", "stopTurn requires sessionId (ses_ ID)", args, actor);
    const command = createCommand({
      kind: "turn.stop",
      source: "pa",
      target: { nodeId, sessionId, turnId },
      payload: { sessionId, turnId },
      ttlMs,
    });
    return dispatchCommand(command, { actor });
  }

  async function answerInteraction(args = {}) {
    const {
      requestId = null,
      interactionId = null,
      sessionId,
      answer = null,
      decision = "approve",
      actor = "pa",
      ttlMs = defaultTtlMs,
    } = args;
    const resolvedRequestId = requestId ?? interactionId;
    if (!isIdOf(resolvedRequestId, "int")) {
      return invalid("interaction.answer", "answerInteraction requires requestId (int_ ID)", args, actor);
    }
    if (!isIdOf(sessionId, "ses")) {
      return invalid("interaction.answer", "answerInteraction requires sessionId (ses_ ID)", args, actor);
    }
    if (!["approve", "deny", "answer"].includes(decision)) {
      return invalid("interaction.answer", "decision must be approve, deny or answer", args, actor);
    }
    if (actor !== "pa" && actor !== "user") {
      return invalid("interaction.answer", "actor must be pa or user", args, actor);
    }
    const command = createCommand({
      kind: "interaction.answer",
      source: actor === "user" ? "user" : "pa",
      target: { nodeId, sessionId, requestId: resolvedRequestId },
      payload: { sessionId, requestId: resolvedRequestId, answer, decision },
      ttlMs,
    });
    return dispatchCommand(command, { actor, decision });
  }

  async function invoke(name, args = {}) {
    const canonical = TOOL_ALIASES[name];
    if (!canonical) {
      return invalid(String(name), `unknown PA tool: ${String(name)}`, args);
    }
    return tools[canonical](args);
  }

  function specs() {
    return [
      {
        name: "listSessions",
        description: "查询核心中的 Agent 会话（只读投影），用于决定跟进哪个会话。",
        parameters: { type: "object", properties: { limit: { type: "integer", minimum: 1 } }, additionalProperties: false },
      },
      {
        name: "createSession",
        description: "在已授权节点上创建一个 Agent 会话。",
        parameters: {
          type: "object",
          properties: {
            agentInstanceId: { type: "string", description: "ai_ 前缀的 Agent 实例 ID" },
            workspaceId: { type: ["string", "null"] },
            path: { type: ["string", "null"], description: "工作区路径；省略时由核心使用默认工作区" },
            title: { type: ["string", "null"] },
            model: { type: ["string", "null"] },
          },
          required: ["agentInstanceId"],
          additionalProperties: false,
        },
      },
      {
        name: "sendTurn",
        description: "向指定 Agent 会话发送一轮任务。",
        parameters: {
          type: "object",
          properties: {
            sessionId: { type: "string" },
            text: { type: "string" },
            attachments: { type: "array" },
          },
          required: ["sessionId", "text"],
          additionalProperties: false,
        },
      },
      {
        name: "stopTurn",
        description: "停止指定 Agent 会话的当前回合。",
        parameters: {
          type: "object",
          properties: { sessionId: { type: "string" }, turnId: { type: ["string", "null"] } },
          required: ["sessionId"],
          additionalProperties: false,
        },
      },
      {
        name: "answerInteraction",
        description: "代表用户或 PA 回答 Agent 审批/提问；决定会进入审计。",
        parameters: {
          type: "object",
          properties: {
            requestId: { type: "string", description: "int_ 前缀的审批/提问 ID" },
            sessionId: { type: "string" },
            decision: { type: "string", enum: ["approve", "deny", "answer"] },
            answer: {},
          },
          required: ["requestId", "sessionId", "decision"],
          additionalProperties: false,
        },
      },
    ];
  }

  const tools = {
    nodeId,
    profileId,
    hasCommandLayer: transport !== null,
    listSessions,
    createSession,
    sendTurn,
    stopTurn,
    answerInteraction,
    invoke,
    dispatchCommand,
    specs,
    summarize: summarizeForAudit,
  };

  return tools;
}
