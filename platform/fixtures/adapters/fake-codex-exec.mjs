#!/usr/bin/env node
/** 测试替身：模拟 `codex exec --json` 的 JSONL 输出，不联网。 */
const argv = process.argv.slice(2);
if (argv.includes("--version")) {
  console.log("codex-cli 0.167.0-fake");
  process.exit(0);
}
if (argv.includes("--help") || argv.includes("-h")) {
  console.log("Usage: codex exec [OPTIONS] [PROMPT]");
  console.log("  --json  Print events to stdout as JSONL");
  console.log("  -s, --sandbox <SANDBOX_MODE>");
  console.log("  -o, --output-last-message <FILE>");
  process.exit(0);
}
const mode = process.env.CODEX_FAKE_MODE ?? "ok";
const out = (obj) => console.log(JSON.stringify(obj));
out({ type: "thread.started", thread_id: "thread-fake-1" });
out({ type: "turn.started" });
if (mode === "error") {
  out({ type: "error", message: "401 Unauthorized: Missing bearer" });
  out({ type: "turn.failed", error: { message: "401 Unauthorized" } });
  process.exitCode = 1;
} else {
  out({ type: "item.completed", item: { id: "item-1", type: "reasoning", text: "思考中" } });
  out({ type: "item.completed", item: { id: "item-2", type: "agent_message", text: "pong" } });
  out({ type: "turn.completed" });
}
