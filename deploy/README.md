# 服务器端部署（AstrBot + NapCat）

给「4 核 8G / 20G 盘」的纯 CPU 机器准备的，一套 compose 起完，不用手动装 Python、不用折腾 proot。

```
手机 App（远程模式）──┐
                      ├─ Tailscale 私有网络 ── 服务器 A：AstrBot :6185 + NapCat :6099
浏览器（管理后台）────┘                          │
                                                └─ http://内网IP:11434  ── 服务器 B：Ollama（可选）
```

服务器 A 只负责「收消息、跑插件、调模型」。模型可以是云端 API，也可以是服务器 B 上的本地模型。

---

## 0. 先决条件

```bash
# 确认是 x86_64 或 arm64
uname -m

# 装 docker（Ubuntu/Debian 一行）
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
```

云厂商控制台的**安全组只放行 22**。6185 / 6099 / 11434 一律不要对公网开放，原因见第 6 节。

---

## 1. 服务器 A：起 AstrBot + NapCat

```bash
# 把 deploy/bot 这个目录整个传上去，或者直接在服务器上建
mkdir -p ~/bot && cd ~/bot
# 复制本目录的 docker-compose.yml 和 .env.example 过来
cp .env.example .env

# UID/GID 必须是当前用户，否则 NapCat 写不了配置
sed -i "s/^NAPCAT_UID=.*/NAPCAT_UID=$(id -u)/" .env
sed -i "s/^NAPCAT_GID=.*/NAPCAT_GID=$(id -g)/" .env

docker compose up -d
docker compose logs -f astrbot
```

日志里会打印管理面板地址和**随机初始密码**，用户名 `astrbot`：

```
🌈 管理面板已启动，可访问
   ➜  Local: http://localhost:6185
   Username: astrbot
   Password: xxxxxxxx
```

记下密码，登录后立刻改掉。忘了也不要紧：

```bash
docker exec -it astrbot astrbot password
docker restart astrbot
```

国内拉不动镜像时，把 compose 里的镜像名换成 DaoCloud 镜像：

```
image: soulter/astrbot:latest        →  image: m.daocloud.io/docker.io/soulter/astrbot:latest
image: mlikiowa/napcat-docker:latest →  image: m.daocloud.io/docker.io/mlikiowa/napcat-docker:latest
```

---

## 2. 扫码登录 QQ

NapCat 的 WebUI 在 `6099`。默认只绑了 `127.0.0.1`，两种方式打开：

**A. SSH 隧道（最省事，临时用）**

在你自己的电脑上执行，然后浏览器开 http://127.0.0.1:6099：

```bash
ssh -L 6099:127.0.0.1:6099 用户名@服务器IP
```

**B. Tailscale（推荐，手机也要连的时候必须用这个）**

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

手机装 Tailscale，登录同一个账号。然后把 `.env` 里的 `BIND_ADDR` 改成 `0.0.0.0`，`docker compose up -d` 重建。之后手机直接访问 `http://100.x.x.x:6099`，公网仍然进不来。

WebUI 的 token 在 `napcat/config/webui.json` 里，也可以直接在登录页填。

> 登录二维码有效期约 2 分钟。扫完还要在手机上点一次确认。

---

## 3. 让 NapCat 连上 AstrBot

`MODE=astrbot` 已经让 NapCat 自动把反向 WS 指向 `ws://astrbot:6199/ws`，正常情况下不用手动配。

验证：AstrBot 后台 →「平台」里应该出现 `aiocqhttp` 且显示已连接。没有的话，在 NapCat WebUI →「网络配置」里新建一个 WebSocket 客户端，地址填 `ws://astrbot:6199/ws`。

---

## 4. 服务器 B：本地模型（可选）

没有 B 机就跳过，直接用云端 API。

```bash
mkdir -p ~/llm && cd ~/llm
# 复制 deploy/llm 下的文件
docker compose up -d
docker exec ollama ollama pull qwen2.5:3b-instruct-q4_K_M
```

`11434` 默认只绑 `127.0.0.1`。要让 A 机访问，把 `.env` 里 `BIND_ADDR=0.0.0.0`，并在 B 机防火墙上只放行 A 机的内网 IP：

```bash
sudo ufw allow from <A机内网IP> to any port 11434 proto tcp
```

在 AstrBot 后台添加服务提供商：

| 字段 | 值 |
|---|---|
| 类型 | OpenAI 兼容 / `openai_chat_completion` |
| API Base | `http://<B机内网IP>:11434/v1` |
| API Key | `ollama`（随便填，本地不校验） |
| 模型 | `qwen2.5:3b-instruct-q4_K_M` |

**速度参考（4 核共享 vCPU，纯 CPU）**：3B Q4 大约 8–15 tok/s，能正常聊天；7B Q4 只有 3–5 tok/s，一句完整回复要十几秒，群里体验很差。不建议上 7B。

---

## 5. 资源与磁盘

| 项目 | 占用 |
|---|---|
| NapCat 容器（Electron + QQ） | 内存 1.2–2.0G，镜像约 2.5G |
| AstrBot 容器 | 内存 0.4–0.8G，镜像约 1.2G |
| 数据目录 `./data` | 0.3–1G（随插件和日志增长） |
| Ollama + 3B Q4 模型 | 内存 3–4G，磁盘 2G |
| Ollama + 7B Q4 模型 | 内存 6–7G，磁盘 4.5G |

一台 4C8G 同时跑 A+B 会很紧张（8G 里模型吃掉 4G，QQ 吃掉 2G，剩 2G 给系统和 AstrBot），所以**建议分两台**。20G 盘装完这些还剩 10G 左右，别在上面放别的。

compose 里已经限制了日志大小（`max-size: 10m`），否则 AstrBot 的日志几天就能把盘写满。

---

## 6. 安全（别跳过）

- **6185 一旦暴露公网，等于把你所有 QQ 的机器人交给扫描器**。AstrBot 的密码是唯一防线。
- **6099 更危险**：NapCat WebUI 能直接操作 QQ 账号。
- 所以默认只绑 `127.0.0.1`，出门靠 Tailscale 或 SSH 隧道。
- 如果一定要公网访问，套 Nginx + HTTPS + 强密码 + fail2ban，并且限制来源 IP。
- 顺手关掉密码登录：`sudo sed -i 's/^#*PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config && sudo systemctl restart ssh`

---

## 7. 日常维护

```bash
docker compose ps                 # 状态
docker compose logs -f --tail=100 # 日志
docker compose restart astrbot    # 改完配置重启
docker compose pull && docker compose up -d   # 升级
```

备份就是打包 `./data` 目录（AstrBot 的全部配置、插件、对话记录都在里面）。别手敲 tar，用现成的：

```bash
./backup.sh                  # 备份到 ./bot/backups，自动只留 7 天
./backup.sh /mnt/nas/bot     # 备份到挂载的网盘/NAS

# 每天凌晨 4 点自动备份
crontab -e
0 4 * * * /home/你的用户名/deploy/backup.sh >> /home/你的用户名/deploy/backup.log 2>&1
```

恢复到另一台机器（或手机本地容器）：拷 `astrbot-xxx.tar.gz` 过去，解压覆盖 `data` 目录，重启容器即可。

---

## 8. 常见问题

**容器起来就退出**：`docker compose logs napcat` 看日志，多数是 `ntqq` 目录权限问题，`sudo chown -R $(id -u):$(id -g) ntqq`。

**AstrBot 显示平台未连接**：先确认 NapCat 已经扫码登录成功（WebUI 里能看到 QQ 在线），再看反向 WS 地址是不是 `ws://astrbot:6199/ws`。

**改了配置不生效**：AstrBot 的配置在启动时加载，改完 `docker compose restart astrbot`。

**内存不够被 OOM kill**：`dmesg | grep -i oom` 确认，然后把模型换小一号，或者把模型挪到 B 机。
