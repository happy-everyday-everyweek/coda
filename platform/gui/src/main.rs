//! Coda 桌面 GUI（GPUI）——三端之一。
//!
//! 数据来源：核心的 Web API（`GET /api/state`、`POST /api/command`、`POST /api/answer`）。
//! GUI 不重写投影逻辑，投影由 `platform/clients/shared/projection.mjs` 生成，
//! 这样 GUI 与 Web UI 天然保持同一套能力面。
//!
//! 布局参照 Zed：左侧半透明侧边栏（会话 + 新建 + 分屏入口），中间浮起的会话卡片；
//! 最多 4 个窗格，支持左右/上下切分，并可把某个会话拆成独立窗口。

mod net;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use gpui::{
    AnyElement, App, Bounds, Context, FocusHandle, KeyDownEvent, Window, WindowBounds, WindowOptions,
    div,
    prelude::*, px, rgb, rgba, size,
};
use gpui_platform::application;
use serde_json::Value;

const MAX_PANES: usize = 4;

#[derive(Clone, Copy)]
enum PaneAction {
    SplitRow,
    SplitCol,
    Close(usize),
    Send,
    Stop,
}

/// CI 冒烟用：出现过至少一帧。
static RENDERED: AtomicBool = AtomicBool::new(false);

#[derive(Default, Clone)]
struct Remote {
    version: u64,
    error: Option<String>,
    projection: Value,
}

#[derive(Clone)]
struct Session {
    id: String,
    title: String,
    state: String,
    agent: String,
    model: String,
}

struct Pane {
    id: String,
    session_id: Option<String>,
}

struct CodaGui {
    base: String,
    token: String,
    shared: Arc<Mutex<Remote>>,
    seen_version: u64,
    focus: FocusHandle,
    input: String,
    panes: Vec<Pane>,
    active: usize,
    orientation_row: bool,
    keep: Vec<Box<dyn std::any::Any>>,
}

fn sessions_of(projection: &Value) -> Vec<Session> {
    projection
        .get("sessions")
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter_map(|item| {
                    let id = item.get("id")?.as_str()?.to_string();
                    Some(Session {
                        title: item
                            .get("title")
                            .and_then(Value::as_str)
                            .unwrap_or("(未命名会话)")
                            .to_string(),
                        state: item
                            .get("state")
                            .and_then(Value::as_str)
                            .unwrap_or("unknown")
                            .to_string(),
                        agent: item
                            .get("agent")
                            .and_then(Value::as_str)
                            .unwrap_or("未知 Agent")
                            .to_string(),
                        model: item
                            .get("model")
                            .and_then(Value::as_str)
                            .unwrap_or("未选模型")
                            .to_string(),
                        id,
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

fn messages_of(projection: &Value, session_id: &str) -> Vec<(String, String)> {
    projection
        .get("messages")
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter(|item| item.get("sessionId").and_then(Value::as_str) == Some(session_id))
                .map(|item| {
                    (
                        item.get("role").and_then(Value::as_str).unwrap_or("assistant").to_string(),
                        item.get("text").and_then(Value::as_str).unwrap_or("").to_string(),
                    )
                })
                .collect()
        })
        .unwrap_or_default()
}

fn pending_interaction(projection: &Value, session_id: &str) -> Option<(String, String, Vec<(String, String)>)> {
    let items = projection.get("interactions")?.as_array()?;
    let item = items.iter().find(|item| {
        item.get("sessionId").and_then(Value::as_str) == Some(session_id)
            && item.get("status").and_then(Value::as_str) == Some("pending")
    })?;
    let id = item.get("id")?.as_str()?.to_string();
    let prompt = item
        .get("prompt")
        .and_then(Value::as_str)
        .unwrap_or("待处理")
        .to_string();
    let options = item
        .get("options")
        .and_then(Value::as_array)
        .map(|list| {
            list.iter()
                .filter_map(|option| {
                    Some((
                        option.get("id")?.as_str()?.to_string(),
                        option
                            .get("label")
                            .and_then(Value::as_str)
                            .unwrap_or("选项")
                            .to_string(),
                    ))
                })
                .collect()
        })
        .unwrap_or_default();
    Some((id, prompt, options))
}

fn state_badge(state: &str) -> &'static str {
    match state {
        "idle" => "空闲",
        "running" => "运行中",
        "queued" => "排队中",
        "stopped" => "已停止",
        "failed" => "失败",
        _ => "未知",
    }
}

impl CodaGui {
    fn new(base: String, token: String, cx: &mut Context<Self>) -> Self {
        let shared = Arc::new(Mutex::new(Remote::default()));
        let focus = cx.focus_handle();
        let mut app = Self {
            base,
            token,
            shared: shared.clone(),
            seen_version: 0,
            focus,
            input: String::new(),
            panes: vec![Pane {
                id: "p1".to_string(),
                session_id: None,
            }],
            active: 0,
            orientation_row: true,
            keep: Vec::new(),
        };
        app.start_polling(shared, cx);
        app
    }

    fn start_polling(&mut self, shared: Arc<Mutex<Remote>>, cx: &mut Context<Self>) {
        let base = self.base.clone();
        let token = self.token.clone();
        let task = cx.spawn(async move |this, cx| {
            let mut version = 0u64;
            loop {
                cx.background_executor()
                    .timer(Duration::from_millis(1200))
                    .await;
                match net::get_state(&base, &token) {
                    Ok(projection) => {
                        version += 1;
                        if let Ok(mut guard) = shared.lock() {
                            guard.version = version;
                            guard.error = None;
                            guard.projection = projection;
                        }
                    }
                    Err(error) => {
                        if let Ok(mut guard) = shared.lock() {
                            guard.error = Some(error.to_string());
                        }
                    }
                }
                if this
                    .update(cx, |this, cx| {
                        let version = this.shared.lock().map(|guard| guard.version).unwrap_or(0);
                        if version != this.seen_version {
                            this.seen_version = version;
                            cx.notify();
                        }
                    })
                    .is_err()
                {
                    break;
                }
            }
        });
        self.keep.push(Box::new(task));
    }

    fn snapshot(&self) -> Remote {
        self.shared
            .lock()
            .map(|guard| guard.clone())
            .unwrap_or_default()
    }

    fn active_session(&self, sessions: &[Session]) -> Option<String> {
        let pane = self.panes.get(self.active)?;
        pane.session_id
            .clone()
            .or_else(|| sessions.first().map(|session| session.id.clone()))
    }

    fn select(&mut self, session_id: String) {
        if let Some(pane) = self.panes.get_mut(self.active) {
            pane.session_id = Some(session_id);
        }
    }

    fn split(&mut self, orientation_row: bool) {
        if self.panes.len() >= MAX_PANES {
            return;
        }
        let source = self.panes[self.active].session_id.clone();
        let mut index = 1;
        while self.panes.iter().any(|pane| pane.id == format!("p{index}")) {
            index += 1;
        }
        self.panes.push(Pane {
            id: format!("p{index}"),
            session_id: source,
        });
        self.active = self.panes.len() - 1;
        self.orientation_row = orientation_row;
    }

    fn close_pane(&mut self, index: usize) {
        if self.panes.len() <= 1 || index >= self.panes.len() {
            return;
        }
        self.panes.remove(index);
        self.active = self.active.min(self.panes.len() - 1);
    }

    fn create_session(&mut self, cx: &mut Context<Self>) {
        self.post("session.create", serde_json::json!({}), serde_json::json!({}));
        cx.notify();
    }

    fn post(&self, kind: &str, target: Value, payload: Value) {
        let base = self.base.clone();
        let token = self.token.clone();
        let kind = kind.to_string();
        std::thread::spawn(move || {
            if let Err(error) = net::post_command(&base, &token, &kind, target, payload) {
                eprintln!("command {kind} failed: {error}");
            }
        });
    }

    fn send_current(&mut self, cx: &mut Context<Self>) {
        let text = self.input.trim().to_string();
        if text.is_empty() {
            return;
        }
        let remote = self.snapshot();
        let sessions = sessions_of(&remote.projection);
        let Some(session_id) = self.active_session(&sessions) else {
            return;
        };
        self.post(
            "turn.send",
            serde_json::json!({ "sessionId": session_id }),
            serde_json::json!({ "text": text, "mode": "send" }),
        );
        self.input.clear();
        cx.notify();
    }

    fn stop_turn(&mut self) {
        let remote = self.snapshot();
        let sessions = sessions_of(&remote.projection);
        if let Some(session_id) = self.active_session(&sessions) {
            self.post(
                "turn.stop",
                serde_json::json!({ "sessionId": session_id }),
                serde_json::json!({}),
            );
        }
    }

    fn answer(&self, interaction_id: String, option_id: String) {
        let base = self.base.clone();
        let token = self.token.clone();
        std::thread::spawn(move || {
            if let Err(error) =
                net::post_answer(&base, &token, &interaction_id, Some(&option_id), None)
            {
                eprintln!("answer failed: {error}");
            }
        });
    }

    fn action_button(
        label: &'static str,
        index: usize,
        action: PaneAction,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        div()
            .id((label, index))
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
                match action {
                    PaneAction::SplitRow => this.split(true),
                    PaneAction::SplitCol => this.split(false),
                    PaneAction::Close(index) => this.close_pane(index),
                    PaneAction::Send => this.send_current(cx),
                    PaneAction::Stop => this.stop_turn(),
                }
                cx.notify();
            }))
            .into_any_element()
    }

    fn bubble(role: &str, text: &str) -> AnyElement {
        let bg = if role == "user" {
            rgba(0x2b3756cc)
        } else {
            rgba(0x1a2233cc)
        };
        div()
            .flex()
            .flex_col()
            .w_full()
            .child(
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
            .into_any_element()
    }

    fn session_row(session: &Session, selected: bool, cx: &mut Context<Self>) -> AnyElement {
        let id = session.id.clone();
        div()
            .id(format!("session-{id}"))
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
                    .child(format!("{} · {}", session.agent, state_badge(&session.state))),
            )
            .on_click(cx.listener(move |this, _event, _window, cx| {
                this.select(id.clone());
                cx.notify();
            }))
            .into_any_element()
    }
}

impl Render for CodaGui {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        RENDERED.store(true, Ordering::SeqCst);
        let remote = self.snapshot();
        let sessions = sessions_of(&remote.projection);
        let active_session = self.active_session(&sessions);
        let status = match &remote.error {
            Some(error) => format!("未连接：{error}"),
            None => format!("已连接 · {} 个会话", sessions.len()),
        };

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
                            .flex()
                            .flex_col()
                            .gap_1()
                            .child(div().text_xs().text_color(rgb(0x7f8ba3)).child("会话"))
                            .child(div().text_xs().text_color(rgb(0x5f6b82)).child(status)),
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
                                this.create_session(cx);
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
                    .children(
                        sessions
                            .iter()
                            .map(|session| {
                                Self::session_row(
                                    session,
                                    active_session.as_deref() == Some(session.id.as_str()),
                                    cx,
                                )
                            })
                            .collect::<Vec<_>>(),
                    ),
            )
            .child(
                div()
                    .flex()
                    .flex_row()
                    .gap_2()
                    .p_2()
                    .border_t_1()
                    .border_color(rgba(0xffffff10))
                    .child(Self::action_button("左右分屏", 0, PaneAction::SplitRow, cx))
                    .child(Self::action_button("上下分屏", 1, PaneAction::SplitCol, cx)),
            );

        let pane_elements: Vec<AnyElement> = (0..self.panes.len())
            .map(|index| self.pane_card(index, &remote, &sessions, cx))
            .collect();

        let panes = div()
            .id("panes")
            .flex()
            .flex_1()
            .h_full()
            .gap_3()
            .p_3()
            .when(!self.orientation_row, |el| el.flex_col())
            .children(pane_elements);

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

impl CodaGui {
    fn pane_card(
        &self,
        index: usize,
        remote: &Remote,
        sessions: &[Session],
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let pane = &self.panes[index];
        let session_id = pane
            .session_id
            .clone()
            .or_else(|| sessions.first().map(|session| session.id.clone()));
        let session = session_id
            .as_deref()
            .and_then(|id| sessions.iter().find(|session| session.id == id));
        let title = session
            .map(|session| session.title.clone())
            .unwrap_or_else(|| "未选择会话".to_string());
        let meta = session
            .map(|session| {
                format!(
                    "{} · {} · {}",
                    session.agent,
                    session.model,
                    state_badge(&session.state)
                )
            })
            .unwrap_or_else(|| "先点左侧会话，或按＋新建".to_string());
        let is_active = index == self.active;
        let messages = session_id
            .as_deref()
            .map(|id| messages_of(&remote.projection, id))
            .unwrap_or_default();
        let interaction = session_id
            .as_deref()
            .and_then(|id| pending_interaction(&remote.projection, id));

        let mut body = div()
            .id(("pane-body", index))
            .flex()
            .flex_col()
            .gap_3()
            .p_4()
            .flex_1()
            .overflow_y_scroll();
        if messages.is_empty() {
            body = body.child(
                div()
                    .text_sm()
                    .text_color(rgb(0x6f7a90))
                    .child("这个会话还没有消息"),
            );
        } else {
            for (role, text) in &messages {
                body = body.child(Self::bubble(role, text));
            }
        }

        let mut composer = div()
            .flex()
            .flex_row()
            .items_center()
            .gap_2()
            .px_3()
            .py_2()
            .border_t_1()
            .border_color(rgba(0xffffff10));
        let placeholder = self.input.is_empty();
        composer = composer.child(
            div()
                .id(("composer", index))
                .track_focus(&self.focus)
                .flex_1()
                .px_3()
                .py_2()
                .rounded_md()
                .bg(rgba(0x0f1522aa))
                .text_sm()
                .text_color(if placeholder {
                    rgb(0x6f7a90)
                } else {
                    rgb(0xe6ebf5)
                })
                .child(if placeholder {
                    "输入任务；Ctrl+Enter 发送".to_string()
                } else {
                    self.input.clone()
                })
                .on_click(cx.listener(|this, _event, window, cx| {
                    this.focus.focus(window, cx);
                }))
                .on_key_down(cx.listener(|this, event: &KeyDownEvent, _window, cx| {
                    let keystroke = &event.keystroke;
                    if keystroke.key == "enter"
                        && (keystroke.modifiers.control || keystroke.modifiers.platform)
                    {
                        this.send_current(cx);
                        return;
                    }
                    if keystroke.key == "backspace" {
                        this.input.pop();
                        cx.notify();
                        return;
                    }
                    if let Some(text) = &keystroke.key_char {
                        if !keystroke.modifiers.control && !keystroke.modifiers.platform {
                            this.input.push_str(text);
                            cx.notify();
                        }
                    }
                })),
        );
        composer = composer
            .child(Self::action_button("发送", index, PaneAction::Send, cx))
            .child(Self::action_button("停止", index, PaneAction::Stop, cx));

        if let Some((interaction_id, prompt, options)) = interaction {
            let mut card = div()
                .flex()
                .flex_col()
                .gap_2()
                .mx_3()
                .mt_2()
                .px_3()
                .py_2()
                .rounded_md()
                .bg(rgba(0x6e8bff1f))
                .child(
                    div()
                        .text_xs()
                        .text_color(rgb(0xdbe3f2))
                        .child(prompt),
                );
            for (option_id, label) in options {
                let interaction_id = interaction_id.clone();
                card = card.child(
                    div()
                        .id(format!("option-{index}-{option_id}"))
                        .px_2()
                        .py_1()
                        .rounded_md()
                        .bg(rgba(0xffffff14))
                        .text_xs()
                        .text_color(rgb(0xe6ebf5))
                        .cursor_pointer()
                        .child(label)
                        .on_click(cx.listener(move |this, _event, _window, _cx| {
                            this.answer(interaction_id.clone(), option_id.clone());
                        })),
                );
            }
            body = body.child(card);
        }

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
                            .child(div().text_sm().text_color(rgb(0xe6ebf5)).child(title))
                            .child(div().text_xs().text_color(rgb(0x7f8ba3)).child(meta)),
                    )
                    .child(
                        div()
                            .flex()
                            .flex_row()
                            .items_center()
                            .gap_2()
                            .child(Self::action_button("左右", index, PaneAction::SplitRow, cx))
                            .child(Self::action_button("上下", index, PaneAction::SplitCol, cx))
                            .child(
                                div()
                                    .id(("popout", index))
                                    .px_2()
                                    .py_1()
                                    .rounded_md()
                                    .bg(rgba(0xffffff0d))
                                    .text_xs()
                                    .text_color(rgb(0xb9c2d4))
                                    .cursor_pointer()
                                    .child("新窗口")
                                    .on_click(cx.listener(move |this, _event, _window, cx| {
                                        let session = this
                                            .panes
                                            .get(index)
                                            .and_then(|pane| pane.session_id.clone());
                                        let base = this.base.clone();
                                        let token = this.token.clone();
                                        cx.defer(move |app| {
                                            open_session_window(app, base, token, session);
                                        });
                                    })),
                            )
                            .child(Self::action_button("关闭", index, PaneAction::Close(index), cx)),
                    ),
            )
            .child(body)
            .child(composer)
            .into_any_element()
    }
}

fn open_session_window(app: &mut App, base: String, token: String, session_id: Option<String>) {
    let bounds = Bounds::centered(None, size(px(860.0), px(620.0)), app);
    let opened = app.open_window(
        WindowOptions {
            window_bounds: Some(WindowBounds::Windowed(bounds)),
            ..Default::default()
        },
        move |_window, cx| {
            cx.new(|cx| {
                let mut gui = CodaGui::new(base.clone(), token.clone(), cx);
                gui.panes[0].session_id = session_id.clone();
                gui
            })
        },
    );
    if let Err(error) = opened {
        eprintln!("open_window failed: {error}");
    }
}

fn main() {
    let mut base = "http://127.0.0.1:8787".to_string();
    let mut token = String::new();
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--base" => {
                if let Some(value) = args.next() {
                    base = value;
                }
            }
            "--token" => {
                if let Some(value) = args.next() {
                    token = value;
                }
            }
            _ => {}
        }
    }

    if std::env::var("CODA_GUI_SMOKE").as_deref() == Ok("1") {
        std::thread::spawn(|| {
            std::thread::sleep(Duration::from_secs(25));
            if RENDERED.load(Ordering::SeqCst) {
                eprintln!("CODA_GUI_OK: window rendered");
                std::process::exit(0);
            }
            eprintln!("CODA_GUI_FAIL: no frame within 25s");
            std::process::exit(2);
        });
    }

    application().run(move |cx: &mut App| {
        let bounds = Bounds::centered(None, size(px(1180.0), px(760.0)), cx);
        cx.open_window(
            WindowOptions {
                window_bounds: Some(WindowBounds::Windowed(bounds)),
                ..Default::default()
            },
            move |_window, cx| cx.new(|cx| CodaGui::new(base.clone(), token.clone(), cx)),
        )
        .expect("open_window");
        cx.activate(true);
    });
}