#!/usr/bin/env python3
"""verify-sea-payload.py 的边界用例。

重点不是「健康载荷能过」——那份在 CI 里用真实文件跑——而是载荷被写坏/截断时
脚本必须给出一句可读的结论，而不是抛 IndexError 或 struct.error。
"""
import contextlib
import importlib.util
import io
import os
import struct
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = importlib.util.spec_from_file_location(
    "verify_sea_payload", os.path.join(HERE, "verify-sea-payload.py")
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def elf_header(**fields):
    """造一个 64 字节的 ELF64 头，字段按名字覆盖。"""
    data = bytearray(64)
    data[0:4] = b"\x7fELF"
    data[4] = 2
    struct.pack_into("<H", data, 18, fields.get("machine", 0xB7))
    struct.pack_into("<QQQ", data, 24, fields.get("entry", 0), fields.get("phoff", 0), fields.get("shoff", 0))
    struct.pack_into("<H", data, 54, fields.get("phentsize", 56))
    struct.pack_into("<H", data, 56, fields.get("phnum", 0))
    struct.pack_into("<H", data, 58, fields.get("shentsize", 64))
    struct.pack_into("<H", data, 60, fields.get("shnum", 0))
    struct.pack_into("<H", data, 62, fields.get("shstrndx", 0))
    return data


class VerifySeaPayloadTest(unittest.TestCase):
    def check(self, payload):
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(bytes(payload))
            path = f.name
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                return MODULE.main(path)
        finally:
            os.unlink(path)

    def test_truncated_magic_is_reported_not_raised(self):
        self.assertEqual(1, self.check(b"\x7fELF"))

    def test_tiny_file_is_reported(self):
        self.assertEqual(1, self.check(b""))

    def test_bad_phentsize_is_reported(self):
        self.assertEqual(1, self.check(elf_header(phentsize=8)))

    def test_program_header_table_past_eof_is_reported(self):
        self.assertEqual(1, self.check(elf_header(phentsize=56, phnum=4, phoff=64)))

    def test_section_name_index_out_of_range_is_reported(self):
        payload = elf_header(shoff=64, shnum=2, shentsize=64, shstrndx=5)
        payload.extend(bytes(128))
        self.assertEqual(1, self.check(payload))

    def test_section_name_without_nul_is_reported(self):
        payload = elf_header(shoff=64, shnum=1, shentsize=64, shstrndx=0)
        payload.extend(bytes(64))
        # 唯一的节头：名字偏移 0、偏移 128、长度 1；字符串表里全是 'A'，没有 NUL
        struct.pack_into("<IIQQQQIIQQ", payload, 64, 0, 1, 0, 128, 1, 0, 0, 0, 0, 0)
        payload.extend(b"A" * 64)
        self.assertEqual(1, self.check(payload))


if __name__ == "__main__":
    unittest.main(verbosity=2)