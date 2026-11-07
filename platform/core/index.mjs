/**
 * CORE-101 / CORE-103：共享核心入口。
 *
 * 契约（platform/CONTRACT.md 第 8 节）：
 *   createCore({dataDir, adapters, clock})
 *     → {handleRpc(msg), subscribe(fn), close(), diagnostics(), state}
 *
 * 核心是唯一权威状态源：SQLite 事件日志 + 命令收据 + 投影。
 * 客户端只读投影、只发命令；适配器只翻译协议，不持有会话真相。
 */
import { randomUUID } from "node:crypto";
import os from "node:os";
import path from "node:path";
import readline from "node:readline";
import { pathToFileURL } from "node:url";

import {
  CORE_VERSION,
  PROTOCOL_VERSION,
  RPC_ERRORS,
  eventNotification,
  rpcError,
} from "../contracts/rpc.mjs";
import { ArtifactStore } from "./artifacts.mjs";
import { createDevice, createExecutionEnvironment } from "./domain.mjs";
import { createDiagnostics } from "./diagnostics.mjs";
import { ClosedError, ConflictError, ValidationError } from "./errors.mjs";
import { createEventBus } from "./eventbus.mjs";
import { Journal } from "./journal.mjs";
import { createLifecycle } from "./lifecycle.mjs";
import { createMockAdapter, runUiScenario } from "./mock.mjs";
import { createNotificationService } from "./notifications.mjs";
import { ReceiptStore } from "./receipts.mjs";
import { recoverCoreSync } from "./recovery.mjs";
import { createRpcServer } from "./server.mjs";
import { AgentSupervisor } from "./supervisor.mjs";
import { TerminalService } from "./terminal.mjs";
import { createWorkspaceService } from "./workspace.mjs";

/** 本机节点与宿主环境的稳定 ID：跨重启保持引用不变（CORE-003）。 */
export const LOCAL_DEVICE_ID = "dev_local";
export const LOCAL_ENVIRONMENT_ID = "env_local_host";

function normalizeAdapters(adapters) {
  if (!adapters) return [];
  if (Array.isArray(adapters)) return adapters.filter(Boolean);
  if (typeof adapters === "object") return Object.values(adapters).filter(Boolean);
  throw new ValidationError("adapters must be an array, map, or omitted");
}

function defaultDataDir() {
  return path.join(os.tmpdir(), "coda-core", randomUUID());
}

function platformName() {
  if (process.platform === "win32") return "windows";
  if (process.platform === "darwin") return "macos";
  if (process.platform === "linux") return "linux";
  return "unknown";
}

/**
 * @param {object} [options]
 * @param {string} [options.dataDir] 本地数据目录（默认 os.tmpdir() 下唯一目录）
 * @param {Array|object} [options.adapters] 适配器实例；为空时使用内置 MockAdapter
 * @param {() => Date} [options.clock] 注入时钟（测试确定性）
 * @param {boolean} [options.lock] 是否启用单实例 lockfile（默认 true）
 * @param {boolean} [options.recover] 是否在启动时执行崩溃恢复（默认 true）
 * @param {string|null} [options.authToken] 可选的认证令牌
 * @param {object} [options.logger] 追加日志接收器
 * @param {Array} [options.systemNotificationAdapters] 系统通知适配器
 */
export function createCore({
  dataDir = defaultDataDir(),
  adapters = [],
  clock = () => new Date(),
  lock = true,
  recover = true,
  authToken = null,
  logger = null,
  systemNotificationAdapters = [],
  terminalOptions = {},
  supervisorOptions = {},
} = {}) {
  const resolvedDataDir = path.resolve(dataDir);
  const nowIso = () => {
    const d = clock();
    return (d instanceof Date ? d : new Date(d)).toISOString();
  };
  const startedAtMs = Date.now();
  const startedAt = nowIso();

  const adapterList = normalizeAdapters(adapters);
  if (adapterList.length === 0) {
    adapterList.push(createMockAdapter({ clock }));
  }

  const journal = new Journal({ dataDir: resolvedDataDir, clock });
  journal.open();
  journal.setMeta("core_version", CORE_VERSION);

  const diagnosticsService = createDiagnostics({ clock, version: CORE_VERSION, logger });
  const receipts = new ReceiptStore({ journal, clock });
  const bus = createEventBus({
    onError: (err, event) =>
      diagnosticsService.log("warn", "subscriber threw", { error: String(err?.message ?? err), seq: event?.seq ?? null }),
  });
  if (typeof logger === "function") {
    diagnosticsService.registerCheck("external-logger", () => typeof logger === "function");
  }
  const notifications = createNotificationService({
    clock,
    journal,
    bus,
    logger: diagnosticsService.logger,
    systemAdapters: systemNotificationAdapters,
  });
  const supervisor = new AgentSupervisor({ clock, ...supervisorOptions });
  const terminal = new TerminalService({ clock, ...terminalOptions });
  const artifacts = new ArtifactStore({ dataDir: path.join(resolvedDataDir, "artifacts"), clock, journal });
  const workspaces = createWorkspaceService();

  /* 本机节点/环境：只在缺失时写入，保持跨重启引用稳定。 */
  let device = journal.getObject("device", LOCAL_DEVICE_ID);
  if (!device) {
    device = createDevice({
      id: LOCAL_DEVICE_ID,
      name: os.hostname() || "local",
      platform: platformName(),
      online: true,
      authorized: true,
      version: CORE_VERSION,
      createdAt: nowIso(),
    });
    journal.putObject("device", device);
  }
  let environment = journal.getObject("environment", LOCAL_ENVIRONMENT_ID);
  if (!environment) {
    environment = createExecutionEnvironment({
      id: LOCAL_ENVIRONMENT_ID,
      deviceId: LOCAL_DEVICE_ID,
      kind: "host",
      name: "host",
      available: true,
      createdAt: nowIso(),
    });
    journal.putObject("environment", environment);
  }

  /* 单实例：拿不到锁就是「已有核心在跑」，由安装器转成连接。 */
  let lifecycle = null;
  const shutdownHook = { fn: null };
  if (lock) {
    try {
      lifecycle = createLifecycle({
        dataDir: resolvedDataDir,
        clock,
        logger: diagnosticsService.logger,
        onShutdown: async (reason) => {
          diagnosticsService.log("info", "lifecycle shutdown", { reason });
          if (typeof shutdownHook.fn === "function") await shutdownHook.fn(reason);
        },
      });
    } catch (err) {
      journal.close();
      throw err;
    }
  }

  /* CORE-108：重启后未完成回合/命令一律 unknown，绝不乐观标成功。 */
  let recoveryReport = null;
  if (recover) {
    try {
      recoveryReport = recoverCoreSync({ journal, receipts, clock, diagnostics: diagnosticsService });
    } catch (err) {
      diagnosticsService.recordError(err, { where: "recovery" });
      recoveryReport = { failed: true, error: { message: String(err?.message ?? err) } };
    }
  }

  const server = createRpcServer({
    journal,
    receipts,
    bus,
    diagnostics: diagnosticsService,
    notifications,
    terminal,
    artifacts,
    supervisor,
    workspaces,
    clock,
    adapters: adapterList,
    localDeviceId: LOCAL_DEVICE_ID,
    localEnvironmentId: LOCAL_ENVIRONMENT_ID,
    dataDir: resolvedDataDir,
    startedAtMs,
    coreVersion: CORE_VERSION,
    protocolVersion: PROTOCOL_VERSION,
    authToken,
    lifecycle,
    recoveryReport,
  });
  server.init();

  let closed = false;
  let closePromise = null;
  let cachedState = null;

  function buildState() {
    return {
      closed,
      startedAt,
      dataDir: resolvedDataDir,
      seq: journal.latestSeq(),
      sessions: journal.listSessions({ limit: 200 }),
      commands: receipts.countByState(),
      adapters: adapterList.map((a) => a.instanceId ?? a.id ?? null),
      terminals: terminal.list().length,
      notificationsUnread: notifications.unreadCount(),
      recovery: recoveryReport?.counts ?? null,
    };
  }

  async function close() {
    if (closePromise) return closePromise;
    cachedState = closed ? cachedState : buildState();
    closed = true;
    cachedState = { ...cachedState, closed: true };
    closePromise = (async () => {
      try {
        await server.close();
      } catch (err) {
        diagnosticsService.log("warn", "server close failed", { error: String(err?.message ?? err) });
      }
      try {
        await supervisor.close();
      } catch (err) {
        diagnosticsService.log("warn", "supervisor close failed", { error: String(err?.message ?? err) });
      }
      try {
        await terminal.closeAll();
      } catch (err) {
        diagnosticsService.log("warn", "terminal close failed", { error: String(err?.message ?? err) });
      }
      bus.close();
      // 先释放单实例锁，再关闭数据库，避免新核心看到半关闭状态。
      try {
        lifecycle?.release?.();
      } catch (err) {
        diagnosticsService.log("warn", "lifecycle release failed", { error: String(err?.message ?? err) });
      }
      try {
        journal.close();
      } catch {
        // ignore
      }
      return cachedState;
    })();
    return closePromise;
  }

  // 信号触发的 shutdown 最终走同一个 close()，避免两套关闭路径。
  shutdownHook.fn = close;

  return {
    /** 所有 RPC 都从这里进；纯函数式分派，不绑定具体传输层。 */
    handleRpc: (msg) => server.handleRpc(msg),
    /** 订阅已持久化的事件（seq 严格 +1）。 */
    subscribe(fn, { sessionId = null } = {}) {
      if (closed) throw new ClosedError("core is closed");
      if (typeof fn !== "function") throw new TypeError("subscribe(fn) requires a function");
      if (!sessionId) return bus.subscribe(fn);
      // 可选按会话过滤：核心级事件（sessionId=null）仍然推送，避免漏掉连接/通知。
      return bus.subscribe((event) => {
        if (!event?.sessionId || event.sessionId === sessionId) fn(event);
      });
    },
    close,
    /** 脱敏诊断快照（版本、健康、计数、适配器、恢复报告）。 */
    diagnostics() {
      if (!closed) {
        return {
          ...server.buildDiagnostics(),
          coreVersion: CORE_VERSION,
          protocolVersion: PROTOCOL_VERSION,
          dataDir: resolvedDataDir,
          startedAt,
          notificationList: notifications.list({ limit: 20 }),
        };
      }
      return {
        coreVersion: CORE_VERSION,
        protocolVersion: PROTOCOL_VERSION,
        dataDir: resolvedDataDir,
        closed: true,
        state: cachedState,
      };
    },
    /** 供客户端首屏/诊断使用的轻量状态。 */
    get state() {
      if (closed) return cachedState ?? { closed: true };
      return buildState();
    },
    get closed() {
      return closed;
    },
    /** 内部服务（PA/Sync/测试使用；客户端不得依赖）。 */
    services: {
      journal,
      receipts,
      bus,
      diagnostics: diagnosticsService,
      notifications,
      supervisor,
      terminal,
      artifacts,
      workspaces,
      lifecycle,
      server,
      get recovery() {
        return recoveryReport;
      },
    },
    get recovery() {
      return recoveryReport;
    },
  };
}

/**
 * CORE-106：带 Mock Adapter 的完整核心，供三界面契约测试与本地演示。
 * 额外暴露 `mock`，不改变 createCore 的契约签名。
 */
export function createMockCore(options = {}) {
  const clock = options.clock ?? (() => new Date());
  const adapter =
    options.adapter ??
    createMockAdapter({
      clock,
      script: options.script,
      instanceId: options.instanceId,
      version: options.version,
      provider: options.provider,
      capabilities: options.capabilities,
      latencyMs: options.latencyMs ?? 0,
    });
  const core = createCore({ ...options, clock, adapters: options.adapters ?? [adapter] });
  core.mock = {
    adapter,
    runScenario: (scenarioOptions = {}) => runUiScenario({ core, ...scenarioOptions }),
    setScript(patch) {
      adapter.script = { ...adapter.script, ...patch };
      return adapter.script;
    },
    trace: () => adapter.trace(),
  };
  return core;
}

/**
 * CORE-103 / C-11：stdio 传输入口。
 *
 *   node core/index.mjs [--data-dir <path>]
 *
 * 每行一个 JSON-RPC 请求；响应逐行输出；核心推送的事件按 `core.event` 通知包装，
 * 与 clients/shared/rpc-client.mjs 的 stdio transport 完全兼容。
 */
export async function runStdioCore({
  input = process.stdin,
  output = process.stdout,
  errorOutput = process.stderr,
  dataDir = undefined,
  argv = [],
} = {}) {
  const flagIndex = argv.indexOf("--data-dir");
  const resolvedDataDir =
    dataDir ??
    (flagIndex >= 0 ? argv[flagIndex + 1] : undefined) ??
    process.env.CODA_DATA_DIR ??
    path.join(os.homedir(), ".coda", "core");

  const core = createCore({ dataDir: resolvedDataDir });
  const write = (value) => output.write(`${JSON.stringify(value)}\n`);
  const unsubscribe = core.subscribe((event) => write(eventNotification(event)));
  const rl = readline.createInterface({ input, crlfDelay: Infinity });
  let chain = Promise.resolve();
  let closing = false;

  const shutdown = async () => {
    if (closing) return;
    closing = true;
    try {
      unsubscribe();
    } catch {
      // ignore
    }
    try {
      rl.close();
    } catch {
      // ignore
    }
    await core.close();
  };

  rl.on("line", (line) => {
    const text = String(line ?? "").trim();
    if (!text) return;
    chain = chain
      .then(async () => {
        let message;
        try {
          message = JSON.parse(text);
        } catch (err) {
          write(rpcError(null, RPC_ERRORS.PARSE_ERROR, `invalid JSON: ${err?.message ?? err}`));
          return;
        }
        const response = await core.handleRpc(message);
        if (response) write(response);
      })
      .catch((err) => {
        errorOutput.write(`coda core rpc error: ${err?.stack ?? err}\n`);
      });
  });

  rl.on("close", () => {
    void shutdown();
  });
  process.once("SIGTERM", () => {
    void shutdown();
  });
  process.once("SIGINT", () => {
    void shutdown();
  });

  return core;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  runStdioCore({ argv: process.argv.slice(2) }).catch((err) => {
    process.stderr.write(`coda core failed to start: ${err?.message ?? err}\n`);
    process.exitCode = 1;
  });
}

export { ConflictError, ClosedError };
