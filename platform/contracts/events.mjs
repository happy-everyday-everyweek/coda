/** 统一事件类型（SPEC 8/13）：适配器只允许发出这些事件。 */
import { nowIso } from "./ids.mjs";

export const EVENT_KINDS = [
  "message",
  "reasoning",
  "tool",
  "plan",
  "error",
  "subagent",
  "status",
  "interaction",
  "terminal",
  "artifact",
  "notification",
  "session",
  "turn",
  "run",
];

const CORE_SCOPED = new Set(["notification", "session"]);

export function createEvent({ kind, sessionId = null, payload = {}, seq, ts = nowIso() }) {
  if (!EVENT_KINDS.includes(kind)) throw new Error(`unknown event kind: ${kind}`);
  if (!CORE_SCOPED.has(kind) && !sessionId) throw new Error(`event ${kind} requires sessionId`);
  if (!Number.isInteger(seq) || seq < 1) throw new Error("event requires positive integer seq");
  return { kind, sessionId, payload, seq, ts };
}

export function validateEvent(e) {
  const errors = [];
  if (!e || typeof e !== "object") return ["event must be object"];
  if (!EVENT_KINDS.includes(e.kind)) errors.push("invalid kind");
  if (!CORE_SCOPED.has(e.kind) && !e.sessionId) errors.push("missing sessionId");
  if (!Number.isInteger(e.seq) || e.seq < 1) errors.push("invalid seq");
  return errors;
}

/** 流式事件合并：连续 message 增量合并成一条展示消息，不改变持久化事件。 */
export function isDeltaEvent(e) {
  return e?.kind === "message" && e.payload?.delta === true;
}
