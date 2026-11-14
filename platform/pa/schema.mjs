/**
 * PA 资料与单会话消息 schema（SPEC 10 / 13.1，PA-101）。
 *
 * 设计约束：
 * - PA 独立于 Coding Agent：这里没有 Session/Turn/AgentInstance 字段，也不引用适配器。
 * - 单用户、单会话：一个 PAProfile 对应一条连续会话；PAMessage 用 paId 归属，不另建会话表。
 * - 跨端：每条消息都有全局 ID、来源节点、混合逻辑时钟（HLC）和 replyTo。
 *
 * 确定性排序规则（v1，跨端一致）：
 *   1. HLC 物理毫秒（15 位十进制，左补零）
 *   2. HLC 计数器（6 位十进制，左补零）
 *   3. HLC 节点 ID（码点序，不是 localeCompare）
 *   4. 消息 ID（码点序）
 * 因为 HLC 节点必须等于 sourceNode（校验强制），上述顺序对同一消息集合是唯一的，
 * 不依赖输入顺序、本机时钟或操作系统 locale。
 */
import { makeId, isIdOf, nowIso } from "../contracts/ids.mjs";

export const PA_SCHEMA_VERSION = 1;

/** 单会话规则：同一 PAProfile 只有一条连续会话，消息只允许属于该 paId。 */
export const PA_SINGLE_SESSION = true;

export const PA_MESSAGE_ROLES = Object.freeze(["user", "assistant", "system"]);
export const PA_MESSAGE_ORIGINS = Object.freeze(["local", "sync", "migration"]);

/** 旧 Android CodaBotProfile 的默认值（app/src/main/java/com/coda/mobileui/personal）。 */
export const PA_DEFAULT_PROFILE = Object.freeze({
  name: "麦麦",
  avatarSkinId: "clawd",
  avatarUri: "",
  persona: "温和、真诚、有分寸，以自然的方式陪我聊天。",
  systemPrompt: "",
  userName: "你",
  userAbout: "",
  plannerProviderId: "",
  plannerModel: "",
  replyProviderId: "",
  replyModel: "",
  bubbleStyle: 0,
  petAnimation: true,
});

const SECRET_KEY_PATTERN = /(api[_-]?key|secret|token|password|credential|authorization|cookie)/i;
const HLC_PATTERN = /^(\d{15}):(\d{6}):([A-Za-z0-9_.-]+)$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 码点序比较；不使用 localeCompare，避免跨端/跨 locale 排序漂移。 */
export function compareStrings(a, b) {
  if (a === b) return 0;
  return a < b ? -1 : 1;
}

export function isUuid(value) {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

/** 事务性 ID 映射：旧 Android UUID 直接复用，非 UUID 用确定性哈希。 */
export function legacyIdToMessageId(legacyId, sourceNode) {
  if (typeof legacyId !== "string" || legacyId.length === 0) {
    throw new Error("legacy message id must be a non-empty string");
  }
  if (isUuid(legacyId)) return makeId("pm", legacyId);
  // makeId 只接受 UUID；非 UUID 的旧 ID（例如 "local-1"）用稳定哈希映射。
  return `pm_${createStableHash(`${sourceNode}:${legacyId}`)}`;
}

/** 32 位十六进制稳定哈希，仅用于 ID 映射，不作为安全摘要。 */
function createStableHash(text) {
  // 轻量 FNV-1a 变体 + 两轮混合，避免为了一个 ID 引入 node:crypto 的异步/平台差异。
  let h1 = 0x811c9dc5;
  let h2 = 0x1000193;
  for (let i = 0; i < text.length; i += 1) {
    const code = text.charCodeAt(i);
    h1 = Math.imul(h1 ^ code, 0x01000193) >>> 0;
    h2 = Math.imul(h2 + code + i, 0x85ebca6b) >>> 0;
  }
  return (h1.toString(16).padStart(8, "0") + h2.toString(16).padStart(8, "0")).padEnd(32, "0").slice(0, 32);
}

export function formatHlc({ physical, counter = 0, nodeId }) {
  if (!Number.isSafeInteger(physical) || physical < 0 || physical > 999_999_999_999_999) {
    throw new Error(`invalid HLC physical: ${physical}`);
  }
  if (!Number.isInteger(counter) || counter < 0 || counter > 999999) {
    throw new Error(`invalid HLC counter: ${counter}`);
  }
  if (typeof nodeId !== "string" || nodeId.length === 0 || nodeId.includes(":")) {
    throw new Error(`invalid HLC nodeId: ${String(nodeId)}`);
  }
  return `${String(physical).padStart(15, "0")}:${String(counter).padStart(6, "0")}:${nodeId}`;
}

export function parseHlc(hlc) {
  if (typeof hlc !== "string") throw new Error("hlc must be a string");
  const match = HLC_PATTERN.exec(hlc);
  if (!match) throw new Error(`invalid HLC: ${hlc}`);
  return { physical: Number(match[1]), counter: Number(match[2]), node: match[3] };
}

export function compareHlc(a, b) {
  const left = parseHlc(a);
  const right = parseHlc(b);
  if (left.physical !== right.physical) return left.physical < right.physical ? -1 : 1;
  if (left.counter !== right.counter) return left.counter < right.counter ? -1 : 1;
  return compareStrings(left.node, right.node);
}

export function tsForHlc(hlc) {
  return new Date(parseHlc(hlc).physical).toISOString();
}

/** 确定性消息全序（见文件头规则）。 */
export function comparePaMessages(a, b) {
  const byHlc = compareHlc(a.hlc, b.hlc);
  if (byHlc !== 0) return byHlc;
  const byNode = compareStrings(a.sourceNode, b.sourceNode);
  if (byNode !== 0) return byNode;
  return compareStrings(a.id, b.id);
}

/**
 * 混合逻辑时钟：单调、可观察远端 HLC，保证并发消息有确定顺序。
 * now 可注入，测试中不依赖真实时间。
 */
export function createHybridClock({ nodeId, now = () => Date.now() } = {}) {
  if (typeof nodeId !== "string" || nodeId.length === 0) {
    throw new Error("createHybridClock requires nodeId");
  }
  if (typeof now !== "function") throw new Error("createHybridClock requires now()");
  let lastPhysical = -1;
  let counter = 0;

  function tick() {
    const wall = Number(now());
    if (!Number.isFinite(wall)) throw new Error("clock.now() must return a finite millisecond value");
    if (wall > lastPhysical) {
      lastPhysical = wall;
      counter = 0;
    } else {
      counter += 1;
      if (counter > 999999) {
        lastPhysical += 1;
        counter = 0;
      }
    }
    return formatHlc({ physical: lastPhysical, counter, nodeId });
  }

  return {
    nodeId,
    now: tick,
    observe(remoteHlc) {
      const remote = parseHlc(remoteHlc);
      if (remote.physical > lastPhysical) {
        lastPhysical = remote.physical;
        counter = remote.counter;
      } else if (remote.physical === lastPhysical && remote.counter > counter) {
        counter = remote.counter;
      }
      return lastPhysical < 0 ? null : formatHlc({ physical: lastPhysical, counter, nodeId });
    },
    current() {
      return lastPhysical < 0 ? null : formatHlc({ physical: lastPhysical, counter, nodeId });
    },
  };
}

export function validatePaProfile(profile) {
  const errors = [];
  if (!profile || typeof profile !== "object" || Array.isArray(profile)) {
    return ["profile must be an object"];
  }
  if (!isIdOf(profile.id, "pa")) errors.push("id must be a pa_ prefixed ID");
  if (typeof profile.name !== "string" || profile.name.trim().length === 0) errors.push("name is required");
  if (typeof profile.userName !== "string" || profile.userName.trim().length === 0) errors.push("userName is required");
  for (const key of [
    "avatarSkinId",
    "avatarUri",
    "persona",
    "systemPrompt",
    "userAbout",
    "plannerProviderId",
    "plannerModel",
    "replyProviderId",
    "replyModel",
  ]) {
    if (typeof profile[key] !== "string") errors.push(`${key} must be a string`);
  }
  if (!Number.isInteger(profile.bubbleStyle) || profile.bubbleStyle < 0) errors.push("bubbleStyle must be a non-negative integer");
  if (typeof profile.petAnimation !== "boolean") errors.push("petAnimation must be a boolean");
  if (!Number.isInteger(profile.schemaVersion) || profile.schemaVersion < 1) errors.push("schemaVersion must be a positive integer");
  if (typeof profile.createdAt !== "string" || Number.isNaN(Date.parse(profile.createdAt))) errors.push("createdAt must be an ISO timestamp");
  if (typeof profile.updatedAt !== "string" || Number.isNaN(Date.parse(profile.updatedAt))) errors.push("updatedAt must be an ISO timestamp");
  for (const key of Object.keys(profile)) {
    if (SECRET_KEY_PATTERN.test(key)) {
      errors.push(`profile must not contain secrets (${key}); keep credentials in platform secure storage`);
    }
  }
  return errors;
}

export function createPaProfile(overrides = {}) {
  const now = overrides.createdAt ?? nowIso();
  const profile = {
    ...PA_DEFAULT_PROFILE,
    ...overrides,
    id: overrides.id ?? makeId("pa"),
    schemaVersion: PA_SCHEMA_VERSION,
    createdAt: now,
    updatedAt: overrides.updatedAt ?? now,
  };
  const errors = validatePaProfile(profile);
  if (errors.length) throw new Error(`invalid PA profile: ${errors.join("; ")}`);
  return profile;
}

function normalizeAttachment(attachment) {
  return {
    artifactId: attachment.artifactId,
    name: attachment.name,
    mimeType: attachment.mimeType ?? "application/octet-stream",
    size: attachment.size ?? 0,
  };
}

function normalizeAttachments(attachments) {
  if (!Array.isArray(attachments)) return attachments;
  return attachments.map((attachment) =>
    attachment && typeof attachment === "object" ? normalizeAttachment(attachment) : attachment,
  );
}

export function validatePaMessage(message) {
  const errors = [];
  if (!message || typeof message !== "object" || Array.isArray(message)) {
    return ["message must be an object"];
  }
  if (!isIdOf(message.id, "pm")) errors.push("id must be a pm_ prefixed ID");
  if (!isIdOf(message.paId, "pa")) errors.push("paId must be a pa_ prefixed ID");
  if (!isIdOf(message.sourceNode, "dev")) errors.push("sourceNode must be a dev_ prefixed ID");
  if (!PA_MESSAGE_ROLES.includes(message.role)) errors.push(`role must be one of ${PA_MESSAGE_ROLES.join(", ")}`);
  if (typeof message.content !== "string") errors.push("content must be a string");
  if (message.role === "user" && typeof message.content === "string" && message.content.trim().length === 0) {
    errors.push("user message content must not be empty");
  }
  if (!Array.isArray(message.attachments)) {
    errors.push("attachments must be an array");
  } else {
    message.attachments.forEach((attachment, index) => {
      if (!attachment || typeof attachment !== "object") {
        errors.push(`attachments[${index}] must be an object`);
        return;
      }
      if (!isIdOf(attachment.artifactId, "art")) errors.push(`attachments[${index}].artifactId must be an art_ ID`);
      if (typeof attachment.name !== "string" || attachment.name.length === 0) {
        errors.push(`attachments[${index}].name is required`);
      }
      if (typeof attachment.mimeType !== "string" || attachment.mimeType.length === 0) {
        errors.push(`attachments[${index}].mimeType is required`);
      }
      if (!Number.isInteger(attachment.size) || attachment.size < 0) {
        errors.push(`attachments[${index}].size must be a non-negative integer`);
      }
    });
  }
  if (message.replyTo !== null && message.replyTo !== undefined) {
    if (!isIdOf(message.replyTo, "pm")) errors.push("replyTo must be null or a pm_ ID");
    if (message.replyTo === message.id) errors.push("replyTo must not reference the message itself");
  }
  if (!Number.isInteger(message.seq) || message.seq < 1) errors.push("seq must be a positive integer");
  if (typeof message.ts !== "string" || Number.isNaN(Date.parse(message.ts))) errors.push("ts must be an ISO timestamp");
  if (typeof message.hlc !== "string") {
    errors.push("hlc is required");
  } else {
    try {
      const hlc = parseHlc(message.hlc);
      if (hlc.node !== message.sourceNode) errors.push("hlc node must equal sourceNode");
    } catch (error) {
      errors.push(error.message);
    }
  }
  if (message.origin !== undefined && !PA_MESSAGE_ORIGINS.includes(message.origin)) {
    errors.push(`origin must be one of ${PA_MESSAGE_ORIGINS.join(", ")}`);
  }
  if (!Number.isInteger(message.schemaVersion) || message.schemaVersion < 1) {
    errors.push("schemaVersion must be a positive integer");
  }
  return errors;
}

export function createPaMessage({
  id,
  paId,
  sourceNode,
  role,
  content,
  attachments = [],
  replyTo = null,
  seq,
  ts,
  hlc,
  origin = "local",
} = {}) {
  const message = {
    schemaVersion: PA_SCHEMA_VERSION,
    id: id ?? makeId("pm"),
    paId,
    sourceNode,
    role,
    content,
    attachments: normalizeAttachments(attachments),
    replyTo: replyTo ?? null,
    seq,
    ts: ts ?? (typeof hlc === "string" ? tsForHlc(hlc) : nowIso()),
    hlc,
    origin,
  };
  const errors = validatePaMessage(message);
  if (errors.length) throw new Error(`invalid PA message: ${errors.join("; ")}`);
  return message;
}

/** 单会话不变量：一段消息序列只能属于同一个 PA。 */
export function assertSinglePaSession(paId, messages) {
  if (!isIdOf(paId, "pa")) throw new Error("assertSinglePaSession requires a pa_ profile ID");
  if (!Array.isArray(messages)) throw new Error("messages must be an array");
  const foreign = messages.filter((message) => message?.paId !== paId);
  if (foreign.length > 0) {
    throw new Error(`PA single-session rule violated: ${foreign.length} message(s) belong to another PA`);
  }
  return true;
}
