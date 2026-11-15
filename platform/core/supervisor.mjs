/**
 * CORE-109：Agent 进程监督器。
 *
 * 责任：spawn、临时环境清理、stdout/stderr 分离、退出/信号处理、超时终止、有界输出。
 * 明确边界：这是「进程」监督器，不理解 Agent 协议；协议事件由适配器负责。
 */
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import os from "node:os";
import path from "node:path";

import { makeId } from "../contracts/ids.mjs";
import { NotFoundError, ValidationError } from "./errors.mjs";

/** 默认从父进程环境里剔除的秘密项（避免 ambient credentials 被静默继承）。 */
const SECRET_ENV_RE = /(TOKEN|SECRET|PASSWORD|PASSWD|API[_-]?KEY|CREDENTIAL|AUTH|SESSION_KEY)/i;
const PRESERVE_ENV = new Set(["PATH", "HOME", "LANG", "LC_ALL", "SHELL", "USER", "TMPDIR", "TMP", "TEMP", "NODE_OPTIONS", "SystemRoot", "COMSPEC"]);

function cleanEnv(base, extra = {}, { stripSecrets = true } = {}) {
  const env = {};
  for (const [key, value] of Object.entries(base)) {
    if (value === undefined) continue;
    if (stripSecrets && !PRESERVE_ENV.has(key) && SECRET_ENV_RE.test(key)) continue;
    env[key] = value;
  }
  for (const [key, value] of Object.entries(extra)) {
    if (value === undefined) continue;
    env[key] = String(value);
  }
  return env;
}

/** 有界文本缓冲：超出上限丢弃最旧内容，并记录截断。 */
class BoundedBuffer {
  constructor(maxBytes = 64 * 1024) {
    this.maxBytes = maxBytes;
    this.text = "";
    this.bytes = 0;
    this.truncated = false;
  }

  push(chunk) {
    const str = typeof chunk === "string" ? chunk : String(chunk);
    this.text += str;
    this.bytes += Buffer.byteLength(str, "utf8");
    while (this.bytes > this.maxBytes && this.text.length > 0) {
      const excess = this.bytes - this.maxBytes;
      // 至少删掉超出部分，按字符逼近，避免切坏多字节。
      const dropChars = Math.max(1, Math.ceil(excess / 4));
      const dropped = this.text.slice(0, dropChars);
      this.text = this.text.slice(dropChars);
      this.bytes -= Buffer.byteLength(dropped, "utf8");
      if (this.bytes < 0) this.bytes = 0;
      this.truncated = true;
    }
  }

  reset() {
    this.text = "";
    this.bytes = 0;
    this.truncated = false;
  }
}

export class AgentSupervisor {
  #records = new Map();
  #closed = false;

  constructor({
    clock = () => new Date(),
    maxOutputBytes = 64 * 1024,
    killGraceMs = 2_000,
    defaultTimeoutMs = 0,
    baseEnv = process.env,
    stripSecrets = true,
    logger = null,
  } = {}) {
    this.clock = clock;
    this.maxOutputBytes = maxOutputBytes;
    this.killGraceMs = killGraceMs;
    this.defaultTimeoutMs = defaultTimeoutMs;
    this.baseEnv = baseEnv;
    this.stripSecrets = stripSecrets;
    this.logger = logger;
  }

  nowIso() {
    const d = this.clock();
    return (d instanceof Date ? d : new Date(d)).toISOString();
  }

  get size() {
    return this.#records.size;
  }

  list() {
    return [...this.#records.values()].map((rec) => this.#view(rec));
  }

  /**
   * 启动受监督子进程。
   * @returns {object} handle（含 stop/kill/wait/output/snapshot）
   */
  start({
    command,
    args = [],
    cwd = null,
    env = {},
    name = null,
    timeoutMs = this.defaultTimeoutMs,
    shell = false,
    tempEnv = true,
    onStdout = null,
    onStderr = null,
    onExit = null,
  } = {}) {
    if (this.#closed) throw new ValidationError("supervisor is closed");
    if (typeof command !== "string" || command.length === 0) throw new ValidationError("command is required");
    if (!Array.isArray(args)) throw new ValidationError("args must be an array");

    const record = {
      id: makeId("run"),
      name: name ?? command,
      command,
      args,
      cwd: cwd ?? process.cwd(),
      startedAt: this.nowIso(),
      startedAtMs: this.clock().getTime(),
      status: "running",
      pid: null,
      exitCode: null,
      signal: null,
      timedOut: false,
      stopReason: null,
      tempDir: null,
      stdout: new BoundedBuffer(this.maxOutputBytes),
      stderr: new BoundedBuffer(this.maxOutputBytes),
      child: null,
      timer: null,
      killTimer: null,
      exitPromise: null,
      resolveExit: null,
      onExit,
    };

    if (tempEnv) {
      record.tempDir = mkdtempSync(path.join(os.tmpdir(), "coda-agent-"));
      env = {
        ...env,
        TMPDIR: record.tempDir,
        TMP: record.tempDir,
        TEMP: record.tempDir,
      };
    }

    const child = spawn(command, args, {
      cwd: record.cwd,
      env: cleanEnv(this.baseEnv, env, { stripSecrets: this.stripSecrets }),
      stdio: ["ignore", "pipe", "pipe"],
      shell,
    });
    record.child = child;
    record.pid = child.pid ?? null;
    record.exitPromise = new Promise((resolve) => {
      record.resolveExit = resolve;
    });

    child.stdout?.setEncoding?.("utf8");
    child.stderr?.setEncoding?.("utf8");
    child.stdout?.on("data", (chunk) => {
      record.stdout.push(chunk);
      try {
        onStdout?.(chunk, record);
      } catch {
        // 回调异常不得破坏监督器
      }
    });
    child.stderr?.on("data", (chunk) => {
      record.stderr.push(chunk);
      try {
        onStderr?.(chunk, record);
      } catch {
        // ignore
      }
    });

    child.on("error", (err) => {
      record.spawnError = err.message;
      record.stderr.push(`[spawn-error] ${err.message}\n`);
      // spawn 失败时不会触发 exit；这里主动收尾。
      if (record.status === "running") {
        record.status = "exited";
        record.exitCode = record.exitCode ?? -1;
        record.signal = record.signal ?? null;
        this.#finish(record);
      }
    });

    child.on("exit", (code, signal) => {
      record.exitCode = code;
      record.signal = signal ?? null;
    });

    child.on("close", (code, signal) => {
      if (record.exitCode === null && code !== null) record.exitCode = code;
      if (record.signal === null && signal) record.signal = signal;
      if (record.status === "running") {
        record.status = "exited";
      }
      this.#finish(record);
    });

    if (Number.isFinite(timeoutMs) && timeoutMs > 0) {
      record.timer = setTimeout(() => {
        record.timedOut = true;
        record.stopReason = "timeout";
        this.stop(record, "SIGTERM");
      }, timeoutMs);
      record.timer.unref?.();
    }

    this.#records.set(record.id, record);
    return this.#handle(record);
  }

  #finish(record) {
    if (record.finished) return;
    record.finished = true;
    if (record.timer) {
      clearTimeout(record.timer);
      record.timer = null;
    }
    if (record.killTimer) {
      clearTimeout(record.killTimer);
      record.killTimer = null;
    }
    if (record.tempDir) {
      try {
        rmSync(record.tempDir, { recursive: true, force: true });
      } catch {
        // 临时目录清理失败不应影响退出记录
      }
      record.tempDir = null;
    }
    record.endedAt = this.nowIso();
    record.durationMs = Math.max(0, this.clock().getTime() - record.startedAtMs);
    try {
      record.onExit?.(this.#view(record));
    } catch {
      // ignore
    }
    record.resolveExit?.(this.#view(record));
    record.resolveExit = null;
  }

  #handle(record) {
    return {
      get id() {
        return record.id;
      },
      get pid() {
        return record.pid;
      },
      get name() {
        return record.name;
      },
      get status() {
        return record.status;
      },
      get exitCode() {
        return record.exitCode;
      },
      get signal() {
        return record.signal;
      },
      get timedOut() {
        return record.timedOut;
      },
      get stdout() {
        return record.stdout.text;
      },
      get stderr() {
        return record.stderr.text;
      },
      output: () => this.#view(record),
      snapshot: () => this.#view(record),
      stop: (signal = "SIGTERM") => this.stop(record, signal),
      kill: () => this.kill(record),
      wait: (opts) => this.wait(record, opts),
    };
  }

  #view(record) {
    return {
      id: record.id,
      name: record.name,
      command: record.command,
      args: [...record.args],
      cwd: record.cwd,
      pid: record.pid,
      status: record.status,
      exitCode: record.exitCode,
      signal: record.signal,
      timedOut: record.timedOut,
      stopReason: record.stopReason,
      startedAt: record.startedAt,
      endedAt: record.endedAt ?? null,
      durationMs: record.durationMs ?? null,
      stdout: record.stdout.text,
      stderr: record.stderr.text,
      stdoutTruncated: record.stdout.truncated,
      stderrTruncated: record.stderr.truncated,
      tempDir: record.tempDir,
      tempDirCleaned: record.tempDir === null,
      spawnError: record.spawnError ?? null,
    };
  }

  stop(recordOrId, signal = "SIGTERM") {
    const record = this.#resolve(recordOrId);
    if (record.status !== "running" || !record.child) return this.#view(record);
    if (!record.stopReason) record.stopReason = "requested";
    try {
      record.child.kill(signal);
    } catch {
      // 进程可能刚好退出
    }
    if (signal !== "SIGKILL") {
      // 宽限期内未退出则强杀，保证不会留下孤儿进程。
      record.killTimer = setTimeout(() => {
        if (record.status === "running") {
          try {
            record.child.kill("SIGKILL");
          } catch {
            // ignore
          }
        }
      }, this.killGraceMs);
      record.killTimer.unref?.();
    }
    return this.#view(record);
  }

  kill(recordOrId) {
    return this.stop(recordOrId, "SIGKILL");
  }

  /**
   * 等待退出；`timeoutMs` 到点后返回 status="running"（不抛出），调用方自行决定。
   */
  async wait(recordOrId, { timeoutMs = 0 } = {}) {
    const record = this.#resolve(recordOrId);
    if (record.status !== "running") return this.#view(record);
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
      await record.exitPromise;
      return this.#view(record);
    }
    let timer = null;
    try {
      await Promise.race([
        record.exitPromise,
        new Promise((resolve) => {
          timer = setTimeout(resolve, timeoutMs);
          timer.unref?.();
        }),
      ]);
    } finally {
      if (timer) clearTimeout(timer);
    }
    return this.#view(record);
  }

  #resolve(recordOrId) {
    if (recordOrId && typeof recordOrId === "object" && recordOrId.id && this.#records.has(recordOrId.id)) {
      return this.#records.get(recordOrId.id);
    }
    const record = this.#records.get(recordOrId);
    if (!record) throw new NotFoundError(`unknown supervised process: ${recordOrId}`, { id: recordOrId });
    return record;
  }

  /** 停止全部子进程并等待退出（核心优雅关闭用）。 */
  async shutdownAll({ signal = "SIGTERM", timeoutMs = 5_000 } = {}) {
    const records = [...this.#records.values()].filter((r) => r.status === "running");
    for (const record of records) this.stop(record, signal);
    const results = await Promise.all(records.map((record) => this.wait(record, { timeoutMs })));
    for (const record of this.#records.values()) this.#finish(record);
    this.#records.clear();
    return results;
  }

  async close() {
    if (this.#closed) return [];
    this.#closed = true;
    return this.shutdownAll();
  }
}
