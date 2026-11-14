/** 契约层测试：CORE-101/CORE-102/AGT-101 的外部行为护栏。 */
import test from "node:test";
import assert from "node:assert/strict";

import { makeId, isIdOf, ID_PREFIXES, SESSION_STATES } from "../../contracts/ids.mjs";
import { RPC_ERRORS, METHODS, PROTOCOL_VERSION, checkProtocolVersion, welcome, rpcRequest, rpcError } from "../../contracts/rpc.mjs";
import { EVENT_KINDS, createEvent, validateEvent } from "../../contracts/events.mjs";
import { COMMAND_KINDS, COMMAND_STATES, createCommand, transition, isTerminal, isExpired } from "../../contracts/commands.mjs";
import { CAPABILITY_KEYS, makeCapabilityDeclaration, supports, supportState } from "../../contracts/capabilities.mjs";

test("ID 带领域前缀且可校验", () => {
  for (const prefix of Object.keys(ID_PREFIXES)) {
    const id = makeId(prefix);
    assert.ok(id.startsWith(`${prefix}_`));
    assert.ok(isIdOf(id, prefix));
    assert.equal(isIdOf(id, "ses"), prefix === "ses");
  }
  assert.throws(() => makeId("nope"), /unknown id prefix/);
});

test("协议版本协商拒绝越界版本", () => {
  assert.equal(PROTOCOL_VERSION, 1);
  assert.equal(checkProtocolVersion(1).ok, true);
  assert.equal(checkProtocolVersion(0).ok, false);
  assert.equal(checkProtocolVersion(99).ok, false);
  assert.equal(checkProtocolVersion("1").ok, false);
  const w = welcome({ clientType: "tui" });
  assert.equal(w.protocolVersion, PROTOCOL_VERSION);
  assert.equal(w.clientType, "tui");
});

test("RPC 错误码稳定", () => {
  assert.equal(RPC_ERRORS.VERSION_MISMATCH, -32001);
  assert.equal(RPC_ERRORS.UNKNOWN_RESULT, -32006);
  const req = rpcRequest(METHODS.command, { commandId: "x" });
  assert.equal(req.jsonrpc, "2.0");
  assert.equal(req.method, "core.command");
  const err = rpcError(req.id, RPC_ERRORS.NOT_FOUND, "no such session");
  assert.equal(err.error.code, RPC_ERRORS.NOT_FOUND);
});

test("事件类型受控且需要 sessionId 与 seq", () => {
  assert.deepEqual(EVENT_KINDS, ["message","reasoning","tool","plan","error","subagent","status","interaction","terminal","artifact","notification","session","turn","run"]);
  const e = createEvent({ kind: "message", sessionId: "ses_1", seq: 1, payload: { text: "hi" } });
  assert.deepEqual(validateEvent(e), []);
  assert.throws(() => createEvent({ kind: "message", seq: 1 }), /requires sessionId/);
  assert.throws(() => createEvent({ kind: "message", sessionId: "s", seq: 0 }), /positive integer seq/);
  assert.throws(() => createEvent({ kind: "bogus", sessionId: "s", seq: 1 }), /unknown event kind/);
});

test("命令状态机：accepted 必须先 dispatched，终态不可再变", () => {
  const c = createCommand({ kind: "turn.send", payload: { text: "hi" } });
  assert.equal(c.state, "accepted");
  assert.equal(isTerminal(c), false);
  assert.throws(() => transition(c, "succeeded"), /accepted can only/);
  transition(c, "dispatched");
  transition(c, "succeeded", { ok: true });
  assert.equal(c.state, "succeeded");
  assert.equal(isTerminal(c), true);
  assert.throws(() => transition(c, "failed"), /already terminal/);
  assert.ok(COMMAND_STATES.includes("unknown"));
  assert.ok(COMMAND_KINDS.includes("turn.send"));
});

test("命令可过期", () => {
  const c = createCommand({ kind: "turn.stop", ttlMs: -1 });
  assert.equal(isExpired(c), true);
});

test("能力声明必须覆盖全部键且 unknown 不算支持", () => {
  const capabilities = Object.fromEntries(CAPABILITY_KEYS.map((k) => [k, false]));
  const decl = makeCapabilityDeclaration({ provider: "mock", version: "1.0.0", capabilities });
  assert.deepEqual(Object.keys(decl.capabilities).sort(), [...CAPABILITY_KEYS].sort());
  assert.equal(supports(decl, "streaming"), false);
  assert.equal(supportState(decl, "streaming"), "unsupported");
  assert.throws(() => makeCapabilityDeclaration({ provider: "mock", version: "1", capabilities: {} }), /missing keys/);
  const streamDecl = makeCapabilityDeclaration({
    provider: "mock",
    version: "1",
    capabilities: { ...capabilities, streaming: true, approvals: "experimental" },
  });
  assert.equal(supports(streamDecl, "streaming"), true);
  assert.equal(supportState(streamDecl, "approvals"), "experimental");
});

test("会话状态枚举与 SPEC 语义一致", () => {
  assert.deepEqual(SESSION_STATES, ["idle", "running", "queued", "stopped", "failed", "unknown"]);
});
