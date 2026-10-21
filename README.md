# AstrMobile

> 手机上的 AstrBot + QQ 机器人。装一个 App，把一台 Android 手机变成 24 小时在线的 AI 机器人主机。

## 下载

| 来源 | 链接 |
|---|---|
| 最新构建（每次推送自动更新） | [Releases · latest](https://github.com/Kdkdmwnwdkd/AstrMobile/releases/tag/latest) |
| 国内加速直链 | `https://gh-proxy.com/https://github.com/Kdkdmwnwdkd/AstrMobile/releases/download/latest/app-debug.apk` |

- 架构：`arm64-v8a`（仅此一种）
- 最低系统：Android 8.0（API 26）
- 签名：仓库内固定 debug keystore，**可覆盖升级，不会丢数据**

## 自动构建

推送到 `main` 触发 GitHub Actions：

```
checkout → JDK 17 → 复用 runner 预装 Android SDK → 下载并 SHA-256 校验 rootfs
        → assembleDebug → apksigner 验签 → 上传 artifact → 更新 latest Release
```

`ubuntu-latest` 自带 Android SDK，因此不依赖 `setup-android`（它在许可协议交互上会失败）。
rootfs（62MB）不纳入版本库，构建时从 ElymBot 仓库拉取并按固定哈希校验。

## 这是什么

AstrMobile 是一个 **Android 端的 AstrBot 启动器**：

- 内置完整 Ubuntu 24.04 arm64 根文件系统，通过 **proot 免 ROOT** 运行
- 容器内安装并运行 **完整版 AstrBot**（Python 后端 + WebUI，保留插件/知识库/MCP 全部能力）
- 同时运行 **NapCatQQ**（基于官方 QQ Linux 客户端的 OneBot 协议端），扫码即可接管 QQ 账号
- 原生 Compose 界面负责安装向导、启停控制、实时日志、网页后台入口与后台保活

**不是**一个精简版聊天壳：容器里跑的是与电脑上完全相同的 AstrBot。

## 功能

App 内可以直接完成的事（不用进网页后台）：

| 模块 | 能力 |
|---|---|
| **自检** | 逐项检查环境、容器与端口、对话模型、QQ 登录态、插件、消息通道，每项不合格都给出具体动作 |
| **对话模型** | DeepSeek / OpenAI / 智谱 / 通义 / Kimi 预设，贴 Key 即可；也支持自定义接口 |
| **QQ 登录** | 二维码直接画在 App 里（调 NapCat 本地接口），不用进它的网页 |
| **人格设定** | 改 system prompt，列出已有的人格 |
| **插件市场** | 1329 个插件可搜可装（数据来自 AstrBot 官方公开索引），支持卸载 |
| **插件参数** | 表单由插件的 `_conf_schema.json` 动态生成：开关、数字、文本、列表、选项 |
| **知识库** | 配向量模型 → 建库 → 上传文档（txt/md/pdf/epub/docx）→ 自动切块索引 |
| **对话记录** | 最近会话倒序，点开看完整问答（左右气泡） |
| **备份与恢复** | 打包 `data` 目录导出到网盘；恢复失败自动回滚 |
| **日志** | 容器实时日志、复制、导出文件 |

自动化的部分（不需要手动配置）：

- NapCat 的反向 WebSocket 指向 AstrBot（`ws://127.0.0.1:6199/ws`）
- AstrBot 的 `aiocqhttp` 平台自动开启并监听 6199
- NapCat 的 WebUI 口令与端口固定（否则会随机化，App 连不上）
- AstrBot 后台口令固定为 `astrbot`（4.29 会自己生成随机密码，App 启动时把它恢复成默认值；你自己改过的不受影响），并只监听 `127.0.0.1`
- NapCat 的 WebUI 同样只监听 `127.0.0.1`——它能直接操作 QQ 账号，不能对局域网开放
- NapCat 进程被系统回收、6099 不再监听时，App 会单独把 QQ 协议端拉起来（不影响 AstrBot）

## 与两个参考项目的关系

本项目的技术选型直接来自对 [DSHA](https://github.com/DSH-APP/DSHA) 与 [ElymBot](https://github.com/undertaker33/ElymBot) 的对比分析：

| 借鉴来源 | 采用的做法 |
|---|---|
| **DSHA**（容器搬运路线） | 「容器内保持上游完整能力」的思路；进程状态机、健康检查、停止哨兵、日志分离等工程化做法 |
| **ElymBot**（原生外壳路线） | proot 启动参数（`--link2symlink`、fake proc binds、`/dev/shm` 映射）；Ubuntu rootfs 与 `libproot.so` 等原生资产；`useLegacyPackaging` 打包技巧 |
| 本项目取舍 | 容器里跑**完整 AstrBot**（而非重写管理面），界面只做启动器与控制台——功能上限最高、维护面最小 |

## 架构

```
┌──────────────────────────── Android App (Kotlin + Compose) ────────────────────────────┐
│  MainActivity / Compose UI                                                             │
│    ├── Onboarding     安装向导（进度 + 实时日志）
│    ├── Dashboard      状态卡、启停、功能入口
│    ├── Diagnostics    六项自检（环境/容器/模型/QQ/插件/通道）
│    ├── QrCode         原生 QQ 扫码（调 NapCat 本地接口）
│    ├── Models         对话模型配置
│    ├── Persona        人格设定（system prompt）
│    ├── Plugins        插件市场 + 参数表单
│    ├── Knowledge      知识库（向量模型 + 上传 + 索引）
│    ├── Conversations  对话记录
│    ├── Backup         备份与恢复
│    ├── Console        容器实时日志
│    ├── WebUi          WebView（兜底入口）
│    └── Settings       镜像源、开机自启、清除环境
│                                                                                        │
│  ContainerService（前台服务 + WakeLock + 常驻通知）  ← 保证息屏不掉线                    │
│                                                                                        │
│  core:container
│    ├── ContainerPaths      目录布局
│    ├── BinaryInstaller     nativeLibraryDir → bin 软链（绕过 W^X）
│    ├── RootfsInstaller     解压 tar.xz → 定位真正的根目录（剥离顶层目录）
│    ├── ProotCommand        proot 参数构造
│    ├── ContainerController 状态机 / 启停 / 日志泵 / 端口探活 / 崩溃自愈
│    ├── Bootstrapper        首次安装编排
│    ├── ScriptInjector      每次启动覆盖容器内脚本（避免升级后仍跑旧脚本）
│    ├── RootfsConfigurator  DNS / apt 源 / hosts
│    ├── AstrBotApi          AstrBot REST（人格/插件参数/对话，HttpOnly Cookie）
│    ├── ModelConfigRepository / KnowledgeBaseRepository
│    ├── PluginRepository    市场索引 + zip 下载解压
│    ├── BackupRepository    打包与恢复（失败回滚）
│    ├── Diagnostics         自检
│    └── NapCatApi / NapCatConfigReader / AstrBotConfigReader
└────────────────────────────────────────────────────────────────────────────────────────┘
                                    │ proot
┌───────────────────────────────────▼────────────────────────────────────────────────────┐
│  Ubuntu 24.04 (arm64) 容器                                                             │
│    ├── Ubuntu rootfs（内置 62MB tar.xz，解压后约 300MB）                                │
│    ├── AstrBot（/opt/astrbot 虚拟环境，端口 6185）                                      │
│    └── NapCatQQ（Xvfb + QQ Linux 客户端，端口 6099 / OneBot 6199）                      │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

## 构建

要求：JDK 17、Android SDK 34、build-tools 34.0.0。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 中填写 `sdk.dir`。

## 首次使用

1. 安装 APK（arm64 设备，Android 8.0+），允许通知权限
2. 打开 App → 「开始安装」，保持屏幕常亮，等待 5–20 分钟（取决于网络）
3. 安装完成后进入控制台 →「启动机器人」，等容器状态变成运行中
4. 点 **「自检」**，先确认链路。全绿再往下走，有红项按提示处理
5. **「QQ 扫码登录」**→ 二维码直接显示在 App 里 → 手机 QQ 扫码
6. **「对话模型」**→ 选厂商 → 贴 API Key → 保存 → 重启
7. **「人格设定」**→ 写一句人设 → 保存 → 重启
8. **「插件」**→ 搜「表情」「记忆」→ 装 1–2 个 → 重启
9. 群里 @机器人 说话
10. 回 App 看 **「对话记录」**，确认它真的回了

建议在装完第 5 步后做一次 **「备份与恢复」→ 立即备份**，之后折腾坏了能回去。

> 通信接口、端口、口令都已自动配置，不需要再进网页后台手动添加 `aiocqhttp`。

## 已知限制

- **仅 arm64-v8a**，Android 8.0+；x86 设备与模拟器不支持
- **后台只监听本机**：6185 / 6099 都绑在 `127.0.0.1`，同一 WiFi 下的其他设备访问不到。需要从电脑打开后台时用 `adb forward tcp:6185 tcp:6185`，不要为了图方便把端口放开到局域网
- **首次安装依赖网络**：apt（Ubuntu 包）、PyPI、NapCat/QQ 安装包，任一环节失败都需要重试
- **内存占用**：NapCat 基于 Electron，常驻 1–2GB，低配机型容易被系统回收
- **QQ 风控**：非官方协议，存在封号风险，建议使用小号
- **应用未签名发布**：当前为 debug 包，仅供自用测试
- 微信不支持：AstrBot 官方已于 2026 年 1 月移除个人微信适配器（腾讯 DMCA），本项目不做该方向

## 目录

```
app/                    Android 壳层：UI、前台服务、开机自启、资产
core/common/            日志、环形缓冲
core/container/         proot 容器引擎（本项目核心）
core/data/              DataStore 配置
core/ui/                Compose 主题与配色
```

## 免责声明

本项目仅做本地环境集成，不提供 QQ/微信账号服务，不修改或绕过任何平台鉴权。
使用非官方协议接入 QQ 可能违反平台服务条款并导致账号受限，风险由使用者自行承担。
AstrBot 采用 AGPL-3.0；NapCatQQ 与 QQ 客户端版权归各自作者所有。
