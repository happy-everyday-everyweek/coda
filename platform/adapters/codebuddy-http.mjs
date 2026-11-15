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
    turn: {
      startEndEvents: openapiDiscovered ? exp : unk, // POST /jobs + GET /jobs/{id}/stream（规范已固定）
      interrupt: openapiDiscovered ? exp : unk, // POST /jobs/{id}/stop
      nativeSteer: openapiDiscovered ? exp : unk, // POST /jobs/{id}/reply
      stopThenAppend: unk,
      queue: unk,
      terminalTrust: unk,
    },
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
  constructor({ instanceId, environment = "host", command = "codebuddy", baseArgs = [], port = CODEBUDDY_HTTP_PROTOCOL.defaultPort, apiKey = null, workspace = null, spawnImpl = spawn, fetchImpl = globalThis.fetch, version = null, probeTimeoutMs = 15000, clock = () => new Date() } = {}) {
    super({ instanceId, environment, provider: "codebuddy", version, clock });
    this.command = command;
    this.baseArgs = baseArgs;
    this.port = port;
    this.apiKey = apiKey;
    this.workspace = workspace;
    this._jobs = new Map(); // coreSessionId → job data
    this._jobsBySessionId = new Map(); // coreSessionId → jobId
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

  _headers(extra = {}) {
    return {
      // CodeBuddy 要求该头，否则 403（CI 实测 /api/v1/health）
      "x-codebuddy-request": "1",
      ...(this.apiKey ? { Authorization: `Bearer ${this.apiKey}` } : {}),
      ...extra,
    };
  }

  async _request(method, path, { body = null, signal = null } = {}) {
    const res = await this.fetchImpl(`http://127.0.0.1:${this.port}${path}`, {
      method,
      headers: this._headers(body ? { "Content-Type": "application/json" } : {}),
      body: body === null ? undefined : JSON.stringify(body),
      signal: signal ?? AbortSignal.timeout(15000),
    });
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* 非 JSON */ }
    if (!res.ok) {
      throw new AdapterError("PROCESS_EXIT", `codebuddy HTTP ${method} ${path} → ${res.status}: ${text.slice(0, 300)}`, {
        details: { status: res.status, path },
      });
    }
    return json;
  }

  /** 列出智能体实例（GET /api/v1/jobs）。 */
  async listJobs() {
    const body = await this._request("GET", "/api/v1/jobs");
    return body?.data?.jobs ?? [];
  }

  /** 派发智能体（POST /api/v1/jobs，OpenAPI 已固定字段）。 */
  async dispatchJob({ prompt, cwd = null, agent = null, model = null, effort = null, permissionMode = null } = {}) {
    const body = await this._request("POST", "/api/v1/jobs", {
      body: {
        prompt: String(prompt ?? ""),
        ...(cwd ?? this.workspace ? { cwd: cwd ?? this.workspace } : {}),
        ...(agent ? { agent } : {}),
        ...(model ? { model } : {}),
        ...(effort ? { effort } : {}),
        ...(permissionMode ? { permissionMode } : {}),
      },
    });
    return body?.data ?? null;
  }

  /** 回放并尾随 transcript（GET /api/v1/jobs/{id}/stream，SSE event=message）。 */
  async streamJob(jobId, { onRecord, timeoutMs = 120000 } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    timer.unref?.();
    try {
      const res = await this.fetchImpl(`http://127.0.0.1:${this.port}/api/v1/jobs/${jobId}/stream`, {
        headers: this._headers({ Accept: "text/event-stream" }),
        signal: controller.signal,
      });
      if (!res.ok || !res.body) return { ok: false, reason: `HTTP ${res.status}` };
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let index;
        while ((index = buffer.indexOf("\n\n")) >= 0) {
          const block = buffer.slice(0, index).trim();
          buffer = buffer.slice(index + 2);
          const dataLines = block.split("\n").filter((line) => line.startsWith("data:"));
          if (dataLines.length === 0) continue;
          const payload = dataLines.map((line) => line.slice(5).trim()).join("\n");
          let record = null;
          try { record = JSON.parse(payload); } catch { record = { raw: payload.slice(0, 500) }; }
          onRecord?.(record);
        }
      }
      return { ok: true };
    } finally {
      clearTimeout(timer);
      controller.abort();
    }
  }

  async getJob(jobId) {
    const body = await this._request("GET", `/api/v1/jobs/${jobId}`);
    return body?.data ?? null;
  }

  async replyJob(jobId, text) {
    const body = await this._request("POST", `/api/v1/jobs/${jobId}/reply`, { body: { text: String(text ?? "") } });
    return body?.data ?? null;
  }

  async stopJob(jobId) {
    const body = await this._request("POST", `/api/v1/jobs/${jobId}/stop`);
    return body?.data ?? null;
  }

  /** Gateway/JSONL 记录 → 统一事件（形状未录制，未知记录只进诊断）。 */
  _mapRecord(record, sessionId) {
    if (!record || typeof record !== "object") return;
    const type = String(record.type ?? record.kind ?? "");
    const text = record.text ?? record.content ?? record.message;
    if (typeof text === "string" && text.length > 0 && /text|message|assistant|delta/i.test(type)) {
      this.emitEvent({ kind: "message", sessionId, payload: { role: "assistant", text, delta: /delta/i.test(type) } });
      return;
    }
    if (/tool/i.test(type)) {
      this.emitEvent({ kind: "tool", sessionId, payload: { tool: record } });
      return;
    }
    if (/error/i.test(type)) {
      this.emitEvent({ kind: "error", sessionId, payload: { message: String(text ?? record.error ?? "codebuddy error") } });
      return;
    }
    if (/result|done|complete|end/i.test(type)) {
      this.emitEvent({ kind: "status", sessionId, payload: { state: "idle", terminal: true } });
      return;
    }
    if (this.diagnostics.unknownFrames.length < 20) this.diagnostics.unknownFrames.push({ type, record: JSON.stringify(record).slice(0, 400) });
  }

  _blocked(operation) {
    return new AdapterError("BLOCKED", `codebuddy HTTP ${operation} 尚未按 OpenAPI 固定；先 discoverOpenApi() 并录制真实调用`, {
      details: { taskId: CODEBUDDY_HTTP_TASK_ID, evidence: CODEBUDDY_HTTP_PROTOCOL.evidence },
    });
  }

  async createSession({ sessionId } = {}) {
    this._jobs.set(sessionId ?? "", null);
    return { providerThreadId: null };
  }

  /** 一次 send = 派发 job 或对活会话回复，随后等待 job 进入终态。 */
  async send({ sessionId, text, model = null, effort = null, permissionMode = null, timeoutMs = 120000 }) {
    this._assertUsable();
    this.assertCapability("turn", "startEndEvents");
    const body = String(text ?? "");
    if (!body.trim()) throw new AdapterError("PROTOCOL_ERROR", "empty prompt");
    await this.discoverOpenApi();

    const existing = this._jobs.get(sessionId) ?? null;
    const existingId = existing?.id ?? existing?.jobId ?? this._jobsBySessionId.get(sessionId) ?? null;
    if (existingId) {
      await this.replyJob(existingId, body);
      this.emitEvent({ kind: "message", sessionId, payload: { role: "user", text: body, delta: false } });
      return { state: "succeeded", text: "", upstreamSessionId: existingId };
    }

    this.emitStatus(sessionId, "running", { phase: "job_dispatch" });
    const job = await this.dispatchJob({ prompt: body, model, effort, permissionMode });
    if (!job?.id) throw new AdapterError("PROTOCOL_ERROR", "codebuddy POST /api/v1/jobs returned no job id");
    this._jobs.set(sessionId, job);
    this._jobsBySessionId.set(sessionId, job.id);

    let textOut = "";
    const records = [];
    const originalEmit = this.emitEvent.bind(this);
    this.emitEvent = (event) => {
      if (event.kind === "message" && event.payload?.text) textOut += event.payload.text;
      return originalEmit(event);
    };
    try {
      await this.streamJob(job.id, {
        timeoutMs,
        onRecord: (record) => {
          records.push(record);
          this._mapRecord(record, sessionId);
        },
      });
    } finally {
      this.emitEvent = originalEmit;
    }

    const deadline = Date.now() + Math.max(1000, Math.min(timeoutMs, 10000));
    let finalJob = job;
    for (;;) {
      try {
        const current = await this.getJob(job.id);
        if (current) finalJob = current;
      } catch {
        /* 轮询失败不改变结论 */
      }
      const state = String(finalJob?.state ?? "");
      if (["done", "failed", "stopped"].includes(state)) break;
      if (Date.now() > deadline) break;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    const state = String(finalJob?.state ?? "unknown");
    this.emitStatus(sessionId, state === "done" ? "idle" : state === "working" ? "unknown" : state);
    if (state === "failed") {
      const message = String(finalJob?.detail ?? finalJob?.error ?? "codebuddy job failed");
      this.emitError(sessionId, new AdapterError("PROCESS_EXIT", message));
      return { state: "failed", text: textOut, error: { message }, upstreamSessionId: job.id };
    }
    if (state === "stopped") return { state: "stopped", text: textOut, upstreamSessionId: job.id };
    if (state === "unknown" || state === "working") return { state: "unknown", text: textOut, upstreamSessionId: job.id };
    return { state: "succeeded", text: textOut, upstreamSessionId: job.id };
  }

  async steer({ sessionId, text }) {
    this.assertCapability("turn", "nativeSteer");
    const jobId = this._jobsBySessionId.get(sessionId) ?? null;
    if (!jobId) throw new AdapterError("CONFLICT", "no active codebuddy job to steer");
    return this.replyJob(jobId, text);
  }

  async interrupt({ sessionId }) {
    this.assertCapability("turn", "interrupt");
    const jobId = this._jobsBySessionId.get(sessionId) ?? null;
    if (!jobId) return { stopped: false, reason: "no-active-job" };
    await this.stopJob(jobId);
    return { stopped: true, trust: "upstream-confirmed" };
  }

  async resume() {
    await this.discoverOpenApi();
    throw new AdapterError("BLOCKED", "codebuddy POST /api/v1/jobs/resume 端点已发现但请求体未固定；等录制后实现");
  }

  async fork() {
    throw new AdapterError("UNSUPPORTED_CAPABILITY", "codebuddy HTTP fork not documented");
  }

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
