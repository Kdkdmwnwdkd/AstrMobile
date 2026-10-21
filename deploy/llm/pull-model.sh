#!/usr/bin/env bash
# 拉模型并跑一句，确认容器真的能推理。
# 用法：./pull-model.sh [模型名]
set -euo pipefail

MODEL="${1:-qwen2.5:3b-instruct-q4_K_M}"

if ! docker ps --format '{{.Names}}' | grep -qx ollama; then
  echo "ollama 容器没在跑，先执行：docker compose up -d"
  exit 1
fi

echo "==> 拉取 $MODEL（3B 约 2GB，7B 约 4.5GB）"
docker exec ollama ollama pull "$MODEL"

echo "==> 试跑一句"
# 首次加载模型要几秒到几十秒，纯 CPU 属于正常
docker exec ollama ollama run "$MODEL" "用一句话介绍你自己" </dev/null

echo
echo "==> 完成。在 AstrBot 里这样填："
echo "    API Base : http://<本机内网IP>:11434/v1"
echo "    API Key  : ollama"
echo "    模型     : $MODEL"
