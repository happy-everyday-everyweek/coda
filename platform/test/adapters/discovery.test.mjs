/** AGT-105：安装发现、版本探测、无副作用策略、升级后重新探测。 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  ALLOWED_PROBE_ARGS,
  assertProbePolicy,
  createDiscoveryCache,
  discoverAgent,
  parseVersion,
} from "../../adapters/discovery.mjs";

test("parseVersion 取不到就返回 null，不猜版本", () => {
  assert.equal(parseVersion("v24.1.0"), "24.1.0");
  assert.equal(parseVersion("claude 1.2.3 (build abc)"), "1.2.3");
  assert.equal(parseVersion("no version here"), null);
  assert.equal(parseVersion(""), null);
});

test("发现阶段参数白名单：越界立即报错（防止探测变成启动）", () => {
  assert.deepEqual([...ALLOWED_PROBE_ARGS], ["--version", "-v", "--help", "-h"]);
  assert.equal(assertProbePolicy(["--version"]), true);
  assert.throws(() => assertProbePolicy(["--dangerously-skip-permissions"]), /probe policy violation/);
  assert.throws(() => assertProbePolicy(["--version", "-p", "run a session"]), /probe policy violation/);
});

test("未安装：installed=false，不抛错、不启动任何进程", async () => {
  const result = await discoverAgent({ id: "ghost", command: "coda-definitely-not-real-agent-xyz" });
  assert.equal(result.installed, false);
  assert.equal(result.path, null);
  assert.equal(result.version, null);
  assert.equal(result.reason, "not-found");
  assert.equal(result.sideEffectFree, true);
  assert.equal(result.probe, null);
});

test("已安装：只运行 --version，解析版本并标记无副作用", async () => {
  const calls = [];
  const result = await discoverAgent({
    id: "node",
    command: process.execPath,
    runProbe: async (options) => {
      calls.push(options);
      return { code: 0, stdout: "v24.1.0\n", stderr: "", timedOut: false, durationMs: 3, command: options.command };
    },
  });
  assert.equal(result.installed, true);
  assert.equal(result.version, "24.1.0");
  assert.equal(result.healthy, true);
  assert.equal(result.sideEffectFree, true);
  assert.equal(calls.length, 1, "discovery must make exactly one probe call");
  assert.deepEqual(calls[0].args, ["--version"]);
});

test("真实二进制探测（本机 node）：版本可解析", async () => {
  const result = await discoverAgent({ id: "node", command: process.execPath, timeoutMs: 5000 });
  assert.equal(result.installed, true);
  assert.match(result.version, /^\d+\.\d+/);
  assert.equal(result.healthy, true);
  assert.equal(result.exitCode, 0);
});

test("探测超时：installed=true 但 healthy=false，原因可见", async () => {
  const result = await discoverAgent({
    id: "slow",
    command: process.execPath,
    runProbe: async () => ({ code: null, stdout: "1.0.0", stderr: "", timedOut: true, durationMs: 5000 }),
  });
  assert.equal(result.installed, true);
  assert.equal(result.healthy, false);
  assert.equal(result.reason, "probe-timeout");
});

test("缓存：升级后强制重新探测，changed 反映版本变化", async () => {
  let version = "1.0.0";
  const cache = createDiscoveryCache({
    discover: async ({ id }) => ({ id, installed: true, version, path: "/bin/x", healthy: true, sideEffectFree: true }),
  });
  const first = await cache.probe("agent-a");
  assert.equal(first.version, "1.0.0");

  version = "2.0.0";
  const upgraded = await cache.reprobeAfterUpgrade("agent-a", { previousVersion: first.version });
  assert.equal(upgraded.reprobed, true);
  assert.equal(upgraded.changed, true);
  assert.equal(upgraded.previousVersion, "1.0.0");
  assert.equal(upgraded.version, "2.0.0");
  assert.equal(cache.get("agent-a").version, "2.0.0");

  const same = await cache.reprobeAfterUpgrade("agent-a");
  assert.equal(same.changed, false);
  cache.invalidate("agent-a");
  assert.equal(cache.get("agent-a"), null);
});
