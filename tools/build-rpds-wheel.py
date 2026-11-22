"""把仓库内的纯 Python rpds 实现组装成 rpds-py 分发轮子。

jsonschema 与 referencing 在依赖里硬要求 rpds-py>=0.25.0，而 Chaquopy 源里没有
Android 轮子。这里用 kernel/maibot-lite/rpds 的实现充当该分发，版本号按依赖下限
声明，运行时仍是纯 Python 的写时复制结构。
"""

from __future__ import annotations

import pathlib
import shutil
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import wheel_helpers

VERSION = "0.25.0"

root = pathlib.Path(__file__).resolve().parent.parent
src = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / "kernel" / "maibot-lite" / "rpds"
if not (src / "__init__.py").is_file():
    sys.exit(f"rpds source package not found: {src}（需要含 __init__.py 的包目录）")

# staging 用一次性目录：固定 /tmp 路径会被并发构建互相清掉。
out = pathlib.Path(tempfile.mkdtemp(prefix="rpds-whl-"))
try:
    pkg = out / "rpds"
    pkg.mkdir(parents=True)
    for path in sorted(src.rglob("*")):
        if path.is_file() and "__pycache__" not in path.parts:
            target = pkg / path.relative_to(src)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(path, target)

    dist_info = f"rpds_py-{VERSION}.dist-info"
    di = out / dist_info
    di.mkdir()
    (di / "METADATA").write_text(
        f"Metadata-Version: 2.1\nName: rpds-py\nVersion: {VERSION}\nRequires-Python: >=3.9\n"
    )
    (di / "WHEEL").write_text(
        "Wheel-Version: 1.0\nGenerator: coda\nRoot-Is-Purelib: true\nTag: py3-none-any\n"
    )

    # 输出锚定仓库根：从其他 CWD 调用时不能把轮子写进陌生目录。
    target = root / "app" / "wheels" / f"rpds_py-{VERSION}-py3-none-any.whl"
    wheel_helpers.write_record_and_zip(out, dist_info, target)
    print(target)
finally:
    shutil.rmtree(out, ignore_errors=True)