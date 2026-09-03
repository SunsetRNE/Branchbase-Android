#!/usr/bin/env bash
# env-persist.sh —— 环境持久化（幂等，仅在 ~/.bashrc 写入一行 source，不再追加一堆 export）
#
# 环境变量本体已迁移到 tools/env/env.rc（进仓库），此处只做「让新 shell 自动加载」这件事。
# 用法：tools/env/env-persist.sh

set -e

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ENV_RC="$SCRIPT_DIR/env.rc"
BASHRC="$HOME/.bashrc"

[ -f "$ENV_RC" ] || { echo "env.rc not found: $ENV_RC" >&2; exit 1; }
touch "$BASHRC"

MARKER="branchbase tools/env/env.rc"
if ! grep -q "$MARKER" "$BASHRC"; then
  cat >> "$BASHRC" <<EOF
# >>> $MARKER >>>
[ -f "$ENV_RC" ] && source "$ENV_RC"
# <<< $MARKER <<<
EOF
  echo "[env-persist] 已在 ~/.bashrc 追加 source 行"
else
  echo "[env-persist] ~/.bashrc 已包含 source 行，跳过"
fi