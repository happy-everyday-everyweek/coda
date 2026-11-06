"""把仓库内的纯 Python rpds 实现组装成 rpds-py 分发轮子。

jsonschema 与 referencing 在依赖里硬要求 rpds-py>=0.25.0，而 Chaquopy 源里没有
Android 轮子。这里用 kernel/maibot-lite/rpds 的实现充当该分发，版本号按依赖下限
声明，运行时仍是纯 Python 的写时复制结构。
"""

from __future__ import annotations

import base64
import hashlib
import pathlib
import shutil
import sys
import zipfile

VERSION = "0.25.0"

root = pathlib.Path(__file__).resolve().parent.parent
src = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / "kernel" / "maibot-lite" / "rpds"
out = pathlib.Path("/tmp/rpds-whl")
shutil.rmtree(out, ignore_errors=True)

pkg = out / "rpds"
pkg.mkdir(parents=True)
for path in src.rglob("*"):
    if path.is_file() and "__pycache__" not in path.parts:
        target = pkg / path.relative_to(src)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(path, target)

di = out / f"rpds_py-{VERSION}.dist-info"
di.mkdir()
(di / "METADATA").write_text(
    f"Metadata-Version: 2.1\nName: rpds-py\nVersion: {VERSION}\nRequires-Python: >=3.9\n"
)
(di / "WHEEL").write_text(
    "Wheel-Version: 1.0\nGenerator: coda\nRoot-Is-Purelib: true\nTag: py3-none-any\n"
)

records: list[str] = []
for path in sorted(out.rglob("*")):
    if path.is_file():
        data = path.read_bytes()
        digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
        records.append(f"{path.relative_to(out)},{digest},{len(data)}")
records.append(f"rpds_py-{VERSION}.dist-info/RECORD,,")
(di / "RECORD").write_text("\n".join(records) + "\n")

target = pathlib.Path(f"app/wheels/rpds_py-{VERSION}-py3-none-any.whl")
target.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in out.rglob("*"):
        if path.is_file():
            archive.write(path, path.relative_to(out))
print(target)
