#!/usr/bin/env node
/** 测试替身：模拟 codex CLI 的 --version/--help 与 app-server 最小会话协议（newline JSON-RPC）。 */
import { createInterface } from "node:readline";

const argv = process.argv.slice(2);
if (argv.includes("--version")) {
  console.log("codex-cli 0.167.0-fake");
  process.exit(0);
}
if (argv.includes("--help") || argv.includes("-h")) {
  console.log("Usage: codex [OPTIONS] [PROMPT]");
  console.log("  app-server   [experimental] Run the app server or related tooling");
  console.log("  generate-json-schema  Generate JSON Schema for the app server protocol");
  process.exit(0);
}

const send = (msg) => process.stdout.write(`${JSON.stringify(msg)}\n`);
const notify = (method, params) => send({ method, params });

const THREAD = "thread-fake-1";
let turnCounter = 0;

const reader = createInterface({ input: process.stdin, crlfDelay: Infinity });
reader.on("line", (line) => {
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return;
  }
  const { id, method, params = {} } = msg;
  switch (method) {
    case "initialize":
      send({
        id,
        result: {
          userAgent: "codex-app-server/0.167.0-fake (fake; x86_64)",
          codexHome: "~/.codex",
          platformFamily: "unix",
          platformOs: "linux",
          capabilities: { experimentalApi: true },
        },
      });
      break;
    case "initialized":
      break;
    case "thread/start":
      send({ id, result: { thread: { id: THREAD, cwd: params.cwd ?? process.cwd(), model: params.model ?? "fake-model" } } });
      break;
    case "thread/resume":
      send({ id, result: { thread: { id: params.threadId ?? THREAD } } });
      break;
    case "turn/start": {
      const turnId = `turn-fake-${++turnCounter}`;
      send({ id, result: { turn: { id: turnId, threadId: params.threadId ?? THREAD } } });
      const threadId = params.threadId ?? THREAD;
      setTimeout(() => {
        notify("agentMessage/delta", { threadId, turnId, delta: "po" });
        notify("reasoning/delta", { threadId, turnId, delta: "思考中" });
        notify("item/started", { threadId, turnId, item: { id: "item-1", type: "commandExecution", command: "echo hi" } });
        notify("item/completed", { threadId, turnId, item: { id: "item-1", type: "commandExecution", command: "echo hi", exitCode: 0, aggregatedOutput: "hi" } });
        notify("agentMessage/delta", { threadId, turnId, delta: "ng" });
        notify("turn/completed", { threadId, turnId });
      }, 20);
      break;
    }
    case "turn/steer":
      send({ id, result: {} });
      break;
    case "turn/interrupt": {
      send({ id, result: {} });
      const turnId = params.turnId ?? "turn-fake-1";
      setTimeout(() => notify("turn/aborted", { threadId: params.threadId ?? THREAD, turnId }), 10);
      break;
    }
    default:
      if (id !== undefined) send({ id, error: { code: -32601, message: `method not implemented in fake: ${method}` } });
  }
});
reader.on("close", () => process.exit(0));
