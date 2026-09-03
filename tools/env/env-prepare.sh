#!/usr/bin/env bash
# env-prepare.sh —— 环境准备（预存资产优先 + 固定镜像，已删除镜像测速）
#
# 职责（与「编译」解耦，不含任何编译预热）：
#   1. 安装基础包（wget/curl/unzip/zip）
#   2. 确保 JDK 17
#   3. 确保 Android SDK（cmdline-tools + platform-tools + platforms/build-tools，固定镜像）
#   4. 确保 Gradle 9.1.0（优先 tools/gradle/ 预存 zip，否则固定镜像下载预存）
#   5. 替换 ARM64 AAPT2（用 tools/aapt2/ 预存资产）
#   6. 确保 Rust aarch64-linux-android target（本地交叉编译依赖）
#
# 用法：tools/env/env-prepare.sh
# 已彻底放弃 ping/分段测速，走「预存资产 + 固定镜像」离线优先路径。

set -e

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
source "$SCRIPT_DIR/mirrors.conf"

log() { echo "[env-prepare] $*" >&2; }
command_exists() { command -v "$1" >/dev/null 2>&1; }

install_packages() {
  local packages=("$@")
  if command_exists apt-get; then
    local sudo_cmd=""; command_exists sudo && sudo_cmd="sudo"
    log "Installing packages: ${packages[*]}"
    $sudo_cmd apt-get update
    $sudo_cmd apt-get install -y "${packages[@]}"
  else
    log "apt-get not found; please install: ${packages[*]}"
  fi
}

download_file() {
  local url="$1" dest="$2"
  log "Downloading $url"
  if command_exists curl; then
    curl -L --connect-timeout 30 --max-time 300 --retry 3 --retry-delay 3 "$url" -o "$dest"
  elif command_exists wget; then
    wget --timeout=30 --tries=3 --waitretry=3 -O "$dest" "$url"
  else
    log "curl or wget required"; exit 1
  fi
}

ensure_java() {
  if command_exists java; then
    local version major
    version=$(java -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p')
    major=${version%%.*}
    [[ "$major" == "1" ]] && major=$(echo "$version" | cut -d. -f2)
    if [[ -n "$major" && "$major" -ge 17 ]]; then log "Java $version OK"; return; fi
    log "Java <17; upgrading"
  fi
  install_packages openjdk-17-jdk
}

resolve_java_home() {
  [[ -n "${JAVA_HOME:-}" && -d "$JAVA_HOME" ]] && return
  if command_exists java; then
    local java_path; java_path=$(readlink -f "$(command -v java)")
    JAVA_HOME=$(dirname "$(dirname "$java_path")"); export JAVA_HOME
  fi
}

ensure_android_tools() {
  ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android}}"
  export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"

  if [[ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
    log "Downloading Android cmdline-tools (fixed mirror)"
    install_packages unzip
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    local tmp_dir; tmp_dir=$(mktemp -d)
    local zip_path="$tmp_dir/cmdline-tools.zip"
    local url="${ANDROID_CMDLINE_MIRROR}/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip"
    download_file "$url" "$zip_path" \
      || download_file "${ANDROID_CMDLINE_MIRROR_FALLBACK}/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" "$zip_path"
    unzip -q "$zip_path" -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf "$tmp_dir"
  fi

  export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
  log "Installing Android SDK packages"
  yes | sdkmanager --licenses >/dev/null || true
  sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
}

ensure_gradle() {
  install_packages unzip
  local gradle_root="${GRADLE_ROOT:-$HOME/gradle}"
  local zip="$gradle_root/${GRADLE_DIST}-bin.zip"
  local preset_zip="$ROOT_DIR/tools/gradle/${GRADLE_DIST}-bin.zip"
  mkdir -p "$gradle_root"

  # ① 预存资产优先：tools/gradle/ 已有 zip 则直接复用（命中）
  if [[ -f "$preset_zip" ]]; then
    log "命中预存资产：$preset_zip"
    [[ "$preset_zip" != "$zip" ]] && cp "$preset_zip" "$zip"
  elif [[ -f "$zip" ]]; then
    log "Gradle zip 已存在（命中）：$zip"
  else
    # ② 未命中：固定镜像下载并预存到 tools/gradle/
    log "下载 Gradle ${GRADLE_VERSION}（固定镜像）"
    local url="${GRADLE_MIRROR}/${GRADLE_DIST}-bin.zip"
    download_file "$url" "$zip" \
      || download_file "${GRADLE_MIRROR_FALLBACK}/${GRADLE_DIST}-bin.zip" "$zip"
    mkdir -p "$ROOT_DIR/tools/gradle"
    cp "$zip" "$preset_zip"
    log "已预存 Gradle zip 到 tools/gradle/"
  fi

  if [[ ! -d "$gradle_root/$GRADLE_DIST" ]]; then
    log "Extracting Gradle ${GRADLE_VERSION}"
    unzip -q "$zip" -d "$gradle_root"
  fi

  export GRADLE_HOME="$gradle_root/$GRADLE_DIST"
  export PATH="$GRADLE_HOME/bin:$PATH"
}

ensure_rust_android_target() {
  if ! command_exists rustup; then
    log "rustup not found; installing via rustup script"
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    # shellcheck disable=SC1091
    source "$HOME/.cargo/env"
  fi
  if ! rustup target list --installed 2>/dev/null | grep -q aarch64-linux-android; then
    log "Adding rust target aarch64-linux-android"
    rustup target add aarch64-linux-android
  fi
}

# 替换 ARM64 AAPT2（用 tools/aapt2/ 预存资产）
replace_aapt2() {
  local bundled_aapt2="$ROOT_DIR/tools/aapt2/aapt2-arm64-v8a"
  local expected_sha256="e5b5ff7f0d4f6ecd7fa5d05d77fed3f09f6f1bf80f078b8aada82bc578848561"
  [[ -f "$bundled_aapt2" ]] || { log "预存 AAPT2 缺失：$bundled_aapt2"; return 0; }

  local actual; actual=$(sha256sum "$bundled_aapt2" | awk '{print $1}')
  if [[ "$actual" != "$expected_sha256" ]]; then
    log "AAPT2 校验失败：expected $expected_sha256 got $actual"
    return 1
  fi

  local tmp_dir; tmp_dir=$(mktemp -d)
  local aapt2_path="$tmp_dir/aapt2"
  cp "$bundled_aapt2" "$aapt2_path"; chmod +x "$aapt2_path"

  if [[ -d "$ANDROID_HOME/build-tools/35.0.0" ]]; then
    cp "$aapt2_path" "$ANDROID_HOME/build-tools/35.0.0/aapt2"
    log "已替换 SDK build-tools aapt2"
  fi

  local gradle_cache_root="${GRADLE_USER_HOME:-$HOME/.gradle}/caches"
  local gradle_aapt_dir="$gradle_cache_root/modules-2/files-2.1/com.android.tools.build/aapt2"
  if [[ -d "$gradle_aapt_dir" ]]; then
    local updated=0 jar_path jar_dir
    while IFS= read -r -d '' jar_path; do
      jar_dir=$(dirname "$jar_path")
      cp "$aapt2_path" "$jar_dir/aapt2"
      (cd "$jar_dir" && zip -q -f "$(basename "$jar_path")" aapt2)
      updated=$((updated + 1))
    done < <(find "$gradle_aapt_dir" -name "aapt2-*-linux.jar" -print0)
    log "已更新 Gradle cache aapt2 jars: $updated"
  fi

  rm -rf "$tmp_dir"
}

main() {
  install_packages wget curl unzip zip
  ensure_java
  resolve_java_home
  ensure_android_tools
  ensure_gradle
  ensure_rust_android_target
  replace_aapt2
  log "环境准备完成（预存资产 + 固定镜像，无测速）"
}

main "$@"