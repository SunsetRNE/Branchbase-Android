#!/usr/bin/env bash
# release-notes.sh —— 生成发布页的「改动记录」段
#
# 背景：发布页原先只有静态模板（beta）或 `git log -10`（正式版），
# **没有可点击的改动引用**，用户看不出这次发布到底改了什么。
#
# 本脚本生成 markdown（交给 softprops/action-gh-release 的 `body` / `body_path`）：
#   ① 锚定「上一个同类标签」→ 本次标签：列出区间内的提交（每条带 commit 链接）
#   ② 给出 compare 链接（GitHub 的完整变更对比；官方自动说明里也会再给一次）
#   ③ 没有上一个标签（首次发布）时退回「最近 N 个提交」并明确说明
#
# 与官方「自动生成发布说明」（generate_release_notes）的关系：
#   官方那段由 GitHub 生成（合并的 PR 列表 / 贡献者 / 完整变更链接），配置见 `.github/release.yml`；
#   本脚本产出的内容会被 action **前置**到官方说明之前（action 内部：
#   `body = body + "\n\n" + generated`）。两者互补：
#   - 本项目多数改动直接推送到 main（没有 PR），官方那段可能为空 → 靠本脚本的提交清单兜底；
#   - 有 PR 时官方那段给出按类别分组的总结。
#
# 用法：
#   tools/build/release-notes.sh \
#     --repo owner/name \
#     --tag v1.0.13-20260910-1231-30fcb35-beta \
#     --previous-pattern 'v*-beta' \
#     [--exclude-substring '-beta'] \
#     [--exclude-author 'github-actions[bot]'] \
#     [--previous-out previous.txt] [--out notes.md] [--max 100]
#
# 退出码：0 成功（即使没有提交也会生成合法段落）；2 参数错误。

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
cd "$ROOT_DIR"

REPO=""
TAG=""
PATTERN=""
EXCLUDE=""
EXCLUDE_AUTHOR=""
OUT=""
PREVIOUS_OUT=""
MAX=100

while [ $# -gt 0 ]; do
  case "$1" in
    --repo) REPO="${2:-}"; shift 2 ;;
    --tag) TAG="${2:-}"; shift 2 ;;
    --previous-pattern) PATTERN="${2:-}"; shift 2 ;;
    --exclude-substring) EXCLUDE="${2:-}"; shift 2 ;;
    --exclude-author) EXCLUDE_AUTHOR="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --previous-out) PREVIOUS_OUT="${2:-}"; shift 2 ;;
    --max) MAX="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$REPO" ] || [ -z "$TAG" ]; then
  echo "用法：release-notes.sh --repo owner/name --tag <tag> [--previous-pattern 'v*-beta'] [--out file] [--previous-out file]" >&2
  exit 2
fi

BASE_URL="https://github.com/$REPO"

# ── ① 找上一个同类标签：按创建时间倒序，取第一个「是 HEAD 祖先且不是本次标签」的 ──
PREVIOUS=""
if [ -n "$PATTERN" ]; then
  while IFS= read -r t; do
    [ -z "$t" ] && continue
    [ "$t" = "$TAG" ] && continue
    # 正式版锚定时要排除 beta 标签（同一版本的 beta 往往比正式版更新）
    if [ -n "$EXCLUDE" ] && [[ "$t" == *"$EXCLUDE"* ]]; then continue; fi
    if git merge-base --is-ancestor "$t" HEAD 2>/dev/null; then
      PREVIOUS="$t"
      break
    fi
  done < <(git tag --list "$PATTERN" --sort=-creatordate 2>/dev/null || true)
fi

# ── ② 收集提交 ──
#
# 注意必须用 `tformat` 而不是 `format`：`--pretty=format:` 输出**不带结尾换行**，
# 而 `while read` 读到「无换行的最后一行」时返回非零 → 循环体会被跳过，
# 表现为「只有一个提交时列表为空」这种诡异现象（本地实测踩到）。
#
# 同时按作者过滤：CI 自己提交的 `.so` / 校验文件（github-actions[bot]）不该出现在
# 用户看到的「改动记录」里。
collect_commits() {
  local range="$1"
  # shellcheck disable=SC2086
  git log $range --no-merges --pretty=tformat:"%H%x09%s%x09%an" 2>/dev/null \
    | { if [ -n "$EXCLUDE_AUTHOR" ]; then grep -v -F "$EXCLUDE_AUTHOR" || true; else cat; fi; } \
    | cut -f1,2
}

if [ -n "$PREVIOUS" ]; then
  RANGE="$PREVIOUS..HEAD"
else
  RANGE="-n $MAX"
fi

TOTAL=$(collect_commits "$RANGE" | grep -c . || true)
mapfile -t COMMITS < <(collect_commits "$RANGE" | head -n "$MAX")

# ── ③ 生成 markdown ──
generate() {
  echo "## 改动记录"
  echo ""
  if [ -n "$PREVIOUS" ]; then
    echo "自 [\`$PREVIOUS\`]($BASE_URL/releases/tag/$PREVIOUS) 以来的 **${TOTAL}** 个提交"
    echo "（[完整对比]($BASE_URL/compare/$PREVIOUS...$TAG)）："
  else
    echo "首次发布（无上一个标签），列出最近 **${TOTAL}** 个提交："
  fi
  echo ""

  if [ "${TOTAL:-0}" -eq 0 ]; then
    echo "> 本次没有新增提交（可能是同一次构建的重跑）。"
    echo ""
    return 0
  fi

  local line sha subject short clean
  for line in "${COMMITS[@]}"; do
    [ -z "$line" ] && continue
    sha="${line%%$'\t'*}"
    subject="${line#*$'\t'}"
    short=$(printf '%s' "$sha" | cut -c1-7)
    # 去掉换行、竖线与连续空白，避免破坏 markdown 列表
    clean=$(printf '%s' "$subject" | tr -d '\r\n' | tr '|' '/' | sed 's/  */ /g')
    echo "- [\`$short\`]($BASE_URL/commit/$sha) $clean"
  done

  if [ -n "$PREVIOUS" ] && [ "$TOTAL" -gt "$MAX" ] 2>/dev/null; then
    echo ""
    echo "> 仅列出前 $MAX 个提交，其余 $((TOTAL - MAX)) 个见上方「完整对比」。"
  fi
  echo ""
}

if [ -n "$OUT" ]; then
  generate > "$OUT"
else
  generate
fi

if [ -n "$PREVIOUS_OUT" ]; then
  printf '%s' "$PREVIOUS" > "$PREVIOUS_OUT"
fi
