#!/bin/bash
# AstrMobile 启动脚本（在 proot 容器内执行）
# NapCat 放后台，AstrBot 放前台：AstrBot 的日志直接进 App 的实时控制台。
cd /root || exit 1

export PATH="/opt/astrbot/bin:/usr/local/bin:$PATH"
export LANG=C.UTF-8

# NapCat 的 WebUI 口令固定成可预测值，App 才能登录它的本地接口。
# 不设的话 NapCat 会自动随机化，且只在「QQ 登录成功后」才告知。
export NAPCAT_WEBUI_SECRET_KEY="${NAPCAT_WEBUI_SECRET_KEY:-astrmobile}"

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

# ------------------------------------------------------------------ 定位 NapCat 配置目录
# 官方安装器与自建脚本的路径不同，按 webui.json 反查最准。
NAPCAT_CONFIG_DIR=""
if [ -f /root/launcher.sh ]; then
  WEBUI_PATH=$(find /root -maxdepth 7 -name webui.json 2>/dev/null | head -1)
  if [ -n "$WEBUI_PATH" ]; then
    NAPCAT_CONFIG_DIR=$(dirname "$WEBUI_PATH")
  else
    NAPCAT_CONFIG_DIR=$(find /root -maxdepth 7 -type d -name config -path "*napcat*" 2>/dev/null | head -1)
  fi
  if [ -z "$NAPCAT_CONFIG_DIR" ]; then
    for candidate in \
      /root/napcat/config \
      /root/Napcat/opt/QQ/resources/app/app_launcher/napcat/config
    do
      if [ -d "$candidate" ]; then NAPCAT_CONFIG_DIR="$candidate"; break; fi
    done
  fi
fi

# ------------------------------------------------------------------ NapCat 侧：反向 WS
# 目标是「扫完码就能用」。NapCat 必须主动连到 AstrBot 的 6199，
# 否则用户还得自己在网页里添加 WebSocket 客户端。
if [ -n "$NAPCAT_CONFIG_DIR" ] && [ -d "$NAPCAT_CONFIG_DIR" ]; then
  echo "[start] NapCat 配置目录: $NAPCAT_CONFIG_DIR"
  NAPCAT_CONFIG_DIR="$NAPCAT_CONFIG_DIR" python3 - <<'PY' || echo "[start] 写入 NapCat 连接配置失败（不影响启动）"
import glob
import json
import os

cfg_dir = os.environ["NAPCAT_CONFIG_DIR"]
target = "ws://127.0.0.1:6199/ws"

# 登录过 QQ 后 NapCat 会按账号拆分成 onebot11_<uin>.json，两种情况都要覆盖。
files = glob.glob(os.path.join(cfg_dir, "onebot11*.json"))
if not files:
    files = [os.path.join(cfg_dir, "onebot11.json")]

for path in files:
    cfg = {}
    if os.path.exists(path):
        try:
            with open(path, encoding="utf-8") as fp:
                cfg = json.load(fp)
        except Exception:
            cfg = {}

    network = cfg.setdefault("network", {})
    for key in ("httpServers", "httpClients", "websocketServers"):
        network.setdefault(key, [])
    clients = network.setdefault("websocketClients", [])

    if any(isinstance(item, dict) and item.get("url") == target for item in clients):
        continue

    clients.append({
        "name": "AstrMobile",
        "enable": True,
        "url": target,
        "messagePostFormat": "array",
        "reportSelfMessage": False,
        "reconnectInterval": 5000,
        "token": "",
        "debug": False,
        "heartInterval": 30000,
    })
    cfg.setdefault("musicSignUrl", "")
    cfg.setdefault("enableLocalFile2Url", False)
    cfg.setdefault("parseMultMsg", False)

    with open(path, "w", encoding="utf-8") as fp:
        json.dump(cfg, fp, ensure_ascii=False, indent=2)
    print("[start] 已写入 NapCat 反向 WS: " + path)
PY
else
  echo "[start] 未定位到 NapCat 配置目录，跳过连接配置"
fi

# ------------------------------------------------------------------ AstrBot 侧：aiocqhttp
# 对应地，AstrBot 要开一个 aiocqhttp 平台等待 NapCat 连进来。
if [ -f /root/data/cmd_config.json ]; then
  python3 - <<'PY' || echo "[start] 写入 AstrBot 平台配置失败（不影响启动）"
import json

path = "/root/data/cmd_config.json"
try:
    with open(path, encoding="utf-8") as fp:
        cfg = json.load(fp)
except Exception:
    raise SystemExit(0)

desired = {
    "id": "qq_astrmobile",
    "type": "aiocqhttp",
    "enable": True,
    "ws_reverse_host": "0.0.0.0",
    "ws_reverse_port": 6199,
    "ws_reverse_token": "",
}

platforms = cfg.setdefault("platform", [])
existing = next(
    (item for item in platforms if isinstance(item, dict) and item.get("type") == "aiocqhttp"),
    None,
)

if existing is None:
    platforms.append(desired)
    changed = True
else:
    changed = any(existing.get(k) != v for k, v in desired.items())
    existing.update(desired)

if changed:
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(cfg, fp, ensure_ascii=False, indent=2)
    print("[start] 已为 AstrBot 启用 aiocqhttp 通信接口（0.0.0.0:6199）")
PY
fi

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
