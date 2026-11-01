# Personal Agent

本 WorkTree 专门用于 Coda 的 Personal Agent 开发，与 `dev` 的日常工作分离。

## 内核

使用用户自己的 `happy-everyday-everyweek/MaiBot-Android-Lite`，不是第三方的 `xiguaooo/MaiBot-Lite`。它是 MaiBot 1.3.15 面向 Android 嵌入的聊天核心，目标是单用户单会话，不使用 proot，不启动 WebUI、插件运行时、API 服务或外接 Agent/ACP。

内核源码位置为 `/home/MaiBot-Android-Lite`，上游分支为 `main`。其 `README-LITE.md` 与 `AGENTS.md` 是接入前的必读文档；重依赖门禁 `scripts/check_lite_core.py --strict` 必须保留。

## 接入边界

Personal Agent 负责个人陪伴与会话体验，不复用 Coda 的 Coding Agent 会话内核。Android 侧通过最小 `LiteRuntime` 调用 `chat_bot.message_process`，不启动原项目的 WebSocket 服务端。模型供应商配置优先复用 Coda 已有能力，个人资料、会话、偏好和陪伴状态独立于 Coding Agent 存储。

第一阶段先完成可启动、可配置、单会话收发与持久化，再考虑长期记忆、主动消息和更多感知能力。任何新增 Python 依赖都要先验证 Android 可用性，不把 SciPy、pandas、pyarrow、FAISS、Playwright、FastAPI 等重依赖带回核心链路。
