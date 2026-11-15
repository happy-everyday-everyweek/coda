import test from "node:test";
import assert from "node:assert/strict";
import { createPaProfile } from "../../pa/schema.mjs";
import {
  canonicalJson,
  findSeqGaps,
  mergePaMessages,
  sortPaMessages,
} from "../../pa/merge.mjs";
import { FIXED_MS, makeMessage } from "./helpers.mjs";

function ids(messages) {
  return messages.map((message) => message.id);
}

test("PA-102: 消息 ID 去重，重复输入幂等", () => {
  const profile = createPaProfile();
  const message = makeMessage({ paId: profile.id, id: "pm_one", seq: 1 });
  const result = mergePaMessages({ local: [message], incoming: [message, message] });
  assert.equal(result.ok, true);
  assert.equal(result.view.length, 1);
  assert.equal(result.added, 0);
  assert.equal(result.duplicates, 2);

  const replay = mergePaMessages({ local: result.messages, incoming: [message, message] });
  assert.deepEqual(ids(replay.view), ids(result.view));
  assert.equal(replay.added, 0);
  assert.equal(replay.duplicates, 2);
});

test("PA-102: 乱序输入按 HLC 确定性排序", () => {
  const profile = createPaProfile();
  const later = makeMessage({ paId: profile.id, id: "pm_later", seq: 2, physical: FIXED_MS + 1000, content: "later" });
  const earlier = makeMessage({ paId: profile.id, id: "pm_earlier", seq: 1, physical: FIXED_MS, content: "earlier" });
  const result = mergePaMessages({ local: [], incoming: [later, earlier] });
  assert.deepEqual(ids(result.view), ["pm_earlier", "pm_later"]);
  assert.equal(result.added, 2);
  assert.equal(result.nextCursor, later.hlc);
});

test("PA-102: 两端并发消息全部保留，任意合并顺序收敛一致", () => {
  const profile = createPaProfile();
  const a1 = makeMessage({ paId: profile.id, node: "dev_a", id: "pm_a1", seq: 1, physical: 1000 });
  const a2 = makeMessage({ paId: profile.id, node: "dev_a", id: "pm_a2", seq: 2, physical: 3000 });
  const b1 = makeMessage({
    paId: profile.id,
    node: "dev_b",
    id: "pm_b1",
    seq: 1,
    physical: 2000,
    role: "assistant",
    replyTo: "pm_a1",
    content: "reply",
  });
  const b2 = makeMessage({ paId: profile.id, node: "dev_b", id: "pm_b2", seq: 2, physical: 4000, role: "assistant", content: "later" });

  const leftFirst = mergePaMessages({ local: [a1, a2], incoming: [b1, b2] });
  const rightFirst = mergePaMessages({ local: [b1, b2], incoming: [a1, a2] });
  assert.deepEqual(ids(leftFirst.view), ids(rightFirst.view));
  assert.deepEqual(ids(leftFirst.view), ["pm_a1", "pm_b1", "pm_a2", "pm_b2"]);
  assert.equal(leftFirst.view.length, 4);
  const reply = leftFirst.view.find((message) => message.id === "pm_b1");
  assert.equal(reply.replyToResolved, true);
  assert.equal(leftFirst.added, 2);
  assert.equal(rightFirst.added, 2);
});

test("PA-102: 同节点同 seq 冲突不丢消息，只报告并确定性排序", () => {
  const profile = createPaProfile();
  const first = makeMessage({ paId: profile.id, node: "dev_a", id: "pm_a", seq: 1, physical: FIXED_MS, counter: 0 });
  const second = makeMessage({ paId: profile.id, node: "dev_a", id: "pm_b", seq: 1, physical: FIXED_MS, counter: 1 });
  const result = mergePaMessages({ local: [], incoming: [second, first] });
  assert.deepEqual(ids(result.view), ["pm_a", "pm_b"]);
  assert.equal(result.seqConflicts.length, 1);
  assert.equal(result.seqConflicts[0].sourceNode, "dev_a");
  assert.equal(result.seqConflicts[0].seq, 1);
  assert.deepEqual(result.seqConflicts[0].ids, ["pm_a", "pm_b"]);
  assert.deepEqual(result.seqConflicts[0].kept, ["pm_a", "pm_b"]);
});

test("PA-102: 同一 ID 内容分叉时按规范化 JSON 取确定胜者", () => {
  const profile = createPaProfile();
  const first = makeMessage({ paId: profile.id, id: "pm_same", seq: 1, content: "alpha" });
  const second = { ...first, content: "beta" };
  const forward = mergePaMessages({ local: [first], incoming: [second] });
  const reverse = mergePaMessages({ local: [second], incoming: [first] });
  assert.equal(forward.view.length, 1);
  assert.equal(reverse.view.length, 1);
  assert.equal(forward.view[0].content, "alpha");
  assert.equal(reverse.view[0].content, "alpha");
  assert.equal(forward.idConflicts.length, 1);
  assert.equal(forward.idConflicts[0].id, "pm_same");
  assert.equal(canonicalJson({ b: 1, a: 2 }), canonicalJson({ a: 2, b: 1 }));
});

test("PA-102: reply_to 缺失保留消息并标记 orphan", () => {
  const profile = createPaProfile();
  const reply = makeMessage({
    paId: profile.id,
    id: "pm_reply",
    seq: 1,
    physical: FIXED_MS + 1,
    role: "assistant",
    content: "reply",
    replyTo: "pm_missing",
  });
  const result = mergePaMessages({ local: [], incoming: [reply] });
  assert.equal(result.view.length, 1);
  assert.deepEqual(result.orphans, ["pm_reply"]);
  assert.equal(result.view[0].replyToMissing, true);
  assert.equal(result.view[0].replyToResolved, false);
});

test("PA-102: seq 缺口只报告，不影响合并", () => {
  const profile = createPaProfile();
  const one = makeMessage({ paId: profile.id, id: "pm_1", seq: 1, physical: 1000 });
  const three = makeMessage({ paId: profile.id, id: "pm_3", seq: 3, physical: 3000 });
  const result = mergePaMessages({ local: [], incoming: [one, three] });
  const gaps = findSeqGaps(result.messages);
  assert.equal(gaps.length, 1);
  assert.equal(gaps[0].sourceNode, "dev_a");
  assert.equal(gaps[0].count, 1);
  assert.deepEqual(gaps[0].missing, [2]);
  assert.equal(result.view.length, 2);
});

test("PA-102: 无效消息被拒收，不影响有效消息合并", () => {
  const profile = createPaProfile();
  const good = makeMessage({ paId: profile.id, id: "pm_good", seq: 1 });
  const bad = { ...makeMessage({ paId: profile.id, id: "pm_bad", seq: 2 }), seq: 0 };
  const result = mergePaMessages({ local: [], incoming: [bad, good] });
  assert.equal(result.ok, false);
  assert.equal(result.rejected.length, 1);
  assert.equal(result.rejected[0].id, "pm_bad");
  assert.deepEqual(ids(result.view), ["pm_good"]);
});

test("PA-102: 排序不修改输入数组", () => {
  const profile = createPaProfile();
  const later = makeMessage({ paId: profile.id, id: "pm_z", seq: 2, physical: 2000 });
  const earlier = makeMessage({ paId: profile.id, id: "pm_y", seq: 1, physical: 1000 });
  const input = [later, earlier];
  const sorted = sortPaMessages(input);
  assert.deepEqual(ids(sorted), ["pm_y", "pm_z"]);
  assert.deepEqual(ids(input), ["pm_z", "pm_y"]);
});
