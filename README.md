# Coda

运行于安卓的 Agent 工具

## 功能

- 与 Coda 聊天并持续推进任务

## 结构

- `app/src/main/java/com/coda/mobileui/` — 界面
  - `core/` — 运行时客户端
- `app/src/main/assets/core/` — 内置运行时载荷
- `app/src/main/assets/linux/` — 内置 Linux 环境，详见 `docs/linux-env.md`

## 构建

- JDK 17 与 Android SDK 36
- 构建前先重建内核二进制载荷：`tools/build-core-payload.sh`，细节见 `docs/kernel-build.md`
- 然后 `./gradlew assembleDebug`
- 内置 Linux 环境随仓库固定携带，改动时才需按 `docs/linux-env.md` 重打包并更新 `assets/linux/version.txt`

## 许可

本项目采用 [GNU Affero General Public License v3.0](LICENSE)

内置运行时载荷包含第三方组件，其版权与许可声明归各上游项目所有，再分发时请一并保留。
界面样式参考 React Bits 的 Status Mark 组件实现，见 https://reactbits.dev ，在此致谢；该组件的原始实现与文档版权归其作者所有。
