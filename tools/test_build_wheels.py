"""三个轮子组装脚本的可测行为：RECORD 格式、版本派生、源校验与确定性输出。

对应 issue #693~#699：RECORD 需要 sha256= 前缀（PEP 376/427），缺失的构建产物
必须给出可定位的报错而不是 StopIteration，staging 目录不得用固定 /tmp 路径，
zip 条目顺序必须确定，输出路径必须锚定仓库根。
"""

import base64
import csv
import hashlib
import importlib.util
import io
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile

TOOLS = pathlib.Path(__file__).resolve().parent
ROOT = TOOLS.parent


def load(name):
    spec = importlib.util.spec_from_file_location(
        name.replace("-", "_"), TOOLS / f"{name}.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_script(name, *args, cwd=None):
    return subprocess.run(
        [sys.executable, str(TOOLS / f"{name}.py"), *map(str, args)],
        capture_output=True,
        text=True,
        cwd=str(cwd or ROOT),
    )


def read_record(zf, dist_info):
    rows = list(csv.reader(io.StringIO(zf.read(f"{dist_info}/RECORD").decode())))
    return {row[0]: row for row in rows if row}


class RpdsWheelTest(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="rpds-test-"))
        self.src = self.tmp / "rpds"
        (self.src / "sub").mkdir(parents=True)
        (self.src / "__init__.py").write_text("VERSION = 1\n", encoding="utf-8")
        (self.src / "sub" / "impl.py").write_text("x = 2\n", encoding="utf-8")
        (self.src / "__pycache__").mkdir()
        (self.src / "__pycache__" / "junk.pyc").write_bytes(b"junk")

    def build(self, cwd=None):
        result = run_script("build-rpds-wheel", self.src, cwd=cwd)
        self.assertEqual(result.returncode, 0, result.stderr)
        target = pathlib.Path(result.stdout.strip().splitlines()[-1])
        self.addCleanup(target.unlink, missing_ok=True)
        return target

    def test_missing_source_fails_with_actionable_message(self):
        missing = self.tmp / "nowhere"
        result = run_script("build-rpds-wheel", missing)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(missing), result.stderr)

    def test_source_without_init_is_rejected(self):
        empty = self.tmp / "empty"
        empty.mkdir()
        result = run_script("build-rpds-wheel", empty)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(empty), result.stderr)

    def test_record_hashes_have_sha256_prefix(self):
        target = self.build()
        with zipfile.ZipFile(target) as zf:
            rows = read_record(zf, "rpds_py-0.25.0.dist-info")
        entry = rows["rpds/__init__.py"]
        self.assertTrue(entry[1].startswith("sha256="), entry)
        digest = base64.urlsafe_b64encode(
            hashlib.sha256(b"VERSION = 1\n").digest()
        ).rstrip(b"=").decode()
        self.assertEqual(entry[1], "sha256=" + digest)
        self.assertEqual(entry[2], str(len(b"VERSION = 1\n")))

    def test_zip_entries_are_sorted_and_pycache_excluded(self):
        target = self.build()
        with zipfile.ZipFile(target) as zf:
            names = zf.namelist()
        self.assertEqual(names, sorted(names))
        self.assertNotIn("rpds/__pycache__/junk.pyc", names)
        self.assertIn("rpds/sub/impl.py", names)

    def test_output_is_anchored_to_repo_root_not_cwd(self):
        other = self.tmp / "elsewhere"
        other.mkdir()
        target = self.build(cwd=other)
        self.assertEqual(target.parent, ROOT / "app" / "wheels")
        self.assertFalse((other / "app" / "wheels").exists())

    def test_build_is_reproducible(self):
        first = self.build().read_bytes()
        second = self.build().read_bytes()
        self.assertEqual(first, second)


class JiterWheelTest(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="jiter-test-"))
        self.src = self.tmp / "jiter-0.18.0"
        release = self.src / "target" / "aarch64-linux-android" / "release"
        release.mkdir(parents=True)
        (release / "libjiter_python.so").write_bytes(b"\x7fELF-fake")

    def test_missing_so_fails_with_expected_path(self):
        (self.src / "target" / "aarch64-linux-android" / "release" / "libjiter_python.so").unlink()
        result = run_script("build-jiter-wheel", self.src)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("libjiter_python.so", result.stderr)
        self.assertNotIn("StopIteration", result.stderr)

    def test_record_hashes_have_sha256_prefix_and_output_sorted(self):
        result = run_script("build-jiter-wheel", self.src)
        self.assertEqual(result.returncode, 0, result.stderr)
        target = pathlib.Path(result.stdout.strip().splitlines()[-1])
        self.addCleanup(target.unlink, missing_ok=True)
        self.assertEqual(target.parent, ROOT / "app" / "wheels")
        with zipfile.ZipFile(target) as zf:
            names = zf.namelist()
            rows = read_record(zf, "jiter-0.18.0.dist-info")
        self.assertEqual(names, sorted(names))
        so_entry = rows["jiter.cpython-312-aarch64-linux-android.so"]
        self.assertTrue(so_entry[1].startswith("sha256="), so_entry)

    def test_version_comes_from_source_dir(self):
        renamed = self.tmp / "jiter-0.19.1"
        self.src.rename(renamed)
        result = run_script("build-jiter-wheel", renamed)
        self.assertEqual(result.returncode, 0, result.stderr)
        target = pathlib.Path(result.stdout.strip().splitlines()[-1])
        self.addCleanup(target.unlink, missing_ok=True)
        self.assertIn("jiter-0.19.1-", target.name)


class PydanticWheelTest(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="pydantic-test-"))
        self.src = self.tmp / "pydantic_core-2.50.2"
        release = self.src / "target" / "aarch64-linux-android" / "release"
        release.mkdir(parents=True)
        (release / "libpydantic_core.so").write_bytes(b"\x7fELF-fake")
        pkg = self.src / "python" / "pydantic_core"
        pkg.mkdir(parents=True)
        (pkg / "__init__.py").write_text("from ._pydantic_core import *\n", encoding="utf-8")

    def build(self):
        result = run_script("build-pydantic-wheel", self.src)
        self.assertEqual(result.returncode, 0, result.stderr)
        target = pathlib.Path(result.stdout.strip().splitlines()[-1])
        self.addCleanup(target.unlink, missing_ok=True)
        return target

    def test_missing_so_fails_with_expected_path(self):
        (self.src / "target" / "aarch64-linux-android" / "release" / "libpydantic_core.so").unlink()
        result = run_script("build-pydantic-wheel", self.src)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("aarch64-linux-android", result.stderr)
        self.assertNotIn("StopIteration", result.stderr)

    def test_version_derived_from_source_dir_everywhere(self):
        renamed = self.tmp / "pydantic_core-2.51.0"
        self.src.rename(renamed)
        result = run_script("build-pydantic-wheel", renamed)
        self.assertEqual(result.returncode, 0, result.stderr)
        target = pathlib.Path(result.stdout.strip().splitlines()[-1])
        self.addCleanup(target.unlink, missing_ok=True)
        self.assertIn("pydantic_core-2.51.0-", target.name)
        with zipfile.ZipFile(target) as zf:
            names = zf.namelist()
            meta = zf.read("pydantic_core-2.51.0.dist-info/METADATA").decode()
            rows = read_record(zf, "pydantic_core-2.51.0.dist-info")
        self.assertIn("Version: 2.51.0", meta)
        self.assertIn("pydantic_core-2.51.0.dist-info/RECORD", rows)
        self.assertEqual(names, sorted(names))
        entry = rows["pydantic_core/__init__.py"]
        self.assertTrue(entry[1].startswith("sha256="), entry)


class WheelHelpersTest(unittest.TestCase):
    def test_record_line_uses_pep376_format(self):
        helpers = load("wheel_helpers")
        line = helpers.record_line("pkg/mod.py", b"data")
        path, digest, size = line.split(",")
        self.assertEqual(path, "pkg/mod.py")
        self.assertTrue(digest.startswith("sha256="))
        self.assertEqual(size, "4")

    def test_single_artifact_reports_missing_path(self):
        helpers = load("wheel_helpers")
        tmp = pathlib.Path(tempfile.mkdtemp(prefix="helper-test-"))
        with self.assertRaises(SystemExit) as ctx:
            helpers.single_so(tmp, "libfoo.so")
        self.assertIn("libfoo.so", str(ctx.exception))

    def test_single_artifact_rejects_ambiguous_candidates(self):
        helpers = load("wheel_helpers")
        tmp = pathlib.Path(tempfile.mkdtemp(prefix="helper-test-"))
        (tmp / "a.so").write_bytes(b"a")
        (tmp / "b.so").write_bytes(b"b")
        with self.assertRaises(SystemExit) as ctx:
            helpers.single_so(tmp, "*.so")
        self.assertIn("2", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
