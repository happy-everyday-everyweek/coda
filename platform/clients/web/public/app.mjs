/**
 * WEB-101 / WEB-102：浏览器端薄客户端。
 *
 * - 只通过 /api/state、/api/events(SSE)、/api/command、/api/answer 与核心交互；
 * - 配对令牌保存在 localStorage，刷新后自动恢复（并从 URL 中移除）；
 * - 草稿按会话保存在 localStorage，发送失败不清空；
 * - 终端/Diff/成果只显示服务端给出的有界投影，下载走 /api/artifacts/:id/download。
 */

const TOKEN_KEY = "coda.web.token";
const DRAFT_PREFIX = "coda.web.draft.";

const state = {
  token: null,
  projection: null,
  selected: null,
  sse: null,
  refreshTimer: null,
  busy: false,
  composerSession: null,
};

const els = {
  connection: document.querySelector("#connection"),
  pairToggle: document.querySelector("#pair-toggle"),
  pairBanner: document.querySelector("#pair-banner"),
  pairForm: document.querySelector("#pair-form"),
  pairToken: document.querySelector("#pair-token"),
  pairError: document.querySelector("#pair-error"),
  errorBanner: document.querySelector("#error-banner"),
  workspace: document.querySelector("#workspace"),
  sessions: document.querySelector("#sessions"),
  sessionHeader: document.querySelector("#session-header"),
  interactions: document.querySelector("#interactions"),
  messages: document.querySelector("#messages"),
  composer: document.querySelector("#composer"),
  input: document.querySelector("#composer-input"),
  queue: document.querySelector("#send-queue"),
  steer: document.querySelector("#send-steer"),
  stop: document.querySelector("#send-stop"),
  modelSelect: document.querySelector("#model-select"),
  composerHint: document.querySelector("#composer-hint"),
  details: document.querySelector("#details"),
};

function h(tag, options = {}, children = []) {
  const node = document.createElement(tag);
  if (options.class) node.className = options.class;
  if (options.text !== undefined) node.textContent = String(options.text);
  if (options.attrs) {
    for (const [key, value] of Object.entries(options.attrs)) {
      if (value !== undefined && value !== null) node.setAttribute(key, String(value));
    }
  }
  if (options.on) {
    for (const [key, fn] of Object.entries(options.on)) node.addEventListener(key, fn);
  }
  if (children && children.length) append(node, ...children);
  return node;
}

function append(parent, ...children) {
  for (const child of children) {
    if (child === null || child === undefined) continue;
    parent.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return parent;
}

function setConnection(text, tone = "") {
  els.connection.textContent = text;
  els.connection.className = `pill ${tone}`.trim();
}

function setError(message) {
  if (!message) {
    els.errorBanner.hidden = true;
    els.errorBanner.textContent = "";
    return;
  }
  els.errorBanner.hidden = false;
  els.errorBanner.textContent = message;
}

function setHint(message) {
  els.composerHint.textContent = message ?? "";
}

function stateBadge(value) {
  const map = {
    idle: "空闲",
    running: "运行中",
    queued: "排队中",
    stopped: "已停止",
    failed: "失败",
    unknown: "未知",
    pending: "待处理",
    answered: "已回答",
    expired: "已过期",
    cancelled: "已取消",
    superseded: "已取代",
    completed: "完成",
    stopping: "停止中",
  };
  return map[value] ?? value ?? "未知";
}

function capabilityLabel(value) {
  if (value === true) return "支持";
  if (value === "experimental") return "实验性";
  if (value === false || value === "unsupported") return "不支持";
  return "未知";
}

function formatBytes(size) {
  if (!Number.isFinite(size)) return "未知大小";
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function sourceText(record) {
  const node = record?.node ?? record?.source ?? "未知节点";
  const env = record?.environment ?? "未知环境";
  const remote = env !== "host" ? "远程节点" : "节点";
  return `来源: ${remote} ${node} / ${env}`;
}

function authHeaders() {
  return state.token ? { Authorization: `Bearer ${state.token}` } : {};
}

function acquireToken() {
  let url;
  try {
    url = new URL(location.href);
  } catch {
    return;
  }
  const fromQuery = url.searchParams.get("token");
  const fromHash = url.hash.startsWith("#token=") ? decodeURIComponent(url.hash.slice(7)) : null;
  let stored = null;
  try {
    stored = localStorage.getItem(TOKEN_KEY);
  } catch {
    stored = null;
  }
  state.token = fromQuery || fromHash || stored || null;
  if (state.token) {
    try {
      localStorage.setItem(TOKEN_KEY, state.token);
    } catch {
      /* localStorage 不可用时仍可用内存中的 token */
    }
  }
  if (fromQuery || fromHash) {
    url.searchParams.delete("token");
    url.hash = "";
    history.replaceState(null, "", `${url.pathname}${url.search}`);
  }
}

function showPair(message = "") {
  els.pairBanner.hidden = false;
  els.workspace.hidden = true;
  els.pairError.textContent = message;
  els.pairToken.focus?.();
}

function hidePair() {
  els.pairBanner.hidden = true;
  els.workspace.hidden = false;
}

function handleUnauthorized(message) {
  if (state.sse) {
    state.sse.close();
    state.sse = null;
  }
  state.token = null;
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* ignore */
  }
  setConnection("未授权", "bad");
  showPair(message ?? "配对令牌无效，请重新输入");
}

async function apiJson(pathname, options = {}) {
  const headers = { ...(options.headers ?? {}), ...authHeaders() };
  const response = await fetch(pathname, { ...options, headers });
  let data = null;
  try {
    data = await response.json();
  } catch {
    data = null;
  }
  if (response.status === 401) {
    handleUnauthorized(data?.error?.message);
    throw new Error(data?.error?.message ?? "未授权");
  }
  if (!response.ok || data?.ok === false) {
    throw new Error(data?.error?.message ?? `HTTP ${response.status}`);
  }
  return data;
}

function draftKey(sessionId) {
  return `${DRAFT_PREFIX}${sessionId ?? "none"}`;
}

function draftGet(sessionId) {
  try {
    return localStorage.getItem(draftKey(sessionId)) ?? "";
  } catch {
    return "";
  }
}

function draftSet(sessionId, text) {
  if (!sessionId) return;
  try {
    if (text) localStorage.setItem(draftKey(sessionId), text);
    else localStorage.removeItem(draftKey(sessionId));
  } catch {
    /* ignore quota errors */
  }
}

function draftClear(sessionId) {
  draftSet(sessionId, "");
}

function ensureSelection() {
  const sessions = state.projection?.sessions ?? [];
  if (!state.selected || !sessions.some((s) => s.id === state.selected)) {
    state.selected = sessions[0]?.id ?? null;
  }
}

async function refresh() {
  const data = await apiJson("/api/state");
  state.projection = data.state;
  ensureSelection();
  render();
}

function scheduleRefresh(delayMs = 80) {
  if (state.refreshTimer) clearTimeout(state.refreshTimer);
  state.refreshTimer = setTimeout(() => {
    state.refreshTimer = null;
    refresh().catch((err) => {
      if (!/未授权/.test(err.message)) setError(`刷新失败：${err.message}`);
    });
  }, delayMs);
}

function openSse() {
  if (!state.token) return;
  if (state.sse) {
    state.sse.close();
    state.sse = null;
  }
  const source = new EventSource(`/api/events?token=${encodeURIComponent(state.token)}`);
  source.addEventListener("snapshot", (event) => {
    try {
      const data = JSON.parse(event.data);
      if (data?.state) {
        state.projection = data.state;
        ensureSelection();
        render();
      }
    } catch {
      /* ignore malformed frame */
    }
  });
  source.addEventListener("resumed", () => scheduleRefresh(0));
  source.addEventListener("event", () => scheduleRefresh(80));
  source.onopen = () => setConnection("已连接", "ok");
  source.onerror = () => setConnection("重连中…", "warn");
  state.sse = source;
}

async function connect() {
  setConnection("连接中…", "warn");
  setError(null);
  try {
    await refresh();
    hidePair();
    openSse();
    setConnection("已连接", "ok");
  } catch (err) {
    setConnection("未连接", "bad");
    if (state.token) showPair(err.message);
    else showPair();
  }
}

async function postCommand(kind, target, payload) {
  const data = await apiJson("/api/command", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ kind, target, payload }),
  });
  const receipt = data.receipt ?? {};
  if (receipt.state === "failed" || receipt.state === "unknown") {
    throw new Error(receipt.error?.message ?? "命令失败");
  }
  if (data.state) {
    state.projection = data.state;
    ensureSelection();
    render();
  }
  setError(null);
  return data;
}

async function answerInteraction(interactionId, optionId, text) {
  if (state.busy) return;
  state.busy = true;
  setHint("提交回答中…");
  try {
    const data = await apiJson("/api/answer", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ interactionId, optionId: optionId ?? null, text: text ?? null }),
    });
    if (data.duplicate) setHint("该审批/提问已处理（去重）");
    else setHint("已提交回答");
    if (data.state) {
      state.projection = data.state;
      render();
    } else {
      await refresh();
    }
  } catch (err) {
    setHint(`回答失败：${err.message}`);
    setError(`回答失败：${err.message}`);
  } finally {
    state.busy = false;
  }
}

async function sendCurrent(mode) {
  if (state.busy) return;
  if (!state.selected) {
    setHint("请选择会话");
    return;
  }
  const text = els.input.value.trim();
  if (!text) {
    setHint("消息为空");
    return;
  }
  const sessionId = state.selected;
  draftSet(sessionId, els.input.value);
  state.busy = true;
  setHint(mode === "queue" ? "排队中…" : mode === "steer" ? "插话中…" : "发送中…");
  try {
    const kind = mode === "steer" ? "turn.steer" : "turn.send";
    const data = await postCommand(kind, { sessionId }, { text, mode });
    if (data.receipt?.state === "failed" || data.receipt?.state === "unknown") {
      throw new Error(data.receipt?.error?.message ?? "发送失败");
    }
    draftClear(sessionId);
    els.input.value = "";
    setHint(mode === "queue" ? "已排队" : mode === "steer" ? "已发送插话" : "已发送");
    await refresh();
  } catch (err) {
    setHint(`发送失败：${err.message}；草稿已保留`);
    setError(`发送失败：${err.message}`);
  } finally {
    state.busy = false;
  }
}

async function stopTurn() {
  if (!state.selected || state.busy) return;
  state.busy = true;
  setHint("停止中…");
  try {
    await postCommand("turn.stop", { sessionId: state.selected }, {});
    setHint("已请求停止");
    await refresh();
  } catch (err) {
    setHint(`停止失败：${err.message}`);
    setError(`停止失败：${err.message}`);
  } finally {
    state.busy = false;
  }
}

function downloadText(name, text) {
  const blob = new Blob([text ?? ""], { type: "text/plain;charset=utf-8" });
  const href = URL.createObjectURL(blob);
  const link = h("a", { attrs: { href, download: name } });
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(href), 1000);
}

function artifactDownloadUrl(id) {
  return `/api/artifacts/${encodeURIComponent(id)}/download?token=${encodeURIComponent(state.token ?? "")}`;
}

function selectSession(id) {
  state.selected = id;
  state.composerSession = null;
  render();
}

function renderSessions(projection) {
  els.sessions.replaceChildren();
  els.sessions.append(h("div", { class: "section-title", text: "会话" }));
  const sessions = projection.sessions ?? [];
  if (!sessions.length) {
    els.sessions.append(h("div", { class: "empty", text: "暂无会话" }));
    return;
  }
  for (const session of sessions) {
    const button = h("button", {
      class: `session${session.id === state.selected ? " selected" : ""}`,
      attrs: { type: "button" },
      on: { click: () => selectSession(session.id) },
    });
    append(button, h("span", { class: "title", text: session.title ?? "(未命名会话)" }));
    append(
      button,
      h("span", {
        class: "meta",
        text: `${stateBadge(session.state)} · ${session.agent ?? "未知 Agent"} · ${session.model ?? "未选模型"}`,
      }),
    );
    append(
      button,
      h("span", {
        class: "meta",
        text: `${session.environment && session.environment !== "host" ? "远程 " : ""}${session.node ?? "?"} / ${session.environment ?? "?"}`,
      }),
    );
    els.sessions.append(button);
  }
}

function renderHeader(projection) {
  const session = (projection.sessions ?? []).find((s) => s.id === state.selected);
  els.sessionHeader.replaceChildren();
  if (!session) {
    els.sessionHeader.append(h("div", { class: "empty", text: "未选择会话" }));
    return;
  }
  els.sessionHeader.append(h("h1", { text: session.title ?? "(未命名会话)" }));
  const meta = h("div", { class: "meta" });
  const caps = session.capabilities && typeof session.capabilities === "object"
    ? Object.entries(session.capabilities).map(([k, v]) => `${k}=${capabilityLabel(v)}`).join(" ")
    : "未知";
  append(
    meta,
    h("span", { text: `状态: ${stateBadge(session.state)}` }),
    h("span", { text: `Agent: ${session.agent ?? "未知"}` }),
    h("span", { text: `工作区: ${session.workspace ?? "未知"}` }),
    h("span", { text: sourceText(session) }),
    h("span", { text: `能力: ${caps}` }),
    session.turnState ? h("span", { text: `回合: ${stateBadge(session.turnState)}` }) : null,
  );
  els.sessionHeader.append(meta);
}

function renderInteractions(projection) {
  els.interactions.replaceChildren();
  const pending = (projection.interactions ?? []).filter(
    (interaction) => interaction.status === "pending" && interaction.sessionId === state.selected,
  );
  if (!pending.length) return;
  for (const interaction of pending) {
    const card = h("section", { class: "interaction" });
    append(
      card,
      h("div", { class: "prompt", text: `[${interaction.kind}] ${interaction.prompt}` }),
    );
    const options = h("div", { class: "options" });
    for (const option of interaction.options ?? []) {
      append(
        options,
        h("button", {
          attrs: { type: "button" },
          text: option.label ?? option.id,
          on: { click: () => answerInteraction(interaction.id, option.id, null) },
        }),
      );
    }
    const textInput = h("input", {
      attrs: { type: "text", placeholder: "自由文本（可选）" },
    });
    append(
      options,
      textInput,
      h("button", {
        class: "ghost",
        attrs: { type: "button" },
        text: "提交文本",
        on: { click: () => answerInteraction(interaction.id, null, textInput.value) },
      }),
    );
    append(card, options);
    if (interaction.expiresAt) append(card, h("div", { class: "hint", text: `过期: ${interaction.expiresAt}` }));
    els.interactions.append(card);
  }
}

function renderMessages(projection) {
  const nearBottom =
    els.messages.scrollHeight - els.messages.scrollTop - els.messages.clientHeight < 100;
  els.messages.replaceChildren();
  const messages = (projection.messages ?? []).filter((message) => message.sessionId === state.selected);
  if (!messages.length) {
    els.messages.append(h("div", { class: "empty", text: "暂无消息" }));
    return;
  }
  for (const message of messages) {
    const article = h("article", { class: `message ${message.role}` });
    const meta = h("div", { class: "message-meta" });
    append(
      meta,
      h("span", { text: message.role === "user" ? "你" : message.role === "assistant" ? "Agent" : message.role }),
      message.streaming ? h("span", { class: "streaming", text: "流式输出中…" }) : null,
      message.ts ? h("span", { text: message.ts }) : null,
    );
    append(article, meta, h("pre", { text: message.text ?? "" }));
    const attachments = message.attachments ?? [];
    if (attachments.length) {
      const list = h("div", { class: "attachments" });
      for (const attachment of attachments) {
        const id = attachment.artifactId ?? attachment.id;
        if (id) {
          append(
            list,
            h("a", {
              text: `${attachment.name ?? id} (${formatBytes(attachment.size)})`,
              attrs: { href: artifactDownloadUrl(id), download: attachment.name ?? id },
            }),
          );
        } else {
          append(list, h("span", { text: `${attachment.name ?? "附件"} (${formatBytes(attachment.size)})` }));
        }
      }
      append(article, list);
    }
    els.messages.append(article);
  }
  if (nearBottom) {
    els.messages.scrollTop = els.messages.scrollHeight;
  }
}

function renderModelSelect(projection) {
  const options = projection.models?.options ?? [];
  const current = projection.models?.current ?? "";
  els.modelSelect.replaceChildren();
  for (const option of options) {
    const node = h("option", { text: option.label ?? option.id, attrs: { value: option.id } });
    els.modelSelect.append(node);
  }
  if (current && options.some((option) => option.id === current)) {
    els.modelSelect.value = current;
  }
  els.modelSelect.disabled = !state.selected || options.length === 0;
}

function renderDetails(projection) {
  els.details.replaceChildren();
  const terminals = (projection.terminals ?? []).filter(
    (terminal) => terminal.sessionId === state.selected,
  );
  const artifacts = (projection.artifacts ?? []).filter(
    (artifact) => artifact.sessionId === state.selected,
  );

  const terminalTitle = h("div", { class: "section-title", text: "终端" });
  els.details.append(terminalTitle);
  if (!terminals.length) {
    els.details.append(h("div", { class: "empty", text: "当前会话没有终端" }));
  }
  for (const terminal of terminals) {
    const card = h("section", { class: "card terminal" });
    append(
      card,
      h("h3", {}, [
        document.createTextNode(`终端 ${terminal.title ?? terminal.id} `),
        h("span", { class: "meta", text: `[${terminal.status}] ${sourceText(terminal)}` }),
      ]),
    );
    const lines = String(terminal.output ?? "").split("\n");
    const tail = lines.slice(-200).join("\n");
    append(card, h("pre", { text: tail || "（无输出）" }));
    if (terminal.truncated || lines.length > 200) {
      append(
        card,
        h("div", {
          class: "truncated",
          text: `输出已截断：显示最近 200 行（总 ${terminal.fullLength ?? "?"} 字符）。`,
        }),
      );
    }
    const actions = h("div", { class: "card-actions" });
    append(
      actions,
      h("button", {
        class: "ghost",
        attrs: { type: "button" },
        text: "保存输出",
        on: { click: () => downloadText(`${terminal.id}.log`, terminal.output ?? "") },
      }),
      h("span", { class: "hint", text: "外部终端打开由核心节点负责；这里只保存当前有界回放。" }),
    );
    append(card, actions);
    els.details.append(card);
  }

  els.details.append(h("div", { class: "section-title", text: "Diff / 成果" }));
  if (!artifacts.length) {
    els.details.append(h("div", { class: "empty", text: "当前会话没有成果" }));
    return;
  }
  for (const artifact of artifacts) {
    const card = h("section", { class: "card" });
    append(
      card,
      h("h3", {}, [
        document.createTextNode(`${artifact.name ?? artifact.id} `),
        h("span", { class: "meta", text: `[${artifact.kind}] ${formatBytes(artifact.size)} · ${sourceText(artifact)}` }),
      ]),
    );
    if (artifact.diff) {
      append(card, h("div", { class: "diff" }, [h("pre", { text: artifact.diff })]));
    }
    if (artifact.diffTruncated) {
      append(card, h("div", { class: "truncated", text: "Diff 已在客户端截断，完整内容请下载。" }));
    }
    if (artifact.sha256) {
      append(card, h("div", { class: "hint", text: `SHA-256: ${artifact.sha256}` }));
    }
    const actions = h("div", { class: "card-actions" });
    append(
      actions,
      h("a", {
        text: "下载成果",
        attrs: { href: artifactDownloadUrl(artifact.id), download: artifact.name ?? artifact.id },
      }),
    );
    append(card, actions);
    els.details.append(card);
  }
}

function syncComposer() {
  if (state.selected !== state.composerSession) {
    state.composerSession = state.selected;
    els.input.value = draftGet(state.selected);
  }
}

function render() {
  const projection = state.projection;
  if (!projection) return;
  hidePair();
  renderSessions(projection);
  renderHeader(projection);
  renderInteractions(projection);
  renderMessages(projection);
  renderModelSelect(projection);
  renderDetails(projection);
  syncComposer();
}

function wireEvents() {
  els.pairToggle.addEventListener("click", () => showPair("请输入配对令牌"));
  els.pairForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const token = els.pairToken.value.trim();
    if (!token) return;
    els.pairError.textContent = "";
    try {
      const response = await fetch("/api/pair", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token }),
      });
      const data = await response.json().catch(() => null);
      if (!response.ok) throw new Error(data?.error?.message ?? "配对失败");
      state.token = token;
      try {
        localStorage.setItem(TOKEN_KEY, token);
      } catch {
        /* ignore */
      }
      els.pairToken.value = "";
      await connect();
    } catch (err) {
      els.pairError.textContent = err.message;
    }
  });

  els.composer.addEventListener("submit", (event) => {
    event.preventDefault();
    sendCurrent("send");
  });
  els.queue.addEventListener("click", () => sendCurrent("queue"));
  els.steer.addEventListener("click", () => sendCurrent("steer"));
  els.stop.addEventListener("click", () => stopTurn());
  els.input.addEventListener("input", () => {
    if (state.selected) draftSet(state.selected, els.input.value);
  });
  els.input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      sendCurrent("send");
    }
  });
  els.modelSelect.addEventListener("change", async () => {
    if (!state.selected) return;
    try {
      await postCommand("model.set", { sessionId: state.selected }, { model: els.modelSelect.value });
      setHint(`模型已切换: ${els.modelSelect.value}`);
    } catch (err) {
      setHint(`模型切换失败：${err.message}`);
    }
  });
}

async function boot() {
  acquireToken();
  wireEvents();
  if (state.token) await connect();
  else showPair("首次使用请输入服务器启动时打印的配对令牌");
}

boot().catch((err) => {
  setConnection("错误", "bad");
  setError(`启动失败：${err.message}`);
});
