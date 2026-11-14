/**
 * 模型 / 推理档位 / 权限模式的能力发现与降级（AGT-109 / ADP-012 / SPEC 9.6）。
 *
 * 硬规则：
 *   - 只展示上游实际声明的选项；没有声明就是 unsupported，不臆造。
 *   - `default` / `auto` 这类占位值**不是**模型名，绝不能发给上游（SPEC 8.2）。
 *   - 不支持的请求不静默改写：要么明确失败，要么返回带原因和 code 的降级记录。
 */
import { AdapterError } from "./base.mjs";

/** 占位/哨兵值：不是真实模型名。 */
export const MODEL_SENTINELS = Object.freeze(["default", "auto", "", "none", "null"]);

export const OPTION_STATES = ["supported", "unsupported", "unknown"];

export function isModelSentinel(value) {
  if (value === null || value === undefined) return true;
  return MODEL_SENTINELS.includes(String(value).trim().toLowerCase());
}

function normalizeItems(items = [], { idKeys = ["id", "model", "value"], filterSentinels = false } = {}) {
  const out = [];
  const skipped = [];
  for (const item of items) {
    const raw = typeof item === "string" ? { id: item } : item ?? {};
    const id = idKeys.map((k) => raw[k]).find((v) => typeof v === "string" && v.trim() !== "") ?? null;
    if (!id) {
      skipped.push({ item, reason: "missing id" });
      continue;
    }
    if (filterSentinels && isModelSentinel(id)) {
      skipped.push({ item, reason: "sentinel value is not a real model name" });
      continue;
    }
    out.push({
      id,
      label: raw.label ?? raw.name ?? raw.displayName ?? id,
      description: raw.description ?? null,
      isDefault: raw.isDefault === true || raw.default === true,
    });
  }
  return { items: out, skipped };
}

function optionGroup(state, items, { reason = null, defaultId = null } = {}) {
  return { state, items, defaultId, reason };
}

/**
 * 从上游声明构造选项发现结果。
 * @param {{models?: any[], reasoningEfforts?: any[], permissionModes?: any[], declaredCapabilities?: object}} input
 */
export function discoverOptions({ models = null, reasoningEfforts = null, permissionModes = null } = {}) {
  const degradations = [];
  const modelResult = normalizeItems(models ?? [], { filterSentinels: true });
  for (const skip of modelResult.skipped) {
    degradations.push({
      field: "model",
      code: "SENTINEL_IGNORED",
      message: `ignored upstream value ${JSON.stringify(skip.item)}: ${skip.reason}`,
    });
  }
  const modelsGroup =
    modelResult.items.length > 0
      ? optionGroup("supported", modelResult.items, { defaultId: modelResult.items.find((m) => m.isDefault)?.id ?? null })
      : optionGroup("unsupported", [], { reason: "upstream did not declare a model list" });

  const reasoningResult = normalizeItems(reasoningEfforts ?? [], { idKeys: ["id", "effort", "value"] });
  const reasoningGroup =
    reasoningResult.items.length > 0
      ? optionGroup("supported", reasoningResult.items)
      : optionGroup("unsupported", [], { reason: "upstream did not declare reasoning effort levels" });

  const permissionResult = normalizeItems(permissionModes ?? [], { idKeys: ["id", "mode", "value"] });
  const permissionGroup =
    permissionResult.items.length > 0
      ? optionGroup("supported", permissionResult.items)
      : optionGroup("unsupported", [], { reason: "upstream did not declare permission modes" });

  return { models: modelsGroup, reasoningEffort: reasoningGroup, permissionMode: permissionGroup, degradations };
}

/** 解析模型请求；哨兵值不会被当作模型名发出去。 */
export function resolveModelRequest(requested, discovery) {
  if (requested === null || requested === undefined || isModelSentinel(requested)) {
    return {
      ok: true,
      model: null,
      degraded: true,
      code: "NO_EXPLICIT_MODEL",
      reason: "no explicit model requested; adapter must not send a sentinel value",
    };
  }
  const group = discovery?.models;
  if (!group || group.state !== "supported") {
    return {
      ok: false,
      model: null,
      code: "UNSUPPORTED_OPTION",
      reason: "upstream does not expose model selection",
    };
  }
  const hit = group.items.find((item) => item.id === requested);
  if (!hit) {
    return {
      ok: false,
      model: null,
      code: "MODEL_UNAVAILABLE",
      reason: `model ${JSON.stringify(requested)} was not declared by upstream`,
      available: group.items.map((i) => i.id),
    };
  }
  return { ok: true, model: hit.id, degraded: false, code: null, reason: null };
}

export function resolveReasoningEffort(requested, discovery) {
  return resolveSimpleOption("reasoningEffort", requested, discovery);
}

export function resolvePermissionMode(requested, discovery) {
  return resolveSimpleOption("permissionMode", requested, discovery);
}

function resolveSimpleOption(field, requested, discovery) {
  if (requested === null || requested === undefined || String(requested).trim() === "") {
    return { ok: true, value: null, degraded: false, code: null, reason: null };
  }
  const group = discovery?.[field];
  if (!group || group.state !== "supported") {
    return {
      ok: false,
      value: null,
      code: "UNSUPPORTED_OPTION",
      reason: `upstream does not expose ${field} selection`,
    };
  }
  const hit = group.items.find((item) => item.id === requested);
  if (!hit) {
    return {
      ok: false,
      value: null,
      code: "UNSUPPORTED_OPTION",
      reason: `${field} ${JSON.stringify(requested)} was not declared by upstream`,
      available: group.items.map((i) => i.id),
    };
  }
  return { ok: true, value: hit.id, degraded: false, code: null, reason: null };
}

/**
 * 一次性解析整组选项。
 * @returns {{ok: boolean, applied: object, degradations: Array<object>, failures: Array<object>}}
 */
export function applyOptions({ model = null, reasoningEffort = null, permissionMode = null } = {}, discovery) {
  const degradations = [...(discovery?.degradations ?? [])];
  const failures = [];
  const applied = { model: null, reasoningEffort: null, permissionMode: null };

  const modelResult = resolveModelRequest(model, discovery);
  if (modelResult.ok) {
    applied.model = modelResult.model;
    if (modelResult.degraded) degradations.push({ field: "model", code: modelResult.code, message: modelResult.reason });
  } else {
    failures.push({ field: "model", code: modelResult.code, message: modelResult.reason, available: modelResult.available ?? [] });
  }

  for (const [field, requested, result] of [
    ["reasoningEffort", reasoningEffort, resolveReasoningEffort(reasoningEffort, discovery)],
    ["permissionMode", permissionMode, resolvePermissionMode(permissionMode, discovery)],
  ]) {
    if (result.ok) applied[field] = result.value;
    else failures.push({ field, code: result.code, message: result.reason, available: result.available ?? [] });
  }

  return { ok: failures.length === 0, applied, degradations, failures };
}

/** 会话恢复后重新核对选项（SPEC 9.6：不能盲用旧缓存）。 */
export function reconcileAfterResume({ previous = {}, discovery } = {}) {
  const result = applyOptions(previous, discovery);
  const changed = [];
  for (const field of ["model", "reasoningEffort", "permissionMode"]) {
    if ((previous?.[field] ?? null) !== (result.applied?.[field] ?? null)) {
      changed.push({ field, from: previous?.[field] ?? null, to: result.applied?.[field] ?? null });
    }
  }
  return { ...result, changed, stale: changed.length > 0 };
}

/** 拒绝把哨兵值写进上游请求。 */
export function assertRealModel(model) {
  if (isModelSentinel(model)) {
    throw new AdapterError("MODEL_UNAVAILABLE", `refusing to send sentinel model value ${JSON.stringify(model)}`, {
      details: { sentinels: [...MODEL_SENTINELS] },
    });
  }
  return model;
}
