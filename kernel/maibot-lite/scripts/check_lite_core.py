"""精简构建门禁：验证核心链路不依赖重型 Web/科学计算栈。

在 CI 中作为质量门槛运行。若上游改动重新引入 fastapi/uvicorn/scipy/pandas 等
重依赖到核心导入链，本脚本会失败，从而阻止构建出体积失控的包。

用法：
    python scripts/check_lite_core.py            # 仅静态+导入检查
    python scripts/check_lite_core.py --strict   # 同时校验原生扩展数量上限
"""

from __future__ import annotations

import argparse
import sys
import sysconfig
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# 精简构建中必须"即使装了也不许被核心导入"的重型依赖
FORBIDDEN = (
    "fastapi",
    "uvicorn",
    "starlette",
    "sse_starlette",
    "pandas",
    "scipy",
    "pyarrow",
    "faiss",
    "playwright",
    "matplotlib",
)

# 精简构建放弃的子系统；核心链路不得硬依赖它们
FORBIDDEN_PREFIXES = ("src.A_memorix",)

# src.agent 中仅 config 允许（纯 pydantic 配置定义，成本极低）
AGENT_ALLOWED = {"src.agent", "src.agent.config"}

# 允许出现的第三方原生扩展（均为 aarch64 可得）
ALLOWED_NATIVE = {
    "PIL",
    "greenlet",
    "msgpack",
    "numpy",
    "pydantic_core",
    "rapidfuzz",
    "rpds",
    "sqlalchemy",
    "watchfiles",
    "zstandard",
    "mmap",
}

NATIVE_LIMIT = 12

# 下限：核心链路必须至少加载这些原生扩展，否则说明检测逻辑失效
# （曾出现 setup-python 的 site-packages 位于 stdlib 下被误判为内置，导致检出 0 个）
REQUIRED_NATIVE = {"numpy", "pydantic_core", "sqlalchemy"}


class LiteGate:
    """导入拦截器：模拟"这些包在目标平台上不可用"。"""

    def __init__(self) -> None:
        self.blocked: list[str] = []

    def find_spec(self, fullname, path=None, target=None):
        top = fullname.split(".")[0]
        if top in FORBIDDEN:
            raise ImportError(f"[lite-core] BLOCKED heavy dependency: {fullname}")
        if any(fullname == p or fullname.startswith(p + ".") for p in FORBIDDEN_PREFIXES):
            raise ImportError(f"[lite-core] BLOCKED disabled subsystem: {fullname}")
        if fullname.startswith("src.agent") and fullname not in AGENT_ALLOWED:
            raise ImportError(f"[lite-core] BLOCKED agent subsystem: {fullname}")
        return None


def collect_native_extensions() -> list[str]:
    """已加载的**第三方**原生扩展（顶层名）。

    判定方式：看 ``__file__`` 相对 stdlib 的层级。
    - CPython 内置 C 扩展位于 ``<stdlib>/lib-dynload/``（如 ``math``/``zlib``）
      或直接位于 ``<stdlib>/``（如 ``_ssl`` 在部分构建下）
    - 第三方包位于 ``<stdlib>/site-packages/``（system python 安装）
      或 ``<venv>/lib/pythonX.Y/site-packages/``（独立于 stdlib）

    注意不能简单用 "path 在 stdlib 下" 判断：使用 actions/setup-python 时，
    site-packages 本身就在 stdlib 目录里，那样会把第三方包误判为内置。
    """

    stdlib = Path(sysconfig.get_paths()["stdlib"]).resolve()
    names = set()
    for name, module in list(sys.modules.items()):
        path_str = getattr(module, "__file__", "") or ""
        if not path_str.endswith(".so"):
            continue
        top = name.split(".")[0]
        if top.startswith("_"):
            continue
        path = Path(path_str).resolve()

        # 判断该 .so 是否位于 stdlib 的 site-packages 之外
        rel = None
        try:
            rel = path.relative_to(stdlib)
        except ValueError:
            # 不在 stdlib 下（典型情况：venv 的 site-packages）—— 第三方
            names.add(top)
            continue

        # 位于 stdlib 下：只有 lib-dynload 与 stdlib 根目录才算内置
        parts = rel.parts
        if len(parts) >= 2 and parts[0] == "lib-dynload":
            continue
        if len(parts) == 1:
            continue
        # site-packages / dist-packages 下 —— 第三方
        names.add(top)
    return sorted(names)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--strict", action="store_true", help="额外校验原生扩展数量上限")
    args = parser.parse_args()

    sys.path.insert(0, str(REPO_ROOT))
    gate = LiteGate()
    sys.meta_path.insert(0, gate)

    failures: list[str] = []

    # 1) maim_message shim 必须由 src 包初始化自动安装
    try:
        import src  # noqa: F401
    except Exception as exc:  # noqa: BLE001
        failures.append(f"import src 失败: {exc}")
        return report(failures)

    shim = sys.modules.get("maim_message")
    if not getattr(shim, "_maibot_lite_shim", False):
        failures.append("maim_message 精简 shim 未生效（src/__init__.py 未安装）")

    # 2) 核心入口必须可导入
    try:
        from src.chat.message_receive.bot import chat_bot

        if not hasattr(chat_bot, "message_process"):
            failures.append("chat_bot 缺少 message_process 入口")
    except Exception as exc:  # noqa: BLE001
        failures.append(f"核心入口导入失败: {type(exc).__name__}: {exc}")

    # 3) 重依赖不得泄漏
    leaked = [m for m in sys.modules if m.split(".")[0] in FORBIDDEN]
    if leaked:
        failures.append(f"重依赖泄漏 {len(leaked)} 个: {sorted(leaked)[:5]}")

    # 4) 原生扩展白名单
    natives = collect_native_extensions()
    missing = sorted(REQUIRED_NATIVE - set(natives))
    if missing:
        failures.append(
            f"未检测到本应加载的原生扩展 {missing}（检测逻辑可能失效）"
        )
    unexpected = [n for n in natives if n not in ALLOWED_NATIVE]
    if unexpected:
        failures.append(f"出现未预期的原生扩展: {unexpected}")

    if args.strict and len(natives) > NATIVE_LIMIT:
        failures.append(f"原生扩展数量 {len(natives)} 超过上限 {NATIVE_LIMIT}")

    print(f"[lite-core] 已加载第三方原生扩展 ({len(natives)}): {natives}")
    return report(failures)


def report(failures: list[str]) -> int:
    if failures:
        print("\n[lite-core] 门禁未通过：", file=sys.stderr)
        for item in failures:
            print(f"  - {item}", file=sys.stderr)
        return 1
    print("[lite-core] 门禁通过：核心链路无重依赖。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())