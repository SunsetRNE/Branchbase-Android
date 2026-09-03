#!/usr/bin/env bash
# setup_android_env.sh —— 本地 proot 环境一键入口（已重构为「判定→准备→持久化」三脚本解耦）
#
# 重构说明（对应 docs/build-optimization-proposal.md）：
#   - 环境判定与准备解耦：tools/env/env-detect.sh（纯判定）→ env-prepare.sh（缺失才准备）
#   - 已彻底删除镜像测速（ping / 分段测速），走「预存资产 + 固定镜像」离线优先路径
#   - 环境变量进仓库 tools/env/env.rc，env-persist.sh 只在 ~/.bashrc 追加一行 source
#   - 编译预热（AAPT2 warmup）已剥离到 tools/build/warmup-aapt2.sh，不再混入本脚本
#
# 用法：./setup_android_env.sh

set -e

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "$SCRIPT_DIR"

ENV_DIR="$SCRIPT_DIR/tools/env"

# 保证 gradlew 可执行
if [[ -f "./gradlew" ]]; then
  chmod +x "./gradlew"
fi

echo "[setup] ① 环境判定（tools/env/env-detect.sh）"
if "$ENV_DIR/env-detect.sh"; then
  echo "[setup] 环境已就绪，跳过准备"
else
  echo "[setup] ② 环境准备（tools/env/env-prepare.sh，缺失项才下载，预存资产 + 固定镜像）"
  "$ENV_DIR/env-prepare.sh"
fi

echo "[setup] ③ 环境持久化（tools/env/env-persist.sh）"
"$ENV_DIR/env-persist.sh"

echo "[setup] 完成。请执行：source tools/env/env.rc  （或重开 shell）"
echo "[setup] 可选：首次编译前执行 tools/build/warmup-aapt2.sh 预热 ARM64 AAPT2"