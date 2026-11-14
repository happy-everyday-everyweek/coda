/** CORE-109：Agent 进程监督器（stdout/stderr 分离、超时终止、退出记录、临时环境清理）。 */
import test from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";

import { AgentSupervisor } from "../../core/supervisor.mjs";

const NODE = process.execPath;

test("stdout/stderr 分离，退出码与临时环境清理", async () => {
  const supervisor = new AgentSupervisor({ clock: () => new Date(), maxOutputBytes: 4096 });
  try {
    const handle = supervisor.start({
      command: NODE,
      args: ["-e", "process.stdout.write('OUT'); process.stderr.write('ERR');"],
      timeoutMs: 5_000,
    });
    const view = await handle.wait({ timeoutMs: 5_000 });
    assert.equal(view.status, "exited");
    assert.equal(view.stdout, "OUT");
    assert.equal(view.stderr, "ERR");
    assert.equal(view.exitCode, 0);
    assert.equal(view.signal, null);
    assert.equal(view.timedOut, false);
    assert.equal(view.tempDirCleaned, true);
    assert.equal(view.spawnError, null);
    assert.ok(typeof view.durationMs === "number");
  } finally {
    await supervisor.close();
  }
});

test("超时后终止进程并标记 timedOut", async () => {
  const supervisor = new AgentSupervisor({ clock: () => new Date(), killGraceMs: 300 });
  try {
    const handle = supervisor.start({
      command: NODE,
      args: ["-e", "setTimeout(() => {}, 10000)"],
      timeoutMs: 150,
    });
    const view = await handle.wait({ timeoutMs: 8_000 });
    assert.equal(view.status, "exited");
    assert.equal(view.timedOut, true);
    assert.equal(view.stopReason, "timeout");
    assert.notEqual(view.signal, null);
  } finally {
    await supervisor.close();
  }
});

test("spawn 失败被记录，不抛未处理异常", async () => {
  const supervisor = new AgentSupervisor({ clock: () => new Date() });
  try {
    const handle = supervisor.start({ command: "coda-definitely-not-a-real-binary-xyz" });
    const view = await handle.wait({ timeoutMs: 5_000 });
    assert.equal(view.status, "exited");
    assert.match(view.stderr, /spawn-error/);
    assert.ok(view.spawnError);
  } finally {
    await supervisor.close();
  }
});

test("显式 env 传递，shutdownAll 终止全部子进程", async () => {
  const supervisor = new AgentSupervisor({ clock: () => new Date() });
  try {
    const handle = supervisor.start({
      command: NODE,
      args: ["-e", "process.stdout.write(process.env.CODA_TEST_ENV ?? 'missing'); setInterval(() => {}, 1000);"],
      env: { CODA_TEST_ENV: "visible" },
    });
    let output = "";
    const deadline = Date.now() + 5_000;
    while (Date.now() < deadline && !output.includes("visible")) {
      await new Promise((resolve) => setTimeout(resolve, 10));
      output = handle.stdout;
    }
    assert.match(output, /visible/);
    assert.equal(handle.status, "running");

    const views = await supervisor.shutdownAll({ timeoutMs: 5_000 });
    assert.equal(views.length, 1);
    assert.equal(handle.status, "exited");
    assert.ok(views[0].signal || views[0].exitCode !== null);
  } finally {
    await supervisor.close();
  }
});

test("有界输出：超过上限时截断并标记", async () => {
  const supervisor = new AgentSupervisor({ clock: () => new Date(), maxOutputBytes: 32 });
  try {
    const handle = supervisor.start({
      command: NODE,
      args: ["-e", "process.stdout.write('x'.repeat(500))"],
    });
    const view = await handle.wait({ timeoutMs: 5_000 });
    assert.equal(view.status, "exited");
    assert.equal(view.stdoutTruncated, true);
    assert.ok(Buffer.byteLength(view.stdout) <= 64, `stdout bytes: ${Buffer.byteLength(view.stdout)}`);
  } finally {
    await supervisor.close();
  }
});

test("临时环境目录在进程运行期间存在，退出后删除", async () => {
  const supervisor = new AgentSupervisor({ clock: () => new Date() });
  try {
    const handle = supervisor.start({
      command: NODE,
      args: ["-e", "setTimeout(() => {}, 400)"],
      tempEnv: true,
    });
    const running = handle.snapshot();
    assert.ok(running.tempDir, "temp dir should be created");
    assert.equal(existsSync(running.tempDir), true);
    const view = await handle.wait({ timeoutMs: 5_000 });
    assert.equal(view.tempDirCleaned, true);
    assert.equal(existsSync(running.tempDir), false);
  } finally {
    await supervisor.close();
  }
});
