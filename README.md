# AstrMobile

> 手机上的 AstrBot + QQ 机器人。装一个 App，把一台 Android 手机变成 24 小时在线的 AI 机器人主机。

## 这是什么

AstrMobile 是一个 **Android 端的 AstrBot 启动器**：

- 内置完整 Ubuntu 24.04 arm64 根文件系统，通过 **proot 免 ROOT** 运行
- 容器内安装并运行 **完整版 AstrBot**（Python 后端 + WebUI，保留插件/知识库/MCP 全部能力）
- 同时运行 **NapCatQQ**（基于官方 QQ Linux 客户端的 OneBot 协议端），扫码即可接管 QQ 账号
- 原生 Compose 界面负责安装向导、启停控制、实时日志、网页后台入口与后台保活

**不是**一个精简版聊天壳：容器里跑的是与电脑上完全相同的 AstrBot。

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
│    ├── Onboarding  安装向导（进度 + 实时日志）                                          │
│    ├── Dashboard   状态卡、启停、快捷入口                                               │
│    ├── Console     容器实时日志                                                         │
│    ├── WebUi       WebView 打开 AstrBot(6185) / NapCat(6099)                            │
│    └── Settings    镜像源、开机自启、清除环境                                           │
│                                                                                        │
│  ContainerService（前台服务 + WakeLock + 常驻通知）  ← 保证息屏不掉线                    │
│                                                                                        │
│  core:container                                                                        │
│    ├── ContainerPaths     目录布局                                                      │
│    ├── BinaryInstaller    nativeLibraryDir → bin 软链（绕过 W^X）                       │
│    ├── RootfsInstaller    Java 解压 tar.xz（保留符号链接/权限，精确进度）                │
│    ├── ProotCommand       proot 参数构造                                                │
│    ├── ContainerController 状态机 / 启停 / 日志泵 / 端口探活                            │
│    └── Bootstrapper       首次安装编排                                                  │
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
3. 安装完成后进入控制台 → 「启动机器人」
4. 等两个端口都显示「已就绪」
5. 打开 **NapCat 扫码登录**，用手机 QQ 扫码
6. 打开 **AstrBot 后台**（默认账号/密码均为 `astrbot`，务必修改）
7. 「模型提供商」填入 API Key
8. 「机器人 → 通信接口」新增 `aiocqhttp`，地址 `ws://127.0.0.1:6199/ws`
9. 在群里 @机器人 即可对话

## 已知限制

- **仅 arm64-v8a**，Android 8.0+；x86 设备与模拟器不支持
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
