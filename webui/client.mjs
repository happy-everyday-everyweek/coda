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
 * 快照指纹。必须覆盖四种变化：条数变、窗口滑动（条数不变但首条换了）、
 * 末条文本原地增长（流式输出）、以及任意位置消息的原地更新（OCR #819：
 * planRender 支持 update 任意下标，早先的工具结果消息补齐时末条可能没变，
 * 只看首末会让 UI 停在旧内容）。对全部消息的文本长度做一遍廉价折叠摘要。
 */
export function snapshotSig(msgs) {
  const list = msgs || [];
  if (list.length === 0) return '0::';
  const first = list[0];
  const last = list[list.length - 1];
  const text = last.text || '';
  let digest = 0;
  for (let i = 0; i < list.length; i++) {
    const t = list[i].text || '';
    // 长度 + 末字符足以捕获绝大多数原地更新，且 O(总条数) 不复制文本。
    digest = (digest * 31 + t.length + (t.length ? t.charCodeAt(t.length - 1) : 0)) | 0;
  }
  return [
    list.length,
    first.id ?? '',
    last.id ?? '',
    last.ts ?? '',
    text.length,
    text.slice(-32),
    digest,
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
 * 单飞轮询器。in-flight 期间的重复调用直接丢弃并返回 `{ skipped: true }`
 * （send()/stop() 触发的立即刷新若已在途，就复用那次结果）。
 *
 * 因为同一时刻最多只有一个请求在飞，响应必然按发出顺序落地，
 * 不需要再记序号做乱序保护（旧代码里的 `mine < applied` 分支不可达）。
 */
export function createPoller({ fetchState, render, onError }) {
  let inFlight = false;
  async function poll() {
    if (inFlight) return { skipped: true };
    inFlight = true;
    try {
      const r = await fetchState();
      if (!r || !r.ok) {
        onError(new Error('状态请求失败（HTTP ' + (r ? r.status : '?') + '）'));
        return { ok: false };
      }
      const state = await r.json();
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