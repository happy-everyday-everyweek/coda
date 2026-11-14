/**
 * 适配器进程运行时（AGT-102 / ADP-001 / ADP-013 / SEC-104）。
 *
 * 只做四件事，且都可测：
 *   1. 隔离环境：从零构造 env，PATH 可白名单，丢弃 ambient 凭据（SEC-104）。
 *   2. 超时与终止：超时/取消时终止整个进程组，回报真实原因（ADP-005 需要“上游确认或可解释的进程终止”）。
 *   3. 输出截断：stdout/stderr 有界，保留字节计数与截断标记（SPEC 9.10 有界日志）。
 *   4. 可注入 spawn：测试不依赖宿主机装了哪些二进制。
 *
 * 本模块不解析任何 Agent 协议，也不写日志文件。
 */
import { spawn } from "node:child_process";
import { accessSync, constants as fsConstants, statSync } from "node:fs";
import path from "node:path";

import { AdapterError } from "./base.mjs";

/** 允许继承的环境变量：只保留运行所需的通用项。 */
export const DEFAULT_ENV_WHITELIST = Object.freeze([
  "PATH",
  "HOME",
  "USER",
  "LANG",
  "LC_ALL",
  "LC_CTYPE",
  "TZ",
  "TMPDIR",
  "TEMP",
  "TMP",
  "SHELL",
  "SystemRoot",
  "SYSTEMROOT",
  "COMSPEC",
  "PATHEXT",
  "USERPROFILE",
  "APPDATA",
  "LOCALAPPDATA",
  "PROGRAMDATA",
  "PROGRAMFILES",
  "NUMBER_OF_PROCESSORS",
  "PROCESSOR_ARCHITECTURE",
]);

/** 命中即为凭据类变量：默认不继承，必须显式 allow（SEC-104 / ADP-002）。 */
export const SECRET_ENV_PATTERN = /(TOKEN|SECRET|PASSWORD|PASSWD|API[_-]?KEY|APIKEY|CREDENTIAL|_AUTH|AUTH_|COOKIE|PRIVATE[_-]?KEY)/i;

export const DEFAULT_TIMEOUT_MS = 10_000;
export const DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;

function delimiterFor(platform) {
  return platform === "win32" ? ";" : ":";
}

/** 可执行文件扩展名（Windows）。 */
function extensionsFor(env, platform) {
  if (platform !== "win32") return [""];
  const pathext = env.PATHEXT ?? ".COM;.EXE;.BAT;.CMD";
  return ["", ...pathext.split(";").filter(Boolean).map((e) => (e.startsWith(".") ? e : `.${e}`))];
}

/**
 * 构造隔离环境。
 * @returns {{env: Record<string,string>, dropped: string[], pathEntries: string[]}}
 */
export function buildIsolatedEnv({
  base = process.env,
  allow = [],
  override = {},
  pathAllowlist = null,
  platform = process.platform,
  whitelist = DEFAULT_ENV_WHITELIST,
} = {}) {
  const allowed = new Set([...whitelist, ...allow]);
  const env = {};
  const dropped = [];
  for (const key of allowed) {
    if (!Object.hasOwn(base, key)) continue;
    if (SECRET_ENV_PATTERN.test(key) && !allow.includes(key)) {
      dropped.push(key);
      continue;
    }
    env[key] = base[key];
  }
  for (const key of Object.keys(base)) {
    if (Object.hasOwn(env, key)) continue;
    if (!allow.includes(key)) continue;
    env[key] = base[key];
  }
  // 显式 override 永远生效（调用方明确指定的变量）。
  for (const [key, value] of Object.entries(override ?? {})) {
    if (value === undefined || value === null) continue;
    env[key] = String(value);
  }

  const pathEntries = pathAllowlist ? [...pathAllowlist] : (env.PATH ?? "").split(delimiterFor(platform)).filter(Boolean);
  if (pathAllowlist) env.PATH = pathEntries.join(delimiterFor(platform));
  if (!env.PATH) {
    env.PATH = platform === "win32" ? "" : "/usr/local/bin:/usr/bin:/bin";
    pathEntries.push(...env.PATH.split(delimiterFor(platform)).filter(Boolean));
  }
  return { env, dropped, pathEntries };
}

function isExecutableFile(file, platform) {
  try {
    if (!statSync(file).isFile()) return false;
    if (platform !== "win32") accessSync(file, fsConstants.X_OK);
    return true;
  } catch {
    return false;
  }
}

/**
 * 在 PATH（或白名单目录）里定位可执行文件，不调用外部 `which`，无副作用。
 * @returns {string|null} 绝对路径
 */
export function resolveExecutable(command, { env = process.env, pathAllowlist = null, platform = process.platform } = {}) {
  if (typeof command !== "string" || command === "") return null;
  if (command.includes("/") || command.includes("\\")) {
    return isExecutableFile(command, platform) ? command : null;
  }
  const dirs = pathAllowlist ?? (env.PATH ?? "").split(delimiterFor(platform)).filter(Boolean);
  const exts = extensionsFor(env, platform);
  for (const dir of dirs) {
    for (const ext of exts) {
      const candidate = path.join(dir, command + ext);
      if (isExecutableFile(candidate, platform)) return candidate;
    }
  }
  return null;
}

function killTree(child, signal) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return false;
  try {
    if (process.platform !== "win32" && child.pid) {
      process.kill(-child.pid, signal);
      return true;
    }
  } catch {
    /* 进程组可能已消失，回退到直接 kill */
  }
  try {
    child.kill(signal);
    return true;
  } catch {
    return false;
  }
}

/** 追加有界输出；返回是否本次发生截断。 */
function appendBounded(state, chunk, maxBytes) {
  state.bytes += Buffer.byteLength(chunk, state.encoding);
  if (state.truncated) return true;
  const room = maxBytes - state.text.length;
  if (room <= 0) {
    state.truncated = true;
    return true;
  }
  if (chunk.length <= room) {
    state.text += chunk;
    return false;
  }
  state.text += chunk.slice(0, room);
  state.truncated = true;
  return true;
}

/**
 * 启动一个受隔离管理的子进程（长生命周期，供 ACP 等协议使用）。
 * 调用方负责读取 stdout/stderr 与关闭。
 */
export function spawnProcess({
  command,
  args = [],
  cwd = null,
  env = null,
  allowEnv = [],
  overrideEnv = {},
  pathAllowlist = null,
  isolate = true,
  platform = process.platform,
  spawnImpl = spawn,
} = {}) {
  if (!command) throw new AdapterError("NOT_IMPLEMENTED", "command is required");
  let executable = command;
  let childEnv;
  let dropped = [];
  if (isolate) {
    const built = buildIsolatedEnv({ base: env ?? process.env, allow: allowEnv, override: overrideEnv, pathAllowlist, platform });
    childEnv = built.env;
    dropped = built.dropped;
    if (pathAllowlist) {
      const resolved = resolveExecutable(command, { env: childEnv, pathAllowlist, platform });
      if (!resolved) {
        throw new AdapterError("NOT_INSTALLED", `executable not found in PATH allowlist: ${command}`, {
          details: { command, pathAllowlist },
        });
      }
      executable = resolved;
    }
  } else {
    childEnv = { ...(env ?? process.env), ...overrideEnv };
  }

  const child = spawnImpl(executable, args, {
    cwd: cwd ?? undefined,
    env: childEnv,
    stdio: ["pipe", "pipe", "pipe"],
    detached: platform !== "win32",
    windowsHide: true,
  });
  child.__coda = { executable, droppedEnv: dropped, startedAt: Date.now() };
  return child;
}

/**
 * 运行一次性命令：有超时、有终止、输出有界。
 * 永远不 reject 于非零退出码；只有启动失败（ENOENT 等）才 reject。
 */
export function runProcess({
  command,
  args = [],
  cwd = null,
  env = null,
  allowEnv = [],
  overrideEnv = {},
  pathAllowlist = null,
  isolate = true,
  timeoutMs = DEFAULT_TIMEOUT_MS,
  maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES,
  stdin = null,
  signal = null,
  encoding = "utf8",
  onStdout = null,
  onStderr = null,
  platform = process.platform,
  spawnImpl = spawn,
} = {}) {
  return new Promise((resolve, reject) => {
    const startedAt = Date.now();
    let child;
    try {
      child = spawnProcess({
        command,
        args,
        cwd,
        env,
        allowEnv,
        overrideEnv,
        pathAllowlist,
        isolate,
        platform,
        spawnImpl,
      });
    } catch (err) {
      reject(err);
      return;
    }

    const out = { text: "", bytes: 0, truncated: false, encoding };
    const errOut = { text: "", bytes: 0, truncated: false, encoding };
    let settled = false;
    let timedOut = false;
    let aborted = false;
    let killed = false;
    let spawnError = null;

    const timer =
      Number.isFinite(timeoutMs) && timeoutMs > 0
        ? setTimeout(() => {
            timedOut = true;
            killed = killTree(child, "SIGKILL") || killed;
          }, timeoutMs)
        : null;
    timer?.unref?.();

    const onAbort = () => {
      aborted = true;
      killed = killTree(child, "SIGKILL") || killed;
    };
    if (signal) {
      if (signal.aborted) onAbort();
      else signal.addEventListener("abort", onAbort, { once: true });
    }

    child.stdout?.setEncoding(encoding);
    child.stderr?.setEncoding(encoding);
    child.stdout?.on("data", (chunk) => {
      appendBounded(out, chunk, maxOutputBytes);
      onStdout?.(chunk);
    });
    child.stderr?.on("data", (chunk) => {
      appendBounded(errOut, chunk, maxOutputBytes);
      onStderr?.(chunk);
    });

    child.on("error", (error) => {
      spawnError = error;
      // 有些平台在 spawn 失败时不会等到 'close'：这里立即结算，避免调用方永久挂起。
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      signal?.removeEventListener?.("abort", onAbort);
      const code2 = error.code === "ENOENT" ? "NOT_INSTALLED" : "PROCESS_EXIT";
      reject(
        new AdapterError(code2, `failed to start ${command}: ${error.message}`, {
          cause: error,
          details: { command, args },
        }),
      );
    });

    child.on("close", (code, sig) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      signal?.removeEventListener?.("abort", onAbort);
      if (spawnError) {
        const code2 = spawnError.code === "ENOENT" ? "NOT_INSTALLED" : "PROCESS_EXIT";
        reject(
          new AdapterError(code2, `failed to start ${command}: ${spawnError.message}`, {
            cause: spawnError,
            details: { command, args },
          }),
        );
        return;
      }
      resolve({
        ok: code === 0 && !timedOut && !aborted,
        code: code ?? null,
        signal: sig ?? null,
        timedOut,
        aborted,
        killed,
        durationMs: Date.now() - startedAt,
        stdout: out.text,
        stderr: errOut.text,
        stdoutBytes: out.bytes,
        stderrBytes: errOut.bytes,
        stdoutTruncated: out.truncated,
        stderrTruncated: errOut.truncated,
        command: child.__coda?.executable ?? command,
        args,
        droppedEnv: child.__coda?.droppedEnv ?? [],
      });
    });

    if (child.stdin) {
      child.stdin.on("error", () => {
        /* 子进程提前退出时忽略 EPIPE */
      });
      if (stdin !== null && stdin !== undefined) child.stdin.end(stdin);
      else child.stdin.end();
    }
  });
}

/** 终止由 spawnProcess 创建的进程组。 */
export async function terminateProcess(child, { graceMs = 200 } = {}) {
  if (!child || child.exitCode !== null || child.signalCode !== null) {
    return { killed: false, code: child?.exitCode ?? null, signal: child?.signalCode ?? null };
  }
  const exited = new Promise((resolve) => child.once("close", (code, sig) => resolve({ code, sig })));
  killTree(child, "SIGTERM");
  const settled = await Promise.race([
    exited.then((r) => ({ ...r, hard: false })),
    new Promise((resolve) => setTimeout(() => resolve(null), Math.max(0, graceMs))),
  ]);
  if (settled) return { killed: true, ...settled };
  killTree(child, "SIGKILL");
  const after = await Promise.race([
    exited,
    new Promise((resolve) => {
      const timer = setTimeout(() => resolve({ code: null, signal: null }), 1000);
      timer.unref?.();
    }),
  ]);
  return { killed: true, ...after, hard: true };
}

/** 文本截断工具（诊断日志用）。 */
export function truncateText(text, maxBytes = 4096) {
  const s = String(text ?? "");
  if (Buffer.byteLength(s) <= maxBytes) return { text: s, truncated: false };
  return { text: s.slice(0, maxBytes), truncated: true };
}
