# MaiBot Android Lite Core

把 MaiBot 的**聊天核心**剥离成一个可嵌入 Android 应用的、无 proot 依赖的 Python 包。

> 本仓库是 [MaiBot](https://github.com/MaiM-with-u/MaiBot) 1.3.15 的派生分支，
> 目标场景是**单用户单会话**的嵌入式助手，不是原版的群聊/多租户机器人。

---

## 为什么要做这个分支

上游 MaiBot 依赖 61 个原生扩展（`scipy` 94MB、`pyarrow` 136MB、`pandas` 52MB、
`faiss` 26MB、Chromium 128MB……），这些在 Android bionic 上**几乎都拿不到 wheel**，
只能用 proot 容器跑 glibc 版本。

用户明确不需要：
- 插件系统（运行时 `pip install` + 多进程 Runner）
- WebUI / 管理面板
- 多租户、群聊、记忆隔离
- A_memorix 长期记忆
- 外接 Agent / ACP

把这些砍掉之后，核心链路的原生依赖降到 **6 个**，其中 5 个在 Chaquopy 源可得，pydantic-core 由 CI 交叉编译。

---

## 实测数据

| 指标 | 上游全功能 | 本分支 |
|---|---|---|
| 第三方原生扩展 | **61** | **10** |
| site-packages 体积 | 786 MB | — |
| 原生 wheel 压缩体积 | — | **36 MB** |
| `scipy` / `pandas` / `pyarrow` / `faiss` | 必需 | 完全移除 |
| `fastapi` / `uvicorn` / `starlette` | 必需 | 完全移除 |
| 需要 proot | 是 | **否** |

保留下来的 6 个原生扩展：

| 包 | 来源 |
|---|---|
| numpy | Chaquopy 源 |
| pillow | Chaquopy 源 |
| zstandard | Chaquopy 源 |
| cryptography / cffi | Chaquopy 源 |
| greenlet | Chaquopy 源 |
| pydantic-core | CI 用 Rust + NDK 交叉编译 |

已移除的原生包：msgpack 改用 pickle，watchfiles 改用轮询，rpds-py 改用纯 Python 实现，rapidfuzz 改用 difflib，ahocorasick-rs 无调用点。

---

## 关键改动

### 1. `src/common/optional_features.py`（新增）

把已禁用子系统的**模块级硬导入**改为延迟导入 + 空实现：

| 原文件 | 原行为 |
|---|---|
| `src/services/memory_service.py` | 模块导入期就 `from src.A_memorix.host_service import ...` |
| `src/services/memory_flow_service.py` | 同上，另加图片组件辅助 |
| `src/services/person_fact_reverification.py` | 同上 |
| `src/maisaka/builtin_tool/query_image_memory.py` | 同上 |
| `src/maisaka/memory/image_injector.py` | 同上 |
| `src/maisaka/memory/person_profile.py` | `from src.A_memorix.core.utils.profile_text import ...` |
| `src/maisaka/runtime.py` | `from src.agent.provider import AgentToolProvider` |

**为什么这是必须的**：即使 `config/bot_config.toml` 里 `a_memorix.plugin.enabled = false`，
这些模块级导入仍会在导入期把 `scipy` 拉进来。导入链是：

```
bot.py → heartflow_message_processor → heartflow_manager
  → maisaka/runtime → chat/utils/utils → person_info
  → memory_service → A_memorix → scipy
```

### 2. `src/common/maim_lite.py`（新增）

`maim_message/__init__.py` 把数据模型和 WebSocket 服务端绑在一起：

```python
from .api import MessageClient, MessageServer        # → uvicorn → fastapi
from .message_base import Seg, MessageBase, ...      # ← 我们只要这些
```

这导致**任何使用 `MessageBase` 的进程都会被拖入整条 Web 栈**。

解法：在 `src/__init__.py` 里预置一个"空壳" `maim_message` 包（带正确 `__path__`），
使 `import maim_message.message_base` 不执行原始 `__init__.py`。
这是 `import src` 时自动完成的，无需调用方配合。

同时把核心路径上的 6 处 `from maim_message import X` 改为
`from maim_message.message_base import X`（或 `.converter`）。

### 3. `requirements-lite.txt`（新增）

精简依赖清单。移除项及原因见文件内注释。

### 4. `scripts/check_lite_core.py`（新增）

**CI 门禁**。若上游合并把重依赖重新引入核心导入链，构建会失败：

- 用 `sys.meta_path` 拦截器模拟"这些包在目标平台不可用"
- 断言：核心入口可导入、shim 已生效、零重依赖泄漏、原生扩展在白名单内

---

## 本地验证

```bash
python -m venv .venv && . .venv/bin/activate
grep -v '^maim-message' requirements-lite.txt > /tmp/req.txt
pip install -r /tmp/req.txt
pip install --no-deps 'maim-message==0.6.8'

python scripts/check_lite_core.py --strict
```

期望输出：

```
[lite-core] 已加载第三方原生扩展 (11): ['PIL', 'greenlet', 'mmap', ...]
[lite-core] 门禁通过：核心链路无重依赖。
```

---

## CI

`.github/workflows/lite-core.yml`，三个 job 全部通过（run `57ca20e`）：

1. **核心依赖门禁**
   - 在干净环境安装 `requirements-lite.txt`
   - 确认 `fastapi`/`uvicorn`/`starlette`/`pandas`/`scipy`/`pyarrow`/`faiss`/`playwright`/`matplotlib` **确实不存在**
   - 跑 `scripts/check_lite_core.py --strict`
   - 静态扫描核心路径无模块级重依赖导入
   - 校验 A_memorix 均为延迟导入

2. **收集 aarch64 wheel**
   - `pip download --platform` 一次性拿全部 10 个官方轮子（35.2 MB artifact）
   - 断言数量为 10 且全部含 `aarch64`

3. **汇总产物体积** —— 写进 job summary，对比上游 786MB

### 踩过的坑（都已修复）

| 问题 | 原因 | 修法 |
|---|---|---|
| `cache: pip` 失败 | 会扫描已删除的 `dashboard/` lockfile | 关闭 cache |
| wheels 全部 checkout 失败 | manylinux 裸镜像无 node20 | 改用 `pip download`，不再用容器 |
| `--platform` 只给 2_28 漏包 | pydantic-core 只发 2_17 | 同时给 2014 与 2_28 |
| 误报 `websockets` 是重依赖 | 它是 google-genai 的硬依赖 | 从强制缺席清单移除 |
| 误报 webui/playwright | 扫了整个 src/ 且含缩进 | 限定核心目录 + 只查模块级 |
| **门禁检出 0 个扩展却"通过"** | setup-python 的 site-packages 在 stdlib 内，被误判为内置 | 改按目录结构判定 + 加下限断言 |

---

## Android 嵌入入口

`src.lite_runtime.LiteRuntimeBridge` 是 Kotlin 可调用的同步外观，在专用线程内运行
`LiteRuntime`。`send(text)` 将文本交给 `chat_bot.message_process`；`AndroidLiteDriver`
把真实出站文本回调给宿主。运行时不启动 WebSocket 服务端、WebUI、插件 Runner、
A_memorix、外接 Agent 或 MCP，聊天与数据库均写入调用方指定的独立实例目录。

公开消息边界的测试位于 `tests/test_lite_runtime.py`。本机真实启动和停止已验证；
模型回复仍取决于宿主提供的有效模型配置，不由测试伪造。

## 待办

- [x] 把 `chat_bot.message_process` 接成 Android 侧入口（替代 maim_message 的 WebSocket 服务端）
- [x] 写最小的 `LiteRuntime`：不启动 WebUI / plugin_runtime / API server，只跑消息处理
- [ ] 多租户 / 记忆隔离相关代码的进一步裁剪（`session_id` 简化）
- [x] Chaquopy 侧确认 CPython 3.12 支持情况（17.2，兼容 AGP 8.13）
- [ ] 为 `pydantic-core` 准备 Android bionic wheel（已加入 Coda 的 personal-agent CI 交叉编译）
- [ ] 实测瘦身后的启动耗时与 RSS

---

## 与上游的关系

保持与上游 `src/` 目录结构一致，改动集中在**导入层的剥离**，不重写业务逻辑。
升级上游版本时，优先复用 `optional_features.py` / `maim_lite.py`，
只处理新增的模块级硬导入（门禁脚本会指出来）。
