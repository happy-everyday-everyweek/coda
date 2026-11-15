//! GPUI 最小冒烟：在 Xvfb + Mesa 软件 Vulkan（lavapipe）下开窗并出帧。
//!
//! 验证目标不是画面质量，而是：
//!   1. gpui 依赖能在 Linux 上编译（系统库齐全）；
//!   2. 应用能拿到 GPU API（无独显时 wgpu 回落到软件 Vulkan）；
//!   3. 窗口事件循环能跑到第一帧 Render。
//!
//! 出帧后由看门线程退出进程，避免 CI 卡在事件循环里。

use std::sync::atomic::{AtomicBool, Ordering};

use gpui::{
    App, Bounds, Context, Window, WindowBounds, WindowOptions, div, prelude::*, px, rgb, size,
};
use gpui_platform::application;

static RENDERED: AtomicBool = AtomicBool::new(false);

struct Smoke;

impl Render for Smoke {
    fn render(&mut self, _window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        RENDERED.store(true, Ordering::SeqCst);
        div()
            .flex()
            .flex_col()
            .size_full()
            .gap_2()
            .bg(rgb(0x101826))
            .p_6()
            .child(
                div()
                    .text_2xl()
                    .text_color(rgb(0x7da1de))
                    .child("Coda GPUI smoke"),
            )
            .child(
                div()
                    .text_sm()
                    .text_color(rgb(0xc8d3e6))
                    .child("software Vulkan / llvmpipe / Xvfb"),
            )
    }
}

fn main() {
    std::thread::spawn(|| {
        std::thread::sleep(std::time::Duration::from_secs(15));
        if RENDERED.load(Ordering::SeqCst) {
            eprintln!("GPUI_SMOKE_OK: window rendered");
            std::process::exit(0);
        }
        eprintln!("GPUI_SMOKE_FAIL: no frame within 15s");
        std::process::exit(2);
    });

    application().run(|cx: &mut App| {
        let bounds = Bounds::centered(None, size(px(520.0), px(260.0)), cx);
        cx.open_window(
            WindowOptions {
                window_bounds: Some(WindowBounds::Windowed(bounds)),
                ..Default::default()
            },
            |_, cx| cx.new(|_| Smoke),
        )
        .expect("open_window");
        cx.activate(true);
    });
}
