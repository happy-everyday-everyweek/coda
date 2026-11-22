#!/usr/bin/env bash
# 将 MaiBot Lite 中 Android Personal Agent 真正需要的运行核心复制到构建目录。
# 不复制 A_memorix、WebUI、CLI、电脑控制、插件市场或完整 Agent 包。
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${MAIBOT_CORE_SOURCE:-$ROOT/kernel/maibot-lite}"
# 先校验源树再动 staging：kernel/maibot-lite 不在仓库里（构建时获取），
# 缺失时必须原地报错，不能先 rm -rf 掉上一次的产物再抛 cp 错误。
if [ ! -d "$SRC/src" ]; then
  echo "MaiBot core source not found: $SRC/src (check out kernel/maibot-lite or set MAIBOT_CORE_SOURCE)" >&2
  exit 1
fi
MODULES="chat common config core emoji_system learners llm_models manager maisaka \
mcp_module person_info platform_io plugin_runtime prompt services web_search"
# allow-list 逐项校验：上游改名/拆分模块时直接指出缺了哪个，而不是裸 cp 错误。
for name in $MODULES; do
  if [ ! -e "$SRC/src/$name" ]; then
    echo "Expected MaiBot core module missing: $SRC/src/$name" >&2
    exit 1
  fi
done
OUT="$ROOT/app/build/maibot-core"
rm -rf "$OUT"
mkdir -p "$OUT/src"
for name in $MODULES; do
  cp -a "$SRC/src/$name" "$OUT/src/"
done

cp -a "$SRC/src/__init__.py" "$OUT/src/"
cp -a "$SRC/src/lite_runtime.py" "$OUT/src/"
cp -a "$SRC/src/lite_android_bridge.py" "$OUT/src/"
cp -a "$SRC/src/lite_coding_tool.py" "$OUT/src/"

# 配置模型只引用 agent 的配置类，不需要外接 Agent 实现与工具运行时。
mkdir -p "$OUT/src/agent"
cp -a "$SRC/src/agent/__init__.py" "$OUT/src/agent/"
cp -a "$SRC/src/agent/config.py" "$OUT/src/agent/"

cp -a "$SRC/prompts" "$OUT/prompts"
cp -a "$SRC/locales" "$OUT/locales"
# rpds 的纯 Python 实现由 app/wheels 里的 rpds-py 垫片轮子提供，这里不再复制，避免同名文件重复。
# msgpack 兼容垫片：只为未启用的旧库迁移保留导入，不再安装原生包。
# 整目录复制（与 jieba 一致）：只拷 __init__.py 会把垫片后续新增的模块静默丢掉。
mkdir -p "$OUT/msgpack"
cp -a "$SRC/msgpack/." "$OUT/msgpack/"
# jieba 只有 sdist，内置纯 Python 源码给 Chaquopy 直接打包。
mkdir -p "$OUT/jieba"
cp -a "$SRC/jieba/." "$OUT/jieba/"
# pyproject.toml 由 app/src/main/python 提供；重复复制会让 Chaquopy 报 DuplicateFileCopying。

find "$OUT" -type d -name __pycache__ -prune -exec rm -rf {} +
find "$OUT" -type f \( -name '*.pyc' -o -name '*.pyo' \) -delete

echo "MaiBot core staged at $OUT"
du -sh "$OUT"
