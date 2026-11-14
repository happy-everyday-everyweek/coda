/** 分屏布局模型：窗格增删、方向切换、会话归属与多窗口地址。 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  MAX_PANES,
  activePane,
  closePane,
  createLayout,
  paneForSession,
  popoutUrl,
  setActive,
  setPaneSession,
  splitPane,
} from "../../clients/web/public/layout.mjs";

test("初始布局只有一个窗格，且是活动窗格", () => {
  const layout = createLayout("ses_local");
  assert.equal(layout.panes.length, 1);
  assert.equal(activePane(layout).sessionId, "ses_local");
  assert.equal(layout.orientation, "row");
});

test("左右与上下分屏都新开窗格并继承会话", () => {
  const base = createLayout("s1");
  const row = splitPane(base, "p1", { orientation: "row" });
  assert.equal(row.panes.length, 2);
  assert.equal(row.orientation, "row");
  assert.equal(activePane(row).sessionId, "s1");

  const column = splitPane(base, "p1", { orientation: "column", sessionId: "s2" });
  assert.equal(column.orientation, "column");
  assert.equal(activePane(column).sessionId, "s2");
});

test("分屏不超过上限", () => {
  let layout = createLayout("s1");
  for (let i = 0; i < 8; i += 1) layout = splitPane(layout, "p1");
  assert.equal(layout.panes.length, MAX_PANES);
});

test("关闭窗格时不让屏幕变空", () => {
  const one = createLayout("s1");
  assert.equal(closePane(one, "p1").panes.length, 1);

  const two = splitPane(one, "p1");
  const closed = closePane(two, "p1");
  assert.equal(closed.panes.length, 1);
  assert.equal(closed.activeId, closed.panes[0].id);
});

test("切换窗格会话与查找会话归属", () => {
  const two = splitPane(createLayout("s1"), "p1", { sessionId: "s2" });
  const moved = setPaneSession(two, "p1", "s3");
  assert.equal(paneForSession(moved, "s3").id, "p1");
  assert.equal(paneForSession(moved, "s1"), null);
  assert.equal(setActive(moved, "p2").activeId, "p2");
  assert.equal(setActive(moved, "nope").activeId, moved.activeId);
});

test("多窗口地址带 session 且不带 token", () => {
  globalThis.location = { href: "http://127.0.0.1:8080/?token=secret" };
  assert.equal(popoutUrl("s9"), "/?session=s9");
  globalThis.location = { href: "http://127.0.0.1:8080/?session=s9" };
  assert.equal(popoutUrl(null), "/");
});