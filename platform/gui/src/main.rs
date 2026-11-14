//! Coda 桌面 GUI（GPUI）。
//!
//! 布局参照 Zed：左侧半透明侧边栏放会话与新建入口，中间是浮起的会话卡片，
//! 支持左右/上下分屏（最多 4 格）与把某个会话拆成独立窗口。
//!
//! 这一版先把「壳 + 布局 + 多窗格 + 多窗口」跑通并通过 CI 出帧验证，
//! 核心连接与真实会话数据在后续提交里接上（见 docs/GUI.md 的进度表）。

use gpui::{
    App, Bounds, Context, Window, WindowBounds, WindowOptions, div, prelude::*, px, rgb, rgba, size,
};
use gpui_platform::application;
use std::sync::atomic::{AtomicBool, Ordering};

static RENDERED: AtomicBool = AtomicBool::new(false);

const MAX_PANES: usize = 4;

#[derive(Clone)]
struct SessionView {
    id: String,
    title: String,
    state: &'static str,
    agent: String,
    model: String,
}

impl SessionView {
    fn demo(index: usize) -> Self {
        let titles = ["检查构建失败原因", "在 WSL 里跑构建", "整理发布清单", "审阅节点桥协议"];
        Self {
            id: format!("ses_{index}"),
            title: titles[index % titles.len()].to_string(),
            state: if index % 3 == 1 { "running" } else { "idle" },
            agent: if index % 2 == 0 { "Claude Code" } else { "Codex" }.to_string(),
            model: if index % 2 == 0 { "claude-sonnet" } else { "gpt-5-codex" }.to_string(),
        }
    }
}

#[derive(Clone)]
struct PaneView {
    id: String,
    session: SessionView,
}

struct CodaGui {
    sessions: Vec<SessionView>,
    panes: Vec<PaneView>,
    active: usize,
    orientation_row: bool,
}

impl CodaGui {
    fn new() -> Self {
        let sessions = (0..4).map(SessionView::demo).collect::<Vec<_>>();
        let panes = vec![PaneView {
            id: "p1".to_string(),
            session: sessions[0].clone(),
        }];
        Self {
            sessions,
            panes,
            active: 0,
            orientation_row: true,
        }
    }

    fn next_pane_id(&self) -> String {
        let mut index = 1;
        while self.panes.iter().any(|pane| pane.id == format!("p{index}")) {
            index += 1;
        }
        format!("p{index}")
    }

    fn split(&mut self, orientation_row: bool) {
        if self.panes.len() >= MAX_PANES {
            return;
        }
        let source = self.panes[self.active].session.clone();
        let id = self.next_pane_id();
        self.panes.push(PaneView {
            id,
            session: source,
        });
        self.active = self.panes.len() - 1;
        self.orientation_row = orientation_row;
    }

    fn close_active(&mut self) {
        if self.panes.len() <= 1 {
            return;
        }
        self.panes.remove(self.active);
        self.active = self.active.min(self.panes.len() - 1);
    }

    fn new_session(&mut self) {
        let session = SessionView::demo(self.sessions.len());
        self.sessions.push(session.clone());
        self.panes[self.active].session = session;
    }

    fn select(&mut self, session: SessionView) {
        self.panes[self.active].session = session;
    }

    fn pane_card(&self, index: usize, cx: &mut Context<Self>) -> impl IntoElement {
        let pane = self.panes[index].clone();
        let is_active = index == self.active;
        let state_color = if pane.session.state == "running" {
            rgb(0x8bd5a0)
        } else {
            rgb(0x8a93a6)
        };

        div()
            .id(("pane", index))
            .flex()
            .flex_col()
            .flex_1()
            .h_full()
            .bg(rgba(0x1b2233cc))
            .rounded_lg()
            .border_1()
            .border_color(if is_active {
                rgba(0x6e8bff66)
            } else {
                rgba(0xffffff14)
            })
            .overflow_hidden()
            .child(
                div()
                    .flex()
                    .flex_row()
                    .items_center()
                    .justify_between()
                    .px_4()
                    .py_3()
                    .border_b_1()
                    .border_color(rgba(0xffffff10))
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap_1()
                            .flex_1()
                            .child(
                                div()
                                    .text_sm()
                                    .text_color(rgb(0xe6ebf5))
                                    .child(pane.session.title.clone()),
                            )
                            .child(
                                div()
                                    .text_xs()
                                    .text_color(rgb(0x7f8ba3))
                                    .child(format!(
                                        "{} · {} · {}",
                                        pane.session.agent, pane.session.model, pane.session.state
                                    )),
                            ),
                    )
                    .child(
                        div()
                            .flex()
                            .flex_row()
                            .items_center()
                            .gap_2()
                            .child(div().size_2().rounded_full().bg(state_color))
                            .child(self.pane_button("左右", index, cx, |app| app.split(true)))
                            .child(self.pane_button("上下", index, cx, |app| app.split(false)))
                            .child(self.pane_button("新窗口", index, cx, |app| app.popout(index, cx)))
                            .child(self.pane_button("关闭", index, cx, |app| app.close_pane(index))),
                    ),
            )
            .child(
                div()
                    .id(("pane-body", index))
                    .flex()
                    .flex_col()
                    .flex_1()
                    .gap_3()
                    .p_4()
                    .overflow_y_scroll()
                    .child(self.bubble("user", "请检查构建失败原因"))
                    .child(self.bubble("assistant", "我先看了一下日志：编译器在 shader 阶段失败，稍后给出补丁。"))
                    .child(self.bubble("assistant", "补丁已生成，点右侧「成果」可查看 diff。"))
                    .child(self.bubble("user", "跑一遍测试确认"))
                    .child(self.bubble("assistant", "测试通过 387 项，没有新增失败。")),
            )
            .child(
                div()
                    .flex()
                    .flex_row()
                    .items_center()
                    .gap_2()
                    .px_3()
                    .py_2()
                    .border_t_1()
                    .border_color(rgba(0xffffff10))
                    .child(
                        div()
                            .flex_1()
                            .px_3()
                            .py_2()
                            .rounded_md()
                            .bg(rgba(0x0f1522aa))
                            .text_sm()
                            .text_color(rgb(0x6f7a90))
                            .child("输入任务；Ctrl+Enter 发送"),
                    )
                    .child(self.small_button("发送"))
                    .child(self.small_button("排队"))
                    .child(self.small_button("插话"))
                    .child(self.small_button("停止")),
            )
    }

    fn pane_button(
        &self,
        label: &'static str,
        index: usize,
        cx: &mut Context<Self>,
        action: fn(&mut Self),
    ) -> impl IntoElement {
        let _ = index;
        div()
            .id(("pane-action", label, index))
            .px_2()
            .py_1()
            .rounded_md()
            .bg(rgba(0xffffff0d))
            .text_xs()
            .text_color(rgb(0xb9c2d4))
            .cursor_pointer()
            .hover(|style| style.bg(rgba(0xffffff1a)))
            .child(label)
            .on_click(cx.listener(move |this, _event, _window, cx| {
                action(this);
                cx.notify();
            }))
    }

    fn close_pane(&mut self, index: usize) {
        if self.panes.len() <= 1 {
            return;
        }
        self.panes.remove(index);
        self.active = self.active.min(self.panes.len() - 1);
    }

    fn popout(&mut self, index: usize, cx: &mut Context<Self>) {
        let pane = self.panes[index].clone();
        let bounds = Bounds::centered(None, size(px(720.0), px(520.0)), cx);
        let handle = cx.open_window(
            WindowOptions {
                window_bounds: Some(WindowBounds::Windowed(bounds)),
                ..Default::default()
            },
            move |_window, cx| {
                cx.new(|_| CodaGui {
                    sessions: vec![pane.session.clone()],
                    panes: vec![pane.clone()],
                    active: 0,
                    orientation_row: true,
                })
            },
        );
        if handle.is_err() {
            eprintln!("open_window for pane failed");
        }
    }

    fn bubble(&self, role: &str, text: &str) -> impl IntoElement {
        let bg = if role == "user" {
            rgba(0x2b3756cc)
        } else {
            rgba(0x1a2233cc)
        };
        div().flex().flex_col().w_full().child(
            div()
                .max_w(px(560.0))
                .px_3()
                .py_2()
                .rounded_md()
                .bg(bg)
                .text_sm()
                .text_color(rgb(0xdbe3f2))
                .child(text.to_string()),
        )
    }

    fn small_button(&self, label: &'static str) -> impl IntoElement {
        div()
            .px_3()
            .py_2()
            .rounded_md()
            .bg(rgba(0xffffff0d))
            .text_xs()
            .text_color(rgb(0xb9c2d4))
            .cursor_pointer()
            .child(label)
    }
}

impl Render for CodaGui {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        RENDERED.store(true, Ordering::SeqCst);
        let sessions = self.sessions.clone();
        let active_session = self.panes[self.active].session.id.clone();

        let sidebar = div()
            .flex()
            .flex_col()
            .w(px(272.0))
            .h_full()
            .bg(rgba(0x111726aa))
            .rounded_lg()
            .border_1()
            .border_color(rgba(0xffffff12))
            .child(
                div()
                    .flex()
                    .flex_row()
                    .items_center()
                    .justify_between()
                    .px_4()
                    .py_3()
                    .child(
                        div()
                            .text_xs()
                            .text_color(rgb(0x7f8ba3))
                            .child("会话"),
                    )
                    .child(
                        div()
                            .id("new-session")
                            .px_2()
                            .rounded_md()
                            .bg(rgba(0xffffff12))
                            .text_sm()
                            .text_color(rgb(0xdbe3f2))
                            .cursor_pointer()
                            .child("＋")
                            .on_click(cx.listener(|this, _event, _window, cx| {
                                this.new_session();
                                cx.notify();
                            })),
                    ),
            )
            .child(
                div()
                    .id("session-list")
                    .flex()
                    .flex_col()
                    .gap_1()
                    .p_2()
                    .flex_1()
                    .overflow_y_scroll()
                    .children(sessions.into_iter().enumerate().map(|(index, session)| {
                        let selected = session.id == active_session;
                        let target = session.clone();
                        div()
                            .id(("session", index))
                            .flex()
                            .flex_col()
                            .gap_1()
                            .px_3()
                            .py_2()
                            .rounded_md()
                            .bg(if selected {
                                rgba(0xffffff1a)
                            } else {
                                rgba(0x00000000)
                            })
                            .cursor_pointer()
                            .hover(|style| style.bg(rgba(0xffffff12)))
                            .child(
                                div()
                                    .text_sm()
                                    .text_color(rgb(0xe6ebf5))
                                    .child(session.title.clone()),
                            )
                            .child(
                                div()
                                    .text_xs()
                                    .text_color(rgb(0x7f8ba3))
                                    .child(format!("{} · {}", session.agent, session.state)),
                            )
                            .on_click(cx.listener(move |this, _event, _window, cx| {
                                this.select(target.clone());
                                cx.notify();
                            }))
                    })),
            )
            .child(
                div()
                    .flex()
                    .flex_row()
                    .gap_2()
                    .p_2()
                    .border_t_1()
                    .border_color(rgba(0xffffff10))
                    .child(self.small_button("左右分屏"))
                    .child(self.small_button("上下分屏")),
            );

        let panes = div()
            .id("panes")
            .flex()
            .flex_1()
            .h_full()
            .gap_3()
            .p_3()
            .when(!self.orientation_row, |el| el.flex_col())
            .children((0..self.panes.len()).map(|index| self.pane_card(index, cx)));

        div()
            .flex()
            .flex_row()
            .size_full()
            .gap_3()
            .p_3()
            .bg(rgb(0x0b0f18))
            .text_color(rgb(0xe6ebf5))
            .child(sidebar)
            .child(panes)
    }
}

fn main() {
    // CI 出帧验证：拿到第一帧就退出 0，避免事件循环把 job 拖到超时。
    std::thread::spawn(|| {
        std::thread::sleep(std::time::Duration::from_secs(20));
        if RENDERED.load(Ordering::SeqCst) {
            eprintln!("CODA_GUI_OK: window rendered");
            std::process::exit(0);
        }
        eprintln!("CODA_GUI_FAIL: no frame within 20s");
        std::process::exit(2);
    });
    application().run(|cx: &mut App| {
        let bounds = Bounds::centered(None, size(px(1180.0), px(760.0)), cx);
        cx.open_window(
            WindowOptions {
                window_bounds: Some(WindowBounds::Windowed(bounds)),
                ..Default::default()
            },
            |_window, cx| cx.new(|_| CodaGui::new()),
        )
        .expect("open_window");
        cx.activate(true);
    });
}