/**
 * 跨端确定性合并（SPEC 10.4 / PA-102，验收 PA-002 / PA-009 / SYNC-009）。
 *
 * 合并规则（v1）：
 * 1. 消息 ID 是去重键。完全相同的副本只保留一份；同一 ID 内容分叉时按规范化 JSON
 *    的码点序取较小者，并在 idConflicts 中记录，保证任意输入顺序得到同一结果。
 * 2. 消息集合是「并集」：不同 sourceNode 的并发消息全部保留，不按到达顺序覆盖。
 * 3. 同 sourceNode + 同 seq + 不同 ID 视为序号冲突（例如节点从旧备份恢复后重放）。
 *    不丢弃任何消息（避免静默丢数据），只报告冲突，最终顺序仍由 HLC + ID 唯一确定。
 * 4. replyTo 只做关系解析：目标存在则标记 resolved，不存在则标记 orphan，消息本身保留。
 * 5. 幂等：merge(merged, incoming) 的结果与 merge(merged) 相同；重复输入 added 为 0。
 *
 * 排序规则见 schema.mjs 文件头；本模块不读数据库、不写数据库、不调用适配器。
 */
import { comparePaMessages, validatePaMessage } from "./schema.mjs";

/** 稳定 JSON：对象键按码点序输出，用于分叉判定与幂等比较。 */
export function canonicalJson(value) {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map((item) => canonicalJson(item)).join(",")}]`;
  const keys = Object.keys(value).sort();
  return `{${keys.map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
}

/** 按 v1 全序排序，返回新数组，不修改输入。 */
export function sortPaMessages(messages) {
  return [...messages].sort(comparePaMessages);
}

function rejectInvalid(candidates, rejected) {
  const valid = [];
  for (const message of candidates) {
    const errors = validatePaMessage(message);
    if (errors.length > 0) {
      rejected.push({ id: message?.id ?? null, errors });
    } else {
      valid.push(message);
    }
  }
  return valid;
}

function findSeqConflicts(messages) {
  const groups = new Map();
  for (const message of messages) {
    const key = `${message.sourceNode}\u0000${message.seq}`;
    const existing = groups.get(key);
    if (existing) existing.push(message);
    else groups.set(key, [message]);
  }
  const conflicts = [];
  for (const group of groups.values()) {
    const ids = [...new Set(group.map((message) => message.id))];
    if (ids.length > 1) {
      conflicts.push({
        sourceNode: group[0].sourceNode,
        seq: group[0].seq,
        ids: ids.sort(),
        // 不丢消息：冲突组内的每一条都保留，顺序由全序规则决定。
        kept: ids.slice().sort(),
      });
    }
  }
  return conflicts;
}

/**
 * 报告每个来源节点的 seq 缺口（例如 1,2,4 缺 3）。
 * 缺口只用于诊断/游标，不影响合并顺序。
 */
export function findSeqGaps(messages, { limit = 100 } = {}) {
  const byNode = new Map();
  for (const message of messages) {
    if (!byNode.has(message.sourceNode)) byNode.set(message.sourceNode, new Set());
    byNode.get(message.sourceNode).add(message.seq);
  }
  const gaps = [];
  for (const [sourceNode, seqs] of [...byNode.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1))) {
    const sorted = [...seqs].sort((a, b) => a - b);
    const min = sorted[0];
    const max = sorted[sorted.length - 1];
    if (max - min + 1 === sorted.length) continue;
    const missing = [];
    let count = 0;
    for (let seq = min; seq <= max; seq += 1) {
      if (seqs.has(seq)) continue;
      count += 1;
      if (missing.length < limit) missing.push(seq);
    }
    gaps.push({ sourceNode, from: min, to: max, count, missing, truncated: count > missing.length });
  }
  return gaps;
}

function decorateView(messages, byId) {
  return messages.map((message) => {
    const replyTo = message.replyTo ?? null;
    return {
      ...message,
      replyToResolved: replyTo === null ? null : byId.has(replyTo),
      replyToMissing: replyTo !== null && !byId.has(replyTo),
    };
  });
}

/**
 * 合并本地消息与远端消息。
 *
 * @param {{local?: object[], incoming?: object[]}} input
 * @returns {{
 *   ok: boolean,
 *   messages: object[],        // 原始消息的确定性排序
 *   view: object[],            // 追加 replyToResolved/replyToMissing 的只读视图
 *   byId: Map<string, object>,
 *   added: number,             // incoming 中本地没有的消息数
 *   duplicates: number,        // incoming 中已存在/本批重复的条目数
 *   idConflicts: object[],     // 同一 ID 内容分叉
 *   seqConflicts: object[],    // 同节点同 seq 不同 ID
 *   orphans: string[],         // replyTo 指向不存在消息的 ID
 *   gaps: object[],            // 各来源节点 seq 缺口
 *   rejected: object[],        // 校验失败被拒收的条目
 *   nextCursor: string|null    // 当前最大 HLC，可作同步游标
 * }}
 */
export function mergePaMessages({ local = [], incoming = [] } = {}) {
  if (!Array.isArray(local)) throw new Error("local must be an array");
  if (!Array.isArray(incoming)) throw new Error("incoming must be an array");

  const rejected = [];
  const localValid = rejectInvalid(local, rejected);
  const incomingValid = rejectInvalid(incoming, rejected);

  const localIds = new Set(localValid.map((message) => message.id));
  const byId = new Map();
  const idConflicts = [];

  const all = [...localValid, ...incomingValid];
  for (const message of all) {
    const existing = byId.get(message.id);
    if (!existing) {
      byId.set(message.id, message);
      continue;
    }
    const existingJson = canonicalJson(existing);
    const nextJson = canonicalJson(message);
    if (existingJson === nextJson) {
      continue;
    }
    // 同一 ID 内容分叉：按规范化 JSON 码点序取较小者，保证与输入顺序无关。
    const winner = existingJson < nextJson ? existing : message;
    byId.set(message.id, winner);
    idConflicts.push({
      id: message.id,
      kept: winner.id,
      variants: 2,
      keptCanonical: canonicalJson(winner),
    });
  }

  const messages = sortPaMessages([...byId.values()]);
  const view = decorateView(messages, byId);
  const seqConflicts = findSeqConflicts(messages);
  const orphans = view.filter((message) => message.replyToMissing).map((message) => message.id);
  const addedIds = [];
  for (const message of incomingValid) {
    if (!localIds.has(message.id) && !addedIds.includes(message.id)) addedIds.push(message.id);
  }
  // 每个不能算作「新增」的 incoming 条目都计为重复（本地已有、本批重复或分叉胜者已存在）。
  const duplicates = incomingValid.length - addedIds.length;

  return {
    ok: rejected.length === 0,
    messages,
    view,
    byId,
    added: addedIds.length,
    duplicates,
    idConflicts,
    seqConflicts,
    orphans,
    gaps: findSeqGaps(messages),
    rejected,
    nextCursor: messages.length > 0 ? messages[messages.length - 1].hlc : null,
  };
}

/** 只解析 replyTo 关系，供 UI/同步层复用。 */
export function resolveReplyRelations(messages) {
  const ids = new Set(messages.map((message) => message.id));
  return messages.map((message) => ({
    id: message.id,
    replyTo: message.replyTo ?? null,
    resolved: message.replyTo === null || message.replyTo === undefined ? null : ids.has(message.replyTo),
  }));
}

/** 同步游标：返回消息集合中最大的 HLC；空集合返回 null。 */
export function cursorAt(messages) {
  if (!Array.isArray(messages) || messages.length === 0) return null;
  return sortPaMessages(messages)[messages.length - 1].hlc;
}
