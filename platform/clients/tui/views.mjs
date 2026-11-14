/**
 * TUI 文本视图（布局参考：DSH 官方首推社区插件 dsh-TUI，MIT；
 * https://github.com/ccch1mneyyy/dsh-TUI ，npm @deepseek-harness-tui/dsh-tui）。
 *
 * 借鉴其「雾蓝」配色语义、像素吉祥物头部、时间线消息流、状态药丸与提示页脚的
 * 视觉组织方式；不复制其代码，也不依赖其运行时。
 *
 * 输入一律是共享 canonical 投影，不包含 RPC / 核心逻辑；核心文本不可信，
 * 先剥掉 ANSI/控制字符，再加我们自己的样式。
 */

const ANSI_PATTERN =
  // eslint-disable-next-line no-control-regex
  /[\u001B\u009B](?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~]|\][^\u0007]*(?:\u0007|\u001B\\))/g;
// eslint-disable-next-line no-control-regex
const CONTROL_PATTERN = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g;

export const MIN_WIDTH = 40;
export const DEFAULT_WIDTH = 80;

/** 雾蓝主题：dark/light 两套，角色与 dsh-TUI 的语义槽位对齐（品牌/正文/次要/线/状态）。 */
const THEMES = Object.freeze({
  dark: {
    accent: "\u001b[38;2;125;161;222m",
    accentSoft: "\u001b[38;2;171;194;236m",
    ink: "\u001b[38;2;230;233;239m",
    muted: "\u001b[38;2;154;164;178m",
    line: "\u001b[38;2;74;83;97m",
    success: "\u001b[38;2;129;150;106m",
    warn: "\u001b[38;2;223;162;91m",
    error: "\u001b[38;2;185;81;72m",
    user: "\u001b[38;2;171;194;236m",
    agent: "\u001b[38;2;125;161;222m",
    tool: "\u001b[38;2;86;182;194m",
  },
  light: {
    accent: "\u001b[38;2;63;108;196m",
    accentSoft: "\u001b[38;2;94;136;204m",
    ink: "\u001b[38;2;52;57;69m",
    muted: "\u001b[38;2;107;114;128m",
    line: "\u001b[38;2;201;207;219m",
    success: "\u001b[38;2;94;124;74m",
    warn: "\u001b[38;2;185;121;41m",
    error: "\u001b[38;2;187;81;72m",
    user: "\u001b[38;2;94;136;204m",
    agent: "\u001b[38;2;63;108;196m",
    tool: "\u001b[38;2;64;128;140m",
  },
});

const ANSI_FALLBACK = {
  accent: "\u001b[36m",
  accentSoft: "\u001b[36m",
  ink: "\u001b[37m",
  muted: "\u001b[90m",
  line: "\u001b[90m",
  success: "\u001b[32m",
  warn: "\u001b[33m",
  error: "\u001b[31m",
  user: "\u001b[36m",
  agent: "\u001b[36m",
  tool: "\u001b[35m",
};
const RESET = "\u001b[0m";

export function safeText(value) {
  return String(value ?? "")
    .replace(ANSI_PATTERN, "")
    .replace(CONTROL_PATTERN, "");
}

/** 解析渲染选项：颜色（TTY + 未设置 NO_COLOR）、主题、宽度。纯函数，便于测试。 */
export function resolveUiOptions({ color, width, theme = "dark", env = process.env, isTTY = false } = {}) {
  const colorEnabled = color === undefined ? Boolean(isTTY) && !env.NO_COLOR : Boolean(color);
  const useAnsi = colorEnabled && env.COLORTERM !== "truecolor" && env.COLORTERM !== "24bit";
  const palette = !colorEnabled ? null : useAnsi ? ANSI_FALLBACK : THEMES[theme] ?? THEMES.dark;
  const resolvedWidth = Math.max(
    MIN_WIDTH,
    Number.isFinite(width) ? Math.floor(width) : Number.parseInt(env.COLUMNS ?? "", 10) || DEFAULT_WIDTH,
  );
  return { color: colorEnabled, palette, width: resolvedWidth, theme };
}

function style(options, role, text) {
  if (!options?.palette) return text;
  return `${options.palette[role] ?? options.palette.ink}${text}${RESET}`;
}

/** 去掉 ANSI 后的显示宽度（CJK 记 2 列）。 */
export function displayWidth(text) {
  const plain = String(text ?? "").replace(ANSI_PATTERN, "");
  let width = 0;
  for (const ch of plain) {
    const code = ch.codePointAt(0);
    if (code === 0 || code < 32) continue;
    width += isWide(code) ? 2 : 1;
  }
  return width;
}

function isWide(code) {
  return (
    (code >= 0x1100 && code <= 0x115f) ||
    (code >= 0x2e80 && code <= 0xa4cf) ||
    (code >= 0xac00 && code <= 0xd7a3) ||
    (code >= 0xf900 && code <= 0xfaff) ||
    (code >= 0xfe30 && code <= 0xfe4f) ||
    (code >= 0xff00 && code <= 0xff60) ||
    (code >= 0xffe0 && code <= 0xffe6) ||
    (code >= 0x1f300 && code <= 0x1faff)
  );
}

/** 按显示宽度截断（保留 ANSI 序列），超出加省略号。 */
export function truncateToWidth(text, maxWidth, ellipsis = "…") {
  const raw = String(text ?? "");
  if (displayWidth(raw) <= maxWidth) return raw;
  const limit = Math.max(0, maxWidth - displayWidth(ellipsis));
  let out = "";
  let width = 0;
  // 逐段复制，ANSI 序列整体跳过（不计宽）
  for (let i = 0; i < raw.length; ) {
    if (raw[i] === "\u001b") {
      const match = raw.slice(i).match(/^\u001b\[[0-9;]*m/);
      if (match) {
        out += match[0];
        i += match[0].length;
        continue;
      }
    }
    const code = raw.codePointAt(i);
    const ch = String.fromCodePoint(code);
    const w = isWide(code) ? 2 : 1;
    if (width + w > limit) break;
    out += ch;
    width += w;
    i += ch.length;
  }
  return `${out}${ellipsis}`;
}

/** 按显示宽度补空格对齐（ANSI 文本按可见宽度对齐）。 */
export function padToWidth(text, width, align = "left") {
  const current = displayWidth(text);
  const pad = Math.max(0, width - current);
  if (align === "right") return `${" ".repeat(pad)}${text}`;
  return `${text}${" ".repeat(pad)}`;
}

export function formatBytes(size) {
  if (!Number.isFinite(size)) return "未知大小";
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

export function truncateLines(text, maxLines) {
  const clean = safeText(text);
  const lines = clean.split("\n");
  if (lines.length <= maxLines) return { text: clean, truncated: false, totalLines: lines.length };
  return {
    text: lines.slice(lines.length - maxLines).join("\n"),
    truncated: true,
    totalLines: lines.length,
  };
}

export function sourceLabel(record, { localNode = null } = {}) {
  const node = record?.node ?? record?.sourceNode ?? record?.origin ?? null;
  const env = record?.environment ?? record?.env ?? null;
  if (!node && !env) return "来源: 未知节点";
  const remote = env && env !== "host" ? "远程节点" : "节点";
  const local = localNode && node === localNode ? "（本机）" : "";
  return `来源: ${remote} ${node ?? "未知"}${local} / 环境 ${env ?? "未知"}`;
}

export function capabilityLabel(value) {
  if (value === true) return "支持";
  if (value === "experimental") return "实验性";
  if (value === false || value === "unsupported") return "不支持";
  return "未知";
}

export function renderCapabilities(capabilities) {
  if (!capabilities || typeof capabilities !== "object") return "能力: 未知";
  const keys = Object.keys(capabilities);
  if (keys.length === 0) return "能力: 未知";
  return `能力: ${keys.map((k) => `${k}=${capabilityLabel(capabilities[k])}`).join(" ")}`;
}

function stateBadge(state) {
  const map = {
    idle: "空闲",
    running: "运行中",
    queued: "排队中",
    stopped: "已停止",
    failed: "失败",
    unknown: "未知",
  };
  return map[state] ?? state ?? "未知";
}

function stateColor(state) {
  if (state === "running") return "success";
  if (state === "queued") return "warn";
  if (state === "failed") return "error";
  if (state === "stopped") return "muted";
  return "muted";
}

function dot(state, options) {
  return style(options, stateColor(state), "●");
}

/** 顶栏：像素鲸风格头部 + 品牌 + 实时状态药丸。 */
export function renderHeader(projection, options = {}) {
  const conn = projection?.connection?.status ?? "unknown";
  const seq = projection?.connection?.seq ?? 0;
  const protocol = projection?.protocol ?? {};
  const counts = projection?.counts ?? {};
  const model = projection?.models?.current ?? "未选模型";
  const pending = counts.pendingInteractions ?? 0;
  const state = conn === "ready" ? "success" : conn === "error" ? "error" : "warn";
  const mascot = style(options, "accent", " ▄▀▄ ") + style(options, "accentSoft", "  ◈ Coda ");
  const brand = `${mascot}${style(options, "muted", " · 跨端 Agent 控制台")}`;
  const pills = [
    `${style(options, state, "●")} ${conn}`,
    style(options, "muted", `协议 v${protocol.version ?? "?"} / 核心 ${protocol.coreVersion ?? "?"}`),
    style(options, "muted", `模型 ${model}`),
    pending > 0 ? style(options, "warn", `待处理 ${pending}`) : style(options, "muted", "待处理 0"),
    style(options, "muted", `seq ${seq}`),
  ].join(style(options, "line", "  │  "));
  return [brand, pills].join("\n");
}

/** 画框：╭─ 标题 ───╮ / │ body │ / ╰───╯；宽度自适应。 */
export function frame(title, bodyLines, options = {}) {
  const width = Math.max(MIN_WIDTH, options.width ?? DEFAULT_WIDTH);
  const inner = width - 2;
  const titleText = title ? ` ${safeText(title)} ` : "";
  const titlePad = Math.max(0, inner - displayWidth(titleText) - 1);
  const top = style(options, "line", `╭─${titleText}${"─".repeat(titlePad)}╮`);
  const bottom = style(options, "line", `╰${"─".repeat(inner)}╯`);
  const lines = (bodyLines ?? []).map((line) => {
    const clipped = truncateToWidth(line, inner - 1);
    return `${style(options, "line", "│")}${padToWidth(clipped, inner - 1)} ${style(options, "line", "│")}`;
  });
  return [top, ...lines, bottom].join("\n");
}

function kv(label, value, options) {
  return `${style(options, "muted", `${label}:`)} ${value}`;
}

function section(title, lines, options) {
  return frame(title, lines, options);
}

/** 会话列表（响应式列：窄终端先丢 Agent/模型列，再丢工作区）。 */
export function renderSessions(projection, options = {}) {
  const { selectedSessionId = null } = options;
  const sessions = projection?.sessions ?? [];
  if (!sessions.length) return frame("会话", ["（无会话，按 /new 或直接发消息创建）"], options);
  const width = options.width ?? DEFAULT_WIDTH;
  const showAgent = width >= 72;
  const showWorkspace = width >= 96;
  const rows = sessions.map((s) => {
    const selected = s.id === selectedSessionId;
    const marker = selected ? style(options, "accent", "❯") : " ";
    const id = style(options, "muted", s.id);
    const title = truncateToWidth(safeText(s.title ?? "(未命名)"), showWorkspace ? 28 : showAgent ? 34 : 40);
    const badge = `${dot(s.state, options)} ${style(options, stateColor(s.state), stateBadge(s.state))}`;
    const agent = showAgent ? style(options, "muted", `${s.agent ?? "未知 Agent"} / ${s.model ?? "未选模型"}`) : "";
    const remote =
      s.environment && s.environment !== "host"
        ? style(options, "warn", `[远程节点 ${s.node ?? "?"} / ${s.environment}]`)
        : "";
    const workspace = showWorkspace ? style(options, "muted", truncateToWidth(String(s.workspace ?? "-"), 24)) : "";
    const cols = [marker, id, title, badge, agent, remote, workspace].filter((part) => part !== "");
    return cols.join(" ");
  });
  const hint = style(options, "muted", "选中会话后用 /send、/queue、/steer 或直接输入文本");
  return [frame("会话", rows, options), hint].join("\n");
}

export function renderSessionHeader(projection, sessionId, options = {}) {
  const s = projection?.sessions?.find((x) => x.id === sessionId);
  if (!s) return "（未选择会话）";
  return [
    `${style(options, "ink", s.title ?? "(未命名会话)")}  ${dot(s.state, options)} ${style(options, stateColor(s.state), stateBadge(s.state))}`,
    kv("Agent", s.agent ?? "未知", options),
    kv("模型", s.model ?? "未选", options),
    kv("工作区", s.workspace ?? "未知", options),
    style(options, "muted", sourceLabel(s)),
    style(options, "muted", renderCapabilities(s.capabilities)),
    s.turnState ? kv("回合", s.turnState, options) : null,
  ]
    .filter(Boolean)
    .join("\n");
}

export function renderAttachments(message, options = {}) {
  const attachments = message?.attachments ?? [];
  if (!attachments.length) return "";
  return attachments
    .map(
      (a) =>
        `  ${style(options, "tool", "⚙")} 附件 ${safeText(a.name)} (${formatBytes(a.size)})${a.artifactId ? ` [${a.artifactId}]` : ""}`,
    )
    .join("\n");
}

function wrapLine(text, width) {
  const clean = safeText(text);
  if (displayWidth(clean) <= width) return [clean];
  // 按显示宽度切行，保留换行语义
  const out = [];
  let current = "";
  let w = 0;
  for (const ch of clean) {
    const code = ch.codePointAt(0);
    const cw = code === 10 ? 0 : isWide(code) ? 2 : 1;
    if (code === 10) {
      out.push(current);
      current = "";
      w = 0;
      continue;
    }
    if (w + cw > width) {
      out.push(current);
      current = "";
      w = 0;
    }
    current += ch;
    w += cw;
  }
  if (current) out.push(current);
  return out;
}

/** 消息时间线：│ ❯ 你 / │ ◆ Coda，长文本按宽度换行，工具与附件用 ⚙。 */
export function renderMessages(projection, sessionId, options = {}) {
  const { limit = 200 } = options;
  const all = (projection?.messages ?? []).filter((m) => m.sessionId === sessionId);
  const messages = all.slice(-limit);
  const width = Math.max(MIN_WIDTH, (options.width ?? DEFAULT_WIDTH) - 4);
  if (!messages.length) return frame("会话记录", ["（暂无消息）"], options);
  const lines = [];
  if (all.length > messages.length) {
    lines.push(style(options, "muted", `（仅显示最近 ${limit} 条，共 ${all.length} 条；/history all 查看全部）`));
  }
  for (const m of messages) {
    const isUser = m.role === "user";
    const who = isUser ? "你" : m.role === "assistant" ? "Coda" : safeText(m.role);
    const marker = isUser ? "❯" : "◆";
    const roleColor = isUser ? "user" : "agent";
    const stream = m.streaming ? style(options, "muted", "▍") : "";
    lines.push(`${style(options, "line", "│")} ${style(options, roleColor, marker)} ${style(options, roleColor, who)}${stream}`);
    for (const row of wrapLine(m.text, width)) {
      lines.push(`${style(options, "line", "│")}   ${style(options, "ink", row)}`);
    }
    const attachments = renderAttachments(m, options);
    if (attachments) {
      for (const row of attachments.split("\n")) {
        lines.push(`${style(options, "line", "│")}   ${row}`);
      }
    }
    lines.push(style(options, "line", "│"));
  }
  return [frame("会话记录", lines.slice(0, -1), options)].join("\n");
}

export function renderInteractions(projection, options = {}) {
  const { sessionId = null } = options;
  const pending = (projection?.interactions ?? []).filter(
    (i) => i.status === "pending" && (!sessionId || i.sessionId === sessionId),
  );
  if (!pending.length) return style(options, "muted", "待处理审批/提问: 无");
  const lines = [];
  for (const i of pending) {
    lines.push(`${style(options, "warn", "◆")} ${style(options, "ink", safeText(i.prompt))}`);
    lines.push(`  ${kv("ID", i.id, options)}  ${kv("类型", i.kind, options)}  ${kv("过期", i.expiresAt ?? "-", options)}`);
    if (i.options?.length) {
      lines.push(`  ${style(options, "muted", "选项:")} ${i.options.map((o) => `${o.id}=${safeText(o.label)}`).join("  ")}`);
    } else {
      lines.push(`  ${style(options, "muted", "选项: （自由文本）")}`);
    }
  }
  return frame("待处理", lines, options);
}

export function renderTerminalList(projection, options = {}) {
  const { sessionId = null } = options;
  const terminals = (projection?.terminals ?? []).filter((t) => !sessionId || t.sessionId === sessionId);
  if (!terminals.length) return style(options, "muted", "终端: 无");
  return frame(
    "终端",
    terminals.map(
      (t) =>
        `${style(options, "tool", "⚙")} ${t.id}  ${safeText(t.title)}  ${style(options, stateColor(t.status), t.status)}  ${style(options, "muted", `来源 ${t.node ?? "?"}`)}${t.truncated ? style(options, "muted", "  （输出已截断）") : ""}`,
    ),
    options,
  );
}

export function renderTerminal(terminal, options = {}) {
  const { maxLines = 20, maxChars = 12_000 } = options;
  if (!terminal) return "终端不可用（可能已关闭或尚未打开）";
  const body = truncateLines(terminal.output ?? "", maxLines);
  let output = body.text;
  let charTruncated = false;
  if (output.length > maxChars) {
    output = output.slice(output.length - maxChars);
    charTruncated = true;
  }
  const truncated = terminal.truncated || body.truncated || charTruncated;
  const meta = [
    kv("终端", `${terminal.id} · ${safeText(terminal.title)}`, options),
    style(options, "muted", sourceLabel(terminal)),
    kv("输出", `${terminal.fullLength ?? output.length} 字符 / ${body.totalLines} 行`, options),
  ];
  if (truncated) meta.push(style(options, "warn", `[输出已截断：仅显示最近 ${maxLines} 行、${maxChars} 字符]`));
  const bodyLines = (output || "（无输出）").split("\n");
  return [
    frame("终端视图", meta, options),
    frame("输出", bodyLines.map((line) => truncateToWidth(line, Math.max(MIN_WIDTH, (options.width ?? DEFAULT_WIDTH) - 2))), options),
    style(options, "muted", `提示: /save-terminal ${terminal.id} <path> 保存输出`),
    style(options, "muted", `提示: 在外部终端打开 ${terminal.id}（本版本只给出命令提示，不自动执行）`),
  ].join("\n");
}

export function renderDiff(artifact, options = {}) {
  const { maxLines = 200 } = options;
  if (!artifact) return "Diff 不可用";
  if (artifact.kind !== "diff" && !artifact.diff) return "该成果不是 Diff";
  const body = truncateLines(artifact.diff ?? "", maxLines);
  const meta = artifact.meta ?? {};
  const metaLines = [
    kv("Diff", safeText(artifact.name), options),
    style(options, "muted", sourceLabel(artifact)),
    kv("大小", formatBytes(artifact.size), options),
    kv("SHA-256", artifact.sha256 ?? "未知", options),
    kv("变更", `+${meta.additions ?? "?"} -${meta.deletions ?? "?"} hunks=${meta.hunks ?? "?"}`, options),
  ];
  if (body.truncated || artifact.diffTruncated) metaLines.push(style(options, "warn", `[Diff 已截断：显示最近 ${maxLines} 行]`));
  const width = Math.max(MIN_WIDTH, (options.width ?? DEFAULT_WIDTH) - 2);
  const bodyLines = (body.text || "（空 Diff）").split("\n").map((line) => truncateToWidth(line, width));
  return [
    frame("Diff 视图", metaLines, options),
    frame("变更内容", bodyLines, options),
    style(options, "muted", `提示: /save-artifact ${artifact.id} <path> 保存 Diff`),
  ].join("\n");
}

export function renderArtifacts(projection, options = {}) {
  const { sessionId = null } = options;
  const artifacts = (projection?.artifacts ?? []).filter((a) => !sessionId || a.sessionId === sessionId);
  if (!artifacts.length) return style(options, "muted", "成果: 无");
  return frame(
    "成果",
    artifacts.map(
      (a) =>
        `${style(options, "tool", "⚙")} ${a.id}  ${safeText(a.name)}  ${style(options, "muted", `[${a.kind}]`)}  ${formatBytes(a.size)}`,
    ),
    options,
  );
}

/** 状态总览：头部 + 概览卡片 + 会话列表 + 选中会话详情 + 待处理。 */
export function renderStatus(projection, options = {}) {
  const { selectedSessionId = null } = options;
  const counts = projection?.counts ?? {};
  const conn = projection?.connection?.status ?? "unknown";
  const overview = [
    kv("连接", `${style(options, conn === "ready" ? "success" : conn === "error" ? "error" : "warn", conn)} · seq ${projection?.connection?.seq ?? 0}`, options),
    kv("协议", `v${projection?.protocol?.version ?? "?"} / 核心 ${projection?.protocol?.coreVersion ?? "?"}`, options),
    kv("计数", `会话 ${counts.sessions ?? 0} · 消息 ${counts.messages ?? 0} · 待处理 ${counts.pendingInteractions ?? 0} · 终端 ${counts.terminals ?? 0} · 成果 ${counts.artifacts ?? 0}`, options),
    kv("模型", `${projection?.models?.current ?? "未选"}（可选 ${(projection?.models?.options ?? []).map((o) => o.id).join(", ") || "无"}）`, options),
  ];
  if (projection?.connection?.error) {
    overview.push(
      `${style(options, "error", "错误:")} ${projection.connection.error.code} ${safeText(projection.connection.error.message)}`,
    );
  }
  const parts = [renderHeader(projection, options), frame("概览", overview, options), renderSessions(projection, options)];
  if (selectedSessionId) {
    parts.push(
      section("会话", renderSessionHeader(projection, selectedSessionId, options).split("\n"), options),
      renderInteractions(projection, { ...options, sessionId: selectedSessionId }),
    );
  } else {
    parts.push(renderInteractions(projection, options));
  }
  return parts.join("\n");
}

/** 页脚提示条：待处理/排队计数 + 快捷键（窄终端自动缩短）。 */
export function renderFooter(projection, options = {}) {
  const counts = projection?.counts ?? {};
  const mode = options.mode ?? "提示";
  const parts = [
    style(options, "accent", "◈"),
    style(options, "muted", `${mode}`),
    style(options, "muted", `会话 ${counts.sessions ?? 0} · 消息 ${counts.messages ?? 0}`),
    counts.pendingInteractions ? style(options, "warn", `待处理 ${counts.pendingInteractions}`) : "",
    style(options, "muted", options.width >= 100 ? "/help 命令 · /status 状态 · /quit 退出" : "/help · /quit"),
  ].filter(Boolean);
  return style(options, "line", "╰─ ") + parts.join(style(options, "line", "  ·  ")) + style(options, "line", " ─╯");
}

export function renderMessageAttachments(projection, sessionId, options = {}) {
  const messages = (projection?.messages ?? []).filter((m) => m.sessionId === sessionId && (m.attachments ?? []).length);
  if (!messages.length) return "附件: 无";
  return frame(
    "附件",
    messages.flatMap((m) => [`${style(options, "muted", m.id)} (${m.role})`, renderAttachments(m, options)]),
    options,
  );
}

/** 帮助：按「会话 / 回合 / 视图 / 协作」分组的命令表。 */
export function renderHelp(options = {}) {
  const groups = [
    ["会话", [
      ["/sessions", "列出会话"],
      ["/use <sessionId>", "切换会话"],
      ["/new", "新建会话"],
    ]],
    ["回合", [
      ["<text> 或 /send <text>", "发送"],
      ["/queue <text>", "排队（当前回合结束后执行）"],
      ["/steer <text>", "插话（不支持时核心降级）"],
      ["/stop", "停止当前回合"],
      ["/answer <intId> <opt> [text]", "回答审批/提问"],
    ]],
    ["视图", [
      ["/status", "状态总览"],
      ["/messages [limit]", "会话记录"],
      ["/terminal <termId>", "终端视图"],
      ["/diff <artifactId>", "Diff 视图"],
      ["/artifacts", "成果列表"],
      ["/model <modelId>", "选择模型"],
    ]],
    ["协作", [
      ["/save-terminal <id> <path>", "保存终端输出"],
      ["/save-artifact <id> <path>", "保存成果"],
      ["/quit", "退出"],
    ]],
  ];
  const lines = [];
  for (const [group, rows] of groups) {
    lines.push(style(options, "accent", `▸ ${group}`));
    for (const [command, desc] of rows) {
      lines.push(`  ${padToWidth(style(options, "ink", command), 34)}${style(options, "muted", desc)}`);
    }
    lines.push("");
  }
  return frame("命令与快捷键", lines.slice(0, -1), options);
}
