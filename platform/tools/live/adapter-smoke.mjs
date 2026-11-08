#!/usr/bin/env node
/**
 * 适配器级真实回合冒烟：不是直接调用 CLI，而是用 platform/adapters 的适配器
 * 驱动真实 Agent 二进制，网关作为模型后端，验证「适配器 → 进程 → 统一事件」。
 *
 * 用法（网关已由 live 脚本启动）：
 *   node platform/tools/live/adapter-smoke.mjs --agent claude \
 *     --gateway http://127.0.0.1:18080 --out live-turns/claude/adapter.json
 *
 * 目前支持 claude / codex。失败退出码 1，并把观测到的事件写入报告。
 */
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import assert from "node:assert/strict";

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : fallback;
};
const agent = option("--agent");
const gateway = option("--gateway", "http://127.0.0.1:18080");
const out = option("--out", `adapter-${agent}.json`);
const prompt = option("--prompt", "Reply with exactly CODA_GATEWAY_OK. Do not use tools.");

if (!["claude", "codex", "kimi"].includes(agent)) {
  console.error(`unsupported --agent: ${agent}`);
  process.exit(2);
}

const events = [];
let report = { agent, gateway, prompt, startedAt: new Date().toISOString(), events: [] };

async function run() {
  let adapter;
  let sessionId = "live-session";
  if (agent === "claude") {
    process.env.ANTHROPIC_BASE_URL = gateway;
    process.env.ANTHROPIC_AUTH_TOKEN = "test-token";
    process.env.ANTHROPIC_MODEL = "claude-sonnet-4-5";
    process.env.CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC = "1";
    const { createClaudeStreamJsonAdapter } = await import("../../adapters/claude-stream-json.mjs");
    adapter = createClaudeStreamJsonAdapter({ command: "claude", model: "claude-sonnet-4-5" });
  } else if (agent === "kimi") {
    const kimiHome = option("--kimi-home");
    if (!kimiHome) throw new Error("kimi 需要 --kimi-home（已导入 coda provider 的配置目录）");
    process.env.KIMI_CODE_HOME = kimiHome;
    process.env.HOME = option("--home", process.env.HOME);
    const { createKimiHeadlessAdapter } = await import("../../adapters/kimi-headless.mjs");
    adapter = createKimiHeadlessAdapter({ command: "kimi" });
  } else {
    const home = mkdtempSync(join(tmpdir(), "coda-codex-home-"));
    writeFileSync(
      join(home, "config.toml"),
      [
        'model = "gpt-5-codex"',
        'model_provider = "coda"',
        "[model_providers.coda]",
        'name = "Coda Gateway"',
        `base_url = "${gateway}/v1"`,
        'env_key = "OPENAI_API_KEY"',
        'wire_api = "responses"',
        "",
      ].join("\n"),
    );
    process.env.CODEX_HOME = home;
    process.env.OPENAI_API_KEY = "test-token";
    const { createCodexExecAdapter } = await import("../../adapters/codex-exec.mjs");
    adapter = createCodexExecAdapter({ command: "codex", model: "gpt-5-codex" });
  }

  adapter.onEvent((event) => events.push({ kind: event.kind, payload: event.payload ?? null }));
  const probe = await adapter.probe();
  report.probe = { installed: probe.installed, version: probe.version, path: probe.path };
  assert.equal(probe.installed, true, "CLI 未安装");

  await adapter.createSession({ sessionId });
  const result = await adapter.send({ sessionId, text: prompt });
  report.result = result;
  report.eventKinds = events.map((e) => e.kind);
  assert.equal(result.state, "succeeded", `回合未成功: ${result.state} ${result.error?.message ?? ""}`);
  assert.match(result.text, /CODA_GATEWAY_OK/, "适配器未收到固定回复");
  assert.ok(events.some((e) => e.kind === "message"), "没有 message 事件");
  assert.ok(events.some((e) => e.kind === "status"), "没有 status 事件");
  report.events = events;
  report.ok = true;
  console.log(JSON.stringify({ agent, ok: true, version: probe.version, text: result.text, eventKinds: report.eventKinds }));
}

try {
  await run();
} catch (error) {
  report.ok = false;
  report.error = error.message;
  report.events = events;
  console.error(`adapter smoke failed: ${error.message}`);
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, JSON.stringify(report, null, 2));
}
