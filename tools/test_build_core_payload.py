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
        # #799：路径必须相对 $ASSETS（app/src/main/assets/core/），
        # 写成 $ROOT/assets/… 会永远 hash 成 missing，指纹形同虚设。
        for extra in ("provider/zcode-builtin.json", "rg", "lib"):
            self.assertIn(extra, body)
        self.assertIn('"$ASSETS/$extra"', body)
        self.assertIn('"$ASSETS/lib"', body)
        self.assertNotIn('"$ROOT/assets/', body)

    def test_fingerprint_hashes_versioned_sonames(self):
        # #800：随包库多为版本化 soname（libcrypto.so.3、libicu*.so.78），
        # `-name '*.so'` 只匹配到一小半，换了库指纹也不变。
        body = self.text.split("kernel_inputs_fingerprint() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("'*.so*'", body)

    def test_fingerprint_extras_actually_hashed(self):
        # 真实执行：在临时目录搭一份最小 ROOT，指纹函数必须把 extras 与
        # 版本化 so 都算进去（而不是 missing），改内容指纹必须变。
        body = self.text.split("kernel_inputs_fingerprint() {", 1)[1].split("\n}", 1)[0]
        fn = "kernel_inputs_fingerprint() {" + body + "\n}"
        digest = self.text.split("file_digest() {", 1)[1].split("\n}", 1)[0]
        stream = self.text.split("stream_digest() {", 1)[1].split("\n}", 1)[0]
        harness = (
            "set -euo pipefail\n"
            'ROOT="$1"; ASSETS="$ROOT/app/src/main/assets/core"; NODE_BASE="$ASSETS/node"\n'
            "file_digest() {" + digest + "\n}\n"
            "stream_digest() {" + stream + "\n}\n"
            + fn + "\nkernel_inputs_fingerprint\n"
        )
        import tempfile

        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            assets = root / "app/src/main/assets/core"
            (assets / "provider").mkdir(parents=True)
            (assets / "lib").mkdir()
            (assets / "node").write_bytes(b"base")
            (assets / "rg").write_bytes(b"rg-v1")
            (assets / "provider/zcode-builtin.json").write_text("{}")
            (assets / "lib/libcrypto.so.3").write_bytes(b"crypto-v1")
            subprocess.run(["git", "init", "-q", str(root)], check=True)

            def fp():
                out = subprocess.run(
                    ["bash", "-c", harness, "_", str(root)],
                    capture_output=True, text=True, timeout=60,
                )
                self.assertEqual(out.returncode, 0, out.stderr)
                return out.stdout.strip()

            first = fp()
            self.assertTrue(first)
            # 换 rg 内容：指纹必须变（旧实现路径错，永远 missing，不会变）。
            (assets / "rg").write_bytes(b"rg-v2")
            self.assertNotEqual(first, fp())
            second = fp()
            # 换版本化 so：同样必须变。
            (assets / "lib/libcrypto.so.3").write_bytes(b"crypto-v2")
            self.assertNotEqual(second, fp())

    def test_manifest_failure_cleans_tmp(self):
        # #798：payload_line 用 exit 1，brace group 在当前 shell 执行，
        # exit 直接终止整个脚本，|| 清理分支永不执行，tmp 残留会被打进 APK。
        # 真实执行验证：缺文件时 tmp 必须被清掉、旧清单必须保留。
        body = self.text.split("write_payload_manifest() {", 1)[1].split("\n}", 1)[0]
        line = self.text.split("payload_line() {", 1)[1].split("\n}", 1)[0]
        digest = self.text.split("file_digest() {", 1)[1].split("\n}", 1)[0]
        harness = (
            "set -uo pipefail\n"
            'ASSETS="$1"\n'
            "file_digest() {" + digest + "\n}\n"
            "payload_line() {" + line + "\n}\n"
            "write_payload_manifest() {" + body + "\n}\n"
            "write_payload_manifest\n"
        )
        import tempfile

        with tempfile.TemporaryDirectory() as td:
            assets = pathlib.Path(td)
            (assets / "payload.txt").write_text("old manifest\n")
            # zcode 存在但 zcode.cjs 缺失：payload_line 中途 exit 1。
            (assets / "zcode").write_bytes(b"x")
            out = subprocess.run(
                ["bash", "-c", harness, "_", str(assets)],
                capture_output=True, text=True, timeout=60,
            )
            self.assertNotEqual(out.returncode, 0)
            self.assertEqual((assets / "payload.txt").read_text(), "old manifest\n")
            leftovers = [p.name for p in assets.iterdir() if ".tmp" in p.name]
            self.assertEqual(leftovers, [], f"tmp 清单残留：{leftovers}")

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