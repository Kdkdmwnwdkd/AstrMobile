# 在 InstaBox 上部署（免费内测）

InstaBox 是 YC 的 InsForge 做的云电脑：**完整 Linux VM、有 root、能跑 Docker 和 compose**，
免费额度 5 台 ×（4 vCPU / 8G / 20G），机器在美国俄勒冈（AWS us-west-2）。

对我们这套东西来说，最要紧的两条已经确认：

| 特性 | 对我们的影响 |
|---|---|
| 闲置 30 分钟自动暂停，**收到 HTTPS 请求会自动唤醒** | 不用保活脚本，手机点开等几秒就能用 |
| 任何端口可以发布成公网 HTTPS URL | 手机 App 直接填这个 URL，不需要 Tailscale |
| 手动 `stop` / `pause` 不会自动唤醒 | 只有「闲置暂停」才会被请求唤醒，手动停的要 `instabox start` |
| 系统盘 16G + `/data` 20G | Docker 镜像默认落在 16G 系统盘，得先挪到 `/data` |

---

## 1. 建机器

网页控制台 https://instabox.sh → Create machine。或者本地有 Node 的话：

```bash
npx instabox@latest create --name bot --template universal
npx instabox@latest sh bot
```

建完在控制台点 **Terminal** 直接进 shell，下面的命令都在里面执行。

---

## 2. 先把 Docker 的数据目录挪到 /data

系统盘只有 16G，NapCat 镜像 2.5G + AstrBot 1.2G，再下点东西就红了。`/data` 是独立的 20G 卷，重启不丢。

```bash
sudo mkdir -p /data/docker
echo '{ "data-root": "/data/docker" }' | sudo tee /etc/docker/daemon.json
sudo systemctl restart docker
docker info | grep -i "docker root dir"     # 应该显示 /data/docker
```

没装 Docker 就：

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
```

---

## 3. 部署 AstrBot + NapCat

直接从这个仓库拿部署文件（机器在境外，访问 GitHub 很顺）：

```bash
git clone --depth 1 https://github.com/Kdkdmwnwdkd/AstrMobile /tmp/am
mkdir -p ~/deploy && cp -r /tmp/am/deploy/* ~/deploy/
cd ~/deploy/bot

cp .env.example .env
sed -i "s/^NAPCAT_UID=.*/NAPCAT_UID=$(id -u)/;s/^NAPCAT_GID=.*/NAPCAT_GID=$(id -g)/" .env

docker compose up -d
docker compose logs -f astrbot
```

日志里会有管理面板密码：

```
Username: astrbot
Password: xxxxxxxx
```

**立刻记下来**，然后 `Ctrl+C` 退出日志（容器不会被停）。

---

## 4. 发布端口，拿到 HTTPS 地址

这一步是让手机能访问的关键。两种方式，任选：

**A. 网页控制台（最省事）**

机器详情页 → **Ports** 标签 → 填 `6185` → Publish。会给你一个
`https://6185-xxxxxxxx.instabox.sh/` 的地址。

**B. 命令行**

```bash
# 在机器外、你自己的电脑上（需要 npx）
npx instabox@latest port publish bot 6185 --name astrbot

# 或者在机器内
instabox port setup bot
instabox-port publish 6185 --name astrbot
```

这个 URL **不是秘密**，谁拿到都能访问，所以：

- 手机 App 里填这个地址 + AstrBot 的密码
- 密码必须改强，别用默认的
- 只在需要时发布 6099，扫完码就 `port unpublish`

---

## 5. 扫码登录 QQ（重要风险提示）

在控制台 Ports 里临时发布 `6099`，浏览器打开那个 URL，用手机 QQ 扫码。

**扫完立刻回去 unpublish 6099**，因为 NapCat 的 WebUI 能直接操作你的 QQ 账号。

> ⚠️ **这台机器的出口 IP 是美国 AWS**。用境外 IP 登录 QQ，风控概率明显高于国内：
> 常见表现是要短信验证、扫码后提示环境异常、登录成功但几小时掉线。
>
> 建议：**用小号试**。如果一直风控，这套东西的 NapCat 部分就得挪回国内网络的机器
> （AstrBot 可以继续留在这里）。这个风险跟部署方式无关，换什么方案都一样。

---

## 6. 关于「闲置 30 分钟暂停」

**不用管它，也不用写保活脚本。**

- 闲置暂停后，手机 App 或浏览器访问那个 HTTPS URL，请求会等它恢复（几秒），然后正常返回。
- 所以你半夜不看它，它就暂停省资源；早上点开，它自己醒。
- 只有你**手动** stop / pause 的机器，才必须 `npx instabox@latest start bot` 才会起来。

如果你想让它一直不休眠（比如怕扫码超时、或者跑定时任务）：

```bash
npx instabox@latest create --name bot --template universal --auto-suspend 0
# 已经是创建好的机器：
npx instabox@latest auto-suspend bot 0
```

官方允许这么设，免费期也不计费。但社区反馈这类账号可能被优先清理，自己权衡。

---

## 7. 和手机 App 怎么配

App 的远程模式（正在开发）会要两个东西：

| 字段 | 填什么 |
|---|---|
| 服务器地址 | `https://6185-xxxxxxxx.instabox.sh`（Ports 里复制的那个） |
| 密码 | AstrBot 管理面板的密码 |

不需要端口、不需要证书、不需要 Tailscale —— InstaBox 给的本来就是 HTTPS。

如果 App 提示"连接超时"，大概率是机器在唤醒，等 10 秒重试一次。

---

## 8. 备份（别省）

免费内测随时可能结束，机器随时可能被回收。数据别只放这儿：

```bash
cd ~/deploy && ./backup.sh
# 然后把这个文件下载到本地（控制台 Files 标签可以下载）
ls -lh bot/backups/
```

每天自动备份：

```bash
(crontab -l 2>/dev/null; echo "0 4 * * * $HOME/deploy/backup.sh >> $HOME/deploy/backup.log 2>&1") | crontab -
```

---

## 9. 到底值不值得

**适合**：先把整套跑通、验证 AstrBot 的插件和人格、临时挂几天看看效果。零成本，1 秒开机，随时快照。

**不适合**：当长期生产环境。三条硬伤：

1. 免费没有期限，官方原话是 "Free until we figure it out"，定价页还是空的；
2. 出口 IP 在美国，QQ 风控不友好，这是最要命的一条；
3. 内测产品，随时可能改规则、清机器。

真跑起来了、觉得有用了，花一两百买台国内 2C4G 的轻量云，把 `~/deploy` 打包搬过去，地址一换就行。
