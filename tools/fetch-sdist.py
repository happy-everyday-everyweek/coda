#!/usr/bin/env python3
"""下载 Android 轮子构建用的 sdist，并按固定 sha256 校验。

这些源码包会被交叉编译成 .so 打进 APK，等于把原生代码的信任交给下载来源。
所以哈希不是可选项：清单里没有这个包、或下载内容摘要不符，都直接失败退出。

用法：fetch-sdist.py <包名> <版本> <目标目录>
"""

import hashlib
import pathlib
import sys
import re
import urllib.request

REQUIREMENTS = pathlib.Path(__file__).with_name("android-wheels.requirements.txt")


def normalize_name(name: str) -> str:
    """PEP 503 归一化：同名包的不同写法要落到同一个键上。"""
    return re.sub(r"[-_.]+", "-", name.lower())


def load_pins(path=REQUIREMENTS):
    """解析带 --hash 的 requirements，返回 {(name, version): set(sha256)}。"""
    pins = {}
    text = path.read_text(encoding="utf-8")
    # 续行合并成一条，再按包名==版本 与 --hash 拆开。
    for raw in text.replace("\\\n", " ").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line or "==" not in line:
            continue
        parts = line.split()
        spec = parts[0]
        name, _, version = spec.partition("==")
        hashes = {
            token.split(":", 1)[1].strip().lower()
            for token in parts[1:]
            if token.startswith("--hash=sha256:")
        }
        if not version or not hashes:
            continue
        pins[(normalize_name(name.strip()), version.strip())] = hashes
    return pins


def verify(data, expected):
    """摘要是否在期望集合内。空集合一律拒绝，不做「没配就放行」。"""
    if not expected:
        return False
    return hashlib.sha256(data).hexdigest() in expected


def sdist_url(name, version):
    """PyPI 官方 sdist 地址；文件名里的连字符按 PEP 503 归一为下划线。"""
    stem = name.replace("-", "_")
    return (
        "https://files.pythonhosted.org/packages/source/"
        f"{name[0]}/{name}/{stem}-{version}.tar.gz"
    )


def main(argv):
    if len(argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2
    name, version, out_dir = argv[1], argv[2], pathlib.Path(argv[3])
    pins = load_pins()
    # 两侧都按 PEP 503 归一化（OCR low #827）：pydantic-core 与 pydantic_core 是同一个
    # 包，只做 lowercase 会让写成下划线的清单/调用方查不到已固定的哈希。
    expected = pins.get((normalize_name(name), version))
    if not expected:
        print(
            f"{name}=={version} 没有固定 sha256，拒绝下载；"
            f"请先在 {REQUIREMENTS.name} 里补上官方哈希",
            file=sys.stderr,
        )
        return 1
    url = sdist_url(name, version)
    try:
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
    except urllib.error.HTTPError as exc:
        # 网络/HTTP 失败要给出可操作的说明（OCR low #827）：旧路径 /packages/source/
        # 对部分上传会 404，原来会直接抛 urllib 的 traceback。
        print(f"下载 {url} 失败：HTTP {exc.code} {exc.reason}", file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"下载 {url} 失败：{exc.reason}", file=sys.stderr)
        return 1
    if not verify(data, expected):
        print(
            f"{name}=={version} 摘要不符，已拒绝使用："
            f"期望 {sorted(expected)}，实际 {hashlib.sha256(data).hexdigest()}",
            file=sys.stderr,
        )
        return 1
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / f"{name.replace('-', '_')}-{version}.tar.gz"
    target.write_bytes(data)
    print(f"已校验并保存 {target}（{len(data)}B）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))