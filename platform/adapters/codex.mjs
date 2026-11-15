/**
 * Codex 适配器骨架（AGT-112）——**未实现，BLOCKED**。
 *
 * Codex 的可编程入口是 app-server（JSON-RPC over stdio），不是一次性 CLI 文本输出。
 * 这里只做 probe + 协议探测 + 能力声明；没有真实二进制与录制，一律 unverified。
 *
 * 证据与下一步见 platform/adapters/BLOCKED.md。
 */
import { AdapterError, BaseAdapter, sideEffectFreeProbe } from "./base.mjs";
import { resolveExecutable, runProcess } from "./runtime.mjs";

export const CODEX_TASK_ID = "AGT-112";

export const CODEX_PROTOCOL = Object.freeze({
  name: "app-server",
  transport: "jsonrpc-stdio",
  invocation: ["app-server"],
  detectFlags: ["app-server"],
  docs: "https://github.com/openai/codex",
});

export const CODEX_BLOCKED_EVIDENCE = Object.freeze({
  status: "blocked",
  reason: "本仓库没有可复现的真实 Codex 二进制与 app-server 协议录制；只完成 probe/协议探测骨架",
  commands: ["which codex", "codex --version", "codex --help"],
  expectedProbe: "codex --version 输出语义版本；codex --help 含 app-server 子命令",
  nextSteps: [
    "安装固定版本 Codex 并记录版本号",
    "用 app-server 建立一次真实会话，录制 initialize / 回合 / 审批原始帧",
    "脱敏后转成统一事件 fixture，再逐项改能力声明",
  ],
  doc: "platform/adapters/BLOCKED.md",
});

/** 全部 unverified：没有证据就不声明支持。 */
export function codexCapabilityDeclaration({ protocolDetected = false } = {}) {
  const unknown = "unverified";
  return {
    session: {
      multiThread: protocolDetected ? unknown : false,
      modelSwitch: unknown,
      permissionModeSwitch: unknown,
      resume: unknown,
      requestDurability: unknown,
    },
    thread: { empty: unknown, snapshot: unknown, fork: unknown, fromTurn: unknown, nativeId: unknown },
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
    context: { system: unknown, summary: unknown, handoffFull: unknown, handoffIncremental: unknown },
    checkpoint: { filesystem: unknown, sessionRollback: unknown, consistent: false },
    identity: { thread: unknown, turn: unknown, item: unknown, request: unknown },
    attachments: { input: unknown, output: unknown },
  };
}

export class CodexAdapter extends BaseAdapter {
  constructor({
    instanceId,
    environment = "host",
    command = "codex",
    version = null,
    env = null,
    pathAllowlist = null,
    probeTimeoutMs = 5000,
    clock = () => new Date(),
  } = {}) {
    super({ instanceId, environment, provider: "codex", version, clock });
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
      this.declareCapabilities(codexCapabilityDeclaration());
      return sideEffectFreeProbe({
        installed: false,
        version: null,
        loggedIn: null,
        path: null,
        details: {
          taskId: CODEX_TASK_ID,
          reason: "not-found",
          command: this.command,
          blocked: true,
          doc: CODEX_BLOCKED_EVIDENCE.doc,
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
    const flags = Object.fromEntries(CODEX_PROTOCOL.detectFlags.map((f) => [f, helpText.includes(f)]));
    const protocolDetected = Object.values(flags).every(Boolean);
    this.probing = { path: executable, protocolDetected, helpText: helpText.slice(0, 512) };
    this.declareCapabilities(codexCapabilityDeclaration({ protocolDetected }));

    return sideEffectFreeProbe({
      installed: true,
      version: this.version,
      loggedIn: null,
      path: executable,
      details: {
        taskId: CODEX_TASK_ID,
        protocol: { name: CODEX_PROTOCOL.name, detected: protocolDetected, flags },
        verified: false,
        blocked: true,
        reason: "no real app-server session recorded; capability declaration stays unverified",
        doc: CODEX_BLOCKED_EVIDENCE.doc,
      },
    });
  }

  _blocked(operation) {
    throw new AdapterError(
      "BLOCKED",
      `${operation} is NOT implemented for Codex (AGT-112 BLOCKED): see platform/adapters/BLOCKED.md`,
      { details: { taskId: CODEX_TASK_ID, doc: CODEX_BLOCKED_EVIDENCE.doc } },
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

export function createCodexAdapter(options = {}) {
  return new CodexAdapter(options);
}
