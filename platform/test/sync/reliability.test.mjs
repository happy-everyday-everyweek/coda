/** REL-101 / REL-102：重启恢复、结果未知、终端恢复、故障注入 harness。 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";

import { createDeviceIdentity } from "../../sync/identity.mjs";
import {
  classifyProcessExit,
  createFaultHarness,
  createRecoveryJournal,
  createSeededRandom,
  recoverSessions,
  recoverTerminals,
  recoverTurns,
  settlePendingInteractions,
} from "../../sync/reliability.mjs";
import { createFileStorage, createMemoryStorage } from "../../sync/storage.mjs";
import { createSyncService } from "../../sync/sync-service.mjs";
import { createMemoryTransportPair } from "../../sync/transport.mjs";
import { writeSecretFile } from "../../security/secrets.mjs";
import { makeTmpDir } from "./helpers.mjs";

test("退出分类：正常完成、崩溃、被信号杀死、超时、启动失败可区分", () => {
  const normal = classifyProcessExit({ code: 0 });
  assert.equal(normal.kind, "normal");
  assert.equal(normal.clean, true);
  const crash = classifyProcessExit({ code: 2 });
  assert.equal(crash.kind, "crash");
  assert.equal(crash.clean, false);
  const killed = classifyProcessExit({ signal: "SIGKILL" });
  assert.equal(killed.kind, "killed");
  assert.match(killed.note, /interrupted\/unknown/);
  assert.equal(classifyProcessExit({ timedOut: true }).kind, "timeout");
  assert.equal(classifyProcessExit({ spawnError: new Error("no exec") }).kind, "spawn_failed");
  assert.equal(classifyProcessExit({}).kind, "unknown");
});

test("REL-003：崩溃后不盲重试，未提交意图标为 unknown；只读核对可收敛", () => {
  const storage = createMemoryStorage();
  const journal = createRecoveryJournal({ storage, clock: () => 1_000 });
  const intent = journal.beginIntent({ kind: "agent.send", payload: { text: "hello" } });
  assert.equal(intent.state, "intent");
  assert.equal(journal.pending().length, 1);

  // 模拟核心重启：新的 journal 实例读同一存储。
  const restarted = createRecoveryJournal({ storage });
  const recovered = restarted.recover();
  assert.equal(recovered.blindRetries, 0);
  assert.equal(recovered.unknown.length, 1);
  assert.equal(recovered.unknown[0].state, "unknown");
  assert.match(recovered.unknown[0].reason, /no_confirmed_result/);

  const second = journal.beginIntent({ id: "intent-2", kind: "agent.send" });
  const reconciled = journal.recover({ reconcile: () => "succeeded" });
  assert.ok(reconciled.reconciled.some((entry) => entry.id === second.id));
  assert.equal(journal.list().find((entry) => entry.id === "intent-2").state, "succeeded");
});

test("REL-001：回合/会话/终端恢复，不把未确认回合标为成功", () => {
  const turns = recoverTurns({
    turns: [
      { id: "turn-1", state: "running" },
      { id: "turn-2", state: "completed" },
      { id: "turn-3", state: "queued" },
      { id: "turn-4", state: "stopping" },
    ],
    now: () => 1_000,
  });
  assert.equal(turns.turns.find((turn) => turn.id === "turn-1").state, "unknown");
  assert.equal(turns.turns.find((turn) => turn.id === "turn-1").interrupted, true);
  assert.equal(turns.turns.find((turn) => turn.id === "turn-2").state, "completed");
  assert.equal(turns.turns.find((turn) => turn.id === "turn-3").state, "queued");
  assert.equal(turns.turns.find((turn) => turn.id === "turn-4").state, "unknown");
  assert.equal(turns.interrupted, 2);

  const sessions = recoverSessions({ sessions: [{ id: "ses-1", state: "running" }, { id: "ses-2", state: "idle" }] });
  assert.equal(sessions.sessions[0].state, "unknown");
  assert.equal(sessions.sessions[1].state, "idle");

  const terminals = recoverTerminals({
    terminals: [
      { id: "term-1", status: "open" },
      { id: "term-2", status: "closed" },
    ],
    now: () => 1_000,
  });
  assert.equal(terminals.terminals[0].status, "closed");
  assert.equal(terminals.terminals[0].closeReason, "core_restart");
  assert.equal(terminals.terminals[0].interactive, false);
  assert.equal(terminals.terminals[1].status, "closed");
  assert.equal(terminals.closed.length, 1);
});

test("REL-002：失败回合结算审批/提问，不留下永远等待", () => {
  const settled = settlePendingInteractions(
    [
      { id: "int-1", status: "pending" },
      { id: "int-2", status: "answered" },
      { id: "int-3", status: "cancelled" },
    ],
    { reason: "agent_crashed" },
  );
  assert.equal(settled[0].status, "cancelled");
  assert.equal(settled[0].cancelledReason, "agent_crashed");
  assert.equal(settled[1].status, "answered");
  assert.equal(settled[2].status, "cancelled");
});

test("REL-102：harness 可复现、可杀子进程、可注入超时与断连", async (t) => {
  const first = createFaultHarness({ seed: 42 });
  const second = createFaultHarness({ seed: 42 });
  assert.deepEqual(
    Array.from({ length: 5 }, () => first.random()),
    Array.from({ length: 5 }, () => second.random()),
  );

  assert.throws(() => first.crash("before-side-effect"), /injected crash/);

  const timeout = first.timeout("agent-response", 5);
  await assert.rejects(timeout, (err) => err.kind === "timeout");

  const pair = createMemoryTransportPair();
  const closed = [];
  pair.right.onClose((info) => closed.push(info));
  first.disconnect(pair.left, "network_partition");
  assert.equal(closed.length, 1);
  assert.match(closed[0].reason, /network_partition|peer_close/);

  const killed = await first.spawnAndKill({ script: "setInterval(() => {}, 1000)" });
  assert.equal(killed.classification.kind, "killed");
  assert.equal(killed.classification.clean, false);
  assert.ok(killed.exit.signal === "SIGKILL" || killed.exit.code === null);

  const failing = await first.runWithRetries(
    async (attempt) => {
      if (attempt < 2) throw new Error("transient");
      return "done";
    },
    { attempts: 3 },
  );
  assert.equal(failing.ok, true);
  assert.equal(failing.value, "done");
  assert.equal(failing.attempts, 2);

  // seeded random 本身也稳定。
  assert.equal(typeof createSeededRandom(7)(), "number");
});

test("REL-004：schema 不匹配拒绝启动写入，旧库保持原样", (t) => {
  const dir = makeTmpDir(t);
  const path = join(dir, "sync-store.json");
  const original = JSON.stringify({ schemaVersion: 999, sessions: {}, events: {} });
  writeSecretFile(path, original);
  assert.throws(
    () => createFileStorage({ path }),
    (err) => err.code === "STORAGE_SCHEMA_MISMATCH" && /Refusing to write/.test(err.message),
  );
  assert.equal(readFileSync(path, "utf8"), original);
});

test("REL-001 持久化：同一文件存储重开后事件、游标与 opSeq 延续", (t) => {
  const dir = makeTmpDir(t);
  const identity = createDeviceIdentity({ dir: join(dir, "id") });
  const path = join(dir, "sync-store.json");

  let storage = createFileStorage({ path });
  let service = createSyncService({ storage, identity });
  const session = service.appendSession({ title: "persist" });
  service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "before restart" } });
  service.close();
  storage.close();

  storage = createFileStorage({ path });
  service = createSyncService({ storage, identity });
  const replayed = service.dump();
  assert.equal(replayed.sessions.length, 1);
  assert.equal(replayed.events[session.id].length, 1);
  assert.equal(replayed.events[session.id][0].payload.text, "before restart");
  const second = service.appendEvent({ sessionId: session.id, kind: "message", payload: { text: "after restart" } });
  assert.equal(second.seq, 2);
  service.close();
  storage.close();
});
