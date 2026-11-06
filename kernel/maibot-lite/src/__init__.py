from __future__ import annotations

import os


_NATIVE_THREAD_ENV_DEFAULTS = {
    "OMP_NUM_THREADS": "1",
    "OPENBLAS_NUM_THREADS": "1",
    "MKL_NUM_THREADS": "1",
    "NUMEXPR_NUM_THREADS": "1",
}

for _name, _value in _NATIVE_THREAD_ENV_DEFAULTS.items():
    os.environ.setdefault(_name, _value)


def _install_lite_shims() -> None:
    """Android 精简构建：尽早剥离 maim_message 自带的 Web 栈依赖。

    必须在任何 ``maim_message`` 子模块被导入之前执行；``src`` 包初始化是所有
    ``src.*`` 导入的最早公共入口，因此放在这里。开关关闭时完全无副作用。
    """

    if os.environ.get("MAIBOT_LITE_MAIM", "1").strip().lower() not in {"1", "true", "yes", "on"}:
        return
    try:
        from src.common.maim_lite import install

        install()
    except Exception:  # noqa: BLE001 - 精简失败不应阻断正常启动
        pass


_install_lite_shims()
