/** SEC-102 / SEC-002：路径规范化、符号链接逃逸、路径穿越、远程授权与写冲突。 */
import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, symlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import {
  checkRemoteFileRequest,
  isWithinWorkspace,
  normalizeWorkspacePath,
  resolveWithinRoot,
} from "../../security/paths.mjs";
import { makeTmpDir } from "../sync/helpers.mjs";

function sha256File(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

test("路径规范化：绝对化、拒绝空/控制字符、保留字面量", (t) => {
  const dir = makeTmpDir(t);
  const workspace = join(dir, "ws");
  mkdirSync(workspace, { recursive: true });
  writeFileSync(join(workspace, "a.txt"), "hello");

  assert.equal(normalizeWorkspacePath("a.txt", { base: workspace }), join(workspace, "a.txt"));
  assert.equal(normalizeWorkspacePath(join(workspace, "sub", "..", "a.txt")), join(workspace, "a.txt"));
  assert.throws(() => normalizeWorkspacePath(""), /must not be empty/);
  assert.throws(() => normalizeWorkspacePath("bad\u0000name"), /control characters/);
  assert.throws(() => normalizeWorkspacePath(42), /must be a string/);
});

test("工作区包含：符号链接解析后仍在工作区内", (t) => {
  const dir = makeTmpDir(t);
  const workspace = join(dir, "ws");
  const outsideDir = join(dir, "outside");
  mkdirSync(workspace, { recursive: true });
  mkdirSync(outsideDir, { recursive: true });
  writeFileSync(join(workspace, "a.txt"), "hello");
  writeFileSync(join(outsideDir, "secret.txt"), "secret");

  assert.equal(isWithinWorkspace(workspace, join(workspace, "a.txt")), true);
  assert.equal(isWithinWorkspace(workspace, join(outsideDir, "secret.txt")), false);

  symlinkSync(join(workspace, "a.txt"), join(workspace, "inside-link.txt"));
  symlinkSync(outsideDir, join(workspace, "escape-dir"));
  assert.equal(resolveWithinRoot(workspace, join(workspace, "inside-link.txt")).ok, true);
  const escaped = resolveWithinRoot(workspace, join(workspace, "escape-dir", "secret.txt"));
  assert.equal(escaped.ok, false);
  assert.equal(escaped.code, "SYMLINK_ESCAPE");
});

test("远程文件请求：提示词路径不授权；穿越/逃逸/外部路径全部拒绝", (t) => {
  const dir = makeTmpDir(t);
  const workspace = join(dir, "ws");
  const outsideDir = join(dir, "outside");
  mkdirSync(workspace, { recursive: true });
  mkdirSync(outsideDir, { recursive: true });
  writeFileSync(join(workspace, "a.txt"), "hello");
  writeFileSync(join(outsideDir, "secret.txt"), "secret");
  symlinkSync(outsideDir, join(workspace, "escape-dir"));

  const grants = [{ path: workspace, operations: ["read", "write"] }];

  const noGrant = checkRemoteFileRequest({ workspaceRoot: workspace, requestPath: "a.txt", operation: "read" });
  assert.equal(noGrant.ok, false);
  assert.equal(noGrant.code, "NOT_AUTHORIZED");
  assert.match(noGrant.reason, /not authorization/);

  const allowed = checkRemoteFileRequest({ workspaceRoot: workspace, requestPath: "a.txt", operation: "read", grants });
  assert.equal(allowed.ok, true);
  assert.equal(allowed.promptPathIsNotAuthorization, true);

  const traversal = checkRemoteFileRequest({
    workspaceRoot: workspace,
    requestPath: "../outside/secret.txt",
    operation: "read",
    grants,
  });
  assert.equal(traversal.ok, false);
  assert.equal(traversal.code, "PATH_TRAVERSAL");

  const encoded = checkRemoteFileRequest({
    workspaceRoot: workspace,
    requestPath: "%2e%2e/outside/secret.txt",
    operation: "read",
    grants,
  });
  assert.equal(encoded.ok, false);
  assert.equal(encoded.code, "PATH_TRAVERSAL");

  const absoluteOutside = checkRemoteFileRequest({
    workspaceRoot: workspace,
    requestPath: join(outsideDir, "secret.txt"),
    operation: "read",
    grants,
  });
  assert.equal(absoluteOutside.ok, false);
  assert.equal(absoluteOutside.code, "OUTSIDE_WORKSPACE");

  const symlinkEscape = checkRemoteFileRequest({
    workspaceRoot: workspace,
    requestPath: "escape-dir/secret.txt",
    operation: "read",
    grants,
  });
  assert.equal(symlinkEscape.ok, false);
  assert.equal(symlinkEscape.code, "SYMLINK_ESCAPE");

  const outsideGrant = checkRemoteFileRequest({
    workspaceRoot: workspace,
    requestPath: "a.txt",
    operation: "read",
    grants: [{ path: outsideDir, operations: ["read"] }],
  });
  assert.equal(outsideGrant.ok, false);
  assert.equal(outsideGrant.code, "NOT_AUTHORIZED");

  const missing = checkRemoteFileRequest({
    workspaceRoot: workspace,
    requestPath: "missing.txt",
    operation: "read",
    grants,
  });
  assert.equal(missing.code, "NOT_FOUND");

  const badOp = checkRemoteFileRequest({
    workspaceRoot: workspace,
    requestPath: "a.txt",
    operation: "delete",
    grants,
  });
  assert.equal(badOp.code, "INVALID_PATH");
});

test("远程写：基线哈希/mtime 不一致时返回写冲突", (t) => {
  const dir = makeTmpDir(t);
  const workspace = join(dir, "ws");
  mkdirSync(workspace, { recursive: true });
  const target = join(workspace, "a.txt");
  writeFileSync(target, "version-1");
  const grants = [{ path: workspace, operations: ["read", "write"] }];
  const currentHash = sha256File(target);

  const wrongHash = checkRemoteFileRequest({
    workspaceRoot: workspace,
    requestPath: "a.txt",
    operation: "write",
    grants,
    expectedSha256: "0".repeat(64),
  });
  assert.equal(wrongHash.code, "WRITE_CONFLICT");

  const mtimeConflict = checkRemoteFileRequest({
    workspaceRoot: workspace,
    requestPath: "a.txt",
    operation: "write",
    grants,
    expectedMtimeMs: 1,
  });
  assert.equal(mtimeConflict.code, "WRITE_CONFLICT");

  const ok = checkRemoteFileRequest({
    workspaceRoot: workspace,
    requestPath: "a.txt",
    operation: "write",
    grants,
    expectedSha256: currentHash,
  });
  assert.equal(ok.ok, true);

  const createNew = checkRemoteFileRequest({
    workspaceRoot: workspace,
    requestPath: "new.txt",
    operation: "write",
    grants,
  });
  assert.equal(createNew.ok, true);
});
