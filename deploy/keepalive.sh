#!/usr/bin/env bash
# 本机保活：每 10 分钟打一次自己的端口。
#
# 只对「按 CPU/网络活动判断」的平台有效。如果平台是整机停机，
# 这个脚本会跟着一起停 —— 那种情况必须靠外部探活（见 README 第 9 节）。
#
# InstaBox 不需要这个：它闲置暂停后，访问发布的 HTTPS 端口会自动唤醒，
# 详见 INSTABOX.md。
#
# crontab:
#   */10 * * * * /home/你的用户名/deploy/keepalive.sh >> /home/你的用户名/deploy/keepalive.log 2>&1
set -uo pipefail

LOG="$(dirname "$0")/keepalive.log"

# 日志自己管，不然几个月就把 20G 的盘吃掉一截
if [ -f "$LOG" ] && [ "$(stat -c%s "$LOG" 2>/dev/null || echo 0)" -gt 1048576 ]; then
  tail -n 200 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
fi

for port in 6185 6099; do
  # 注意别写成 code=$(curl ... || echo 000)：curl 失败时 -w 已经输出过 000，
  # 追加会变成 000000，日志里看不出到底通没通
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://127.0.0.1:$port/" 2>/dev/null) || code=000
  echo "$(date '+%F %T') $port -> $code"
done
