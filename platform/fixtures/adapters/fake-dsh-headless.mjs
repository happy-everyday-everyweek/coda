#!/usr/bin/env node
/** 测试替身：模拟 `dsh --profile headless --json` 的 NDJSON 输出，不联网。 */
const mode = process.env.DSH_FAKE_MODE ?? "ok";
const argv = process.argv.slice(2);
if (argv.includes("--version")) {
  console.log("0.2.2-fake");
  process.exit(0);
}
if (argv.includes("--help")) {
  console.log("Usage: dsh --profile headless [options] [task...]");
  console.log("  --json    write newline-delimited run events to stdout");
  console.log("  --session-id <id>  adopt the persisted Session with this id");
  console.log("  --approval-policy <policy>  (ask|never)");
  process.exit(0);
}
const sessionId = "session-fake";
const lines = [
  { type: "session", sessionId, cwd: process.cwd() },
  { type: "status", phase: "turn_start", turn: 1 },
  { type: "status", phase: "step_start", turn: 1, step: 1 },
  { type: "status", phase: "step_end", turn: 1, step: 1 },
];
for (const line of lines) console.log(JSON.stringify(line));
if (mode === "error") {
  const error = { message: "fake failure", code: "MISSING_CREDENTIAL" };
  console.log(JSON.stringify({ type: "status", phase: "turn_end", turn: 1, reason: { kind: "error", error } }));
  console.log(JSON.stringify({ type: "final", sessionId, reason: { kind: "error", error }, text: "" }));
  process.exitCode = 1;
} else {
  console.log(JSON.stringify({ type: "status", phase: "turn_end", turn: 1, reason: { kind: "ok" } }));
  console.log(JSON.stringify({ type: "final", sessionId, reason: { kind: "ok" }, text: "pong" }));
}
