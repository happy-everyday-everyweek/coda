#!/usr/bin/env node
/**
 * 可脚本化的 fake ACP server —— 人工构造的协议实现，只用于测试 AGT-104。
 *
 * ⚠️ 它**不是**真实 Agent：不得作为“某个 Agent 已支持”的证据（TODO 5.4）。
 *
 * 用法：
 *   node fake-acp-server.mjs --version    → 打印版本并退出（供 probe 使用，无副作用）
 *   node fake-acp-server.mjs              → 在 stdio 上跑 ACP JSON-RPC
 *
 * 环境变量：
 *   FAKE_ACP_SCENARIO=happy|approval|cancel|crash|error  （默认 happy）
 *   FAKE_ACP_LOAD=1            声明并支持 session/load
 *   FAKE_ACP_CHUNK_DELAY_MS=10 每个流式片段之间的延迟
 *   FAKE_ACP_NO_UNKNOWN=1      不发送未知 sessionUpdate（默认会发一条，用于容错测试）
 */
import readline from "node:readline";

const VERSION = "fake-acp 1.0.0";

function main() {
  if (process.argv.includes("--version") || process.argv.includes("-v")) {
    process.stdout.write(`${VERSION}\n`);
    return;
  }

  const scenario = process.env.FAKE_ACP_SCENARIO ?? "happy";
  const loadEnabled = process.env.FAKE_ACP_LOAD === "1";
  const chunkDelayMs = Number(process.env.FAKE_ACP_CHUNK_DELAY_MS ?? "0");
  const sendUnknownUpdate = process.env.FAKE_ACP_NO_UNKNOWN !== "1";

  let permissionSeq = 1000;
  let sessionSeq = 0;
  const pendingPermissions = new Map();
  const cancelWaiters = new Map();
  const cancelledSessions = new Set();
  const sessions = new Set();

  const send = (message) => process.stdout.write(`${JSON.stringify(message)}\n`);
  const result = (id, value) => send({ jsonrpc: "2.0", id, result: value });
  const error = (id, code, message) => send({ jsonrpc: "2.0", id, error: { code, message } });
  const notify = (method, params) => send({ jsonrpc: "2.0", method, params });
  const update = (sessionId, payload) => notify("session/update", { sessionId, update: payload });
  const delay = (ms) => (ms > 0 ? new Promise((resolve) => setTimeout(resolve, ms)) : Promise.resolve());

  function waitForCancel(sessionId) {
    // 取消可能先到：已取消就立刻返回，避免测试挂死。
    if (cancelledSessions.has(sessionId)) return Promise.resolve();
    return new Promise((resolve) => {
      cancelWaiters.set(sessionId, resolve);
      const timer = setTimeout(() => {
        if (cancelWaiters.get(sessionId) === resolve) {
          cancelWaiters.delete(sessionId);
          resolve();
        }
      }, 5000);
      timer.unref?.();
    });
  }

  function requestPermission(sessionId, toolCall) {
    const id = permissionSeq++;
    send({
      jsonrpc: "2.0",
      id,
      method: "session/request_permission",
      params: {
        sessionId,
        toolCall,
        options: [
          { optionId: "allow-once", name: "Allow once", kind: "allow_once" },
          { optionId: "allow-always", name: "Always allow", kind: "allow_always" },
          { optionId: "reject-once", name: "Reject", kind: "reject_once" },
        ],
      },
    });
    return new Promise((resolve) => pendingPermissions.set(id, resolve));
  }

  async function handlePrompt(id, params) {
    const sessionId = params?.sessionId;
    if (!sessionId || !sessions.has(sessionId)) {
      error(id, -32602, `unknown session: ${sessionId}`);
      return;
    }
    if (scenario === "error") {
      error(id, -32000, "model unavailable: rate limit exceeded (retry after 30s)");
      return;
    }

    update(sessionId, { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Look" } });
    await delay(chunkDelayMs);
    update(sessionId, { sessionUpdate: "agent_thought_chunk", content: { type: "text", text: "checking workspace" } });

    if (scenario === "crash") {
      process.stderr.write("fake-acp: simulated crash\n");
      process.exit(3);
    }

    if (scenario === "cancel") {
      await waitForCancel(sessionId);
      result(id, { stopReason: "cancelled" });
      return;
    }

    if (scenario === "approval") {
      update(sessionId, {
        sessionUpdate: "tool_call",
        toolCallId: "tc_1",
        title: "Run npm test",
        kind: "execute",
        status: "pending",
        rawInput: { command: "npm test" },
      });
      const permission = await requestPermission(sessionId, {
        toolCallId: "tc_1",
        title: "Run npm test",
        kind: "execute",
      });
      const outcome = permission?.outcome ?? permission;
      if (outcome?.outcome === "selected" && String(outcome.optionId).startsWith("allow")) {
        update(sessionId, {
          sessionUpdate: "tool_call_update",
          toolCallId: "tc_1",
          status: "completed",
          rawOutput: "12 passing",
        });
        update(sessionId, { sessionUpdate: "plan", entries: [{ content: "run tests", status: "completed", priority: "high" }] });
        update(sessionId, { sessionUpdate: "agent_message_chunk", content: { type: "text", text: " approved" } });
        result(id, { stopReason: "end_turn" });
      } else {
        result(id, { stopReason: "refusal" });
      }
      return;
    }

    update(sessionId, {
      sessionUpdate: "tool_call",
      toolCallId: "tc_1",
      title: "Run npm test",
      kind: "execute",
      status: "pending",
      rawInput: { command: "npm test" },
    });
    await delay(chunkDelayMs);
    update(sessionId, {
      sessionUpdate: "tool_call_update",
      toolCallId: "tc_1",
      status: "completed",
      rawOutput: "12 passing",
    });
    update(sessionId, { sessionUpdate: "plan", entries: [{ content: "run tests", status: "completed", priority: "high" }] });
    update(sessionId, { sessionUpdate: "agent_message_chunk", content: { type: "text", text: " streaming" } });
    if (sendUnknownUpdate) {
      // 未知字段必须被适配器容忍并只进诊断（ADP-013），不能崩。
      update(sessionId, { sessionUpdate: "agent_future_thing", payload: { hint: "ignored" } });
    }
    await delay(chunkDelayMs);
    result(id, { stopReason: "end_turn" });
  }

  function handleCancel(params) {
    const sessionId = params?.sessionId;
    cancelledSessions.add(sessionId);
    const waiter = cancelWaiters.get(sessionId);
    if (waiter) {
      cancelWaiters.delete(sessionId);
      waiter();
    }
  }

  function handleMessage(message) {
    if (message && message.id !== undefined && pendingPermissions.has(message.id)) {
      const resolve = pendingPermissions.get(message.id);
      pendingPermissions.delete(message.id);
      resolve(message.result ?? message.error ?? null);
      return;
    }
    if (!message || typeof message.method !== "string") return;
    const { id, method, params } = message;

    switch (method) {
      case "initialize":
        result(id, {
          protocolVersion: 1,
          agentCapabilities: {
            loadSession: loadEnabled,
            promptCapabilities: { image: false, audio: false, embeddedContext: false },
          },
          authMethods: [],
        });
        return;
      case "session/new": {
        sessionSeq += 1;
        const sessionId = `acp_sess_${sessionSeq}`;
        sessions.add(sessionId);
        result(id, { sessionId });
        return;
      }
      case "session/load": {
        if (!loadEnabled) {
          error(id, -32601, "method not found: session/load");
          return;
        }
        const sessionId = params?.sessionId;
        if (!sessionId) {
          error(id, -32602, "session/load requires sessionId");
          return;
        }
        sessions.add(sessionId);
        result(id, { sessionId });
        return;
      }
      case "session/prompt":
        void handlePrompt(id, params);
        return;
      case "session/cancel":
        handleCancel(params);
        return;
      default:
        if (id !== undefined) error(id, -32601, `method not found: ${method}`);
        return;
    }
  }

  const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
  rl.on("line", (line) => {
    const text = line.trim();
    if (!text) return;
    let message;
    try {
      message = JSON.parse(text);
    } catch {
      send({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "parse error" } });
      return;
    }
    handleMessage(message);
  });
  rl.on("close", () => {
    process.exitCode = 0;
  });
}

main();
