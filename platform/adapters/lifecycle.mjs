/**
 * 会话 / 回合生命周期辅助（AGT-107 / ADP-004~009 / SPEC 8.3）。
 *
 * 只做状态与记录语义，**不持久化**：
 *   - 排队：FIFO、按 messageId 去重，重试不会生成第二条用户消息（ADP-007）。
 *   - 插话：有原生 steer 就用；没有就显式降级 stop → resume → append，并把
 *     原会话 ID、原回合 ID、保留的部分输出、新消息 ID 全部记录（SPEC 8.3）。
 *   - 停止：状态必须来自上游确认或可解释的进程终止；拿不到就记 unknown，不改 UI（ADP-005）。
 *   - 恢复失败：抛 RESUME_FAILED，且明确不新建空会话顶替（SPEC 8.3 第 6 条）。
 *   - 命令收据：用 contracts/commands.mjs 生成命令对象，交给 core 去持久化。
 */
import { createCommand, transition } from "../contracts/commands.mjs";
import { makeId, nowIso } from "../contracts/ids.mjs";
import { supports } from "../contracts/capabilities.mjs";
import { AdapterError } from "./base.mjs";

export const STEER_STRATEGIES = ["native-steer", "stop-resume-append", "direct-append"];

export const STOP_TRUST = ["upstream-confirmed", "process-termination", "unknown"];

export class SessionLifecycle {
  constructor({ adapter, sessionId = null, maxQueue = 32, clock = () => Date.now() } = {}) {
    if (!adapter) throw new AdapterError("NOT_IMPLEMENTED", "SessionLifecycle requires an adapter");
    this.adapter = adapter;
    this.sessionId = sessionId ?? makeId("ses");
    this.maxQueue = maxQueue;
    this.clock = clock;
    this.state = "idle";
    this.turn = null;
    this.queue = [];
    this.commands = [];
    this.steerRecords = [];
    this.stopRecords = [];
    this._turnDone = null;
  }

  // ------------------------------------------------------------ 能力

  supports(key, sub = null) {
    let declaration;
    try {
      declaration = this.adapter.capabilities();
    } catch {
      return false;
    }
    return supports(declaration, key, sub);
  }

  // ------------------------------------------------------------ 命令收据

  _command(kind, payload = {}) {
    const command = createCommand({ kind, payload, ttlMs: 60_000 });
    transition(command, "dispatched");
    this.commands.push(command);
    return command;
  }

  _finishCommand(command, state, result = null) {
    try {
      transition(command, state, result);
    } catch {
      /* 已是终态：重试幂等，不重复记账 */
    }
    return command;
  }

  // ------------------------------------------------------------ 排队

  /** 入队；同一 messageId 重复投递返回原条目，不重复排队（ADP-007）。 */
  enqueue({ messageId, text = null, attachments = [] } = {}) {
    if (!messageId) throw new AdapterError("PROTOCOL_ERROR", "enqueue requires messageId");
    const existing = this.queue.find((item) => item.messageId === messageId);
    if (existing) return { ...existing, duplicate: true, position: this.queue.indexOf(existing) + 1 };
    if (this.queue.length >= this.maxQueue) {
      throw new AdapterError("TURN_IN_PROGRESS", `queue is full (${this.maxQueue})`, { details: { sessionId: this.sessionId } });
    }
    const item = { messageId, text, attachments, queuedAt: nowIso() };
    this.queue.push(item);
    this.state = this.turn ? this.state : "queued";
    return { ...item, duplicate: false, position: this.queue.length };
  }

  dequeue() {
    const item = this.queue.shift() ?? null;
    if (!this.queue.length && !this.turn) this.state = "idle";
    return item;
  }

  hasQueued() {
    return this.queue.length > 0;
  }

  queuedIds() {
    return this.queue.map((item) => item.messageId);
  }

  // ------------------------------------------------------------ 回合

  /** 运行一个回合；返回上游结算结果（含 state/stopReason）。 */
  async runTurn({ messageId, text = null, attachments = [] } = {}) {
    if (this.turn) {
      return { queued: true, ...this.enqueue({ messageId, text, attachments }) };
    }
    const turn = { turnId: makeId("turn"), messageId, state: "running", startedAt: nowIso(), preservedEvents: 0 };
    this.turn = turn;
    this.state = "running";
    const command = this._command("turn.send", { messageId, turnId: turn.turnId });
    // 保留停止前的部分输出证据（SPEC 8.3 第 2 条）。
    const off =
      typeof this.adapter.onEvent === "function"
        ? this.adapter.onEvent((event) => {
            if (event?.sessionId !== this.sessionId) return;
            if (event.kind === "message" || event.kind === "tool" || event.kind === "reasoning") turn.preservedEvents += 1;
          })
        : null;
    const promise = (async () => {
      try {
        const result = await this.adapter.send({
          sessionId: this.sessionId,
          text,
          attachments,
          turnId: turn.turnId,
        });
        turn.state = result?.state ?? "completed";
        turn.stopReason = result?.stopReason ?? null;
        this.state = turn.state === "stopped" ? "stopped" : "idle";
        this._finishCommand(command, "succeeded", { state: turn.state, stopReason: turn.stopReason });
        return { ...result, turnId: turn.turnId, messageId };
      } catch (error) {
        const code = error instanceof AdapterError ? error.code : "UNKNOWN";
        if (code === "TIMEOUT" || code === "PROCESS_EXIT") {
          turn.state = "unknown";
          this.state = "unknown";
          this._finishCommand(command, "unknown", { code });
        } else {
          turn.state = "failed";
          this.state = "failed";
          this._finishCommand(command, "failed", { code });
        }
        throw error;
      } finally {
        off?.();
        this._turnDone = null;
        if (this.turn === turn) this.turn = null;
      }
    })();
    this._turnDone = promise;
    return promise;
  }

  /** 标记结果未知（崩溃/超时后不得改写成成功或失败）。 */
  markResultUnknown({ turnId = null, reason = "process-crashed" } = {}) {
    const turn = this.turn;
    if (!turn) return { state: "unknown", turnId, reason, changed: false };
    if (turnId && turn.turnId !== turnId) {
      throw new AdapterError("PROTOCOL_ERROR", `turn mismatch: running ${turn.turnId}, requested ${turnId}`);
    }
    turn.state = "unknown";
    this.state = "unknown";
    return { state: "unknown", turnId: turn.turnId, reason, changed: true };
  }

  // ------------------------------------------------------------ 停止

  /**
   * 停止当前回合。返回值必须带真实来源：
   *   upstream-confirmed / process-termination / unknown
   */
  async stop({ turnId = null, reason = null } = {}) {
    const turn = this.turn;
    if (!turn) return { sessionId: this.sessionId, state: this.state, trust: "upstream-confirmed", alreadyIdle: true };
    if (turnId && turn.turnId !== turnId) {
      throw new AdapterError("PROTOCOL_ERROR", `turn mismatch: running ${turn.turnId}, requested ${turnId}`);
    }
    const command = this._command("turn.stop", { turnId: turn.turnId });
    let info = null;
    let error = null;
    try {
      info = await this.adapter.interrupt({ sessionId: this.sessionId, turnId: turn.turnId });
    } catch (err) {
      error = err;
    }
    const done = this._turnDone;
    let settled = null;
    if (done) {
      try {
        settled = await done;
      } catch (err) {
        settled = { state: err?.code === "TIMEOUT" ? "unknown" : "failed", code: err?.code ?? "UNKNOWN" };
      }
    }
    const state = settled?.state ?? info?.state ?? (error ? "unknown" : "stopped");
    const trust =
      state === "stopped"
        ? error
          ? "process-termination"
          : "upstream-confirmed"
        : "unknown";
    const record = {
      sessionId: this.sessionId,
      turnId: turn.turnId,
      state,
      trust,
      reason,
      stopReason: settled?.stopReason ?? null,
      upstream: info ?? null,
      closedInteractions: info?.closedInteractions ?? 0,
      at: nowIso(),
    };
    this.stopRecords.push(record);
    if (state === "unknown") {
      turn.state = "unknown";
      this.state = "unknown";
      this._finishCommand(command, "unknown", record);
    } else {
      turn.state = state;
      this.state = state === "stopped" ? "stopped" : "idle";
      this._finishCommand(command, "succeeded", record);
    }
    return record;
  }

  // ------------------------------------------------------------ 插话

  /**
   * 插话：
   *   - 无运行中回合 → 直接追加（direct-append）。
   *   - 原生 steer 可用 → native-steer。
   *   - 否则 → stop → resume → append，记录完整证据；恢复失败抛 RESUME_FAILED 且不新建会话。
   */
  async steer({ messageId, text = null, attachments = [] } = {}) {
    if (!messageId) throw new AdapterError("PROTOCOL_ERROR", "steer requires messageId");
    const turn = this.turn;
    if (!turn) {
      const result = await this.runTurn({ messageId, text, attachments });
      return { strategy: "direct-append", ok: true, messageId, result };
    }

    if (this.supports("turn", "nativeSteer")) {
      const command = this._command("turn.steer", { messageId, turnId: turn.turnId });
      try {
        const result = await this.adapter.steer({ sessionId: this.sessionId, text, attachments });
        this._finishCommand(command, "succeeded", result ?? null);
        return { strategy: "native-steer", ok: true, turnId: turn.turnId, messageId, result };
      } catch (error) {
        this._finishCommand(command, "failed", { code: error?.code ?? "UNKNOWN" });
        throw error;
      }
    }

    return this._steerByStopResumeAppend({ messageId, text, attachments, turn });
  }

  async _steerByStopResumeAppend({ messageId, text, attachments, turn }) {
    const session = (() => {
      try {
        return this.adapter.getSession(this.sessionId);
      } catch {
        return null;
      }
    })();
    const record = {
      strategy: "stop-resume-append",
      sessionId: this.sessionId,
      originalTurnId: turn.turnId,
      originalProviderSessionId: session?.providerSessionId ?? null,
      newMessageId: messageId,
      preservedOutput: turn.preservedEvents ?? 0,
      steps: [],
      stopReason: null,
      resumedProviderSessionId: null,
      createdNewSession: false,
      ok: false,
      at: nowIso(),
    };
    this.steerRecords.push(record);

    // 1) 停止旧回合，并等它真正结算（不能一边旧回合在跑一边假装插话成功）。
    const stop = await this.stop({ turnId: turn.turnId, reason: "steer" });
    record.stopReason = stop.stopReason ?? stop.state;
    record.steps.push({ step: "stop", state: stop.state, trust: stop.trust, closedInteractions: stop.closedInteractions });
    if (stop.state === "unknown") {
      // 结果未知时不能继续把新消息塞进旧回合；也不新建会话顶替。
      throw new AdapterError("STOP_UNCONFIRMED", "turn stop could not be confirmed; refusing to fake a steer", {
        details: { sessionId: this.sessionId, turnId: turn.turnId },
      });
    }

    // 2) 恢复原会话。
    const resumeCommand = this._command("session.resume", { providerSessionId: record.originalProviderSessionId });
    try {
      const resumed = await this.adapter.resume({ sessionId: this.sessionId });
      record.resumedProviderSessionId = resumed?.providerSessionId ?? record.originalProviderSessionId;
      this._finishCommand(resumeCommand, "succeeded", resumed ?? null);
      record.steps.push({ step: "resume", ok: true, providerSessionId: record.resumedProviderSessionId });
    } catch (error) {
      const code = error?.code ?? "UNKNOWN";
      this._finishCommand(resumeCommand, "failed", { code });
      record.steps.push({ step: "resume", ok: false, code });
      this.state = "failed";
      throw new AdapterError(
        "RESUME_FAILED",
        `resume failed (${code}); refusing to create a blank session as a substitute`,
        { cause: error, details: { sessionId: this.sessionId, providerSessionId: record.originalProviderSessionId } },
      );
    }

    // 3) 追加新指令（只持久化一次，messageId 由 core 去重）。
    record.steps.push({ step: "append", messageId });
    const result = await this.runTurn({ messageId, text, attachments });
    record.ok = true;
    record.result = { state: result?.state ?? null, stopReason: result?.stopReason ?? null };
    return { ...record, result };
  }

  // ------------------------------------------------------------ 快照

  snapshot() {
    return {
      sessionId: this.sessionId,
      state: this.state,
      turn: this.turn
        ? { turnId: this.turn.turnId, messageId: this.turn.messageId, state: this.turn.state }
        : null,
      queue: this.queue.map((item) => item.messageId),
      commands: this.commands.map((c) => ({ id: c.id, kind: c.kind, state: c.state })),
      steerRecords: this.steerRecords.map((r) => ({
        strategy: r.strategy,
        originalTurnId: r.originalTurnId,
        newMessageId: r.newMessageId,
        ok: r.ok,
        steps: r.steps,
      })),
      stopRecords: [...this.stopRecords],
    };
  }
}

export function createSessionLifecycle(options) {
  return new SessionLifecycle(options);
}
