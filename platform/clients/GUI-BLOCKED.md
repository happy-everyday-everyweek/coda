# GUI（GPUI）阻塞说明

- 分支：`feat/desktop-agent-platform`
- 日期：2026-11-07
- 对应任务：GUI-101、GUI-102、GUI-103、GUI-104、GUI-105、GUI-106、GUI-107
- 对应验收：UI-001、UI-002、UI-003、UI-004、UI-005（GUI 侧）
- 状态：**BLOCKED（全部）**

> 本文件不包含任何 GPUI/Rust 假实现。没有可运行的 GUI 代码，也没有把 TUI/Web 截图冒充 GUI。

## 1. 阻塞原因

本机是 Android 手机上的 Ubuntu proot 环境，没有 Rust / Cargo / GPUI 工具链，也没有 GPUI
所需的本机图形栈。`platform/docs/adr/ADR-0001-shared-core-runtime.md` 已决策：GUI 仍按规格走
Rust + GPUI，作为独立进程客户端通过同一 JSON-RPC v1 契约连接核心；GUI 代码不在本切片交付。

GUI-101..105、GUI-107 为 P0，GUI-106 为 P1，但都无法在没有工具链的机器上构建或做真实验收，
因此统一标记 **BLOCKED**，不写“部分完成”。

## 2. 证据

### 2.1 已执行的本机文件系统检查

本会话的执行环境没有可用 shell（`workspace-write` 无沙箱后端，`danger-full-access` 升级被拒绝），
因此不能在会话内运行 `which cargo`。改用文件系统检索确认以下路径均不存在：

| 检查 | 命令（等价） | 实际结果 |
|---|---|---|
| `/usr/bin/cargo` | `ls /usr/bin/cargo` | 不存在（glob `/usr/bin` → `cargo` 无结果） |
| `/usr/bin/rustc` | `ls /usr/bin/rustc` | 不存在（glob `/usr/bin` → `{cargo,rustc,rustup}` 无结果） |
| `/usr/local/bin/*rust*` | `ls /usr/local/bin \| grep rust` | 无结果 |
| `/root/.cargo` | `ls -la ~/.cargo` | 目录不存在（glob `/root/.cargo/**/*` 无结果） |
| `/root/.rustup` | `ls -la ~/.rustup` | 目录不存在（glob `/root/.rustup/**/*` 无结果） |
| `/opt` 下的 cargo/rustc | `find /opt -name cargo -o -name rustc` | 无结果 |
| 仓库内 `rust-toolchain*` | `find . -name 'rust-toolchain*'` | 无结果 |

**待 Lead 复核（请在具备 shell 的主会话执行并把输出贴回本文件）：**

```bash
which cargo; echo "cargo=$?"
which rustc; echo "rustc=$?"
which rustup; echo "rustup=$?"
cargo --version 2>&1 || true
rustc --version 2>&1 || true
uname -a
cat /etc/os-release | head -3
```

预期：`which` 全部非零，`cargo/rustc --version` 报 command not found。若实际存在工具链，
应把本文件与任务状态改为可实施，而不是沿用本结论。

### 2.2 相关既有记录

- `platform/docs/adr/ADR-0001-shared-core-runtime.md`：本机无 Rust/GPUI 工具链，GUI 标 BLOCKED。
- `platform/docs/LICENSE-AUDIT.md`：LIC-05「GPUI/Rust 引入前清单」记录“无 Rust 工具链，未引入”。

## 3. 未交付内容（禁止伪造）

- 没有 `platform/clients/gui/` 下的 Rust/GPUI 源文件。
- 没有 GUI 截图、录屏或性能数据。
- 没有声称 GUI-101..107 的任何验收 PASS。
- TUI/Web 的实现和证据不能替代 GUI 验收。

## 4. 解除阻塞的前置条件

1. 安装并固定工具链：`rustup toolchain install <pinned>`、`cargo --version` 可复现；
   提交 `rust-toolchain.toml`。
2. 图形栈：Linux 需要 X11/Wayland、fontconfig、Vulkan/GL 驱动（或在 CI 上使用 headless 方案）；
   Windows/macOS 分别建立构建环境。
3. 固定 GPUI revision，完成 `QA-107` 依赖许可审核，补 NOTICE。
4. 建立 GUI 契约测试：同一 Mock Core fixture 下，Rust 客户端输出的 canonical projection
   与 TUI/Web 一致（复用本切片的 `platform/test/clients/contract.test.mjs` 场景定义）。
5. 通过 `GUI-101` 最小壳验收后，才能认领 GUI-102..107。

## 5. 用同一 RPC 契约接入的设计（解除阻塞后实施）

GUI 不另写 Agent 控制逻辑，也不读核心数据库；与 TUI/Web 共用：

```text
GPUI 客户端
  ├─ transport: stdio 行 JSON（spawn core）或本地 socket（后续）
  ├─ hello {protocolVersion:1, clientType:"gui", clientVersion}
  ├─ subscribe {fromSeq} → snapshot{seq} + core.event 通知
  ├─ resume {fromSeq} → 连续则回放事件，缺口则 snapshot
  ├─ command {commandId, kind, target, payload} → receipt
  └─ canonical projection（会话/消息/状态/审批/模型/终端/成果）
```

### 5.1 模块划分（建议）

| 模块 | 责任 | 复用 |
|---|---|---|
| `gui/src/rpc.rs` | stdio JSON-RPC、pending map、超时、断线、seq 缺口恢复 | 对照 `clients/shared/rpc-client.mjs` 行为 |
| `gui/src/projection.rs` | 快照 + 事件 → 只读投影；幂等、可比较 | 对照 `clients/shared/projection.mjs` |
| `gui/src/views/*` | 会话列表、虚拟化消息流、Composer、审批、终端、Diff、成果 | 新写 GPUI 视图 |
| `gui/src/security.rs` | 令牌/本机绑定、路径与终端内容转义 | 对照 `clients/web/server.mjs` 授权矩阵 |

### 5.2 与验收条目的对应

- **UI-001**：会话/消息/审批/模型/终端均来自同一核心投影；操作只发 `core.command`。
- **UI-002**：虚拟化列表 + 跟随底部状态；用户上翻时暂停自动跟随，只有回到底部才恢复。
- **UI-003**：能力来自 `session.capabilities` / 能力矩阵；`false`/`unsupported` 显示“不支持”，
  `"experimental"` 显示“实验性”，不把降级渲染成成功。
- **UI-004**：草稿按会话保存在本机（不写入核心）；命令失败/断线/core 重启后保留，
  重发使用同一 `commandId` 幂等。
- **UI-005**：通知来自核心事件；GUI 关闭后核心继续产生；重开后用 snapshot + resume 对齐。
- **GUI-107**：GUI 进程退出不关闭核心（核心由安装器/用户服务托管）；重启 GUI 后
  `hello → subscribe(fromSeq) → resume` 恢复页面。
- **GUI-106**（P1）：键盘/IME/缩放/减少动态效果/对比度，属于本机 UI 偏好，不写入核心、不同步。

### 5.3 安全与边界

- 不按 Agent 名称分支；不直接读核心 SQLite/日志文件。
- 远程节点来源必须在会话/终端/成果上标注 `node` 与 `environment`。
- 终端输出、Diff、附件正文视为不可信文本，渲染前限制长度并做转义。
- 明文局域网连接不自动获得管理权限；远程访问沿用配对令牌 + 加密连接（SYNC-101/103）。

### 5.4 契约测试接入方式

`GUI-101` 完成后新增 Rust 契约测试（或在 CI 中用 Node 驱动 Rust 客户端）：

1. 用 `platform/test/clients/contract.test.mjs` 同一 Mock Core 场景（快照、发送、审批去重、
   停止、SSE 恢复）启动核心；
2. GUI 以 `--script`/测试入口输出 canonical JSON；
3. 与 TUI/Web 的 canonical projection 做等价比较（允许渲染差异，不允许状态差异）。

在完成上述前置条件前，GUI-101..107 保持 **BLOCKED**。
