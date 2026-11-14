/** AGT-111：Claude Code 骨架只能 probe，其余入口必须 BLOCKED。 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  CLAUDE_CODE_BLOCKED_EVIDENCE,
  CLAUDE_CODE_PROTOCOL,
  CLAUDE_CODE_TASK_ID,
  ClaudeCodeAdapter,
  createClaudeCodeAdapter,
} from "../../adapters/claude-code.mjs";
import {
  buildCapabilityMatrix,
  flattenCapabilityStates,
  instanceFromAdapter,
  supportsCapability,
} from "../../adapters/capability-matrix.mjs";

const MISSING = "coda-definitely-not-real-claude-xyz";

test("未安装时 probe 返回 installed:false，并指向 BLOCKED 记录", async () => {
  const adapter = new ClaudeCodeAdapter({ instanceId: "ai_claude_missing", command: MISSING });
  const probe = await adapter.probe();
  assert.equal(probe.installed, false);
  assert.equal(probe.sideEffectFree, true);
  assert.equal(probe.version, null);
  assert.equal(probe.details.taskId, "AGT-111");
  assert.equal(probe.details.blocked, true);
  assert.equal(probe.details.doc, "platform/adapters/BLOCKED.md");
  await adapter.dispose();
});

test("能力声明全是 unverified/false：没有任何 supported 或 experimental", async () => {
  const adapter = new ClaudeCodeAdapter({ instanceId: "ai_claude_caps", command: MISSING });
  await adapter.probe();
  const declaration = adapter.capabilities();
  const states = flattenCapabilityStates(declaration.capabilities);
  assert.ok(states.length > 0);
  assert.equal(states.includes("supported"), false);
  assert.equal(states.includes("experimental"), false);
  assert.ok(states.includes("unverified"));
  await adapter.dispose();
});

test("所有会话入口抛 BLOCKED，绝不返回假成功", async () => {
  const adapter = new ClaudeCodeAdapter({ instanceId: "ai_claude_blocked", command: MISSING });
  await adapter.probe();
  for (const call of [
    () => adapter.createSession({ workspace: "/tmp" }),
    () => adapter.send({ sessionId: "ses_x", text: "hi" }),
    () => adapter.steer({ sessionId: "ses_x", text: "hi" }),
    () => adapter.interrupt({ sessionId: "ses_x" }),
    () => adapter.resume({ sessionId: "ses_x" }),
    () => adapter.fork({ sessionId: "ses_x" }),
  ]) {
    await assert.rejects(call(), (err) => err.code === "BLOCKED" && /AGT-111/.test(err.message));
  }
  await adapter.dispose();
});

test("能力矩阵中只能以 blocked 出现", async () => {
  const adapter = createClaudeCodeAdapter({ instanceId: "ai_claude_matrix", command: MISSING });
  await adapter.probe();
  const matrix = buildCapabilityMatrix([
    instanceFromAdapter(adapter, {
      release: "blocked",
      blockedReason: "no real binary/recording; see platform/adapters/BLOCKED.md",
      evidence: [{ kind: "manual", ref: "platform/adapters/BLOCKED.md" }],
    }),
  ]);
  const row = matrix.instances[0];
  assert.equal(row.release, "blocked");
  assert.equal(supportsCapability(matrix, "ai_claude_matrix", "streaming"), false);
  assert.equal(supportsCapability(matrix, "ai_claude_matrix", "turn", "interrupt"), false);
  assert.equal(supportsCapability(matrix, "ai_claude_matrix", "approvals", "command"), false);
  await adapter.dispose();
});

test("协议与阻塞证据是可引用的常量", () => {
  assert.equal(CLAUDE_CODE_TASK_ID, "AGT-111");
  assert.equal(CLAUDE_CODE_PROTOCOL.name, "stream-json");
  assert.ok(CLAUDE_CODE_PROTOCOL.invocation.includes("--output-format"));
  assert.equal(CLAUDE_CODE_BLOCKED_EVIDENCE.status, "blocked");
  assert.ok(CLAUDE_CODE_BLOCKED_EVIDENCE.commands.includes("claude --version"));
  assert.ok(CLAUDE_CODE_BLOCKED_EVIDENCE.nextSteps.length >= 3);
});
