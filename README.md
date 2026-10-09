# Coda

运行于安卓的 Agent 客户端（原生 Kotlin）。内置 Agent 内核（来自 zCode 开源项目），通过 ZCode Protocol（NDJSON over stdio）与本地 `app-server` 子进程通信；"运行时客户端"被设计为可替换的一层，同一套协议可延伸到未来的云端运行时。

## 功能

- 会话：新建 / 列表 / 打开历史 / 流式输出 / 工具调用展示 / Markdown 渲染 / 斜杠命令
- 模型：供应商配置（API 端点 / 模型名 / Key）、模型切换、思考强度调节
- 交互：Yolo / Build / Chat 发送模式（长按发送按钮，默认模式可在设置中配置）、权限弹窗、Agent 提问弹窗、发送后自动滚动到底部（可在设置关闭）
- 设置系统：模型设置、子智能体、技能、命令（与桌面端同款文件格式与目录）、插件、MCP 服务器、定时任务、钩子、使用统计
- 稳定性：崩溃拦截（落盘日志 + 崩溃页）、首装载荷解包提示

## 结构

- `app/src/main/java/com/coda/mobileui/` — 界面与运行时对接
  - `core/` — 运行时客户端（CoreRuntime / ZController / ProviderStore / AgentAssets / CodaExtras 等）
- `app/src/main/assets/core/` — 内置运行时载荷（node / 原生内核二进制 / 引擎脚本 / rg / lib）

## 构建

- JDK 17 + Android SDK（compileSdk 36，minSdk 26）
- 构建前先重建内核二进制载荷：`tools/build-core-payload.sh`，细节见 `docs/kernel-build.md`
- 然后 `./gradlew assembleDebug`（wrapper 首次运行会下载 Gradle；国内可用腾讯镜像，见 gradle-wrapper.properties 注释）

## 分支与发布

- `main`：只放这份仓库说明，不放代码。
- `dev`：全部源码与开发提交，日常改动落在这里。
- 在 `dev` 上打 `v*` Tag 出正式发行版本：CI 先重建内核二进制载荷，再打 release 变体，挂到对应 Release。
- `dev` 每次推送出预发行版本：用 debug 变体打包，滚动覆盖到 `dev-latest` 这一个预发行版本。

## 说明

- 首次启动会解包核心载荷（约 200 MB，1-2 分钟，有界面提示）。
- 模型走标准 OpenAI / Anthropic 兼容接口，在「设置 → 模型设置」中配置。
- 用户级资产目录（与桌面端一致）：`{HOME}/.zcode/agents`、`{HOME}/.zcode/commands`、`{HOME}/.zcode/skills`。

## 许可

本项目采用 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0，比 GPL v3 更严格：通过计算机网络提供服务时同样要求提供源代码）。

内置运行时载荷包含第三方组件（ZCode CLI 为 Apache-2.0，Node.js 为 MIT 等），其版权与许可声明归各上游项目所有，再分发时请一并保留。
界面的任务状态标记样式参考 React Bits 的 Status Mark 组件（https://reactbits.dev ，github.com/DavidHDev/react-bits）实现，在此致谢；该组件的原始实现与文档版权归其作者所有。
