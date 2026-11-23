"""把 cargo 编出的 pydantic-core .so 组装成 Chaquopy 可安装的 Android wheel。

版本从源目录名派生（pydantic_core-<version>），dist-info、METADATA、RECORD 与
输出文件名共用同一版本，换源树时不再产出标错版本的轮子。
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
src = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / "build" / "pydantic-src"
if not src.is_dir():
    sys.exit(f"pydantic-core 源目录不存在：{src}")

# OCR #819：不带后缀锚定的 \d+\.\d+\.\d+ 会把 2.50.2rc1 截成 2.50.2，
# METADATA/dist-info/文件名全标错版本。捕获完整 PEP 440 版本（含 rc/dev/post 后缀）。
match = re.fullmatch(r"pydantic_core-(\d+\.\d+\.\d+(?:(?:a|b|rc|\.dev|\.post)\d+)*)", src.name)
if not match:
    raise SystemExit(f"无法从目录名解析版本：{src.name}")
version = match.group(1)

so = wheel_helpers.single_so(src / "target" / "aarch64-linux-android" / "release", "*.so")

out = pathlib.Path(tempfile.mkdtemp(prefix="pydantic-whl-"))
try:
    pkg = out / "pydantic_core"
    shutil.copytree(src / "python" / "pydantic_core", pkg)
    shutil.copy(so, pkg / "_pydantic_core.cpython-312-aarch64-linux-android.so")

    dist_info = f"pydantic_core-{version}.dist-info"
    di = out / dist_info
    di.mkdir()
    (di / "METADATA").write_text(
        f"Metadata-Version: 2.1\nName: pydantic-core\nVersion: {version}\nRequires-Python: >=3.9\n"
    )
    (di / "WHEEL").write_text(
        "Wheel-Version: 1.0\nGenerator: coda\nRoot-Is-Purelib: false\nTag: cp312-cp312-android_26_arm64_v8a\n"
    )

    target = (
        root / "app" / "wheels"
        / f"pydantic_core-{version}-cp312-cp312-android_26_arm64_v8a.whl"
    )
    wheel_helpers.write_record_and_zip(out, dist_info, target)
    print(target)
finally:
    shutil.rmtree(out, ignore_errors=True)