/** AGT-107 / C-14：忙碌时 mode=queue 排队，当前回合结束后按序派发。 */
import test from "node:test";
import assert from "node:assert/strict";

import { METHODS, rpcRequest } from "../../contracts/rpc.mjs";
import { CAPABILITY_KEYS } from "../../contracts/capabilities.mjs";
import { BaseAdapter } from "../../adapters/base.mjs";
import { createCore } from "../../core/index.mjs";
import { cleanupDir, fixedClock, makeTempDir } from "../../fixtures/core/helpers.mjs";

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

class SlowAdapter extends BaseAdapter {
  constructor(clock) {
    super({ instanceId: "ai_slow", provider: "slow", version: "1.0.0", clock });
    this.sent = [];
    this.declareCapabilities(Object.fromEntries(CAPABILITY_KEYS.map((key) => [key, false])));
  }
  async probe() {
    return { installed: true, version: "1.0.0", loggedIn: null, path: null, details: {} };
  }
  async createSession() {
    return { providerThreadId: null };
  }
  async send({ sessionId, turnId, text }) {
    this.sent.push(text);
    this.emitEvent({ kind: "turn", sessionId, payload: { turnId, state: "running", text } });
    await sleep(80);
    this.emitEvent({ kind: "message", sessionId, payload: { role: "assistant", text: `reply:${text}`, delta: false } });
    this.emitEvent({ kind: "turn", sessionId, payload: { turnId, state: "completed", terminalConfidence: "confirmed" } });
    return { state: "completed", turnId };
  }
}

function command(kind, target, payload) {
  return rpcRequest(METHODS.command, { commandId: `cmd_${Math.random().toString(36).slice(2)}`, kind, target, payload });
}

async function waitFor(predicate, { timeoutMs = 3000, intervalMs = 20 } = {}) {
  const started = Date.now();
  for (;;) {
    if (await predicate()) return true;
    if (Date.now() - started > timeoutMs) return false;
    await sleep(intervalMs);
  }
}

test("忙碌时 mode=queue 返回 queued，当前回合结束后按序派发", async (t) => {
  const dir = makeTempDir("coda-queue-");
  const clock = fixedClock();
  const adapter = new SlowAdapter(clock);
  const core = createCore({ dataDir: dir, clock, lock: false, adapters: [adapter] });
  const events = [];
  core.subscribe((event) => events.push(event));
  t.after(async () => {
    await core.close();
    cleanupDir(dir);
  });

  const created = await core.handleRpc(command("session.create", {}, { title: "queue" }));
  const sessionId = created.result?.sessionId;
  assert.ok(sessionId, JSON.stringify(created));

  const first = core.handleRpc(command("turn.send", { sessionId }, { text: "first" }));
  await waitFor(() => adapter.sent.length === 1);

  const queued = await core.handleRpc(command("turn.send", { sessionId }, { text: "second", mode: "queue" }));
  assert.equal(queued.error, undefined, JSON.stringify(queued.error));
  const queuedResult = queued.result?.receipt?.result ?? queued.result?.result ?? {};
  assert.equal(queuedResult.queued, true);
  assert.equal(queuedResult.state, "queued");
  assert.ok(queuedResult.turnId);

  await first;
  const completed = await waitFor(
    () => events.filter((e) => e.kind === "turn" && e.payload?.state === "completed").length >= 2,
  );
  assert.equal(completed, true, "排队的 turn 没有被派发完成");
  assert.deepEqual(adapter.sent, ["first", "second"], "派发顺序不对");
});
