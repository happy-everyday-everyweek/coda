"""msgpack 兼容垫片。

只服务未启用的旧库迁移模块：它只需要 packb 写出空消息常量。
嵌入式运行时不安装真正的 msgpack 原生包，这里的字节格式是 pickle，
不能与真实 MessagePack 数据互通；新写入的消息由 utils_message 直接使用 pickle。
"""

from __future__ import annotations

import pickle
from typing import Any


class ExtType:
    def __init__(self, code: int, data: bytes) -> None:
        self.code = code
        self.data = data


def packb(obj: Any, **_: Any) -> bytes:
    return pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL)


def unpackb(data: bytes, **_: Any) -> Any:
    return pickle.loads(data)
