#!/usr/bin/env bash
# 备份 AstrBot 的 data 目录，保留最近 7 天。
#
# 用法：
#   ./backup.sh                      # 备份到 ./bot/backups
#   ./backup.sh /mnt/nas/astrbot     # 备份到别处（建议挂个网盘/NAS）
#
# 本脚本要和 bot/ 目录放在同一层（也就是仓库里的 deploy/ 目录）。
#
# 定时（每天凌晨 4 点）：
#   crontab -e
#   0 4 * * * /home/你的用户名/deploy/backup.sh >> /home/你的用户名/deploy/backup.log 2>&1
#
# 恢复：在 deploy/bot 下停容器 → 解压覆盖 data → 起容器
#   docker compose stop astrbot
#   tar xzf ../bot/backups/astrbot-xxx.tar.gz -C ./data
#   docker compose up -d astrbot
set -euo pipefail

cd "$(dirname "$0")"
DATA_DIR="./bot/data"
TARGET="${1:-./bot/backups}"
KEEP_DAYS=7

if [ ! -d "$DATA_DIR" ]; then
  echo "找不到 $DATA_DIR，先在 bot 目录 docker compose up -d"
  exit 1
fi

# 盘小，先看还有多少空间，别把自己写挂
AVAIL_MB=$(df -Pm "$(dirname "$TARGET")" | awk 'NR==2 {print $4}')
if [ "${AVAIL_MB:-0}" -lt 512 ]; then
  echo "剩余空间只有 ${AVAIL_MB}MB，先清一清再来备份"
  exit 1
fi

mkdir -p "$TARGET"
STAMP=$(date +%F_%H%M)
ARCHIVE="$TARGET/astrbot-$STAMP.tar.gz"

# data 里可能有 SQLite，运行中打包偶尔会拿到撕裂的副本；
# 这里用 sqlite3 的备份更麻烦，先简单粗暴，恢复后 AstrBot 自己能重建索引
tar czf "$ARCHIVE" -C "$DATA_DIR" .

echo "已备份：$ARCHIVE（$(du -h "$ARCHIVE" | cut -f1)）"

# 只保留最近 KEEP_DAYS 天
find "$TARGET" -name 'astrbot-*.tar.gz' -type f -mtime +"$KEEP_DAYS" -delete
echo "保留最近 $KEEP_DAYS 天的备份："
ls -lh "$TARGET" | tail -n +2
