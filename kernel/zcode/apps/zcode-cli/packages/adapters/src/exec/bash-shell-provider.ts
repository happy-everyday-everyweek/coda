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
 */
const EXTENSION_SHELL = "ZCODE_TERMINAL_SHELL";
const EXTENSION_DIALECT = "ZCODE_TERMINAL_DIALECT";
const EXTENSION_LOGIN = "ZCODE_TERMINAL_LOGIN";

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

  if (!isExecutableCandidate(shellPath, options.exists)) {
    return undefined;
  }

  const loginShell = options.env[EXTENSION_LOGIN]?.trim() !== "0";
  const gitBash = dialect === "git-bash";
  const name = basename(shellPath) || (gitBash ? "bash" : "sh");
  return {
    provider: gitBash
      ? createGitBashProvider(shellPath, loginShell)
      : createPosixShellProvider(shellPath, loginShell),
    selection: shellSelection({
      dialect: gitBash ? "git-bash" : "posix",
      displayName: name,
      id: `extension:${name}`,
      label: `终端拓展：${shellPath}`,
      path: shellPath,
      source: "extension",
    }),
  };
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
  if (selection.dialect === "git-bash") {
    return createGitBashProvider(selection.path);
  }
  if (selection.dialect === "cmd") {
    return createWindowsCmdProvider(selection.path);
  }
  if (selection.dialect === "posix") {
    return createPosixShellProvider(selection.path);
  }
  return undefined;
}

function createGitBashProvider(shellPath: string, loginShell?: boolean): BashShellProvider {
  return {
    dialect: "git-bash",
    envOverlay: {
      GIT_EDITOR: "true",
      SHELL: shellPath,
    },
    file: shellPath,
    shell: false,
    loginShell,
  };
}

function createPosixShellProvider(shellPath: string, loginShell?: boolean): BashShellProvider {
  return {
    dialect: "posix",
    envOverlay: {
      GIT_EDITOR: "true",
      SHELL: shellPath,
    },
    file: shellPath,
    shell: false,
    loginShell,
  };
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