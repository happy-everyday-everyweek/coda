/**
 * 分屏布局模型（纯函数，供 Web 客户端与后续 GUI 复用）。
 *
 * 一屏最多 4 个窗格；同一时刻只有一个方向（左右 row / 上下 column），
 * 这样 CSS 只需要一条 flex-direction，不必维护任意嵌套的切分树。
 */
export const MAX_PANES = 4;

function paneId(layout) {
  let index = 1;
  while (layout.panes.some((pane) => pane.id === `p${index}`)) index += 1;
  return `p${index}`;
}

export function createLayout(sessionId = null) {
  return { panes: [{ id: "p1", sessionId }], activeId: "p1", orientation: "row" };
}

export function activePane(layout) {
  return layout.panes.find((pane) => pane.id === layout.activeId) ?? layout.panes[0] ?? null;
}

export function setActive(layout, paneIdValue) {
  if (!layout.panes.some((pane) => pane.id === paneIdValue)) return layout;
  return { ...layout, activeId: paneIdValue };
}

export function setPaneSession(layout, paneIdValue, sessionId) {
  return {
    ...layout,
    panes: layout.panes.map((pane) => (pane.id === paneIdValue ? { ...pane, sessionId } : pane)),
  };
}

/** 在 source 之后插入一个新窗格；达到上限时返回原布局。 */
export function splitPane(layout, sourceId, { sessionId = null, orientation = "row" } = {}) {
  if (layout.panes.length >= MAX_PANES) return layout;
  const source = layout.panes.find((pane) => pane.id === sourceId) ?? activePane(layout);
  if (!source) return layout;
  const next = { id: paneId(layout), sessionId: sessionId ?? source.sessionId };
  const index = layout.panes.findIndex((pane) => pane.id === source.id);
  const panes = [...layout.panes];
  panes.splice(index + 1, 0, next);
  return { panes, activeId: next.id, orientation };
}

/** 关掉一个窗格；只剩一个时不允许关，避免出现空屏。 */
export function closePane(layout, paneIdValue) {
  if (layout.panes.length <= 1) return layout;
  const panes = layout.panes.filter((pane) => pane.id !== paneIdValue);
  const activeId = layout.activeId === paneIdValue ? panes[panes.length - 1].id : layout.activeId;
  return { ...layout, panes, activeId };
}

export function paneForSession(layout, sessionId) {
  return layout.panes.find((pane) => pane.sessionId === sessionId) ?? null;
}

/** 多窗口：每个会话一个可独立打开的地址。 */
export function popoutUrl(sessionId) {
  const url = new URL(location.href);
  url.searchParams.delete("token");
  if (sessionId) url.searchParams.set("session", sessionId);
  else url.searchParams.delete("session");
  return `${url.pathname}${url.search}`;
}