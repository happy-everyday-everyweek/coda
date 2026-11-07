# Coda

运行于安卓的 Agent 工具

## 功能

- Coda：与 Coda 聊天并持续推进任务
- 工作台：以标签页组织概览、文件、Git 与终端
- Bot：持续推进任务，监控会话

## 结构

- `app/src/main/java/com/coda/mobileui/` — 界面
  - `core/` — 运行时客户端
  - `workspace/` — 工作台页面与容器
- `app/src/main/assets/core/` — 内置运行时载荷
- `app/src/main/assets/linux/` — 内置 Linux 环境

## 构建

- JDK 17
- Android SDK 36
- 构建前重建内核二进制载荷：`tools/build-core-payload.sh`，细节见 `docs/kernel-build.md`
- `./gradlew assembleDebug`

## 许可

本项目采用 [GNU Affero General Public License v3.0](LICENSE)

## 致谢
- MaiBot：https://github.com/Mai-with-u/MaiBot
- MaiBot Android Lite：https://github.com/happy-everyday-everyweek/MaiBot-Android-Lite
- ZCode：https://github.com/zai-org/ZCode
- WorkBuddy2API：https://github.com/happy-everyday-everyweek/workbuddy2api-panel
- WorkBuddy2API（上游）：https://github.com/linguo2625469/workbuddy2api-panel
- CLIProxyAPI：https://github.com/router-for-me/CLIProxyAPI
- CPA WorkBuddy Plugin：https://github.com/luode0320/cpa-workbuddy-plugin
- React Bits：https://github.com/DavidHDev/react-bits
- Alpine Linux：https://gitlab.alpinelinux.org/alpine/aports
- Termux PRoot：https://github.com/termux/proot
- Chaquopy：https://github.com/chaquo/chaquopy
- Node.js：https://github.com/nodejs/node
- ripgrep：https://github.com/BurntSushi/ripgrep
- ugrep：https://github.com/Genivia/ugrep
- jieba：https://github.com/fxsjy/jieba
- AndroidX：https://github.com/androidx/androidx
- Material Components for Android：https://github.com/material-components/material-components-android
- Markwon：https://github.com/noties/Markwon
- OkHttp：https://github.com/square/okhttp
- Kotlin Coroutines：https://github.com/Kotlin/kotlinx.coroutines

内置运行时载荷包含第三方组件，其版权与许可声明归各上游项目所有，完整文本见 `kernel/zcode/THIRD-PARTY-NOTICES.md`。
