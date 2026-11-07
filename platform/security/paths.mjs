/**
 * 工作区路径与远程文件授权（SEC-102 / SEC-002 / WORK-002）。
 *
 * 规则（SPEC 14.3）：
 * - 文件访问必须校验工作区包含关系、符号链接和授权范围。
 * - 提示词里写路径 ≠ 授权读取：必须有显式 grant。
 * - 远程写请求带基线哈希/mtime 时校验写冲突。
 *
 * 本模块只做路径判定与只读元数据检查，不写文件。
 */
import {
  existsSync,
  lstatSync,
  readFileSync,
  realpathSync,
  statSync,
} from "node:fs";
import { createHash } from "node:crypto";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";

export class PathSecurityError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "PathSecurityError";
    this.code = code;
    this.details = details;
  }
}

export const PATH_ERRORS = {
  INVALID_PATH: "INVALID_PATH",
  PATH_TRAVERSAL: "PATH_TRAVERSAL",
  OUTSIDE_WORKSPACE: "OUTSIDE_WORKSPACE",
  SYMLINK_ESCAPE: "SYMLINK_ESCAPE",
  SYMLINK_LOOP: "SYMLINK_LOOP",
  NOT_AUTHORIZED: "NOT_AUTHORIZED",
  WRITE_CONFLICT: "WRITE_CONFLICT",
  NOT_FOUND: "NOT_FOUND",
};

const MAX_PATH_BYTES = 4096;

function fail(code, detailPath, message) {
  return { ok: false, code, path: detailPath ?? null, reason: message ?? code };
}

/** 去掉尾部分隔符（保留根），统一绝对路径。 */
function trimTrailingSep(p) {
  if (p.length > 1 && (p.endsWith(sep) || p.endsWith("/"))) {
    const trimmed = p.replace(/[\\/]+$/, "");
    return trimmed.length === 0 ? p : trimmed;
  }
  return p;
}

/**
 * 契约入口：规范化工作区路径。
 * - 相对路径按 cwd（或传入 base）解析为绝对路径。
 * - 拒绝空字节、控制字符、超长路径。
 * - 不做符号链接解析（解析由 isWithinWorkspace/checkRemoteFileRequest 负责）。
 */
export function normalizeWorkspacePath(input, { base = process.cwd() } = {}) {
  if (typeof input !== "string") {
    throw new PathSecurityError(PATH_ERRORS.INVALID_PATH, "path must be a string");
  }
  if (input.length === 0) throw new PathSecurityError(PATH_ERRORS.INVALID_PATH, "path must not be empty");
  if (Buffer.byteLength(input) > MAX_PATH_BYTES) {
    throw new PathSecurityError(PATH_ERRORS.INVALID_PATH, `path exceeds ${MAX_PATH_BYTES} bytes`);
  }
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f]/.test(input)) {
    throw new PathSecurityError(PATH_ERRORS.INVALID_PATH, "path contains control characters");
  }
  if (!isAbsolute(base)) throw new PathSecurityError(PATH_ERRORS.INVALID_PATH, "base must be absolute");
  return trimTrailingSep(resolve(base, input));
}

/** Windows/POSIX 通用的「a 是否在 root 内」判断（root 自身算在内）。 */
export function isPathInside(root, candidate) {
  if (typeof root !== "string" || typeof candidate !== "string") return false;
  const rel = relative(root, candidate);
  if (rel === "") return true;
  if (isAbsolute(rel)) return false;
  return rel !== ".." && !rel.startsWith(`..${sep}`) && !rel.startsWith("../");
}

function realpathOrNull(p) {
  try {
    return realpathSync.native ? realpathSync.native(p) : realpathSync(p);
  } catch {
    return null;
  }
}

/** 解析「最近的已存在祖先」的真实路径，再拼回不存在的尾部。 */
export function realpathOfNearestExisting(p) {
  let cur = p;
  const tail = [];
  while (!existsSync(cur)) {
    const parent = dirname(cur);
    if (parent === cur) return null;
    tail.unshift(cur.slice(parent.length).replace(/^[\\/]+/, ""));
    cur = parent;
  }
  const real = realpathOrNull(cur);
  if (!real) return null;
  return tail.length ? join(real, ...tail) : real;
}

/**
 * 逐段解析目标路径，任何符号链接都必须仍落在真实根目录内。
 * 不存在的尾部保留字面量（写入新文件时常见）。
 */
export function resolveWithinRoot(workspaceRoot, candidate) {
  const rootRaw = trimTrailingSep(resolve(workspaceRoot));
  const rootReal = realpathOrNull(rootRaw);
  if (!rootReal) return fail(PATH_ERRORS.NOT_FOUND, workspaceRoot, "workspace root does not exist");
  const absRaw = trimTrailingSep(isAbsolute(candidate) ? resolve(candidate) : resolve(rootReal, candidate));
  // macOS 的 /tmp → /private/tmp 等系统符号链接：先做真实路径规范化再比较包含关系，
  // 否则「字面在根内、真实也在根内」的路径会被误判为越界。
  const abs = realpathOfNearestExisting(absRaw) ?? absRaw;
  if (!isPathInside(rootReal, abs)) {
    const literalInside = isPathInside(rootRaw, absRaw) || isPathInside(rootReal, absRaw);
    return fail(
      literalInside ? PATH_ERRORS.SYMLINK_ESCAPE : PATH_ERRORS.OUTSIDE_WORKSPACE,
      absRaw,
      literalInside ? "symlink resolves outside workspace" : "path escapes workspace",
    );
  }
  const rel = relative(rootReal, abs);
  let cur = rootReal;
  for (const part of rel.split(sep).filter(Boolean)) {
    cur = join(cur, part);
    let st;
    try {
      st = lstatSync(cur);
    } catch (err) {
      if (err.code === "ENOENT") return { ok: true, path: abs, realPath: cur };
      throw new PathSecurityError(PATH_ERRORS.INVALID_PATH, `cannot stat ${cur}: ${err.message}`);
    }
    if (st.isSymbolicLink()) {
      const target = realpathOrNull(cur);
      if (!target) return fail(PATH_ERRORS.SYMLINK_LOOP, abs, "symlink loop or dangling symlink");
      if (!isPathInside(rootReal, target)) {
        return fail(PATH_ERRORS.SYMLINK_ESCAPE, abs, "symlink points outside workspace");
      }
      cur = target;
    }
  }
  if (!isPathInside(rootReal, cur)) {
    return fail(PATH_ERRORS.OUTSIDE_WORKSPACE, abs, "resolved path escapes workspace");
  }
  return { ok: true, path: abs, realPath: cur };
}

/** 布尔版包含关系检查（解析符号链接）。 */
export function isWithinWorkspace(workspaceRoot, candidate) {
  return resolveWithinRoot(workspaceRoot, candidate).ok === true;
}

/** 抛错版：供内部调用。 */
export function assertWithinWorkspace(workspaceRoot, candidate) {
  const result = resolveWithinRoot(workspaceRoot, candidate);
  if (!result.ok) throw new PathSecurityError(result.code, result.reason, { path: result.path });
  return result;
}

function decodeSuspicious(raw) {
  // 拒绝百分号编码的路径分隔符/点点：远程请求不得用编码绕过规范化。
  const encoded = /%(?:2e|2f|5c)/i.test(raw);
  if (encoded) return { traversal: true };
  let decoded = raw;
  try {
    decoded = decodeURIComponent(raw);
  } catch {
    return { traversal: true };
  }
  const doubled = /%(?:2e|2f|5c)/i.test(decoded);
  return { traversal: doubled, decoded };
}

function hasDotDotSegment(p) {
  return p.split(/[\\/]+/).some((part) => part === ".." || part === ".");
}

function grantCovers(grant, absPath, operation) {
  if (!grant || typeof grant !== "object") return false;
  const ops = grant.operations ?? grant.ops ?? ["read"];
  if (!Array.isArray(ops) || !ops.includes(operation)) return false;
  if (typeof grant.path !== "string" || grant.path.length === 0) return false;
  const grantAbs = isAbsolute(grant.path) ? resolve(grant.path) : null;
  if (!grantAbs) return false;
  return isPathInside(grantAbs, absPath);
}

function fileSha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

/**
 * 远程文件请求授权检查。
 *
 * @param {object} req
 * @param {string} req.workspaceRoot 已存在的真实工作区根
 * @param {string} req.requestPath 客户端给出的路径（相对或绝对）
 * @param {"read"|"list"|"write"} [req.operation]
 * @param {Array<{path:string, operations:string[]}>} [req.grants] 显式文件授权
 * @param {string} [req.expectedSha256] 写请求基线哈希
 * @param {number} [req.expectedMtimeMs] 写请求基线 mtime
 * @param {boolean} [req.allowSymlinks] 仅本机可信调用方可开启
 * @param {boolean} [req.mustExist]
 */
export function checkRemoteFileRequest(req = {}) {
  const {
    workspaceRoot,
    requestPath,
    operation = "read",
    grants = [],
    expectedSha256 = null,
    expectedMtimeMs = null,
    allowSymlinks = false,
    mustExist = operation !== "write",
  } = req;
  if (!workspaceRoot) return fail(PATH_ERRORS.INVALID_PATH, null, "workspaceRoot required");
  if (!["read", "list", "write"].includes(operation)) {
    return fail(PATH_ERRORS.INVALID_PATH, requestPath, `unsupported operation: ${operation}`);
  }
  if (typeof requestPath !== "string" || requestPath.length === 0) {
    return fail(PATH_ERRORS.INVALID_PATH, null, "requestPath required");
  }
  const suspicious = decodeSuspicious(requestPath);
  if (suspicious.traversal) {
    return fail(PATH_ERRORS.PATH_TRAVERSAL, requestPath, "encoded path traversal rejected");
  }
  if (hasDotDotSegment(requestPath)) {
    return fail(PATH_ERRORS.PATH_TRAVERSAL, requestPath, "dot-dot path segment rejected");
  }

  let abs;
  try {
    const rootReal = realpathSync.native ? realpathSync.native(resolve(workspaceRoot)) : realpathSync(resolve(workspaceRoot));
    abs = isAbsolute(requestPath) ? resolve(requestPath) : resolve(rootReal, requestPath);
    // 相对路径以工作区为基准；绝对路径必须落在工作区内。
    assertWithinWorkspace(rootReal, abs);
  } catch (err) {
    if (err instanceof PathSecurityError) {
      return fail(err.code, requestPath, err.message);
    }
    return fail(PATH_ERRORS.INVALID_PATH, requestPath, err.message);
  }

  const resolved = resolveWithinRoot(workspaceRoot, abs);
  if (!resolved.ok) return resolved;
  if (allowSymlinks !== true && resolved.realPath !== abs && resolved.path === abs) {
    // resolveWithinRoot 已拒绝逃逸的链接；这里只记录实际解析结果。
  }

  if (mustExist && !existsSync(resolved.realPath)) {
    return fail(PATH_ERRORS.NOT_FOUND, resolved.path, "file not found");
  }

  // 授权：默认拒绝；提示词中的路径不构成授权。
  const granted = grants.some((grant) => {
    const ops = grant?.operations ?? grant?.ops ?? ["read"];
    if (!Array.isArray(ops) || !ops.includes(operation)) return false;
    if (typeof grant.path !== "string" || grant.path.length === 0) return false;
    let grantAbs;
    try {
      grantAbs = isAbsolute(grant.path)
        ? resolve(grant.path)
        : resolve(realpathSync(resolve(workspaceRoot)), grant.path);
      if (!isWithinWorkspace(workspaceRoot, grantAbs)) return false;
    } catch {
      return false;
    }
    return isPathInside(grantAbs, resolved.path);
  });
  if (!granted) {
    return fail(
      PATH_ERRORS.NOT_AUTHORIZED,
      resolved.path,
      "no explicit file grant covers this path; a path mentioned in a prompt is not authorization",
    );
  }

  if (operation === "write" && (expectedSha256 || expectedMtimeMs !== null)) {
    if (existsSync(resolved.realPath)) {
      const st = statSync(resolved.realPath);
      if (!st.isFile()) return fail(PATH_ERRORS.WRITE_CONFLICT, resolved.path, "target is not a regular file");
      if (expectedMtimeMs !== null && expectedMtimeMs !== undefined) {
        if (Math.trunc(st.mtimeMs) !== Math.trunc(expectedMtimeMs)) {
          return fail(PATH_ERRORS.WRITE_CONFLICT, resolved.path, "mtime changed since baseline");
        }
      }
      if (expectedSha256) {
        const actual = fileSha256(resolved.realPath);
        if (actual !== expectedSha256) {
          return fail(PATH_ERRORS.WRITE_CONFLICT, resolved.path, "content hash changed since baseline");
        }
      }
    } else if (expectedSha256) {
      return fail(PATH_ERRORS.WRITE_CONFLICT, resolved.path, "file no longer exists");
    }
  }

  return {
    ok: true,
    path: resolved.path,
    realPath: resolved.realPath,
    operation,
    withinWorkspace: true,
    promptPathIsNotAuthorization: true,
  };
}

/** 便捷布尔入口。 */
export function canAccessWorkspacePath(workspaceRoot, requestPath, opts = {}) {
  return checkRemoteFileRequest({ workspaceRoot, requestPath, ...opts }).ok;
}
