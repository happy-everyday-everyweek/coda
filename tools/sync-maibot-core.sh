#!/usr/bin/env bash
# 将 MaiBot Lite 中 Android Personal Agent 真正需要的运行核心复制到构建目录。
# 不复制 A_memorix、WebUI、CLI、电脑控制、插件市场或完整 Agent 包。
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${MAIBOT_CORE_SOURCE:-$ROOT/kernel/maibot-lite}"
OUT="$ROOT/app/build/maibot-core"

rm -rf "$OUT"
mkdir -p "$OUT/src"

for name in \
  chat common config core emoji_system learners llm_models manager maisaka \
  mcp_module person_info platform_io plugin_runtime prompt services web_search
do
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
# rpds 的纯 Python 实现，替代 rpds-py 原生包；referencing 以顶层模块方式导入。
mkdir -p "$OUT/rpds"
cp -a "$SRC/rpds/__init__.py" "$OUT/rpds/__init__.py"
# 仅用于读取内核版本号，不包含示例配置或模型密钥。
cp -a "$SRC/pyproject.toml" "$OUT/pyproject.toml"

find "$OUT" -type d -name __pycache__ -prune -exec rm -rf {} +
find "$OUT" -type f \( -name '*.pyc' -o -name '*.pyo' \) -delete

echo "MaiBot core staged at $OUT"
du -sh "$OUT"