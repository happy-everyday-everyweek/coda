/** AGT-112：Codex 骨架只能 probe（app-server 协议探测），其余入口必须 BLOCKED。 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  CODEX_BLOCKED_EVIDENCE,
  CODEX_PROTOCOL,
  CODEX_TASK_ID,
  CodexAdapter,
  createCodexAdapter,
} from "../../adapters/codex.mjs";
import {
  buildCapabilityMatrix,
  flattenCapabilityStates,
  instanceFromAdapter,
  supportsCapability,
} from "../../adapters/capability-matrix.mjs";

const MISSING = "coda-definitely-not-real-codex-xyz";

test("未安装时 probe 返回 installed:false，并指向 BLOCKED 记录", async () => {
  const adapter = new CodexAdapter({ instanceId: "ai_codex_missing", command: MISSING });
  const probe = await adapter.probe();
  assert.equal(probe.installed, false);
  assert.equal(probe.sideEffectFree, true);
  assert.equal(probe.details.taskId, "AGT-112");
  assert.equal(probe.details.doc, "platform/adapters/BLOCKED.md");
  await adapter.dispose();
});

test("能力声明全是 unverified/false：app-server 语义未验证前不声明支持", async () => {
  const adapter = new CodexAdapter({ instanceId: "ai_codex_caps", command: MISSING });
  await adapter.probe();
  const states = flattenCapabilityStates(adapter.capabilities().capabilities);
  assert.equal(states.includes("supported"), false);
  assert.equal(states.includes("experimental"), false);
  await adapter.dispose();
});

test("所有会话入口抛 BLOCKED", async () => {
  const adapter = new CodexAdapter({ instanceId: "ai_codex_blocked", command: MISSING });
  await adapter.probe();
  await assert.rejects(adapter.createSession({ workspace: "/tmp" }), (err) => err.code === "BLOCKED" && /AGT-112/.test(err.message));
  await assert.rejects(adapter.send({ sessionId: "ses_x", text: "hi" }), (err) => err.code === "BLOCKED");
  await assert.rejects(adapter.resume({ sessionId: "ses_x" }), (err) => err.code === "BLOCKED");
  await adapter.dispose();
});

test("能力矩阵中只能以 blocked 出现", async () => {
  const adapter = createCodexAdapter({ instanceId: "ai_codex_matrix", command: MISSING });
  await adapter.probe();
  const matrix = buildCapabilityMatrix([
    instanceFromAdapter(adapter, {
      release: "blocked",
      blockedReason: "no app-server recording; see platform/adapters/BLOCKED.md",
      evidence: [{ kind: "manual", ref: "platform/adapters/BLOCKED.md" }],
    }),
  ]);
  assert.equal(matrix.instances[0].release, "blocked");
  assert.equal(supportsCapability(matrix, "ai_codex_matrix", "streaming", "text"), false);
  assert.equal(supportsCapability(matrix, "ai_codex_matrix", "approvals"), false);
  await adapter.dispose();
});

test("协议与阻塞证据常量：app-server / JSON-RPC over stdio", () => {
  assert.equal(CODEX_TASK_ID, "AGT-112");
  assert.equal(CODEX_PROTOCOL.name, "app-server");
  assert.equal(CODEX_PROTOCOL.transport, "jsonrpc-stdio");
  assert.ok(CODEX_PROTOCOL.invocation.includes("app-server"));
  assert.equal(CODEX_BLOCKED_EVIDENCE.status, "blocked");
  assert.ok(CODEX_BLOCKED_EVIDENCE.commands.includes("codex --version"));
});
