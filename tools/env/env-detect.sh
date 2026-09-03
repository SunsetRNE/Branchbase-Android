#!/usr/bin/env bash
# env-detect.sh —— 环境判定（纯判定，零副作用，不下载、不写文件）
#
# 检查本地 proot 环境是否就绪，输出 JSON 状态。
# 退出码：0 = ready（全部就绪）；1 = partial（部分缺失）；2 = missing（关键缺失）
#
# 用法：tools/env/env-detect.sh
# 供 env-prepare.sh / CI 判定是否跳过准备阶段。

set -u

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)

command_exists() { command -v "$1" >/dev/null 2>&1; }

# 判定各组件
java_ok=0; sdk_ok=0; gradle_ok=0; rust_ok=0; ndk_ok=0; cargo_ndk_ok=0; aapt2_ok=0

# Java >= 17
if command_exists java; then
  ver=$(java -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p')
  major=${ver%%.*}
  [[ "$major" == "1" ]] && major=$(echo "$ver" | cut -d. -f2)
  if [[ -n "$major" && "$major" -ge 17 ]]; then java_ok=1; fi
fi

# Android SDK（sdkmanager 可执行）
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android}}"
if [[ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then sdk_ok=1; fi

# Gradle
if command_exists gradle || [[ -x "$HOME/gradle/gradle-9.1.0/bin/gradle" ]]; then gradle_ok=1; fi

# Rust 工具链 + aarch64 target
if command_exists cargo; then
  rust_ok=1
  if rustup target list --installed 2>/dev/null | grep -q aarch64-linux-android; then ndk_ok=1; fi
fi

# cargo-ndk
if command_exists cargo-ndk; then cargo_ndk_ok=1; fi

# AAPT2 预存资产（tools/aapt2/）
if [[ -f "$ROOT_DIR/tools/aapt2/aapt2-arm64-v8a" ]]; then aapt2_ok=1; fi

total=$((java_ok + sdk_ok + gradle_ok + rust_ok + ndk_ok + cargo_ndk_ok + aapt2_ok))
ready=$([ "$total" -eq 7 ] && echo true || echo false)

cat <<EOF
{
  "ready": $ready,
  "java": $([ $java_ok -eq 1 ] && echo true || echo false),
  "sdk": $([ $sdk_ok -eq 1 ] && echo true || echo false),
  "gradle": $([ $gradle_ok -eq 1 ] && echo true || echo false),
  "rust": $([ $rust_ok -eq 1 ] && echo true || echo false),
  "ndk_target": $([ $ndk_ok -eq 1 ] && echo true || echo false),
  "cargo_ndk": $([ $cargo_ndk_ok -eq 1 ] && echo true || echo false),
  "aapt2": $([ $aapt2_ok -eq 1 ] && echo true || echo false),
  "score": $total
}
EOF

if [ "$ready" = true ]; then exit 0; fi
if [ "$total" -ge 4 ]; then exit 1; fi
exit 2