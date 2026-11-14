/**
 * 高度自主提示、一次性确认与行动审计（PA-105，验收 PA-005 / PA-006 / PA-007 / SEC-005）。
 *
 * 产品约束（SPEC 10.3 / 14.2）：
 * - 首启必须明确告知：PA 可自主选择 Agent、创建会话、派发任务、处理审批、跨端操作。
 * - 首版只有「高度自主」一个档位：没有额外自主程度切换，也没有专门的 PA 暂停开关。
 * - 普通安全操作仍然可用：停止具体 Agent 回合、停止核心、撤销设备授权。
 * - 每次 PA 自动审批/派发都必须独立记录：时间、来源节点、目标、参数摘要、决定、结果、决定者。
 *   决定者字段 actor 明确区分 pa / user / system，不能把 PA 的决定记到用户头上。
 */
import { isIdOf, nowIso } from "../contracts/ids.mjs";

export const AUTONOMY_NOTICE_VERSION = "pa-autonomy-1";

export const AUTONOMY_NOTICE = [
  "PA 高度自主模式（首版唯一档位，试验性）",
  "",
  "启用后，PA 可以：",
  "1. 自主选择 Agent、创建会话并派发任务（可自主派发）；",
  "2. 自动处理命令、文件修改等审批（可自主处理审批，也可以拒绝）；",
  "3. 在已配对设备之间发起远程操作（可跨端操作）。",
  "",
  "每次自主审批与派发都会写入独立审计，记录来源、目标、时间、参数摘要、决定和结果；失败可追踪。",
  "高度自主不能绕过设备配对、目标节点授权或上游 Agent 的真实权限机制。",
  "首版不提供额外的自主程度切换，也没有专门的 PA 暂停开关；你仍可以停止具体 Agent 回合、停止核心或撤销设备授权。",
].join("\n");

export const AUTONOMY_MODES = Object.freeze(["high"]);
export const PA_EXTRA_AUTONOMY_MODES = Object.freeze([]);
export const PA_PAUSE_SWITCH = false;

export const AUDIT_ACTORS = Object.freeze(["pa", "user", "system"]);
export const AUDIT_DECISIONS = Object.freeze([
  "dispatch",
  "approve",
  "deny",
  "answer",
  "query",
  "acknowledge",
  "migrate",
  "rollback",
  "notify",
]);

const SECRET_KEY_PATTERN = /(api[_-]?key|secret|token|passwd|password|credential|authorization|cookie)/i;

/**
 * 审计参数摘要：递归截断，敏感键一律替换为 [redacted]。
 * 审计里不允许出现 Token、API Key、配对码等秘密（SPEC 14.3 / SEC-001）。
 */
export function summarizeForAudit(value, { maxLength = 240, maxDepth = 4, maxArray = 10, maxString = 120 } = {}) {
  const seen = new WeakSet();
  function sanitize(current, depth) {
    if (current === null || current === undefined) return current ?? null;
    if (typeof current === "string") {
      return current.length > maxString ? `${current.slice(0, maxString)}…` : current;
    }
    if (typeof current === "number" || typeof current === "boolean") return current;
    if (typeof current === "bigint") return current.toString();
    if (typeof current === "function" || typeof current === "symbol") return "[omitted]";
    if (depth >= maxDepth) return "[truncated]";
    if (typeof current === "object") {
      if (seen.has(current)) return "[circular]";
      seen.add(current);
      if (Array.isArray(current)) {
        const items = current.slice(0, maxArray).map((item) => sanitize(item, depth + 1));
        if (current.length > maxArray) items.push(`…(+${current.length - maxArray})`);
        return items;
      }
      const output = {};
      for (const key of Object.keys(current)) {
        output[key] = SECRET_KEY_PATTERN.test(key) ? "[redacted]" : sanitize(current[key], depth + 1);
      }
      return output;
    }
    return String(current);
  }

  let text;
  try {
    text = JSON.stringify(sanitize(value, 0));
  } catch {
    text = String(value);
  }
  if (text === undefined) return "";
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
}

function normalizeResult(result) {
  if (result === null || result === undefined) return null;
  if (typeof result === "string") return { state: result, ok: null, duplicate: null };
  if (typeof result !== "object") return { state: String(result), ok: null, duplicate: null };
  return {
    state: typeof result.state === "string" ? result.state : result.ok === true ? "succeeded" : "unknown",
    ok: typeof result.ok === "boolean" ? result.ok : null,
    duplicate: typeof result.duplicate === "boolean" ? result.duplicate : null,
  };
}

export function describeAutonomyControls() {
  return {
    highAutonomyOnly: true,
    modes: [{ id: "high", label: "高度自主（首版唯一档位）" }],
    extraModeSwitch: false,
    pauseSwitch: false,
    genericSafetyControls: [
      { id: "turn.stop", available: true, note: "停止具体 Agent 回合" },
      { id: "core.stop", available: true, note: "停止核心" },
      { id: "device.revoke", available: true, note: "撤销设备授权" },
    ],
  };
}

export function createAuditLog(store) {
  if (!store) throw new Error("createAuditLog requires a PA store");

  function append(entry = {}) {
    if (typeof entry.action !== "string" || entry.action.length === 0) {
      throw new Error("audit entry requires action");
    }
    if (typeof entry.decision !== "string" || entry.decision.length === 0) {
      throw new Error("audit entry requires decision");
    }
    const actor = entry.actor ?? "pa";
    if (!AUDIT_ACTORS.includes(actor)) throw new Error(`audit actor must be one of ${AUDIT_ACTORS.join(", ")}`);
    if (!AUDIT_DECISIONS.includes(entry.decision)) {
      throw new Error(`audit decision must be one of ${AUDIT_DECISIONS.join(", ")}`);
    }
    if (!isIdOf(entry.sourceNode, "dev")) throw new Error("audit entry requires sourceNode (dev_ ID)");
    const record = {
      at: entry.at ?? nowIso(),
      actor,
      action: entry.action,
      decision: entry.decision,
      sourceNode: entry.sourceNode,
      targetNode: entry.targetNode ?? null,
      targetSessionId: entry.targetSessionId ?? null,
      commandId: entry.commandId ?? null,
      paramsSummary:
        typeof entry.paramsSummary === "string" ? entry.paramsSummary : summarizeForAudit(entry.params ?? {}),
      result: normalizeResult(entry.result),
      error: entry.error ? String(entry.error) : null,
      autonomyMode: "high",
    };
    return store.appendAudit(record);
  }

  function list(filter = {}) {
    return store.listAudit(filter);
  }

  function autonomyState(profileId) {
    if (!isIdOf(profileId, "pa")) throw new Error("autonomyState requires a pa_ profile ID");
    const ack = store.getAck(profileId, AUTONOMY_NOTICE_VERSION);
    return {
      profileId,
      required: true,
      acknowledged: ack !== null,
      acknowledgedAt: ack?.acknowledgedAt ?? null,
      acknowledgedBy: ack?.acknowledgedBy ?? null,
      noticeVersion: AUTONOMY_NOTICE_VERSION,
      notice: AUTONOMY_NOTICE,
      modes: [...AUTONOMY_MODES],
      extraModeSwitch: PA_EXTRA_AUTONOMY_MODES.length > 0,
      pauseSwitch: PA_PAUSE_SWITCH,
      controls: describeAutonomyControls(),
    };
  }

  /**
   * 一次性确认：第一次写入确认记录；重复调用返回同一时间戳，不产生第二条记录。
   * 只有用户能确认；PA 不能替用户确认自己的自主权限。
   */
  function acknowledge({ profileId, sourceNode, by = "user", at = nowIso() } = {}) {
    if (!isIdOf(profileId, "pa")) throw new Error("acknowledge requires a pa_ profile ID");
    if (by !== "user") throw new Error("only the user can acknowledge the autonomy notice");
    if (!isIdOf(sourceNode, "dev")) throw new Error("acknowledge requires sourceNode (dev_ ID)");
    const existing = store.getAck(profileId, AUTONOMY_NOTICE_VERSION);
    if (existing) {
      return {
        acknowledged: true,
        firstTime: false,
        acknowledgedAt: existing.acknowledgedAt,
        acknowledgedBy: existing.acknowledgedBy,
        noticeVersion: AUTONOMY_NOTICE_VERSION,
      };
    }
    const inserted = store.insertAck({
      profileId,
      noticeVersion: AUTONOMY_NOTICE_VERSION,
      acknowledgedAt: at,
      acknowledgedBy: by,
    });
    append({
      actor: "user",
      action: "pa.autonomy.acknowledge",
      decision: "acknowledge",
      sourceNode,
      params: { noticeVersion: AUTONOMY_NOTICE_VERSION },
      result: { state: inserted ? "succeeded" : "duplicate", ok: true, duplicate: !inserted },
      at,
    });
    const record = store.getAck(profileId, AUTONOMY_NOTICE_VERSION);
    return {
      acknowledged: true,
      firstTime: inserted,
      acknowledgedAt: record?.acknowledgedAt ?? at,
      acknowledgedBy: record?.acknowledgedBy ?? by,
      noticeVersion: AUTONOMY_NOTICE_VERSION,
    };
  }

  return {
    append,
    list,
    autonomyState,
    acknowledge,
    summarizeForAudit,
    store,
  };
}

export const __auditInternals = { normalizeResult };
