/** REL-103（P1）有界长稳/泄漏冒烟：大量事件、重放与投影预算。 */
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { Journal } from "../../core/journal.mjs";
import { fixedClock } from "../../fixtures/core/helpers.mjs";

test("长稳冒烟：5000 事件写入/重放有界，投影不无限增长", { timeout: 60000 }, () => {
  const dir = mkdtempSync(join(tmpdir(), "coda-soak-"));
  const journal = new Journal({ dataDir: dir, clock: fixedClock() });
  journal.open();
  try {
    journal.upsertSession({ id: "ses_soak", title: "soak", state: "running" });
    const startedAt = Date.now();
    for (let i = 0; i < 5000; i += 1) {
      journal.transaction(() =>
        journal.appendEvent({
          kind: "message",
          sessionId: "ses_soak",
          payload: { role: "assistant", text: `chunk-${i}`, delta: true },
        }),
      );
    }
    const elapsed = Date.now() - startedAt;
    // 预算：本地 SQLite 5k 事务写入应在数秒内（给 CI 慢机留余量）
    assert.ok(elapsed < 30000, `5000 次写入耗时 ${elapsed}ms 超预算`);
    assert.equal(journal.eventCount(), 5000);
    assert.equal(journal.latestSeq(), 5000);

    // 重放：从头读应完整且 seq 连续
    const events = journal.readSince(0, { limit: 6000, sessionId: "ses_soak" });
    assert.equal(events.length, 5000);
    for (let i = 1; i < events.length; i += 1) {
      assert.equal(events[i].seq, events[i - 1].seq + 1);
    }

    // 投影有界：会话投影默认只保留有限事件窗口
    const projection = journal.sessionProjection("ses_soak", { eventLimit: 200 });
    assert.ok(projection.events.length <= 200, `投影事件数 ${projection.events.length} 超上限`);
    const recent = journal.recentMessages({ limit: 100 });
    assert.ok(recent.length <= 100);
  } finally {
    journal.close();
    rmSync(dir, { recursive: true, force: true });
  }
});
