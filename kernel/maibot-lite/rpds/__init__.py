"""rpds 的纯 Python 替代实现。

referencing 只用到 HashTrieMap、HashTrieSet、List 这三个不可变容器。
这里用 dict / frozenset / tuple 做写时复制，接口保持一致，去掉 rpds-py 原生依赖。
"""

from __future__ import annotations

from collections.abc import Collection, Iterable, Iterator, Mapping, Sequence
from typing import Any, Generic, TypeVar

K = TypeVar("K")
V = TypeVar("V")
T = TypeVar("T")


class HashTrieMap(Mapping[K, V], Generic[K, V]):
    __slots__ = ("_data",)

    def __init__(self, iterable: Iterable[tuple[K, V]] | Mapping[K, V] | None = None) -> None:
        self._data: dict[K, V] = dict(iterable or {})

    @classmethod
    def convert(cls, value: Any) -> "HashTrieMap[K, V]":
        return value if isinstance(value, cls) else cls(value)

    def insert(self, key: K, value: V) -> "HashTrieMap[K, V]":
        data = dict(self._data)
        data[key] = value
        return HashTrieMap(data)

    def update(self, iterable: Iterable[tuple[K, V]] | Mapping[K, V]) -> "HashTrieMap[K, V]":
        data = dict(self._data)
        data.update(iterable)
        return HashTrieMap(data)

    def remove(self, key: K) -> "HashTrieMap[K, V]":
        data = dict(self._data)
        del data[key]
        return HashTrieMap(data)

    def discard(self, key: K) -> "HashTrieMap[K, V]":
        if key not in self._data:
            return self
        return self.remove(key)

    def __getitem__(self, key: K) -> V:
        return self._data[key]

    def __iter__(self) -> Iterator[K]:
        return iter(self._data)

    def __len__(self) -> int:
        return len(self._data)

    def __contains__(self, key: object) -> bool:
        return key in self._data

    def items(self) -> Any:
        return self._data.items()

    def keys(self) -> Any:
        return self._data.keys()

    def values(self) -> Any:
        return self._data.values()

    def __repr__(self) -> str:
        return f"HashTrieMap({self._data!r})"


class HashTrieSet(Collection[T], Generic[T]):
    __slots__ = ("_data",)

    def __init__(self, iterable: Iterable[T] | None = None) -> None:
        self._data: frozenset[T] = frozenset(iterable or ())

    @classmethod
    def convert(cls, value: Any) -> "HashTrieSet[T]":
        return value if isinstance(value, cls) else cls(value)

    def insert(self, value: T) -> "HashTrieSet[T]":
        return HashTrieSet(self._data | {value})

    def add(self, value: T) -> "HashTrieSet[T]":
        return self.insert(value)

    def update(self, iterable: Iterable[T]) -> "HashTrieSet[T]":
        return HashTrieSet(self._data | frozenset(iterable))

    def extend(self, iterable: Iterable[T]) -> "HashTrieSet[T]":
        return self.update(iterable)

    def remove(self, value: T) -> "HashTrieSet[T]":
        if value not in self._data:
            raise KeyError(value)
        return HashTrieSet(self._data - {value})

    def discard(self, value: T) -> "HashTrieSet[T]":
        if value not in self._data:
            return self
        return HashTrieSet(self._data - {value})

    def __contains__(self, value: object) -> bool:
        return value in self._data

    def __iter__(self) -> Iterator[T]:
        return iter(self._data)

    def __len__(self) -> int:
        return len(self._data)

    def __repr__(self) -> str:
        return f"HashTrieSet({set(self._data)!r})"


class List(Sequence[T], Generic[T]):
    __slots__ = ("_items",)

    def __init__(self, iterable: Iterable[T] | None = None) -> None:
        self._items: tuple[T, ...] = tuple(iterable or ())

    @classmethod
    def convert(cls, value: Any) -> "List[T]":
        return value if isinstance(value, cls) else cls(value)

    def append(self, value: T) -> "List[T]":
        return List((*self._items, value))

    def extend(self, iterable: Iterable[T]) -> "List[T]":
        return List((*self._items, *tuple(iterable)))

    def push_front(self, value: T) -> "List[T]":
        return List((value, *self._items))

    def __getitem__(self, index: int | slice) -> Any:
        return self._items[index]

    def __iter__(self) -> Iterator[T]:
        return iter(self._items)

    def __len__(self) -> int:
        return len(self._items)

    def __repr__(self) -> str:
        return f"List({list(self._items)!r})"


__all__ = ["HashTrieMap", "HashTrieSet", "List"]
