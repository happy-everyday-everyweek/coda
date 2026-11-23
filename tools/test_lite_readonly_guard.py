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

    # ---------------------------------------------------------- OCR 复审：bash 引号剥离
    def test_quoted_pre_is_denied(self):
        # 命令经 /bin/bash -lc 执行，引号在守卫之后才被剥掉：
        # 按原始空白切分会把 `'--pre=...'` 当成无害 token 放行。
        self.deny("rg '--pre=touch /tmp/pwned #' x .")
        self.deny("rg --p're' x .")
        self.deny('grep "--pre=evil" foo .')
        self.deny("git log --pre\\=evil")
    def test_quoted_pretty_still_allowed(self):
        # shlex 剥引号后 --pretty 仍须放行，不能因修复而误杀。
        self.allow("git log '--pretty=oneline' -20")
        self.allow('git show "--pretty=format:%H" HEAD')
    def test_unbalanced_quotes_denied(self):
        self.deny("rg 'unterminated x .")
    # ---------------------------------------------------------- OCR 复审：bash 展开
    def test_brace_expansion_denied(self):
        # shlex 不建模 brace expansion：`--list {victim,-d}` 被当成一个
        # 模式 token 放行，bash 展开后却是 `--list victim -d`（删分支）。
        # 只读检查用不到花括号展开，出现即拒绝（fail-closed）。
        self.deny("git branch --list {victim,-d}")
        self.deny("git tag {v1,-d}")
        self.deny("ls {a,b}")
    def test_variable_expansion_denied(self):
        # 未加引号的 $VAR 会做词拆分，shlex 同样不建模：
        # `git branch $X` 里 X='-d victim' 时守卫看到的是无害单 token。
        self.deny("git branch $X")
        self.deny('git tag "$TAGS"')
        self.deny("ls ~/x")
    # ---------------------------------------------------------- OCR 复审：白名单吞值
    def test_list_flag_does_not_swallow_write_flag(self):
        # --list/-l 是布尔模式开关，不吃值；盲目 skip_next 会把
        # 紧随的写选项当「值」放过去。
        self.deny("git branch --list --unset-upstream")
        self.deny("git tag --list --delete=v1")
        self.deny("git tag -l --force v1")
    def test_optional_value_flags_do_not_swallow_next(self):
        # --color/--column 的可选值只能用 = 附着；裸用时不消费下一个 token，
        # 否则 `--column --list v1` 里的 --list 被当值吞掉、v1 反而按位置参数误拒，
        # 或更糟：吞掉的恰是白名单项而放行后续写操作。
        self.deny("git branch --color --delete victim")
        self.deny("git tag --column --delete v1")
        self.allow("git tag --column --list v1")
        self.allow("git branch --color=always --list")
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