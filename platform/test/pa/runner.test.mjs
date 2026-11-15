import test from "node:test";
import assert from "node:assert/strict";
import { createPaProfile, compareHlc } from "../../pa/schema.mjs";
import { createPaRunner } from "../../pa/runner.mjs";
import { FIXED_MS, fakeClock, makeMessage, makeTempDir, scriptedLlm } from "./helpers.mjs";

test("PA-103: PA 使用独立 SQLite 存储，重启后资料与单会话仍在", async (t) => {
  const dir = makeTempDir(t);
  const llm = scriptedLlm([{ text: "你好呀" }]);
  let runner = createPaRunner({ dataDir: dir, nodeId: "dev_a", llm, clock: fakeClock() });
  const sent = await runner.sendUserMessage("你好");
  assert.equal(sent.status, "replied");
  const profileId = runner.profile.id;
  const history = runner.history();
  assert.equal(history.length, 2);
  assert.equal(history[0].role, "user");
  assert.equal(history[1].role, "assistant");
  assert.equal(history[1].replyTo, sent.userMessage.id);
  assert.equal(history[1].replyToResolved, true);
  assert.ok(history.every((message) => message.paId === profileId));
  assert.equal(runner.diagnostics().hasCore, false);
  assert.equal(runner.diagnostics().hasLlm, true);
  runner.close();
  assert.equal(runner.isClosed(), true);

  runner = createPaRunner({ dataDir: dir, nodeId: "dev_a", clock: fakeClock(FIXED_MS + 1000) });
  assert.equal(runner.profile.id, profileId);
  assert.equal(runner.history().length, 2);
  assert.equal(runner.diagnostics().messages, 2);
  runner.close();
});

test("PA-103: 无 LLM 时仍保存用户消息，不伪造 PA 回复", async (t) => {
  const runner = createPaRunner({ dataDir: makeTempDir(t), nodeId: "dev_a", clock: fakeClock() });
  const result = await runner.sendUserMessage("先记下来");
  assert.equal(result.ok, true);
  assert.equal(result.status, "no_llm");
  assert.equal(result.reply, null);
  const history = runner.history();
  assert.equal(history.length, 1);
  assert.equal(history[0].role, "user");
  runner.close();
});

test("PA-103: LLM 失败可追踪，不生成假回复", async (t) => {
  const runner = createPaRunner({
    dataDir: makeTempDir(t),
    nodeId: "dev_a",
    clock: fakeClock(),
    llm: async () => {
      throw new Error("model down");
    },
  });
  const result = await runner.sendUserMessage("在吗");
  assert.equal(result.ok, false);
  assert.equal(result.status, "failed");
  assert.match(result.error, /model down/);
  assert.equal(result.reply, null);
  assert.equal(runner.history().length, 1);
  const entry = runner.audit.list({ action: "pa.reply" })[0];
  assert.equal(entry.actor, "pa");
  assert.equal(entry.result.state, "failed");
  assert.match(entry.error, /model down/);
  runner.close();
});

test("PA-103: 修改 PA 资料不新建会话、不改变消息归属", async (t) => {
  const dir = makeTempDir(t);
  const runner = createPaRunner({
    dataDir: dir,
    nodeId: "dev_a",
    clock: fakeClock(),
    llm: scriptedLlm([{ text: "好的" }]),
  });
  await runner.sendUserMessage("第一条");
  const beforeId = runner.profile.id;
  const beforeCount = runner.history().length;
  const updated = runner.updateProfile({ name: "小麦", persona: "新的 PA 人设" });
  assert.equal(updated.id, beforeId);
  assert.equal(updated.name, "小麦");
  assert.equal(runner.history().length, beforeCount);
  assert.ok(runner.history().every((message) => message.paId === beforeId));
  assert.throws(() => runner.updateProfile({ id: createPaProfile().id }), /cannot change/);
  runner.close();
});

test("PA-102 / PA-103: 合并远端消息幂等、观测远端 HLC、游标持久化", async (t) => {
  const dir = makeTempDir(t);
  const runner = createPaRunner({ dataDir: dir, nodeId: "dev_a", clock: fakeClock() });
  const remote1 = makeMessage({ paId: runner.profile.id, node: "dev_b", id: "pm_b1", seq: 1, physical: FIXED_MS + 1000 });
  const remote2 = makeMessage({ paId: runner.profile.id, node: "dev_b", id: "pm_b2", seq: 2, physical: FIXED_MS + 2000 });
  const first = runner.mergeIncoming([remote1, remote2, remote1]);
  assert.equal(first.ok, true);
  assert.equal(first.added, 2);
  assert.equal(first.duplicates, 1);
  const second = runner.mergeIncoming([remote1, remote2]);
  assert.equal(second.added, 0);
  assert.equal(second.duplicates, 2);
  assert.deepEqual(
    runner.history().map((message) => message.id),
    ["pm_b1", "pm_b2"],
  );
  assert.equal(runner.diagnostics().cursor, remote2.hlc);

  const local = runner.appendMessage({ role: "user", content: "sync 之后的本机消息" });
  assert.ok(compareHlc(local.hlc, remote2.hlc) > 0);
  runner.close();

  const reopened = createPaRunner({ dataDir: dir, nodeId: "dev_a", clock: fakeClock(FIXED_MS + 3000) });
  assert.deepEqual(
    reopened.history().map((message) => message.id),
    ["pm_b1", "pm_b2", local.id],
  );
  reopened.close();
});

test("PA-002: 拒绝把另一个 PA 的会话消息并入当前单会话", async (t) => {
  const runner = createPaRunner({ dataDir: makeTempDir(t), nodeId: "dev_a", clock: fakeClock() });
  const foreign = makeMessage({ paId: createPaProfile().id, node: "dev_c", id: "pm_foreign", seq: 1 });
  const result = runner.mergeIncoming([foreign]);
  assert.equal(result.added, 0);
  assert.equal(result.rejected.length, 1);
  assert.match(result.rejected[0].reason, /paId mismatch/);
  assert.equal(runner.history().length, 0);
  runner.close();
});

test("PA-101: history(limit) 取最近消息，afterHlc 只返回其后消息", async (t) => {
  const runner = createPaRunner({ dataDir: makeTempDir(t), nodeId: "dev_a", clock: fakeClock() });
  const appended = [];
  for (let index = 0; index < 5; index += 1) {
    appended.push(runner.appendMessage({ role: "user", content: `m${index}` }));
  }
  const limited = runner.history({ limit: 2 });
  assert.deepEqual(limited.map((message) => message.content), ["m3", "m4"]);
  const after = runner.history({ afterHlc: appended[2].hlc });
  assert.deepEqual(after.map((message) => message.content), ["m3", "m4"]);
  const withoutView = runner.history({ limit: 1, withView: false });
  assert.equal(withoutView[0].replyToResolved, undefined);
  runner.close();
});

test("PA-103: close 幂等，关闭后拒绝继续写入", async (t) => {
  const runner = createPaRunner({ dataDir: makeTempDir(t), nodeId: "dev_a", clock: fakeClock() });
  runner.close();
  runner.close();
  await assert.rejects(runner.sendUserMessage("x"), /closed/);
  assert.throws(() => runner.history(), /closed/);
  assert.equal(runner.isClosed(), true);
  const diagnostics = JSON.stringify(runner.diagnostics());
  assert.doesNotMatch(diagnostics, /apiKey|token|secret/i);
});
