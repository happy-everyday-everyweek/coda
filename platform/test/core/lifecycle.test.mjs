/** CORE-107：单实例锁、优雅关闭、重启入口。 */
import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, utimesSync, writeFileSync } from "node:fs";
import path from "node:path";

import { LOCK_FILE, acquireInstanceLock, createLifecycle, isProcessAlive } from "../../core/lifecycle.mjs";
import { createCore } from "../../core/index.mjs";
import { cleanupDir, fixedClock, makeTempDir } from "../../fixtures/core/helpers.mjs";

test("同一 dataDir 只允许一个实例持有 lockfile", () => {
  const dir = makeTempDir("coda-life-");
  try {
    const first = acquireInstanceLock({ dataDir: dir, pid: process.pid });
    assert.equal(first.acquired, true);
    assert.equal(first.lockPath, path.join(dir, LOCK_FILE));

    const second = acquireInstanceLock({ dataDir: dir, pid: process.pid });
    assert.equal(second.acquired, false);
    assert.equal(second.owner.pid, process.pid);
    // 非持有者不能释放别人的锁。
    assert.equal(second.release(), false);

    assert.equal(first.release(), true);
    const third = acquireInstanceLock({ dataDir: dir, pid: process.pid });
    assert.equal(third.acquired, true);
    third.release();
  } finally {
    cleanupDir(dir);
  }
});

test("陈旧锁按 mtime 或损坏内容回收", () => {
  const dir = makeTempDir("coda-life-");
  try {
    const lockPath = path.join(dir, LOCK_FILE);
    writeFileSync(lockPath, JSON.stringify({ pid: process.pid, instanceId: "old" }));
    const old = new Date(Date.now() - 60_000);
    utimesSync(lockPath, old, old);

    const byMtime = acquireInstanceLock({ dataDir: dir, pid: process.pid, staleMs: 1_000 });
    assert.equal(byMtime.acquired, true);
    assert.equal(byMtime.staleReason, "stale-mtime");
    byMtime.release();

    writeFileSync(lockPath, "{ broken json");
    const byCorrupt = acquireInstanceLock({ dataDir: dir, pid: process.pid });
    assert.equal(byCorrupt.acquired, true);
    assert.equal(byCorrupt.staleReason, "corrupt");
    byCorrupt.release();
  } finally {
    cleanupDir(dir);
  }
});

test("isProcessAlive 识别当前进程与非法 pid", () => {
  assert.equal(isProcessAlive(process.pid), true);
  assert.equal(isProcessAlive(0), false);
  assert.equal(isProcessAlive(-1), false);
  assert.equal(isProcessAlive(1.5), false);
});

test("createCore 对同一 dataDir 只允许一个实例（CORE-001）", async () => {
  const dir = makeTempDir("coda-life-core-");
  const first = createCore({ dataDir: dir, clock: fixedClock(), lock: true });
  try {
    assert.throws(() => createCore({ dataDir: dir, clock: fixedClock(), lock: true }), /another core instance/);
  } finally {
    await first.close();
  }
  try {
    // 关闭后锁被释放，新实例可以接管同一份数据。
    const second = createCore({ dataDir: dir, clock: fixedClock(), lock: true });
    await second.close();
  } finally {
    cleanupDir(dir);
  }
});

test("生命周期：重复启动冲突、关闭幂等、重启入口可用", async () => {
  const dir = makeTempDir("coda-life-");
  try {
    let shutdowns = 0;
    const lifecycle = createLifecycle({
      dataDir: dir,
      installSignals: false,
      onShutdown: async () => {
        shutdowns += 1;
      },
    });
    assert.equal(lifecycle.state, "running");
    assert.throws(() => createLifecycle({ dataDir: dir, installSignals: false }), /another core instance/);

    const restart = lifecycle.restartArgs();
    assert.equal(restart.execPath, process.execPath);
    assert.ok(Array.isArray(restart.args));
    assert.equal(restart.env.CODA_CORE_RESTARTED, "1");

    const first = await lifecycle.shutdown("test-request");
    assert.equal(first.reason, "test-request");
    assert.equal(shutdowns, 1);
    assert.equal(lifecycle.state, "stopped");

    const second = await lifecycle.shutdown("again");
    assert.equal(second.reason, "test-request");
    assert.equal(shutdowns, 1);

    // 锁已释放，新实例可以启动。
    const next = createLifecycle({ dataDir: dir, installSignals: false });
    assert.equal(next.state, "running");
    assert.equal(next.health().state, "running");
    await next.shutdown("done");
    assert.equal(next.state, "stopped");
    assert.equal(existsSync(path.join(dir, LOCK_FILE)), false);
  } finally {
    cleanupDir(dir);
  }
});
