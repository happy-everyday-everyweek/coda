/**
 * 性能基准框架（PERF-101 / PERF-001 / PERF-005 的同步侧代理指标）。
 *
 * 运行：
 *   node platform/sync/perf.mjs --quick
 *   node platform/sync/perf.mjs --full --size=1073741824 --out=perf.json
 *
 * 输出 JSON（stdout 和可选文件）：
 * - startup：创建 identity + storage + sync service 至可接受本地调用的耗时（10 次 p95）；
 * - eventAppend：事件追加延迟；
 * - largeFile：分块传输吞吐、最终 SHA-256、传输期间事件循环/控制延迟；
 * - idle：空闲 RSS 与 CPU 占用采样。
 *
 * 说明：真正的「核心热启动到 RPC 可连接」需要 core/ 进程，本脚本测量同一同步层
 * 启动路径，作为回归基线；阈值不在代码里写死，由报告消费方按基准设备判定。
 */
import { createHash } from "node:crypto";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir, cpus, platform, arch } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { createDeviceIdentity } from "./identity.mjs";
import { createMemoryStorage } from "./storage.mjs";
import { createSyncService } from "./sync-service.mjs";

export const PERF_SCHEMA_VERSION = 1;
export const QUICK_DEFAULTS = Object.freeze({
  startupRuns: 5,
  eventCount: 200,
  fileSizeBytes: 1024 * 1024,
  chunkSize: 64 * 1024,
  idleMs: 150,
});
export const FULL_DEFAULTS = Object.freeze({
  startupRuns: 10,
  eventCount: 5000,
  fileSizeBytes: 256 * 1024 * 1024,
  chunkSize: 1024 * 1024,
  idleMs: 300_000,
});

export function percentile(values, p) {
  if (!Array.isArray(values) || values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const rank = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.min(sorted.length - 1, Math.max(0, rank))];
}

export function summarize(values) {
  if (!values.length) return { count: 0, p50: null, p95: null, max: null, mean: null };
  const sum = values.reduce((acc, v) => acc + v, 0);
  return {
    count: values.length,
    p50: percentile(values, 50),
    p95: percentile(values, 95),
    max: Math.max(...values),
    mean: sum / values.length,
  };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function nowMs() {
  return Number(process.hrtime.bigint()) / 1e6;
}

/** 分块传输基准：不把整个文件读进内存；逐块更新哈希并让出事件循环。 */
async function benchmarkLargeFile({ fileSizeBytes, chunkSize, samples = 500, runControlProbe = true } = {}) {
  const hash = createHash("sha256");
  const chunk = Buffer.alloc(chunkSize, 0x5a);
  const chunks = Math.ceil(fileSizeBytes / chunkSize);
  const lagSamples = [];
  let expected = nowMs() + 10;
  let probe = null;
  if (runControlProbe) {
    probe = setInterval(() => {
      const at = nowMs();
      lagSamples.push(Math.max(0, at - expected));
      expected = at + 10;
    }, 10);
    probe.unref?.();
  }
  const started = nowMs();
  let written = 0;
  for (let index = 0; index < chunks; index += 1) {
    const remaining = fileSizeBytes - written;
    const slice = remaining >= chunkSize ? chunk : chunk.subarray(0, remaining);
    hash.update(slice);
    written += slice.length;
    // 控制通道必须有让出点：大文件不得阻塞同步 RPC。
    await new Promise((resolve) => setImmediate(resolve));
  }
  const durationMs = nowMs() - started;
  if (probe) clearInterval(probe);
  return {
    bytes: written,
    chunkSize,
    chunks,
    durationMs,
    throughputMBps: written / (1024 * 1024) / (durationMs / 1000 || 1),
    sha256: hash.digest("hex"),
    controlLatencyMs: summarize(lagSamples.slice(0, samples)),
    yielded: true,
  };
}

async function benchmarkStartup({ identity, runs }) {
  const samples = [];
  for (let index = 0; index < runs; index += 1) {
    const started = nowMs();
    const storage = createMemoryStorage();
    const service = createSyncService({ storage, identity });
    service.diagnostics();
    samples.push(nowMs() - started);
    service.close();
    storage.close();
  }
  return { runs, ...summarize(samples), samples: samples.map((v) => Number(v.toFixed(3))) };
}

function benchmarkEventAppend({ identity, count }) {
  const storage = createMemoryStorage();
  const service = createSyncService({ storage, identity });
  const session = service.appendSession({ title: "perf" });
  const samples = [];
  for (let index = 0; index < count; index += 1) {
    const started = nowMs();
    service.appendEvent({
      sessionId: session.id,
      kind: "message",
      payload: { text: `event ${index}`, index },
    });
    samples.push(nowMs() - started);
  }
  const result = { count, ...summarize(samples), samples: samples.slice(0, 50).map((v) => Number(v.toFixed(3))) };
  service.close();
  storage.close();
  return result;
}

async function benchmarkIdle({ identity, idleMs }) {
  const storage = createMemoryStorage();
  const service = createSyncService({ storage, identity });
  const beforeRss = process.memoryUsage().rss;
  const beforeCpu = process.cpuUsage();
  await sleep(idleMs);
  const afterRss = process.memoryUsage().rss;
  const afterCpu = process.cpuUsage(beforeCpu);
  const cpuMs = (afterCpu.user + afterCpu.system) / 1000;
  service.close();
  storage.close();
  return {
    durationMs: idleMs,
    rssBytes: afterRss,
    rssDeltaBytes: afterRss - beforeRss,
    cpuMs,
    cpuPercent: idleMs > 0 ? (cpuMs / idleMs) * 100 : 0,
  };
}

/**
 * 运行全部基准。默认 quick；full 会显著更久。
 * @param {object} opts
 * @param {boolean} [opts.quick]
 * @param {number} [opts.startupRuns]
 * @param {number} [opts.eventCount]
 * @param {number} [opts.fileSizeBytes]
 * @param {number} [opts.chunkSize]
 * @param {number} [opts.idleMs]
 * @param {string} [opts.dir] 复用已有目录（不会删除）；不传则创建并清理临时目录
 */
export async function runBenchmarks({
  quick = true,
  startupRuns,
  eventCount,
  fileSizeBytes,
  chunkSize,
  idleMs,
  dir = null,
} = {}) {
  const defaults = quick ? QUICK_DEFAULTS : FULL_DEFAULTS;
  const config = {
    startupRuns: startupRuns ?? defaults.startupRuns,
    eventCount: eventCount ?? defaults.eventCount,
    fileSizeBytes: fileSizeBytes ?? defaults.fileSizeBytes,
    chunkSize: chunkSize ?? defaults.chunkSize,
    idleMs: idleMs ?? defaults.idleMs,
  };
  const ownDir = !dir;
  const workDir = dir ?? mkdtempSync(join(tmpdir(), "coda-perf-"));
  const identity = createDeviceIdentity({
    dir: join(workDir, "identity"),
    name: "perf-bench",
    platform: "bench",
    persist: false,
  });
  try {
    const startup = await benchmarkStartup({ identity, runs: config.startupRuns });
    const eventAppend = benchmarkEventAppend({ identity, count: config.eventCount });
    const largeFile = await benchmarkLargeFile({
      fileSizeBytes: config.fileSizeBytes,
      chunkSize: config.chunkSize,
    });
    const idle = await benchmarkIdle({ identity, idleMs: config.idleMs });
    return {
      schemaVersion: PERF_SCHEMA_VERSION,
      generatedAt: new Date().toISOString(),
      mode: quick ? "quick" : "full",
      env: {
        node: process.version,
        platform: platform(),
        arch: arch(),
        cpus: cpus().length,
        pid: process.pid,
      },
      config,
      results: { startup, eventAppend, largeFile, idle },
      notes: [
        "startup measures identity+storage+sync service bootstrap, not core RPC listen",
        "largeFile streams synthetic chunks; it never buffers the whole file",
        "no fixed pass/fail thresholds; interpret against a named baseline device",
      ],
    };
  } finally {
    if (ownDir) rmSync(workDir, { recursive: true, force: true });
  }
}

function parseArgs(argv) {
  const args = { quick: false, full: false, out: null, size: null, events: null, idle: null };
  for (const raw of argv) {
    if (raw === "--quick") args.quick = true;
    else if (raw === "--full") args.full = true;
    else if (raw.startsWith("--out=")) args.out = raw.slice(6);
    else if (raw.startsWith("--size=")) args.size = Number(raw.slice(7));
    else if (raw.startsWith("--events=")) args.events = Number(raw.slice(9));
    else if (raw.startsWith("--idle=")) args.idle = Number(raw.slice(7));
  }
  return args;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const quick = args.quick || !args.full;
  const result = await runBenchmarks({
    quick,
    fileSizeBytes: Number.isFinite(args.size) && args.size > 0 ? args.size : undefined,
    eventCount: Number.isFinite(args.events) && args.events > 0 ? args.events : undefined,
    idleMs: Number.isFinite(args.idle) && args.idle > 0 ? args.idle : undefined,
  });
  const json = JSON.stringify(result, null, 2);
  if (args.out) {
    const outPath = resolve(args.out);
    mkdirSync(dirname(outPath), { recursive: true });
    writeFileSync(outPath, json);
  }
  process.stdout.write(`${json}\n`);
}

const entry = process.argv[1] ? resolve(process.argv[1]) : null;
const self = fileURLToPath(import.meta.url);
if (entry && resolve(self) === entry) {
  main().catch((err) => {
    process.stderr.write(`perf benchmark failed: ${err.stack ?? err}\n`);
    process.exitCode = 1;
  });
}
