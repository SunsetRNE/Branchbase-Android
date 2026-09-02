#!/usr/bin/env bash
# 编译 Rust 核心为 Android 动态库 (.so) —— aarch64 (ARM64) 方案
#
# 背景：Google 官方 NDK 不提供 linux-aarch64 版（只有 x86_64），
# 在 ARM64 环境下无法直接运行 NDK 自带的 clang。
# 方案：用系统 aarch64 clang + NDK 的 sysroot + clang builtin 库交叉编译。
#
# 依赖：系统 clang（apt install clang llvm）、Rust aarch64-linux-android target
#   rustup target add aarch64-linux-android
#
# 产物：app/src/main/jniLibs/arm64-v8a/libbranchbase_core.so

set -e
cd "$(dirname "$0")"

# NDK 路径（可被 ANDROID_NDK_HOME 覆盖）
NDK="${ANDROID_NDK_HOME:-/root/Android/ndk/26.1.10909125}"
# 固化环境变量：openssl-src / libgit2-sys 交叉编译依赖 ANDROID_NDK_HOME
export ANDROID_NDK_HOME="$NDK"
NDK_TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$NDK_TOOLCHAIN/sysroot"

# 探测 NDK 内 clang 版本（17 或其他）
CLANG_VER=$(ls "$NDK_TOOLCHAIN/lib/clang/" | head -1)
CLANG_LIB="$NDK_TOOLCHAIN/lib/clang/$CLANG_VER/lib/linux/aarch64"

echo "==> NDK: $NDK (clang $CLANG_VER)"

# 创建临时 clang wrapper
WRAPPER="$(mktemp /tmp/clang-android.XXXXXX)"
cat > "$WRAPPER" <<EOF
#!/bin/bash
exec /usr/bin/clang \\
  --target=aarch64-linux-android26 \\
  --sysroot="$SYSROOT" \\
  -L"$CLANG_LIB" \\
  "\$@"
EOF
chmod +x "$WRAPPER"

export CC_aarch64_linux_android="$WRAPPER"
export AR_aarch64_linux_android=/usr/bin/llvm-ar
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$WRAPPER"

echo "==> 编译中..."
cargo build --target aarch64-linux-android --release

OUT_DIR="../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT_DIR"
cp "target/aarch64-linux-android/release/libbranchbase_core.so" "$OUT_DIR/"
rm -f "$WRAPPER"

echo "==> 完成: $OUT_DIR/libbranchbase_core.so"