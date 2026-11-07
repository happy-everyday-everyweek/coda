/**
 * ACP 画像（AGT-119/120/121/122/126/129）：给 ground-up ACP 的 Agent 一个统一接入表。
 *
 * 依据 Zeron `crates/harness/src/acp/mod.rs` 与实机命令：
 *   Grok `grok agent stdio`、Hermes `hermes acp`、fx `fx acp`、
 *   Gemini `gemini --acp`、OpenCode `opencode acp`、Antigravity `agy_acp_server`。
 * 本机 CI 已真实验证 gemini/opencode 的 initialize 握手（见 fixtures/probes 与
 * fixtures/adapters/*-acp-handshake.fixture.json）。
 */
import { AcpAdapter, acpCapabilityDeclaration } from "./acp.mjs";

export const ACP_PROFILES = Object.freeze({
  gemini: { provider: "gemini-cli", command: "gemini", args: ["--acp"], taskId: "AGT-122", docs: "https://github.com/google-gemini/gemini-cli" },
  opencode: { provider: "opencode", command: "opencode", args: ["acp"], taskId: "AGT-121", docs: "https://opencode.ai/docs/", note: "ACP 仅为实验路径；主链路应为 HTTP+SSE（opencode serve），见 AGENT-PROTOCOLS.md" },
  grok: { provider: "grok-build", command: "grok", args: ["--no-auto-update", "agent", "--no-leader", "stdio"], taskId: "AGT-120", docs: "https://x.ai/cli" },
  hermes: { provider: "hermes", command: "hermes", args: ["acp"], taskId: "AGT-126", docs: "https://hermes-agent.nousresearch.com/docs/" },
  fx: { provider: "fx", command: "fx", args: ["acp"], taskId: "AGT-129", docs: "https://fx.sh/docs" },
  antigravity: { provider: "antigravity", command: "agy_acp_server", args: [], taskId: "AGT-119", docs: "https://antigravity.google/" },

  // ── 国产（官方 ACP 入口，CI 待复核；见 platform/docs/DOMESTIC-AGENTS.md）──
  qoder: { provider: "qoder", command: "qodercn", args: ["--acp"], taskId: "AGT-114", docs: "阿里云 Qoder 文档", domestic: true, note: "CI 1.1.85 实测 --help 未见 --acp；bin 名 qodercn/qoderclicn 都要探测" },
  codebuddy: { provider: "codebuddy", command: "codebuddy", args: ["--acp"], taskId: "AGT-117", docs: "腾讯云 CodeBuddy 文档", domestic: true, note: "CI 2.181.0 实测 --acp 与 --serve 均存在；--acp 为 stdio，--serve 为 HTTP+SSE" },
  kimi: { provider: "kimi", command: "kimi", args: ["acp"], taskId: "AGT-128", docs: "code.kimi.com/kimi-code", domestic: true, note: "CI 安装后 kimi 未在 PATH（安装目录待确认）；命令来自官方文档" },
  openclaw: { provider: "openclaw", command: "openclaw", args: ["acp"], taskId: "AGT-127", docs: "OpenClaw 文档", domestic: false, note: "开源项目，原 Clawdbot/Moltbot" },
  // TRAE（AGT-115）：企业版旗舰套餐才提供 CLI，暂不接入，见 DOMESTIC-AGENTS.md
  // WorkBuddy（AGT-116）：官方无 CLI/ACP/app-server，BLOCKED
});

export class AcpProfileError extends Error {
  constructor(message, details = {}) {
    super(message);
    this.name = "AcpProfileError";
    this.details = details;
  }
}

export function getAcpProfile(profileId) {
  const profile = ACP_PROFILES[profileId];
  if (!profile) throw new AcpProfileError(`unknown ACP profile: ${profileId}`, { profileId });
  return profile;
}

/** 用画像创建 AcpAdapter（命令/参数/提供方固定，能力仍由握手决定）。 */
export function createAcpProfileAdapter({ profileId, instanceId, environment = "host", ...overrides } = {}) {
  const profile = getAcpProfile(profileId);
  return new AcpAdapter({
    instanceId,
    environment,
    command: profile.command,
    args: profile.args,
    provider: profile.provider,
    ...overrides,
  });
}

/**
 * 把 ACP initialize 的 agentCapabilities 翻译成能力声明。
 * 只把「握手里明确声明」的项标 experimental，其余 unverified/false——不声明 supported。
 */
export function capabilitiesFromAcpHandshake(agentCapabilities = {}, { taskId = null } = {}) {
  const base = acpCapabilityDeclaration({ agentCapabilities });
  const sessionCaps = agentCapabilities.sessionCapabilities ?? {};
  const promptCaps = agentCapabilities.promptCapabilities ?? {};
  const mcpCaps = agentCapabilities.mcpCapabilities ?? {};
  const next = structuredClone(base);
  if (agentCapabilities.loadSession === true) next.session.resume = "experimental";
  if (sessionCaps.fork) next.thread.fork = "experimental";
  if (sessionCaps.resume) next.session.resume = "experimental";
  if (promptCaps.image) next.attachments.input = "experimental";
  if (promptCaps.embeddedContext) next.context.system = "experimental";
  if (mcpCaps.http || mcpCaps.sse) next.tools = "experimental";
  next.meta = { source: "acp-initialize", taskId };
  return next;
}

/** 从真实握手 fixture 读取 agentCapabilities（测试/矩阵用）。 */
export function agentCapabilitiesFromFixture(fixture) {
  const frames = fixture?.frames ?? [];
  const init = frames.find((frame) => frame?.id === 1 && frame?.result);
  const caps = init?.result?.agentCapabilities ?? {};
  return { capabilities: caps, agentInfo: init?.result?.agentInfo ?? null, authMethods: init?.result?.authMethods ?? [] };
}
