#!/usr/bin/env bash
# 在 CI 内构建 Chaquopy 需要的 Android 轮子：pydantic-core、jiter、rpds-py。
#
# 依赖：python3（含 pip）、cargo（含 aarch64-linux-android 目标）、Android NDK。
# NDK 路径依次取 $NDK、$ANDROID_NDK_ROOT、$ANDROID_HOME/ndk/<默认版本>。
# Python 解释器可用 $PYTHON 覆盖，默认 python3。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# 本脚本始终为 Chaquopy 的 CPython 3.12 交叉编译 pyo3 扩展，
# 由脚本统一设置，避免不同工作流遗漏环境变量。
export PYO3_CROSS=1
export PYO3_CROSS_PYTHON_VERSION=3.12

# 版本只在这一处：pip download 与解包目录名都从它派生，避免两处版本号漂移。
PYDANTIC_VERSION="${PYDANTIC_VERSION:-2.50.2}"
JITER_VERSION="${JITER_VERSION:-0.18.0}"
PYTHON="${PYTHON:-python3}"

DEFAULT_NDK_VERSION="28.2.13676358"
# 三个变量都没给时直接报错：拼出来的 /ndk/<版本> 是根目录下的路径，
# 只会在后面「找不到 NDK」里绕一圈，看不出是环境变量没设。
if [ -z "${NDK:-}" ] && [ -z "${ANDROID_NDK_ROOT:-}" ] && [ -z "${ANDROID_HOME:-}" ]; then
  echo "未设置 NDK / ANDROID_NDK_ROOT / ANDROID_HOME，无法定位 NDK" >&2
  exit 1
fi
NDK_PATH="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:-}/ndk/$DEFAULT_NDK_VERSION}}"
if [ ! -d "$NDK_PATH" ]; then
  echo "找不到 NDK：$NDK_PATH" >&2
  exit 1
fi

# NDK 的预编译包只发布 linux-x86_64 与 darwin-x86_64 两种 host tag。
# 不再把 linux-x86_64 写死在路径里：在 macOS 上跑脚本时才不会找错目录。
case "$(uname -s)" in
  Darwin) DEFAULT_HOST_TAG="darwin-x86_64" ;;
  *) DEFAULT_HOST_TAG="linux-x86_64" ;;
esac
HOST_TAG="${NDK_HOST_TAG:-$DEFAULT_HOST_TAG}"

TC="$NDK_PATH/toolchains/llvm/prebuilt/$HOST_TAG/bin"
if [ ! -x "$TC/aarch64-linux-android26-clang" ]; then
  echo "NDK 工具链缺少 aarch64-linux-android26-clang：$TC" >&2
  exit 1
fi

export CC_aarch64_linux_android="$TC/aarch64-linux-android26-clang"
export AR_aarch64_linux_android="$TC/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TC/aarch64-linux-android26-clang"

# pyo3 的交叉编译需要一个 libpython 占位；实际符号在设备上由 Chaquopy 运行时提供。
mkdir -p /tmp/fakelib
"$TC/aarch64-linux-android26-clang" -shared -fPIC -o /tmp/fakelib/libpython3.12.so -x c /dev/null
# RUSTFLAGS 只追加：CI 里可能已经设了 -C target-feature 之类的参数，
# 直接赋值会整条覆盖掉。
export RUSTFLAGS="${RUSTFLAGS:-} -L native=/tmp/fakelib"

echo "==> pydantic-core $PYDANTIC_VERSION"
rm -rf /tmp/pc
mkdir -p /tmp/pc
# sdist 会被编译成 .so 打进 APK：必须按 sha256 固定，否则被污染的镜像就能注入原生代码。
# 校验值来自 tools/android-wheels.requirements.txt，下载后逐个比对，不符即中止。
"$PYTHON" tools/fetch-sdist.py pydantic-core "$PYDANTIC_VERSION" /tmp/pc
tar -xf "/tmp/pc/pydantic_core-$PYDANTIC_VERSION.tar.gz" -C /tmp/pc
(cd "/tmp/pc/pydantic_core-$PYDANTIC_VERSION" && cargo build --release --target aarch64-linux-android --features pyo3/extension-module)
"$PYTHON" tools/build-pydantic-wheel.py

echo "==> jiter $JITER_VERSION"
rm -rf /tmp/jt
mkdir -p /tmp/jt
"$PYTHON" tools/fetch-sdist.py jiter "$JITER_VERSION" /tmp/jt
tar -xf "/tmp/jt/jiter-$JITER_VERSION.tar.gz" -C /tmp/jt
(cd "/tmp/jt/jiter-$JITER_VERSION" && cargo build --release --target aarch64-linux-android -p jiter-python --features pyo3/extension-module)
"$PYTHON" tools/build-jiter-wheel.py "/tmp/jt/jiter-$JITER_VERSION"

echo "==> rpds-py 垫片"
"$PYTHON" tools/build-rpds-wheel.py

ls -l app/wheels