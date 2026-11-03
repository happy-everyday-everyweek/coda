"""把 cargo 编出的 pydantic-core .so 组装成 Chaquopy 可安装的 Android wheel。"""

from __future__ import annotations

import base64
import hashlib
import pathlib
import shutil
import sys
import zipfile

src = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/pc/pydantic_core-2.50.2")
out = pathlib.Path("/tmp/whl")
shutil.rmtree(out, ignore_errors=True)

so = next(src.glob("target/aarch64-linux-android/release/*.so"))
pkg = out / "pydantic_core"
shutil.copytree(src / "python" / "pydantic_core", pkg)
shutil.copy(so, pkg / "_pydantic_core.cpython-312-aarch64-linux-android.so")

di = out / "pydantic_core-2.50.2.dist-info"
di.mkdir()
(di / "METADATA").write_text(
    "Metadata-Version: 2.1\nName: pydantic-core\nVersion: 2.50.2\nRequires-Python: >=3.9\n"
)
(di / "WHEEL").write_text(
    "Wheel-Version: 1.0\nGenerator: coda\nRoot-Is-Purelib: false\nTag: cp312-cp312-android_26_arm64_v8a\n"
)

records: list[str] = []
for path in sorted(out.rglob("*")):
    if path.is_file():
        data = path.read_bytes()
        digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
        records.append(f"{path.relative_to(out)},{digest},{len(data)}")
records.append("pydantic_core-2.50.2.dist-info/RECORD,,")
(di / "RECORD").write_text("\n".join(records) + "\n")

target = pathlib.Path(
    "app/wheels/pydantic_core-2.50.2-cp312-cp312-android_26_arm64_v8a.whl"
)
target.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in out.rglob("*"):
        if path.is_file():
            archive.write(path, path.relative_to(out))
print(target)