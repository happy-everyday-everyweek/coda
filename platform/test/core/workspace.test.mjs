/** CORE-110：工作区路径规范化、包含关系、Git 探测与失败降级。 */
import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";

import {
  createWorkspaceService,
  inspectGit,
  isPathInside,
  normalizeWorkspacePath,
  parseWorktreeList,
  resolveInsideWorkspace,
} from "../../core/workspace.mjs";
import { workspaceIdentity } from "../../core/domain.mjs";
import { cleanupDir, makeTempDir } from "../../fixtures/core/helpers.mjs";

test("路径规范化：折叠 . / ..、去尾部分隔符、拒绝相对路径", () => {
  assert.equal(normalizeWorkspacePath("/tmp/a/../b/"), "/tmp/b");
  assert.equal(normalizeWorkspacePath("/tmp//a/./b"), "/tmp/a/b");
  assert.equal(normalizeWorkspacePath("/"), "/");
  assert.throws(() => normalizeWorkspacePath("relative/path"), /absolute/);
  assert.throws(() => normalizeWorkspacePath(""), /non-empty/);
  assert.throws(() => normalizeWorkspacePath("/tmp/a\0b"), /NUL/);

  assert.equal(normalizeWorkspacePath("C:\\Work\\Proj\\", { platform: "win32" }), "C:\\Work\\Proj");
  assert.equal(normalizeWorkspacePath("c:\\x", { platform: "win32" }), "C:\\x");
});

test("工作区包含关系拒绝前缀伪装与 .. 逃逸", () => {
  assert.equal(isPathInside("/tmp/ws", "/tmp/ws/a"), true);
  assert.equal(isPathInside("/tmp/ws", "/tmp/ws"), true);
  assert.equal(isPathInside("/tmp/ws", "/tmp/ws-evil/a"), false);
  assert.equal(isPathInside("/tmp/ws", "/tmp/ws/../other"), false);
  assert.equal(isPathInside("C:\\ws", "C:\\ws\\a", { platform: "win32" }), true);
  assert.equal(isPathInside("C:\\ws", "C:\\ws2\\a", { platform: "win32" }), false);
});

test("resolveInsideWorkspace 拒绝越界相对路径", () => {
  const workspace = { path: "/tmp/ws" };
  assert.equal(resolveInsideWorkspace(workspace, "src/a.js"), path.posix.join("/tmp/ws", "src/a.js"));
  assert.throws(() => resolveInsideWorkspace(workspace, "../etc/passwd"), /escapes workspace/);
});

test("worktree 列表解析", () => {
  const text = [
    "worktree /repo/main",
    "HEAD abc123",
    "branch refs/heads/main",
    "",
    "worktree /repo/wt",
    "HEAD def456",
    "branch refs/heads/feature",
    "",
  ].join("\n");
  const entries = parseWorktreeList(text);
  assert.equal(entries.length, 2);
  assert.equal(entries[0].path, "/repo/main");
  assert.equal(entries[1].branch, "refs/heads/feature");
});

test("非 Git 目录降级返回，不抛异常", async () => {
  const dir = makeTempDir("coda-ws-");
  try {
    const info = await inspectGit({ cwd: dir });
    assert.equal(info.isRepo, false);
    assert.equal(typeof info.degraded, "boolean");
    assert.equal(typeof info.error, "string");
  } finally {
    cleanupDir(dir);
  }
});

test("不存在的工作区目录也降级，不阻塞调用方", async () => {
  const dir = makeTempDir("coda-ws-");
  try {
    const info = await inspectGit({ cwd: path.join(dir, "missing-subdir") });
    assert.equal(info.isRepo, false);
    assert.equal(info.degraded, true);
  } finally {
    cleanupDir(dir);
  }
});

test("工作区身份 = 节点 + 环境 + 规范化路径", async () => {
  const dir = makeTempDir("coda-ws-");
  try {
    const service = createWorkspaceService();
    const workspace = await service.create({
      deviceId: "dev_ws",
      environmentId: "env_ws",
      path: `${dir}/`,
      label: "demo",
    });
    assert.equal(workspace.path, dir);
    assert.equal(workspace.deviceId, "dev_ws");
    assert.equal(workspace.environmentId, "env_ws");
    assert.equal(workspace.label, "demo");
    assert.equal(workspaceIdentity(workspace), `dev_ws::env_ws::${dir}`);
    assert.equal(service.identity(workspace), workspaceIdentity(workspace));
    assert.equal(service.resolveInside(workspace, "src/a.js"), path.posix.join(dir, "src/a.js"));
  } finally {
    cleanupDir(dir);
  }
});
