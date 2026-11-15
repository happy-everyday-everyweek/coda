/** PA 测试工具：临时目录、假时钟、脚本化 LLM、假核心（只实现 RPC，不实现适配器）。 */
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { makeId } from "../../contracts/ids.mjs";
import { createPaMessage, formatHlc } from "../../pa/schema.mjs";

export const FIXED_MS = 1_700_000_000_000;

export function makeTempDir(t, prefix = "coda-pa-test-") {
  const dir = mkdtempSync(join(tmpdir(), prefix));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  return dir;
}

export function fakeClock(start = FIXED_MS) {
  let current = start;
  return {
    now: () => current,
    advance(ms = 1) {
      current += ms;
      return current;
    },
    set(ms) {
      current = ms;
    },
  };
}

export function hlcFor(nodeId, physical = FIXED_MS, counter = 0) {
  return formatHlc({ physical, counter, nodeId });
}

export function makeMessage({
  paId,
  node = "dev_a",
  id = undefined,
  role = "user",
  content = "hello",
  seq = 1,
  physical = FIXED_MS,
  counter = 0,
  replyTo = null,
  attachments = [],
  origin = "local",
} = {}) {
  return createPaMessage({
    id,
    paId,
    sourceNode: node,
    role,
    content,
    attachments,
    replyTo,
    seq,
    hlc: hlcFor(node, physical, counter),
    origin,
  });
}

/**
 * 脚本化 LLM：按顺序返回响应，支持函数形式的动态响应。
 * 返回的是函数本身，runner 的 callLlm 支持 llm(request)。
 */
export function scriptedLlm(responses) {
  const queue = [...responses];
  const calls = [];
  const fn = async (request) => {
    calls.push(request);
    if (queue.length === 0) throw new Error("scripted LLM has no more responses");
    const next = queue.shift();
    return typeof next === "function" ? next(request) : next;
  };
  fn.calls = calls;
  fn.remaining = () => queue.length;
  return fn;
}

/**
 * 假共享核心：只暴露 core.handleRpc。
 * - core.snapshot 返回会话投影（PA 查询路径）
 * - core.command 持久化 commandId 收据，重复投递返回同一收据（幂等）
 * - 不包含任何适配器；用于证明 PA 走统一命令层。
 */
export function fakeCore({ sessions = [], failKinds = [], failError = "core command failed" } = {}) {
  const sessionMap = new Map(sessions.map((session) => [session.id, session]));
  const results = new Map();
  const received = [];
  const rpcCalls = [];
  let executions = 0;
  let sessionCounter = 0;

  return {
    sessions: sessionMap,
    results,
    received,
    rpcCalls,
    get executions() {
      return executions;
    },
    async handleRpc(request) {
      rpcCalls.push(request);
      const base = { jsonrpc: "2.0", id: request.id };
      if (request.method === "core.snapshot") {
        return { ...base, result: { state: { sessions: [...sessionMap.values()] } } };
      }
      if (request.method !== "core.command") {
        return { ...base, error: { code: -32601, message: `method not found: ${request.method}` } };
      }
      const command = request.params?.command;
      received.push(command);
      if (results.has(command.id)) {
        // 对齐 core/server.mjs 的幂等标志：deduped（不是 duplicate）。
        return { ...base, result: { ...results.get(command.id), deduped: true } };
      }
      if (failKinds.includes(command.kind)) {
        return { ...base, error: { code: -32603, message: failError } };
      }
      executions += 1;
      const result = {
        commandId: command.id,
        kind: command.kind,
        state: "succeeded",
        receipt: { state: "succeeded", at: new Date().toISOString() },
        result: {},
      };
      if (command.kind === "session.create") {
        sessionCounter += 1;
        const sessionId = makeId("ses");
        sessionMap.set(sessionId, {
          id: sessionId,
          nodeId: command.target?.nodeId ?? null,
          agentInstanceId: command.payload?.agentInstanceId ?? null,
        });
        result.result = { sessionId };
      } else if (command.kind === "turn.send") {
        result.result = { sessionId: command.payload?.sessionId ?? null, queued: true };
      } else if (command.kind === "turn.stop") {
        result.result = { sessionId: command.payload?.sessionId ?? null, stopped: true };
      } else if (command.kind === "interaction.answer") {
        result.result = {
          requestId: command.payload?.requestId ?? command.payload?.interactionId ?? null,
          decision: command.payload?.decision ?? null,
        };
      }
      results.set(command.id, result);
      return { ...base, result };
    },
  };
}

/** 直接使用 Agent 的假路径：与 PA 完全无关，用来证明 PA 停止不影响它。 */
export function fakeDirectAgent() {
  const log = [];
  return {
    running: true,
    log,
    send(text) {
      log.push(text);
      return { ok: true, count: log.length };
    },
    stop() {
      this.running = false;
    },
  };
}
