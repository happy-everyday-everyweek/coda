#!/usr/bin/env bash
# 重建内置内核载荷（app/src/main/assets/core/）。
#
# 为什么需要它：assets/core/zcode 是 node 基础镜像 + 内核 bundle 注入而成，
# 内核源码一旦变更，bundle 与 SEA 二进制都必须重编，不能沿用仓库里的旧二进制。
#
# 产物：
#   app/src/main/assets/core/zcode.cjs  内核 bundle（跨平台同一份）
#   app/src/main/assets/core/zcode      SEA 可执行文件（Android aarch64 基础镜像 + 上面这份 bundle）
#
# 不在本脚本职责内（作为固定基础镜像跟踪在仓库里，无需每次重建）：
#   assets/core/node           Android aarch64 的 node v24.18.0（SEA 基础镜像）
#   assets/core/lib/*.so       上述 node 的共享库
#   assets/core/rg             原生搜索工具
#   assets/core/provider/zcode-builtin.json
#
# 用法：
#   tools/build-core-payload.sh                # 完整：装依赖 → 编 bundle → 注入 SEA
#   SKIP_INSTALL=1 tools/build-core-payload.sh # 已 pnpm install 过，跳过安装
#   DRY_RUN=1 tools/build-core-payload.sh      # 只打印将执行的命令
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KERNEL="$ROOT/kernel/zcode"
CLI_PKG="$KERNEL/apps/zcode-cli/packages/cli"
ASSETS="$ROOT/app/src/main/assets/core"

# 目标名用 linux-arm64：与 Android aarch64 同为 arm64 ELF，内核运行时按 process.platform
# 单独识别 android（见 kernel/README.md 的 android SEA 补丁）。
SEA_TARGET="${CODA_SEA_TARGET:-linux-arm64}"
NODE_BASE="${CODA_NODE_BASE:-$ASSETS/node}"
BUNDLE="$CLI_PKG/dist/zcode.cjs"
SEA_OUT="$CLI_PKG/dist/zcode-$SEA_TARGET"

run() {
  echo "+ $*"
  if [ "${DRY_RUN:-0}" = "1" ]; then return 0; fi
  "$@"
}

echo "== 内核载荷重建开始"
echo "   仓库根   : $ROOT"
echo "   内核      : $KERNEL"
echo "   node 镜像 : $NODE_BASE"
echo "   目标      : $SEA_TARGET"

if [ ! -f "$NODE_BASE" ]; then
  echo "!! 缺少 SEA 基础镜像：$NODE_BASE" >&2
  exit 1
fi
if [ ! -f "$ASSETS/provider/zcode-builtin.json" ]; then
  echo "!! 缺少内置 provider 清单：$ASSETS/provider/zcode-builtin.json" >&2
  exit 1
fi

command -v pnpm >/dev/null 2>&1 || { echo "!! 需要 pnpm（建议通过 corepack 启用）" >&2; exit 1; }
command -v node >/dev/null 2>&1 || { echo "!! 需要 node" >&2; exit 1; }

# 1) 依赖
if [ "${SKIP_INSTALL:-0}" = "1" ]; then
  echo "== 跳过依赖安装（SKIP_INSTALL=1）"
else
  echo "== [1/3] 安装内核依赖"
  run pnpm --dir "$KERNEL" install --frozen-lockfile
fi

# 2) 编 bundle：先构建 workspace 依赖包，再用 esbuild 打 CLI 单文件
echo "== [2/3] 构建内核 bundle"
run pnpm --dir "$KERNEL/apps/zcode-cli" exec turbo --skip-infer \
  --cwd apps/zcode-cli run build --filter='!@zcode/cli' --force
run pnpm --dir "$CLI_PKG" exec node scripts/build.mjs

if [ "${DRY_RUN:-0}" != "1" ] && [ ! -f "$BUNDLE" ]; then
  echo "!! bundle 未生成：$BUNDLE" >&2
  exit 1
fi

# 3) SEA：注入 bundle + 内嵌资源，输出 Android 可用可执行文件
echo "== [3/3] 生成 SEA 二进制"
run node "$CLI_PKG/scripts/build-sea.mjs" \
  --target "$SEA_TARGET" \
  --node-binary "$SEA_TARGET=$NODE_BASE"

if [ "${DRY_RUN:-0}" != "1" ] && [ ! -f "$SEA_OUT" ]; then
  echo "!! SEA 二进制未生成：$SEA_OUT" >&2
  exit 1
fi

echo "== 回填到 assets/core"
run cp "$BUNDLE" "$ASSETS/zcode.cjs"
run cp "$SEA_OUT" "$ASSETS/zcode"
run chmod 0755 "$ASSETS/zcode" "$ASSETS/zcode.cjs"

if [ "${DRY_RUN:-0}" != "1" ]; then
  echo "== 校验"
  ls -l "$ASSETS/zcode" "$ASSETS/zcode.cjs"
  grep -aq 'NODE_SEA_FUSE_' "$ASSETS/zcode" || { echo "!! SEA fuse 缺失，二进制不可用" >&2; exit 1; }
  grep -aq 'zcode-node-license' "$ASSETS/zcode" || echo "  提示：未检测到内嵌资源键，可能是资源收集未启用"
  echo "== 完成"
fi
