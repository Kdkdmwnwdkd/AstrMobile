#!/bin/bash
# AstrMobile 启动脚本（在 proot 容器内执行）
# NapCat 放后台，AstrBot 放前台：AstrBot 的日志直接进 App 的实时控制台。
cd /root || exit 1

export PATH="/opt/astrbot/bin:/usr/local/bin:$PATH"
export LANG=C.UTF-8

# NapCat 的 WebUI 口令固定成可预测值，App 才能登录它的本地接口。
# 不设的话 NapCat 会自动随机化，且只在「QQ 登录成功后」才告知。
export NAPCAT_WEBUI_SECRET_KEY="${NAPCAT_WEBUI_SECRET_KEY:-astrmobile}"

# 端口也固定，否则被占用时 NapCat 会静默换一个，App 仍按 6099 连就会连不上。
export NAPCAT_WEBUI_PREFERRED_PORT=6099

echo "[start] 容器已启动 $(date '+%F %T')"
echo "[start] 工作目录: $(pwd)"

# ------------------------------------------------------------------ 清理残留
# proot 被强杀时，容器内的 AstrBot / QQ 会变成孤儿继续跑：它们仍占着
# /root/astrbot.lock 和 6185 / 6099 端口，新实例一起来就报
# "Cannot acquire lock file. Please check if another instance is running"。
# proot 的 --kill-on-exit 只盯直接 tracee，孙进程不保险，所以启动前自己清一遍。
# 只按绝对路径匹配（/opt/astrbot、/opt/QQ、/root/napcat），碰不到宿主上的别的应用。
STALE_PIDS=""
for proc_dir in /proc/[0-9]*; do
  [ -d "$proc_dir" ] || continue
  stale_pid=${proc_dir#/proc/}
  [ "$stale_pid" = "$$" ] && continue
  stale_cmd=$(tr '\0' ' ' < "$proc_dir/cmdline" 2>/dev/null) || continue
  [ -z "$stale_cmd" ] && continue
  case "$stale_cmd" in
    *"/opt/astrbot/bin"*|*"/opt/QQ/"*|*"/root/napcat/"*|*"/root/launcher.sh"*)
      echo "[start] 发现上次遗留的进程 $stale_pid：$(echo "$stale_cmd" | cut -c1-70)"
      STALE_PIDS="$STALE_PIDS $stale_pid"
      ;;
  esac
done

if [ -n "$STALE_PIDS" ]; then
  kill -TERM $STALE_PIDS 2>/dev/null
  sleep 2
  for stale_pid in $STALE_PIDS; do
    kill -0 "$stale_pid" 2>/dev/null && kill -9 "$stale_pid" 2>/dev/null
  done
  rm -f /root/astrbot.lock
  echo "[start] 残留进程已清理，锁与端口释放"
fi

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
# 安装方式不同，配置可能在 /root/napcat/config，也可能在 /opt/QQ/.../napcat/config。
# 先按 webui.json / onebot11*.json 反查，再把结果写进标记文件供 App 直接读取
# （App 侧不要自己遍历容器目录，手机上会很慢）。
NAPCAT_CONFIG_DIR=""
if [ -f /root/launcher.sh ]; then
  # 按「最可能命中」的顺序找，避免一上来就全盘扫描（容器里 node_modules 很多）。
  HIT=$(find /root/napcat -maxdepth 4 -name "webui.json" 2>/dev/null | head -1)
  if [ -z "$HIT" ]; then
    HIT=$(find /opt/QQ -maxdepth 6 -name "webui.json" 2>/dev/null | head -1)
  fi
  if [ -z "$HIT" ]; then
    HIT=$(find /root -maxdepth 6 -name "webui.json" 2>/dev/null | head -1)
  fi
  if [ -z "$HIT" ]; then
    HIT=$(find /root /opt -maxdepth 6 -name "onebot11*.json" 2>/dev/null | head -1)
  fi
  if [ -n "$HIT" ]; then
    NAPCAT_CONFIG_DIR=$(dirname "$HIT")
    echo "$NAPCAT_CONFIG_DIR" > /root/.astrmobile-napcat-config
  fi
fi

# ------------------------------------------------------------------ 固定 NapCat 口令
# 直接改配置文件最可靠：NapCat 只在 token 为空或等于默认值 'napcat' 时才随机化，
# 写成固定值后它就照读。环境变量在某些版本上不被识别，所以两条路都留。
if [ -n "$NAPCAT_CONFIG_DIR" ] && [ -f "$NAPCAT_CONFIG_DIR/webui.json" ]; then
  NAPCAT_CONFIG_DIR="$NAPCAT_CONFIG_DIR" python3 - <<'PY' || echo "[start] 固定 NapCat 口令失败"
import json
import os

path = os.path.join(os.environ["NAPCAT_CONFIG_DIR"], "webui.json")
try:
    with open(path, encoding="utf-8") as fp:
        cfg = json.load(fp)
except Exception:
    raise SystemExit(0)

changed = False
if cfg.get("token") != "astrmobile":
    cfg["token"] = "astrmobile"
    changed = True
    print("[start] 已将 NapCat WebUI 口令固定为 astrmobile")

# 只监听本机。proot 与宿主共用网络栈，NapCat WebUI 能直接操作 QQ 账号，
# 绑 0.0.0.0 等于把账号交给同网段的任何人。App 自己走 127.0.0.1，不受影响。
if cfg.get("host") != "127.0.0.1":
    cfg["host"] = "127.0.0.1"
    changed = True
    print("[start] 已将 NapCat WebUI 绑定到 127.0.0.1")

if changed:
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(cfg, fp, ensure_ascii=False, indent=2)
PY
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
  python3 - <<'PY' || echo "[start] 写入 AstrBot 配置失败（不影响启动）"
import hashlib
import json
import os

path = "/root/data/cmd_config.json"
try:
    with open(path, encoding="utf-8") as fp:
        cfg = json.load(fp)
except Exception:
    raise SystemExit(0)

changed = False

# 首次运行且从未设过密码时固定成默认口令。
# 否则 AstrBot 会生成 24 位随机密码，用户不知道该填什么，只能看到登录页。
dash = cfg.setdefault("dashboard", {})
if not dash.get("password") and not dash.get("pbkdf2_password"):
    raw = "astrbot"
    salt = os.urandom(16)
    digest = hashlib.pbkdf2_hmac("sha256", raw.encode(), salt, 600000)
    dash["pbkdf2_password"] = "pbkdf2_sha256$600000${}${}".format(salt.hex(), digest.hex())
    dash["password"] = hashlib.md5(raw.encode()).hexdigest()
    dash["password_storage_upgraded"] = True
    changed = True
    print("[start] 已设置 AstrBot 后台默认口令（astrbot）")

# 后台只监听本机。默认 0.0.0.0 在 proot 下等于对同网段开放，
# 而它的口令是固定的 astrbot——不收紧就是白送一个后台入口。
if dash.get("host") != "127.0.0.1":
    dash["host"] = "127.0.0.1"
    changed = True
    print("[start] 已将 AstrBot 后台绑定到 127.0.0.1")

# 开启 aiocqhttp，等 NapCat 连进来。
# 只碰自己建的这一条（id=qq_astrmobile）：如果用户已经在网页里配过别的
# aiocqhttp（端口可能不同），覆盖它会让 NapCat 连不上，属于破坏性操作。
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
    (item for item in platforms
     if isinstance(item, dict) and item.get("id") == "qq_astrmobile"),
    None,
)
others = [
    item for item in platforms
    if isinstance(item, dict)
    and item.get("type") == "aiocqhttp"
    and item.get("id") != "qq_astrmobile"
]

if existing is None:
    if others:
        print("[start] 检测到你已自建 aiocqhttp 平台，跳过自动配置")
    else:
        platforms.append(desired)
        changed = True
else:
    if any(existing.get(k) != v for k, v in desired.items()):
        changed = True
    existing.update(desired)

if changed:
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(cfg, fp, ensure_ascii=False, indent=2)
    print("[start] 已为 AstrBot 启用 aiocqhttp 通信接口（0.0.0.0:6199）")
PY
fi

# ------------------------------------------------------------------ NapCat
# napcat.log 不会自己轮转：长期运行后动辄几百 MB，读它的时候手机会卡死。
# 超过 5MB 就只保留尾部 1MB（只用于排错，不需要完整历史）。
if [ -f /root/napcat.log ] && [ "$(stat -c%s /root/napcat.log 2>/dev/null || echo 0)" -gt 5242880 ]; then
  tail -c 1048576 /root/napcat.log > /root/napcat.log.tmp 2>/dev/null \
    && mv /root/napcat.log.tmp /root/napcat.log
  echo "[start] napcat.log 已轮转（保留尾部 1MB）"
fi

if [ -f /root/launcher.sh ]; then
  echo "[start] 启动 NapCat"
  nohup bash /root/launcher.sh > /root/napcat.log 2>&1 &
  NAPCAT_PID=$!
  echo "$NAPCAT_PID" > /root/napcat.pid

  # 立刻退出的情况以前完全看不出来，只在扫码页表现为「连不上」。
  sleep 2
  if kill -0 "$NAPCAT_PID" 2>/dev/null; then
    echo "[start] NapCat 进程存活（pid=$NAPCAT_PID）"
  else
    echo "[start] NapCat 启动后立即退出，日志末尾："
    tail -8 /root/napcat.log 2>/dev/null
  fi
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
