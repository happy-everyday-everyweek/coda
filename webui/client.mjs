// Coda Web 前端的纯逻辑层：不碰 DOM，便于 node --test 直接驱动。
// index.html 只负责把这些函数的结果贴到页面上。
//
// 拆出来的原因（对应 issue）：
//   #682 无变化判定只看条数，而 /api/state 只回最后 200 条，窗口滑动后条数恒定。
//   #683 运行中每 1.5s 整表重建，选中文字与滚动位置一起被冲掉。
//   #684 poll() 同时被定时链与 send()/stop() 调用，无单飞与乱序保护。
//   #685 结果未知就清空输入框，且请求在途时按钮仍可点。
//   #686 指示灯恒为 on，断线时仍是绿的。

/**
 * 快照指纹。必须覆盖三种变化：条数变、窗口滑动（条数不变但首条换了）、
 * 末条文本原地增长（流式输出）。只用 length 或只用末条都会漏。
 */
export function snapshotSig(msgs) {
  const list = msgs || [];
  if (list.length === 0) return '0::';
  const first = list[0];
  const last = list[list.length - 1];
  const text = last.text || '';
  return [
    list.length,
    first.id ?? '',
    last.id ?? '',
    last.ts ?? '',
    text.length,
    text.slice(-32),
  ].join('|');
}

/**
 * 计算增量渲染计划。
 * rebuild=true 表示必须整表重建（窗口滑动、条数变少、id 序列错位）；
 * 否则给出需要追加的消息与需要就地改文本的消息，调用方只动这些节点。
 */
export function planRender(prev, next) {
  const a = prev || [];
  const b = next || [];
  if (b.length < a.length) return { rebuild: true, append: [], update: [] };
  for (let i = 0; i < a.length; i++) {
    if ((a[i].id ?? i) !== (b[i].id ?? i)) {
      return { rebuild: true, append: [], update: [] };
    }
  }
  const update = [];
  for (let i = 0; i < a.length; i++) {
    if ((a[i].text || '') !== (b[i].text || '')) update.push(b[i]);
  }
  return { rebuild: false, append: b.slice(a.length), update };
}

/** 指示灯只跟真实轮询结果与内核就绪态走。 */
export function dotOn({ ok, state }) {
  if (!ok) return false;
  return !state || state.kernelReady !== false;
}

/** 轮询间隔：运行中 1.5s、空闲 3s；页面隐藏时退到 15s，别在后台空转。 */
export function nextDelay({ running, hidden }) {
  if (hidden) return 15000;
  return running ? 1500 : 3000;
}

/**
 * 单飞轮询器。in-flight 期间的重复调用直接丢弃（send()/stop() 触发的立即刷新
 * 若已在途就复用那次结果）；响应带序号，迟到的旧响应不再覆盖新状态。
 */
export function createPoller({ fetchState, render, onError }) {
  let inFlight = false;
  let seq = 0;
  let applied = 0;
  async function poll() {
    if (inFlight) return { skipped: true };
    inFlight = true;
    const mine = ++seq;
    try {
      const r = await fetchState();
      if (!r || !r.ok) {
        onError(new Error('状态请求失败（HTTP ' + (r ? r.status : '?') + '）'));
        return { ok: false };
      }
      const state = await r.json();
      // 乱序保护：只有比已应用序号更新的响应才允许落地。
      if (mine < applied) return { ok: true, stale: true };
      applied = mine;
      render(state);
      return { ok: true, state };
    } catch (e) {
      onError(e);
      return { ok: false, error: e };
    } finally {
      inFlight = false;
    }
  }
  return { poll, inFlight: () => inFlight };
}

/**
 * 发送器。成功才清空输入框；失败保留原文并给出可诊断的原因。
 * 在途时拒绝第二次发送，避免服务端回「Agent 正在运行中，请先停止」。
 */
export function createSender({ post, alert, clearInput, refresh, setBusy, isRunning }) {
  let busy = false;
  const busyTo = (v) => {
    busy = v;
    if (setBusy) setBusy(v);
  };
  async function send(raw) {
    const text = (raw || '').trim();
    if (!text) return { sent: false, reason: 'empty' };
    if (isRunning && isRunning()) return { sent: false, reason: 'running' };
    if (busy) return { sent: false, reason: 'busy' };
    busyTo(true);
    try {
      const r = await post(text);
      const j = await r.json().catch(() => ({}));
      if (!r.ok || j.error) {
        alert(j.error || '发送失败（HTTP ' + r.status + '）');
        return { sent: false, reason: 'error' };
      }
      clearInput();
      refresh();
      return { sent: true };
    } catch (e) {
      alert('发送失败：' + (e && e.message ? e.message : '网络错误'));
      return { sent: false, reason: 'network' };
    } finally {
      busyTo(false);
    }
  }
  return { send, busy: () => busy };
}