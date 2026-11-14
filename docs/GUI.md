# Coda GUI（GPUI）实施说明

本文件记录桌面 GUI 的目标、当前完成度与差距。GUI 是三端之一（CLI/TUI、Web UI、GUI），
规格见 `docs/desktop-agent-platform/SPEC.md` §4.1。

## 1. 技术选型

- 框架：GPUI（`zeronsh/zui` fork，pin `b564a34413bb7ef1da04121c4a287758f94993d9`），
  与 `platform/gui-smoke/` 使用的版本完全一致，避免出现两套渲染栈。
- 为什么不用 Electron/WebView：桌面端要贴近原生窗口、输入与多窗口语义，
  Web UI 已经由 `platform/clients/web` 覆盖，GUI 再包一层 WebView 只是重复。
- 编译环境：本机（Android 上的 Ubuntu proot）没有 Rust 工具链，
  GUI 的编译与出帧验证全部在 CI 完成（`.github/workflows/gpui-soft.yml` 的 `gui` 任务）。

## 2. 布局（参照 Zed）

- 左侧是半透明侧边栏：会话列表、`＋` 新建会话、分屏与多窗口入口。
- 中间是浮起的会话卡片：标题、Agent/模型/状态、消息列表、底部输入区。
- 卡片之间留间距，激活窗格用更亮的描边区分。
- 一屏最多 4 个窗格，支持左右与上下两种切分方向。
- 每个窗格可以把当前会话拆成独立窗口（多窗口）。

## 3. 当前完成度

已完成并进入 CI 验证：

- 应用壳、窗口创建、首帧渲染（`CODA_GUI_OK` 标记）。
- 上述 Zed 式布局（侧边栏 + 浮动卡片 + 激活描边）。
- 窗格增删、左右/上下分屏、上限 4 格。
- 多窗口：把某个窗格的会话拆成独立窗口。
- 对话流、输入区、操作按钮的静态呈现。

尚未完成（与 CLI/Web 的能力差距）：

- 真实核心连接：stdio JSON-RPC（hello → subscribe → command/answer），
  目前界面数据是演示数据，不是核心投影。
- 流式消息、审批/提问应答、终端回放、Diff 与成果下载。
- 可编辑输入框（含 IME/CJK）、模型选择、排队/插话/停止的真实命令。
- 会话搜索、通知、快捷键与命令面板。
- 三平台安装器与真实桌面环境的性能/IME/可访问性验证。

在以上完成前，`GUI-102 ~ GUI-107` 保持 `BLOCKED`，不得对外声称 GUI 已交付。

## 4. CI 口径

`gpui-soft.yml` 的 `gui` 任务会编译 GUI 并在 Xvfb（lavapipe 软件 Vulkan）下运行，
日志出现 `CODA_GUI_OK` 即判定首帧成功。该任务目前 `continue-on-error: true`，
GUI 稳定后再改成阻塞。

## 5. 与 Web/CLI 共享的部分

- 分屏布局模型：`platform/clients/web/public/layout.mjs`（纯函数 + 单测），
  Web 与 GUI 采用同一套窗格规则（最多 4 格、关到最后一个不再关）。
- 协议与投影定义：`platform/contracts/*`、`platform/clients/shared/projection.mjs`。