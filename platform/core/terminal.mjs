/**
 * CORE-111：终端服务（参考实现）。
 *
 * 重要降级说明：本实现**没有真 PTY**。Node 标准库不提供 PTY，而零外部依赖约束下不引入
 * `node-pty`，因此这里用子进程管道（pipe）模拟：
 * - 可写 stdin、分离读取 stdout/stderr、可回放有界输出、可 detach/重连（进程不退出）。
 * - `resize(cols, rows)` **明确返回 unsupported**，不会假装已经改变上游窗口大小。
 * - 全屏 TUI 程序、依赖 `isatty()` 的行为、Ctrl-C 等信号语义与真 PTY 不同，属于已知限制。
 *
 * 容量策略：输出保存在环形缓冲中，超过 `maxBytes` 丢弃最旧数据并返回缺口信息，
 * 客户端可从 `nextOffset` 续读，不会无界占用内存。
 */
import { spawn } from "node:child_process";

import { makeId } from "../contracts/ids.mjs";
import { ClosedError, ConflictError, NotFoundError, ValidationError } from "./errors.mjs";

const DEFAULT_SHELL = process.platform === "win32" ? "cmd.exe" : process.env.SHELL || "/bin/sh";

function byteLen(str) {
  return Buffer.byteLength(str, "utf8");
}

export class TerminalService {
  #records = new Map();
  #closed = false;

  constructor({
    clock = () => new Date(),
    maxBytes = 256 * 1024,
    defaultShell = DEFAULT_SHELL,
    baseEnv = process.env,
    killGraceMs = 2_000,
  } = {}) {
    this.clock = clock;
    this.maxBytes = maxBytes;
    this.defaultShell = defaultShell;
    this.baseEnv = baseEnv;
    this.killGraceMs = killGraceMs;
  }

  nowIso() {
    const d = this.clock();
    return (d instanceof Date ? d : new Date(d)).toISOString();
  }

  capabilities() {
    return {
      pty: false,
      resize: "unsupported",
      replay: true,
      detach: true,
      reason: "no PTY backend available in zero-dependency Node runtime; using pipes",
    };
  }

  list() {
    return [...this.#records.values()].map((r) => this.#view(r));
  }

  /**
   * 打开终端（不要求真 PTY）。
   * @returns {object} 终端视图
   */
  open({
    sessionId = null,
    cwd = process.cwd(),
    command = null,
    args = [],
    env = {},
    cols = 80,
    rows = 24,
    name = null,
  } = {}) {
    if (this.#closed) throw new ClosedError("terminal service is closed");
    if (typeof cwd !== "string" || cwd.length === 0) throw new ValidationError("cwd is required");
    if (!Array.isArray(args)) throw new ValidationError("args must be an array");

    const record = {
      id: makeId("run"),
      sessionId,
      name: name ?? (command ?? this.defaultShell),
      cwd,
      cols,
      rows,
      startedAt: this.nowIso(),
      status: "running",
      exitCode: null,
      signal: null,
      pid: null,
      child: null,
      stdinClosed: false,
      attached: true,
      detachCount: 0,
      // 环形缓冲：chunks 保存 Buffer，offset 为全局字节偏移。
      chunks: [],
      retainedBytes: 0,
      totalBytes: 0,
      droppedBytes: 0,
      truncated: false,
      killTimer: null,
    };

    const child = spawn(command ?? this.defaultShell, args, {
      cwd,
      env: { ...this.baseEnv, ...env },
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
    });
    record.child = child;
    record.pid = child.pid ?? null;

    child.stdout?.on("data", (chunk) => this.#push(record, chunk));
    child.stderr?.on("data", (chunk) => this.#push(record, chunk));
    child.on("error", (err) => {
      this.#push(record, Buffer.from(`[spawn-error] ${err.message}\n`, "utf8"));
      if (record.status === "running") {
        record.status = "exited";
        record.exitCode = record.exitCode ?? -1;
      }
    });
    child.on("exit", (code, signal) => {
      if (record.exitCode === null && code !== null) record.exitCode = code;
      if (signal) record.signal = signal;
    });
    child.on("close", (code, signal) => {
      if (record.exitCode === null && code !== null) record.exitCode = code;
      if (record.signal === null && signal) record.signal = signal;
      record.status = "exited";
      record.stdinClosed = true;
      if (record.killTimer) {
        clearTimeout(record.killTimer);
        record.killTimer = null;
      }
    });

    this.#records.set(record.id, record);
    return this.#view(record);
  }

  #push(record, chunk) {
    const buf = Buffer.isBuffer(chunk) ? Buffer.from(chunk) : Buffer.from(String(chunk), "utf8");
    if (buf.length === 0) return;
    record.chunks.push({ offset: record.totalBytes, data: buf });
    record.totalBytes += buf.length;
    record.retainedBytes += buf.length;
    while (record.retainedBytes > this.maxBytes && record.chunks.length > 0) {
      const dropped = record.chunks.shift();
      record.retainedBytes -= dropped.data.length;
      record.droppedBytes += dropped.data.length;
      record.truncated = true;
    }
  }

  /** 保留区间内的完整文本（有界）。 */
  text(id) {
    const record = this.#get(id);
    return Buffer.concat(record.chunks.map((c) => c.data)).toString("utf8");
  }

  /**
   * 回放输出。
   * @param {string} id
   * @param {{fromOffset?:number, maxBytes?:number}} [opts]
   * @returns {{chunks:Array<{offset:number,data:string}>, nextOffset:number, totalBytes:number,
   *   truncated:boolean, droppedBytes:number, hasMore:boolean, status:string}}
   */
  read(id, { fromOffset = 0, maxBytes = this.maxBytes } = {}) {
    const record = this.#get(id);
    const firstOffset = record.chunks[0]?.offset ?? record.totalBytes;
    const requested = Math.max(0, Number.isFinite(fromOffset) ? fromOffset : 0);
    // 请求位置早于保留窗口 → 出现缺口，从最早可用位置开始。
    const start = Math.max(requested, firstOffset);
    const truncated = requested < firstOffset;
    const limit = Math.max(1, Math.min(maxBytes, this.maxBytes));

    const out = [];
    let emitted = 0;
    let cursor = start;
    for (const chunk of record.chunks) {
      const chunkEnd = chunk.offset + chunk.data.length;
      if (chunkEnd <= start) continue;
      if (emitted >= limit) break;
      const localStart = Math.max(0, start - chunk.offset);
      const remaining = limit - emitted;
      const slice = chunk.data.subarray(localStart, localStart + remaining);
      if (slice.length === 0) continue;
      out.push({ offset: chunk.offset + localStart, data: slice.toString("utf8") });
      emitted += slice.length;
      cursor = chunk.offset + localStart + slice.length;
    }

    return {
      chunks: out,
      nextOffset: cursor,
      totalBytes: record.totalBytes,
      truncated,
      droppedBytes: record.droppedBytes,
      hasMore: record.totalBytes > cursor,
      status: record.status,
    };
  }

  write(id, data) {
    const record = this.#get(id);
    if (record.status !== "running" || !record.child?.stdin?.writable) {
      throw new ConflictError("terminal stdin is closed", { terminalId: id });
    }
    if (typeof data !== "string" && !Buffer.isBuffer(data)) {
      throw new ValidationError("terminal input must be a string or Buffer");
    }
    record.child.stdin.write(data);
    return { terminalId: id, written: Buffer.isBuffer(data) ? data.length : byteLen(String(data)) };
  }

  /** 关闭视图 ≠ 关闭进程：detach 只解除绑定，进程和回放缓冲保留。 */
  detach(id) {
    const record = this.#get(id);
    record.attached = false;
    record.detachCount += 1;
    return this.#view(record);
  }

  /** 重连：拿到当前回放视图（配合 read 从 nextOffset 续读）。 */
  attach(id, { fromOffset = null } = {}) {
    const record = this.#get(id);
    record.attached = true;
    return {
      ...this.#view(record),
      ...(fromOffset === null ? {} : { replay: this.read(id, { fromOffset }) }),
    };
  }

  /** 真 PTY 才支持 resize；这里明确降级，不修改上游窗口。 */
  resize(id, cols, rows) {
    const record = this.#get(id);
    if (!Number.isInteger(cols) || !Number.isInteger(rows) || cols <= 0 || rows <= 0) {
      throw new ValidationError("cols and rows must be positive integers");
    }
    return {
      terminalId: id,
      supported: false,
      applied: false,
      mode: "pipes",
      reason: "PTY unavailable: pipe backend cannot change terminal window size",
      cols,
      rows,
      previous: { cols: record.cols, rows: record.rows },
    };
  }

  close(id, { signal = "SIGTERM" } = {}) {
    const record = this.#get(id);
    if (record.status !== "running" || !record.child) return this.#view(record);
    try {
      record.child.kill(signal);
    } catch {
      // 进程可能刚好退出
    }
    record.killTimer = setTimeout(() => {
      if (record.status === "running") {
        try {
          record.child?.kill("SIGKILL");
        } catch {
          // ignore
        }
      }
    }, this.killGraceMs);
    record.killTimer.unref?.();
    return this.#view(record);
  }

  /** 关闭并移除（真删除终端与回放）。 */
  dispose(id) {
    const record = this.#records.get(id);
    if (!record) return false;
    if (record.status === "running") this.close(id);
    if (record.killTimer) {
      clearTimeout(record.killTimer);
      record.killTimer = null;
    }
    this.#records.delete(id);
    return true;
  }

  /** 等待输出匹配（测试与 UI 首屏用）。 */
  async waitForOutput(id, { pattern = null, timeoutMs = 2_000, intervalMs = 10 } = {}) {
    const record = this.#get(id);
    // 用墙钟计时：clock 可被注入成固定值（确定性测试），不能当作超时来源。
    const start = Date.now();
    const matches = (text) => {
      if (!pattern) return true;
      if (pattern instanceof RegExp) {
        pattern.lastIndex = 0;
        return pattern.test(text);
      }
      return text.includes(String(pattern));
    };
    // 即使 pattern 为 null 也先让出一次事件循环，等待首批输出。
    await new Promise((resolve) => setTimeout(resolve, 0));
    for (;;) {
      const text = Buffer.concat(record.chunks.map((c) => c.data)).toString("utf8");
      if (matches(text)) return { matched: true, text };
      if (Date.now() - start >= timeoutMs) return { matched: false, text };
      await new Promise((resolve) => setTimeout(resolve, intervalMs));
    }
  }

  #get(id) {
    if (this.#closed) throw new ClosedError("terminal service is closed");
    const record = this.#records.get(id);
    if (!record) throw new NotFoundError(`unknown terminal: ${id}`, { terminalId: id });
    return record;
  }

  #view(record) {
    return {
      id: record.id,
      sessionId: record.sessionId,
      name: record.name,
      cwd: record.cwd,
      pid: record.pid,
      status: record.status,
      exitCode: record.exitCode,
      signal: record.signal,
      cols: record.cols,
      rows: record.rows,
      pty: false,
      resize: "unsupported",
      attached: record.attached,
      detachCount: record.detachCount,
      startedAt: record.startedAt,
      bytes: record.totalBytes,
      retainedBytes: record.retainedBytes,
      droppedBytes: record.droppedBytes,
      truncated: record.truncated,
    };
  }

  async shutdownAll({ timeoutMs = 2_000 } = {}) {
    const running = [...this.#records.values()].filter((r) => r.status === "running");
    for (const record of running) this.close(record.id);
    const start = Date.now();
    while (running.some((r) => r.status === "running") && Date.now() - start < timeoutMs) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    for (const record of running) {
      if (record.status === "running") {
        try {
          record.child?.kill("SIGKILL");
        } catch {
          // ignore
        }
        record.status = "exited";
      }
    }
    this.#records.clear();
    this.#closed = true;
    return undefined;
  }

  async closeAll() {
    if (this.#closed) return;
    await this.shutdownAll();
  }
}
