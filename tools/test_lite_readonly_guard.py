"""只读命令守卫的边界（#794、#795）。

守卫是内核 lite 侧唯一的一道屏障：命令最终经 `/bin/bash -lc` 在宿主 Linux 环境执行。
两类错误后果相反但都严重——误杀让 git log/diff/show 这些常用只读命令全被拒；
漏放让 branch/tag 的长选项与位置参数直接改 refs。

放在 tools/ 下用 unittest 写，是为了跟 CI 已有的 `unittest discover -s tools` 一起跑；
kernel/maibot-lite/tests 用的是 pytest，CI 目前没有执行它。
"""

import importlib.util
import pathlib
import unittest

SRC = pathlib.Path(__file__).resolve().parents[1] / "kernel/maibot-lite/src/lite_coding_tool.py"


def load_guard():
    """只取守卫函数，不导入整个模块（它依赖 aiohttp 等运行时包）。"""
    text = SRC.read_text(encoding="utf-8")
    start = text.index("_READONLY_COMMANDS")
    end = text.index("def configure_host_controller")
    body = text[start:end]
    ns = {"Optional": __import__("typing").Optional}
    exec(compile(body, str(SRC), "exec"), ns)
    return ns["_readonly_violation"]


class ReadonlyGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.violation = staticmethod(load_guard())

    def deny(self, cmd):
        self.assertIsNotNone(self.violation(cmd), f"应当拒绝：{cmd}")

    def allow(self, cmd):
        self.assertIsNone(self.violation(cmd), f"应当放行：{cmd}（原因：{self.violation(cmd)}）")

    # ---------------------------------------------------------- #794 误杀

    def test_pretty_is_allowed(self):
        self.allow("git log --pretty=oneline -20")
        self.allow("git log --pretty")
        self.allow("git show --pretty=format:%H HEAD")
        self.allow("git diff --pretty=short")

    def test_pre_exact_token_is_denied(self):
        self.deny("grep --pre foo .")
        self.deny("grep --pre=foo .")

    def test_other_banned_flags_still_denied(self):
        self.deny("find . -exec rm {} +")
        self.deny("grep --output=/tmp/x foo .")
        self.deny("git log --open-files-in-pager=evil")

    # ---------------------------------------------------------- #795 漏放

    def test_branch_long_write_flags_denied(self):
        for cmd in (
            "git branch --delete feature",
            "git branch --move old new",
            "git branch --copy a b",
            "git branch --force feature HEAD",
            "git branch --set-upstream-to=origin/main",
            "git branch --unset-upstream",
            "git branch --edit-description",
        ):
            self.deny(cmd)

    def test_branch_positional_creation_denied(self):
        self.deny("git branch newbranch")
        self.deny("git branch newbranch HEAD")

    def test_branch_listing_allowed(self):
        self.allow("git branch")
        self.allow("git branch -a")
        self.allow("git branch --list")
        self.allow("git branch -r --contains HEAD")
        self.allow("git branch --show-current")
        self.allow("git branch -vv")

    def test_tag_long_write_flags_denied(self):
        for cmd in (
            "git tag --annotate v1",
            "git tag --sign v1",
            "git tag --message=hi v1",
            "git tag --file=msg.txt v1",
            "git tag --force v1",
            "git tag --delete v1",
        ):
            self.deny(cmd)

    def test_tag_positional_creation_denied(self):
        self.deny("git tag v1")
        self.deny("git tag v1 HEAD")

    def test_tag_listing_allowed(self):
        self.allow("git tag")
        self.allow("git tag -l")
        self.allow("git tag --list v*")
        self.allow("git tag -n")

    # ---------------------------------------------------------- 既有边界不能退化

    def test_shell_tokens_denied(self):
        self.deny("git status > /tmp/x")
        self.deny("git status && rm -rf /")
        self.deny("cat $(whoami)")

    def test_write_subcommands_denied(self):
        self.deny("git commit -m x")
        self.deny("git push")
        self.deny("rm -rf /")

    def test_common_readonly_allowed(self):
        self.allow("git status")
        self.allow("git diff HEAD~1")
        self.allow("git log -5")
        self.allow("ls -la")
        self.allow("cat README.md")


if __name__ == "__main__":
    unittest.main()