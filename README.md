# ZCode Mobile

运行于安卓的 Agent 客户端（原生 Kotlin）。内置 ZCode 运行时，通过 ZCode Protocol（NDJSON over stdio）与本地 `zcode app-server` 子进程通信；"运行时客户端"被设计为可替换的一层，同一套协议可延伸到未来的云端运行时。

## 功能

- 会话：新建 / 列表 / 打开历史 / 流式输出 / 工具调用展示 / Markdown 渲染 / 斜杠命令
- 模型：供应商配置（API 端点 / 模型名 / Key）、模型切换、思考强度调节
- 交互：Yolo / Build / Chat 发送模式（长按发送按钮）、权限弹窗、Agent 提问弹窗
- 设置系统：模型设置、子智能体、技能、命令（与 ZCode 桌面端同款文件格式与目录）
- 稳定性：崩溃拦截（落盘日志 + 崩溃页）、首装载荷解包提示

## 结构

- `app/src/main/java/com/zcode/mobileui/` — 界面与运行时对接
  - `core/` — 运行时客户端（CoreRuntime / ZController / ProviderStore / AgentAssets 等）
- `app/src/main/assets/core/` — 内置运行时载荷（node / zcode 原生二进制 / zcode.cjs / rg / lib）

## 构建

- JDK 17 + Android SDK（compileSdk 36，minSdk 26）
- `./gradlew assembleDebug`（wrapper 首次运行会下载 Gradle；国内可用腾讯镜像，见 gradle-wrapper.properties 注释）

## 说明

- 首次启动会解包核心载荷（约 200 MB，1-2 分钟，有界面提示）。
- 模型走标准 OpenAI / Anthropic 兼容接口，在「设置 → 模型设置」中配置。
- 用户级资产目录（与桌面端一致）：`{HOME}/.zcode/agents`、`{HOME}/.zcode/commands`、`{HOME}/.zcode/skills`。
