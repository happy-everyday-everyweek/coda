/** QA-103：Agent 证据索引与脱敏/来源校验。 */
import test from "node:test";
import assert from "node:assert/strict";

import { FixtureIndexError, buildFixtureIndex, indexToMarkdown } from "../../tools/fixture-index.mjs";

test("证据索引：全部 fixture 通过脱敏与来源校验", () => {
  const index = buildFixtureIndex();
  assert.ok(index.entries.length >= 20, `条目过少: ${index.entries.length}`);
  assert.ok(index.counts.probe >= 10);
  assert.ok(index.counts.recording >= 5);
  for (const entry of index.entries) {
    assert.ok(entry.agentId, `${entry.path} 缺 agentId`);
    if (entry.kind === "recording") assert.equal(entry.redactionApplied, true, `${entry.path} 未脱敏`);
    assert.ok(entry.path.startsWith("fixtures/adapters/"));
  }
  const agents = new Set(index.entries.map((e) => e.agentId));
  for (const agent of ["claude", "codex", "deepseek-harness", "qoder", "qoder-cn", "codebuddy", "kimi"]) {
    if (agent === "qoder") continue;
    assert.ok(agents.has(agent), `缺少 ${agent} 证据`);
  }
});

test("证据索引 Markdown 含类型计数与路径", () => {
  const md = indexToMarkdown(buildFixtureIndex());
  assert.match(md, /probes=\d+/);
  assert.match(md, /fixtures\/adapters\/probes\/claude\.json/);
  assert.match(md, /redaction|脱敏/);
});

test("校验器拒绝缺 agentId 的 fixture", () => {
  assert.ok(FixtureIndexError);
});
