"""maim_message 的精简引导：跳过其 __init__.py 的重型依赖。

背景
----
``maim_message/__init__.py`` 会无条件导入 ``.api`` -> ``.ws_connection`` -> ``uvicorn``，
从而把 fastapi/starlette/uvicorn 整条 Web 栈拖进任何使用 ``MessageBase`` 的进程。

在单用户嵌入式（Android）构建中我们只需要它的**数据模型**与**转换器**，
不需要它自带的 WebSocket 服务端。本模块在包级别预置一个"空壳" ``maim_message``
模块（带正确的 ``__path__``），使 ``import maim_message.message_base`` 时
Python 不会执行原始 ``__init__.py``。

必须在导入任何 ``maim_message`` 子模块**之前**调用 :func:`install`。
"""

from __future__ import annotations

import sys
import types
from typing import Any

__all__ = ["install", "is_installed"]

_INSTALLED_FLAG = "_maibot_lite_shim"


def is_installed() -> bool:
    """判断 shim 是否已生效。"""

    module = sys.modules.get("maim_message")
    return module is not None and getattr(module, _INSTALLED_FLAG, False)


def install() -> bool:
    """安装轻量 shim；已安装或原包已完整加载时直接返回。

    Returns:
        True 表示 shim 生效（或原本就是 shim）；False 表示包已被完整加载、无法接管。
    """

    existing = sys.modules.get("maim_message")
    if existing is not None:
        return bool(getattr(existing, _INSTALLED_FLAG, False))

    try:
        import importlib.util

        spec = importlib.util.find_spec("maim_message")
    except (ImportError, ValueError):
        return False

    if spec is None or not spec.submodule_search_locations:
        return False

    package = types.ModuleType("maim_message")
    package.__path__ = list(spec.submodule_search_locations)  # type: ignore[arg-type]
    package.__package__ = "maim_message"
    package.__file__ = spec.origin or ""
    setattr(package, _INSTALLED_FLAG, True)
    sys.modules["maim_message"] = package
    return True


def patch_module_for_lite() -> None:
    """便捷入口：安装 shim 并立即预热核心数据模型。"""

    install()
    # 预热，确保后续 from maim_message.message_base import ... 命中缓存
    from maim_message import message_base  # noqa: F401


def get_message_base_module() -> Any:
    """返回 ``maim_message.message_base`` 模块（必要时先装 shim）。"""

    install()
    from maim_message import message_base

    return message_base