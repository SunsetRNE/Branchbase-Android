#!/usr/bin/env bash
# fetch-core.sh —— 取一份**与源码同代**的 Rust 核心 .so（不需要 NDK、不需要编译）
#
# 为什么需要它：`main` 分支按约定不留 `.so`（见 docs/specs/BUILD-NOTES.md §四），
# 而缺少它的 APK **一启动就崩** —— `RustBridge` 的类初始化里 `System.loadLibrary("branchbase_core")`
# 抛 `UnsatisfiedLinkError`，`MainActivity.onCreate` 在 `setContent` 之前就踩上（现场只能靠
# `adb logcat -b crash` 看）。只改 Kotlin / UI 的时候，没必要为这一枚文件跑 18 分钟交叉编译：
# CI 每次发布都会把同 commit 的 `.so` 打进 Beta APK，取出来即可。
#
# 两种取法（默认先试 ①，失败自动退 ②）：
#   ① Release：按 commit 七位哈希找文件名带它的 Beta APK → 解包取 lib/arm64-v8a/libbranchbase_core.so
#   ② 分支：  origin/beta 里那份（CI 每次发布 force-push 的单提交，只放 .so + 校验文件）
#
# 用法：
#   tools/build/fetch-core.sh                  # 按当前 HEAD 的七位哈希
#   tools/build/fetch-core.sh --commit ab12cd3 # 指定 commit
#   tools/build/fetch-core.sh --from-beta      # 只走 ②（不联网到 Release）
#   tools/build/fetch-core.sh --out <path>     # 自定义输出（默认 app/src/main/jniLibs/arm64-v8a/libbranchbase_core.so）
#
# 安全：写盘前校验 —— 必须是 AArch64 的 ELF、且含 `Java_com_branchbase_core_RustBridge_` 符号。
# 不满足就**不写**（宁可不生成，也不要留下一个"看着成功、装上去就崩"的文件）。
set -e

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)

COMMIT=""
EXPLICIT_COMMIT=false
FROM_BETA_ONLY=false
OUT="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a/libbranchbase_core.so"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --commit) COMMIT="${2:-}"; EXPLICIT_COMMIT=true; shift 2 ;;
    --from-beta) FROM_BETA_ONLY=true; shift ;;
    --out) OUT="${2:-}"; shift 2 ;;
    -h|--help)
      sed -n '1,40p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "未知参数：$1（--help 看用法）" >&2; exit 1 ;;
  esac
done

cd "$ROOT_DIR"

if [[ -z "$COMMIT" ]]; then
  COMMIT=$(git rev-parse --short=7 HEAD)
fi
if ! [[ "$COMMIT" =~ ^[0-9a-f]{7,40}$ ]]; then
  echo "[fetch-core] commit 必须是十六进制哈希：$COMMIT" >&2
  exit 1
fi

# owner/repo：从 origin 的 URL 里解析（https://…/Owner/Repo.git 或 git@…:Owner/Repo.git）
ORIGIN=$(git config --get remote.origin.url || true)
REPO=$(printf '%s' "$ORIGIN" | sed -E 's#\.git$##' | sed -E 's#^.*[:/]([^/]+/[^/]+)$#\1#')
if [[ -z "$REPO" || "$REPO" == "$ORIGIN" ]]; then
  REPO="SunsetRNE/Branchbase-Android"
fi
echo "[fetch-core] 仓库：$REPO · 目标 commit：$COMMIT"

mkdir -p "$(dirname "$OUT")"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# ── 写盘前的校验：AArch64 ELF + JNI 符号 ──
verify_and_place() {
  local src="$1"
  local label="$2"
  python3 - "$src" "$OUT" "$label" <<'PY'
import struct, sys, hashlib

src, out, label = sys.argv[1], sys.argv[2], sys.argv[3]
data = open(src, 'rb').read()

if len(data) < 64 or data[:4] != b'\x7fELF':
    sys.exit(f"[fetch-core] {label} 不是 ELF 文件，拒绝写入")

e_machine = struct.unpack_from('<H', data, 18)[0]
if e_machine != 183:
    sys.exit(f"[fetch-core] {label} 的 e_machine={e_machine}（需要 183=AArch64），拒绝写入")

if b'Java_com_branchbase_core_RustBridge_' not in data:
    sys.exit(f"[fetch-core] {label} 里找不到 RustBridge 的 JNI 符号，拒绝写入（多半取错了文件）")

open(out, 'wb').write(data)
print(f"[fetch-core] 来源：{label}")
print(f"[fetch-core] 大小：{len(data) / 1048576:.2f} MB · sha256 {hashlib.sha256(data).hexdigest()[:16]}…")
print(f"[fetch-core] 已写入：{out}")
PY
}

extract_so() {
  local apk="$1" dest="$2"
  if command -v unzip >/dev/null 2>&1; then
    unzip -p "$apk" lib/arm64-v8a/libbranchbase_core.so > "$dest"
  else
    python3 - "$apk" "$dest" <<'PY'
import sys, zipfile
apk, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z:
    data = z.read('lib/arm64-v8a/libbranchbase_core.so')
open(dest, 'wb').write(data)
PY
  fi
}

# ── 分支里的 .so（CI 每次发布 force-push 的单提交）────────────────────────────
#
# 这里**不能无脑拿来用**：`origin/beta` 里那份是「某个 main 提交」编出来的，而当前 HEAD
# 可能已经往前走了。真正决定能不能用的是 **JNI 面**（`core/` 与 `RustBridge.kt`）有没有变，
# 而不是 commit 哈希是否相等 —— 所以下面先算差异，再决定允许 / 拒绝。
BETA_SO_PATH="app/src/main/jniLibs/arm64-v8a/libbranchbase_core.so"

die() {
  echo "" >&2
  echo "[fetch-core] $*" >&2
  exit 1
}

beta_base_commit() {
  git rev-parse --short=7 "origin/beta^" 2>/dev/null || true
}

# HEAD 与「那份 .so 的源码基线」之间，JNI 面有没有变（空输出 = 没变）。
jni_drift_since() {
  local base="$1"
  [[ -z "$base" ]] && return 0
  git diff --name-only "$base" HEAD -- core app/src/main/java/com/branchbase/core/RustBridge.kt 2>/dev/null || true
}

prepare_beta_branch() {
  echo "[fetch-core] ② 看 origin/beta 分支…"
  git fetch --quiet origin beta 2>/dev/null || true
  if git cat-file -e "origin/beta:$BETA_SO_PATH" 2>/dev/null; then
    return 0
  fi
  echo "[fetch-core] origin/beta 里没有 .so（或没有该分支）" >&2
  return 1
}

place_from_beta_branch() {
  local base
  base=$(beta_base_commit)
  git show "origin/beta:$BETA_SO_PATH" > "$TMP/from-beta.so"
  verify_and_place "$TMP/from-beta.so" "origin/beta（基线 $base）"
}

# ── --from-beta：显式要求「最新一份」，允许落后，但要把差异喊出来 ──
if [[ "$FROM_BETA_ONLY" == true ]]; then
  prepare_beta_branch || die "origin/beta 不可用；改用 build-core.sh 重编。"
  BASE=$(beta_base_commit)
  DRIFT=$(jni_drift_since "$BASE")
  if [[ -n "$DRIFT" ]]; then
    echo "[fetch-core] ⚠ origin/beta 的 .so 由 $BASE 编出，而 HEAD（$COMMIT）之后 JNI 面有变化：" >&2
    printf '%s\n' "$DRIFT" | sed 's/^/    /' >&2
    echo "[fetch-core] ⚠ 已按 --from-beta 继续（显式参数 = 你接受这个风险）；改过 Rust 就该用 build-core.sh。" >&2
  fi
  place_from_beta_branch
  exit 0
fi

# ── ① GitHub Release：文件名里带本次 commit 的 Beta APK（最精确的来源）──
echo "[fetch-core] ① 在 Release 里找 commit $COMMIT 的 Beta 包…"
TAG=""
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  TAG=$(gh api "repos/$REPO/releases?per_page=60" \
          --jq ".[] | select(any(.assets[]; .name | test(\"-$COMMIT-debug\\\\.apk$\"))) | .tag_name" \
          2>/dev/null | grep -E '^v[0-9]' | head -1 || true)
fi

if [[ -n "$TAG" ]]; then
  echo "[fetch-core] 命中 release：$TAG"
  APK=""
  if gh release download "$TAG" --repo "$REPO" --pattern "*-debug.apk" --dir "$TMP" --clobber; then
    APK=$(find "$TMP" -maxdepth 1 -name "*-debug.apk" | head -1)
  fi
  if [[ -n "$APK" ]]; then
    extract_so "$APK" "$TMP/from-release.so"
    verify_and_place "$TMP/from-release.so" "Release $TAG 的 $COMMIT"
    exit 0
  fi
  echo "[fetch-core] Release 下载失败，改走 origin/beta" >&2
fi

# 显式指定了 commit 时**不许**退到别的版本 —— 那正是「取错 .so」的事故形态。
if [[ "$EXPLICIT_COMMIT" == true ]]; then
  die "Release 里没有 commit $COMMIT 的 Beta 包（显式指定 commit 时不会退到 origin/beta）。
  下一步：等这个 commit 的 CI 出包，或 tools/build/build-core.sh 重编。"
fi

# ── ② 隐式回退：先验 JNI 面，再决定 ──
prepare_beta_branch || die "Release 与 origin/beta 都取不到。
  下一步：tools/build/build-core.sh（本地需 NDK sysroot，ARM64 约 18 分钟）。"
BASE=$(beta_base_commit)
DRIFT=$(jni_drift_since "$BASE")
if [[ -n "$DRIFT" ]]; then
  echo "[fetch-core] 拒绝写入：origin/beta 的 .so 由 $BASE 编出，而 HEAD（$COMMIT）之后 JNI 面有变化：" >&2
  printf '%s\n' "$DRIFT" | sed 's/^/    /' >&2
  die "拿旧 .so 顶替不会立刻报错，只会把崩点推到某个业务页。
  下一步：tools/build/build-core.sh 重编（或 --from-beta 显式接受风险、--commit <hash> 指定已发布版本）。"
fi
echo "[fetch-core] JNI 面自 $BASE 以来未变（core/ 与 RustBridge.kt 无差异），可以用这份"
place_from_beta_branch
