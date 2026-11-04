#!/usr/bin/env bash
# 在 CI 内构建 Chaquopy 需要的 Android 轮子：pydantic-core、jiter、rpds-py。
#
# 依赖：python3（含 pip）、cargo（含 aarch64-linux-android 目标）、Android NDK。
# NDK 路径依次取 $NDK、$ANDROID_NDK_ROOT、$ANDROID_HOME/ndk/<默认版本>。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DEFAULT_NDK_VERSION="28.2.13676358"
NDK_PATH="${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:-}/ndk/$DEFAULT_NDK_VERSION}}"
if [ ! -d "$NDK_PATH" ]; then
  echo "找不到 NDK：$NDK_PATH" >&2
  exit 1
fi
TC="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin"
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
export RUSTFLAGS="-L native=/tmp/fakelib"

echo "==> pydantic-core"
rm -rf /tmp/pc
mkdir -p /tmp/pc
python -m pip download pydantic-core==2.50.2 --no-binary=:all: --no-deps -d /tmp/pc -q
tar -xf /tmp/pc/pydantic_core-2.50.2.tar.gz -C /tmp/pc
(cd /tmp/pc/pydantic_core-2.50.2 && cargo build --release --target aarch64-linux-android --features pyo3/extension-module)
python tools/build-pydantic-wheel.py

echo "==> jiter"
rm -rf /tmp/jt
mkdir -p /tmp/jt
python -m pip download jiter==0.18.0 --no-binary=:all: --no-deps -d /tmp/jt -q
tar -xf /tmp/jt/jiter-0.18.0.tar.gz -C /tmp/jt
(cd /tmp/jt/jiter-0.18.0 && cargo build --release --target aarch64-linux-android -p jiter-python --features pyo3/extension-module)
python tools/build-jiter-wheel.py /tmp/jt/jiter-0.18.0

echo "==> rpds-py 垫片"
python tools/build-rpds-wheel.py

ls -l app/wheels