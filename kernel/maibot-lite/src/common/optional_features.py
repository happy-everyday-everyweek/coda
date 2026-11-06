"""Android 精简构建的特性开关与可选依赖守卫。

本文件是 android-lite 构建分支引入的唯一新增模块，不修改上游逻辑。

设计原则：
- 所有"可选子系统"（A_memorix 长期记忆、agent、WebUI、插件运行时、API server）
  通过延迟导入 + 空实现占位，避免其重依赖（scipy/pandas/pyarrow/faiss/fastapi/uvicorn）
  在模块导入期被无条件拉起。
- 上游代码保持原样：需要这些子系统的位置改为 ``get_x()`` 调用，在开关关闭时返回 None。
- 默认全部关闭，适合"单用户单会话"的嵌入式场景。
"""

from __future__ import annotations

from typing import Any, Callable, Optional
import os

__all__ = [
    "ENABLE_A_MEMORIX",
    "ENABLE_AGENT",
    "ENABLE_WEBUI",
    "ENABLE_PLUGIN_RUNTIME",
    "ENABLE_API_SERVER",
    "optional_import",
    "get_a_memorix_host_service",
    "get_a_memorix_image_helpers",
    "get_profile_injection_text",
    "get_agent_tool_provider",
]


def _flag(name: str, default: bool = False) -> bool:
    """读取布尔开关；未设置时使用 default。"""

    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


# 单用户场景默认关闭的重型子系统
ENABLE_A_MEMORIX = _flag("MAIBOT_ENABLE_A_MEMORIX", False)
ENABLE_AGENT = _flag("MAIBOT_ENABLE_AGENT", False)
ENABLE_WEBUI = _flag("MAIBOT_ENABLE_WEBUI", False)
ENABLE_PLUGIN_RUNTIME = _flag("MAIBOT_ENABLE_PLUGIN_RUNTIME", False)
ENABLE_API_SERVER = _flag("MAIBOT_ENABLE_API_SERVER", False)


def optional_import(module_path: str, attr: str = "", *, default: Any = None) -> Any:
    """按需导入可选依赖；缺失时返回 default，不抛异常。

    Args:
        module_path: 点分模块路径。
        attr: 需要取的属性名；为空时返回模块本身。
        default: 导入失败时的回退值。
    """

    try:
        import importlib

        module = importlib.import_module(module_path)
        return getattr(module, attr) if attr else module
    except Exception:  # noqa: BLE001 - 可选依赖缺失不应中断主流程
        return default


def get_a_memorix_host_service() -> Optional[Any]:
    """长期记忆宿主服务；关闭时返回 None。"""

    if not ENABLE_A_MEMORIX:
        return None
    return optional_import("src.A_memorix.host_service", "a_memorix_host_service")


def get_a_memorix_image_helpers() -> tuple[Optional[Callable[..., Any]], Optional[Callable[..., Any]]]:
    """A_memorix 图片组件辅助函数；关闭时返回 (None, None)。"""

    if not ENABLE_A_MEMORIX:
        return None, None
    module = optional_import("src.A_memorix.core.image.component_paths")
    if module is None:
        return None, None
    return (
        getattr(module, "build_chat_external_ref", None),
        getattr(module, "iter_message_image_components", None),
    )


def get_profile_injection_text() -> Optional[Callable[..., Any]]:
    """人物画像注入文本构建器；关闭时返回 None。"""

    if not ENABLE_A_MEMORIX:
        return None
    return optional_import(
        "src.A_memorix.core.utils.profile_text", "build_profile_injection_text"
    )


def get_agent_tool_provider() -> Optional[Any]:
    """外接 Agent 工具提供者；关闭时返回 None。"""

    if not ENABLE_AGENT:
        return None
    return optional_import("src.agent.provider", "AgentToolProvider")