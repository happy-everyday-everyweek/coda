#!/usr/bin/env node
/**
 * 能力矩阵生成 CLI（QA-106 / AGT-103）。
 *
 * 输入：CI 勘察证据 `fixtures/adapters/probes/*.json` + 真实会话录制 `fixtures/adapters/*.fixture.json`
 * 输出：JSON 矩阵 + Markdown 摘要（写入 platform/docs/CAPABILITY-MATRIX-GENERATED.md）
 *
 * 规则（ADR-0005）：
 *   - 矩阵单元 = 实例 × 版本 × 环境；能力是运行期事实；
 *   - 没有真实会话录制/成功回合 → release 最高 experimental，不得出现 supported；
 *   - 无法安装/无入口的 Agent → blocked（带原因），不进入可用列表。
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { CAPABILITY_KEYS } from "../contracts/capabilities.mjs";
import { buildCapabilityMatrix, isInstanceUsable } from "../adapters/capability-matrix.mjs";
import { listProbeEvidence, listRecordings } from "../adapters/probe-evidence.mjs";
import {
  claudeStreamCapabilityDeclaration,
} from "../adapters/claude-stream-json.mjs";
import { codexAppServerCapabilityDeclaration } from "../adapters/codex-app-server.mjs";
import { deepseekHarnessCapabilityDeclaration } from "../adapters/deepseek-harness.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const platformDir = join(here, "..");

function allUnverified() {
  const value = (keys) => Object.fromEntries(keys.map((k) => [k, "unverified"]));
  return Object.fromEntries(CAPABILITY_KEYS.map((key) => [key, "unverified"]));
}

/** 各 agent 的能力声明：能复用的复用适配器声明，其余一律全 unverified。 */
const DECLARATIONS = {
  claude: () => claudeStreamCapabilityDeclaration({ protocolDetected: true }),
  codex: () => codexAppServerCapabilityDeclaration({ handshakeOk: true, sessionMethodsOk: true }),
  "deepseek-harness": () => deepseekHarnessCapabilityDeclaration({ protocolDetected: true, resumeDetected: true }),
};

const BLOCKED = {
  trae: "TRAE CLI 需要企业版旗舰套餐（259 元/席/月，3 席起）；无凭证不可用",
  workbuddy: "官方无本地 CLI/ACP/app-server；只有 WMA 云接口",
  kimi: null, // 可安装、ACP 可达，仅需登录 → experimental
};

export function buildEvidenceMatrix({ clock = () => new Date() } = {}) {
  const probes = listProbeEvidence();
  const recordings = listRecordings();
  const recordedAgents = new Set(recordings.map((r) => r.agentId));
  const instances = [];
  for (const probe of probes) {
    const blockedReason = BLOCKED[probe.id] ?? null;
    const declaration = (DECLARATIONS[probe.id] ?? (() => allUnverified()))();
    instances.push({
      instanceId: `ai_${probe.id.replace(/[^a-z0-9]/gi, "_")}`,
      provider: probe.id,
      version: probe.packageVersion ?? "unknown",
      environment: "host",
      capabilities: declaration,
      release: blockedReason ? "blocked" : "experimental",
      blockedReason,
      evidence: [
        { kind: "protocol-trace", ref: `fixtures/adapters/probes/${probe.id}.json`, note: `${probe.totalProbes} probes` },
        ...(recordedAgents.has(probe.id)
          ? [{ kind: "recording", ref: `fixtures/adapters/${probe.id}*.fixture.json`, note: "真实帧（成功回合待录）" }]
          : []),
      ],
      notes: recordedAgents.has(probe.id) ? "有真实帧录制；成功模型回合仍需账号" : "仅有安装/入口证据，无会话录制",
    });
  }
  // 本机真实录制但不在 CI 勘察里的 agent（例如 DeepSeek Harness）：补成 experimental 实例
  for (const recording of recordings) {
    const provider = recording.agentId;
    if (!provider || instances.some((i) => i.provider === provider)) continue;
    const declaration = (DECLARATIONS[provider] ?? (() => allUnverified()))();
    instances.push({
      instanceId: `ai_${provider.replace(/[^a-z0-9]/gi, "_")}`,
      provider,
      version: recording.agentVersion ?? "unknown",
      environment: "host",
      capabilities: declaration,
      release: "experimental",
      blockedReason: null,
      evidence: [{ kind: "recording", ref: `fixtures/adapters/${provider}*.fixture.json`, note: `${recording.events} frames` }],
      notes: "本机真实录制（成功路径仍需账号）",
    });
  }

  // WorkBuddy 没有任何 CLI 证据，也列入 blocked 以显式暴露缺口
  if (!instances.some((i) => i.provider === "workbuddy")) {
    instances.push({
      instanceId: "ai_workbuddy",
      provider: "workbuddy",
      version: "unknown",
      environment: "host",
      capabilities: allUnverified(),
      release: "blocked",
      blockedReason: BLOCKED.workbuddy,
      evidence: [{ kind: "manual", ref: "platform/docs/DOMESTIC-AGENTS.md", note: "官方文档树无 CLI 章节" }],
      notes: "BLOCKED：不采用非官方网关照生产",
    });
  }
  const matrix = buildCapabilityMatrix(instances);
  const usable = matrix.instances.filter((row) => isInstanceUsable(matrix, row.instanceId).usable);
  return { matrix, usable, generatedAt: clock().toISOString?.() ?? String(clock()) };
}

export function matrixToMarkdown({ matrix, usable }) {
  const rows = matrix.instances
    .map((row) => {
      const state = isInstanceUsable(matrix, row.instanceId).usable ? "usable(experimental)" : "blocked/unavailable";
      return `| ${row.instanceId} | ${row.provider} | ${row.version} | ${row.release} | ${state} | ${row.blockedReason ?? "-"} |`;
    })
    .join("\n");
  return [
    "# 能力矩阵（自动生成，勿手改）",
    "",
    `> 生成时间：${matrix.generatedAt}；实例 ${matrix.instances.length} 个，可用（experimental）${usable.length} 个。`,
    "> 规则见 `platform/docs/adr/ADR-0005-agent-capability-matrix.md`；本文件不替代发布矩阵。",
    "> **任何行都不得当作 supported**：真实成功回合录制缺失，release 最高 experimental。",
    "",
    "| 实例 | 提供方 | 版本 | release | 可用性 | 阻塞原因 |",
    "|---|---|---|---|---|---|",
    rows,
    "",
  ].join("\n");
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
function canonical(matrix) {
  const { generatedAt, ...rest } = matrix;
  return JSON.stringify(rest);
}

if (isMain) {
  const checkMode = process.argv.includes("--check");
  const result = buildEvidenceMatrix();
  if (checkMode) {
    const existing = JSON.parse(readFileSync(join(platformDir, "docs", "capability-matrix.json"), "utf8"));
    if (canonical(existing) !== canonical(result.matrix)) {
      console.error("capability matrix is out of date: run `node tools/capability-matrix.mjs` and commit");
      process.exit(1);
    }
    console.log("capability matrix is up to date");
    process.exit(0);
  }
  const outJson = join(platformDir, "docs", "capability-matrix.json");
  const outMd = join(platformDir, "docs", "CAPABILITY-MATRIX-GENERATED.md");
  writeFileSync(outJson, JSON.stringify(result.matrix, null, 2) + "\n");
  writeFileSync(outMd, matrixToMarkdown(result));
  console.log(`matrix: ${outJson}`);
  console.log(`summary: ${outMd}`);
  console.log(`instances=${result.matrix.instances.length} usable=${result.usable.length}`);
}
