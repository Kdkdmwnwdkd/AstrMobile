#!/bin/bash
# AstrMobile 首次安装脚本（在 proot 容器内执行）
# 目标：把一台普通 Ubuntu rootfs 变成可运行 AstrBot + NapCat 的环境。
set -o pipefail

export DEBIAN_FRONTEND=noninteractive
export PIP_INDEX_URL="${PIP_INDEX_URL:-https://pypi.tuna.tsinghua.edu.cn/simple}"
export UV_DEFAULT_INDEX="$PIP_INDEX_URL"
export UV_HTTP_TIMEOUT=120

log() { echo "[bootstrap] $*"; }
fail_hint() { echo "[bootstrap] 提示: $*"; }

# ---------------------------------------------------------------- 0. 基础准备
log "0/6 准备基础环境"
mkdir -p /root /opt /usr/local/bin
if ! command -v sudo >/dev/null 2>&1; then
  cat > /usr/local/bin/sudo <<'EOS'
#!/bin/sh
exec "$@"
EOS
  chmod +x /usr/local/bin/sudo
  echo "已安装 sudo 兼容层"
fi

# ---------------------------------------------------------------- 1. apt 更新
log "1/6 更新软件源"
# Ubuntu 24.04 默认使用 deb822 格式的 ubuntu.sources，可能与宿主写入的 sources.list 冲突
rm -f /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true
if ! apt-get update -y; then
  fail_hint "apt update 失败，尝试修复 dpkg 状态"
  dpkg --configure -a || true
  apt-get install -f -y || true
  apt-get update -y || { echo "[bootstrap] apt update 仍然失败，请检查网络或镜像源"; exit 11; }
fi

# ---------------------------------------------------------------- 2. 依赖
log "2/6 安装系统依赖"

# 先确认 DNS 可用，避免把 DNS 问题误判成"源不可用"
if ! getent hosts mirrors.tuna.tsinghua.edu.cn >/dev/null 2>&1 \
   && ! getent hosts archive.ubuntu.com >/dev/null 2>&1; then
  echo "[bootstrap] 警告：域名解析失败，请检查 /etc/resolv.conf 或网络"
fi

# 核心依赖：这些失败就没法继续
# g++ / python3-dev / libffi-dev 等是编译 Python 扩展必需的，
# AstrBot 的部分依赖没有 arm64 预编译 wheel，缺了会在 pip 阶段直接失败
CORE_PACKAGES="ca-certificates curl wget git unzip xz-utils procps tzdata \
python3 python3-venv python3-pip python3-dev \
build-essential g++ pkg-config libffi-dev libssl-dev zlib1g-dev \
xvfb xauth"

if ! apt-get install -y --no-install-recommends $CORE_PACKAGES; then
  fail_hint "核心依赖安装失败，重试一次（可能是镜像瞬时错误）"
  apt-get update -y || true
  apt-get install -y --no-install-recommends $CORE_PACKAGES || { echo "[bootstrap] 核心依赖安装失败"; exit 12; }
fi

# GUI 依赖：只影响 QQ（Electron），失败不应阻断 AstrBot
# Ubuntu 24.04 对部分库做了 t64 改名，两套包名都试一次
GUI_PACKAGES_T64="libgl1 libglib2.0-0t64 libnss3 libnspr4 libasound2t64 libgbm1 libxss1 libxtst6 fonts-noto-cjk"
GUI_PACKAGES_LEGACY="libgl1 libglib2.0-0 libnss3 libnspr4 libasound2 libgbm1 libxss1 libxtst6 fonts-noto-cjk"

if ! apt-get install -y --no-install-recommends $GUI_PACKAGES_T64; then
  echo "[bootstrap] t64 包名安装失败，回退旧包名"
  apt-get install -y --no-install-recommends $GUI_PACKAGES_LEGACY \
    || echo "[bootstrap] GUI 依赖安装失败，QQ 可能无法启动（AstrBot 不受影响）"
fi

# ---------------------------------------------------------------- 3. uv
log "3/6 安装 uv"
export PATH="/root/.local/bin:/root/.cargo/bin:$PATH"
if ! command -v uv >/dev/null 2>&1; then
  pip3 install --break-system-packages -i "$PIP_INDEX_URL" uv \
    || curl -LsSf https://astral.sh/uv/install.sh | sh \
    || { echo "[bootstrap] uv 安装失败"; exit 13; }
fi
export PATH="/root/.local/bin:/root/.cargo/bin:$PATH"
command -v uv >/dev/null 2>&1 || { echo "[bootstrap] 找不到 uv 可执行文件"; exit 14; }

# ---------------------------------------------------------------- 4. AstrBot
log "4/6 安装 AstrBot"

# proot 的 --link2symlink 会把 hardlink 改写成 .l2s.* 符号链接，
# uv 默认用 hardlink 装包，随后再操作这些链接就会 EPERM。
# 强制 copy 模式可以绕开；但主路径仍改用系统 venv + pip（纯复制，最稳）。
export UV_LINK_MODE=copy

if [ ! -x /opt/astrbot/bin/astrbot ]; then
  rm -rf /opt/astrbot /root/.cache/uv

  if ! python3 -m venv /opt/astrbot; then
    echo "[bootstrap] 创建虚拟环境失败（python3-venv 是否安装成功？）"
    exit 15
  fi

  /opt/astrbot/bin/python -m pip install --no-cache-dir --upgrade pip -i "$PIP_INDEX_URL" || true

  if ! /opt/astrbot/bin/python -m pip install --no-cache-dir astrbot -i "$PIP_INDEX_URL"; then
    echo "[bootstrap] pip 安装失败，改用 uv（copy 模式）重试"
    if command -v uv >/dev/null 2>&1; then
      if ! uv pip install --link-mode=copy --python /opt/astrbot/bin/python astrbot; then
        echo "[bootstrap] AstrBot 安装失败。请把上面 30 行日志发回，常见原因是镜像不可达或编译失败"
        exit 16
      fi
    else
      echo "[bootstrap] AstrBot 安装失败，且没有可用的 uv"
      exit 16
    fi
  fi
fi
ln -sf /opt/astrbot/bin/astrbot /usr/local/bin/astrbot

# ---------------------------------------------------------------- 5. NapCat
log "5/6 安装 NapCat（QQ 协议端）"
if [ ! -f /root/launcher.sh ] && [ ! -d /root/napcat ]; then
  NAPCAT_OK=0
  PROXY="${GITHUB_PROXY:-}"
  for url in \
    "${PROXY}https://raw.githubusercontent.com/NapNeko/napcat-linux-installer/main/install.sh" \
    "https://raw.githubusercontent.com/NapNeko/napcat-linux-installer/main/install.sh" \
    "https://ghfast.top/https://raw.githubusercontent.com/NapNeko/napcat-linux-installer/main/install.sh"
  do
    echo "尝试下载 NapCat 安装器: $url"
    if curl -fL --retry 2 --connect-timeout 20 --max-time 300 -o /root/napcat-linux.sh "$url"; then
      if bash /root/napcat-linux.sh --github-proxy 0; then
        NAPCAT_OK=1
        break
      fi
    fi
  done

  if [ "$NAPCAT_OK" != "1" ]; then
    fail_hint "NapCat 安装失败。AstrBot 本体已可用，可先进入控制台，稍后在终端重试安装 NapCat"
    echo "[bootstrap] napcat-install-failed" > /root/.napcat-install-failed
  fi
else
  echo "检测到已有 NapCat 安装，跳过"
fi

# ---------------------------------------------------------------- 6. 初始化
log "6/6 初始化 AstrBot 数据目录"
cd /root || exit 17

# astrbot init 在无 TTY 的环境里会等待输入而永久挂起（实测卡在 "Initializing AstrBot..."）。
# 用 timeout + /dev/null 兜住：超时就放弃，astrbot run 首次启动会自动建数据目录。
if [ ! -d /root/data ]; then
  echo "[bootstrap] 预初始化（最多 90 秒，超时不影响启动）"
  if timeout 90 astrbot init </dev/null >/root/astrbot-init.log 2>&1; then
    echo "[bootstrap] 预初始化完成"
  else
    echo "[bootstrap] 预初始化超时或跳过，首次启动会自动初始化"
    tail -5 /root/astrbot-init.log 2>/dev/null || true
  fi
fi

# 生成一份给用户看的下一步说明
cat > /root/README-FIRST-RUN.txt <<'EOR'
AstrMobile 已就绪。

下一步（在 App 的「控制台」里点启动，然后打开 AstrBot 网页后台）：
1. 打开 http://127.0.0.1:6185 ，默认账号/密码均为 astrbot（登录后请立刻修改）。
2. 进入「模型提供商」，添加你的大模型 API Key（OpenAI / DeepSeek / 智谱 等）。
3. 扫码登录 QQ：打开 http://127.0.0.1:6099 （NapCat 控制台），用手机 QQ 扫码。
4. 在 AstrBot「机器人 -> 通信接口」新增 QQ 个人号(aiocqhttp)，地址填 ws://127.0.0.1:6199/ws 。
5. 完成后在群里 @机器人 即可对话。

注意：QQ 机器人有封号风险，建议使用小号；NapCat 与 QQ 客户端会占用较多内存。
EOR

log "安装完成"
