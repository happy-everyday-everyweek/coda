/** CONTRACT §8：adapters/index.mjs 的对外入口必须稳定。 */
import test from "node:test";
import assert from "node:assert/strict";

import * as adapters from "../../adapters/index.mjs";

test("CONTRACT §8 入口导出齐全", () => {
  assert.equal(typeof adapters.createMockAdapter, "function");
  assert.equal(typeof adapters.createAcpAdapter, "function");
  assert.equal(typeof adapters.buildCapabilityMatrix, "function");
  assert.equal(typeof adapters.buildMatrixFromAdapters, "function");
  assert.equal(typeof adapters.buildIsolatedEnv, "function");
  assert.equal(typeof adapters.runProcess, "function");
  assert.equal(typeof adapters.createSessionLifecycle, "function");
  assert.equal(typeof adapters.createRecorder, "function");
  assert.equal(typeof adapters.replayFixture, "function");
  assert.equal(typeof adapters.discoverAgent, "function");
  assert.equal(typeof adapters.supportsCapability, "function");
  assert.equal(typeof adapters.createClaudeCodeAdapter, "function");
  assert.equal(typeof adapters.createCodexAdapter, "function");
  assert.equal(typeof adapters.ProviderAdapter, "function");
});

test("buildMatrixFromAdapters 从已 probe 适配器生成矩阵（mock 只能是 experimental）", async () => {
  const adapter = adapters.createMockAdapter({ instanceId: "ai_mock_index" });
  await adapter.probe();
  const matrix = await adapters.buildMatrixFromAdapters([adapter]);
  assert.equal(matrix.instances[0].instanceId, "ai_mock_index");
  assert.equal(matrix.instances[0].provider, "mock");
  assert.equal(matrix.instances[0].release, "experimental");
  assert.equal(adapters.supportsCapability(matrix, "ai_mock_index", "tools"), true);
  assert.equal(adapters.supportsCapability(matrix, "ai_mock_index", "plan"), false);
  await adapter.dispose();
});
