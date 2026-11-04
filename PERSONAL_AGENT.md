# Personal Agent

本 WorkTree 专门用于 Coda 的 Personal Agent 开发，与 `dev` 的日常工作分离。

## 内核

使用用户自己的 `happy-everyday-everyweek/MaiBot-Android-Lite`，不是第三方的 `xiguaooo/MaiBot-Lite`。它是 MaiBot 1.3.15 面向 Android 嵌入的聊天核心，目标是单用户单会话，不使用 proot，不启动 WebUI、插件运行时、API 服务或外接 Agent/ACP。

内核源码位置为 `/home/MaiBot-Android-Lite`，上游分支为 `main`。其 `README-LITE.md` 与 `AGENTS.md` 是接入前的必读文档；重依赖门禁 `scripts/check_lite_core.py --strict` 必须保留。

## 接入边界

Personal Agent 负责个人陪伴与会话体验，不复用 Coda 的 Coding Agent 会话内核。Android 侧通过最小 `LiteRuntime` 调用 `chat_bot.message_process`，不启动原项目的 WebSocket 服务端。模型供应商配置优先复用 Coda 已有能力，个人资料、会话、偏好和陪伴状态独立于 Coding Agent 存储。

第一阶段先完成可启动、可配置、单会话收发与持久化，再考虑长期记忆、主动消息和更多感知能力。任何新增 Python 依赖都要先验证 Android 可用性，不把 SciPy、pandas、pyarrow、FAISS、Playwright、FastAPI 等重依赖带回核心链路。

## 产品入口与设置

Personal Agent 从原 Coda 侧边栏进入，位置在新对话和设置之间，显示用户为该 Bot 设置的名字。它不替换 Coding Agent 的会话页；宠物动画只在新 Personal Agent 页面输入框上方展示，原 Coding Agent 页面不再显示。

Coda Bot 设置独立于原宠物设置，包含形象与名称、人设、了解你、记忆、规划模型、回复模型、聊天样式和本地会话数据。形象复用 `assets/pet` 的五个动画资源，也允许选择本地图片。规划与回复使用两个独立的模型配置；规划模型可调用受限的 `coding_agent` 工具来新建、发送、停止和关闭 Coding Agent 会话，不开放任意 Shell。

长期记忆、主动消息和免打扰在能力真正接入前不得提供无效开关。

## Android 构建现状

内核以 Git 子模块固定在 `kernel/maibot-lite`，Chaquopy 17.2 使用 Python 3.12，仅打包 arm64-v8a。Kotlin 编译与整包构建都由 `.github/workflows/personal-agent.yml` 验证，`assembleDebug` 可产出安装包。

原生依赖已收敛到三个自建轮子：`pydantic-core` 与 `jiter` 在 CI 里用 cargo 交叉编译，`rpds-py` 用纯 Python 垫片轮子满足 jsonschema 的版本下限；`jieba` 源码内置，`msgpack`、`watchfiles`、`rapidfuzz`、`ahocorasick-rs` 已从依赖清单移除。版本钉在 Chaquopy 源实际可用的上限，具体值见 `kernel/maibot-lite/requirements-lite.txt`。
