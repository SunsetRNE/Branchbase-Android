#!/usr/bin/env bash
# warmup-aapt2.sh —— AAPT2 替换后的编译预热（本地专用，独立于环境准备）
#
# 从原 setup_android_env.sh 剥离出的「编译预热」步骤，作为首次编译前的可选动作，
# 不再混入环境准备。目的：确保替换后的 ARM64 AAPT2 被 Gradle 缓存真正采用。
#
# 用法：tools/build/warmup-aapt2.sh

set -e

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
cd "$ROOT_DIR"

GRADLE_CMD="${GRADLE_HOME:-$HOME/gradle/gradle-9.1.0}/bin/gradle"
[[ -x "$GRADLE_CMD" ]] || GRADLE_CMD="./gradlew"

echo "[warmup] 预热 Gradle 缓存以执行 ARM64 AAPT2（processDebugResources）"
"$GRADLE_CMD" --no-daemon --rerun-tasks :app:processDebugResources || {
  echo "[warmup] 预热失败（继续，不影响后续构建）" >&2
}
echo "[warmup] 完成"