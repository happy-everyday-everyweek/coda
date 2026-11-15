#!/usr/bin/env node
/** 测试替身：模拟 codebuddy --version/--help（不启动真实服务）。 */
const argv = process.argv.slice(2);
if (argv.includes("--version")) { console.log("2.181.0-fake"); process.exit(0); }
if (argv.includes("--help")) {
  console.log("Usage: codebuddy|cbc [options] [command] [prompt]");
  console.log("  --serve  Start HTTP server (Web UI, REST API, ACP over HTTP SSE)");
  console.log("  --acp    Run as ACP stdio server");
  process.exit(0);
}
setTimeout(() => process.exit(0), 500);
