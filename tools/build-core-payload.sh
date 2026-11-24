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
# 重建前先算一遍输入指纹：内核源码、SEA 基础镜像与脚本自身。指纹与上次构建记下的一致，
# 且载荷产物都在时直接跳过，只重写载荷清单，没有内核改动的提交不必重复重建。
#
# 用法：
#   tools/build-core-payload.sh                 # 指纹有变才完整重建
#   tools/build-core-payload.sh --fingerprint   # 只打印输入指纹，供 CI 当缓存键
#   FORCE_REBUILD=1 tools/build-core-payload.sh # 指纹一致也强制重建
#   SKIP_INSTALL=1 tools/build-core-payload.sh  # 已 pnpm install 过，跳过安装
#   DRY_RUN=1 tools/build-core-payload.sh       # 只打印将执行的命令
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

file_digest() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

stream_digest() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | cut -d' ' -f1
  else
    shasum -a 256 | cut -d' ' -f1
  fi
}

# 输入指纹覆盖所有会被打进载荷的东西：仓库跟踪的内核源文件、SEA 基础镜像、脚本自身，
# 以及随包但不参与内核构建的运行时输入（内置供应商配置、rg、core/lib 下的 .so）。
# dist 与 node_modules 是构建产物，不参与计算，因此同一个提交在本地与 CI 上值一致。
# third-party/runtime/sources.json 与生成的 node-*-LICENSE.txt 排除在外：它们会被本脚本
# 的许可证登记步骤改写，算进指纹会让 stamp 永远对不上、每次都全量重建，还会弄脏工作树。
kernel_inputs_fingerprint() {
  {
    printf 'script %s\n' "$(file_digest "$ROOT/tools/build-core-payload.sh")"
    printf 'node-base %s\n' "$(file_digest "$NODE_BASE")"
    # 这些文件换了内容就是换了载荷的运行时输入，必须让跳过判定失效。
    # 路径相对 $ASSETS（app/src/main/assets/core/）：仓库里没有 $ROOT/assets/，
    # 之前写成那样两条 extras 永远 hash 成 missing，指纹形同虚设（#799）。
    for extra in provider/zcode-builtin.json rg; do
      if [ -f "$ASSETS/$extra" ]; then
        printf '%s %s\n' "$extra" "$(file_digest "$ASSETS/$extra")"
      else
        printf '%s missing\n' "$extra"
      fi
    done
    if [ -d "$ASSETS/lib" ]; then
      # '*.so*'：随包库多为版本化 soname（libcrypto.so.3、libicudata.so.78），
      # 只匹配 '*.so' 会漏掉一大半，换了库指纹也不变（#800）。
      find "$ASSETS/lib" -type f -name '*.so*' -print0 \
        | LC_ALL=C sort -z \
        | while IFS= read -r -d '' path; do
            printf '%s %s\n' "${path#"$ROOT"/}" "$(file_digest "$path")"
          done
    fi
    # -o --exclude-standard：把本地新增但还没 git add 的源文件也算进指纹。
    # 只取 git ls-files 的话，改完没 add 时 stamp 判定为「无变化」，CI 与本地
    # 会各自得到不同的指纹含义（CI 干净检出不受影响，本地容易踩）。
    git -C "$ROOT" ls-files -z -c -o --exclude-standard -- kernel/zcode \
      | LC_ALL=C sort -z \
      | while IFS= read -r -d '' path; do
          case "$path" in
            */dist/*|*/node_modules/*|*.tsbuildinfo) continue ;;
            */third-party/runtime/sources.json) continue ;;
            */third-party/runtime/node-*-LICENSE.txt) continue ;;
          esac
          printf '%s %s\n' "$path" "$(file_digest "$ROOT/$path")"
        done
  } | stream_digest
}

# 输出载荷清单的一行：文件名 字节数 sha256。应用按这份清单核对解包结果。
payload_line() {
  local file="$ASSETS/$1" size sha
  if [ ! -f "$file" ]; then
    echo "!! 载荷文件缺失：$file" >&2
    exit 1
  fi
  size="$(wc -c < "$file" | tr -d ' ')"
  sha="$(file_digest "$file")"
  printf '%s %s %s\n' "$1" "$size" "$sha"
}

# 载荷清单：应用解包后据此核对 files/core 里的文件就是随包这一份，字节数或摘要对不上就重新
# 解包。少了它，应用只能凭一个固定标记判断，换了载荷也可能继续沿用上一版解出来的二进制。
write_payload_manifest() {
  # 必须原子写：重定向会先截断 payload.txt，而 payload_line 在文件缺失时 exit 1。
  # 中途失败会留下一份被截断的清单，应用侧 mapNotNull 会静默丢掉坏行，
  # 于是被省略的文件跳过摘要校验——正是清单要防的那种情况。
  local tmp="$ASSETS/payload.txt.tmp.$$"
  # 子 shell 而不是 brace group：payload_line 失败走 exit 1，brace group
  # 在当前 shell 执行，exit 会直接终止整个脚本，下面的 || 清理分支永不执行，
  # 带 PID 的 tmp 残留还可能被下一次构建打进 APK（#798）。
  (
    payload_line zcode
    payload_line zcode.cjs
    payload_line node
    payload_line rg
  ) > "$tmp" || {
    rm -f "$tmp"
    echo "!! 载荷清单生成失败，保留上一份 payload.txt" >&2
    exit 1
  }
  mv -f "$tmp" "$ASSETS/payload.txt"
  echo "   已写出载荷清单 payload.txt"
}


# CI 用这个模式取缓存键，输出必须只有一行指纹，所以放在任何提示之前。
if [ "${1:-}" = "--fingerprint" ]; then
  if [ ! -f "$NODE_BASE" ]; then
    echo "!! 缺少 SEA 基础镜像：$NODE_BASE" >&2
    exit 1
  fi
  kernel_inputs_fingerprint
  exit 0
fi

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

# 审查这一步：内核源码与基础镜像都没变、载荷产物也都在时，跳过整套重建。CI 按同一个指纹
# 缓存载荷，跳过时取回的就是上一次用同样输入构建出来的产物，不存在陈旧二进制的问题。
INPUTS_FINGERPRINT="$(kernel_inputs_fingerprint)"
STAMP="$ASSETS/kernel.stamp"
if [ "${DRY_RUN:-0}" != "1" ] && [ "${FORCE_REBUILD:-0}" != "1" ] \
  && [ -f "$STAMP" ] && [ -f "$ASSETS/zcode" ] && [ -f "$ASSETS/zcode.cjs" ] \
  && [ "$(head -n 1 "$STAMP")" = "$INPUTS_FINGERPRINT" ]; then
  echo "== 内核源码与基础镜像均未变更，跳过重建"
  echo "   输入指纹 : $INPUTS_FINGERPRINT"
  write_payload_manifest
  exit 0
fi

command -v pnpm >/dev/null 2>&1 || { echo "!! 需要 pnpm（建议通过 corepack 启用）" >&2; exit 1; }
command -v node >/dev/null 2>&1 || { echo "!! 需要 node" >&2; exit 1; }

# 桌面端二进制下载与本载荷无关，跳过可避免在受限网络下安装失败。
export ELECTRON_SKIP_BINARY_DOWNLOAD="${ELECTRON_SKIP_BINARY_DOWNLOAD:-1}"
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD="${PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD:-1}"

# 1) 依赖
if [ "${SKIP_INSTALL:-0}" = "1" ]; then
  echo "== 跳过依赖安装（SKIP_INSTALL=1）"
else
  echo "== [1/3] 安装内核依赖"
  run pnpm --dir "$KERNEL" install --frozen-lockfile
fi

# 2) 编 bundle：先构建 workspace 依赖包，再用 esbuild 打 CLI 单文件
echo "== [2/3] 构建内核 bundle"
# 先删掉预期产物：只判断「文件存在」的话，上一轮留下的旧文件就能满足检查。
# 构建器若退出 0 却没写出东西（比如输出路径改了名），会把过期产物当新的拷进 assets。
if [ "${DRY_RUN:-0}" != "1" ]; then
  rm -f "$BUNDLE" "$SEA_OUT"
fi
# turbo 是内核仓库根的依赖，必须从根目录调用（与上游 apps/zcode-cli 的 build:sea 脚本一致）
run pnpm --dir "$KERNEL" exec turbo --skip-infer \
  --cwd apps/zcode-cli run build --filter='!@zcode/cli' --force
run pnpm --dir "$CLI_PKG" run build

# @zcode/shared 是纯 TS 源码包（exports 直指 src/*.ts，没有 build 脚本），
# 但 SEA 的 TUI 运行时闭包要求它有 dist/index.js，所以单独用 tsc 编一遍。
run pnpm --dir "$KERNEL" exec tsc -p packages/shared/tsconfig.json

if [ "${DRY_RUN:-0}" != "1" ] && [ ! -f "$BUNDLE" ]; then
  echo "!! bundle 未生成：$BUNDLE" >&2
  exit 1
fi

# 3) SEA：注入 bundle + 内嵌资源，输出 Android 可用可执行文件
# SEA 的许可证声明要求 third-party/runtime/sources.json 登记当前 node 版本。
# 版本必须取自基础镜像而不是宿主 node：SEA 二进制里嵌的是 $NODE_BASE，
# 宿主可能领先一两个补丁，用宿主版本会登记一个根本不随包发布的运行时。
# 但基础镜像是 Android aarch64 二进制，在 x86 构建机上不能执行（Exec format error），
# 所以从二进制里提取它自报的版本字符串，而不是运行它。
node_base_version() {
  # node 二进制里内嵌唯一的 "node.js/vX.Y.Z" 标识（已用随包的 aarch64 node 实测）。
  # 这个锚点比裸版本号可靠：二进制里还有 v8、openssl 等其他组件的版本字符串。
  local ver
  ver="$(LC_ALL=C grep -aoE 'node\.js/v[0-9]+\.[0-9]+\.[0-9]+' "$NODE_BASE" \
    | sed 's#^node\.js/v##' | sort -u | head -1)"
  if [ -z "$ver" ]; then
    # 兜底：取出现次数最多的裸版本号。仍然只读不执行。
    ver="$(LC_ALL=C grep -aoE 'v[0-9]+\.[0-9]+\.[0-9]+' "$NODE_BASE" \
      | sed 's/^v//' | sort | uniq -c | sort -rn | head -1 | awk '{print $2}')"
  fi
  printf '%s' "$ver"
}
if [ "${DRY_RUN:-0}" = "1" ]; then
  NODE_VER="dry-run"
else
  NODE_VER="$(node_base_version)"
  if [ -z "$NODE_VER" ]; then
    echo "!! 无法从基础镜像读取 node 版本：$NODE_BASE" >&2
    exit 1
  fi
fi
echo "   基础镜像 node : v$NODE_VER"
if [ "${DRY_RUN:-0}" != "1" ]; then
  node -e '
const fs=require("fs"),crypto=require("crypto"),path=require("path");
const [sourcesPath,version,root]=process.argv.slice(1);
const json=JSON.parse(fs.readFileSync(sourcesPath,"utf8"));
if(json.node.some(n=>n.version===version)){console.log("   node "+version+" license provenance 已登记");process.exit(0);}
const sameMajor=json.node.filter(n=>n.version.split(".")[0]===version.split(".")[0]);
const base=sameMajor.length?sameMajor[sameMajor.length-1]:json.node[json.node.length-1];
const bytes=fs.readFileSync(path.join(root,base.file));
const rel=path.posix.join("third-party/runtime","node-"+version+"-LICENSE.txt");
fs.writeFileSync(path.join(root,rel),bytes);
json.node.push({version,source:"https://raw.githubusercontent.com/nodejs/node/v"+version+"/LICENSE",file:rel,sha256:crypto.createHash("sha256").update(bytes).digest("hex")});
fs.writeFileSync(sourcesPath,JSON.stringify(json,null,2)+"\n");
console.log("   已为基础镜像 node "+version+" 登记许可证来源（复用 "+base.version+" 的许可证文本）");
' "$KERNEL/third-party/runtime/sources.json" "$NODE_VER" "$KERNEL"
fi
echo "== [3/3] 生成 SEA 二进制"
# SEA 的资源闭包会用到仓库根 packages/* 下的 workspace 包，而它们不在
# apps/zcode-cli 这个 turbo 范围里，可能缺 dist。缺哪个就补编哪个，最多补 4 轮。
# dry-run 与真实构建走同一个函数：否则两条路径手工维护，DRY_RUN=1 看不到
# 可能的 tsc 补编与输出重定向，预览就不忠实。
build_sea_with_retry() {
  if [ "${DRY_RUN:-0}" = "1" ]; then
    echo "+ node $CLI_PKG/scripts/build-sea.mjs --target $SEA_TARGET --node-binary $SEA_TARGET=$NODE_BASE（失败时按缺 dist 的包补编 tsc，最多 4 轮）"
    return 0
  fi
  SEA_LOG="$(mktemp)"
  # 中间任何一步失败（set -e）都不能把临时文件留在 /tmp：只用显式 rm 覆盖不到
  # pnpm/tsc 这类中途退出。
  trap 'rm -f "$SEA_LOG"' EXIT
  attempt=1
  while :; do
    if node "$CLI_PKG/scripts/build-sea.mjs" \
      --target "$SEA_TARGET" \
      --node-binary "$SEA_TARGET=$NODE_BASE" 2>&1 | tee "$SEA_LOG"; then
      break
    fi
    missing="$(grep -oE 'Missing @zcode/[a-z0-9-]+ dist files' "$SEA_LOG" | tail -1 | sed -E 's/^Missing //; s/ dist files$//' || true)"
    if [ -z "$missing" ] || [ "$attempt" -ge 4 ]; then
      echo "!! SEA 生成失败" >&2
      tail -20 "$SEA_LOG" >&2
      rm -f "$SEA_LOG"
      exit 1
    fi
    attempt=$((attempt + 1))
    echo "== 补齐根 workspace 包：$missing"
    # 报错里的包名不一定对应 packages/<去掉 scope 的名字>/tsconfig.json：
    # 嵌套包、改过名的包、或根本没有 tsconfig 的包都会让 tsc 直接失败，
    # 在 set -e 下脚本会以 tsc 错误中止，而不是给出「SEA 生成失败」的诊断。
    missing_tsconfig="$KERNEL/packages/${missing#@zcode/}/tsconfig.json"
    if [ ! -f "$missing_tsconfig" ]; then
      echo "!! SEA 生成失败：缺少 $missing 的 dist，且找不到 $missing_tsconfig 可用于补编" >&2
      tail -20 "$SEA_LOG" >&2
      rm -f "$SEA_LOG"
      exit 1
    fi
    pnpm --dir "$KERNEL" exec tsc -p "$missing_tsconfig"
  done
  rm -f "$SEA_LOG"
}
build_sea_with_retry
if [ "${DRY_RUN:-0}" != "1" ] && [ ! -f "$SEA_OUT" ]; then
  echo "!! SEA 二进制未生成：$SEA_OUT" >&2
  exit 1
fi

echo "== 回填到 assets/core"
run cp "$BUNDLE" "$ASSETS/zcode.cjs"
run cp "$SEA_OUT" "$ASSETS/zcode"
run chmod 0755 "$ASSETS/zcode" "$ASSETS/zcode.cjs"

if [ "${DRY_RUN:-0}" != "1" ]; then
  write_payload_manifest
  printf '%s\n' "$INPUTS_FINGERPRINT" > "$STAMP"
fi

if [ "${DRY_RUN:-0}" != "1" ]; then
  echo "== 校验"
  ls -l "$ASSETS/zcode" "$ASSETS/zcode.cjs" "$ASSETS/payload.txt"
  grep -aq 'NODE_SEA_FUSE_' "$ASSETS/zcode" || { echo "!! SEA fuse 缺失，二进制不可用" >&2; exit 1; }
  grep -aq 'zcode-node-license' "$ASSETS/zcode" || echo "  提示：未检测到内嵌资源键，可能是资源收集未启用"
  echo "== 完成"
fi
