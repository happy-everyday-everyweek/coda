/** PERF-101：基准框架可运行、输出结构稳定；不设固定阈值（避免机器相关抖动）。 */
import test from "node:test";
import assert from "node:assert/strict";

import { runBenchmarks } from "../../sync/perf.mjs";

test("PERF-101：启动/事件追加/大文件分块/空闲资源基准可运行并输出 JSON 结构", async () => {
  const result = await runBenchmarks({
    quick: true,
    startupRuns: 2,
    eventCount: 20,
    fileSizeBytes: 256 * 1024,
    chunkSize: 32 * 1024,
    idleMs: 50,
  });

  assert.equal(result.schemaVersion, 1);
  assert.equal(result.mode, "quick");
  assert.equal(typeof result.generatedAt, "string");
  assert.ok(result.env.node.startsWith("v"));
  assert.ok(Array.isArray(result.notes));
  assert.deepEqual(result.config, {
    startupRuns: 2,
    eventCount: 20,
    fileSizeBytes: 256 * 1024,
    chunkSize: 32 * 1024,
    idleMs: 50,
  });

  const { startup, eventAppend, largeFile, idle } = result.results;
  assert.equal(startup.runs, 2);
  assert.equal(startup.count, 2);
  assert.ok(startup.p50 >= 0);
  assert.ok(startup.p95 >= 0);

  assert.equal(eventAppend.count, 20);
  assert.ok(eventAppend.p95 >= eventAppend.p50);

  assert.equal(largeFile.bytes, 256 * 1024);
  assert.equal(largeFile.chunks, 8);
  assert.match(largeFile.sha256, /^[0-9a-f]{64}$/);
  assert.ok(largeFile.throughputMBps > 0);
  assert.equal(typeof largeFile.controlLatencyMs.count, "number");
  assert.equal(largeFile.yielded, true);

  assert.equal(idle.durationMs, 50);
  assert.equal(typeof idle.rssBytes, "number");
  assert.ok(idle.rssBytes > 0);
  assert.ok(idle.cpuPercent >= 0);

  // 结构必须可 JSON 序列化（bench 脚本输出契约）。
  const roundTrip = JSON.parse(JSON.stringify(result));
  assert.equal(roundTrip.results.largeFile.sha256, largeFile.sha256);
});
