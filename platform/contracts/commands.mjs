/** 命令信封、状态机与幂等规则（SPEC 11.5 / 13.3）。 */
import { makeId, nowIso } from "./ids.mjs";

export const COMMAND_STATES = ["accepted", "dispatched", "succeeded", "failed", "expired", "superseded", "unknown"];
const TERMINAL = new Set(["succeeded", "failed", "expired", "superseded", "unknown"]);

export const COMMAND_KINDS = [
  "session.create",
  "session.resume",
  "session.fork",
  "turn.send",
  "turn.steer",
  "turn.stop",
  "interaction.answer",
  "model.set",
  "workspace.create",
  "terminal.open",
  "terminal.resize",
  "terminal.close",
  "artifact.put",
  "pa.send",
  "device.pair",
];

export function createCommand({ kind, source = "ui", target = {}, payload = {}, ttlMs = 60_000, supersedes = null }) {
  if (!COMMAND_KINDS.includes(kind)) throw new Error(`unknown command kind: ${kind}`);
  const createdAt = nowIso();
  return {
    id: makeId("cmd"),
    kind,
    source,
    target,
    payload,
    supersedes,
    state: "accepted",
    createdAt,
    expiresAt: new Date(Date.now() + ttlMs).toISOString(),
    receipt: null,
    result: null,
  };
}

export function transition(command, next, result = null) {
  if (!COMMAND_STATES.includes(next)) throw new Error(`unknown state ${next}`);
  if (TERMINAL.has(command.state)) throw new Error(`command already terminal: ${command.state}`);
  if (command.state === "accepted" && next !== "dispatched") throw new Error(`accepted can only → dispatched`);
  command.state = next;
  if (result !== null) command.result = result;
  command.receipt = { state: next, at: nowIso(), result };
  return command;
}

export function isTerminal(cmd) {
  return TERMINAL.has(cmd.state);
}

export function isExpired(cmd, at = Date.now()) {
  return Date.parse(cmd.expiresAt) < at;
}
