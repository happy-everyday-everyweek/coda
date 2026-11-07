#!/usr/bin/env node
/** 测试替身：模拟 `claude -p --output-format stream-json --verbose` 的帧序列，不联网。 */
const argv = process.argv.slice(2);
if (argv.includes("--version")) { console.log("2.1.319 (fake)"); process.exit(0); }
if (argv.includes("--help")) {
  console.log("Usage: claude [options] [prompt]");
  console.log("  --output-format <format>  text, json, stream-json");
  console.log("  --input-format <format>   text, stream-json");
  console.log("  --resume <session-id>     Resume a conversation");
  console.log("  --permission-mode <mode>  Permission mode");
  process.exit(0);
}
const mode = process.env.CLAUDE_FAKE_MODE ?? "ok";
const print = (obj) => console.log(JSON.stringify(obj));
print({ type: "system", subtype: "init", session_id: "claude-fake-session", cwd: process.cwd(), model: "fake-model", permissionMode: "default", tools: ["Bash"] });
if (mode === "error") {
  print({ type: "result", subtype: "error", is_error: true, result: "Not logged in", session_id: "claude-fake-session" });
  process.exitCode = 1;
} else {
  print({ type: "assistant", message: { role: "assistant", content: [{ type: "text", text: "po" }, { type: "tool_use", id: "toolu_1", name: "Bash", input: { command: "echo hi" } }] } });
  print({ type: "user", message: { role: "user", content: [{ type: "tool_result", tool_use_id: "toolu_1", content: "hi" }] } });
  print({ type: "assistant", message: { role: "assistant", content: [{ type: "text", text: "ng" }] } });
  print({ type: "result", subtype: "success", is_error: false, result: "pong", session_id: "claude-fake-session", duration_ms: 12 });
}
