#!/bin/bash
# 只重启 NapCat（QQ 协议端），不触碰 AstrBot 与容器本身。
#
# 什么时候用：Electron 版 NapCat 被系统 OOM 掉、或 QQ 进程僵死，此时 6099 端口
# 不再监听，但 proot 与 AstrBot 都还活着——容器不会退出，也就不会触发自动重启。
# App 的健康检查盯到 6099 连续失败后会调用本脚本。
cd /root || exit 1

export PATH="/opt/astrbot/bin:/usr/local/bin:$PATH"
export LANG=C.UTF-8
export NAPCAT_WEBUI_SECRET_KEY="${NAPCAT_WEBUI_SECRET_KEY:-astrmobile}"
export NAPCAT_WEBUI_PREFERRED_PORT=6099

if [ ! -f /root/launcher.sh ]; then
  echo "[napcat-restart] 未找到 /root/launcher.sh（NapCat 未安装），跳过"
  exit 3
fi

echo "[napcat-restart] 开始重启 NapCat $(date '+%F %T')"

# ---------------------------------------------------------------- 停旧的
# 只按容器内独有路径匹配，别碰宿主上用户自己的 QQ。
# 排除 $$：本脚本自身命令行里含 restart-napcat.sh，不在匹配列表里，但仍留一层保险。
KILLED=""
for proc_dir in /proc/[0-9]*; do
  [ -d "$proc_dir" ] || continue
  _pid=${proc_dir#/proc/}
  [ "$_pid" = "$$" ] && continue
  _cmd=$(tr '\0' ' ' < "$proc_dir/cmdline" 2>/dev/null) || continue
  [ -z "$_cmd" ] && continue
  case "$_cmd" in
    *"/opt/QQ/"*|*"/root/napcat/"*|*"/root/launcher.sh"*)
      KILLED="$KILLED $_pid"
      ;;
  esac
done

if [ -n "$KILLED" ]; then
  # shellcheck disable=SC2086
  kill -TERM $KILLED 2>/dev/null
  sleep 2
  for _pid in $KILLED; do
    kill -0 "$_pid" 2>/dev/null && kill -9 "$_pid" 2>/dev/null
  done
  echo "[napcat-restart] 已停止旧进程：$KILLED"
else
  echo "[napcat-restart] 没有发现残留的 NapCat 进程"
fi

# Xvfb 的锁也要清，否则 QQ 起不来（与 start.sh 保持一致）
rm -f /root/napcat.pid /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true
mkdir -p /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix 2>/dev/null || true

# ---------------------------------------------------------------- 起新的
echo "[napcat-restart] 重新拉起 NapCat"
nohup bash /root/launcher.sh > /root/napcat.log 2>&1 &
NEW_PID=$!
echo "$NEW_PID" > /root/napcat.pid

sleep 3
if kill -0 "$NEW_PID" 2>/dev/null; then
  echo "[napcat-restart] NapCat 已拉起（pid=$NEW_PID），等待 6099 监听"
  exit 0
fi

echo "[napcat-restart] NapCat 启动后立即退出，日志末尾："
tail -8 /root/napcat.log 2>/dev/null
exit 4
