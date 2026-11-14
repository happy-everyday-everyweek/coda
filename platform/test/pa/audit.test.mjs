import test from "node:test";
import assert from "node:assert/strict";
import {
  AUTONOMY_MODES,
  AUTONOMY_NOTICE,
  AUTONOMY_NOTICE_VERSION,
  PA_EXTRA_AUTONOMY_MODES,
  PA_PAUSE_SWITCH,
  createAuditLog,
  describeAutonomyControls,
  summarizeForAudit,
} from "../../pa/audit.mjs";
import { createPaProfile } from "../../pa/schema.mjs";
import { createPaStore } from "../../pa/store.mjs";
import { makeTempDir } from "./helpers.mjs";

function setup(t) {
  const store = createPaStore({ dataDir: makeTempDir(t) });
  t.after(() => store.close());
  return { store, audit: createAuditLog(store) };
}

test("PA-005: 首启文案覆盖自主派发、审批、跨端操作与审计", () => {
  assert.match(AUTONOMY_NOTICE, /自主/);
  assert.match(AUTONOMY_NOTICE, /派发/);
  assert.match(AUTONOMY_NOTICE, /审批/);
  assert.match(AUTONOMY_NOTICE, /跨端/);
  assert.match(AUTONOMY_NOTICE, /审计/);
  assert.match(AUTONOMY_NOTICE, /试验性/);
  assert.match(AUTONOMY_NOTICE_VERSION, /^pa-autonomy-\d+$/);
  assert.deepEqual([...AUTONOMY_MODES], ["high"]);
});

test("PA-007: 只有高度自主一个档位，没有额外切换或暂停开关", () => {
  const controls = describeAutonomyControls();
  assert.equal(controls.highAutonomyOnly, true);
  assert.deepEqual(controls.modes.map((mode) => mode.id), ["high"]);
  assert.equal(controls.extraModeSwitch, false);
  assert.equal(controls.pauseSwitch, false);
  assert.equal(PA_EXTRA_AUTONOMY_MODES.length, 0);
  assert.equal(PA_PAUSE_SWITCH, false);
  assert.deepEqual(
    controls.genericSafetyControls.map((control) => control.id),
    ["turn.stop", "core.stop", "device.revoke"],
  );
});

test("PA-005: 一次性确认幂等，只有用户能确认", (t) => {
  const { store, audit } = setup(t);
  const profile = createPaProfile();
  store.saveProfile(profile);
  const initial = audit.autonomyState(profile.id);
  assert.equal(initial.acknowledged, false);
  assert.equal(initial.required, true);
  assert.deepEqual(initial.modes, ["high"]);
  assert.equal(initial.pauseSwitch, false);

  const first = audit.acknowledge({ profileId: profile.id, sourceNode: "dev_a" });
  assert.equal(first.acknowledged, true);
  assert.equal(first.firstTime, true);
  const second = audit.acknowledge({ profileId: profile.id, sourceNode: "dev_a" });
  assert.equal(second.acknowledged, true);
  assert.equal(second.firstTime, false);
  assert.equal(second.acknowledgedAt, first.acknowledgedAt);
  assert.equal(audit.autonomyState(profile.id).acknowledged, true);

  const entries = audit.list({ action: "pa.autonomy.acknowledge" });
  assert.equal(entries.length, 1);
  assert.equal(entries[0].actor, "user");
  assert.equal(entries[0].decision, "acknowledge");
  assert.throws(
    () => audit.acknowledge({ profileId: profile.id, sourceNode: "dev_a", by: "pa" }),
    /only the user/,
  );
});

test("PA-006: 审计记录来源、目标、时间、参数、决定与结果", (t) => {
  const { audit } = setup(t);
  audit.append({
    actor: "pa",
    action: "turn.stop",
    decision: "deny",
    sourceNode: "dev_a",
    targetNode: "dev_b",
    targetSessionId: "ses_x",
    params: { sessionId: "ses_x", reason: "用户要求" },
    result: { state: "succeeded", ok: true },
  });
  const entries = audit.list({ action: "turn.stop" });
  assert.equal(entries.length, 1);
  const entry = entries[0];
  assert.equal(entry.sourceNode, "dev_a");
  assert.equal(entry.targetNode, "dev_b");
  assert.equal(entry.targetSessionId, "ses_x");
  assert.equal(entry.actor, "pa");
  assert.equal(entry.decision, "deny");
  assert.equal(entry.result.state, "succeeded");
  assert.ok(typeof entry.at === "string" && !Number.isNaN(Date.parse(entry.at)));
  assert.match(entry.paramsSummary, /ses_x/);
  assert.equal(audit.list({ action: "turn.stop", actor: "user" }).length, 0);
});

test("SEC-005: 审计能区分 PA 与用户的决定", (t) => {
  const { audit } = setup(t);
  audit.append({ actor: "pa", action: "interaction.answer", decision: "approve", sourceNode: "dev_a" });
  audit.append({ actor: "user", action: "interaction.answer", decision: "deny", sourceNode: "dev_b" });
  const paEntry = audit.list({ action: "interaction.answer", actor: "pa" })[0];
  const userEntry = audit.list({ action: "interaction.answer", actor: "user" })[0];
  assert.equal(paEntry.decision, "approve");
  assert.equal(userEntry.decision, "deny");
  assert.notEqual(paEntry.sourceNode, userEntry.sourceNode);
});

test("SEC-001 / SEC-005: 审计参数摘要脱敏，不写入秘密", (t) => {
  const { audit } = setup(t);
  audit.append({
    actor: "pa",
    action: "session.create",
    decision: "dispatch",
    sourceNode: "dev_a",
    params: {
      agentInstanceId: "ai_demo",
      apiKey: "sk-live-secret",
      authorization: "Bearer top-secret-token",
      nested: { password: "p@ss", keep: "visible" },
      token: "pairing-token",
    },
    result: { state: "succeeded", ok: true },
  });
  const entry = audit.list({ action: "session.create" })[0];
  assert.doesNotMatch(entry.paramsSummary, /sk-live-secret/);
  assert.doesNotMatch(entry.paramsSummary, /top-secret-token/);
  assert.doesNotMatch(entry.paramsSummary, /pairing-token/);
  assert.match(entry.paramsSummary, /\[redacted\]/);
  assert.match(entry.paramsSummary, /visible/);
  const summary = summarizeForAudit({ apiKey: "x", keep: "y" });
  assert.equal(summary.includes("x"), false);
  assert.match(summary, /keep/);
});

test("PA-006: 审计分页与过滤可查询", (t) => {
  const { audit } = setup(t);
  for (let index = 0; index < 5; index += 1) {
    audit.append({
      actor: "pa",
      action: "sessions.list",
      decision: "query",
      sourceNode: "dev_a",
      params: { index },
      result: { state: "succeeded", ok: true },
    });
  }
  assert.equal(audit.list({ limit: 2 }).length, 2);
  assert.equal(audit.list({ limit: 2, offset: 2 }).length, 2);
  assert.equal(audit.list({ action: "sessions.list" }).length, 5);
  assert.equal(audit.list({ action: "none" }).length, 0);
});

test("PA-006: 非法审计条目不落库", (t) => {
  const { audit } = setup(t);
  assert.throws(() => audit.append({ decision: "dispatch", sourceNode: "dev_a" }), /action/);
  assert.throws(
    () => audit.append({ action: "x", decision: "not-a-decision", sourceNode: "dev_a" }),
    /decision/,
  );
  assert.throws(
    () => audit.append({ action: "x", decision: "dispatch", actor: "agent", sourceNode: "dev_a" }),
    /actor/,
  );
  assert.throws(
    () => audit.append({ action: "x", decision: "dispatch", sourceNode: "ses_a" }),
    /sourceNode/,
  );
  assert.equal(audit.list().length, 0);
});
