/** AGT-101：适配器基类的统一事件、能力声明与错误模型。 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  AdapterError,
  BaseAdapter,
  CAPABILITY_VALUES,
  classifyUpstreamError,
  isValidCapabilityValue,
  rpcCodeFor,
  sideEffectFreeProbe,
} from "../../adapters/base.mjs";
import { MockAdapter } from "../../adapters/mock.mjs";
import { validateEvent } from "../../contracts/events.mjs";
import { CAPABILITY_KEYS } from "../../contracts/capabilities.mjs";
import { RPC_ERRORS } from "../../contracts/rpc.mjs";

test("统一事件：全部通过 contracts/events.mjs 校验且 seq 递增", async () => {
  const adapter = new MockAdapter({ instanceId: "mock_base", script: { chunks: ["a", "b"] } });
  await adapter.probe();
  const events = [];
  adapter.onEvent((event) => events.push(event));
  await adapter.createSession({ sessionId: "ses_base" });
  await adapter.send({ sessionId: "ses_base", text: "hi" });

  assert.ok(events.length >= 4, "expect session/turn/message events");
  for (const event of events) assert.deepEqual(validateEvent(event), []);
  const seqs = events.map((e) => e.seq);
  for (let i = 1; i < seqs.length; i += 1) assert.ok(seqs[i] > seqs[i - 1], `seq must increase: ${seqs}`);
  assert.ok(events.some((e) => e.kind === "session"), "session event is core-scoped (no sessionId)");
  assert.ok(events.filter((e) => e.kind === "message").every((e) => e.sessionId === "ses_base"));
  await adapter.dispose();
});

test("自造事件被拒绝：未知 kind 不能绕过统一事件构造", async () => {
  const adapter = new MockAdapter({ instanceId: "mock_bad_event" });
  await adapter.probe();
  assert.throws(() => adapter.emitEvent({ kind: "raw_upstream", sessionId: "ses_x" }), /unknown event kind/);
  assert.throws(() => adapter.emitEvent({ kind: "message" }), /requires sessionId/);
  await adapter.dispose();
});

test("capabilities() 未 probe 前抛 NOT_PROBED；声明必须覆盖全部键", async () => {
  const adapter = new MockAdapter({ instanceId: "mock_caps" });
  assert.throws(() => adapter.capabilities(), (err) => err instanceof AdapterError && err.code === "NOT_PROBED");
  assert.throws(() => adapter.declareCapabilities({}), /missing keys/);

  const partial = Object.fromEntries(CAPABILITY_KEYS.map((k) => [k, false]));
  delete partial.tools;
  assert.throws(() => adapter.declareCapabilities(partial), /missing keys: tools/);
  await adapter.dispose();
});

test("能力值域只允许 supported/experimental/unsupported/unverified", () => {
  for (const value of CAPABILITY_VALUES) assert.equal(isValidCapabilityValue(value), true);
  assert.equal(isValidCapabilityValue("maybe"), false);
  assert.equal(isValidCapabilityValue(1), false);
  assert.equal(isValidCapabilityValue({ a: true, b: "experimental" }), true);
  assert.equal(isValidCapabilityValue({ a: "nope" }), false);
});

test("断言能力：不支持的能力抛 UNSUPPORTED_CAPABILITY 而不是假成功", async () => {
  const adapter = new MockAdapter({ instanceId: "mock_assert" });
  await adapter.probe();
  // 对象型能力必须按子项判断（contracts/capabilities.mjs supports 的语义）
  assert.equal(adapter.assertCapability("streaming", "text"), true);
  assert.equal(adapter.assertCapability("tools"), true);
  assert.throws(() => adapter.assertCapability("plan"), (err) => err.code === "UNSUPPORTED_CAPABILITY");
  assert.throws(() => adapter.assertCapability("subagent"), (err) => err.code === "UNSUPPORTED_CAPABILITY");
  await adapter.dispose();
});

test("错误分类覆盖认证/配额/网络/进程退出，并映射到稳定 RPC 码", () => {
  assert.equal(classifyUpstreamError("invalid api key"), "NOT_AUTHENTICATED");
  assert.equal(classifyUpstreamError("rate limit exceeded"), "QUOTA_EXCEEDED");
  assert.equal(classifyUpstreamError("ECONNRESET while reading"), "NETWORK_ERROR");
  assert.equal(classifyUpstreamError("command not found: claude"), "NOT_INSTALLED");
  assert.equal(classifyUpstreamError("something odd"), "UNKNOWN");
  assert.equal(rpcCodeFor("UNSUPPORTED_CAPABILITY"), RPC_ERRORS.UNSUPPORTED_CAPABILITY);
  assert.equal(rpcCodeFor("SESSION_NOT_FOUND"), RPC_ERRORS.NOT_FOUND);
  assert.equal(rpcCodeFor("TURN_IN_PROGRESS"), RPC_ERRORS.CONFLICT);
});

test("events() 推送式迭代器：先订阅后发事件，dispose 后结束", async () => {
  const adapter = new MockAdapter({ instanceId: "mock_iter" });
  await adapter.probe();
  await adapter.createSession({ sessionId: "ses_iter" });

  const iterator = adapter.events()[Symbol.asyncIterator]();
  const pending = iterator.next();
  // 让 async generator 真正开始执行并注册监听（避免“先发事件后订阅”的竞态）
  await new Promise((resolve) => setTimeout(resolve, 10));
  adapter.emitEvent({ kind: "status", sessionId: "ses_iter", payload: { state: "idle" } });
  const first = await pending;
  assert.equal(first.done, false);
  assert.equal(first.value.kind, "status");

  await adapter.dispose();
  const finished = await iterator.next();
  assert.equal(finished.done, true);
});

test("waitForEvent 命中即返回，超时抛 TIMEOUT", async () => {
  const adapter = new MockAdapter({ instanceId: "mock_wait" });
  await adapter.probe();
  await adapter.createSession({ sessionId: "ses_wait" });
  setTimeout(() => adapter.emitEvent({ kind: "status", sessionId: "ses_wait", payload: { state: "running" } }), 10);
  const event = await adapter.waitForEvent((e) => e.kind === "status", 1000);
  assert.equal(event.payload.state, "running");
  await assert.rejects(adapter.waitForEvent(() => false, 30), (err) => err.code === "TIMEOUT");
  await adapter.dispose();
});

test("dispose 后入口一律 DISPOSED，不返回假结果", async () => {
  const adapter = new MockAdapter({ instanceId: "mock_disposed" });
  await adapter.probe();
  const session = await adapter.createSession({ sessionId: "ses_disposed" });
  assert.equal(session.sessionId, "ses_disposed");
  await adapter.dispose();
  await assert.rejects(adapter.send({ sessionId: "ses_disposed", text: "x" }), (err) => err.code === "DISPOSED");
  await assert.rejects(adapter.createSession({}), (err) => err.code === "DISPOSED");
});

test("probe 结果标记无副作用（ADP-001）", () => {
  const result = sideEffectFreeProbe({ installed: true, version: "1.2.3", path: "/usr/bin/x" });
  assert.equal(result.installed, true);
  assert.equal(result.version, "1.2.3");
  assert.equal(result.sideEffectFree, true);
  assert.equal(result.loggedIn, null);
});
