/**
 * Agent CLI 安装与探测目录（AGT-105 / INST 基础）。
 *
 * 来源：
 *   - 国际产品安装命令：Zeron `crates/harness/README.md`（2026-09-21 用 curl 对官方文档核对，MIT 引用）；
 *   - 国产产品安装命令：platform/docs/DOMESTIC-AGENTS.md（官方文档核对）。
 *
 * 本模块只做「有据可查的安装计划与探测方式」，不执行安装；是否安装由用户/安装器决定。
 * 任何条目都不表示该 Agent 已被适配或可发布——能力仍以 SUPPORT-MATRIX 为准。
 */
export const INSTALL_CATALOG_VERSION = 1;

/** platform: "linux" | "macos" | "windows"（linux/macos 命令相同即复用）。 */
export const INSTALL_CATALOG = Object.freeze({
  "claude-code": {
    taskId: "AGT-111",
    agent: "Claude Code",
    domestic: false,
    linux: "curl -fsSL https://claude.ai/install.sh | bash",
    windows: "irm https://claude.ai/install.ps1 | iex",
    probe: ["--version", "--help"],
    protocol: "stream-json",
  },
  codex: {
    taskId: "AGT-112",
    agent: "Codex",
    domestic: false,
    linux: "curl -fsSL https://chatgpt.com/codex/install.sh | sh",
    windows: "irm https://chatgpt.com/codex/install.ps1 | iex",
    npmFallback: "npm install -g @openai/codex",
    probe: ["--version", "--help"],
    protocol: "app-server / exec --json",
  },
  cursor: {
    taskId: "AGT-118",
    agent: "Cursor CLI",
    domestic: false,
    linux: "curl https://cursor.com/install -fsS | bash",
    windows: "irm 'https://cursor.com/install?win32=true' | iex",
    probe: ["--version", "--help"],
    protocol: "agent CLI / SDK shim",
  },
  opencode: {
    taskId: "AGT-121",
    agent: "OpenCode",
    domestic: false,
    linux: "curl -fsSL https://opencode.ai/install | bash",
    npmFallback: "npm install -g opencode-ai",
    probe: ["--version", "--help"],
    protocol: "HTTP+SSE（opencode serve）",
  },
  pi: {
    taskId: "AGT-125",
    agent: "Pi Coding Agent",
    domestic: false,
    linux: "curl -fsSL https://pi.dev/install.sh | sh",
    npmFallback: "npm install -g --ignore-scripts @earendil-works/pi-coding-agent",
    probe: ["--version", "--help"],
    protocol: "JSONL RPC（pi --mode rpc）",
  },
  grok: {
    taskId: "AGT-120",
    agent: "Grok Build",
    domestic: false,
    linux: "curl -fsSL https://x.ai/cli/install.sh | bash",
    npmFallback: "npm install -g @xai-official/grok",
    probe: ["--version", "--help"],
    protocol: "ACP（grok agent stdio）",
  },
  hermes: {
    taskId: "AGT-126",
    agent: "Hermes Agent",
    domestic: false,
    linux: "curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash",
    windows: "irm https://hermes-agent.nousresearch.com/install.ps1 | iex",
    probe: ["--version", "--help"],
    protocol: "ACP（hermes acp）",
  },
  fx: {
    taskId: "AGT-129",
    agent: "fx",
    domestic: false,
    linux: "curl -fsSL https://fx.sh/setup.sh | bash",
    windows: null,
    probe: ["--version", "--help"],
    protocol: "ACP（fx acp）",
  },
  "qoder-cn": {
    taskId: "AGT-114",
    agent: "Qoder（国内）",
    domestic: true,
    linux: "curl -fsSL https://qoder.com.cn/install | bash",
    npmFallback: "npm install -g @qoder-ai/qodercli",
    probe: ["--version", "--help"],
    protocol: "ACP（qodercn --acp）/ headless stream-json",
    needsAuth: "QODERCN_PERSONAL_ACCESS_TOKEN",
    binNames: ["qodercn", "qoderclicn"],
  },
  codebuddy: {
    taskId: "AGT-117",
    agent: "CodeBuddy",
    domestic: true,
    linux: "curl -fsSL https://copilot.tencent.com/cli/install.sh | bash",
    windows: "npm.cmd install -g @tencent-ai/codebuddy-code",
    npmFallback: "npm install -g @tencent-ai/codebuddy-code",
    probe: ["--version", "--help"],
    protocol: "ACP / HTTP API（codebuddy --serve）",
    needsAuth: "CODEBUDDY_API_KEY",
  },
  kimi: {
    taskId: "AGT-128",
    agent: "Kimi Code CLI",
    domestic: true,
    linux: "curl -fsSL https://code.kimi.com/kimi-code/install.sh | bash",
    npmFallback: "npm install -g @moonshot-ai/kimi-code",
    probe: ["--version", "--help"],
    protocol: "ACP（kimi acp）/ stream-json / kimi web",
    needsAuth: "Kimi OAuth device code",
  },
  openclaw: {
    taskId: "AGT-127",
    agent: "OpenClaw",
    domestic: false,
    linux: "curl -fsSL https://openclaw.ai/install.sh | bash",
    npmFallback: "npm install -g openclaw",
    probe: ["--version", "--help"],
    protocol: "ACP（openclaw acp）/ Gateway HTTP",
    needsAuth: "Gateway onboard + 模型凭据",
  },
  trae: {
    taskId: "AGT-115",
    agent: "TRAE CLI",
    domestic: true,
    linux: 'sh -c "$(curl -fsSL https://trae.cn/trae-cli/install_v2.sh)"',
    windows: "PowerShell trae-cli/install_v2.ps1（建议 WSL2）",
    probe: ["--version", "--help"],
    protocol: "ACP / exec --json",
    needsAuth: "TRAECLI_PERSONAL_ACCESS_TOKEN",
    blockedReason: "官方限制：仅 TRAE 企业版旗舰套餐可用 TRAE CLI 2.0（259 元/席/月，3 席起）",
  },
  workbuddy: {
    taskId: "AGT-116",
    agent: "WorkBuddy",
    domestic: true,
    linux: null,
    probe: [],
    protocol: null,
    blockedReason: "官方无本地 CLI/ACP/app-server；只有 WMA 云 OpenAPI/A2A/MCP 与 IM Bot",
  },
});

/** 平台归一：macos 复用 linux 命令（当前目录里的安装器都支持 POSIX shell）。 */
export function normalizePlatform(platform) {
  if (platform === "macos" || platform === "darwin") return "linux";
  return platform === "windows" ? "windows" : "linux";
}

/** 生成安装计划；不可安装/被阻塞的返回明确原因，不伪造命令。 */
export function installPlanFor(agentId, { platform = "linux" } = {}) {
  const entry = INSTALL_CATALOG[agentId];
  if (!entry) throw new Error(`unknown agent in install catalog: ${agentId}`);
  const os = normalizePlatform(platform);
  if (entry.blockedReason) return { agentId, installable: false, os, reason: entry.blockedReason, entry };
  const command = entry[os] ?? entry.npmFallback ?? null;
  if (!command) return { agentId, installable: false, os, reason: "no documented command for this platform", entry };
  return { agentId, installable: true, os, command, probe: [...entry.probe], needsAuth: entry.needsAuth ?? null, entry };
}

export function listInstallCatalog({ domestic = null } = {}) {
  return Object.entries(INSTALL_CATALOG)
    .filter(([, entry]) => (domestic === null ? true : entry.domestic === domestic))
    .map(([id, entry]) => ({
      id,
      taskId: entry.taskId,
      agent: entry.agent,
      domestic: entry.domestic,
      installable: !entry.blockedReason && !!(entry.linux || entry.npmFallback),
      blockedReason: entry.blockedReason ?? null,
      protocol: entry.protocol ?? null,
      needsAuth: entry.needsAuth ?? null,
    }))
    .sort((a, b) => a.id.localeCompare(b.id));
}
