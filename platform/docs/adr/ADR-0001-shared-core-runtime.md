# ADR-0001：共享核心语言、进程边界与契约先行

- 状态：Accepted
- 日期：2026-11-07
- 对应任务：DOC-102（部分）、CORE-101、CORE-103
- 对应验收：DOC-003、QA-007

## 背景

`SPEC.md` 5.4 建议共享核心采用 Rust、GUI 采用 Rust + GPUI，同时明确「最终核心语言可以在技术设计中确认，不改变本规格的外部行为」。
当前交付环境（Android 手机上的 Ubuntu proot）没有 Rust/GPUI 工具链，也没有 Android SDK；网络带宽受限，无法安装动辄数 GB 的 Rust 依赖树。

## 决策

1. 共享核心以 **契约先行** 方式落地：`platform/CONTRACT.md` + `platform/contracts/` 定义协议、事件、命令、能力声明；核心实现是**参考实现**，语言为 Node.js 24 ESM（零外部依赖）。
2. 进程边界不变：核心是独立进程，持有唯一权威状态；TUI/Web/GUI 都是客户端，只读投影、只发命令。
3. GUI 仍按规格走 Rust + GPUI，作为**独立进程客户端**通过同一 JSON-RPC v1 契约连接核心；GUI 代码不在本切片交付（本机无 Rust 工具链，任务标 BLOCKED 并记录证据）。
4. 未来把核心换成 Rust 时，只允许替换 `platform/core/` 的实现，`contracts/` 与客户端不变；契约测试（`platform/test/`）作为迁移门禁。

## 理由

- 契约与语言解耦：外部行为由 `contracts/` 与契约测试固定，语言可替换。
- 可验证：Node 24 内置 `node:sqlite`、`node:crypto`（X25519/ChaCha20-Poly1305）、`node:test`，无需联网安装即可跑完整测试，满足 `TODO.md` 1.3 的「CI 通过、证据可复现」。
- 进程管理与同步逻辑在 Node 中可直接实现并测试，不需要 GPU 或桌面环境。

## 影响

- 与 SPEC 5.4 的建议不一致，需在规格版本记录中注明「核心语言待 Rust 迁移，外部行为不变」。
- GPUI GUI、WSL、真实 Agent 适配在无对应环境的机器上只能标 BLOCKED/UNVERIFIED，禁止伪装完成。
- 所有客户端仅依赖 `contracts/`，不得依赖核心内部模块（核心内部模块放 `core/`，由 `core/index.mjs` 导出）。

## 备选方案

- Rust 核心：与规格一致，但本机无工具链且依赖下载不可行，无法产出可验证切片，放弃。
- 纯 Python 核心：可运行，但跨平台进程管理/PTY/加密与后续 GUI 生态衔接不如 Node，且仓库已有 Chaquopy Python 端，会造成双运行时混淆，放弃。
