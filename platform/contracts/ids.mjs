/** 全局 ID 与领域枚举：所有模块共用，禁止自造 ID 格式。 */
import { randomUUID } from "node:crypto";

/** ID 前缀 → 领域对象（SPEC 13.1）。 */
export const ID_PREFIXES = {
  dev: "Device",
  env: "ExecutionEnvironment",
  ad: "AgentDefinition",
  ai: "AgentInstance",
  ws: "Workspace",
  ses: "Session",
  run: "Run",
  turn: "Turn",
  evt: "Event",
  int: "InteractionRequest",
  cmd: "Command",
  art: "Artifact",
  pa: "PAProfile",
  pm: "PAMessage",
  cur: "SyncCursor",
};

/** 生成带领域前缀的 ID。 */
export function makeId(prefix, uuid = randomUUID()) {
  if (!Object.hasOwn(ID_PREFIXES, prefix)) {
    throw new Error(`unknown id prefix: ${prefix}`);
  }
  return `${prefix}_${uuid.replaceAll("-", "")}`;
}

/** 校验 ID 是否属于期望领域。 */
export function isIdOf(id, prefix) {
  return typeof id === "string" && id.startsWith(`${prefix}_`) && id.length > prefix.length + 1;
}

export const SESSION_STATES = ["idle", "running", "queued", "stopped", "failed", "unknown"];
export const TURN_STATES = ["queued", "running", "stopping", "completed", "failed", "unknown"];
export const RUN_STATES = ["queued", "running", "stopping", "completed", "failed", "unknown"];
export const INTERACTION_STATES = ["pending", "answered", "expired", "superseded", "cancelled"];

export function nowIso() {
  return new Date().toISOString();
}
