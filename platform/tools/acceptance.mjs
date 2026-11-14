#!/usr/bin/env node
/**
 * 验收证据收集器（ACCEPTANCE 1.3：命令、环境、结果、失败日志）。
 *
 * 用法：
 *   node platform/tools/acceptance.mjs
 *   node platform/tools/acceptance.mjs --out ../artifacts/acceptance
 *
 * 产物：<out>/<run-id>/{commands.txt,environment.json,result.json,summary.md,test.log}
 * 只记录相对路径与脱敏环境（不写 HOME、主机名以外的敏感信息）。
 */
import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID } from "node:crypto";

const here = dirname(fileURLToPath(import.meta.url));
const platformDir = resolve(here, "..");
const repoRoot = resolve(platformDir, "..");

const args = process.argv.slice(2);
const outIdx = args.indexOf("--out");
const outBase = resolve(outIdx >= 0 && args[outIdx + 1] ? args[outIdx + 1] : join(repoRoot, "artifacts", "acceptance"));

const started = new Date();
const runId = `${started.toISOString().replace(/[:.]/g, "-")}-${randomUUID().slice(0, 8)}`;
const runDir = join(outBase, runId);
mkdirSync(runDir, { recursive: true });

function gitHead() {
  const r = spawnSync("git", ["rev-parse", "--short", "HEAD"], { cwd: repoRoot, encoding: "utf8" });
  return r.status === 0 ? r.stdout.trim() : null;
}

function gitBranch() {
  const r = spawnSync("git", ["rev-parse", "--abbrev-ref", "HEAD"], { cwd: repoRoot, encoding: "utf8" });
  return r.status === 0 ? r.stdout.trim() : null;
}

const command = 'node --test "test/**/*.test.mjs"';
const startedAt = Date.now();
const run = spawnSync(process.execPath, ["--test", "test/**/*.test.mjs"], {
  cwd: platformDir,
  encoding: "utf8",
  timeout: 15 * 60_000,
  env: { ...process.env, NO_COLOR: "1" },
});
const durationMs = Date.now() - startedAt;
const stdout = run.stdout ?? "";
const stderr = run.stderr ?? "";
const log = [stdout, stderr].join("\n");

const summary = {};
for (const line of stdout.split("\n")) {
  const m = line.match(/^ℹ\s+(tests|pass|fail|cancelled|skipped|todo|duration_ms)\s+(\d+(?:\.\d+)?)/);
  if (m) summary[m[1]] = Number(m[2]);
}

const environment = {
  runId,
  startedAt: started.toISOString(),
  finishedAt: new Date().toISOString(),
  durationMs,
  node: process.version,
  platform: process.platform,
  arch: process.arch,
  repo: { branch: gitBranch(), commit: gitHead() },
  cwd: "platform/",
  command,
  testDiscovery: 'node --test "test/**/*.test.mjs"（Node 24 目录模式不可用，使用 glob）',
};

const result = {
  exitCode: run.status,
  signal: run.signal,
  summary,
  status: run.status === 0 && (summary.fail ?? 0) === 0 ? "PASS" : "FAIL",
  note:
    "本结果只代表参考实现的契约/单元测试；G0–G5 发布门槛、真机与真实 Agent 证据见 ACCEPTANCE.md，不得据此标记发布 PASS。",
};

writeFileSync(join(runDir, "environment.json"), JSON.stringify(environment, null, 2) + "\n");
writeFileSync(join(runDir, "commands.txt"), `${command}\n# cwd: ${platformDir}\n`);
writeFileSync(join(runDir, "result.json"), JSON.stringify(result, null, 2) + "\n");
writeFileSync(join(runDir, "test.log"), log + "\n");

const failing = stdout
  .split("\n")
  .filter((l) => /^✖/.test(l))
  .slice(0, 20);

const md = [
  `# 验收证据 ${runId}`,
  "",
  `- 时间：${environment.startedAt} → ${environment.finishedAt}（${durationMs} ms）`,
  `- 分支/提交：${environment.repo.branch} @ ${environment.repo.commit ?? "unknown"}`,
  `- 运行时：Node ${environment.node} / ${environment.platform} ${environment.arch}`,
  `- 命令：\`${command}\`（cwd: platform/）`,
  `- 结果：**${result.status}**（exit=${result.exitCode}）`,
  `- 计数：tests=${summary.tests ?? "?"} pass=${summary.pass ?? "?"} fail=${summary.fail ?? "?"}`,
  "",
  "## 失败项",
  "",
  failing.length ? failing.map((l) => `- ${l}`).join("\n") : "- 无",
  "",
  "## 声明边界",
  "",
  result.note,
  "",
].join("\n");
writeFileSync(join(runDir, "summary.md"), md);

console.log(`evidence: ${join(runDir, "summary.md")}`);
console.log(`status=${result.status} tests=${summary.tests} pass=${summary.pass} fail=${summary.fail}`);
process.exit(result.status === "PASS" ? 0 : 1);
