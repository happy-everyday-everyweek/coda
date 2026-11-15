/**
 * CORE-108：崩溃恢复与监督重启策略。
 *
 * 恢复原则（SPEC 15.1 / REL-001）：
 * - 重启后不得把未确认回合直接标成功：能核对就核对（probe），不能核对就标 unknown。
 * - 已 dispatched 但结果未落库的命令 → unknown，不盲目重放副作用。
 * - 投影用事件重放校验（verifyReplay），发现不一致要暴露在报告里。
 *
 * 重启退避（CORE-005）：有界指数退避，超过上限放弃并上报，绝不无限快速重启。
 */
import { TURN_STATES, RUN_STATES } from "../contracts/ids.mjs";
import { ValidationError } from "./errors.mjs";

const ACTIVE_TURN_STATES = new Set(["queued", "running", "stopping"]);
const ACTIVE_RUN_STATES = new Set(["queued", "running", "stopping"]);
const ACTIVE_SESSION_STATES = new Set(["running", "queued", "stopping"]);
const KNOWN_TURN_STATES = new Set(TURN_STATES);
const KNOWN_RUN_STATES = new Set(RUN_STATES);

const REASON = "core-restarted";

function makeClock(clock) {
  return () => {
    const d = clock();
    return (d instanceof Date ? d : new Date(d)).toISOString();
  };
}

function finishReport({ recoveredAt, commands, turns, runs, sessions, replay, diagnostics }) {
  const report = {
    recoveredAt,
    reason: REASON,
    commands,
    turns,
    runs,
    sessions,
    replay,
    counts: {
      commandsUnknown: commands.unknown.length,
      commandsExpired: commands.expired.length,
      turnsUnknown: turns.filter((t) => t.to === "unknown").length,
      runsUnknown: runs.filter((r) => r.to === "unknown").length,
      sessionsUpdated: sessions.length,
      replayOk: replay.ok,
    },
  };
  if (diagnostics) {
    diagnostics.log("info", "core recovery completed", {
      recoveredAt,
      counts: report.counts,
      commandsUnknown: commands.unknown,
    });
    if (!replay.ok) diagnostics.log("error", "projection replay mismatch", { before: replay.before, after: replay.after });
  }
  return report;
}

/** 同步恢复：不询问上游，一律 unknown（参考实现默认路径）。 */
export function recoverCoreSync({ journal, receipts, clock = () => new Date(), diagnostics = null, limit = 5000 } = {}) {
  if (!journal) throw new ValidationError("recoverCore requires a journal");
  if (!receipts) throw new ValidationError("recoverCore requires receipts");
  const nowIso = makeClock(clock);
  const recoveredAt = nowIso();
  const commands = receipts.recoverAfterCrash({ reason: "core restarted before command result was committed" });

  const turns = [];
  const runs = [];
  const sessions = [];
  for (const session of journal.listSessions({ limit, includeArchived: true })) {
    for (const turn of journal.listTurns(session.id).filter((t) => ACTIVE_TURN_STATES.has(t.state))) {
      const updated = journal.transaction(() => {
        const next = journal.upsertTurn({
          ...turn,
          state: "unknown",
          terminalConfidence: "unknown",
          stopReason: REASON,
          updatedAt: nowIso(),
          endedAt: nowIso(),
        });
        journal.appendEvent({
          kind: "turn",
          sessionId: session.id,
          ts: nowIso(),
          payload: { turn: next, state: "unknown", reason: REASON, previousState: turn.state },
        });
        return next;
      });
      turns.push({ sessionId: session.id, turnId: turn.id, from: turn.state, to: "unknown", turn: updated });
    }

    for (const run of journal.listRuns(session.id).filter((r) => ACTIVE_RUN_STATES.has(r.state))) {
      const updated = journal.transaction(() => {
        const next = journal.upsertRun({ ...run, state: "unknown", reason: REASON, updatedAt: nowIso(), endedAt: nowIso() });
        journal.appendEvent({
          kind: "run",
          sessionId: session.id,
          ts: nowIso(),
          payload: { run: next, state: "unknown", reason: REASON, previousState: run.state },
        });
        return next;
      });
      runs.push({ sessionId: session.id, runId: run.id, from: run.state, to: "unknown", run: updated });
    }

    if (ACTIVE_SESSION_STATES.has(session.state)) {
      const stillActive = journal.listTurns(session.id).filter((t) => ACTIVE_TURN_STATES.has(t.state));
      if (stillActive.length === 0) {
        const updated = journal.transaction(() => {
          const next = journal.upsertSession({ ...session, state: "unknown", updatedAt: nowIso() });
          journal.appendEvent({
            kind: "session",
            sessionId: session.id,
            ts: nowIso(),
            payload: { session: next, state: "unknown", reason: REASON },
          });
          return next;
        });
        sessions.push({ sessionId: session.id, from: session.state, to: "unknown", session: updated });
      }
    }
  }

  const replay = journal.verifyReplay();
  return finishReport({ recoveredAt, commands, turns, runs, sessions, replay, diagnostics });
}

/**
 * 异步恢复：可选 probe 向上游核对状态（可以是异步的）。
 * 没有 probe 时等价于 recoverCoreSync。
 */
export async function recoverCore({ probe = null, ...rest } = {}) {
  if (typeof probe !== "function") return recoverCoreSync(rest);
  const { journal, receipts, clock = () => new Date(), diagnostics = null, limit = 5000 } = rest;
  if (!journal) throw new ValidationError("recoverCore requires a journal");
  if (!receipts) throw new ValidationError("recoverCore requires receipts");

  const nowIso = makeClock(clock);
  const recoveredAt = nowIso();
  const commands = receipts.recoverAfterCrash({ reason: "core restarted before command result was committed" });
  const turns = [];
  const runs = [];
  const sessions = [];

  for (const session of journal.listSessions({ limit, includeArchived: true })) {
    for (const turn of journal.listTurns(session.id).filter((t) => ACTIVE_TURN_STATES.has(t.state))) {
      let nextState = "unknown";
      try {
        const probed = await probe({ kind: "turn", session, turn });
        if (KNOWN_TURN_STATES.has(probed) && !ACTIVE_TURN_STATES.has(probed)) nextState = probed;
      } catch (err) {
        diagnostics?.log?.("warn", "turn probe failed", { turnId: turn.id, error: String(err?.message ?? err) });
      }
      const updated = journal.transaction(() => {
        const next = journal.upsertTurn({
          ...turn,
          state: nextState,
          terminalConfidence: nextState === "unknown" ? "unknown" : "confirmed",
          stopReason: REASON,
          updatedAt: nowIso(),
          endedAt: nowIso(),
        });
        journal.appendEvent({
          kind: "turn",
          sessionId: session.id,
          ts: nowIso(),
          payload: { turn: next, state: nextState, reason: REASON, previousState: turn.state },
        });
        return next;
      });
      turns.push({ sessionId: session.id, turnId: turn.id, from: turn.state, to: nextState, turn: updated });
    }

    for (const run of journal.listRuns(session.id).filter((r) => ACTIVE_RUN_STATES.has(r.state))) {
      let nextState = "unknown";
      try {
        const probed = await probe({ kind: "run", session, run });
        if (KNOWN_RUN_STATES.has(probed) && !ACTIVE_RUN_STATES.has(probed)) nextState = probed;
      } catch (err) {
        diagnostics?.log?.("warn", "run probe failed", { runId: run.id, error: String(err?.message ?? err) });
      }
      const updated = journal.transaction(() => {
        const next = journal.upsertRun({ ...run, state: nextState, reason: REASON, updatedAt: nowIso(), endedAt: nowIso() });
        journal.appendEvent({
          kind: "run",
          sessionId: session.id,
          ts: nowIso(),
          payload: { run: next, state: nextState, reason: REASON, previousState: run.state },
        });
        return next;
      });
      runs.push({ sessionId: session.id, runId: run.id, from: run.state, to: nextState, run: updated });
    }

    if (ACTIVE_SESSION_STATES.has(session.state)) {
      const stillActive = journal.listTurns(session.id).filter((t) => ACTIVE_TURN_STATES.has(t.state));
      if (stillActive.length === 0) {
        const updated = journal.transaction(() => {
          const next = journal.upsertSession({ ...session, state: "unknown", updatedAt: nowIso() });
          journal.appendEvent({
            kind: "session",
            sessionId: session.id,
            ts: nowIso(),
            payload: { session: next, state: "unknown", reason: REASON },
          });
          return next;
        });
        sessions.push({ sessionId: session.id, from: session.state, to: "unknown", session: updated });
      }
    }
  }

  const replay = journal.verifyReplay();
  return finishReport({ recoveredAt, commands, turns, runs, sessions, replay, diagnostics });
}

/* ------------------------------------------------------------ restart policy */

/**
 * 有界指数退避。
 * @param {{baseMs?:number, maxMs?:number, maxAttempts?:number, stableAfterMs?:number}} [options]
 */
export function createRestartPolicy({ baseMs = 250, maxMs = 30_000, maxAttempts = 8, stableAfterMs = 60_000 } = {}) {
  if (!Number.isFinite(baseMs) || baseMs < 0) throw new ValidationError("baseMs must be >= 0");
  if (!Number.isFinite(maxMs) || maxMs < baseMs) throw new ValidationError("maxMs must be >= baseMs");
  if (!Number.isInteger(maxAttempts) || maxAttempts < 1) throw new ValidationError("maxAttempts must be a positive integer");

  return {
    baseMs,
    maxMs,
    maxAttempts,
    stableAfterMs,
    /** attempt 从 0 开始；返回本次重启前应等待的毫秒数。 */
    delayFor(attempt) {
      if (!Number.isInteger(attempt) || attempt < 0) throw new ValidationError("attempt must be a non-negative integer");
      return Math.min(baseMs * 2 ** attempt, maxMs);
    },
    shouldGiveUp(attempt) {
      return attempt >= maxAttempts;
    },
  };
}

/**
 * 崩溃 → 退避决策。记录 lastStartAt，稳定运行超过 stableAfterMs 后清零尝试次数。
 */
export function createRestartController({ policy = createRestartPolicy(), clock = () => new Date() } = {}) {
  const nowMs = () => {
    const d = clock();
    return (d instanceof Date ? d : new Date(d)).getTime();
  };
  let attempts = 0;
  let lastStartAt = null;
  const history = [];

  return {
    get attempts() {
      return attempts;
    },
    get history() {
      return [...history];
    },
    markStarted() {
      lastStartAt = nowMs();
      return lastStartAt;
    },
    /** @returns {{giveUp:boolean, attempt:number, delayMs:number, reason:string}} */
    recordCrash(reason = "crash") {
      const at = nowMs();
      if (lastStartAt !== null && at - lastStartAt >= policy.stableAfterMs) attempts = 0;
      if (policy.shouldGiveUp(attempts)) {
        history.push({ at: new Date(at).toISOString(), reason, attempt: attempts, giveUp: true });
        return { giveUp: true, attempt: attempts, delayMs: 0, reason };
      }
      const delayMs = policy.delayFor(attempts);
      attempts += 1;
      history.push({ at: new Date(at).toISOString(), reason, attempt: attempts, delayMs, giveUp: false });
      return { giveUp: false, attempt: attempts, delayMs, reason };
    },
    reset() {
      attempts = 0;
      lastStartAt = null;
    },
  };
}
