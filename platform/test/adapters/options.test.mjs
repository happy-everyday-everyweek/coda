/** AGT-109：模型/推理档位/权限模式的能力发现与降级。 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  applyOptions,
  assertRealModel,
  discoverOptions,
  isModelSentinel,
  reconcileAfterResume,
  resolveModelRequest,
  resolvePermissionMode,
  resolveReasoningEffort,
} from "../../adapters/options.mjs";
import { AdapterError } from "../../adapters/base.mjs";

test("上游没有模型列表：state=unsupported，不臆造选项", () => {
  const discovery = discoverOptions({});
  assert.equal(discovery.models.state, "unsupported");
  assert.equal(discovery.models.items.length, 0);
  assert.equal(discovery.reasoningEffort.state, "unsupported");
  assert.equal(discovery.permissionMode.state, "unsupported");
  assert.match(discovery.models.reason, /did not declare/);
});

test("default/auto 不是模型名：既不发送也不当真选项", () => {
  assert.equal(isModelSentinel("default"), true);
  assert.equal(isModelSentinel("AUTO"), true);
  assert.equal(isModelSentinel(""), true);
  assert.equal(isModelSentinel("gpt-5"), false);
  assert.throws(() => assertRealModel("default"), (err) => err instanceof AdapterError && err.code === "MODEL_UNAVAILABLE");

  const discovery = discoverOptions({ models: [{ id: "default" }, { id: "gpt-5" }] });
  assert.deepEqual(
    discovery.models.items.map((m) => m.id),
    ["gpt-5"],
  );
  assert.ok(discovery.degradations.some((d) => d.code === "SENTINEL_IGNORED"));

  const requested = resolveModelRequest("default", discovery);
  assert.equal(requested.ok, true);
  assert.equal(requested.model, null, "sentinel must never be sent upstream");
  assert.equal(requested.code, "NO_EXPLICIT_MODEL");
});

test("请求上游没有声明的模型：明确失败，不静默改写", () => {
  const discovery = discoverOptions({ models: [{ id: "gpt-5" }] });
  const result = resolveModelRequest("claude-sonnet", discovery);
  assert.equal(result.ok, false);
  assert.equal(result.code, "MODEL_UNAVAILABLE");
  assert.deepEqual(result.available, ["gpt-5"]);

  const unsupported = resolveModelRequest("gpt-5", discoverOptions({}));
  assert.equal(unsupported.ok, false);
  assert.equal(unsupported.code, "UNSUPPORTED_OPTION");
});

test("推理档位与权限模式按声明解析", () => {
  const discovery = discoverOptions({ reasoningEfforts: ["low", "medium", "high"], permissionModes: ["plan", "accept-edits"] });
  assert.equal(discovery.reasoningEffort.state, "supported");
  assert.equal(resolveReasoningEffort("high", discovery).ok, true);
  assert.equal(resolveReasoningEffort("xhigh", discovery).ok, false);
  assert.equal(resolvePermissionMode("plan", discovery).ok, true);
  assert.equal(resolvePermissionMode("bypass", discovery).ok, false);
  assert.equal(resolvePermissionMode(null, discovery).value, null);
});

test("applyOptions 汇总 applied / degradations / failures", () => {
  const discovery = discoverOptions({ models: [{ id: "gpt-5" }], reasoningEfforts: ["low", "high"] });
  const ok = applyOptions({ model: "gpt-5", reasoningEffort: "low" }, discovery);
  assert.equal(ok.ok, true);
  assert.deepEqual(ok.applied, { model: "gpt-5", reasoningEffort: "low", permissionMode: null });
  assert.deepEqual(ok.failures, []);

  const degraded = applyOptions({ model: "default" }, discovery);
  assert.equal(degraded.ok, true);
  assert.equal(degraded.applied.model, null);
  assert.ok(degraded.degradations.some((d) => d.code === "NO_EXPLICIT_MODEL"));

  const failed = applyOptions({ model: "nope", reasoningEffort: "xhigh" }, discovery);
  assert.equal(failed.ok, false);
  assert.equal(failed.failures.length, 2);
  assert.deepEqual(
    failed.failures.map((f) => f.field).sort(),
    ["model", "reasoningEffort"],
  );
});

test("会话恢复后重新核对选项：旧缓存失效可见", () => {
  const discovery = discoverOptions({ models: [{ id: "m2" }] });
  const reconciled = reconcileAfterResume({ previous: { model: "m1" }, discovery });
  assert.equal(reconciled.stale, true);
  assert.equal(reconciled.applied.model, null);
  assert.deepEqual(reconciled.changed, [{ field: "model", from: "m1", to: null }]);
  assert.equal(reconciled.failures[0].code, "MODEL_UNAVAILABLE");
});
