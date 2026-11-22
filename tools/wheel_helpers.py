"""轮子组装脚本共享的小工具：RECORD 行格式、构建产物定位与确定性打包。

RECORD 的哈希字段按 PEP 376/PEP 427 写成 sha256=<urlsafe-b64>，
裸摘要会被 pip uninstall / wheel verify 判为格式错误。
"""

from __future__ import annotations

import base64
import hashlib
import pathlib
import zipfile


def record_line(path: str, data: bytes) -> str:
    digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
    return f"{path},sha256={digest},{len(data)}"


def single_so(directory: pathlib.Path, pattern: str) -> pathlib.Path:
    """在目录里定位唯一的 .so 产物；缺失或多个候选都给出可定位的报错。"""
    candidates = sorted(directory.glob(pattern))
    if not candidates:
        raise SystemExit(
            f"找不到编出的扩展模块：{directory / pattern}（请先完成 cargo 交叉编译）"
        )
    if len(candidates) > 1:
        names = ", ".join(str(p) for p in candidates)
        raise SystemExit(
            f"{directory / pattern} 匹配到 {len(candidates)} 个候选，无法确定用哪个：{names}"
        )
    return candidates[0]


def _sorted_files(out: pathlib.Path) -> list[pathlib.Path]:
    # 按相对路径字符串排序（Path 对象比较是按路径分段，与字符串序不同），
    # RECORD 与 zip 条目共用同一顺序，跨机器可复现。
    return sorted(
        (p for p in out.rglob("*") if p.is_file()),
        key=lambda p: str(p.relative_to(out)),
    )


def write_record_and_zip(out: pathlib.Path, dist_info: str, target: pathlib.Path) -> None:
    """按确定顺序写 RECORD 并打包：条目排序、时间戳固定、RECORD 自身哈希留空。"""
    di = out / dist_info
    records: list[str] = []
    for path in _sorted_files(out):
        records.append(record_line(str(path.relative_to(out)), path.read_bytes()))
    records.append(f"{dist_info}/RECORD,,")
    (di / "RECORD").write_text("\n".join(records) + "\n")

    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        # RECORD 写完后重新枚举：它自己也必须进包。
        for path in _sorted_files(out):
            info = zipfile.ZipInfo(str(path.relative_to(out)), date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes())