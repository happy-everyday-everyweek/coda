# GUI（GPUI）状态与环境限制

- 分支：`feat/desktop-agent-platform`
- 日期：2026-11-07
- 对应任务：GUI-101 ~ GUI-107
- 对应验收：UI-001 ~ UI-005（GUI 侧）
- 状态：**GUI-101 IN PROGRESS（CI 可行性冒烟已通过）**；GUI-102 ~ GUI-107 **BLOCKED**

> 本文件不包含任何 GPUI 产品实现的冒充。`platform/gui-smoke/` 只验证
> “无独显的 Linux CI 能否编译 GPUI 并用软件 Vulkan 出帧”，不是 GUI 应用壳。

## 1. 已完成的验证（CI）

本机是 Android 手机上的 Ubuntu proot：没有 Rust/Cargo，无法本地编译 GPUI。
因此把可行性验证放到 GitHub Actions：`.github/workflows/gpui-soft.yml`
（`platform/gui-smoke/`，gpui pin 与参考项目 Zeron 一致：
`zeronsh/zui@b564a34413bb7ef1da04121c4a287758f94993d9`）。

- runner 安装 `mesa-vulkan-drivers`（lavapipe）、`xvfb`、字体/窗系统开发库；
- `vulkaninfo --summary` 记录软件 Vulkan 设备与 API 版本；
- `cargo build` 编译最小 GPUI 窗口；
- `xvfb-run` 下运行，日志出现 `GPUI_SMOKE_OK` 即判定第一帧渲染成功；
- 该 job `continue-on-error: true`，失败不阻塞 Platform / Release 主流水线。

**实测结果（run [42386944351](https://github.com/happy-everyday-everyweek/coda/actions/runs/42386944351)，2026-11-07）**：

| 项目 | 结果 |
|---|---|
| Vulkan 设备 | `llvmpipe (LLVM 21.1.8, 256 bits)`，driver `llvmpipe`，apiVersion `1.4.335` |
| OpenGL | `llvmpipe`，direct rendering |
| gpui 编译 | `Finished dev profile in 6m 22s`（固定 zui rev `b564a34`） |
| 出帧 | `GPUI_SMOKE_OK: window rendered`（Xvfb，15s 内） |

结论：**没有独显不影响 GPUI 的编译与软件渲染验证**；GPUI 需要的是可用的 GPU API，
集显/软件 Vulkan（lavapipe）即可跑通。真实产品性能仍需在目标桌面环境测。

**结论边界**：这证明 GPUI 依赖可构建、无独显时能拿到 GPU API（软件实现）并出帧；
不证明产品 GUI 的交互、性能、IME、可访问性或三平台安装器可用。

## 2. 仍未交付（GUI-102 ~ GUI-107）

- 没有 `platform/clients/gui/` 产品应用壳，也没有核心连接、会话导航、Composer、审批面板、
  终端/Diff/成果界面；
- 没有 GUI 截图、录屏、交互或性能数据；
- 三平台（Windows/macOS/Linux）安装器与真实桌面环境验证仍缺；
- GUI-101 的产品化部分仍依赖真实桌面环境，故只能保持 IN PROGRESS，不能标 DONE。

## 3. 解除条件

1. 在具备桌面环境的 CI/真机上跑通 `gpui-soft.yml` 且稳定；
2. 落地 `platform/clients/gui/` 最小应用壳并通过 UI-001/UI-003 契约测试（Mock Core）；
3. 完成跨平台打包与安装器验证（QA-001）；
4. 有真实截图/录屏与可重复命令作为证据后，再逐项把 GUI-102..107 从 BLOCKED 改状态。

在此之前，任何“GPUI GUI 已完成”的描述都不成立。
