"""build-core-payload.sh 的构建约束。

这些约束都关系到「会不会把过期或不完整的载荷当成新的发出去」，
所以用脚本文本层面的断言把它们钉住，不依赖真的跑一次内核构建。
"""

import pathlib
import re
import subprocess
import unittest

SCRIPT = pathlib.Path(__file__).with_name("build-core-payload.sh")
ROOT = SCRIPT.parent.parent


def _version_fn():
    """从脚本里取出 node_base_version 的函数体，供真实执行验证。"""
    text = SCRIPT.read_text(encoding="utf-8")
    return "node_base_version() {" + text.split("node_base_version() {", 1)[1].split("\n}", 1)[0] + "\n}"


class ScriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = SCRIPT.read_text(encoding="utf-8")

    def test_syntax_is_valid(self):
        result = subprocess.run(
            ["bash", "-n", str(SCRIPT)], capture_output=True, text=True
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_manifest_is_written_atomically(self):
        # 直接重定向到 payload.txt 会先截断，中途 exit 1 就留下半份清单。
        body = self.text.split("write_payload_manifest() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("payload.txt.tmp", body)
        self.assertIn("mv -f", body)
        self.assertNotRegex(body, r'>\s*"\$ASSETS/payload\.txt"')

    def test_fingerprint_covers_runtime_inputs(self):
        body = self.text.split("kernel_inputs_fingerprint() {", 1)[1].split("\n}", 1)[0]
        for extra in ("assets/provider/zcode-builtin.json", "assets/rg", "assets/core/lib"):
            self.assertIn(extra, body)

    def test_fingerprint_excludes_mutated_license_files(self):
        body = self.text.split("kernel_inputs_fingerprint() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("*/third-party/runtime/sources.json) continue", body)
        self.assertIn("node-*-LICENSE.txt) continue", body)

    def test_license_version_comes_from_base_image(self):
        # 基础镜像是 Android aarch64 二进制，在 x86 构建机上执行会 Exec format error，
        # 所以只能从二进制里读版本字符串，不能运行它。
        self.assertIn("node_base_version() {", self.text)
        self.assertIn(r"node\.js/v[0-9]+\.[0-9]+\.[0-9]+", self.text)
        self.assertNotIn('NODE_VER="$(node -v', self.text)
        self.assertNotIn('"$NODE_BASE" -p', self.text)

    def test_base_image_version_is_readable_without_executing(self):
        base = ROOT / "app" / "src" / "main" / "assets" / "core" / "node"
        if not base.is_file():
            self.skipTest("随包的 node 基础镜像不在本地")
        out = subprocess.run(
            ["bash", "-c", 'NODE_BASE="$1"; ' + _version_fn() + "; node_base_version",
             "_", str(base)],
            capture_output=True,
            text=True,
            timeout=300,
        )
        self.assertRegex(out.stdout.strip(), r"^\d+\.\d+\.\d+$")

    def test_stale_outputs_are_removed_before_build(self):
        self.assertRegex(self.text, r'rm -f "\$BUNDLE" "\$SEA_OUT"')

    def test_missing_tsconfig_is_checked_before_tsc(self):
        self.assertIn("missing_tsconfig=", self.text)
        self.assertRegex(
            self.text,
            r'if \[ ! -f "\$missing_tsconfig" \]; then',
        )

    def test_sea_build_has_one_entry_for_both_modes(self):
        # dry-run 与真实构建共用一个函数，避免两条路径手工维护后预览失真。
        self.assertIn("build_sea_with_retry() {", self.text)
        self.assertEqual(len(re.findall(r"^build_sea_with_retry$", self.text, re.M)), 1)
        body = self.text.split("build_sea_with_retry() {", 1)[1].split("\n}", 1)[0]
        self.assertIn('DRY_RUN:-0', body)
        self.assertIn("tee", body)


if __name__ == "__main__":
    unittest.main()