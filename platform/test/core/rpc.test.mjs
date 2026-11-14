/** CORE-103：hello/welcome、订阅快照、断点续传、命令幂等。 */
import test from "node:test";
import assert from "node:assert/strict";

import { makeId } from "../../contracts/ids.mjs";
import { METHODS, PROTOCOL_VERSION, RPC_ERRORS, rpcRequest } from "../../contracts/rpc.mjs";
import { createCore, runStdioCore } from "../../core/index.mjs";
import { cleanupDir, fixedClock, makeTempDir, waitFor } from "../../fixtures/core/helpers.mjs";

function newCore() {
  const dir = makeTempDir("coda-rpc-");
  const core = createCore({ dataDir: dir, clock: fixedClock(), lock: false });
  return { dir, core };
}

test("hello → welcome，版本不匹配返回 VERSION_MISMATCH", async (t) => {
  const { dir, core } = newCore();
  t.after(async () => {
    await core.close();
    cleanupDir(dir);
  });

  const ok = await core.handleRpc(rpcRequest(METHODS.hello, { protocolVersion: 1, clientType: "tui" }));
  assert.equal(ok.error, undefined);
  assert.equal(ok.result.protocolVersion, PROTOCOL_VERSION);
  assert.equal(ok.result.clientType, "tui");
  assert.ok(typeof ok.result.coreVersion === "string");

  const tooNew = await core.handleRpc(rpcRequest(METHODS.hello, { protocolVersion: 99 }));
  assert.equal(tooNew.error.code, RPC_ERRORS.VERSION_MISMATCH);
  const tooOld = await core.handleRpc(rpcRequest(METHODS.hello, { protocolVersion: 0 }));
  assert.equal(tooOld.error.code, RPC_ERRORS.VERSION_MISMATCH);
  const notInteger = await core.handleRpc(rpcRequest(METHODS.hello, { protocolVersion: "1" }));
  assert.equal(notInteger.error.code, RPC_ERRORS.VERSION_MISMATCH);
});

test("subscribe 返回 snapshot{seq}，事件推送 seq 严格 +1", async (t) => {
  const { dir, core } = newCore();
  t.after(async () => {
    await core.close();
    cleanupDir(dir);
  });

  const events = [];
  const off = core.subscribe((event) => events.push(event));
  const subscribed = await core.handleRpc(rpcRequest(METHODS.subscribe, {}));
  assert.equal(subscribed.result.type, "snapshot");
  assert.equal(subscribed.result.seq, 0);
  assert.deepEqual(subscribed.result.snapshot.sessions, []);

  const created = await core.handleRpc(
    rpcRequest(METHODS.command, { kind: "session.create", payload: { title: "rpc", path: dir } }),
  );
  assert.equal(created.error, undefined, JSON.stringify(created.error));
  const sessionId = created.result.result.sessionId;
  assert.ok(sessionId.startsWith("ses_"));
  assert.ok(events.length >= 2, `expected pushed events, got ${events.length}`);
  for (let i = 1; i < events.length; i += 1) {
    assert.equal(events[i].seq, events[i - 1].seq + 1);
  }

  const later = await core.handleRpc(rpcRequest(METHODS.subscribe, {}));
  assert.equal(later.result.seq, events.at(-1).seq);
  assert.equal(later.result.snapshot.sessions.length, 1);
  off();
});

test("resume：连续回放事件，客户端超前则返回 snapshot", async (t) => {
  const { dir, core } = newCore();
  t.after(async () => {
    await core.close();
    cleanupDir(dir);
  });

  await core.handleRpc(rpcRequest(METHODS.command, { kind: "session.create", payload: { path: dir } }));
  const latest = core.services.journal.latestSeq();
  assert.ok(latest > 0);

  const upToDate = await core.handleRpc(rpcRequest(METHODS.resume, { fromSeq: latest }));
  assert.equal(upToDate.result.type, "events");
  assert.deepEqual(upToDate.result.events, []);
  assert.equal(upToDate.result.nextSeq, latest);

  const fromZero = await core.handleRpc(rpcRequest(METHODS.resume, { fromSeq: 0 }));
  assert.equal(fromZero.result.type, "events");
  assert.equal(fromZero.result.events.length, latest);
  assert.deepEqual(
    fromZero.result.events.map((e) => e.seq),
    Array.from({ length: latest }, (_, i) => i + 1),
  );

  const ahead = await core.handleRpc(rpcRequest(METHODS.resume, { fromSeq: latest + 5 }));
  assert.equal(ahead.result.type, "snapshot");
  assert.equal(ahead.result.reason, "client-ahead");
  assert.equal(ahead.result.seq, latest);

  const invalid = await core.handleRpc(rpcRequest(METHODS.resume, { fromSeq: -1 }));
  assert.equal(invalid.error.code, RPC_ERRORS.INVALID_PARAMS);
});

test("同一 commandId 重投只执行一次并返回原收据", async (t) => {
  const { dir, core } = newCore();
  t.after(async () => {
    await core.close();
    cleanupDir(dir);
  });

  const commandId = makeId("cmd");
  const params = { commandId, kind: "workspace.create", payload: { path: dir } };
  const first = await core.handleRpc(rpcRequest(METHODS.command, params));
  assert.equal(first.error, undefined, JSON.stringify(first.error));
  assert.equal(first.result.state, "succeeded");
  assert.equal(first.result.deduped, false);
  const workspaceId = first.result.result.workspaceId;

  const second = await core.handleRpc(rpcRequest(METHODS.command, params));
  assert.equal(second.error, undefined);
  assert.equal(second.result.deduped, true);
  assert.equal(second.result.commandId, commandId);
  assert.equal(second.result.receipt.state, "succeeded");
  assert.equal(second.result.result.workspaceId, workspaceId);
  assert.equal(core.services.journal.listObjects("workspace").length, 1);
});

test("未知方法与非法参数返回契约错误码", async (t) => {
  const { dir, core } = newCore();
  t.after(async () => {
    await core.close();
    cleanupDir(dir);
  });

  const unknown = await core.handleRpc(rpcRequest("core.nope", {}));
  assert.equal(unknown.error.code, RPC_ERRORS.METHOD_NOT_FOUND);

  const badKind = await core.handleRpc(rpcRequest(METHODS.command, { kind: "not.a.command" }));
  assert.equal(badKind.error.code, RPC_ERRORS.INVALID_PARAMS);

  const missingKind = await core.handleRpc(rpcRequest(METHODS.command, {}));
  assert.equal(missingKind.error.code, RPC_ERRORS.INVALID_PARAMS);

  const notJsonRpc = await core.handleRpc({ id: 1, method: METHODS.hello });
  assert.equal(notJsonRpc.error.code, RPC_ERRORS.INVALID_REQUEST);
});

test("diagnostics 与 capabilityMatrix 反映核心内部状态", async (t) => {
  const { dir, core } = newCore();
  t.after(async () => {
    await core.close();
    cleanupDir(dir);
  });

  const diag = await core.handleRpc(rpcRequest(METHODS.diagnostics, {}));
  assert.equal(diag.result.schemaVersion, 1);
  assert.equal(typeof diag.result.counts.events, "number");
  assert.equal(typeof diag.result.commands.accepted, "number");
  assert.ok(diag.result.adapters.length >= 1);

  const matrix = await core.handleRpc(rpcRequest(METHODS.capabilityMatrix, {}));
  assert.equal(matrix.result.matrix.length, 1);
  assert.ok(matrix.result.matrix[0].declaration.capabilities.streaming);
});

test("subscribe 之后 close：订阅失败且 RPC 明确拒绝", async () => {
  const dir = makeTempDir("coda-rpc-");
  const core = createCore({ dataDir: dir, clock: fixedClock(), lock: false });
  await core.close();
  try {
    assert.throws(() => core.subscribe(() => {}), /closed/);
    const response = await core.handleRpc(rpcRequest(METHODS.hello, {}));
    assert.equal(response.error.code, RPC_ERRORS.INTERNAL);
    assert.equal(core.state.closed, true);
  } finally {
    cleanupDir(dir);
  }
});

test("stdio 入口逐行输出响应，并把事件包装成 core.event 通知", async () => {
  const dir = makeTempDir("coda-stdio-");
  const { PassThrough } = await import("node:stream");
  const input = new PassThrough();
  const output = new PassThrough();
  const errorOutput = new PassThrough();
  let buffer = "";
  output.on("data", (chunk) => {
    buffer += String(chunk);
  });
  const core = await runStdioCore({ input, output, errorOutput, dataDir: dir, argv: [] });
  try {
    input.write(`${JSON.stringify(rpcRequest(METHODS.hello, { protocolVersion: 1, clientType: "stdio" }))}\n`);
    input.write(`${JSON.stringify(rpcRequest(METHODS.command, { kind: "session.create", payload: { path: dir } }))}\n`);

    const parseLines = () =>
      buffer
        .split("\n")
        .filter(Boolean)
        .flatMap((line) => {
          try {
            return [JSON.parse(line)];
          } catch {
            return [];
          }
        });

    await waitFor(() => parseLines().some((line) => line.method === "core.event"), {
      timeoutMs: 5_000,
      label: "core.event notification",
    });
    await waitFor(() => parseLines().some((line) => line.result?.protocolVersion === 1), {
      timeoutMs: 5_000,
      label: "welcome response",
    });

    const lines = parseLines();
    const welcomeLine = lines.find((line) => line.result?.protocolVersion === 1);
    assert.equal(welcomeLine.result.clientType, "stdio");
    const eventLine = lines.find((line) => line.method === "core.event");
    assert.equal(eventLine.jsonrpc, "2.0");
    assert.ok(Number.isInteger(eventLine.params.event.seq));
    assert.ok(typeof eventLine.params.event.kind === "string");
  } finally {
    input.end();
    await core.close();
    cleanupDir(dir);
  }
});
