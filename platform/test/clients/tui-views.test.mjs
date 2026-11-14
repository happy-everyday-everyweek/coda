/** TUI 视图层：布局/宽度/颜色/注入防护（参考 dsh-TUI 视觉组织）。 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  displayWidth,
  padToWidth,
  renderHelp,
  renderMessages,
  renderSessions,
  renderStatus,
  resolveUiOptions,
  safeText,
  truncateToWidth,
} from "../../clients/tui/views.mjs";

const PROJECTION = {
  connection: { status: "ready", seq: 12 },
  protocol: { version: 1, coreVersion: "0.1.0" },
  counts: { sessions: 2, messages: 4, pendingInteractions: 1, terminals: 0, artifacts: 0 },
  models: { current: "m1", options: [{ id: "m1" }, { id: "m2" }] },
  sessions: [
    { id: "ses_local", title: "本地会话", state: "idle", agent: "Codex", model: "m1", workspace: "/work/demo", environment: "host", node: "node_local" },
    { id: "ses_remote", title: "远程会话", state: "running", agent: "Claude", model: "m2", workspace: "/home/dev/demo", environment: "wsl:Ubuntu", node: "node_remote" },
  ],
  messages: [
    { id: "msg1", sessionId: "ses_local", role: "user", text: "你好" },
    { id: "msg2", sessionId: "ses_local", role: "assistant", text: "这是回复", streaming: true },
  ],
  interactions: [{ id: "int1", sessionId: "ses_local", kind: "approval", prompt: "允许执行吗？", status: "pending", options: [{ id: "allow", label: "允许" }] }],
  terminals: [],
  artifacts: [],
};

test("UI 选项：NO_COLOR 关闭颜色，宽度下限 40，主题可选", () => {
  const plain = resolveUiOptions({ color: false, width: 100 });
  assert.equal(plain.color, false);
  assert.equal(plain.palette, null);
  assert.equal(plain.width, 100);
  const auto = resolveUiOptions({ isTTY: true, env: { NO_COLOR: "1", COLUMNS: "120" } });
  assert.equal(auto.color, false);
  assert.equal(auto.width, 120);
  const narrow = resolveUiOptions({ color: false, width: 10 });
  assert.equal(narrow.width, 40);
  assert.ok(resolveUiOptions({ color: true, isTTY: true, env: {} }).palette);
});

test("宽度工具：CJK 记 2 列，截断与对齐", () => {
  assert.equal(displayWidth("abc"), 3);
  assert.equal(displayWidth("中文"), 4);
  assert.equal(displayWidth("\u001b[31mred\u001b[0m"), 3);
  assert.equal(truncateToWidth("中文中文", 5), "中文…");
  assert.equal(displayWidth(padToWidth("中文", 6)), 6);
});

test("注入防护：安全文本剥离 ANSI/控制字符", () => {
  assert.equal(safeText("\u001b[31m红\u001b[0m"), "红");
  assert.equal(safeText("a\u0007b"), "ab");
});

test("状态视图：头部药丸 + 框架 + 远程节点标记，无颜色时无 ANSI", () => {
  const text = renderStatus(PROJECTION, { color: false, width: 100, selectedSessionId: "ses_local" });
  assert.match(text, /◈ Coda/);
  assert.match(text, /● ready/);
  assert.match(text, /╭─ 概览/);
  assert.match(text, /远程节点 node_remote/);
  assert.match(text, /待处理/);
  assert.doesNotMatch(text, /\u001b\[/);
});

test("会话列表：窄终端丢列，宽终端显示 Agent/工作区", () => {
  const wide = renderSessions(PROJECTION, { color: false, width: 110, selectedSessionId: "ses_local" });
  assert.match(wide, /Codex \/ m1/);
  assert.match(wide, /\/work\/demo/);
  const narrow = renderSessions(PROJECTION, { color: false, width: 50 });
  assert.doesNotMatch(narrow, /Codex \/ m1/);
  assert.match(narrow, /本地会话/);
});

test("消息时间线：角色标记、流式指示、长行按宽度换行", () => {
  const long = { ...PROJECTION, messages: [{ id: "m", sessionId: "ses_local", role: "assistant", text: "长文本".repeat(30), streaming: true }] };
  const text = renderMessages(long, "ses_local", { color: false, width: 60 });
  assert.match(text, /◆ Coda/);
  assert.match(text, /▍/);
  for (const line of text.split("\n")) assert.ok(displayWidth(line) <= 60, `行超宽: ${line}`);
  const user = renderMessages(PROJECTION, "ses_local", { color: false, width: 60 });
  assert.match(user, /❯ 你/);
});

test("帮助：分组命令表", () => {
  const help = renderHelp({ color: false, width: 90 });
  assert.match(help, /▸ 会话/);
  assert.match(help, /▸ 回合/);
  assert.match(help, /\/queue/);
  assert.match(help, /╭─ 命令与快捷键/);
});
