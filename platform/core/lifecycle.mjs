/**
 * CORE-107：核心单实例、生命周期、优雅关闭与重启入口。
 *
 * 单实例语义（CORE-001）：同一 dataDir 只允许一个核心持有 lockfile；
 * 后启动者拿不到锁必须抛 CONFLICT，由安装器/客户端转成「连接已有核心」。
 *
 * 锁的失效判定（按顺序）：文件损坏 → mtime 超过 staleMs → pid 不存在。
 * 注意：这里只做「同用户同数据目录」的进程级互斥，不替代操作系统服务管理。
 */
import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, statSync, unlinkSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";

import { ConflictError, ValidationError } from "./errors.mjs";

export const LOCK_FILE = "core.lock";

/** 进程是否存活（EPERM 视为存活：属于其它用户）。 */
export function isProcessAlive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (err) {
    return err?.code === "EPERM";
  }
}

function readLock(lockPath) {
  try {
    const stat = statSync(lockPath);
    const raw = readFileSync(lockPath, "utf8");
    let json = null;
    try {
      json = JSON.parse(raw);
    } catch {
      json = null;
    }
    return { json, raw, mtimeMs: stat.mtimeMs, size: stat.size };
  } catch (err) {
    if (err?.code === "ENOENT") return null;
    throw err;
  }
}

function staleReason(existing, { staleMs, nowMs }) {
  if (!existing) return null;
  if (!existing.json || typeof existing.json.pid !== "number") return "corrupt";
  if (Number.isFinite(staleMs) && nowMs - existing.mtimeMs > staleMs) return "stale-mtime";
  if (!isProcessAlive(existing.json.pid)) return "dead-process";
  return null;
}

/**
 * 获取单实例锁。
 * @returns {{acquired:boolean, lockPath:string, instanceId:string, owner:object|null, staleReason?:string|null, release:()=>boolean}}
 */
export function acquireInstanceLock({
  dataDir,
  pid = process.pid,
  instanceId = randomUUID(),
  clock = () => new Date(),
  staleMs = 30_000,
  meta = {},
} = {}) {
  if (typeof dataDir !== "string" || dataDir.length === 0) throw new ValidationError("dataDir is required");
  mkdirSync(dataDir, { recursive: true });
  const lockPath = path.join(dataDir, LOCK_FILE);
  const nowMs = (() => {
    const d = clock();
    return (d instanceof Date ? d : new Date(d)).getTime();
  })();

  const record = {
    pid,
    instanceId,
    host: os.hostname(),
    startedAt: new Date(nowMs).toISOString(),
    ...meta,
  };

  const create = () => {
    writeFileSync(lockPath, JSON.stringify(record, null, 2), { flag: "wx", mode: 0o600 });
  };

  const release = () => {
    const current = readLock(lockPath);
    if (!current) return false;
    if (current.json?.instanceId !== instanceId) return false;
    try {
      unlinkSync(lockPath);
      return true;
    } catch {
      return false;
    }
  };

  try {
    create();
    return { acquired: true, lockPath, instanceId, owner: record, staleReason: null, release };
  } catch (err) {
    if (err?.code !== "EEXIST") throw err;
  }

  let existing = readLock(lockPath);
  if (!existing) {
    // 竞态：上一次读取后锁被释放，重试一次。
    try {
      create();
      return { acquired: true, lockPath, instanceId, owner: record, staleReason: null, release };
    } catch {
      existing = readLock(lockPath);
    }
  }

  const reason = staleReason(existing, { staleMs, nowMs });
  if (reason) {
    try {
      unlinkSync(lockPath);
      create();
      return { acquired: true, lockPath, instanceId, owner: record, staleReason: reason, release };
    } catch {
      // 落到下面按冲突返回
    }
  }

  return {
    acquired: false,
    lockPath,
    instanceId,
    owner: existing?.json ?? null,
    staleReason: reason,
    release,
  };
}

/**
 * 核心生命周期：锁 + 关闭钩子 + 信号安装 + 重启入口。
 */
export function createLifecycle({
  dataDir,
  onShutdown = null,
  clock = () => new Date(),
  staleMs = 30_000,
  shutdownTimeoutMs = 5_000,
  installSignals = true,
  exitOnSignal = false,
  logger = null,
  staleLockOk = false,
} = {}) {
  const lock = acquireInstanceLock({ dataDir, clock, staleMs });
  if (!lock.acquired && !staleLockOk) {
    throw new ConflictError("another core instance already owns this data directory", {
      lockPath: lock.lockPath,
      owner: lock.owner,
      staleReason: lock.staleReason,
    });
  }

  let state = "running";
  let shutdownPromise = null;
  const createdAtMs = Date.now();
  const startedAt = (() => {
    const d = clock();
    return (d instanceof Date ? d : new Date(d)).toISOString();
  })();

  const signalHandlers = new Map();
  const install = () => {
    for (const signal of ["SIGINT", "SIGTERM"]) {
      const handler = () => {
        shutdown(`signal:${signal}`)
          .catch((err) => logger?.error?.("shutdown failed", { error: String(err?.message ?? err) }))
          .finally(() => {
            if (exitOnSignal) process.exit(0);
          });
      };
      signalHandlers.set(signal, handler);
      process.once(signal, handler);
    }
  };
  const removeSignals = () => {
    for (const [signal, handler] of signalHandlers) process.removeListener(signal, handler);
    signalHandlers.clear();
  };

  async function shutdown(reason = "requested") {
    if (shutdownPromise) return shutdownPromise;
    state = "stopping";
    shutdownPromise = (async () => {
      const startedMs = Date.now();
      try {
        if (typeof onShutdown === "function") {
          await Promise.race([
            Promise.resolve().then(() => onShutdown(reason)),
            new Promise((_resolve, reject) => {
              const timer = setTimeout(
                () => reject(new Error(`shutdown timed out after ${shutdownTimeoutMs}ms`)),
                shutdownTimeoutMs,
              );
              timer.unref?.();
            }),
          ]);
        }
      } finally {
        removeSignals();
        lock.release();
        state = "stopped";
      }
      return { reason, state, durationMs: Date.now() - startedMs, stoppedAt: new Date().toISOString() };
    })();
    return shutdownPromise;
  }

  if (installSignals) install();

  /** 同步释放（供核心 close 调用）：停信号、放锁、置状态。幂等。 */
  function release() {
    removeSignals();
    const released = lock.release();
    if (state !== "stopped") state = "stopped";
    return released;
  }

  return {
    get state() {
      return state;
    },
    get startedAt() {
      return startedAt;
    },
    get lockInfo() {
      return { lockPath: lock.lockPath, instanceId: lock.instanceId, acquired: lock.acquired };
    },
    shutdown,
    release,
    /** 服务管理器重启入口：由外部以该命令重新拉起核心进程。 */
    restartArgs() {
      return {
        execPath: process.execPath,
        args: [...process.execArgv, ...process.argv.slice(1)],
        cwd: process.cwd(),
        env: { ...process.env, CODA_CORE_RESTARTED: "1" },
      };
    },
    disposeSignals: removeSignals,
    health() {
      return {
        state,
        pid: process.pid,
        startedAt,
        uptimeMs: Math.max(0, Date.now() - createdAtMs),
        lock: { lockPath: lock.lockPath, instanceId: lock.instanceId },
      };
    },
  };
}
