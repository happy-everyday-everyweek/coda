/** 能力矩阵生成（QA-106/AGT-103）：只用证据，不出现 supported，阻塞项显式。 */
import test from "node:test";
import assert from "node:assert/strict";

import { buildEvidenceMatrix, matrixToMarkdown } from "../../tools/capability-matrix.mjs";

test("能力矩阵：从勘察证据生成，全部最高 experimental，阻塞项显式", () => {
  const result = buildEvidenceMatrix({ clock: () => new Date("2026-11-07T00:00:00.000Z") });
  const instances = result.matrix.instances;
  assert.ok(instances.length >= 10, `实例过少: ${instances.length}`);
  for (const row of instances) {
    assert.ok(["experimental", "blocked", "unavailable"].includes(row.release), `${row.instanceId} release=${row.release}`);
    assert.notEqual(row.release, "supported");
  }
  const providers = new Set(instances.map((i) => i.provider));
  assert.ok(providers.has("claude"));
  assert.ok(providers.has("codex"));
  assert.ok(providers.has("deepseek-harness"));
  const trae = instances.find((i) => i.provider === "trae");
  assert.equal(trae.release, "blocked");
  assert.match(trae.blockedReason, /企业版|旗舰/);
  const workbuddy = instances.find((i) => i.provider === "workbuddy");
  assert.equal(workbuddy.release, "blocked");
  assert.ok(result.usable.length >= 1);
  assert.ok(result.usable.every((row) => row.release === "experimental"));
});

test("能力矩阵 Markdown：带警示与阻塞原因列", () => {
  const result = buildEvidenceMatrix({ clock: () => new Date("2026-11-07T00:00:00.000Z") });
  const md = matrixToMarkdown(result);
  assert.match(md, /不得当作 supported/);
  assert.match(md, /\| ai_claude \|/);
  assert.match(md, /企业版|旗舰/);
  assert.match(md, /blocked/);
});
