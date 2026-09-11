#!/usr/bin/env bash
# env-prepare-arm64-patches.sh —— ARM64(proot) 本地构建环境的补充准备
#
# 背景：setup_android_env.sh / env-prepare.sh 的原始路径只覆盖了
#   JDK + Android SDK + Gradle + Rust target，
# 在 aarch64 proot 环境下还缺这几项，缺任何一项都无法真正编出 APK：
#
#   ① lld         —— Rust 链接 Android .so 时 clang 需要 ld.lld（build-android.sh 注释未提）
#   ② cargo-ndk   —— env-detect.sh 与 CI 都要求（本地走 clang+sysroot，CI 走它）
#   ③ ARM64 aapt2 —— AGP 从 Google Maven 取的 aapt2 只有 x86-64，ARM64 上无法执行；
#                    官方 SDK/NDK 也不发布 linux-aarch64。改用 Termux 的 aarch64 aapt2
#                    （Android 16 / aapt 2.20），并用 patchelf 把库路径写进 ELF（含每个 .so），
#                    绕开 Android linker 的 namespace 隔离，做成"无环境变量即可执行"的产物。
#   ④ gradle 绑定 —— 通过用户级 ~/.gradle/gradle.properties 的
#                    android.aapt2FromMavenOverride 指向 ③，不改仓库内任何构建脚本。
#   ⑤ env.rc PATH —— 补上 $HOME/.cargo/bin，否则 env-detect 的 rust 判定永远 false。
#
# 幂等：可重复执行；已就绪的步骤会跳过。
# 用法：tools/env/env-prepare-arm64-patches.sh

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)

log() { echo "[arm64-patch] $*" >&2; }
command_exists() { command -v "$1" >/dev/null 2>&1; }

AAPT2_DIR=/opt/aapt2-arm64
AAPT2_LIB="$AAPT2_DIR/lib"
GRADLE_PROPS="$HOME/.gradle/gradle.properties"
TERMUX_BASE=https://packages.termux.dev/apt/termux-main

# ---------- 架构守卫 ----------
# 本脚本准备的全是 aarch64 原生件：Termux 的 aarch64 deb、预存的 ARM64 aapt2。
# 在 x86_64 上继续跑只会「装一堆用不上的包 → 把 aarch64 的 aapt2 写进全局
# ~/.gradle/gradle.properties → 卡在自检，让 setup_android_env.sh 整体失败」，
# 而 setup_android_env.sh 是**无条件**调用本脚本的（「环境已就绪」只跳过 ②），
# 所以这里必须自己挡一道，而不是指望调用方判断。
if [ "$(uname -m)" != "aarch64" ] && [ "$(uname -m)" != "arm64" ]; then
  log "当前架构 $(uname -m) 非 ARM64，跳过（本脚本仅服务 aarch64/proot 本地构建）"
  exit 0
fi

# ---------- ① lld ----------
ensure_lld() {
  if command_exists ld.lld; then log "lld 已就绪"; return; fi
  log "安装 lld（Rust 链接 Android .so 需要）"
  apt-get install -y --no-install-recommends lld
}

# ---------- ② cargo-ndk ----------
ensure_cargo_ndk() {
  export CARGO_HOME="${CARGO_HOME:-$HOME/.cargo}"
  export PATH="$CARGO_HOME/bin:$PATH"
  if command_exists cargo-ndk; then log "cargo-ndk 已就绪"; return; fi
  log "安装 cargo-ndk 4.1.2（与 CI 锁定版本一致）"
  cargo install cargo-ndk --version 4.1.2 --locked
}

# ---------- ③ ARM64 aapt2（自包含：rpath 写进 ELF）----------
ensure_aapt2_arm64() {
  if [ -x "$AAPT2_DIR/aapt2" ] && "$AAPT2_DIR/aapt2" version >/dev/null 2>&1; then
    log "ARM64 aapt2 已就绪"
  else
    log "构建自包含 ARM64 aapt2（Termux 运行时）"
    command_exists patchelf || apt-get install -y --no-install-recommends patchelf

    local tmp; tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' RETURN
    curl -sL -o "$tmp/Packages" "$TERMUX_BASE/dists/stable/main/binary-aarch64/Packages"

    # aapt2 及其运行时依赖（soname 见 Packages 的 Depends）
    local want="aapt2 abseil-cpp libprotobuf fmt libc++ libexpat libpng libzopfli zlib"
    local pkg fn
    for pkg in $want; do
      fn=$(awk -v RS='' -v p="$pkg" '
        $0 ~ "^Package: " p "$" { for (i=1;i<=NF;i++) if ($i ~ /^Filename: /) { sub(/^Filename: /,"",$i); print $i } }' \
        "$tmp/Packages" | head -1)
      [ -n "$fn" ] || { log "找不到 Termux 包：$pkg"; continue; }
      curl -sL -o "$tmp/$pkg.deb" "$TERMUX_BASE/$fn"
      dpkg-deb -x "$tmp/$pkg.deb" "$tmp/x" 2>/dev/null || true
    done

    local termux_lib="$tmp/x/data/data/com.termux/files/usr/lib"
    mkdir -p "$AAPT2_LIB"
    cp "$tmp/x/data/data/com.termux/files/usr/bin/aapt2" "$AAPT2_DIR/aapt2"
    find "$termux_lib" -maxdepth 1 \( -name '*.so' -o -name '*.so.*' \) -exec cp -a {} "$AAPT2_LIB/" \;
    chmod +x "$AAPT2_DIR/aapt2"

    # 关键：给主程序与每个 .so 都写入 rpath，否则 Android linker 的 namespace
    # 隔离会让间接依赖解析失败（如 libprotobuf → libabsl_*）。
    patchelf --set-rpath "$AAPT2_LIB" "$AAPT2_DIR/aapt2"
    local f
    for f in "$AAPT2_LIB"/*.so "$AAPT2_LIB"/*.so.*; do
      [ -f "$f" ] || continue
      patchelf --set-rpath "$AAPT2_LIB" "$f" 2>/dev/null || true
    done
  fi

  # 自检：不允许依赖外部环境变量
  if ! env -u LD_LIBRARY_PATH "$AAPT2_DIR/aapt2" version >/dev/null 2>&1; then
    log "ARM64 aapt2 自检失败（无法独立执行）"; return 1
  fi
  log "ARM64 aapt2 自检通过：$(env -u LD_LIBRARY_PATH "$AAPT2_DIR/aapt2" version 2>&1 | tail -1)"
}

# ---------- ④ Gradle 绑定 ----------
ensure_gradle_binding() {
  mkdir -p "$(dirname "$GRADLE_PROPS")"
  touch "$GRADLE_PROPS"
  if grep -q '^android.aapt2FromMavenOverride=' "$GRADLE_PROPS"; then
    sed -i "s|^android.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride=$AAPT2_DIR/aapt2|" "$GRADLE_PROPS"
  else
    {
      echo ""
      echo "# ARM64 本地环境：AGP 默认从 Google Maven 取 x86-64 aapt2，ARM64 上无法执行。"
      echo "# 指向自包含的 aarch64 aapt2（Termux 构建，Android 16 / aapt 2.20）。"
      echo "android.aapt2FromMavenOverride=$AAPT2_DIR/aapt2"
    } >> "$GRADLE_PROPS"
  fi
  log "已绑定 $GRADLE_PROPS → $AAPT2_DIR/aapt2"
}

# ---------- ⑤ env.rc 的 cargo PATH ----------
ensure_env_rc_cargo() {
  local rc="$ROOT_DIR/tools/env/env.rc"
  grep -q 'CARGO_HOME' "$rc" && { log "env.rc 已含 cargo 配置"; return; }
  log "补 env.rc 的 cargo PATH（否则 env-detect 的 rust 恒为 false）"
  python3 - "$rc" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
anchor = 'export GRADLE_HOME="${GRADLE_HOME:-$HOME/gradle/gradle-9.1.0}"\n'
add = ('\n# Rust 工具链（rustup 默认装在 ~/.cargo，env.rc 原先未纳入 PATH）\n'
       'export CARGO_HOME="${CARGO_HOME:-$HOME/.cargo}"\n'
       'export RUSTUP_HOME="${RUSTUP_HOME:-$HOME/.rustup}"\n')
assert anchor in s, "env.rc 锚点未找到"
s = s.replace(anchor, anchor + add)
s = s.replace(':bin:$PATH"', ':bin:$CARGO_HOME/bin:$PATH"')
open(p, 'w').write(s)
PY
}

main() {
  ensure_lld
  ensure_cargo_ndk
  ensure_aapt2_arm64
  ensure_gradle_binding
  ensure_env_rc_cargo
  log "ARM64 补充准备完成。下一步：source tools/env/env.rc && tools/env/env-detect.sh"
}

main "$@"
