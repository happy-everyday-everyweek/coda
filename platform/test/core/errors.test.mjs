/** CORE-101：错误类型与事件总线（核心公共原语）。 */
import test from "node:test";
import assert from "node:assert/strict";

import { RPC_ERRORS } from "../../contracts/rpc.mjs";
import {
  ClosedError,
  ConflictError,
  CoreError,
  ExpiredError,
  NotFoundError,
  UnauthorizedError,
  UnknownResultError,
  UnsupportedCapabilityError,
  ValidationError,
  VersionMismatchError,
  asCoreError,
  isUnknownResult,
} from "../../core/errors.mjs";
import { createEventBus } from "../../core/eventbus.mjs";

test("错误码映射到契约 RPC 错误码", () => {
  assert.equal(new ValidationError("x").rpcCode, RPC_ERRORS.INVALID_PARAMS);
  assert.equal(new NotFoundError("x").rpcCode, RPC_ERRORS.NOT_FOUND);
  assert.equal(new ConflictError("x").rpcCode, RPC_ERRORS.CONFLICT);
  assert.equal(new ExpiredError("x").rpcCode, RPC_ERRORS.EXPIRED);
  assert.equal(new UnknownResultError("x").rpcCode, RPC_ERRORS.UNKNOWN_RESULT);
  assert.equal(new UnsupportedCapabilityError("x").rpcCode, RPC_ERRORS.UNSUPPORTED_CAPABILITY);
  assert.equal(new UnauthorizedError("x").rpcCode, RPC_ERRORS.UNAUTHORIZED);
  assert.equal(new VersionMismatchError("x").rpcCode, RPC_ERRORS.VERSION_MISMATCH);
  assert.equal(new ClosedError().rpcCode, RPC_ERRORS.INTERNAL);
  assert.throws(() => new CoreError("BOGUS", "x"), /unknown core error code/);

  const rpc = new ConflictError("busy", { id: 1 }).toRpcError();
  assert.equal(rpc.code, RPC_ERRORS.CONFLICT);
  assert.equal(rpc.data.coreCode, "CONFLICT");
  assert.equal(rpc.data.id, 1);
});

test("asCoreError 保留 CoreError，未知异常归类为 INTERNAL", () => {
  const original = new ConflictError("conflict");
  assert.equal(asCoreError(original), original);
  const wrapped = asCoreError(new Error("boom"));
  assert.equal(wrapped.code, "INTERNAL");
  assert.equal(wrapped.message, "boom");
  assert.equal(wrapped.cause instanceof Error, true);
  assert.equal(asCoreError("plain string").code, "INTERNAL");
  assert.equal(isUnknownResult(new UnknownResultError("?")), true);
  assert.equal(isUnknownResult(new Error("?")), false);
});

test("事件总线按订阅顺序扇出，订阅者异常被隔离", () => {
  const errors = [];
  const bus = createEventBus({ onError: (err, event) => errors.push({ message: err.message, seq: event.seq }) });
  const seenA = [];
  const seenB = [];
  const offA = bus.subscribe((event) => {
    seenA.push(event.seq);
    if (event.seq === 2) throw new Error("bad subscriber");
  });
  bus.subscribe((event) => seenB.push(event.seq));

  bus.publish({ seq: 1 });
  bus.publish({ seq: 2 });

  assert.deepEqual(seenA, [1, 2]);
  assert.deepEqual(seenB, [1, 2]);
  assert.equal(errors.length, 1);
  assert.equal(errors[0].seq, 2);
  assert.equal(bus.published, 2);
  assert.equal(bus.subscriberCount, 2);

  offA();
  bus.publish({ seq: 3 });
  assert.deepEqual(seenA, [1, 2]);
  assert.deepEqual(seenB, [1, 2, 3]);

  bus.close();
  assert.equal(bus.closed, true);
  assert.throws(() => bus.subscribe(() => {}), /closed/);
  assert.equal(bus.publish({ seq: 4 }), 0);
});
