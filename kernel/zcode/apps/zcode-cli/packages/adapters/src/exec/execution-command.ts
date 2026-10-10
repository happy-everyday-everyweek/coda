import { existsSync } from "node:fs";
import { extname } from "node:path";
import { sanitizeZCodeRuntimeEnvInPlace } from "@zcode/shared";
import { applyNetworkEgressEnv, type NetworkEgressEnvPolicy } from "../network/subprocess-env.js";
import {
  resolveEffectiveBashShellSelection,
  type BashShellProvider,
} from "./bash-shell-provider.js";
import { applyExecutionTextEnv } from "./outputEncoding.js";
import { windowsExecutableCandidates } from "./windows-executable.js";
import type {
  ExecutionCommand,
  ExecutionEnvOverlay,
  ExecutionShellDialect,
} from "@zcode/contracts";

const WINDOWS_COMMAND_SHIM_EXTENSIONS = new Set([".cmd", ".bat"]);

/**
 * shell 形态命令的 argv 组装信息。
 *
 * 原来的实现靠 `args[0] === "-c"` / `args[1] === "-l"` 反推 argv 形状，一旦 shell 前面插入
 * 一层启动器（PRoot 之类），这个判断就会失效。这里把形状显式记下来：`prefix` 是启动器参数，
 * `entry` 是启动器之后到命令之前的固定 argv，`login` 记录当前是否带登录标记。
 */
export interface ResolvedShellLaunch {
  entry?: string[];
  login: boolean;
  prefix: string[];
}

export interface ResolvedSpawnCommand {
  args: string[];
  cwdDialect: ExecutionShellDialect;
  envOverlay?: Record<string, string>;
  file: string;
  shell: boolean | string;
  shellLaunch?: ResolvedShellLaunch;
  usesLoginShell?: boolean;
}

export function buildExecutionEnv(
  overlay?: ExecutionEnvOverlay,
  options: {
    network?: NetworkEgressEnvPolicy;
    platform?: NodeJS.Platform;
    processEnv?: NodeJS.ProcessEnv;
  } = {},
): NodeJS.ProcessEnv {
  const platform = options.platform ?? process.platform;
  const sourceEnv = options.processEnv ?? process.env;
  const env: Record<string, string> = {};

  if (overlay?.base !== "empty") {
    for (const [key, value] of Object.entries(sourceEnv)) {
      if (value !== undefined) {
        env[key] = value;
      }
    }
    // Bash/tool 子进程不能直接继承运行时的 NODE_ENV、http_proxy 或证书变量。
    // 网络变量会在 applyNetworkEgressEnv 中从 ZCode 内部封存恢复，避免 app/provider 运行时先被污染。
    sanitizeZCodeRuntimeEnvInPlace(env);
  }

  applyExecutionTextEnv(env, platform);

  applyNetworkEgressEnv(env, {
    network: options.network,
    platform,
    sourceEnv,
    toolEnvPassthrough: overlay?.base !== "empty",
  });

  for (const key of overlay?.unset ?? []) {
    deleteEnvKey(env, key, platform);
  }

  for (const [key, value] of Object.entries(overlay?.set ?? {})) {
    setEnvKey(env, key, value, platform);
  }

  return env;
}

export function resolveExecutionCommand(
  command: ExecutionCommand,
  options: {
    cwd?: string;
    env?: NodeJS.ProcessEnv;
    exists?: (path: string) => boolean;
    platform?: NodeJS.Platform;
    resolvedShell?: ResolvedSpawnCommand;
  } = {},
): ResolvedSpawnCommand {
  const platform = options.platform ?? process.platform;
  const env = options.env ?? process.env;

  if (command.mode === "shell") {
    if (options.resolvedShell) {
      return applyResolvedShellCommand(options.resolvedShell, command.command);
    }

    if (command.shellProfile === "posix-bash") {
      const resolution = resolveEffectiveBashShellSelection({
        env,
        exists: options.exists,
        override: command.shellOverride,
        platform,
      });
      if (resolution.provider) {
        return createShellProviderCommand(resolution.provider, command.command);
      }
    }

    return {
      args: [],
      cwdDialect: defaultCwdDialect(platform),
      file: command.command,
      shell: resolveShell(command.shell, env, platform),
    };
  }

  if (platform !== "win32") {
    return {
      args: command.args ?? [],
      cwdDialect: "posix",
      file: command.file,
      shell: false,
    };
  }

  const resolvedFile = resolveWindowsExecutable(command.file, {
    cwd: options.cwd,
    env,
    exists: options.exists,
  });
  if (!isWindowsCommandShim(resolvedFile)) {
    return {
      args: command.args ?? [],
      cwdDialect: "cmd",
      file: resolvedFile,
      shell: false,
    };
  }

  // Note: Windows .cmd/.bat shims cannot be spawned directly with shell:false.
  // Route them through cmd.exe while keeping normal .exe argv execution shell-free.
  return {
    args: createCmdShimArgs(resolvedFile, command.args ?? []),
    cwdDialect: "cmd",
    file: getEnvValue(env, "ComSpec", "win32") ?? "cmd.exe",
    shell: false,
  };
}

function createShellProviderCommand(
  provider: BashShellProvider,
  command: string,
): ResolvedSpawnCommand {
  if (provider.dialect === "cmd") {
    return {
      args: [],
      cwdDialect: provider.dialect,
      envOverlay: provider.envOverlay,
      file: command,
      shell: provider.shell,
    };
  }

  const shellLaunch: ResolvedShellLaunch = {
    entry: provider.entryArgs,
    login: provider.loginShell !== false,
    prefix: provider.prefixArgs ?? [],
  };
  return {
    args: buildShellLaunchArgs(shellLaunch, command),
    cwdDialect: provider.dialect,
    envOverlay: provider.envOverlay,
    file: provider.file,
    shell: provider.shell,
    shellLaunch,
    usesLoginShell: true,
  };
}

/**
 * 命令是否由启动器包裹（例如 PRoot 进入内置 Linux 环境）。
 *
 * 这类形态的 file 不是 shell 本身，任何"直接 execFile(file, ["-c", ...])"的旁路都必须跳过，
 * 目前只有终端初始化快照走这种旁路。
 */
export function isLauncherShell(resolved: ResolvedSpawnCommand): boolean {
  const launch = resolved.shellLaunch;
  return launch !== undefined && (launch.prefix.length > 0 || (launch.entry?.length ?? 0) > 0);
}

export function setResolvedShellLoginMode(
  resolved: ResolvedSpawnCommand,
  useLoginShell: boolean,
): ResolvedSpawnCommand {
  const launch = shellLaunchOf(resolved);
  if (resolved.shell !== false || !launch || resolved.usesLoginShell !== true) return resolved;
  if (launch.login === useLoginShell) return resolved;

  const command = resolved.args.at(-1) ?? "";
  const nextLaunch: ResolvedShellLaunch = { ...launch, login: useLoginShell };
  return {
    ...resolved,
    args: buildShellLaunchArgs(nextLaunch, command),
    shellLaunch: nextLaunch,
  };
}

export function applyResolvedShellCommand(
  resolved: ResolvedSpawnCommand,
  command: string,
): ResolvedSpawnCommand {
  if (resolved.cwdDialect === "cmd") {
    return {
      ...resolved,
      args: [],
      file: command,
    };
  }

  const launch = shellLaunchOf(resolved);
  if (resolved.shell === false && launch && resolved.usesLoginShell === true) {
    return {
      ...resolved,
      args: buildShellLaunchArgs(launch, command),
    };
  }

  return {
    ...resolved,
    args: [],
    file: command,
  };
}

function buildShellLaunchArgs(launch: ResolvedShellLaunch, command: string): string[] {
  return [
    ...launch.prefix,
    ...(launch.entry ?? []),
    "-c",
    ...(launch.login ? ["-l"] : []),
    command,
  ];
}

/**
 * 取 argv 形状。旧形状（argv 直接是 `-c` / `-c -l`）不带 shellLaunch，
 * 这里按空前缀补出来，让既有调用方与测试里手写的字面量仍走同一条路径。
 */
function shellLaunchOf(resolved: ResolvedSpawnCommand): ResolvedShellLaunch | undefined {
  if (resolved.shellLaunch) return resolved.shellLaunch;
  if (resolved.shell !== false || resolved.usesLoginShell !== true) return undefined;
  if (resolved.args[0] !== "-c") return undefined;
  return { login: resolved.args[1] === "-l", prefix: [] };
}

export const applyResolvedShellCommandForTest = applyResolvedShellCommand;

export function defaultCwdDialect(platform: NodeJS.Platform): ExecutionShellDialect {
  return platform === "win32" ? "cmd" : "posix";
}

function resolveShell(
  shell: true | string | undefined,
  env: NodeJS.ProcessEnv,
  platform: NodeJS.Platform,
): true | string {
  if (typeof shell === "string") return shell;
  if (platform === "win32") {
    return getEnvValue(env, "ComSpec", "win32") ?? "cmd.exe";
  }
  return true;
}

function resolveWindowsExecutable(
  file: string,
  options: {
    cwd?: string;
    env: NodeJS.ProcessEnv;
    exists?: (path: string) => boolean;
  },
): string {
  const exists = options.exists ?? existsSync;
  const candidates = windowsExecutableCandidates(file, options.env, options.cwd);
  return candidates.find((candidate) => exists(candidate)) ?? file;
}

function isWindowsCommandShim(file: string): boolean {
  return WINDOWS_COMMAND_SHIM_EXTENSIONS.has(extname(file).toLowerCase());
}

function createCmdShimArgs(file: string, args: string[]): string[] {
  const commandLine = [file, ...args].map(quoteCmdArgument).join(" ");
  return ["/d", "/s", "/c", commandLine];
}

function quoteCmdArgument(value: string): string {
  if (value.length === 0) return '""';
  if (!/[\s"%&()<>^|]/.test(value)) return value;
  return `"${value.replace(/(["%&()<>^|])/g, "^$1")}"`;
}

function setEnvKey(
  env: Record<string, string>,
  key: string,
  value: string,
  platform: NodeJS.Platform,
): void {
  deleteEnvKey(env, key, platform);
  env[key] = value;
}

function deleteEnvKey(env: Record<string, string>, key: string, platform: NodeJS.Platform): void {
  if (platform !== "win32") {
    delete env[key];
    return;
  }

  const lowerKey = key.toLowerCase();
  for (const existingKey of Object.keys(env)) {
    if (existingKey.toLowerCase() === lowerKey) {
      delete env[existingKey];
    }
  }
}

function getEnvValue(
  env: NodeJS.ProcessEnv,
  key: string,
  platform: NodeJS.Platform,
): string | undefined {
  if (platform !== "win32") return env[key];
  const lowerKey = key.toLowerCase();
  const actualKey = Object.keys(env).find((candidate) => candidate.toLowerCase() === lowerKey);
  return actualKey ? env[actualKey] : undefined;
}
