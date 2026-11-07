/**
 * 安装发现与版本探测（AGT-105 / ADP-001 / ADP-018 / SPEC 9.2）。
 *
 * 探测必须无副作用：只做 PATH 查找 + `--version`（必要时 `--help`），
 * 绝不启动会话、MCP、Hooks 或登录浏览器。
 *
 * 找不到就返回 installed:false，不抛错；版本变化后必须重新探测，不沿用旧缓存。
 */
import { nowIso } from "../contracts/ids.mjs";
import { resolveExecutable, runProcess } from "./runtime.mjs";

/** 允许在“发现”阶段执行的参数白名单；越界直接报错（防止探测变成启动）。 */
export const ALLOWED_PROBE_ARGS = Object.freeze(["--version", "-v", "--help", "-h"]);

export const PROBE_POLICY = Object.freeze({
  allowedArgs: [...ALLOWED_PROBE_ARGS],
  forbiddenEffects: ["启动会话", "启动 MCP server", "触发登录/浏览器", "安装或升级", "写入 Agent 配置"],
  note: "发现阶段只运行 --version / --help，并丢弃 ambient 凭据；不做任何会话级操作",
});

export function assertProbePolicy(args = []) {
  const bad = args.filter((a) => !ALLOWED_PROBE_ARGS.includes(a));
  if (bad.length) {
    throw new Error(`probe policy violation: ${bad.join(", ")} (allowed: ${ALLOWED_PROBE_ARGS.join(", ")})`);
  }
  return true;
}

/** 从 `--version` 输出里取第一个版本号；取不到返回 null，不猜。 */
export function parseVersion(text) {
  const match = /(\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?)/.exec(String(text ?? ""));
  return match ? match[1] : null;
}

/**
 * 发现一个 Agent：是否安装、路径、版本、探测健康度。
 * @returns {Promise<object>} 永远不因“没装”而 reject
 */
export async function discoverAgent({
  id,
  command,
  probeArgs = ["--version"],
  env = null,
  pathAllowlist = null,
  timeoutMs = 5000,
  platform = process.platform,
  runProbe = runProcess,
  probedAt = nowIso(),
} = {}) {
  if (!id || !command) throw new Error("discoverAgent requires id and command");
  assertProbePolicy(probeArgs);

  const executable = resolveExecutable(command, { env: env ?? process.env, pathAllowlist, platform });
  const base = {
    id,
    command,
    probeArgs: [...probeArgs],
    probedAt,
    sideEffectFree: true,
    policy: { allowedArgs: [...ALLOWED_PROBE_ARGS] },
  };

  if (!executable) {
    return { ...base, installed: false, path: null, version: null, healthy: false, reason: "not-found", probe: null };
  }

  let probe = null;
  let error = null;
  try {
    probe = await runProbe({
      command: executable,
      args: probeArgs,
      env,
      allowEnv: [],
      timeoutMs,
      maxOutputBytes: 32 * 1024,
    });
  } catch (err) {
    error = { code: err?.code ?? "UNKNOWN", message: err?.message ?? String(err) };
  }

  if (!probe) {
    return {
      ...base,
      installed: false,
      path: executable,
      version: null,
      healthy: false,
      reason: "probe-failed",
      error,
      probe: null,
    };
  }

  const raw = `${probe.stdout ?? ""}${probe.stderr ?? ""}`.trim();
  const version = parseVersion(raw);
  if (probe.timedOut) {
    return {
      ...base,
      installed: true,
      path: executable,
      version,
      healthy: false,
      reason: "probe-timeout",
      exitCode: probe.code,
      rawOutput: raw.slice(0, 512),
      probe,
    };
  }
  return {
    ...base,
    installed: true,
    path: executable,
    version,
    healthy: probe.code === 0,
    reason: probe.code === 0 ? null : `probe-exit-${probe.code}`,
    exitCode: probe.code,
    durationMs: probe.durationMs ?? null,
    rawOutput: raw.slice(0, 512),
    probe,
  };
}

/**
 * 发现结果缓存：显式失效 + 升级后强制重探（ADP-018）。
 */
export function createDiscoveryCache({ discover = discoverAgent, now = () => nowIso() } = {}) {
  const records = new Map();

  async function probe(id, options = {}) {
    const result = await discover({ id, ...options });
    const previous = records.get(id) ?? null;
    const record = { ...result, checkedAt: now(), previousVersion: previous?.version ?? null };
    records.set(id, record);
    return record;
  }

  return {
    records,
    get(id) {
      return records.get(id) ?? null;
    },
    async probe(id, options = {}) {
      return probe(id, options);
    },
    /** 升级/切换路径后强制重新探测；changed 表示版本真的变了。 */
    async reprobeAfterUpgrade(id, { previousVersion = null, ...options } = {}) {
      const before = previousVersion ?? records.get(id)?.version ?? null;
      const result = await probe(id, options);
      const changed = before !== null && result.version !== null && before !== result.version;
      return { ...result, previousVersion: before, changed, reprobed: true };
    },
    invalidate(id) {
      if (id === undefined) records.clear();
      else records.delete(id);
    },
    list() {
      return [...records.values()];
    },
  };
}
