/**
 * 可靠性状态辅助与故障注入 harness（REL-101 / REL-102）。
 *
 * 核心原则（SPEC 11.5 / 15.1）：
 * - 外部副作用前先持久化 intent；结果提交失败/进程崩溃后，重启**不得**盲目重试；
 * - 未确认回合标记中断/未知，绝不改成成功；
 * - 进程正常退出与异常退出可区分；失败回合的审批/提问全部结算；
 * - 终端在核心重启后关闭并标记原因，不遗留“永远在线”的假状态。
 *
 * 本模块不执行真实 Agent；它提供状态机与可复现的故障注入原语，供 core/测试使用。
 */
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";

export const EXIT_KINDS = Object.freeze(["normal", "crash", "killed", "timeout", "spawn_failed", "unknown"]);
export const RECOVERY_STATES = Object.freeze(["intent", "succeeded", "failed", "unknown"]);

export class InjectedFault extends Error {
  constructor(kind, point, details = {}) {
    super(`injected ${kind} at ${point}`);
    this.name = "InjectedFault";
    this.code = `INJECTED_${kind.toUpperCase()}`;
    this.kind = kind;
    this.point = point;
    this.details = details;
  }
}

/** 区分正常完成、崩溃、被信号杀死、超时与启动失败。 */
export function classifyProcessExit({ code = null, signal = null, expected = false, timedOut = false, spawnError = null } = {}) {
  if (spawnError) {
    return { kind: "spawn_failed", clean: false, retryable: true, expected, code, signal, error: String(spawnError.message ?? spawnError) };
  }
  if (timedOut) return { kind: "timeout", clean: false, retryable: true, expected, code, signal };
  if (signal) {
    return {
      kind: "killed",
      clean: false,
      retryable: false,
      expected,
      code,
      signal,
      note: "process terminated by signal; an in-flight turn must be marked interrupted/unknown",
    };
  }
  if (code === 0) return { kind: "normal", clean: true, retryable: false, expected, code, signal, note: "normal completion" };
  if (typeof code === "number") {
    return {
      kind: "crash",
      clean: false,
      retryable: false,
      expected,
      code,
      signal,
      note: "non-zero exit; treat as failed/interrupted, never as success",
    };
  }
  return { kind: "unknown", clean: false, retryable: false, expected, code, signal };
}

/**
 * 持久化「先写意图、后写结果」的日志。recover 永远不重新执行外部 I/O。
 */
export function createRecoveryJournal({ storage, clock = Date.now } = {}) {
  if (!storage) throw new InjectedFault("config", "createRecoveryJournal", { reason: "storage required" });
  const KEY = "recovery.intents";
  const iso = () => new Date(clock()).toISOString();
  const read = () => storage.kvGet(KEY, {}) ?? {};
  const write = (intents) => storage.kvSet(KEY, intents);

  return {
    /** 在发起外部副作用前调用（必须已持久化）。 */
    beginIntent({ id = randomUUID(), kind, sessionId = null, payload = null, commandId = null } = {}) {
      if (!kind) throw new InjectedFault("config", "beginIntent", { reason: "kind required" });
      const intents = read();
      if (intents[id]) return { ...intents[id] };
      const intent = {
        id,
        kind,
        sessionId,
        commandId,
        payload,
        state: "intent",
        at: iso(),
        updatedAt: iso(),
        result: null,
        reason: null,
      };
      intents[id] = intent;
      write(intents);
      return { ...intent };
    },

    commitResult(id, { state = "succeeded", result = null } = {}) {
      if (state !== "succeeded" && state !== "failed") {
        throw new InjectedFault("config", "commitResult", { reason: "state must be succeeded|failed" });
      }
      const intents = read();
      const intent = intents[id];
      if (!intent) throw new InjectedFault("config", "commitResult", { reason: `unknown intent ${id}` });
      intent.state = state;
      intent.result = result;
      intent.updatedAt = iso();
      write(intents);
      return { ...intent };
    },

    markUnknown(id, { reason = "outcome_unknown" } = {}) {
      const intents = read();
      const intent = intents[id];
      if (!intent) return null;
      intent.state = "unknown";
      intent.reason = reason;
      intent.updatedAt = iso();
      write(intents);
      return { ...intent };
    },

    pending() {
      return Object.values(read()).filter((intent) => intent.state === "intent");
    },

    /**
     * 重启恢复：对每个未提交意图，先尝试只读核对（reconcile），否则标 unknown。
     * 不重放外部操作；blindRetries 恒为 0。
     */
    recover({ reconcile = null } = {}) {
      const intents = read();
      const unknown = [];
      const reconciled = [];
      for (const intent of Object.values(intents)) {
        if (intent.state !== "intent") continue;
        let outcome = null;
        if (typeof reconcile === "function") {
          try {
            outcome = reconcile({ ...intent });
          } catch {
            outcome = null;
          }
        }
        if (outcome === "succeeded" || outcome === "failed") {
          intent.state = outcome;
          intent.updatedAt = iso();
          intent.result = { reconciled: true };
          reconciled.push({ ...intent });
        } else {
          intent.state = "unknown";
          intent.reason = "no_confirmed_result_after_restart";
          intent.updatedAt = iso();
          unknown.push({ ...intent });
        }
      }
      write(intents);
      return { unknown, reconciled, blindRetries: 0 };
    },

    list() {
      return Object.values(read()).map((intent) => ({ ...intent }));
    },

    clear() {
      storage.kvDelete(KEY);
    },
  };
}

/**
 * 重启后回合恢复：running/stopping → unknown（interrupted），终态不变，
 * queued 保持 queued（尚未产生外部副作用）。
 */
export function recoverTurns({ turns = [], now = Date.now } = {}) {
  const at = new Date(now()).toISOString();
  const unknown = [];
  const recovered = turns.map((turn) => {
    const next = { ...turn };
    if (["completed", "failed", "unknown"].includes(turn.state)) return next;
    if (turn.state === "running" || turn.state === "stopping") {
      next.state = "unknown";
      next.interrupted = true;
      next.interruptedAt = at;
      next.reason = "core_restart";
      unknown.push(next);
    }
    return next;
  });
  return { turns: recovered, unknown, interrupted: unknown.length };
}

/** 会话状态同理：running/queued 不因重启变成 idle（会掩盖未确认工作）。 */
export function recoverSessions({ sessions = [], now = Date.now } = {}) {
  const at = new Date(now()).toISOString();
  const unknown = [];
  const recovered = sessions.map((session) => {
    const next = { ...session };
    if (session.state === "running") {
      next.state = "unknown";
      next.reason = "core_restart";
      next.recoveredAt = at;
      unknown.push(next);
    }
    return next;
  });
  return { sessions: recovered, unknown };
}

/**
 * 终端恢复：核心重启后所有“存活”终端实际已失效，必须关闭并说明原因，
 * 不能显示为可交互。
 */
export function recoverTerminals({ terminals = [], now = Date.now } = {}) {
  const at = new Date(now()).toISOString();
  const closed = [];
  const recovered = terminals.map((terminal) => {
    const status = terminal.status ?? terminal.state ?? "unknown";
    if (status === "open" || status === "running" || status === "alive") {
      const next = {
        ...terminal,
        status: "closed",
        state: "closed",
        closedAt: at,
        closeReason: "core_restart",
        interactive: false,
      };
      closed.push(next);
      return next;
    }
    return { ...terminal };
  });
  return { terminals: recovered, closed, orphaned: [] };
}

/** 失败回合结算审批/提问：pending → cancelled，不留永远等待。 */
export function settlePendingInteractions(interactions = [], { reason = "agent_crashed", now = Date.now } = {}) {
  const at = new Date(now()).toISOString();
  return interactions.map((interaction) => {
    if (interaction.status !== "pending") return { ...interaction };
    return {
      ...interaction,
      status: "cancelled",
      cancelledReason: reason,
      updatedAt: at,
      settledAt: at,
    };
  });
}

/** 可复现的伪随机数（mulberry32），用于故障注入概率。 */
export function createSeededRandom(seed = 1) {
  let state = Number(seed) >>> 0 || 1;
  return function random() {
    state |= 0;
    state = (state + 0x6d2b79f5) | 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** 真实超时（毫秒），用于注入“上游无响应”。 */
export function withTimeout(promise, ms, { label = "operation" } = {}) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new InjectedFault("timeout", label, { ms })), ms);
    }),
  ]).finally(() => clearTimeout(timer));
}

/**
 * 故障注入 harness：crash / timeout / disconnect / kill 子进程 / 旧版本，
 * 全部可用 seed 复现。
 */
export function createFaultHarness({ seed = 1, clock = Date.now } = {}) {
  const random = createSeededRandom(seed);
  const events = [];
  return {
    seed,
    random,
    events,

    crash(point = "unspecified", details = {}) {
      events.push({ kind: "crash", point, at: clock() });
      throw new InjectedFault("crash", point, details);
    },

    maybeCrash(point = "unspecified", probability = 0.5, details = {}) {
      if (random() < probability) return this.crash(point, details);
      return null;
    },

    timeout(point = "unspecified", ms = 10) {
      events.push({ kind: "timeout", point, at: clock() });
      return new Promise((_, reject) => {
        setTimeout(() => reject(new InjectedFault("timeout", point, { ms })), ms);
      });
    },

    /** 模拟断连：关闭通道两端；重连需显式建立新通道。 */
    disconnect(transport, reason = "network_partition") {
      events.push({ kind: "disconnect", reason, at: clock() });
      transport?.close?.({ reason });
      return { disconnected: true, reason };
    },

    /** 模拟旧版本对端：只返回一个不兼容的版本列表。 */
    oldProtocolVersions() {
      return [99];
    },

    /** 真实子进程 + 信号杀死，返回退出分类。 */
    async spawnAndKill({
      script = "setInterval(() => {}, 1000)",
      signal = "SIGKILL",
      settleMs = 50,
      timeoutMs = 5000,
    } = {}) {
      const child = spawn(process.execPath, ["-e", script], { stdio: "ignore" });
      const exit = new Promise((resolve) => {
        child.once("exit", (code, exitSignal) => resolve({ code, signal: exitSignal }));
        child.once("error", (err) => resolve({ code: null, signal: null, error: err }));
      });
      try {
        await new Promise((resolve, reject) => {
          child.once("spawn", resolve);
          child.once("error", reject);
        });
        await new Promise((resolve) => {
          const timer = setTimeout(resolve, settleMs);
          timer.unref?.();
        });
        child.kill(signal);
        const result = await withTimeout(exit, timeoutMs, { label: "child-exit" });
        events.push({ kind: "kill", signal, at: clock() });
        return { exit: result, classification: classifyProcessExit(result) };
      } catch (err) {
        child.kill("SIGKILL");
        return {
          exit: { code: null, signal: null, error: err },
          classification: classifyProcessExit({ spawnError: err }),
        };
      }
    },

    /** 确定性重试：同样的 seed/policy 得到同样的尝试序列。 */
    async runWithRetries(fn, { attempts = 3, onAttempt = null } = {}) {
      let lastError = null;
      for (let attempt = 1; attempt <= attempts; attempt += 1) {
        try {
          const value = await fn(attempt);
          if (onAttempt) onAttempt({ attempt, ok: true });
          return { ok: true, value, attempts: attempt };
        } catch (err) {
          lastError = err;
          if (onAttempt) onAttempt({ attempt, ok: false, error: err });
        }
      }
      return { ok: false, error: lastError, attempts };
    },

    snapshot() {
      return { seed, events: [...events] };
    },
  };
}
