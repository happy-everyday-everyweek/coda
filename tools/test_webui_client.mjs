// webui/client.mjs 的单元测试：node --test tools/test_webui_client.mjs
// 覆盖 #682 截断后签名失效、#683 增量渲染与滚动、#684 单飞与乱序、#685 发送态、#686 指示灯。
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  snapshotSig,
  planRender,
  createPoller,
  createSender,
  dotOn,
  nextDelay,
} from '../webui/client.mjs';

const msg = (id, text, ts = 1) => ({ id, role: 'assistant', text, ts });

// ---------------------------------------------------------------- #682 签名
test('sig changes when a truncated 200-message window slides', () => {
  const old = Array.from({ length: 200 }, (_, i) => msg('m' + i, 't' + i));
  const slid = [...old.slice(1), msg('m200', 't200')];
  assert.equal(old.length, slid.length);
  assert.notEqual(snapshotSig(old), snapshotSig(slid));
});

test('sig changes when last message text is edited in place', () => {
  const a = [msg('a', 'hello')];
  const b = [msg('a', 'hello world')];
  assert.notEqual(snapshotSig(a), snapshotSig(b));
});

test('sig stable for identical snapshot', () => {
  const a = [msg('a', 'x'), msg('b', 'y')];
  assert.equal(snapshotSig(a), snapshotSig([...a]));
});

test('sig handles empty list', () => {
  assert.equal(typeof snapshotSig([]), 'string');
  assert.notEqual(snapshotSig([]), snapshotSig([msg('a', '')]));
});

// ---------------------------------------------------------------- #683 渲染计划
test('append plan keeps existing nodes when list only grows at tail', () => {
  const prev = [msg('a', 'one'), msg('b', 'two')];
  const next = [...prev, msg('c', 'three')];
  const plan = planRender(prev, next);
  assert.equal(plan.rebuild, false);
  assert.deepEqual(plan.append.map((m) => m.id), ['c']);
  assert.deepEqual(plan.update, []);
});

test('in-place text update yields update, not rebuild', () => {
  const prev = [msg('a', 'one'), msg('b', 'two')];
  const next = [msg('a', 'one'), msg('b', 'two more')];
  const plan = planRender(prev, next);
  assert.equal(plan.rebuild, false);
  assert.deepEqual(plan.append, []);
  assert.deepEqual(plan.update.map((m) => m.id), ['b']);
});

test('sliding window forces rebuild instead of silently dropping head', () => {
  const prev = [msg('a', '1'), msg('b', '2')];
  const next = [msg('b', '2'), msg('c', '3')];
  assert.equal(planRender(prev, next).rebuild, true);
});

test('shrink forces rebuild', () => {
  assert.equal(planRender([msg('a', '1'), msg('b', '2')], [msg('a', '1')]).rebuild, true);
});

test('empty next clears', () => {
  const plan = planRender([msg('a', '1')], []);
  assert.equal(plan.rebuild, true);
  assert.deepEqual(plan.append, []);
});

// ---------------------------------------------------------------- #684 单飞串行
test('poller drops overlapping call while one is in flight', async () => {
  let resolveFirst;
  const calls = [];
  const fetchState = () => {
    calls.push(1);
    if (calls.length === 1) return new Promise((r) => { resolveFirst = r; });
    return Promise.resolve({ ok: true, json: async () => ({ messages: [] }) });
  };
  const seen = [];
  const poller = createPoller({ fetchState, render: (s) => seen.push(s), onError: () => {} });
  const p1 = poller.poll();
  const p2 = poller.poll();
  await p2;
  assert.equal(calls.length, 1, 'second poll must not issue a request');
  resolveFirst({ ok: true, json: async () => ({ messages: [msg('a', 'x')] }) });
  await p1;
  assert.equal(seen.length, 1);
});

// #861/#863：单飞就等于串行，因此不需要序号记账。
// 这里断言两件事：在途期间的调用被丢弃，且上一次完成后不会饿死。
test('poller serializes polls: one request at a time, then keeps polling', async () => {
  const seen = [];
  const gates = [];
  const fetchState = () => {
    const i = gates.length;
    return new Promise((r) => { gates[i] = r; });
  };
  const poller = createPoller({ fetchState, render: (s) => seen.push(s.tag), onError: () => {} });
  const p1 = poller.poll();
  assert.deepEqual(await poller.poll(), { skipped: true }, 'in-flight 期间的调用必须被丢弃');
  assert.equal(gates.length, 1, '同一时刻只能有一个在飞请求');
  gates[0]({ ok: true, json: async () => ({ tag: 'first', messages: [] }) });
  await p1;
  const p2 = poller.poll();
  assert.equal(gates.length, 2, '上一次完成后必须能继续轮询');
  gates[1]({ ok: true, json: async () => ({ tag: 'second', messages: [] }) });
  await p2;
  assert.deepEqual(seen, ['first', 'second']);
});

test('poller treats non-ok response as error, keeps last state untouched', async () => {
  const seen = [];
  const errs = [];
  const poller = createPoller({
    fetchState: async () => ({ ok: false, status: 500 }),
    render: (s) => seen.push(s),
    onError: (e) => errs.push(e),
  });
  await poller.poll();
  assert.equal(seen.length, 0);
  assert.equal(errs.length, 1);
});

test('poller reports network failure', async () => {
  const errs = [];
  const poller = createPoller({
    fetchState: async () => { throw new Error('offline'); },
    render: () => {},
    onError: (e) => errs.push(e),
  });
  await poller.poll();
  assert.equal(errs.length, 1);
});

test('poller can poll again after failure', async () => {
  let n = 0;
  const seen = [];
  const poller = createPoller({
    fetchState: async () => {
      n++;
      if (n === 1) throw new Error('x');
      return { ok: true, json: async () => ({ tag: 'ok', messages: [] }) };
    },
    render: (s) => seen.push(s.tag),
    onError: () => {},
  });
  await poller.poll();
  await poller.poll();
  assert.deepEqual(seen, ['ok']);
});

test('nextDelay skips work while hidden and adapts to running', () => {
  assert.equal(nextDelay({ running: true, hidden: false }), 1500);
  assert.equal(nextDelay({ running: false, hidden: false }), 3000);
  assert.ok(nextDelay({ running: true, hidden: true }) > 3000);
});

// ---------------------------------------------------------------- #686 指示灯
test('dot follows poll outcome, not a constant', () => {
  assert.equal(dotOn({ ok: true, state: { kernelReady: true } }), true);
  assert.equal(dotOn({ ok: true, state: { kernelReady: false } }), false);
  assert.equal(dotOn({ ok: false }), false);
});

// ---------------------------------------------------------------- #685 发送
test('sender keeps text and reports error on non-2xx', async () => {
  const alerts = [];
  const cleared = [];
  const sender = createSender({
    post: async () => ({ ok: false, status: 500, json: async () => ({}) }),
    alert: (m) => alerts.push(m),
    clearInput: () => cleared.push(1),
    refresh: () => {},
  });
  const r = await sender.send('hi');
  assert.equal(r.sent, false);
  assert.equal(cleared.length, 0, 'must not clear input on failure');
  assert.equal(alerts.length, 1);
  assert.match(alerts[0], /500/);
});

test('sender surfaces server error field even on 200', async () => {
  const alerts = [];
  const sender = createSender({
    post: async () => ({ ok: true, status: 200, json: async () => ({ error: '内核未就绪' }) }),
    alert: (m) => alerts.push(m),
    clearInput: () => { throw new Error('should not clear'); },
    refresh: () => {},
  });
  const r = await sender.send('hi');
  assert.equal(r.sent, false);
  assert.deepEqual(alerts, ['内核未就绪']);
});

test('sender clears input only after success', async () => {
  const cleared = [];
  const sender = createSender({
    post: async () => ({ ok: true, status: 200, json: async () => ({ ok: true }) }),
    alert: () => { throw new Error('should not alert'); },
    clearInput: () => cleared.push(1),
    refresh: () => {},
  });
  const r = await sender.send('hi');
  assert.equal(r.sent, true);
  assert.deepEqual(cleared, [1]);
});

test('sender blocks a second send while in flight', async () => {
  let gate;
  let posts = 0;
  const states = [];
  const sender = createSender({
    post: () => { posts++; return new Promise((r) => { gate = r; }); },
    alert: () => {},
    clearInput: () => {},
    refresh: () => {},
    setBusy: (b) => states.push(b),
  });
  const p1 = sender.send('one');
  const r2 = await sender.send('two');
  assert.equal(r2.sent, false);
  assert.equal(r2.reason, 'busy');
  assert.equal(posts, 1);
  gate({ ok: true, status: 200, json: async () => ({ ok: true }) });
  await p1;
  assert.deepEqual(states, [true, false]);
});

test('sender releases busy flag after network failure', async () => {
  const states = [];
  const sender = createSender({
    post: async () => { throw new Error('offline'); },
    alert: () => {},
    clearInput: () => {},
    refresh: () => {},
    setBusy: (b) => states.push(b),
  });
  await sender.send('x');
  assert.deepEqual(states, [true, false]);
  assert.equal(sender.busy(), false);
});

test('sender ignores blank text', async () => {
  const sender = createSender({
    post: async () => { throw new Error('should not post'); },
    alert: () => {},
    clearInput: () => {},
    refresh: () => {},
  });
  const r = await sender.send('   ');
  assert.equal(r.sent, false);
});

test('sender refuses while agent running', async () => {
  const sender = createSender({
    post: async () => { throw new Error('should not post'); },
    alert: () => {},
    clearInput: () => {},
    refresh: () => {},
    isRunning: () => true,
  });
  const r = await sender.send('x');
  assert.equal(r.sent, false);
  assert.equal(r.reason, 'running');
});

test('sender tolerates non-JSON body', async () => {
  const alerts = [];
  const sender = createSender({
    post: async () => ({ ok: false, status: 502, json: async () => { throw new Error('bad json'); } }),
    alert: (m) => alerts.push(m),
    clearInput: () => {},
    refresh: () => {},
  });
  const r = await sender.send('x');
  assert.equal(r.sent, false);
  assert.match(alerts[0], /502/);
});
