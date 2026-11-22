"""sync-maibot-core.sh 的防御行为。

对应 issue #726/#727/#728：源树缺失时不得先毁掉 staging 目录再报 cp 错误；
allow-list 中任何模块缺失都要指名道姓地失败；msgpack 垫片必须整目录复制，
不能只带 __init__.py 把后续新增模块静默丢掉。
"""

import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("sync-maibot-core.sh")
ROOT = SCRIPT.parent.parent
OUT = ROOT / "app" / "build" / "maibot-core"

MODULES = (
    "chat common config core emoji_system learners llm_models manager maisaka "
    "mcp_module person_info platform_io plugin_runtime prompt services web_search"
).split()


def make_fake_source(base: pathlib.Path) -> pathlib.Path:
    src = base / "maibot-lite"
    for name in MODULES:
        module = src / "src" / name
        module.mkdir(parents=True)
        (module / "__init__.py").write_text("", encoding="utf-8")
    for name in ("__init__.py", "lite_runtime.py", "lite_android_bridge.py", "lite_coding_tool.py"):
        (src / "src" / name).write_text("", encoding="utf-8")
    agent = src / "src" / "agent"
    agent.mkdir(parents=True)
    (agent / "__init__.py").write_text("", encoding="utf-8")
    (agent / "config.py").write_text("", encoding="utf-8")
    (src / "prompts").mkdir()
    (src / "prompts" / "p.txt").write_text("p", encoding="utf-8")
    (src / "locales").mkdir()
    (src / "locales" / "l.json").write_text("{}", encoding="utf-8")
    (src / "msgpack").mkdir()
    (src / "msgpack" / "__init__.py").write_text("", encoding="utf-8")
    (src / "jieba").mkdir()
    (src / "jieba" / "__init__.py").write_text("", encoding="utf-8")
    return src


def run_sync(source: pathlib.Path):
    return subprocess.run(
        ["bash", str(SCRIPT)],
        capture_output=True,
        text=True,
        env={"MAIBOT_CORE_SOURCE": str(source), "PATH": "/usr/bin:/bin:/usr/local/bin"},
    )


class SyncGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="maibot-sync-test-"))

    def test_syntax_is_valid(self):
        result = subprocess.run(["bash", "-n", str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_source_fails_before_destroying_staging(self):
        OUT.mkdir(parents=True, exist_ok=True)
        sentinel = OUT / "sentinel.txt"
        sentinel.write_text("keep me", encoding="utf-8")
        self.addCleanup(sentinel.unlink, missing_ok=True)
        missing = self.tmp / "not-checked-out"
        result = run_sync(missing)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(missing), result.stderr)
        self.assertTrue(sentinel.exists(), "staging 目录在校验前被毁掉了")

    def test_missing_allow_list_module_names_the_module(self):
        src = make_fake_source(self.tmp)
        import shutil

        shutil.rmtree(src / "src" / "maisaka")
        result = run_sync(src)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("maisaka", result.stderr)

    def test_msgpack_shim_copies_whole_directory(self):
        src = make_fake_source(self.tmp)
        (src / "msgpack" / "fallback.py").write_text("# shim module\n", encoding="utf-8")
        result = run_sync(src)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((OUT / "msgpack" / "__init__.py").is_file())
        self.assertTrue(
            (OUT / "msgpack" / "fallback.py").is_file(),
            "msgpack 垫片的附加模块被静默丢弃",
        )

    def test_full_fake_source_stages_successfully(self):
        src = make_fake_source(self.tmp)
        result = run_sync(src)
        self.assertEqual(result.returncode, 0, result.stderr)
        for name in MODULES:
            self.assertTrue((OUT / "src" / name).is_dir(), name)
        self.assertTrue((OUT / "src" / "agent" / "config.py").is_file())
        self.assertFalse((OUT / "src" / "agent" / "tools").exists())


class AndroidWheelsScriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = SCRIPT.with_name("build-android-wheels.sh").read_text(encoding="utf-8")

    def test_syntax_is_valid(self):
        result = subprocess.run(
            ["bash", "-n", str(SCRIPT.with_name("build-android-wheels.sh"))],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_work_dirs_are_per_run_with_cleanup_trap(self):
        self.assertIn("mktemp -d", self.text)
        self.assertRegex(self.text, r"trap .*EXIT")

    def test_no_fixed_tmp_work_paths(self):
        for fixed in ("/tmp/fakelib", "/tmp/pc", "/tmp/jt"):
            self.assertNotIn(fixed, self.text, f"固定路径 {fixed} 会让并发构建互相清掉对方")


if __name__ == "__main__":
    unittest.main()