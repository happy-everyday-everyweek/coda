/**
 * TUI 文本视图。输入一律是共享 canonical 投影，不包含任何 RPC / 核心逻辑。
 *
 * 终端注入防护：核心提供的文本可能是不可信内容，渲染前去掉 ANSI 控制序列，
 * 只保留换行与制表符。
 */

const ANSI_PATTERN =
  // eslint-disable-next-line no-control-regex
  /[\u001B\u009B](?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~]|\][^\u0007]*(?:\u0007|\u001B\\))/g;
// eslint-disable-next-line no-control-regex
const CONTROL_PATTERN = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g;

export function safeText(value) {
  return String(value ?? "")
    .replace(ANSI_PATTERN, "")
    .replace(CONTROL_PATTERN, "");
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

export function renderSessions(projection, { selectedSessionId = null } = {}) {
  const lines = ["会话:"];
  if (!projection?.sessions?.length) {
    lines.push("  （无会话）");
    return lines.join("\n");
  }
  for (const s of projection.sessions) {
    const marker = s.id === selectedSessionId ? "*" : " ";
    const remote = s.environment && s.environment !== "host" ? ` [远程节点 ${s.node ?? "?"}]` : "";
    lines.push(
      `  ${marker} ${s.id}  ${s.title ?? "(未命名)"}  [${stateBadge(s.state)}]  ` +
        `${s.agent ?? "未知 Agent"} / ${s.model ?? "未选模型"}${remote}`,
    );
  }
  return lines.join("\n");
}

export function renderSessionHeader(projection, sessionId, { localNode = null } = {}) {
  const s = projection?.sessions?.find((x) => x.id === sessionId);
  if (!s) return "（未选择会话）";
  return [
    `${s.title ?? "(未命名会话)"}  [${stateBadge(s.state)}]`,
    `  Agent: ${s.agent ?? "未知"}  模型: ${s.model ?? "未选"}  工作区: ${s.workspace ?? "未知"}`,
    `  ${sourceLabel(s, { localNode })}`,
    `  ${renderCapabilities(s.capabilities)}`,
    s.turnState ? `  回合: ${s.turnState}` : null,
  ]
    .filter(Boolean)
    .join("\n");
}

export function renderAttachments(message) {
  const attachments = message?.attachments ?? [];
  if (!attachments.length) return "";
  return attachments
    .map((a) => `    ↳ 附件: ${safeText(a.name)} (${formatBytes(a.size)})${a.artifactId ? ` [${a.artifactId}]` : ""}`)
    .join("\n");
}

export function renderMessages(projection, sessionId, { limit = 200 } = {}) {
  const all = (projection?.messages ?? []).filter((m) => m.sessionId === sessionId);
  const messages = all.slice(-limit);
  if (!messages.length) return "（暂无消息）";
  const lines = [];
  if (all.length > messages.length) lines.push(`（仅显示最近 ${limit} 条，共 ${all.length} 条；使用 /history all 查看全部）`);
  for (const m of messages) {
    const who = m.role === "user" ? "你" : m.role === "assistant" ? "Agent" : m.role;
    const streaming = m.streaming ? " …" : "";
    lines.push(`[${who}]${streaming} ${safeText(m.text)}`);
    const attachments = renderAttachments(m);
    if (attachments) lines.push(attachments);
  }
  return lines.join("\n");
}

export function renderInteractions(projection, { sessionId = null } = {}) {
  const pending = (projection?.interactions ?? []).filter(
    (i) => i.status === "pending" && (!sessionId || i.sessionId === sessionId),
  );
  if (!pending.length) return "待处理审批/提问: 无";
  const lines = ["待处理审批/提问:"];
  for (const i of pending) {
    lines.push(`  ${i.id}  [${i.kind}]  ${safeText(i.prompt)}`);
    if (i.options?.length) {
      lines.push(`    选项: ${i.options.map((o) => `${o.id}=${safeText(o.label)}`).join("  ")}`);
    } else {
      lines.push("    选项: （自由文本）");
    }
    if (i.expiresAt) lines.push(`    过期: ${i.expiresAt}`);
  }
  return lines.join("\n");
}

export function renderTerminalList(projection, { sessionId = null } = {}) {
  const terminals = (projection?.terminals ?? []).filter((t) => !sessionId || t.sessionId === sessionId);
  if (!terminals.length) return "终端: 无";
  return [
    "终端:",
    ...terminals.map(
      (t) =>
        `  ${t.id}  ${safeText(t.title)}  [${t.status}]  ${sourceLabel(t)}  ` +
        `${t.truncated ? "（输出已截断）" : ""}`,
    ),
  ].join("\n");
}

export function renderTerminal(terminal, { maxLines = 20, maxChars = 12_000 } = {}) {
  if (!terminal) return "终端不可用（可能已关闭或尚未打开）";
  const body = truncateLines(terminal.output ?? "", maxLines);
  let output = body.text;
  let charTruncated = false;
  if (output.length > maxChars) {
    output = output.slice(output.length - maxChars);
    charTruncated = true;
  }
  const truncated = terminal.truncated || body.truncated || charTruncated;
  const lines = [
    `终端 ${terminal.id} · ${safeText(terminal.title)} · [${terminal.status}]`,
    `  ${sourceLabel(terminal)}`,
    `  输出: ${terminal.fullLength ?? output.length} 字符 / ${body.totalLines} 行`,
  ];
  if (truncated) {
    lines.push(`  [输出已截断：仅显示最近 ${maxLines} 行、${maxChars} 字符]`);
  }
  lines.push("---");
  lines.push(output || "（无输出）");
  lines.push("---");
  lines.push(`提示: 使用 /save-terminal ${terminal.id} <path> 保存输出`);
  lines.push(`提示: 在外部终端打开 ${terminal.id}（本版本只给出命令提示，不自动执行）`);
  return lines.join("\n");
}

export function renderDiff(artifact, { maxLines = 200 } = {}) {
  if (!artifact) return "Diff 不可用";
  if (artifact.kind !== "diff" && !artifact.diff) return "该成果不是 Diff";
  const body = truncateLines(artifact.diff ?? "", maxLines);
  const meta = artifact.meta ?? {};
  const lines = [
    `Diff: ${safeText(artifact.name)}`,
    `  ${sourceLabel(artifact)}`,
    `  大小: ${formatBytes(artifact.size)}  SHA-256: ${artifact.sha256 ?? "未知"}`,
    `  变更: +${meta.additions ?? "?"} -${meta.deletions ?? "?"} hunks=${meta.hunks ?? "?"}`,
  ];
  if (body.truncated || artifact.diffTruncated) lines.push(`  [Diff 已截断：显示最近 ${maxLines} 行]`);
  lines.push("---");
  lines.push(body.text || "（空 Diff）");
  lines.push("---");
  lines.push(`提示: 使用 /save-artifact ${artifact.id} <path> 保存 Diff`);
  lines.push(`提示: 下载地址 /api/artifacts/${artifact.id}/download`);
  return lines.join("\n");
}

export function renderArtifacts(projection, { sessionId = null } = {}) {
  const artifacts = (projection?.artifacts ?? []).filter((a) => !sessionId || a.sessionId === sessionId);
  if (!artifacts.length) return "成果: 无";
  return [
    "成果:",
    ...artifacts.map(
      (a) =>
        `  ${a.id}  ${safeText(a.name)}  [${a.kind}]  ${formatBytes(a.size)}  ${sourceLabel(a)}`,
    ),
  ].join("\n");
}

export function renderStatus(projection, { selectedSessionId = null } = {}) {
  const counts = projection?.counts ?? {};
  const lines = [
    "Coda 状态",
    `  协议: v${projection?.protocol?.version ?? "?"} / 核心 ${projection?.protocol?.coreVersion ?? "?"}  ` +
      `连接: ${projection?.connection?.status ?? "unknown"}  seq: ${projection?.connection?.seq ?? 0}`,
    `  会话 ${counts.sessions ?? 0} · 消息 ${counts.messages ?? 0} · 待审批/提问 ${counts.pendingInteractions ?? 0} · ` +
      `终端 ${counts.terminals ?? 0} · 成果 ${counts.artifacts ?? 0}`,
    `  模型: ${projection?.models?.current ?? "未选"}（可选 ${(projection?.models?.options ?? []).map((o) => o.id).join(", ") || "无"}）`,
  ];
  if (projection?.connection?.error) {
    lines.push(`  错误: ${projection.connection.error.code} ${safeText(projection.connection.error.message)}`);
  }
  lines.push("", renderSessions(projection, { selectedSessionId }));
  if (selectedSessionId) {
    lines.push("", renderSessionHeader(projection, selectedSessionId), "", renderInteractions(projection, { sessionId: selectedSessionId }));
  } else {
    lines.push("", renderInteractions(projection));
  }
  return lines.join("\n");
}

export function renderMessageAttachments(projection, sessionId) {
  const messages = (projection?.messages ?? []).filter((m) => m.sessionId === sessionId && (m.attachments ?? []).length);
  if (!messages.length) return "附件: 无";
  return [
    "附件:",
    ...messages.flatMap((m) => [
      `  ${m.id} (${m.role})`,
      renderAttachments(m),
    ]),
  ].join("\n");
}

export function renderHelp() {
  return [
    "命令:",
    "  /sessions                 列出会话",
    "  /use <sessionId>          切换会话",
    "  /send <text>              发送（也可直接输入文本）",
    "  /queue <text>             排队发送",
    "  /steer <text>             插话（能力不支持时核心会降级）",
    "  /stop                     停止当前回合",
    "  /answer <intId> <opt> [text]  回答审批/提问",
    "  /model <modelId>          选择模型",
    "  /status                   状态",
    "  /terminal <termId>        终端视图",
    "  /diff <artifactId>        Diff 视图",
    "  /artifacts                成果列表",
    "  /save-terminal <id> <path> 保存终端输出",
    "  /save-artifact <id> <path> 保存成果",
    "  /quit                     退出",
  ].join("\n");
}
