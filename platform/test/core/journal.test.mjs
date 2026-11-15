/** CORE-104：事件日志、事务边界、投影、断点回放。 */
import test from "node:test";
import assert from "node:assert/strict";

import { Journal } from "../../core/journal.mjs";
import { fixedClock, makeTempDir, cleanupDir, FIXED_ISO } from "../../fixtures/core/helpers.mjs";

function openJournal(dir) {
  const journal = new Journal({ dataDir: dir, clock: fixedClock() });
  journal.open();
  return journal;
}

function sessionFixture(id = "ses_journal_1", state = "running") {
  return {
    id,
    nodeId: "dev_test",
    environmentId: "env_test",
    agentInstanceId: "ai_test",
    workspaceId: "ws_test",
    state,
    title: "journal test",
    options: {},
    archived: false,
    unread: 0,
    createdAt: FIXED_ISO,
    updatedAt: FIXED_ISO,
    lastActivityAt: FIXED_ISO,
  };
}

test("迁移建表，seq 全局单调递增且按会话维护 lastSeq", () => {
  const dir = makeTempDir("coda-journal-");
  const journal = openJournal(dir);
  try {
    assert.equal(journal.schemaVersion(), 1);
    assert.equal(journal.latestSeq(), 0);

    const a = journal.appendEvent({ kind: "message", sessionId: "ses_a", payload: { text: "a" } });
    const b = journal.appendEvent({ kind: "message", sessionId: "ses_b", payload: { text: "b" } });
    const c = journal.appendEvent({ kind: "message", sessionId: "ses_a", payload: { text: "c" } });

    assert.deepEqual([a.seq, b.seq, c.seq], [1, 2, 3]);
    assert.equal(journal.latestSeq(), 3);
    assert.equal(journal.getSession("ses_a").lastSeq, 3);
    assert.equal(journal.getSession("ses_b").lastSeq, 2);
    assert.equal(journal.eventCount(), 3);
  } finally {
    journal.close();
    cleanupDir(dir);
  }
});

test("事务边界：投影抛错时事件与 seq 一起回滚", () => {
  const dir = makeTempDir("coda-journal-");
  const journal = openJournal(dir);
  try {
    journal.appendEvent({ kind: "message", sessionId: "ses_a", payload: { text: "ok" } });
    const before = journal.eventCount();
    const seqBefore = journal.latestSeq();

    assert.throws(
      () =>
        journal.appendEvent({
          kind: "message",
          sessionId: "ses_a",
          payload: { text: "boom" },
          project: () => {
            throw new Error("projection boom");
          },
        }),
      /projection boom/,
    );

    assert.equal(journal.eventCount(), before);
    assert.equal(journal.latestSeq(), seqBefore);
    // 回滚后 seq 不能跳号：下一事件仍是 before+1。
    const next = journal.appendEvent({ kind: "message", sessionId: "ses_a", payload: { text: "next" } });
    assert.equal(next.seq, seqBefore + 1);
  } finally {
    journal.close();
    cleanupDir(dir);
  }
});

test("会话投影可从事件读取，且 verifyReplay 一致", () => {
  const dir = makeTempDir("coda-journal-");
  const journal = openJournal(dir);
  try {
    const session = sessionFixture();
    journal.transaction(() => {
      journal.upsertSession(session);
      journal.appendEvent({ kind: "session", sessionId: session.id, payload: { session, state: "running" } });
      journal.upsertTurn({
        id: "turn_1",
        sessionId: session.id,
        runId: "run_1",
        state: "running",
        index: 1,
        createdAt: FIXED_ISO,
        updatedAt: FIXED_ISO,
      });
      journal.appendEvent({
        kind: "turn",
        sessionId: session.id,
        payload: { turn: journal.getTurn("turn_1"), state: "running" },
      });
    });

    const projection = journal.sessionProjection(session.id);
    assert.equal(projection.session.id, session.id);
    assert.equal(projection.turns.length, 1);
    assert.equal(projection.turns[0].id, "turn_1");
    assert.equal(projection.lastSeq, journal.latestSeq());

    const replay = journal.verifyReplay();
    assert.equal(replay.ok, true, JSON.stringify(replay));
    assert.equal(replay.replayed, journal.eventCount());
  } finally {
    journal.close();
    cleanupDir(dir);
  }
});

test("断点回放：连续 seq 走 replay，越界走 snapshot", () => {
  const dir = makeTempDir("coda-journal-");
  const journal = openJournal(dir);
  try {
    for (let i = 0; i < 3; i += 1) {
      journal.appendEvent({ kind: "message", sessionId: "ses_a", payload: { i } });
    }
    assert.equal(journal.checkResume(0).mode, "replay");
    assert.equal(journal.checkResume(2).mode, "replay");
    assert.equal(journal.checkResume(3).mode, "replay");
    const ahead = journal.checkResume(4);
    assert.equal(ahead.mode, "snapshot");
    assert.equal(ahead.reason, "client-ahead");

    const events = journal.readSince(1, { limit: 10 });
    assert.deepEqual(events.map((e) => e.seq), [2, 3]);
    const filtered = journal.readSince(0, { limit: 10, sessionId: "ses_a" });
    assert.equal(filtered.length, 3);
  } finally {
    journal.close();
    cleanupDir(dir);
  }
});

test("重启后 seq 与投影保持连续（同一 dataDir）", () => {
  const dir = makeTempDir("coda-journal-");
  try {
    const first = openJournal(dir);
    first.appendEvent({ kind: "message", sessionId: "ses_p", payload: { n: 1 } });
    first.appendEvent({ kind: "message", sessionId: "ses_p", payload: { n: 2 } });
    first.close();

    const second = openJournal(dir);
    try {
      assert.equal(second.latestSeq(), 2);
      assert.equal(second.getSession("ses_p").lastSeq, 2);
      const third = second.appendEvent({ kind: "message", sessionId: "ses_p", payload: { n: 3 } });
      assert.equal(third.seq, 3);
      assert.equal(second.schemaVersion(), 1);
    } finally {
      second.close();
    }
  } finally {
    cleanupDir(dir);
  }
});

test("journal.snapshot 返回全局 seq 与会话列表", () => {
  const dir = makeTempDir("coda-journal-");
  const journal = openJournal(dir);
  try {
    const session = sessionFixture("ses_snap", "idle");
    journal.appendEvent({ kind: "session", sessionId: session.id, payload: { session, state: "idle" } });
    const snapshot = journal.snapshot();
    assert.equal(snapshot.seq, 1);
    assert.equal(snapshot.sessions.length, 1);
    assert.equal(snapshot.sessions[0].id, "ses_snap");
    const scoped = journal.snapshot({ sessionId: "ses_snap" });
    assert.equal(scoped.sessionId, "ses_snap");
    assert.equal(scoped.session.session?.id ?? scoped.session.id, "ses_snap");
  } finally {
    journal.close();
    cleanupDir(dir);
  }
});
