/**
 * CORE-110：工作区规范化和 Git 仓库/分支/worktree 检测。
 *
 * 不变量（WORK-001/002）：
 * - 工作区身份 = 节点 + 执行环境 + 规范化绝对路径。
 * - 相对路径、含 `..` 逃逸的路径一律拒绝；符号链接解析由调用方结合 realpath 校验。
 * - Git 不可用或目录不是仓库时降级返回 `isRepo:false, degraded:true`，绝不抛错。
 */
import { execFile as execFileCb } from "node:child_process";
import path from "node:path";
import { promisify } from "node:util";

import { createWorkspace, emptyRepoInfo, workspaceIdentity } from "./domain.mjs";
import { ValidationError } from "./errors.mjs";

const execFileAsync = promisify(execFileCb);

const WINDOWS_ABS_RE = /^[a-zA-Z]:[\\/]/;
const UNC_RE = /^\\\\[^\\]+\\[^\\]+/;

export function isWindowsPath(p) {
  return typeof p === "string" && (WINDOWS_ABS_RE.test(p) || UNC_RE.test(p));
}

/** 规范化工作区路径：统一分隔符、去尾部分隔符、折叠 `.`/`..`，拒绝相对路径。 */
export function normalizeWorkspacePath(input, { platform = process.platform } = {}) {
  if (typeof input !== "string" || input.length === 0) {
    throw new ValidationError("workspace path must be a non-empty string");
  }
  if (input.includes("\0")) throw new ValidationError("workspace path must not contain NUL");
  const trimmed = input.trim();
  if (trimmed.length === 0) throw new ValidationError("workspace path must not be blank");

  const windows = isWindowsPath(trimmed) || (platform === "win32" && !trimmed.startsWith("/"));
  if (windows) {
    if (!isWindowsPath(trimmed)) throw new ValidationError(`workspace path must be absolute: ${input}`);
    let out = path.win32.normalize(trimmed);
    // 统一盘符大小写，避免 C:\x 与 c:\x 被判成两个工作区。
    if (/^[a-zA-Z]:/.test(out)) out = out[0].toUpperCase() + out.slice(1);
    if (out.length > 3 && /[\\/]$/.test(out)) out = out.replace(/[\\/]+$/, "");
    return out;
  }

  if (!trimmed.startsWith("/")) {
    throw new ValidationError(`workspace path must be absolute: ${input}`);
  }
  let out = path.posix.normalize(trimmed);
  if (out.length > 1 && out.endsWith("/")) out = out.replace(/\/+$/, "");
  return out;
}

/** target 是否位于 root 内（含 root 本身）；跨平台按目标路径风格判断。 */
export function isPathInside(root, target, { platform = process.platform } = {}) {
  const win = isWindowsPath(root) || platform === "win32";
  const impl = win ? path.win32 : path.posix;
  const r = normalizeWorkspacePath(root, { platform: win ? "win32" : "linux" });
  const t = normalizeWorkspacePath(target, { platform: win ? "win32" : "linux" });
  const rel = impl.relative(r, t);
  if (rel === "") return true;
  if (rel.startsWith("..")) return false;
  return !impl.isAbsolute(rel);
}

/** 在授权工作区内解析相对路径；越界直接拒绝（符号链接需调用方 realpath 后再校验）。 */
export function resolveInsideWorkspace(workspace, relativePath) {
  if (!workspace || typeof workspace.path !== "string") throw new ValidationError("workspace is required");
  const win = isWindowsPath(workspace.path);
  const impl = win ? path.win32 : path.posix;
  const candidate = impl.resolve(workspace.path, relativePath ?? "");
  if (!isPathInside(workspace.path, candidate, { platform: win ? "win32" : "linux" })) {
    throw new ValidationError(`path escapes workspace: ${relativePath}`, { workspacePath: workspace.path });
  }
  return candidate;
}

/* --------------------------------------------------------------------- git */

async function runGit(exec, args, cwd, timeoutMs) {
  try {
    const { stdout } = await exec("git", args, {
      cwd,
      timeout: timeoutMs,
      windowsHide: true,
      maxBuffer: 1024 * 1024,
    });
    return { ok: true, stdout: String(stdout ?? "") };
  } catch (err) {
    return {
      ok: false,
      error: err?.code ?? err?.name ?? "GIT_FAILED",
      message: String(err?.message ?? err),
      stderr: String(err?.stderr ?? ""),
      stdout: String(err?.stdout ?? ""),
    };
  }
}

/** 解析 `git worktree list --porcelain`。 */
export function parseWorktreeList(text) {
  const entries = [];
  for (const block of String(text ?? "").split(/\n\s*\n/)) {
    const entry = { path: null, head: null, branch: null, bare: false, detached: false };
    for (const line of block.split("\n")) {
      if (line.startsWith("worktree ")) entry.path = line.slice("worktree ".length).trim();
      else if (line.startsWith("HEAD ")) entry.head = line.slice("HEAD ".length).trim();
      else if (line.startsWith("branch ")) entry.branch = line.slice("branch ".length).trim();
      else if (line.trim() === "bare") entry.bare = true;
      else if (line.trim() === "detached") entry.detached = true;
    }
    if (entry.path) entries.push(entry);
  }
  return entries;
}

/**
 * 检测 Git 仓库信息。任何失败都降级，不抛出。
 * @returns {Promise<object>} emptyRepoInfo 形状
 */
export async function inspectGit({ cwd, exec = execFileAsync, timeoutMs = 3_000 } = {}) {
  const repo = emptyRepoInfo();
  if (typeof cwd !== "string" || cwd.length === 0) {
    return { ...repo, degraded: true, error: "cwd is required" };
  }

  const top = await runGit(exec, ["rev-parse", "--show-toplevel"], cwd, timeoutMs);
  if (!top.ok) {
    const notRepo = /not a git repository/i.test(`${top.message} ${top.stderr}`);
    return { ...repo, degraded: !notRepo, error: notRepo ? "not-a-git-repository" : top.message };
  }
  repo.isRepo = true;
  repo.root = top.stdout.trim() || null;

  const [branch, head, config, worktrees] = await Promise.all([
    runGit(exec, ["rev-parse", "--abbrev-ref", "HEAD"], cwd, timeoutMs),
    runGit(exec, ["rev-parse", "HEAD"], cwd, timeoutMs),
    runGit(exec, ["config", "--get", "remote.origin.url"], cwd, timeoutMs),
    runGit(exec, ["worktree", "list", "--porcelain"], cwd, timeoutMs),
  ]);

  repo.branch = branch.ok ? branch.stdout.trim() : null;
  repo.head = head.ok ? head.stdout.trim() : null;
  repo.remote = config.ok ? config.stdout.trim() || null : null;

  if (worktrees.ok) {
    const list = parseWorktreeList(worktrees.stdout);
    const current = list.find((w) => w.path === repo.root);
    repo.worktree = list.length > 1 && !!current && list[0].path !== repo.root;
    repo.worktreePath = repo.worktree ? repo.root : null;
  }
  if (!repo.branch && !repo.head) {
    repo.degraded = true;
    repo.error = "git metadata unavailable";
  }
  return repo;
}

/* ----------------------------------------------------------------- service */

export function createWorkspaceService({ exec = execFileAsync, timeoutMs = 3_000 } = {}) {
  return {
    normalize: normalizeWorkspacePath,
    isInside: isPathInside,
    resolveInside: resolveInsideWorkspace,
    identity: workspaceIdentity,

    /** 只读探测：不创建任何文件，不改工作区。 */
    async inspect(workspacePath) {
      const normalizedPath = normalizeWorkspacePath(workspacePath);
      const repo = await inspectGit({ cwd: normalizedPath, exec, timeoutMs });
      return { path: normalizedPath, repo };
    },

    /**
     * 构建 Workspace 领域对象。
     * @param {{deviceId:string, environmentId:string, path:string, label?:string, id?:string}} input
     */
    async create({ deviceId, environmentId, path: workspacePath, label = null, id } = {}) {
      const { path: normalizedPath, repo } = await this.inspect(workspacePath);
      return createWorkspace({
        id,
        deviceId,
        environmentId,
        path: normalizedPath,
        displayPath: workspacePath,
        label,
        repo,
      });
    },
  };
}
