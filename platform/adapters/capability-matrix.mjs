/**
 * 能力矩阵生成与校验（AGT-103 / ADP-017 / UI-003）。
 *
 * 规则来源（SPEC 8、TODO 5.4、CONTRACT §5）：
 *   - 能力是「实例 × 版本 × 执行环境」的运行期事实，不是产品名布尔值。
 *   - 没有真实二进制或 fixture 证据的能力只能是 unsupported / unverified / experimental。
 *   - release 不得高于能力本身能支撑的等级；BLOCKED 实例不得声明已支持能力。
 *   - 界面只读矩阵，不得按 Agent 名称分支（由 supportsCapability 提供唯一判断入口）。
 *
 * 本模块是纯函数，不读文件系统、不联网、不持久化。
 */
import { CAPABILITY_KEYS } from "../contracts/capabilities.mjs";
import { nowIso } from "../contracts/ids.mjs";
import { isValidCapabilityValue } from "./base.mjs";

export const MATRIX_VERSION = 1;

/** 发布状态：只有 supported 能进入首版默认支持列表（ACCEPTANCE §8）。 */
export const RELEASE_STATES = ["supported", "experimental", "unavailable", "blocked"];

/** 单能力聚合状态。partial 表示对象子项部分可用。 */
export const CAPABILITY_STATES = ["supported", "experimental", "unverified", "unsupported", "partial"];

/** 证据类型；supported 需要 recording 或 smoke（QA-003）。 */
export const EVIDENCE_KINDS = ["recording", "smoke", "handwritten-fixture", "protocol-trace", "manual"];

const REQUIRED_INSTANCE_FIELDS = ["instanceId", "provider", "version", "capabilities", "support", "release", "evidence"];

export class CapabilityMatrixError extends Error {
  constructor(issues) {
    const list = Array.isArray(issues) ? issues : [issues];
    super(`invalid capability matrix: ${list.map((i) => `${i.path}: ${i.message}`).join("; ")}`);
    this.name = "CapabilityMatrixError";
    this.code = "CAPABILITY_MATRIX_INVALID";
    this.issues = list;
  }
}

function leafState(value) {
  if (value === true) return "supported";
  if (value === "experimental") return "experimental";
  if (value === "unverified") return "unverified";
  return "unsupported";
}

function aggregateStates(values) {
  if (values.length === 0) return "unverified";
  if (values.every((v) => v === "supported")) return "supported";
  if (values.includes("experimental")) return "experimental";
  if (values.includes("unverified")) return "unverified";
  if (values.includes("supported")) return "partial";
  return "unsupported";
}

/** 把一个能力值（布尔/字符串/对象）压平成叶子状态列表。 */
export function flattenCapabilityStates(value, out = []) {
  if (value && typeof value === "object" && !Array.isArray(value)) {
    for (const v of Object.values(value)) flattenCapabilityStates(v, out);
    return out;
  }
  out.push(leafState(value));
  return out;
}

/** 每个顶层能力键 → 聚合状态。 */
export function computeSupportStates(capabilities = {}) {
  const support = {};
  for (const key of CAPABILITY_KEYS) {
    support[key] = aggregateStates(flattenCapabilityStates(capabilities[key]));
  }
  return support;
}

/** 由能力声明推导发布等级；只低不高。 */
export function deriveRelease(capabilities = {}) {
  const states = flattenCapabilityStates(
    Object.fromEntries(CAPABILITY_KEYS.map((k) => [k, capabilities[k]])),
  );
  if (states.every((s) => s === "unsupported")) return "unavailable";
  if (states.some((s) => s === "unsupported")) {
    // 部分能力缺失不阻断发布，但必须降级为 experimental（不得标 supported）。
    // unverified 同样意味着不可宣称 supported，但也不等于不可用。
    if (states.some((s) => s === "supported" || s === "experimental" || s === "unverified")) return "experimental";
    return "unavailable";
  }
  if (states.some((s) => s === "unverified" || s === "experimental")) return "experimental";
  return states.length ? "supported" : "unavailable";
}

function normalizeEvidence(evidence) {
  if (!Array.isArray(evidence)) return [];
  return evidence
    .filter((e) => e && typeof e === "object")
    .map((e) => ({ kind: e.kind ?? "manual", ref: e.ref ?? null, note: e.note ?? null }));
}

/**
 * 生成矩阵 JSON（可直接序列化给 UI / 诊断）。
 *
 * @param {Array<object>} instances 每项：
 *   { instanceId, provider, version, environment?, capabilities, evidence?, release?, blockedReason?, notes? }
 * @returns 能力矩阵对象
 */
export function buildCapabilityMatrix(instances = [], { generatedAt = nowIso(), strict = false } = {}) {
  if (!Array.isArray(instances)) throw new CapabilityMatrixError([{ path: "instances", message: "must be an array" }]);

  const seen = new Set();
  const rows = instances.map((instance, index) => {
    const path = `instances[${index}]`;
    if (!instance || typeof instance !== "object") throw new CapabilityMatrixError([{ path, message: "must be an object" }]);
    for (const field of ["instanceId", "provider", "version", "capabilities"]) {
      if (instance[field] === undefined || instance[field] === null || instance[field] === "") {
        throw new CapabilityMatrixError([{ path: `${path}.${field}`, message: "required" }]);
      }
    }
    if (seen.has(instance.instanceId)) {
      throw new CapabilityMatrixError([{ path: `${path}.instanceId`, message: "duplicate instanceId" }]);
    }
    seen.add(instance.instanceId);

    const capabilities = {};
    for (const key of CAPABILITY_KEYS) {
      if (!Object.hasOwn(instance.capabilities, key)) {
        throw new CapabilityMatrixError([{ path: `${path}.capabilities.${key}`, message: "missing capability key" }]);
      }
      const value = instance.capabilities[key];
      if (!isValidCapabilityValue(value)) {
        throw new CapabilityMatrixError([
          { path: `${path}.capabilities.${key}`, message: `illegal capability value ${JSON.stringify(value)}` },
        ]);
      }
      capabilities[key] = value;
    }
    const extraKeys = Object.keys(instance.capabilities).filter((k) => !CAPABILITY_KEYS.includes(k));
    if (extraKeys.length) {
      throw new CapabilityMatrixError(
        extraKeys.map((k) => ({ path: `${path}.capabilities.${k}`, message: "unknown capability key" })),
      );
    }

    return {
      instanceId: instance.instanceId,
      provider: instance.provider,
      version: instance.version,
      environment: instance.environment ?? "host",
      release: instance.release ?? deriveRelease(capabilities),
      capabilities,
      support: computeSupportStates(capabilities),
      evidence: normalizeEvidence(instance.evidence),
      blockedReason: instance.blockedReason ?? null,
      notes: instance.notes ?? null,
    };
  });

  const matrix = {
    matrixVersion: MATRIX_VERSION,
    generatedAt,
    columns: [...CAPABILITY_KEYS],
    instances: rows,
  };
  validateCapabilityMatrix(matrix, { strict });
  return matrix;
}

/** 非抛出版校验，供诊断面板使用。 */
export function checkCapabilityMatrix(matrix, { strict = false } = {}) {
  const issues = [];
  const warnings = [];
  const add = (path, message) => issues.push({ path, message });
  const warn = (path, message) => warnings.push({ path, message });

  if (!matrix || typeof matrix !== "object") return { ok: false, issues: [{ path: "$", message: "matrix must be an object" }], warnings };

  if (matrix.matrixVersion !== MATRIX_VERSION) add("$.matrixVersion", `must be ${MATRIX_VERSION}`);
  if (typeof matrix.generatedAt !== "string" || Number.isNaN(Date.parse(matrix.generatedAt))) {
    add("$.generatedAt", "must be an ISO-8601 timestamp");
  }
  if (!Array.isArray(matrix.columns) || matrix.columns.length !== CAPABILITY_KEYS.length) {
    add("$.columns", "must list every capability key");
  } else if (CAPABILITY_KEYS.some((k, i) => matrix.columns[i] !== k)) {
    add("$.columns", "must match contracts/capabilities.mjs CAPABILITY_KEYS order");
  }
  if (!Array.isArray(matrix.instances)) add("$.instances", "must be an array");
  if (!Array.isArray(matrix.instances) || issues.length) return { ok: false, issues, warnings };

  const seen = new Set();
  matrix.instances.forEach((row, index) => {
    const path = `$.instances[${index}]`;
    if (!row || typeof row !== "object") {
      add(path, "must be an object");
      return;
    }
    for (const field of REQUIRED_INSTANCE_FIELDS) {
      if (!Object.hasOwn(row, field)) add(`${path}.${field}`, "required");
    }
    if (seen.has(row.instanceId)) add(`${path}.instanceId`, "duplicate instanceId");
    seen.add(row.instanceId);
    if (!RELEASE_STATES.includes(row.release)) {
      add(`${path}.release`, `illegal release ${JSON.stringify(row.release)}`);
    }

    if (!row.capabilities || typeof row.capabilities !== "object") {
      add(`${path}.capabilities`, "must be an object");
      return;
    }
    for (const key of CAPABILITY_KEYS) {
      if (!Object.hasOwn(row.capabilities, key)) {
        add(`${path}.capabilities.${key}`, "missing capability key");
        continue;
      }
      if (!isValidCapabilityValue(row.capabilities[key])) {
        add(`${path}.capabilities.${key}`, `illegal capability value ${JSON.stringify(row.capabilities[key])}`);
      }
    }
    for (const key of Object.keys(row.capabilities)) {
      if (!CAPABILITY_KEYS.includes(key)) add(`${path}.capabilities.${key}`, "unknown capability key");
    }

    // support 视图必须与 capabilities 一致，避免 UI 读到过期投影。
    const expected = computeSupportStates(row.capabilities);
    if (!row.support || typeof row.support !== "object") {
      add(`${path}.support`, "must be an object");
    } else {
      for (const key of CAPABILITY_KEYS) {
        const state = row.support[key];
        if (!CAPABILITY_STATES.includes(state)) {
          add(`${path}.support.${key}`, `illegal support state ${JSON.stringify(state)}`);
        } else if (expected[key] && state !== expected[key]) {
          add(`${path}.support.${key}`, `support state ${state} disagrees with capabilities (${expected[key]})`);
        }
      }
    }

    if (!Array.isArray(row.evidence)) add(`${path}.evidence`, "must be an array");
    for (const [i, item] of (row.evidence ?? []).entries()) {
      if (!item || typeof item !== "object") add(`${path}.evidence[${i}]`, "must be an object");
      else if (!EVIDENCE_KINDS.includes(item.kind)) add(`${path}.evidence[${i}].kind`, "illegal evidence kind");
    }

    const derived = deriveRelease(row.capabilities);
    const rank = { unavailable: 0, blocked: 0, experimental: 1, supported: 2 };
    if (RELEASE_STATES.includes(row.release) && rank[row.release] > rank[derived]) {
      add(`${path}.release`, `release ${row.release} overstates capabilities (max ${derived})`);
    }
    if (row.release === "blocked" && (!row.blockedReason || String(row.blockedReason).trim() === "")) {
      add(`${path}.blockedReason`, "blocked instance must record a reason");
    }
    if (row.release === "blocked" || row.release === "unavailable") {
      const states = flattenCapabilityStates(row.capabilities);
      if (states.includes("supported") || states.includes("experimental")) {
        add(`${path}.release`, `${row.release} instance must not declare supported/experimental capabilities`);
      }
    }
    if (row.release === "supported") {
      const evidence = Array.isArray(row.evidence) ? row.evidence : [];
      if (!evidence.some((e) => e?.kind === "recording" || e?.kind === "smoke")) {
        add(`${path}.evidence`, "release supported requires recording or smoke evidence");
      }
      const states = flattenCapabilityStates(row.capabilities);
      if (states.some((s) => s !== "supported")) {
        add(`${path}.capabilities`, "release supported requires every capability to be true");
      }
    }
    if (strict && row.release === "experimental" && (row.evidence ?? []).length === 0) {
      warn(`${path}.evidence`, "experimental instance without evidence");
    }
  });

  return { ok: issues.length === 0, issues, warnings };
}

/** 校验矩阵；不合法直接抛 CapabilityMatrixError（缺键 / 非法值都报错）。 */
export function validateCapabilityMatrix(matrix, { strict = false } = {}) {
  const result = checkCapabilityMatrix(matrix, { strict });
  if (!result.ok) throw new CapabilityMatrixError(result.issues);
  if (strict && result.warnings.length) {
    throw new CapabilityMatrixError(result.warnings.map((w) => ({ ...w, message: `[strict] ${w.message}` })));
  }
  return matrix;
}

function findInstance(matrix, instanceId) {
  const row = matrix?.instances?.find((i) => i.instanceId === instanceId);
  if (!row) {
    throw new CapabilityMatrixError([{ path: `instances.${instanceId}`, message: "unknown instance" }]);
  }
  return row;
}

/** 单能力状态：supported / experimental / unverified / unsupported / partial。 */
export function capabilityState(matrix, instanceId, key, sub = null) {
  const row = findInstance(matrix, instanceId);
  if (!CAPABILITY_KEYS.includes(key)) {
    throw new CapabilityMatrixError([{ path: `${instanceId}.capabilities.${key}`, message: "unknown capability key" }]);
  }
  let value = row.capabilities?.[key];
  if (sub !== null) {
    if (!value || typeof value !== "object") return "unsupported";
    if (!Object.hasOwn(value, sub)) return "unverified";
    value = value[sub];
  }
  return aggregateStates(flattenCapabilityStates(value));
}

/** UI 唯一的“能不能用”判断入口；unverified 不算支持。 */
export function supportsCapability(matrix, instanceId, key, sub = null) {
  const state = capabilityState(matrix, instanceId, key, sub);
  return state === "supported" || state === "experimental";
}

/** 该实例是否可被 UI 暴露；blocked/unavailable 时为 false 并给出原因。 */
export function isInstanceUsable(matrix, instanceId) {
  const row = findInstance(matrix, instanceId);
  return {
    usable: row.release === "supported" || row.release === "experimental",
    release: row.release,
    reason: row.release === "supported" || row.release === "experimental" ? null : row.blockedReason ?? `release=${row.release}`,
  };
}

/** 便捷：从已 probe 的适配器生成一条矩阵实例。 */
export function instanceFromAdapter(adapter, { evidence = [], release, blockedReason = null, notes = null } = {}) {
  const describe = adapter.describe();
  if (!describe.declared) {
    throw new CapabilityMatrixError([
      { path: `instances.${describe.instanceId}`, message: "adapter has no capability declaration; call probe() first" },
    ]);
  }
  return {
    instanceId: describe.instanceId,
    provider: describe.provider,
    version: describe.version ?? describe.declared.version,
    environment: describe.environment,
    capabilities: describe.declared.capabilities,
    evidence,
    release,
    blockedReason,
    notes,
  };
}
