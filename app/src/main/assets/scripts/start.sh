#!/bin/bash
# AstrMobile 启动脚本（在 proot 容器内执行）
# NapCat 放后台，AstrBot 放前台：AstrBot 的日志直接进 App 的实时控制台。
cd /root || exit 1

export PATH="/opt/astrbot/bin:/usr/local/bin:$PATH"
export LANG=C.UTF-8

echo "[start] 容器已启动 $(date '+%F %T')"
echo "[start] 工作目录: $(pwd)"

# 清理上次异常退出留下的锁
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null || true
mkdir -p /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix 2>/dev/null || true

# ------------------------------------------------------------------ NapCat
if [ -f /root/launcher.sh ]; then
  echo "[start] 启动 NapCat"
  nohup bash /root/launcher.sh > /root/napcat.log 2>&1 &
  echo $! > /root/napcat.pid
  echo "[start] NapCat pid=$!"
else
  if [ -f /root/.napcat-install-failed ]; then
    echo "[start] NapCat 未安装（上次安装失败），QQ 功能不可用"
  else
    echo "[start] 未找到 NapCat launcher.sh，跳过"
  fi
fi

# ------------------------------------------------------------------ AstrBot
if [ ! -x /opt/astrbot/bin/astrbot ]; then
  echo "[start] 未找到 AstrBot，请先完成初始化安装"
  exit 2
fi

echo "[start] 启动 AstrBot，WebUI 端口 6185"
exec /opt/astrbot/bin/astrbot run
