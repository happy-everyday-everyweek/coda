"""把 cargo 编出的 jiter .so 组装成 Chaquopy 可安装的 Android wheel。

jiter 是 openai 的硬依赖，Chaquopy 源里没有它的分发，只能自己交叉编译。
maturin 的 module-name 是 jiter（顶层扩展模块），因此 wheel 里直接放
jiter.cpython-312-aarch64-linux-android.so，而不是包目录。
"""

from __future__ import annotations

import base64
import hashlib
import pathlib
import re
import shutil
import sys
import zipfile

src = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/jt/jiter-0.18.0")
out = pathlib.Path("/tmp/jiter-whl")
shutil.rmtree(out, ignore_errors=True)

match = re.search(r"jiter-(\d+\.\d+\.\d+)", src.name)
if not match:
    raise SystemExit(f"无法从目录名解析版本：{src.name}")
version = match.group(1)

so = next(src.glob("target/aarch64-linux-android/release/libjiter_python.so"))
out.mkdir(parents=True)
shutil.copy(so, out / "jiter.cpython-312-aarch64-linux-android.so")

di = out / f"jiter-{version}.dist-info"
di.mkdir()
(di / "METADATA").write_text(
    f"Metadata-Version: 2.1\nName: jiter\nVersion: {version}\nRequires-Python: >=3.8\n"
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
records.append(f"jiter-{version}.dist-info/RECORD,,")
(di / "RECORD").write_text("\n".join(records) + "\n")

target = pathlib.Path(f"app/wheels/jiter-{version}-cp312-cp312-android_26_arm64_v8a.whl")
target.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in out.rglob("*"):
        if path.is_file():
            archive.write(path, path.relative_to(out))
print(target)