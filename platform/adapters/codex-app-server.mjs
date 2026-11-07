/**
 * Codex app-server 适配器（AGT-112）。
 *
 * 已在 CI 中真实验证：`codex app-server` 接受 newline JSON-RPC 的 `initialize` 请求并返回
 * `{userAgent, codexHome, platformFamily, platformOs}`（证据见 fixtures/adapters/probes/codex.json）。
 *
 * 除握手外的会话方法（session/new、turn/send、cancel、approval…）尚未从协议 schema 固定，
 * 因此一律显式抛 BLOCKED —— 不猜测方法名、不伪造“已支持”。
 */
import { spawn } from "node:child_process";

import { AdapterError, BaseAdapter, sideEffectFreeProbe } from "./base.mjs";
import { JsonRpcConnection } from "./acp.mjs";
import { resolveExecutable, runProcess } from "./runtime.mjs";

export const CODEX_APP_SERVER_TASK_ID = "AGT-112";

export const CODEX_APP_SERVER_PROTOCOL = Object.freeze({
  name: "codex-app-server",
  transport: "stdio",
  framing: "newline-json-rpc",
  invocation: ["app-server"],
  handshake: "initialize",
  evidence: "platform/fixtures/adapters/probes/codex.json",
  schemaCommands: ["codex app-server generate-json-schema", "codex app-server generate-ts"],
});

/** 只有 initialize 握手是已验证事实；其余保持 unverified。 */
export function codexAppServerCapabilityDeclaration({ handshakeOk = false } = {}) {
  const unk = "unverified";
  return {
    session: {
      multiThread: unk,
      modelSwitch: unk,
      permissionModeSwitch: unk,
      resume: handshakeOk ? unk : false,
      requestDurability: unk,
    },
    thread: { empty: unk, snapshot: unk, fork: unk, fromTurn: unk, nativeId: handshakeOk ? unk : false },
    turn: {
      startEndEvents: unk,
      interrupt: unk,
      nativeSteer: unk,
      stopThenAppend: false,
      queue: unk,
      terminalTrust: unk,
    },
    streaming: { text: unk, reasoning: unk, toolOutput: unk, planText: unk, messageCompleted: unk },
    tools: unk,
    approvals: { command: unk, fileRead: unk, fileWrite: unk, patch: unk, subagent: unk, nativeRequestId: unk },
    plan: unk,
    subagent: unk,
    context: { system: unk, summary: unk, handoffFull: unk, handoffIncremental: unk },
    checkpoint: { filesystem: unk, sessionRollback: unk, consistent: unk },
    identity: { thread: unk, turn: unk, item: unk, request: unk },
    attachments: { input: unk, output: unk },
  };
}

export class CodexAppServerAdapter extends BaseAdapter {
  constructor({
    instanceId,
    environment = "host",
    command = "codex",
    baseArgs = [],
    spawnImpl = spawn,
    version = null,
    handshakeTimeoutMs = 20000,
    probeTimeoutMs = 15000,
    clock = () => new Date(),
  } = {}) {
    super({ instanceId, environment, provider: "codex", version, clock });
    this.command = command;
    this.baseArgs = baseArgs;
    this.spawnImpl = spawnImpl;
    this.handshakeTimeoutMs = handshakeTimeoutMs;
    this.probeTimeoutMs = probeTimeoutMs;
    this.probing = { path: null, appServerDetected: false, helpHead: null };
    this._conn = null;
    this._child = null;
    this._handshake = null;
    this._connecting = null;
  }

  async probe() {
    const executable = resolveExecutable(this.command);
    if (!executable) {
      this.version = "0.0.0-unknown";
      this.declareCapabilities(codexAppServerCapabilityDeclaration());
      return sideEffectFreeProbe({
        installed: false,
        version: null,
        loggedIn: null,
        path: null,
        details: { taskId: CODEX_APP_SERVER_TASK_ID, reason: "not-found", command: this.command, blocked: true },
      });
    }
    const versionRun = await runProcess({
      command: executable,
      args: [...this.baseArgs, "--version"],
      timeoutMs: this.probeTimeoutMs,
      isolate: false,
    }).catch(() => null);
    const helpRun = await runProcess({
      command: executable,
      args: [...this.baseArgs, "--help"],
      timeoutMs: this.probeTimeoutMs,
      isolate: false,
    }).catch(() => null);
    const helpText = `${helpRun?.stdout ?? ""}\n${helpRun?.stderr ?? ""}`;
    const appServerDetected = /app-server/.test(helpText);
    this.version = (versionRun?.stdout ?? "").trim().replace(/^codex-cli\s+/, "") || "0.0.0-unknown";
    this.declareCapabilities(codexAppServerCapabilityDeclaration({ handshakeOk: false }));
    this.probing = { path: executable, appServerDetected, helpHead: helpText.slice(0, 400) };
    return sideEffectFreeProbe({
      installed: true,
      version: this.version,
      loggedIn: null,
      path: executable,
      details: {
        taskId: CODEX_APP_SERVER_TASK_ID,
        protocol: CODEX_APP_SERVER_PROTOCOL.name,
        appServerDetected,
        note: "probe 不启动 app-server；握手由 connect() 在真实会话前执行",
      },
    });
  }

  /** 建立 app-server 连接并完成 initialize 握手（可重复调用，结果缓存）。 */
  async connect() {
    this._assertUsable();
    if (this._handshake) return this._handshake;
    if (this._connecting) return this._connecting;
    this._connecting = (async () => {
      const executable = resolveExecutable(this.command);
      if (!executable) throw new AdapterError("NOT_INSTALLED", `${this.command} not found`);
      const child = this.spawnImpl(executable, [...this.baseArgs, "app-server"], {
        stdio: ["pipe", "pipe", "pipe"],
        env: { ...process.env, NO_COLOR: "1", TERM: "dumb" },
      });
      this._child = child;
      this._conn = new JsonRpcConnection({ child, label: "codex-app-server", requireJsonrpc: false });
      const result = await this._conn.request(
        "initialize",
        {
          clientInfo: { name: "coda-core", version: "0.1.0" },
          capabilities: {},
        },
        { timeoutMs: this.handshakeTimeoutMs },
      );
      this._handshake = result ?? {};
      this.declareCapabilities(codexAppServerCapabilityDeclaration({ handshakeOk: true }));
      return this._handshake;
    })().finally(() => {
      this._connecting = null;
    });
    return this._connecting;
  }

  _blocked(operation) {
    return new AdapterError(
      "BLOCKED",
      `codex app-server ${operation} 尚未固定协议方法；先跑 generate-json-schema 并录制真实会话`,
      { details: { taskId: CODEX_APP_SERVER_TASK_ID, evidence: CODEX_APP_SERVER_PROTOCOL.evidence } },
    );
  }

  async createSession() {
    await this.connect();
    throw this._blocked("createSession");
  }
  async send() {
    await this.connect();
    throw this._blocked("send");
  }
  async steer() {
    throw this._blocked("steer");
  }
  async interrupt() {
    throw this._blocked("interrupt");
  }
  async resume() {
    throw this._blocked("resume");
  }
  async fork() {
    throw this._blocked("fork");
  }
  async dispose() {
    try {
      this._conn?.close?.();
    } catch {
      /* ignore */
    }
    try {
      this._child?.kill?.("SIGTERM");
    } catch {
      /* ignore */
    }
    this._conn = null;
    this._child = null;
    this._handshake = null;
    await super.dispose();
  }
}

export function createCodexAppServerAdapter(options = {}) {
  return new CodexAppServerAdapter(options);
}
