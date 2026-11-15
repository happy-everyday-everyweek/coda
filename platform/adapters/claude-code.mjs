/**
 * Claude Code 适配器骨架（AGT-111）——**未实现，BLOCKED**。
 *
 * 只做三件事：
 *   1. probe()：PATH 查找 + `claude --version` + `claude --help` 里检测 stream-json 能力，
 *      全程无副作用（不启动会话、不触发登录、不跑 Hooks）。
 *   2. 能力声明：全部 unverified —— 本仓库没有任何真实 Claude Code 二进制/录制证据。
 *   3. 其余入口显式抛 BLOCKED，绝不伪造“已支持”。
 *
 * 证据与下一步见 platform/adapters/BLOCKED.md。
 */
import { AdapterError, BaseAdapter, sideEffectFreeProbe } from "./base.mjs";
import { resolveExecutable, runProcess } from "./runtime.mjs";

export const CLAUDE_CODE_TASK_ID = "AGT-111";

export const CLAUDE_CODE_PROTOCOL = Object.freeze({
  name: "stream-json",
  transport: "stdio",
  /** 官方 CLI 的流式 JSON 输出形态；实际参数需按版本核对。 */
  invocation: ["--print", "--output-format", "stream-json", "--verbose"],
  detectFlags: ["--output-format", "stream-json"],
  docs: "https://docs.anthropic.com/en/docs/claude-code",
});

export const CLAUDE_CODE_BLOCKED_EVIDENCE = Object.freeze({
  status: "blocked",
  reason: "本仓库没有可复现的真实 Claude Code 二进制与协议录制；只完成 probe/协议探测骨架",
  commands: ["which claude", "claude --version", "claude --help"],
  expectedProbe: "claude --version 输出语义版本；claude --help 含 --output-format 与 stream-json",
  nextSteps: [
    "在受控机器上安装固定版本 Claude Code 并记录版本号",
    "录制一次真实 stream-json 会话，脱敏后放入 platform/fixtures/adapters/",
    "验证恢复、审批、提问、子 Agent 帧过滤后再逐项改能力声明",
  ],
  doc: "platform/adapters/BLOCKED.md",
});

/** 全部 unverified：没有证据就不声明支持（SPEC 8 / TODO 5.4）。 */
export function claudeCodeCapabilityDeclaration({ protocolDetected = false } = {}) {
  const unknown = "unverified";
  return {
    session: {
      multiThread: protocolDetected ? unknown : false,
      modelSwitch: unknown,
      permissionModeSwitch: unknown,
      resume: unknown,
      requestDurability: unknown,
    },
    thread: { empty: unknown, snapshot: false, fork: false, fromTurn: unknown, nativeId: unknown },
    turn: {
      startEndEvents: unknown,
      interrupt: unknown,
      nativeSteer: false,
      stopThenAppend: unknown,
      queue: unknown,
      terminalTrust: unknown,
    },
    streaming: {
      text: protocolDetected ? unknown : false,
      reasoning: unknown,
      toolOutput: unknown,
      planText: unknown,
      messageCompleted: unknown,
    },
    tools: unknown,
    approvals: {
      command: unknown,
      fileRead: unknown,
      fileWrite: unknown,
      patch: unknown,
      subagent: unknown,
      nativeRequestId: unknown,
    },
    plan: unknown,
    subagent: unknown,
    context: { system: unknown, summary: unknown, handoffFull: false, handoffIncremental: false },
    checkpoint: { filesystem: false, sessionRollback: false, consistent: false },
    identity: { thread: unknown, turn: unknown, item: unknown, request: unknown },
    attachments: { input: unknown, output: unknown },
  };
}

export class ClaudeCodeAdapter extends BaseAdapter {
  constructor({
    instanceId,
    environment = "host",
    command = "claude",
    version = null,
    env = null,
    pathAllowlist = null,
    probeTimeoutMs = 5000,
    clock = () => new Date(),
  } = {}) {
    super({ instanceId, environment, provider: "claude-code", version, clock });
    this.command = command;
    this.env = env;
    this.pathAllowlist = pathAllowlist;
    this.probeTimeoutMs = probeTimeoutMs;
    this.probing = { path: null, protocolDetected: false, helpText: null };
  }

  async probe() {
    const executable = resolveExecutable(this.command, {
      env: this.env ?? process.env,
      pathAllowlist: this.pathAllowlist,
    });
    if (!executable) {
      this.version = "0.0.0-unknown";
      this.declareCapabilities(claudeCodeCapabilityDeclaration());
      return sideEffectFreeProbe({
        installed: false,
        version: null,
        loggedIn: null,
        path: null,
        details: {
          taskId: CLAUDE_CODE_TASK_ID,
          reason: "not-found",
          command: this.command,
          blocked: true,
          doc: CLAUDE_CODE_BLOCKED_EVIDENCE.doc,
        },
      });
    }

    let versionProbe = null;
    let helpProbe = null;
    try {
      versionProbe = await runProcess({
        command: executable,
        args: ["--version"],
        env: this.env,
        timeoutMs: this.probeTimeoutMs,
        maxOutputBytes: 16 * 1024,
      });
      helpProbe = await runProcess({
        command: executable,
        args: ["--help"],
        env: this.env,
        timeoutMs: this.probeTimeoutMs,
        maxOutputBytes: 64 * 1024,
      });
    } catch {
      /* 探测失败按未验证处理，不抛错 */
    }

    const rawVersion = `${versionProbe?.stdout ?? ""}${versionProbe?.stderr ?? ""}`.trim();
    const helpText = `${helpProbe?.stdout ?? ""}${helpProbe?.stderr ?? ""}`;
    const match = /(\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?)/.exec(rawVersion);
    this.version = match?.[1] ?? "unknown";
    const flags = Object.fromEntries(CLAUDE_CODE_PROTOCOL.detectFlags.map((f) => [f, helpText.includes(f)]));
    const protocolDetected = Object.values(flags).every(Boolean);
    this.probing = { path: executable, protocolDetected, helpText: helpText.slice(0, 512) };
    this.declareCapabilities(claudeCodeCapabilityDeclaration({ protocolDetected }));

    return sideEffectFreeProbe({
      installed: true,
      version: this.version,
      loggedIn: null,
      path: executable,
      details: {
        taskId: CLAUDE_CODE_TASK_ID,
        protocol: { name: CLAUDE_CODE_PROTOCOL.name, detected: protocolDetected, flags },
        verified: false,
        blocked: true,
        reason: "no real session recorded; capability declaration stays unverified",
        doc: CLAUDE_CODE_BLOCKED_EVIDENCE.doc,
      },
    });
  }

  _blocked(operation) {
    throw new AdapterError(
      "BLOCKED",
      `${operation} is NOT implemented for Claude Code (AGT-111 BLOCKED): see platform/adapters/BLOCKED.md`,
      { details: { taskId: CLAUDE_CODE_TASK_ID, doc: CLAUDE_CODE_BLOCKED_EVIDENCE.doc } },
    );
  }

  async createSession() {
    this._blocked("createSession");
  }

  async send() {
    this._blocked("send");
  }

  async steer() {
    this._blocked("steer");
  }

  async interrupt() {
    this._blocked("interrupt");
  }

  async resume() {
    this._blocked("resume");
  }

  async fork() {
    this._blocked("fork");
  }
}

export function createClaudeCodeAdapter(options = {}) {
  return new ClaudeCodeAdapter(options);
}
