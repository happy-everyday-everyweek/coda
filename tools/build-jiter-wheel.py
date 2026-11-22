"""把 cargo 编出的 jiter .so 组装成 Chaquopy 可安装的 Android wheel。

jiter 是 openai 的硬依赖，Chaquopy 源里没有它的分发，只能自己交叉编译。
maturin 的 module-name 是 jiter（顶层扩展模块），因此 wheel 里直接放
jiter.cpython-312-aarch64-linux-android.so，而不是包目录。
"""

from __future__ import annotations

import pathlib
import re
import shutil
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import wheel_helpers

root = pathlib.Path(__file__).resolve().parent.parent
src = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / "build" / "jiter-src"
if not src.is_dir():
    sys.exit(f"jiter 源目录不存在：{src}")

match = re.search(r"jiter-(\d+\.\d+\.\d+)", src.name)
if not match:
    raise SystemExit(f"无法从目录名解析版本：{src.name}")
version = match.group(1)

so = wheel_helpers.single_so(
    src / "target" / "aarch64-linux-android" / "release", "libjiter_python.so"
)

out = pathlib.Path(tempfile.mkdtemp(prefix="jiter-whl-"))
try:
    shutil.copy(so, out / "jiter.cpython-312-aarch64-linux-android.so")

    dist_info = f"jiter-{version}.dist-info"
    di = out / dist_info
    di.mkdir()
    (di / "METADATA").write_text(
        f"Metadata-Version: 2.1\nName: jiter\nVersion: {version}\nRequires-Python: >=3.8\n"
    )
    (di / "WHEEL").write_text(
        "Wheel-Version: 1.0\nGenerator: coda\nRoot-Is-Purelib: false\nTag: cp312-cp312-android_26_arm64_v8a\n"
    )

    target = (
        root / "app" / "wheels" / f"jiter-{version}-cp312-cp312-android_26_arm64_v8a.whl"
    )
    wheel_helpers.write_record_and_zip(out, dist_info, target)
    print(target)
finally:
    shutil.rmtree(out, ignore_errors=True)