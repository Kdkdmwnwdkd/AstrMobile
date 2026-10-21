<div align="center">

# AstrMobile

**把一台 Android 手机变成常驻的 AstrBot 主机**

内置 Ubuntu 24.04 · proot 免 ROOT · 完整 AstrBot + NapCatQQ

[![Build](https://github.com/Kdkdmwnwdkd/AstrMobile/actions/workflows/build.yml/badge.svg)](https://github.com/Kdkdmwnwdkd/AstrMobile/actions/workflows/build.yml)
[![Version](https://img.shields.io/github/v/release/Kdkdmwnwdkd/AstrMobile?include_prereleases&label=version)](https://github.com/Kdkdmwnwdkd/AstrMobile/releases)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![Arch](https://img.shields.io/badge/arch-arm64--v8a-informational)
![License](https://img.shields.io/badge/license-AGPL--3.0-orange)

</div>

---

## 目录

- [这是什么](#这是什么)
- [能力](#能力)
- [下载与安装](#下载与安装)
- [首次使用](#首次使用)
- [工作原理](#工作原理)
- [安全与隐私](#安全与隐私)
- [已知限制](#已知限制)
- [常见问题](#常见问题)
- [从源码构建](#从源码构建)
- [目录结构](#目录结构)
- [致谢](#致谢)
- [许可](#许可)

## 这是什么

AstrMobile 是一个 Android 应用，把 **完整的 AstrBot** 装进手机里长期运行。

它在应用私有目录摆一套 Ubuntu 24.04 arm64 根文件系统，用 **proot** 以普通应用身份进入这个环境，在其中安装并运行 AstrBot（Python 后端 + WebUI）与 NapCatQQ（QQ 协议端）。不需要 ROOT，不需要解锁，不需要另一台服务器。

容器里跑的是与电脑上**完全相同**的 AstrBot：插件、知识库、MCP、人格、对话记录都在，没有为移动端做功能裁剪。界面只承担启动器与控制台的职责——装、启停、看日志、改配置，剩下的事交给 AstrBot 自己。

## 能力

**容器与控制**

- 一键安装：解压 rootfs、换 apt 源、装 AstrBot 与 NapCat，全程有实时日志
- 状态机式启停：启动、停止、重启，异常退出按 5s / 20s / 60s 退避重试
- 前台服务 + WakeLock，熄屏后保持运行
- 孤儿进程清理：按容器内绝对路径收尾，不会误杀宿主上你自己的 QQ
- 开机自启（可关）
- 六项自检：环境、容器与端口、对话模型、QQ 登录态、插件、消息通道，每项给出具体动作

**模型**

- 可保存多个模型，随时切换「使用中」的那一个，切换后立即生效
- 内置 DeepSeek、Agnes、OpenAI、智谱、通义、Kimi 预设，贴 Key 即可；也支持任意 OpenAI 兼容端点
- 「测试连接」会真的发一次请求，直接告诉你通没通、报什么错
- 走 AstrBot 的 provider 接口写入，由它自己落盘并热加载，不需要重启

**QQ**

- 二维码直接画在 App 里，不用进 NapCat 网页
- aiocqhttp 平台、反向 WebSocket 地址、端口全部自动配置
- NapCat 单独看护：进程被系统回收时只重启 QQ 协议端，不影响 AstrBot

**内容与运维**

- 人格设定：编辑真正生效的那一条，保存后回读校验
- 插件市场：搜索、查看完整介绍、一键安装、卸载；显示运行时加载状态，装失败的直接给原因
- 插件参数：表单由插件的 `_conf_schema.json` 动态生成
- 知识库：配向量模型 → 建库 → 上传文档（txt / md / pdf / epub / docx）→ 自动切块索引
- 对话记录：最近会话倒序，点开看完整问答，可继续加载更早的会话
- 备份与恢复：打包 `data` 目录导出；恢复前先停容器，失败自动回滚
- 看屏幕：截一张当前屏幕，交给多模态模型理解，或只用本地 OCR 提取文字

## 下载与安装

**最新构建**（每次推送到 `main` 自动更新）

https://github.com/Kdkdmwnwdkd/AstrMobile/releases/tag/latest

**国内加速直链**

```
https://gh-proxy.com/https://github.com/Kdkdmwnwdkd/AstrMobile/releases/download/latest/app-debug.apk
```

备用线路：`https://ghfast.top/https://github.com/Kdkdmwnwdkd/AstrMobile/releases/download/latest/app-debug.apk`

要求：

- **arm64-v8a**，仅此一种架构，x86 设备与模拟器不支持
- Android 8.0（API 26）及以上
- 至少 6 GB 可用空间（安装后约 3.5 GB）
- 首次安装需要联网

安装包为 debug 签名，仓库内使用固定 keystore，**可以覆盖升级，不会丢数据**。

## 首次使用

1. 安装 APK，允许通知权限
2. 打开 App →「开始安装」，保持屏幕常亮，等待 10–40 分钟（取决于网络）
3. 安装完成后进入控制台 →「启动机器人」，等状态变成「运行中」
4. 打开「自检」，全绿再往下走；有红项会写明该做什么
5. 「扫码登录」→ 二维码显示在 App 内 → 手机 QQ 扫码
6. 「模型」→ 选厂商 → 贴 API Key → 保存 → 点「测试连接」确认
7. 「人格」→ 写人设 → 保存（返回时自动重启容器）
8. 「插件」→ 装一两个 → 返回（自动重启）
9. 在群里 @ 机器人 说话

装完第 5 步建议先做一次「备份」→ 立即备份，之后折腾坏了能回去。

> 通信接口、端口、口令都已自动配置，**不需要**再进网页后台手动添加 aiocqhttp。

## 工作原理

```
┌─────────────────────── Android App（Kotlin + Compose）───────────────────────┐
│  底部导航：控制台 / 模型 / 插件 / 设置                                        │
│  二级页面：自检 · 扫码 · 人格 · 知识库 · 对话记录 · 备份 · 看屏 · 日志 · 后台 │
│                                                                             │
│  ContainerService：前台服务 + WakeLock + 常驻通知                            │
│                                                                             │
│  core:container                                                             │
│    ContainerController   状态机 / 启停 / 日志泵 / 端口探活 / 崩溃自愈        │
│    BinaryInstaller       nativeLibraryDir → bin 软链（绕过 W^X）             │
│    RootfsInstaller       解压 tar.xz，定位真正的根目录                       │
│    ProotCommand          proot 参数构造                                     │
│    ScriptInjector        每次启动覆盖容器内脚本                              │
│    RootfsConfigurator    DNS / apt 源 / hosts                               │
│    AstrBotApi            AstrBot REST（人格 / 模型 / 插件 / 对话）           │
│    VisionApi             直连多模态接口，用于看屏幕                          │
│    NapCatApi             扫码、登录状态                                      │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │ proot（免 ROOT）
┌──────────────────────────────────▼──────────────────────────────────────────┐
│  Ubuntu 24.04 (arm64)                                                       │
│    ├── AstrBot   /opt/astrbot 虚拟环境，监听 127.0.0.1:6185                  │
│    └── NapCatQQ  Xvfb + QQ Linux 客户端，6099（WebUI）/ 6199（OneBot）       │
└─────────────────────────────────────────────────────────────────────────────┘
```

启动链路：`BinaryInstaller` 重建软链 → 删停止哨兵 → `RootfsConfigurator` 写 DNS 与 apt 源 → `ScriptInjector` 覆盖容器内脚本 → 拼装 proot 命令 → 拉起进程 → 日志泵 + 每 2 秒探测端口。

几处刻意的设计：

- **软链每次都校验指向**，而不是只判断文件存在。APK 覆盖升级后 `nativeLibraryDir` 会变，旧的软链会断。
- **停止时按容器内绝对路径杀进程**，不按进程名，避免误杀宿主上用户自己的 QQ。
- **解根目录不猜顶层文件夹名**，而是找含 `usr/bin/env` 的那一层，避免不同解包实现给出的路径差异。
- **脚本每次启动覆盖**，避免应用升级后容器里还跑着旧脚本。

## 安全与隐私

- **所有服务只监听 `127.0.0.1`**：AstrBot 后台（6185）与 NapCat WebUI（6099）都不对局域网开放。6099 可以直接操作 QQ 账号，绝不建议放开。
- **API Key 加密存放**：模型 Key 除了写入 AstrBot 配置，App 另存一份用于直连看图，使用 Android Keystore 的 AES-GCM 加密，密钥不可导出。
- **截图默认不出设备**：看屏幕有两条路，「只提取文字」用系统 OCR，图片不离开手机；「看图」由你主动点击才会把压缩后的截图发给你配置的模型服务商。
- **没有遥测**：不上报使用数据、不做统计、不接入任何分析 SDK。
- **备份包含明文密钥**：备份包是 `data` 目录的打包，里面有 `cmd_config.json`。分享备份前请自行脱敏。

第三方插件是**未经审核的代码**，安装后在容器内以 root 身份运行，可读写挂载进来的应用私有目录。只装你信任来源的插件。

## 已知限制

- **仅 arm64-v8a**，Android 8.0+。x86 设备与模拟器无法运行（rootfs 与 proot 都是 arm64 的）
- **后台只能从本机访问**。需要从电脑打开后台时用 `adb forward tcp:6185 tcp:6185`，再访问 `http://127.0.0.1:6185`
- **首次安装依赖网络**：apt、PyPI、NapCat 与 QQ 安装包，任一环节失败都要重试
- **内存占用高**：NapCat 基于 Electron，常驻 1–2 GB，低配机型容易被系统回收
- **QQ 风控**：非官方协议接入，存在账号受限风险，强烈建议使用小号
- **debug 签名**：当前只有 debug 包，仅供自用与测试
- **不支持微信个人号**：AstrBot 已于 2026 年 1 月移除个人微信适配器，本项目不做该方向

## 常见问题

**装完为什么占了 3 GB 多？**
rootfs 解压约 300 MB，AstrBot 的 Python 环境约 1 GB，NapCat 的 Electron 与 QQ 客户端 1–2 GB。都是运行必需品。

**为什么只支持 arm64？**
proot 与 rootfs 都是架构相关的二进制，没法跨架构复用。x86 手机与模拟器需要另一套 rootfs，本项目不做。

**怎么从电脑打开 AstrBot 后台？**
先 `adb forward tcp:6185 tcp:6185`，再在电脑浏览器打开 `http://127.0.0.1:6185`。不要去改绑定地址把端口暴露到局域网。

**模型保存了，但机器人还是用旧的？**
先点「测试连接」，它会真的发一次请求。如果报错是登录失败，说明 AstrBot 后台密码不对；如果成功但群里没变化，检查模型列表里「使用中」是不是你要的那条。

**群里必须 @ 才回复，能不 @ 吗？**
这是 AstrBot 的行为，不是 App 的限制。装「唤醒增强」类插件（如 `astrbot_plugin_wakepro`），在白名单里填群号、设置唤醒词与智能唤醒即可。注意这类能力都有概率或相关性过滤，不可能做到每句都回。

**QQ 号被封了怎么办？**
非官方协议接入本身有风险，尤其对长期不活跃、突然高频发送的账号。建议：换用自己实名的账号、先正常使用一段时间再接入、固定网络环境、不要与另一个号高频互刷。App 不会也不能绕过平台风控。

**息屏后机器人掉线？**
确认「设置」里保活开关开着，并在系统设置中把本应用加入电池优化白名单。部分 ROM（MIUI、ColorOS 等）还需要单独允许自启动与后台运行。

**换手机怎么迁移？**
在旧机「备份」→ 导出备份包 → 新机装 App、完成首次安装 →「恢复」选这个包。rootfs 与 Python 环境不会迁移，需要重新联网安装。

**看屏幕会上传我的画面吗？**
只有你点「看图」时，压缩后的截图会发送到你自己配置的模型服务商；点「只提取文字」则完全在本地完成。

## 从源码构建

要求：JDK 17、Android SDK 34、build-tools 34.0.0。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

rootfs（约 62 MB）不纳入版本库，构建时从上游拉取并按固定 SHA-256 校验。CI 流程：

```
checkout → JDK 17 → 复用 runner 预装 Android SDK → 下载并校验 rootfs
        → ./gradlew test → assembleDebug → apksigner 验签 → 上传 artifact → 更新 latest Release
```

## 目录结构

```
app/                    Android 壳层：Compose UI、前台服务、开机自启、容器内脚本
core/common/            日志、环形缓冲
core/container/         proot 容器引擎（本项目核心）
core/data/              DataStore 配置、加密凭据
core/ui/                Compose 主题与配色
deploy/                 服务器形态的 docker compose 部署参考
```

## 致谢

- [AstrBot](https://github.com/AstrBotDevs/AstrBot) —— 容器内运行的 AI 助手本体
- [NapCatQQ](https://github.com/NapNeko/NapCatQQ) —— QQ 协议端
- [DSHA](https://github.com/DSH-APP/DSHA) —— 容器搬运路线、进程状态机与工程化做法的参考
- [ElymBot](https://github.com/undertaker33/ElymBot) —— proot 启动参数、rootfs 与原生资产打包方式的参考
- Ubuntu、proot、ML Kit 及所有上游项目的维护者

## 许可

本项目以 **AGPL-3.0** 发布，全文见 [LICENSE](LICENSE)。

选择 AGPL 而不是 MIT，是因为应用会在运行时安装并分发 AstrBot（AGPL-3.0）。如果要在闭源产品中使用本项目，需要先确认与上游许可证的兼容性。

## 免责声明

本项目仅做本地环境集成，不提供 QQ / 微信账号服务，不修改或绕过任何平台鉴权。

使用非官方协议接入即时通讯平台可能违反其服务条款并导致账号受限，风险由使用者自行承担。AstrBot 采用 AGPL-3.0；NapCatQQ、QQ 客户端、Ubuntu 及 proot 的版权归各自作者所有。
