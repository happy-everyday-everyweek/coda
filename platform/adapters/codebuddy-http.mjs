/**
 * CodeBuddy HTTP API（Beta）适配器骨架（AGT-117）。
 *
 * CI 实测（2.181.0，无凭据）：
 *   `codebuddy --serve --port 8799 --session-id probe`
 *   - GET /api/openapi.json → 200（公开 OpenAPI 3.1 规范）
 *   - GET /api/v1/health    → 403 `Missing required header: x-codebuddy-request`
 *   - GET /api/docs         → 200 Swagger UI
 *
 * 本模块只做「服务启动 + 规范发现 + capability 诚实降级」；会话方法等 OpenAPI 固定后再实现，
 * 当前一律抛 BLOCKED（不猜端点、不伪造成功）。证据见 fixtures/adapters/probes/codebuddy.json。
 */
import { spawn } from "node:child_process";

import { AdapterError, BaseAdapter, sideEffectFreeProbe } from "./base.mjs";
import { resolveExecutable, runProcess } from "./runtime.mjs";

export const CODEBUDDY_HTTP_TASK_ID = "AGT-117";
export const CODEBUDDY_HTTP_PROTOCOL = Object.freeze({
  name: "codebuddy-http",
  invocation: ["--serve"],
  defaultPort: 8799,
  health: "/api/v1/health",
  openapi: "/api/openapi.json",
  docs: "/api/docs",
  requiredHeaders: { health: "x-codebuddy-request" },
  evidence: "platform/fixtures/adapters/probes/codebuddy.json",
});

export function codebuddyHttpCapabilityDeclaration({ openapiDiscovered = false } = {}) {
  const unk = "unverified";
  const exp = "experimental";
  return {
    session: { multiThread: unk, modelSwitch: unk, permissionModeSwitch: unk, resume: unk, requestDurability: unk },
    thread: { empty: unk, snapshot: false, fork: unk, fromTurn: unk, nativeId: unk },
    turn: { startEndEvents: openapiDiscovered ? exp : unk, interrupt: unk, nativeSteer: unk, stopThenAppend: unk, queue: unk, terminalTrust: unk },
    streaming: { text: openapiDiscovered ? exp : unk, reasoning: unk, toolOutput: unk, planText: unk, messageCompleted: unk },
    tools: unk,
    approvals: { command: unk, fileRead: unk, fileWrite: unk, patch: unk, subagent: unk, nativeRequestId: unk },
    plan: unk,
    subagent: unk,
    context: { system: unk, summary: unk, handoffFull: false, handoffIncremental: false },
    checkpoint: { filesystem: false, sessionRollback: false, consistent: false },
    identity: { thread: unk, turn: unk, item: unk, request: unk },
    attachments: { input: unk, output: unk },
  };
}

export class CodeBuddyHttpAdapter extends BaseAdapter {
  constructor({ instanceId, environment = "host", command = "codebuddy", baseArgs = [], port = CODEBUDDY_HTTP_PROTOCOL.defaultPort, spawnImpl = spawn, fetchImpl = globalThis.fetch, version = null, probeTimeoutMs = 15000, clock = () => new Date() } = {}) {
    super({ instanceId, environment, provider: "codebuddy", version, clock });
    this.command = command;
    this.baseArgs = baseArgs;
    this.port = port;
    this.spawnImpl = spawnImpl;
    this.fetchImpl = fetchImpl;
    this.probeTimeoutMs = probeTimeoutMs;
    this.probing = { path: null, serveDetected: false, openapiDiscovered: false };
    this._child = null;
    this.spec = null;
  }

  async probe() {
    const executable = resolveExecutable(this.command);
    if (!executable) {
      this.version = "0.0.0-unknown";
      this.declareCapabilities(codebuddyHttpCapabilityDeclaration());
      return sideEffectFreeProbe({ installed: false, version: null, loggedIn: null, path: null, details: { taskId: CODEBUDDY_HTTP_TASK_ID, reason: "not-found", command: this.command, blocked: true } });
    }
    const versionRun = await runProcess({ command: executable, args: [...this.baseArgs, "--version"], timeoutMs: this.probeTimeoutMs, isolate: false }).catch(() => null);
    const helpRun = await runProcess({ command: executable, args: [...this.baseArgs, "--help"], timeoutMs: this.probeTimeoutMs, isolate: false }).catch(() => null);
    const helpText = `${helpRun?.stdout ?? ""}\n${helpRun?.stderr ?? ""}`;
    const serveDetected = helpText.includes("--serve");
    this.version = (versionRun?.stdout ?? "").trim() || "0.0.0-unknown";
    this.declareCapabilities(codebuddyHttpCapabilityDeclaration({ openapiDiscovered: serveDetected }));
    this.probing = { path: executable, serveDetected, openapiDiscovered: false };
    return sideEffectFreeProbe({ installed: true, version: this.version, loggedIn: null, path: executable, details: { taskId: CODEBUDDY_HTTP_TASK_ID, protocol: CODEBUDDY_HTTP_PROTOCOL.name, serveDetected, note: "probe 只跑 --version/--help，不启动服务" } });
  }

  /** 启动 --serve 并抓取 OpenAPI 规范（只读发现，不做业务调用）。 */
  async discoverOpenApi({ waitMs = 12000 } = {}) {
    this._assertUsable();
    if (this.spec) return this.spec;
    const executable = resolveExecutable(this.command);
    if (!executable) throw new AdapterError("NOT_INSTALLED", `${this.command} not found`);
    this._child = this.spawnImpl(executable, [...this.baseArgs, "--serve", "--port", String(this.port), "--session-id", `coda-probe-${Date.now()}`], {
      stdio: ["ignore", "pipe", "pipe"],
      detached: true,
    });
    const deadline = Date.now() + waitMs;
    let lastError = null;
    for (;;) {
      try {
        const res = await this.fetchImpl(`http://127.0.0.1:${this.port}${CODEBUDDY_HTTP_PROTOCOL.openapi}`, { signal: AbortSignal.timeout(3000) });
        if (res.ok) {
          this.spec = { openapi: await res.clone().json().catch(() => null), fetchedAt: new Date().toISOString() };
          this.probing.openapiDiscovered = true;
          this.declareCapabilities(codebuddyHttpCapabilityDeclaration({ openapiDiscovered: true }));
          return this.spec;
        }
        lastError = `HTTP ${res.status}`;
      } catch (err) {
        lastError = err?.message ?? String(err);
      }
      if (Date.now() > deadline) throw new AdapterError("TIMEOUT", `codebuddy openapi not ready: ${lastError}`);
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }

  _blocked(operation) {
    return new AdapterError("BLOCKED", `codebuddy HTTP ${operation} 尚未按 OpenAPI 固定；先 discoverOpenApi() 并录制真实调用`, {
      details: { taskId: CODEBUDDY_HTTP_TASK_ID, evidence: CODEBUDDY_HTTP_PROTOCOL.evidence },
    });
  }

  async createSession() {
    await this.discoverOpenApi();
    throw this._blocked("createSession");
  }
  async send() {
    await this.discoverOpenApi();
    throw this._blocked("send");
  }
  async steer() { throw this._blocked("steer"); }
  async interrupt() { throw this._blocked("interrupt"); }
  async resume() { throw this._blocked("resume"); }
  async fork() { throw this._blocked("fork"); }

  async dispose() {
    try { if (this._child?.pid) process.kill(-this._child.pid, "SIGTERM"); } catch { try { this._child?.kill?.("SIGKILL"); } catch { /* ignore */ } }
    this._child = null;
    this.spec = null;
    await super.dispose();
  }
}

export function createCodeBuddyHttpAdapter(options = {}) {
  return new CodeBuddyHttpAdapter(options);
}
