/** AGT-110：附件与成果归属、执行环境校验、Diff 来源、大内容引用化。 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  artifactRef,
  assertSameEnvironment,
  attachmentPlan,
  attributeArtifact,
  classifyAttachment,
  diffAttribution,
  normalizeEnvironment,
  summarizeAttachments,
} from "../../adapters/attachments.mjs";

test("执行环境标识可校验，非法值直接拒绝", () => {
  assert.equal(normalizeEnvironment("host"), "host");
  assert.equal(normalizeEnvironment("wsl:Ubuntu-24.04"), "wsl:Ubuntu-24.04");
  assert.equal(normalizeEnvironment("remote:node-1"), "remote:node-1");
  assert.throws(() => normalizeEnvironment("C:\\Users\\alice"), (err) => err.code === "WORKSPACE_INVALID");
  assert.throws(() => normalizeEnvironment(null), (err) => err.code === "WORKSPACE_INVALID");
});

test("成果归属：跨执行环境一律拒绝，不按同名路径推断", () => {
  assert.throws(
    () =>
      attributeArtifact({
        sessionId: "ses_art",
        sessionEnvironment: "host",
        artifactEnvironment: "wsl:Ubuntu",
        name: "report.md",
      }),
    (err) => err.code === "WORKSPACE_INVALID" && /cross-environment/.test(err.message),
  );

  const owned = attributeArtifact({
    sessionId: "ses_art",
    sessionEnvironment: "wsl:Ubuntu",
    sessionWorkspace: "/home/[REDACTED_USER]/proj",
    artifactEnvironment: "wsl:Ubuntu",
    artifactWorkspace: "/home/[REDACTED_USER]/proj",
    name: "report.md",
    kind: "text",
    size: 12,
    sha256: "a".repeat(64),
    source: "upstream",
  });
  assert.match(owned.artifactId, /^art_/);
  assert.equal(owned.environment, "wsl:Ubuntu");
  assert.equal(owned.source, "upstream");
  assert.equal(owned.ownedBySession, true);
});

test("同一会话内工作区不一致也拒绝", () => {
  assert.throws(
    () =>
      attributeArtifact({
        sessionId: "ses_art",
        sessionEnvironment: "host",
        sessionWorkspace: "/a",
        artifactEnvironment: "host",
        artifactWorkspace: "/b",
      }),
    (err) => err.code === "WORKSPACE_INVALID",
  );
});

test("附件计划：上游声明才走 native，否则降级为文本引用并声明“路径不是授权”", () => {
  const image = { name: "shot.png", mimeType: "image/png", size: 10 };
  const nativePlan = attachmentPlan({
    attachment: image,
    capabilities: { attachments: { input: "experimental", output: false } },
  });
  assert.equal(nativePlan.mode, "native");
  assert.equal(nativePlan.kind, "image");

  const degraded = attachmentPlan({
    attachment: { name: "notes.txt", mimeType: "text/plain", size: 10 },
    capabilities: { attachments: { input: false, output: false } },
  });
  assert.equal(degraded.mode, "text-reference");
  assert.match(degraded.note, /not an access grant/);

  const oversized = attachmentPlan({
    attachment: { name: "huge.bin", mimeType: "application/octet-stream", size: 30 * 1024 * 1024 },
    capabilities: { attachments: { input: true } },
  });
  assert.equal(oversized.mode, "unsupported");
  assert.match(oversized.reason, /exceeds native limit/);
});

test("附件分类只看元数据，不读文件", () => {
  assert.equal(classifyAttachment({ name: "a.png" }), "image");
  assert.equal(classifyAttachment({ name: "a.md" }), "text");
  assert.equal(classifyAttachment({ name: "a", mimeType: "text/markdown" }), "text");
  assert.equal(classifyAttachment({ name: "a.bin" }), "binary");
});

test("Diff 归属：必须有工作区路径，跨环境拒绝，超预算标 truncated", () => {
  assert.throws(
    () => diffAttribution({ sessionId: "ses_diff", sessionEnvironment: "host" }),
    (err) => err.code === "WORKSPACE_INVALID" && /owning workspace/.test(err.message),
  );
  assert.throws(
    () =>
      diffAttribution({
        sessionId: "ses_diff",
        sessionEnvironment: "host",
        workspacePath: "/p",
        diffEnvironment: "wsl:Ubuntu",
      }),
    (err) => err.code === "WORKSPACE_INVALID",
  );

  const diff = diffAttribution({
    sessionId: "ses_diff",
    sessionEnvironment: "host",
    workspacePath: "/home/[REDACTED_USER]/proj",
    source: "git",
    files: ["src/a.mjs"],
    bytes: 1024,
  });
  assert.equal(diff.computedIn, "host");
  assert.equal(diff.truncated, false);

  const big = diffAttribution({
    sessionId: "ses_diff",
    sessionEnvironment: "host",
    workspacePath: "/p",
    bytes: 900 * 1024,
  });
  assert.equal(big.truncated, true);
  assert.match(big.note, /artifact/);
});

test("大内容只保留引用，不进事件日志", () => {
  const small = artifactRef({ name: "a.txt", content: "abc" });
  assert.equal(small.contentOmitted, false);
  assert.equal(small.content, "abc");

  const big = artifactRef({ name: "b.txt", content: "x".repeat(300 * 1024) });
  assert.equal(big.contentOmitted, true);
  assert.equal(big.content, null);
  assert.ok(big.size > 0);
});

test("附件摘要不含正文；assertSameEnvironment 可复用", () => {
  const summary = summarizeAttachments([{ name: "a.png", mimeType: "image/png", size: 3, sha256: "b".repeat(64) }]);
  assert.deepEqual(summary, [{ name: "a.png", mimeType: "image/png", size: 3, kind: "image", sha256: "b".repeat(64) }]);
  assert.equal(assertSameEnvironment("host", "host"), true);
  assert.throws(() => assertSameEnvironment("host", "wsl:Ubuntu"), (err) => err.code === "WORKSPACE_INVALID");
});
