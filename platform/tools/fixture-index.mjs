#!/usr/bin/env node
/**
 * Agent fixture / 勘察证据索引（QA-103）。
 *
 * 扫描 `fixtures/adapters/probes/*.json`（CI 勘察）与 `fixtures/adapters/*.fixture.json`（真实/手写录制），
 * 生成 `docs/FIXTURE-INDEX.md` + `docs/fixture-index.json`；`--check` 用于 CI 防止漏更新。
 *
 * 校验规则（SPEC 14.3 / TODO 5.2）：
 *   - 录制必须 `redaction.applied === true`；
 *   - 录制必须带 agentId 与 taskId；
 *   - 手写 fixture 必须 `source === "handwritten"` 且 sourceNote 说明来源；
 *   - 引用路径必须真实存在（相对 platform/）。
 */
import { existsSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

import { PROBE_TASK_IDS } from "../adapters/probe-evidence.mjs";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const platformDir = join(here, "..");
const fixturesDir = join(platformDir, "fixtures", "adapters");
const probesDir = join(fixturesDir, "probes");

export class FixtureIndexError extends Error {
  constructor(issues) {
    super(`fixture index invalid: ${issues.join("; ")}`);
    this.name = "FixtureIndexError";
    this.issues = issues;
  }
}

export function buildFixtureIndex() {
  const issues = [];
  const entries = [];

  const probeFiles = existsSync(probesDir)
    ? readdirSync(probesDir).filter((f) => f.endsWith(".json") && f !== "Notes.md")
    : [];
  for (const file of probeFiles.sort()) {
    const path = join(probesDir, file);
    let data;
    try {
      data = JSON.parse(readFileSync(path, "utf8"));
    } catch (err) {
      issues.push(`${file}: invalid JSON (${err.message})`);
      continue;
    }
    entries.push({
      kind: "probe",
      agentId: data.agent?.id ?? file.replace(/\.json$/, ""),
      taskId: PROBE_TASK_IDS[data.agent?.id ?? file.replace(/\.json$/, "")] ?? null,
      version: data.packageVersion ?? null,
      path: `fixtures/adapters/probes/${file}`,
      generatedAt: data.generatedAt ?? null,
      probes: (data.probes ?? []).length,
      redactionApplied: true,
    });
  }

  const fixtureFiles = existsSync(fixturesDir)
    ? readdirSync(fixturesDir).filter((f) => f.endsWith(".fixture.json"))
    : [];
  for (const file of fixtureFiles.sort()) {
    const path = join(fixturesDir, file);
    let data;
    try {
      data = JSON.parse(readFileSync(path, "utf8"));
    } catch (err) {
      issues.push(`${file}: invalid JSON (${err.message})`);
      continue;
    }
    const isRecording = data.source === "recording";
    const isHandwritten = data.source === "handwritten";
    if (!isRecording && !isHandwritten) issues.push(`${file}: source must be recording|handwritten (got ${data.source})`);
    if (!data.agentId) issues.push(`${file}: missing agentId`);
    if (!data.taskId) issues.push(`${file}: missing taskId`);
    if (isRecording && data.redaction?.applied !== true) issues.push(`${file}: recording requires redaction.applied=true`);
    if (isHandwritten && !data.sourceNote) issues.push(`${file}: handwritten fixture requires sourceNote`);
    const frameCount = Array.isArray(data.frames) ? data.frames.length : Array.isArray(data.events) ? data.events.length : 0;
    if (frameCount === 0) issues.push(`${file}: no frames/events`);
    entries.push({
      kind: isRecording ? "recording" : "handwritten",
      agentId: data.agentId,
      taskId: data.taskId,
      version: data.agentVersion ?? null,
      path: `fixtures/adapters/${file}`,
      recordedAt: data.recordedAt ?? null,
      frames: frameCount,
      redactionApplied: data.redaction?.applied === true,
    });
  }

  // 引用完整性：索引里的路径必须存在
  for (const entry of entries) {
    if (!existsSync(join(platformDir, entry.path))) issues.push(`${entry.path}: missing file`);
  }

  if (issues.length) throw new FixtureIndexError(issues);
  entries.sort((a, b) => `${a.agentId}:${a.path}`.localeCompare(`${b.agentId}:${b.path}`));
  return {
    schemaVersion: 1,
    entries,
    counts: {
      probe: entries.filter((e) => e.kind === "probe").length,
      recording: entries.filter((e) => e.kind === "recording").length,
      handwritten: entries.filter((e) => e.kind === "handwritten").length,
    },
  };
}

export function indexToMarkdown(index) {
  const rows = index.entries
    .map((e) => `| ${e.agentId} | ${e.version ?? "-"} | ${e.kind} | ${e.taskId ?? "-"} | ${e.frames ?? e.probes ?? "-"} | ${e.path} |`)
    .join("\n");
  return [
    "# Agent 证据索引（自动生成，勿手改）",
    "",
    `> probes=${index.counts.probe} recordings=${index.counts.recording} handwritten=${index.counts.handwritten}。`,
    "> 规则：SPEC 14.3 脱敏；录制必须 redaction.applied=true；手写 fixture 必须说明来源。",
    "",
    "| Agent | 版本 | 类型 | 任务 | 帧/探测数 | 路径 |",
    "|---|---|---|---|---|---|",
    rows,
    "",
  ].join("\n");
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isMain) {
  let index;
  try {
    index = buildFixtureIndex();
  } catch (err) {
    console.error(err.message);
    process.exit(1);
  }
  const outJson = join(platformDir, "docs", "fixture-index.json");
  const outMd = join(platformDir, "docs", "FIXTURE-INDEX.md");
  const canonical = JSON.stringify(index);
  if (process.argv.includes("--check")) {
    const existing = JSON.parse(readFileSync(outJson, "utf8"));
    if (JSON.stringify(existing) !== canonical) {
      console.error("fixture index is out of date: run `node tools/fixture-index.mjs` and commit");
      process.exit(1);
    }
    console.log("fixture index is up to date");
    process.exit(0);
  }
  writeFileSync(outJson, JSON.stringify(index, null, 2) + "\n");
  writeFileSync(outMd, indexToMarkdown(index));
  console.log(`index: ${outJson}`);
  console.log(`counts: ${JSON.stringify(index.counts)}`);
}
