/** CORE-108：崩溃恢复把未完成回合/命令标为 unknown，并给出有界重启退避。 */
import test from "node:test";
import assert from "node:assert/strict";

import { Journal } from "../../core/journal.mjs";
import { ReceiptStore } from "../../core/receipts.mjs";
import { createRestartController, createRestartPolicy, recoverCoreSync } from "../../core/recovery.mjs";
import { createRun, createSession, createTurn } from "../../core/domain.mjs";
import { cleanupDir, fixedClock, makeTempDir } from "../../fixtures/core/helpers.mjs";

test("恢复：未完成回合/运行/会话标 unknown，dispatched 命令标 unknown", () => {
  const dir = makeTempDir("coda-recovery-");
  const journal = new Journal({ dataDir: dir, clock: fixedClock() });
  journal.open();
  try {
    const receipts = new ReceiptStore({ journal, clock: fixedClock() });
    const session = createSession({
      id: "ses_recovery_1",
      nodeId: "dev_recovery",
      environmentId: "env_recovery",
      agentInstanceId: "ai_recovery",
      workspaceId: "ws_recovery",
      state: "running",
    });
    const run = createRun({ id: "run_recovery_1", sessionId: session.id, state: "running" });
    const turn = createTurn({
      id: "turn_recovery_1",
      runId: run.id,
      sessionId: session.id,
      state: "running",
    });

    journal.transaction(() => {
      journal.upsertSession(session);
      journal.appendEvent({
        kind: "session",
        sessionId: session.id,
        payload: { session, state: "running" },
      });
      journal.upsertRun(run);
      journal.appendEvent({ kind: "run", sessionId: session.id, payload: { run, state: "running" } });
      journal.upsertTurn(turn);
      journal.appendEvent({ kind: "turn", sessionId: session.id, payload: { turn, state: "running" } });
    });

    const command = receipts.accept({ kind: "turn.send", payload: { text: "hi" } });
    receipts.dispatch(command.receipt.commandId);

    const report = recoverCoreSync({ journal, receipts, clock: fixedClock() });

    assert.equal(journal.getTurn(turn.id).state, "unknown");
    assert.equal(journal.getTurn(turn.id).stopReason, "core-restarted");
    assert.equal(journal.getRun(run.id).state, "unknown");
    assert.equal(journal.getSession(session.id).state, "unknown");
    assert.equal(receipts.get(command.receipt.commandId).state, "unknown");

    assert.equal(report.turns.length, 1);
    assert.equal(report.runs.length, 1);
    assert.equal(report.sessions.length, 1);
    assert.deepEqual(report.commands.unknown, [command.receipt.commandId]);
    assert.equal(report.replay.ok, true, JSON.stringify(report.replay));

    // 恢复后会话投影仍可读，且没有伪造成功状态。
    const projection = journal.sessionProjection(session.id);
    assert.equal(projection.session.state, "unknown");
    assert.equal(projection.turns[0].state, "unknown");
  } finally {
    journal.close();
    cleanupDir(dir);
  }
});

test("恢复：已完成回合不被改写", () => {
  const dir = makeTempDir("coda-recovery-");
  const journal = new Journal({ dataDir: dir, clock: fixedClock() });
  journal.open();
  try {
    const receipts = new ReceiptStore({ journal, clock: fixedClock() });
    const session = createSession({
      id: "ses_recovery_done",
      nodeId: "dev_recovery",
      environmentId: "env_recovery",
      agentInstanceId: "ai_recovery",
      workspaceId: "ws_recovery",
      state: "idle",
    });
    const turn = createTurn({
      id: "turn_done",
      sessionId: session.id,
      state: "completed",
      terminalConfidence: "confirmed",
    });
    journal.transaction(() => {
      journal.upsertSession(session);
      journal.appendEvent({ kind: "session", sessionId: session.id, payload: { session, state: "idle" } });
      journal.upsertTurn(turn);
      journal.appendEvent({ kind: "turn", sessionId: session.id, payload: { turn, state: "completed" } });
    });

    const report = recoverCoreSync({ journal, receipts, clock: fixedClock() });
    assert.equal(report.turns.length, 0);
    assert.equal(journal.getTurn(turn.id).state, "completed");
    assert.equal(journal.getSession(session.id).state, "idle");
  } finally {
    journal.close();
    cleanupDir(dir);
  }
});

test("重启退避有界且稳定运行后会清零", () => {
  const policy = createRestartPolicy({ baseMs: 100, maxMs: 1_000, maxAttempts: 3, stableAfterMs: 60_000 });
  assert.deepEqual([0, 1, 2, 3, 4, 10].map((n) => policy.delayFor(n)), [100, 200, 400, 800, 1_000, 1_000]);
  assert.equal(policy.shouldGiveUp(2), false);
  assert.equal(policy.shouldGiveUp(3), true);

  let now = Date.parse("2026-01-01T00:00:00.000Z");
  const clock = () => new Date(now);
  const controller = createRestartController({ policy, clock });
  controller.markStarted();

  const first = controller.recordCrash("crash-1");
  assert.deepEqual({ giveUp: first.giveUp, attempt: first.attempt, delayMs: first.delayMs }, { giveUp: false, attempt: 1, delayMs: 100 });
  now += 1_000;
  const second = controller.recordCrash("crash-2");
  assert.equal(second.delayMs, 200);
  assert.equal(second.attempt, 2);
  now += 1_000;
  controller.recordCrash("crash-3");
  now += 1_000;
  const fourth = controller.recordCrash("crash-4");
  assert.equal(fourth.giveUp, true);

  // 稳定运行超过 stableAfterMs 后重新计数。
  now += 70_000;
  const afterStable = controller.recordCrash("crash-after-stable");
  assert.equal(afterStable.attempt, 1);
  assert.equal(afterStable.delayMs, 100);

  controller.reset();
  assert.equal(controller.attempts, 0);
});
