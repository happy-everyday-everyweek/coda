import { accessSync, constants as fsConstants } from "node:fs";
import { basename } from "node:path";
import { type ExecutionShellDialect, type ExecutionShellSelection } from "@zcode/contracts";

type PosixShellKind = "bash" | "zsh";
type ExecutableCheck = (path: string) => boolean;
type EffectiveBashShellResolveOptions = {
  env: NodeJS.ProcessEnv;
  platform: NodeJS.Platform | string;
  exists?: ExecutableCheck;
  override?: ExecutionShellSelection;
};

/**
 * 终端拓展注入的环境变量。
 *
 * 新架构里"有哪些终端"由宿主的终端分类拓展决定：拓展自带 shell 可执行文件与工具目录，
 * 宿主启动内核时用这几个变量声明。内核不再扫描目录猜测环境里有什么 shell——那种探测依赖
 * 具体设备的布局（Android 上既没有 /bin/bash 也没有 /bin/sh），且在拓展体系下属于重复判断。
 * 既没有用户指定、也没有拓展声明时退回 legacy shell，与旧调用方保持兼容。
 *
 * 拓展还可以声明一层启动器包裹（`ZCODE_TERMINAL_LAUNCH_*`）：可执行文件不是 shell 本身，
 * 而是先跑启动器再进 shell，典型是移动端用 PRoot 进入随包携带的 Linux 环境。
 */
const EXTENSION_SHELL = "ZCODE_TERMINAL_SHELL";
const EXTENSION_DIALECT = "ZCODE_TERMINAL_DIALECT";
const EXTENSION_LOGIN = "ZCODE_TERMINAL_LOGIN";
const EXTENSION_LAUNCH_EXEC = "ZCODE_TERMINAL_LAUNCH_EXEC";
const EXTENSION_LAUNCH_PREFIX = "ZCODE_TERMINAL_LAUNCH_PREFIX";
const EXTENSION_LAUNCH_ENTRY = "ZCODE_TERMINAL_LAUNCH_ENTRY";

/**
 * 启动器包裹的终端：真正的 shell 不在宿主路径上，需要先跑一个启动器。
 *
 * 内置 Linux 环境（PRoot）就是这种形态：可执行文件是 proot，前面有 `-0 -r <rootfs> -w /root`
 * 之类的参数，后面跟着 `env -i ... /bin/bash` 这段固定 argv，命令再拼在最后。
 * 宿主通过三个环境变量声明，内核只负责按顺序拼 argv，不认识 proot 本身。
 */
interface ExtensionShellLauncher {
  entry: string[];
  file: string;
  prefix: string[];
}

export interface BashShellProvider {
  dialect: ExecutionShellDialect;
  envOverlay?: Record<string, string>;
  file: string;
  shell: boolean | string;
  /**
   * 是否以登录 shell 执行。拓展可以声明不需要登录 shell（例如 busybox ash），
   * 未声明时按登录 shell 处理，与内核原有行为一致。
   */
  loginShell?: boolean;
  /** 启动器参数，紧跟 file 之后；普通 shell 为空。 */
  prefixArgs?: string[];
  /** 启动器之后到命令之前的固定 argv；普通 shell 未声明。 */
  entryArgs?: string[];
}

interface EffectiveBashShellResolution {
  selection: ExecutionShellSelection;
  provider?: BashShellProvider;
}

export function resolveEffectiveBashShellSelection(
  options: EffectiveBashShellResolveOptions,
): EffectiveBashShellResolution {
  const snapshotResolution = resolveShellSnapshotSelection(options.override);
  if (snapshotResolution) {
    return snapshotResolution;
  }

  const configuredResolution = resolveConfiguredShellSelection(options);
  if (configuredResolution) {
    return configuredResolution;
  }

  // 可用的终端由宿主的终端拓展提供，内核不再探测环境里有哪些 shell。
  const extensionResolution = resolveExtensionShellSelection(options);
  if (extensionResolution) {
    return extensionResolution;
  }

  return legacyShellSelection();
}

/** 用户在设置里显式指定的 shell 优先于拓展声明。 */
function resolveConfiguredShellSelection(
  options: EffectiveBashShellResolveOptions,
): EffectiveBashShellResolution | undefined {
  const override = options.override;
  if (override?.source !== "user-config" || !override.path) {
    return undefined;
  }

  if (override.dialect === "git-bash" && isExecutableCandidate(override.path, options.exists)) {
    return {
      provider: createGitBashProvider(override.path),
      selection: shellSelection({
        dialect: "git-bash",
        displayName: "Git Bash",
        id: override.id,
        label: override.label,
        path: override.path,
        source: "user-config",
      }),
    };
  }

  if (override.dialect === "cmd") {
    const cmdPath = resolveWindowsCmdOverridePath(override.path, options.exists);
    if (!cmdPath) {
      return undefined;
    }
    return {
      provider: createWindowsCmdProvider(cmdPath),
      selection: shellSelection({
        dialect: "cmd",
        displayName: "CMD",
        id: override.id,
        label: override.label,
        path: cmdPath,
        source: "user-config",
      }),
    };
  }

  if (override.dialect === "posix" && isExecutableCandidate(override.path, options.exists)) {
    return {
      provider: createPosixShellProvider(override.path),
      selection: shellSelection({
        dialect: "posix",
        displayName: basename(override.path) || "sh",
        id: override.id,
        label: override.label,
        path: override.path,
        source: "user-config",
      }),
    };
  }

  return undefined;
}

/** 终端拓展声明的 shell。 */
function resolveExtensionShellSelection(
  options: EffectiveBashShellResolveOptions,
): EffectiveBashShellResolution | undefined {
  const shellPath = options.env[EXTENSION_SHELL]?.trim();
  if (!shellPath) {
    return undefined;
  }

  const dialect = options.env[EXTENSION_DIALECT]?.trim().toLowerCase();
  if (dialect === "cmd") {
    const cmdPath = resolveWindowsCmdOverridePath(shellPath, options.exists);
    if (!cmdPath) {
      return undefined;
    }
    return {
      provider: createWindowsCmdProvider(cmdPath),
      selection: shellSelection({
        dialect: "cmd",
        displayName: "CMD",
        id: "extension:cmd",
        label: `终端拓展：${cmdPath}`,
        path: cmdPath,
        source: "extension",
      }),
    };
  }

  const launcher = resolveExtensionLauncher(options.env, options.exists);
  const execPath = launcher?.file ?? shellPath;

  if (!isExecutableCandidate(execPath, options.exists)) {
    return undefined;
  }

  const loginShell = options.env[EXTENSION_LOGIN]?.trim() !== "0";
  const gitBash = dialect === "git-bash";
  const guestShell = launcher?.entry.at(-1);
  const name = basename(guestShell ?? shellPath) || (gitBash ? "bash" : "sh");
  return {
    provider: gitBash
      ? createGitBashProvider(shellPath, loginShell, launcher)
      : createPosixShellProvider(shellPath, loginShell, launcher),
    selection: shellSelection({
      dialect: gitBash ? "git-bash" : "posix",
      displayName: name,
      id: `extension:${name}`,
      label: `终端拓展：${execPath}`,
      path: execPath,
      source: "extension",
    }),
  };
}

/**
 * 宿主是否声明了启动器包裹。
 *
 * 只要前缀或 entry 有一个非空就按启动器形态处理：exec 取 LAUNCH_EXEC，缺失时退回
 * 拓展声明的 shell 路径。格式不对（不是字符串数组）时当作没声明，退回普通 shell 行为。
 */
function resolveExtensionLauncher(
  env: NodeJS.ProcessEnv,
  exists?: ExecutableCheck,
): ExtensionShellLauncher | undefined {
  const prefix = parseArgv(env[EXTENSION_LAUNCH_PREFIX]);
  const entry = parseArgv(env[EXTENSION_LAUNCH_ENTRY]);
  if (prefix.length === 0 && entry.length === 0) return undefined;

  const file = env[EXTENSION_LAUNCH_EXEC]?.trim() || env[EXTENSION_SHELL]?.trim() || "";
  if (!file || !isExecutableCandidate(file, exists)) return undefined;
  return { entry, file, prefix };
}

function parseArgv(raw: string | undefined): string[] {
  const text = raw?.trim();
  if (!text) return [];

  try {
    const parsed: unknown = JSON.parse(text);
    if (!Array.isArray(parsed)) return [];
    return parsed.every((item) => typeof item === "string") ? (parsed as string[]) : [];
  } catch {
    return [];
  }
}

function resolveShellSnapshotSelection(
  selection: ExecutionShellSelection | undefined,
): EffectiveBashShellResolution | undefined {
  if (!selection || selection.source === "user-config") {
    return undefined;
  }
  return {
    provider: createShellProviderFromSelection(selection),
    selection,
  };
}

function createShellProviderFromSelection(
  selection: ExecutionShellSelection,
): BashShellProvider | undefined {
  if (!selection.path) return undefined;
  // 拓展声明的选择只记了方言与路径，容器化启动参数不在选择里；
  // 这里重建 provider 时从进程环境重取一次启动器，避免丢掉前缀后直接 exec 启动器。
  const launcher =
    selection.source === "extension" ? resolveExtensionLauncher(process.env) : undefined;
  if (selection.dialect === "git-bash") {
    return createGitBashProvider(selection.path, undefined, launcher);
  }
  if (selection.dialect === "cmd") {
    return createWindowsCmdProvider(selection.path);
  }
  if (selection.dialect === "posix") {
    return createPosixShellProvider(selection.path, undefined, launcher);
  }
  return undefined;
}

function createGitBashProvider(
  shellPath: string,
  loginShell?: boolean,
  launcher?: ExtensionShellLauncher,
): BashShellProvider {
  return {
    dialect: "git-bash",
    envOverlay: {
      GIT_EDITOR: "true",
      SHELL: guestShellPath(shellPath, launcher),
    },
    entryArgs: launcher?.entry,
    file: launcher?.file ?? shellPath,
    loginShell,
    prefixArgs: launcher?.prefix,
    shell: false,
  };
}

function createPosixShellProvider(
  shellPath: string,
  loginShell?: boolean,
  launcher?: ExtensionShellLauncher,
): BashShellProvider {
  return {
    dialect: "posix",
    envOverlay: {
      GIT_EDITOR: "true",
      SHELL: guestShellPath(shellPath, launcher),
    },
    entryArgs: launcher?.entry,
    file: launcher?.file ?? shellPath,
    loginShell,
    prefixArgs: launcher?.prefix,
    shell: false,
  };
}

/**
 * SHELL 要报给 shell 自己的路径：容器里宿主的可执行文件路径不存在，
 * 有启动器时用 entry 末尾的容器内 shell 路径。
 */
function guestShellPath(shellPath: string, launcher?: ExtensionShellLauncher): string {
  return launcher?.entry.at(-1) ?? shellPath;
}

function createWindowsCmdProvider(shellPath: string): BashShellProvider {
  return {
    dialect: "cmd",
    file: shellPath,
    shell: shellPath,
  };
}

function resolveWindowsCmdOverridePath(
  shellPath: string,
  exists?: ExecutableCheck,
): string | undefined {
  if (isExecutableCandidate(shellPath, exists)) {
    return shellPath;
  }
  // 设置页在 ComSpec 缺失时会暴露系统默认 cmd.exe fallback；它和 generic
  // Windows shell fallback 一样不能依赖 accessSync 预校验，否则用户显式选择会被忽略。
  return isWindowsCmdFallback(shellPath) ? shellPath : undefined;
}

function isWindowsCmdFallback(shellPath: string): boolean {
  return (
    !shellPath.includes("\\") && !shellPath.includes("/") && shellPath.toLowerCase() === "cmd.exe"
  );
}

function shellSelection(options: {
  dialect: ExecutionShellDialect;
  displayName: string;
  id?: string;
  label?: string;
  path: string;
  source: "user-config" | "extension";
}): ExecutionShellSelection {
  return {
    dialect: options.dialect,
    display: { name: options.displayName },
    id: options.id,
    label: options.label,
    path: options.path,
    source: options.source,
  };
}

function legacyShellSelection(): EffectiveBashShellResolution {
  return {
    selection: {
      dialect: "legacy-shell",
      display: { name: "system shell" },
      source: "legacy-fallback",
    },
  };
}

function posixShellKind(path: string): PosixShellKind | undefined {
  const name = basename(path);
  if (name.includes("bash")) return "bash";
  if (name.includes("zsh")) return "zsh";
  return undefined;
}

export function isExecutableCandidate(path: string, exists?: ExecutableCheck): boolean {
  if (exists) {
    return exists(path);
  }

  try {
    accessSync(path, fsConstants.X_OK);
    return true;
  } catch {
    return false;
  }
}