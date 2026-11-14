/**
 * 协议勘察证据（AGT-111..131 的版本固定与入口验证）。
 *
 * 证据来源：公开仓库 `happy-everyday-everyweek/coda-agent-probes` 的 CI 运行，
 * 由 `fixtures/adapters/probes/*.json` 保存；`fixtures/adapters/*.fixture.json`
 * 中的 `source: "recording"` 才是真实会话录制。
 *
 * 本模块只做只读加载与校验：不联网、不安装、不执行上游二进制。
 */
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
export const PROBES_DIR = join(here, "..", "fixtures", "adapters", "probes");
export const FIXTURES_DIR = join(here, "..", "fixtures", "adapters");

/** agent id → TODO 任务 ID（不在 TODO 名单里的 agent 记 null）。 */
export const PROBE_TASK_IDS = Object.freeze({
  claude: "AGT-111",
  codex: "AGT-112",
  gemini: "AGT-122",
  opencode: "AGT-121",
  copilot: "AGT-123",
  qwen: null,
  auggie: null,
  crush: null,
});

export const KNOWN_FLAGS = Object.freeze([
  "--json", "--print", "--output-format", "stream-json", "--input-format",
  "app-server", "--acp", "acp", "serve", "mcp", "--session-id", "--resume",
  "--permission-mode", "--model", "--approval-policy", "sessions",
]);

export class ProbeEvidenceError extends Error {
  constructor(message, details = {}) {
    super(message);
    this.name = "ProbeEvidenceError";
    this.details = details;
  }
}

export function validateProbeEvidence(evidence) {
  const issues = [];
  if (!evidence || typeof evidence !== "object") return ["evidence must be an object"];
  if (evidence.schemaVersion !== 1) issues.push("unsupported schemaVersion");
  if (!evidence.agent?.id) issues.push("missing agent.id");
  if (!Array.isArray(evidence.probes) || evidence.probes.length === 0) issues.push("missing probes");
  for (const probe of evidence.probes ?? []) {
    if (typeof probe.cmd !== "string") issues.push("probe.cmd must be string");
    if (!Number.isInteger(probe.exitCode) && probe.exitCode !== null) issues.push("probe.exitCode must be integer or null");
  }
  return issues;
}

function loadJson(path) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (err) {
    throw new ProbeEvidenceError(`failed to read ${path}: ${err.message}`, { path });
  }
}

/** 读取单个 agent 的 CI 勘察证据。 */
export function loadProbeEvidence(agentId) {
  const path = join(PROBES_DIR, `${agentId}.json`);
  const evidence = loadJson(path);
  const issues = validateProbeEvidence(evidence);
  if (issues.length) throw new ProbeEvidenceError(`invalid probe evidence for ${agentId}`, { issues });
  return evidence;
}

/** 列出全部勘察证据，并抽取版本与已验证入口。 */
export function listProbeEvidence() {
  let files = [];
  try {
    files = readdirSync(PROBES_DIR).filter((f) => f.endsWith(".json"));
  } catch {
    return [];
  }
  return files
    .map((file) => {
      const evidence = loadJson(join(PROBES_DIR, file));
      const id = evidence.agent?.id ?? file.replace(/\.json$/, "");
      const text = (evidence.probes ?? []).map((p) => `${p.stdout ?? ""}\n${p.stderr ?? ""}`).join("\n");
      const flags = KNOWN_FLAGS.filter((flag) => text.includes(flag));
      const okProbes = (evidence.probes ?? []).filter((p) => p.exitCode === 0 && !p.timedOut).length;
      return {
        id,
        taskId: PROBE_TASK_IDS[id] ?? null,
        package: evidence.agent?.package ?? null,
        packageVersion: evidence.packageVersion ?? null,
        generatedAt: evidence.generatedAt ?? null,
        okProbes,
        totalProbes: (evidence.probes ?? []).length,
        flags,
      };
    })
    .sort((a, b) => a.id.localeCompare(b.id));
}

/** 列出真实会话录制（只认 source: "recording"）。 */
export function listRecordings() {
  let files = [];
  try {
    files = readdirSync(FIXTURES_DIR).filter((f) => f.endsWith(".fixture.json"));
  } catch {
    return [];
  }
  const out = [];
  for (const file of files) {
    const fixture = loadJson(join(FIXTURES_DIR, file));
    if (fixture.source !== "recording") continue;
    out.push({
      file: join(FIXTURES_DIR, file),
      agentId: fixture.agentId ?? null,
      taskId: fixture.taskId ?? null,
      recordedAt: fixture.recordedAt ?? null,
      agentVersion: fixture.agentVersion ?? null,
      events: Array.isArray(fixture.events) ? fixture.events.length : 0,
    });
  }
  return out;
}

/**
 * 能力矩阵用的证据条目：把 CI 勘察结果翻译成 evidence kind=probe。
 * 只证明「某版本暴露了某入口」，不证明能力可用。
 */
export function probeEvidenceForMatrix(agentId) {
  const evidence = loadProbeEvidence(agentId);
  const text = (evidence.probes ?? []).map((p) => `${p.stdout ?? ""}\n${p.stderr ?? ""}`).join("\n");
  return {
    kind: "probe",
    ref: `fixtures/adapters/probes/${agentId}.json`,
    note: `version=${evidence.packageVersion ?? "unknown"}; probes=${(evidence.probes ?? []).length}`,
    flags: KNOWN_FLAGS.filter((flag) => text.includes(flag)),
  };
}
