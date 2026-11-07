/** CORE-113：完成/失败/审批/连接状态 → 通知记录；系统通知只做日志适配。 */
import test from "node:test";
import assert from "node:assert/strict";

import { NotificationService } from "../../core/notifications.mjs";
import { fixedClock, makeTempDir, cleanupDir } from "../../fixtures/core/helpers.mjs";
import { Journal } from "../../core/journal.mjs";

function createLoggerSink() {
  const lines = [];
  const push = (level) => (message) => lines.push({ level, message: String(message) });
  return { lines, logger: { debug: push("debug"), info: push("info"), warn: push("warn"), error: push("error") } };
}

test("完成/失败/未知/审批事件映射为不同通知", () => {
  const { lines, logger } = createLoggerSink();
  const service = new NotificationService({ clock: fixedClock(), logger, dedupeWindowMs: 0 });

  const completed = service.handleEvent({
    seq: 1,
    kind: "turn",
    sessionId: "ses_notify",
    payload: { state: "completed" },
  });
  assert.equal(completed.type, "completed");
  assert.equal(completed.level, "info");

  const failed = service.handleEvent({
    seq: 2,
    kind: "turn",
    sessionId: "ses_notify",
    payload: { state: "failed", error: { message: "boom" } },
  });
  assert.equal(failed.type, "failed");
  assert.equal(failed.level, "error");

  const unknown = service.handleEvent({
    seq: 3,
    kind: "turn",
    sessionId: "ses_notify",
    payload: { state: "unknown" },
  });
  assert.equal(unknown.type, "info");
  assert.equal(unknown.level, "warn");

  const approval = service.handleEvent({
    seq: 4,
    kind: "interaction",
    sessionId: "ses_notify",
    payload: { requestId: "int_1", state: "pending", kind: "approval", prompt: "允许？" },
  });
  assert.equal(approval.type, "approval");
  assert.equal(approval.level, "warn");

  assert.equal(service.unreadCount(), 4);
  assert.ok(lines.some((l) => l.level === "error"));
  assert.ok(lines.some((l) => l.message.includes("[notify:approval]")));
});

test("连接状态只在真正变化时通知", () => {
  const service = new NotificationService({ clock: fixedClock(), dedupeWindowMs: 0 });
  const online = service.setConnection({ peerId: "dev_peer", online: true });
  assert.equal(online.type, "connection");
  assert.equal(online.payload.online, true);

  assert.equal(service.setConnection({ peerId: "dev_peer", online: true }), null);

  const offline = service.setConnection({ peerId: "dev_peer", online: false, reason: "network lost" });
  assert.equal(offline.type, "connection");
  assert.equal(offline.level, "warn");
  assert.equal(offline.payload.previous, true);
  assert.equal(service.diagnostics().connections.dev_peer, false);
});

test("通知列表有界，可标记已读并持久化", () => {
  const dir = makeTempDir("coda-notify-");
  const journal = new Journal({ dataDir: dir, clock: fixedClock() });
  journal.open();
  try {
    const service = new NotificationService({ clock: fixedClock(), journal, maxRecords: 3, dedupeWindowMs: 0 });
    for (let i = 0; i < 6; i += 1) {
      service.record({ type: "info", title: `n${i}`, key: `k${i}` });
    }
    assert.equal(service.size, 3);
    const list = service.list({ limit: 10 });
    assert.deepEqual(list.map((n) => n.title), ["n5", "n4", "n3"]);

    const read = service.markRead(list[0].id);
    assert.equal(read.read, true);
    assert.equal(service.markRead("evt_missing"), null);
    assert.equal(journal.listNotifications({ limit: 10 }).length, 3);
    assert.equal(journal.unreadNotificationCount(), 2);
  } finally {
    journal.close();
    cleanupDir(dir);
  }
});

test("去重窗口内同一 key 只记一次", () => {
  const service = new NotificationService({ clock: fixedClock(), dedupeWindowMs: 5_000 });
  const first = service.record({ type: "completed", title: "done", key: "turn:1" });
  const second = service.record({ type: "completed", title: "done again", key: "turn:1" });
  assert.equal(second.deduped, true);
  assert.equal(second.id, first.id);
  assert.equal(service.size, 1);
});

test("未知通知类型被拒绝，不静默吞掉", () => {
  const service = new NotificationService({ clock: fixedClock() });
  assert.throws(() => service.record({ type: "nope" }), /unknown notification type/);
});
