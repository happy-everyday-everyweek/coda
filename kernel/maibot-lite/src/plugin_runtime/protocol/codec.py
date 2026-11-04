"""进程内编解码器。

插件运行时在嵌入式构建中不启用，这里改用 pickle，去掉 msgpack 原生依赖；
只服务 Python 进程之间的信封往返，不对外暴露格式。
"""

from abc import ABC, abstractmethod
from typing import Any, Dict

import pickle

from .envelope import Envelope


class Codec(ABC):
    """消息编解码器基类"""

    @abstractmethod
    def encode_envelope(self, envelope: Envelope) -> bytes: ...

    @abstractmethod
    def decode_envelope(self, data: bytes) -> Envelope: ...

    @abstractmethod
    def encode(self, obj: Dict[str, Any]) -> bytes: ...

    @abstractmethod
    def decode(self, data: bytes) -> Dict[str, Any]: ...


class PickleCodec(Codec):
    """保留原类名，内部使用 pickle。"""

    def encode(self, obj: Dict[str, Any]) -> bytes:
        return pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL)

    def decode(self, data: bytes) -> Dict[str, Any]:
        result = pickle.loads(data)
        if not isinstance(result, dict):
            raise ValueError(f"期望解码为 dict，实际为 {type(result)}")
        return result

    def encode_envelope(self, envelope: Envelope) -> bytes:
        return self.encode(envelope.model_dump())

    def decode_envelope(self, data: bytes) -> Envelope:
        raw = self.decode(data)
        return Envelope.model_validate(raw)
