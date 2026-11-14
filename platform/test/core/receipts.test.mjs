/** CORE-105：命令收据幂等、状态机、过期/取代、结果未知。 */
import test from "node:test";
import assert from "node:assert/strict";

import { Journal } from "../../core/journal.mjs";
import { ReceiptStore } from "../../core/receipts.mjs";
import { fixedClock, makeTempDir, cleanupDir, mutableClock } from "../../fixtures/core/helpers.mjs";

function setup({ clock = fixedClock() } = {}) {
  const dir = makeTempDir("coda-receipts-");
  const journal = new Journal({ dataDir: dir, clock });
  journal.open();
  const receipts = new ReceiptStore({ journal, clock });
  return {
    dir,
    journal,
    receipts,
    cleanup() {
      journal.close();
      cleanupDir(dir);
    },
  };
}

test("accepted → dispatched → succeeded，重复投递返回原收据", () => {
  const ctx = setup();
  try {
    const first = ctx.receipts.accept({ kind: "turn.send", payload: { text: "hi" } });
    assert.equal(first.created, true);
    assert.equal(first.receipt.state, "accepted");
    assert.equal(first.receipt.attempts, 0);

    const duplicate = ctx.receipts.accept({
      id: first.receipt.commandId,
      kind: "turn.send",
      payload: { text: "should be ignored" },
    });
    assert.equal(duplicate.deduped, true);
    assert.equal(duplicate.created, false);
    assert.equal(duplicate.receipt.commandId, first.receipt.commandId);
    assert.deepEqual(duplicate.receipt.payload, { text: "hi" });

    const dispatched = ctx.receipts.dispatch(first.receipt.commandId);
    assert.equal(dispatched.receipt.state, "dispatched");
    assert.equal(dispatched.receipt.attempts, 1);
    assert.ok(dispatched.receipt.dispatchedAt);

    const settled = ctx.receipts.settle(first.receipt.commandId, "succeeded", { result: { ok: true } });
    assert.equal(settled.state, "succeeded");
    assert.equal(settled.terminal, true);
    assert.deepEqual(settled.result, { ok: true });
    assert.equal(settled.receipt.state, "succeeded");

    // 终态幂等：再次 settle 不改状态。
    const again = ctx.receipts.settle(first.receipt.commandId, "failed");
    assert.equal(again.state, "succeeded");
    assert.equal(ctx.receipts.countByState().succeeded, 1);
  } finally {
    ctx.cleanup();
  }
});

test("未 dispatch 不能直接 settle", () => {
  const ctx = setup();
  try {
    const accepted = ctx.receipts.accept({ kind: "turn.stop" });
    assert.throws(() => ctx.receipts.settle(accepted.receipt.commandId, "succeeded"), /must be dispatched/);
  } finally {
    ctx.cleanup();
  }
});

test("过期命令不再派发，状态为 expired", () => {
  const clock = mutableClock("2026-01-01T00:00:00.000Z");
  const ctx = setup({ clock });
  try {
    const accepted = ctx.receipts.accept({ kind: "turn.send", ttlMs: 1_000 });
    assert.equal(accepted.receipt.state, "accepted");
    clock.advance(2_000);
    const dispatched = ctx.receipts.dispatch(accepted.receipt.commandId);
    assert.equal(dispatched.receipt.state, "expired");
    assert.equal(dispatched.receipt.terminal, true);
    assert.equal(ctx.receipts.countByState().expired, 1);
  } finally {
    ctx.cleanup();
  }
});

test("结果未知是合法终态，且不会被重试成成功", () => {
  const ctx = setup();
  try {
    const accepted = ctx.receipts.accept({ kind: "turn.stop" });
    ctx.receipts.dispatch(accepted.receipt.commandId);
    const unknown = ctx.receipts.markUnknown(accepted.receipt.commandId, { reason: "core crashed" });
    assert.equal(unknown.state, "unknown");
    assert.equal(unknown.terminal, true);
    assert.equal(unknown.error.reason, "core crashed");

    const duplicate = ctx.receipts.accept({ id: accepted.receipt.commandId, kind: "turn.stop" });
    assert.equal(duplicate.deduped, true);
    assert.equal(duplicate.receipt.state, "unknown");
    assert.equal(ctx.receipts.countByState().unknown, 1);
  } finally {
    ctx.cleanup();
  }
});

test("新命令取代旧命令，旧命令变 superseded", () => {
  const ctx = setup();
  try {
    const old = ctx.receipts.accept({ kind: "turn.stop" });
    const replacement = ctx.receipts.accept({ kind: "turn.stop", supersedes: old.receipt.commandId });
    const after = ctx.receipts.get(old.receipt.commandId);
    assert.equal(after.state, "superseded");
    assert.equal(after.supersededBy, replacement.receipt.commandId);
    assert.equal(replacement.receipt.supersedes, old.receipt.commandId);
    assert.equal(ctx.receipts.countByState().superseded, 1);
  } finally {
    ctx.cleanup();
  }
});

test("崩溃恢复：dispatched → unknown，accepted 保留或过期", () => {
  const clock = mutableClock("2026-01-01T00:00:00.000Z");
  const ctx = setup({ clock });
  try {
    const dispatched = ctx.receipts.accept({ kind: "turn.send", ttlMs: 60_000 });
    ctx.receipts.dispatch(dispatched.receipt.commandId);
    const pending = ctx.receipts.accept({ kind: "turn.stop", ttlMs: 60_000 });
    const expiring = ctx.receipts.accept({ kind: "turn.stop", ttlMs: 600 });
    clock.advance(2_000);

    const report = ctx.receipts.recoverAfterCrash();
    assert.deepEqual(report.unknown, [dispatched.receipt.commandId]);
    assert.deepEqual(report.expired, [expiring.receipt.commandId]);
    assert.ok(report.pending.includes(pending.receipt.commandId));
    assert.equal(ctx.receipts.get(dispatched.receipt.commandId).state, "unknown");
    assert.equal(ctx.receipts.get(pending.receipt.commandId).state, "accepted");
  } finally {
    ctx.cleanup();
  }
});
