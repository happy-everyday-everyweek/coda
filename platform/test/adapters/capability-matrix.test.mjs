/** AGT-103：能力矩阵生成、校验与唯一查询入口。 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  CapabilityMatrixError,
  buildCapabilityMatrix,
  capabilityState,
  checkCapabilityMatrix,
  deriveRelease,
  instanceFromAdapter,
  isInstanceUsable,
  supportsCapability,
  validateCapabilityMatrix,
} from "../../adapters/capability-matrix.mjs";
import { MockAdapter } from "../../adapters/mock.mjs";
import { CAPABILITY_KEYS } from "../../contracts/capabilities.mjs";

/** 全部 unsupported 的安全底座。 */
function caps(overrides = {}) {
  return { ...Object.fromEntries(CAPABILITY_KEYS.map((k) => [k, "unsupported"])), ...overrides };
}

function instance(overrides = {}) {
  return {
    instanceId: "ai_acp_1",
    provider: "acp-generic",
    version: "1.0.0",
    environment: "host",
    capabilities: caps({
      streaming: { text: "experimental" },
      turn: { startEndEvents: "experimental", nativeSteer: false },
    }),
    evidence: [{ kind: "protocol-trace", ref: "fixtures/adapters/acp-happy-path.fixture.json" }],
    release: "experimental",
    ...overrides,
  };
}

test("生成矩阵：列、行、support 投影与可序列化", () => {
  const matrix = buildCapabilityMatrix([instance()]);
  assert.equal(matrix.matrixVersion, 1);
  assert.deepEqual(matrix.columns, CAPABILITY_KEYS);
  assert.equal(matrix.instances.length, 1);
  const row = matrix.instances[0];
  assert.equal(row.instanceId, "ai_acp_1");
  assert.equal(row.release, "experimental");
  assert.equal(row.support.streaming, "experimental");
  assert.equal(row.support.tools, "unsupported");
  assert.deepEqual(JSON.parse(JSON.stringify(matrix)), matrix);
  assert.deepEqual(validateCapabilityMatrix(matrix).instances.length, 1);
});

test("supportsCapability 是 UI 唯一判断入口：unverified 不算支持", () => {
  const matrix = buildCapabilityMatrix([
    instance({
      instanceId: "ai_unverified",
      capabilities: caps({ streaming: { text: "unverified" }, tools: true }),
      release: "experimental",
    }),
  ]);
  assert.equal(supportsCapability(matrix, "ai_unverified", "streaming", "text"), false);
  assert.equal(capabilityState(matrix, "ai_unverified", "streaming", "text"), "unverified");
  // release=experimental 且含 true：仍然不能把 streaming 当支持
  assert.equal(supportsCapability(matrix, "ai_unverified", "streaming"), false);
  assert.equal(supportsCapability(matrix, "ai_unverified", "tools"), true);
  assert.equal(supportsCapability(matrix, "ai_unverified", "plan"), false);
});

test("缺键报错并给出精确路径", () => {
  const broken = caps();
  delete broken.plan;
  assert.throws(
    () => buildCapabilityMatrix([instance({ capabilities: broken })]),
    (err) => err instanceof CapabilityMatrixError && err.issues.some((i) => i.path.endsWith(".plan")),
  );
});

test("非法值报错", () => {
  assert.throws(
    () => buildCapabilityMatrix([instance({ capabilities: caps({ tools: "maybe" }) })]),
    /illegal capability value/,
  );
  assert.throws(
    () => buildCapabilityMatrix([instance({ capabilities: caps({ extra: true }) })]),
    /unknown capability key/,
  );
});

test("release 不得高于能力：blocked 实例声明 experimental 直接拒绝", () => {
  assert.throws(
    () =>
      buildCapabilityMatrix([
        instance({
          instanceId: "ai_claude",
          provider: "claude-code",
          capabilities: caps({ streaming: { text: "experimental" } }),
          release: "blocked",
          blockedReason: "no real binary",
        }),
      ]),
    /must not declare supported\/experimental/,
  );
});

test("release=support 需要真实证据且所有能力为 true", () => {
  const allTrue = Object.fromEntries(CAPABILITY_KEYS.map((k) => [k, true]));
  assert.throws(
    () =>
      buildCapabilityMatrix([
        instance({
          instanceId: "ai_liar",
          capabilities: allTrue,
          release: "supported",
          evidence: [{ kind: "handwritten-fixture", ref: "fixtures/adapters/acp-happy-path.fixture.json" }],
        }),
      ]),
    /requires recording or smoke evidence/,
  );
});

test("release 过高（capabilities 只到 experimental）被拒绝", () => {
  assert.throws(
    () =>
      buildCapabilityMatrix([
        instance({
          capabilities: caps({ streaming: { text: "unverified" } }),
          release: "supported",
          evidence: [{ kind: "recording", ref: "x" }],
        }),
      ]),
    /overstates capabilities/,
  );
});

test("blocked 实例必须写 blockedReason", () => {
  assert.throws(
    () =>
      buildCapabilityMatrix([
        instance({
          capabilities: caps({ streaming: { text: "unverified" } }),
          release: "blocked",
        }),
      ]),
    /blocked instance must record a reason/,
  );
});

test("未知实例/未知能力键显式报错，不静默返回 false", () => {
  const matrix = buildCapabilityMatrix([instance()]);
  assert.throws(
    () => supportsCapability(matrix, "ai_missing", "streaming"),
    (err) => err instanceof CapabilityMatrixError && /unknown instance/.test(err.issues[0].message),
  );
  assert.throws(
    () => supportsCapability(matrix, "ai_acp_1", "teleport"),
    (err) => err instanceof CapabilityMatrixError && /unknown capability key/.test(err.issues[0].message),
  );
});

test("篡改 support 投影会被校验器发现", () => {
  const matrix = buildCapabilityMatrix([instance()]);
  matrix.instances[0].support.streaming = "supported";
  const check = checkCapabilityMatrix(matrix);
  assert.equal(check.ok, false);
  assert.ok(check.issues.some((i) => /disagrees with capabilities/.test(i.message)));
});

test("deriveRelease 与 isInstanceUsable", () => {
  assert.equal(deriveRelease(caps()), "unavailable");
  assert.equal(deriveRelease(caps({ streaming: { text: "experimental" } })), "experimental");
  assert.equal(deriveRelease(Object.fromEntries(CAPABILITY_KEYS.map((k) => [k, true]))), "supported");
  assert.equal(deriveRelease(caps({ streaming: { text: "unverified" } })), "experimental");

  const matrix = buildCapabilityMatrix([
    instance(),
    instance({
      instanceId: "ai_blocked",
      capabilities: caps({ streaming: { text: "unverified" } }),
      release: "blocked",
      blockedReason: "no binary",
    }),
  ]);
  assert.deepEqual(isInstanceUsable(matrix, "ai_acp_1"), { usable: true, release: "experimental", reason: null });
  const blocked = isInstanceUsable(matrix, "ai_blocked");
  assert.equal(blocked.usable, false);
  assert.equal(blocked.reason, "no binary");
});

test("instanceFromAdapter：从已 probe 的适配器生成实例行", async () => {
  const adapter = new MockAdapter({ instanceId: "ai_mock_matrix" });
  await adapter.probe();
  const matrix = buildCapabilityMatrix([instanceFromAdapter(adapter, { release: "experimental" })]);
  const row = matrix.instances[0];
  assert.equal(row.provider, "mock");
  assert.equal(row.version, "mock/1.0.0");
  assert.equal(row.environment, "host");
  assert.equal(row.release, "experimental");
  await adapter.dispose();

  const fresh = new MockAdapter({ instanceId: "ai_mock_unprobed" });
  assert.throws(() => instanceFromAdapter(fresh), /no capability declaration/);
  await fresh.dispose();
});
