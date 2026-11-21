"""sdist 哈希固定的解析与校验。

这些包会被交叉编译成 .so 打进 APK，所以「没有哈希就放行」是不能接受的默认行为。
"""

import hashlib
import importlib.util
import pathlib
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "fetch_sdist", pathlib.Path(__file__).with_name("fetch-sdist.py")
)
fetch_sdist = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fetch_sdist)


class LoadPinsTest(unittest.TestCase):
    def write(self, text):
        tmp = tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8")
        tmp.write(text)
        tmp.close()
        self.addCleanup(pathlib.Path(tmp.name).unlink)
        return pathlib.Path(tmp.name)

    def test_continuation_lines_are_joined(self):
        path = self.write("pkg-a==1.0 \\\n    --hash=sha256:" + "a" * 64 + "\n")
        pins = fetch_sdist.load_pins(path)
        self.assertEqual(pins[("pkg-a", "1.0")], {"a" * 64})

    def test_comments_and_blank_lines_are_ignored(self):
        path = self.write("# 注释\n\npkg==2.0 --hash=sha256:" + "b" * 64 + "  # 尾注释\n")
        self.assertEqual(fetch_sdist.load_pins(path)[("pkg", "2.0")], {"b" * 64})

    def test_entry_without_hash_is_not_pinned(self):
        path = self.write("pkg==3.0\n")
        self.assertEqual(fetch_sdist.load_pins(path), {})

    def test_real_requirements_cover_both_wheels(self):
        pins = fetch_sdist.load_pins()
        self.assertIn(("pydantic-core", "2.50.2"), pins)
        self.assertIn(("jiter", "0.18.0"), pins)
        for hashes in pins.values():
            for value in hashes:
                self.assertEqual(len(value), 64)


class VerifyTest(unittest.TestCase):
    def test_matching_digest_passes(self):
        data = b"native code"
        digest = hashlib.sha256(data).hexdigest()
        self.assertTrue(fetch_sdist.verify(data, {digest}))

    def test_tampered_content_fails(self):
        digest = hashlib.sha256(b"native code").hexdigest()
        self.assertFalse(fetch_sdist.verify(b"evil code", {digest}))

    def test_empty_expectation_rejects(self):
        self.assertFalse(fetch_sdist.verify(b"anything", set()))

    def test_truncated_content_fails(self):
        data = b"x" * 4096
        digest = hashlib.sha256(data).hexdigest()
        self.assertFalse(fetch_sdist.verify(data[:1024], {digest}))


class UrlTest(unittest.TestCase):
    def test_hyphen_becomes_underscore_in_filename(self):
        url = fetch_sdist.sdist_url("pydantic-core", "2.50.2")
        self.assertTrue(url.endswith("/pydantic_core-2.50.2.tar.gz"))
        self.assertIn("/p/pydantic-core/", url)


if __name__ == "__main__":
    unittest.main()