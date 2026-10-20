#!/bin/bash
# AstrMobile 启动脚本（在 proot 容器内执行）
# NapCat 放后台，AstrBot 放前台：AstrBot 的日志直接进 App 的实时控制台。
cd /root || exit 1

export PATH="/opt/astrbot/bin:/usr/local/bin:$PATH"
export LANG=C.UTF-8

echo "[start] 容器已启动 $(date '+%F %T')"
echo "[start] 工作目录: $(pwd)"

# ------------------------------------------------------------------ 自愈初始化
# astrbot run 只校验 /root/.astrbot 是否存在（check_astrbot_root），
# 没有就报 "not a valid AstrBot root directory" 并退出。
# 用户可能跳过了安装步骤直接启动，这里做一次幂等补齐，避免反复重启。
if [ ! -f /root/.astrbot ]; then
  echo "[start] 未检测到 .astrbot 标记，自动初始化数据目录"
  mkdir -p /root/data/config /root/data/plugins /root/data/temp
  export ASTRBOT_DASHBOARD_INITIAL_PASSWORD="${ASTRBOT_DASHBOARD_INITIAL_PASSWORD:-astrbot}"
  if [ -x /opt/astrbot/bin/astrbot ]; then
    timeout 120 /opt/astrbot/bin/astrbot init --yes </dev/null >>/root/astrbot-init.log 2>&1 \
      || echo "[start] astrbot init 未成功，继续用兜底标记启动"
  fi
  # 兜底标记：只要它存在，run 就会继续并自行创建默认配置
  touch /root/.astrbot
  echo "[start] 初始化标记已就绪"
fi

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
