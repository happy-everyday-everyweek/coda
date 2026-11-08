/**
 * headless CLI 画像（Claude Code 同族协议）。
 *
 * 依据 CI 实测（2026-11-07）：
 *   - claude 2.1.319：`-p/--print` + `--output-format/--input-format stream-json` + `--resume/--session-id`
 *   - qoder-cn 1.1.85（`qodercn`）：有 `-p`、`--output-format`、`--session-id`、`--resume`、`--permission-mode`
 *     （CI 未见 `--acp`，ACP 入口待更高版本/企业版复核）
 *   - codebuddy 2.181.0：有 `-p`、`--output-format/--input-format stream-json`、`--resume`、
 *     `--permission-mode`，另有 `--acp` 与 `--serve`（HTTP+SSE）
 *
 * 协议形状只对 Claude 有真实录制；其余按「同族 CLI」假设，能力声明仍为 experimental/unverified，
 * 成功回合必须等各自真实录制后才可升级（SPEC 8/TODO 5.4）。
 */
import { ClaudeStreamJsonAdapter } from "./claude-stream-json.mjs";

export const HEADLESS_PROFILES = Object.freeze({
  claude: {
    taskId: "AGT-111",
    provider: "claude-code",
    command: "claude",
    baseArgs: [],
    evidence: "fixtures/adapters/claude-stream-json-noauth.fixture.json（真实录制）",
    protocolConfirmed: true,
  },
  qoder: {
    taskId: "AGT-114",
    provider: "qoder",
    command: "qodercn",
    baseArgs: [],
    evidence: "fixtures/adapters/probes/qoder-cn.json（CI 版本与入口）",
    protocolConfirmed: false,
    note: "ACP 入口未在 1.1.85 的 --help 中出现；如需 ACP 需按官方文档升级/企业版复核",
  },
  codebuddy: {
    taskId: "AGT-117",
    provider: "codebuddy",
    command: "codebuddy",
    baseArgs: [],
    evidence: "fixtures/adapters/codebuddy-headless-stream-json.fixture.json（真实帧：与 Claude 同族）",
    protocolConfirmed: true,
    note: "另有 --acp（stdio）与 --serve（HTTP+SSE ACP），ACP 路径见 acp-profiles.mjs",
  },
});

export function getHeadlessProfile(profileId) {
  const profile = HEADLESS_PROFILES[profileId];
  if (!profile) throw new Error(`unknown headless profile: ${profileId}`);
  return profile;
}

/** 用画像创建 Claude 同族 stream-json 适配器（命令/参数/提供方按画像）。 */
export function createHeadlessProfileAdapter({ profileId, instanceId, environment = "host", ...overrides } = {}) {
  const profile = getHeadlessProfile(profileId);
  return new ClaudeStreamJsonAdapter({
    instanceId,
    environment,
    provider: profile.provider,
    taskId: profile.taskId,
    command: profile.command,
    baseArgs: profile.baseArgs,
    ...overrides,
  });
}
