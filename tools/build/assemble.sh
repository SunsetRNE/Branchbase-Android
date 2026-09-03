#!/usr/bin/env bash
# assemble.sh —— 执行编译（gradlew assemble），并「注入关键版本参数」
#
# 职责：读取 version.properties → 计算 standardVersion/gitHash/buildTime →
#       ① 注入环境变量（app/build.gradle.kts 优先读取，保证版本号一致）
#       ② 写入 $GITHUB_OUTPUT（远程 CI 供后续 publish 阶段引用）
#       ③ 打印到 stdout（本地可读）
# 然后执行 ./gradlew <task>。
#
# 用法：tools/build/assemble.sh assembleDebug|assembleRelease

set -e

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
cd "$ROOT_DIR"

TASK="${1:-assembleDebug}"

# ── 读取工程版本 ──
VERSION_NAME=$(grep '^versionName=' version.properties | cut -d= -f2 | tr -d '[:space:]')
VERSION_CODE=$(grep '^versionCode=' version.properties | cut -d= -f2 | tr -d '[:space:]')

# ── 计算关键版本参数（固定 Asia/Shanghai，与 app/build.gradle.kts 对齐）──
GIT_HASH=$(git rev-parse --short=7 HEAD 2>/dev/null || echo "unknown")
BUILD_TIME=$(TZ=Asia/Shanghai date +%Y%m%d-%H%M)
STANDARD_VERSION="${VERSION_NAME}-${BUILD_TIME}-${GIT_HASH}"

# ── ① 注入环境变量（build.gradle.kts 优先读取）──
export BRANCHBASE_VERSION_NAME="$VERSION_NAME"
export BRANCHBASE_VERSION_CODE="$VERSION_CODE"
export BRANCHBASE_GIT_HASH="$GIT_HASH"
export BRANCHBASE_BUILD_TIME="$BUILD_TIME"
export BRANCHBASE_STANDARD_VERSION="$STANDARD_VERSION"

# ── ② 写入 GITHUB_OUTPUT（CI）──
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "versionName=$VERSION_NAME"
    echo "versionCode=$VERSION_CODE"
    echo "gitHash=$GIT_HASH"
    echo "buildTime=$BUILD_TIME"
    echo "standardVersion=$STANDARD_VERSION"
  } >> "$GITHUB_OUTPUT"
fi

# ── ③ 打印（本地可读 / 日志）──
echo "[assemble] versionName=$VERSION_NAME versionCode=$VERSION_CODE"
echo "[assemble] standardVersion=$STANDARD_VERSION"

# ── 执行编译 ──
./gradlew "$TASK"

echo "[assemble] 完成 task=$TASK"
echo "BRANCHBASE_STANDARD_VERSION=$STANDARD_VERSION"