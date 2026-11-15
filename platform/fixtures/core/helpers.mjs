/**
 * 核心测试夹具（不放 test/ 目录下，避免被 node --test 当作测试文件执行）。
 */
import { mkdtempSync, rmSync } from "node:fs";
import os from "node:os";
import path from "node:path";

export const FIXED_ISO = "2026-01-01T00:00:00.000Z";

export function makeTempDir(prefix = "coda-core-test-") {
  return mkdtempSync(path.join(os.tmpdir(), prefix));
}

export function cleanupDir(dir) {
  if (!dir) return;
  try {
    rmSync(dir, { recursive: true, force: true });
  } catch {
    // 测试清理失败不应掩盖断言结果
  }
}

/** 固定时钟：每次调用都返回同一时刻的**新** Date，避免共享可变对象。 */
export function fixedClock(iso = FIXED_ISO) {
  const t = Date.parse(iso);
  return () => new Date(t);
}

/** 可推进时钟：clock.advance(ms) / clock.set(iso)。 */
export function mutableClock(iso = FIXED_ISO) {
  let t = Date.parse(iso);
  const clock = () => new Date(t);
  clock.advance = (ms) => {
    t += ms;
    return t;
  };
  clock.set = (next) => {
    t = Date.parse(next);
    return t;
  };
  clock.now = () => t;
  return clock;
}

/** 轮询等待谓词为真；超时抛错。 */
export function waitFor(predicate, { timeoutMs = 3_000, intervalMs = 10, label = "condition" } = {}) {
  return new Promise((resolve, reject) => {
    const start = Date.now();
    const tick = () => {
      let ok = false;
      try {
        ok = !!predicate();
      } catch {
        ok = false;
      }
      if (ok) {
        resolve(true);
        return;
      }
      if (Date.now() - start > timeoutMs) {
        reject(new Error(`timeout waiting for ${label}`));
        return;
      }
      setTimeout(tick, intervalMs);
    };
    tick();
  });
}

/** 收集 core 推送的事件。 */
export function collectEvents(core) {
  const events = [];
  const off = core.subscribe((event) => events.push(event));
  return { events, off };
}

export async function closeCore(core) {
  try {
    await core?.close?.();
  } catch {
    // 关闭失败不应掩盖测试断言
  }
}
