#!/usr/bin/env node
/** 测试替身：模拟 kimi headless JSONL（不联网）。 */
const argv = process.argv.slice(2);
if (argv.includes("--version")) { console.log("2.5.2-fake"); process.exit(0); }
if (argv.includes("--help")) {
  console.log("Usage: kimi [options] [command]");
  console.log("  -p, --prompt <prompt>   Run one prompt non-interactively");
  console.log("  --output-format <format>  text | stream-json");
  process.exit(0);
}
const print = (obj) => console.log(JSON.stringify(obj));
print({ role: "meta", type: "system.version", version: "2.5.2-fake" });
if (process.env.KIMI_FAKE_MODE === "error") {
  console.error("error: failed to run prompt: No model configured");
  process.exitCode = 1;
} else {
  print({ role: "assistant", type: "assistant.message", text: "pong" });
  print({ role: "assistant", type: "result.done" });
}
