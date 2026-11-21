"""lint.sh 不能再假通过。

报告为空不等于代码干净：jar 损坏、配置缺失、路径写错都会让报告空掉，
旧实现把这些情况一律算成「问题数 0」并以 0 退出，在 CI 里就是一次假通过。
"""

import os
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("lint.sh")
ROOT = SCRIPT.parent.parent


class ScriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = SCRIPT.read_text(encoding="utf-8")

    def test_syntax_is_valid(self):
        result = subprocess.run(["bash", "-n", str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_failure_flag_is_summarised_at_the_end(self):
        self.assertIn("FAILED=0", self.text)
        self.assertRegex(self.text, r'if \[ "\$FAILED" -ne 0 \]; then')
        # 汇总块必须是脚本最后一段逻辑，且以非 0 退出。
        tail = self.text.split('if [ "$FAILED" -ne 0 ]; then', 1)[1]
        self.assertIn("exit 1", tail)

    def test_ktlint_distinguishes_findings_from_execution_failure(self):
        body = self.text.split("run_ktlint() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("local rc=$?", body)
        self.assertRegex(body, r'if \[ "\$rc" -gt 1 \]; then')

    def test_ktlint_preview_matches_plain_reporter_format(self):
        body = self.text.split("run_ktlint() {", 1)[1].split("\n}", 1)[0]
        # plain reporter 的规则名在行尾括号里，行首没有 standard:。
        self.assertNotIn(r"^\s+standard:", body)
        self.assertIn("sed -nE", body)

    def test_detekt_scans_the_same_sources_as_ktlint(self):
        body = self.text.split("run_detekt() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("--input app/src,kernel/src", body)

    def test_detekt_count_and_grouping_use_one_format(self):
        body = self.text.split("run_detekt() {", 1)[1].split("\n}", 1)[0]
        self.assertNotIn("^[A-Za-z]+ - ", body)
        self.assertIn(r"\[[A-Za-z0-9]+\]$", body)

    def test_android_lint_uses_a_stable_report_path(self):
        body = self.text.split("run_lint() {", 1)[1].split("\n}", 1)[0]
        # 只看实际代码行，注释里提到旧路径是为了说明为什么不用它。
        code = "\n".join(
            line for line in body.splitlines() if not line.strip().startswith("#")
        )
        self.assertNotIn("lint_intermediate_text_report", code)
        self.assertIn("android-lint.txt", code)
        self.assertIn("local rc=$?", code)
        gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("textReport = true", gradle)
        self.assertIn("build/lint-reports/android-lint.txt", gradle)

    def test_missing_jar_exits_non_zero(self):
        # 真实执行：把 LINT_HOME 指到空目录，脚本必须报错并非 0 退出。
        with tempfile.TemporaryDirectory() as empty:
            env = dict(os.environ, LINT_HOME=empty)
            result = subprocess.run(
                ["bash", str(SCRIPT), "ktlint"],
                capture_output=True,
                text=True,
                env=env,
                cwd=str(ROOT),
                timeout=120,
            )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("缺少", result.stderr)

    def test_unknown_target_exits_two(self):
        result = subprocess.run(
            ["bash", str(SCRIPT), "no-such-target"],
            capture_output=True,
            text=True,
            cwd=str(ROOT),
            timeout=120,
        )
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()