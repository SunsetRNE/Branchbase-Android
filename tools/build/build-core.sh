#!/usr/bin/env bash
# build-core.sh —— 编译 Rust 核心为 Android .so（本地/远程共用入口）
#
# 本地 proot（ARM64）：NDK 无 linux-aarch64 clang，走「系统 clang + NDK sysroot」交叉编译
#                      → 委托 core/build-android.sh
# 远程 CI（x86_64）：用 cargo-ndk（锁定 4.1.2）交叉编译
#
# 用法：tools/build/build-core.sh [--mode=local|ci]
# 默认：检测 $CI / $GITHUB_ACTIONS，远程走 ci，否则走 local。

set -e

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)

MODE="${1:---mode=auto}"
case "$MODE" in
  --mode=local) MODE=local ;;
  --mode=ci) MODE=ci ;;
  --mode=auto)
    if [[ "${CI:-false}" == "true" || "${GITHUB_ACTIONS:-false}" == "true" ]]; then
      MODE=ci
    else
      MODE=local
    fi
    ;;
  *) echo "用法: $0 [--mode=local|ci|auto]" >&2; exit 1 ;;
esac

cd "$ROOT_DIR/core"

if [[ "$MODE" == "ci" ]]; then
  echo "[build-core] 远程 CI：cargo-ndk 交叉编译 arm64-v8a"
  cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
else
  echo "[build-core] 本地：系统 clang + NDK sysroot 交叉编译"
  ./build-android.sh
fi

echo "[build-core] 完成：app/src/main/jniLibs/arm64-v8a/libbranchbase_core.so"