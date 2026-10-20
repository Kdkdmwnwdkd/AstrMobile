package com.astrmobile.core.container

import java.io.File

/**
 * 容器网络与 apt 源配置。
 *
 * 为什么必须自己写：这个 rootfs 是从构建机打包出来的，自带配置在 proot 里是坏的——
 * - `/etc/resolv.conf` 指向 `127.0.0.53`（systemd-resolved 的 stub 地址），
 *   容器内没有 systemd-resolved，DNS 解析必然失败；
 * - 它还把 search 域写成了构建机的内网域名，进一步拖慢解析；
 * - `sources.list` 指向境外的 ports.ubuntu.com。
 *
 * 首次安装（apt 拉几百 MB 依赖）和每次启动都必须先执行本配置，
 * 否则表现就是「apt update 卡住 → 安装失败」，而日志里只有超时。
 */
object RootfsConfigurator {

    const val DEFAULT_APT_MIRROR = "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"

    fun apply(paths: ContainerPaths, aptMirror: String = DEFAULT_APT_MIRROR) {
        val etc = File(paths.rootfsDir, "etc")
        etc.mkdirs()

        File(etc, "resolv.conf").writeText(
            """
            nameserver 223.5.5.5
            nameserver 119.29.29.29
            nameserver 8.8.8.8
            options edns0
            """.trimIndent() + "\n",
        )

        File(etc, "hostname").writeText("astrmobile\n")
        File(etc, "hosts").writeText(
            """
            127.0.0.1 localhost astrmobile
            ::1 localhost
            """.trimIndent() + "\n",
        )

        val aptDir = File(etc, "apt")
        aptDir.mkdirs()
        File(aptDir, "sources.list").writeText(
            """
            deb [trusted=yes] $aptMirror noble main restricted universe multiverse
            deb [trusted=yes] $aptMirror noble-updates main restricted universe multiverse
            deb [trusted=yes] $aptMirror noble-security main restricted universe multiverse
            deb [trusted=yes] $aptMirror noble-backports main restricted universe multiverse
            """.trimIndent() + "\n",
        )

        // Ubuntu 24.04 的 deb822 源如果存在，会和上面的 sources.list 重复
        File(aptDir, "sources.list.d").mkdirs()
        runCatching {
            val ubuntuSources = File(aptDir, "sources.list.d/ubuntu.sources")
            if (ubuntuSources.exists()) ubuntuSources.delete()
        }

        // APT::Sandbox::User=root：proot 下切不到 _apt 用户，不设会直接报权限错误
        File(aptDir, "apt.conf.d").mkdirs()
        File(aptDir, "apt.conf.d/99astrmobile").writeText(
            """
            Acquire::Retries "3";
            Acquire::http::Timeout "30";
            Acquire::https::Timeout "30";
            Acquire::Languages "none";
            APT::Sandbox::User "root";
            APT::Get::AllowUnauthenticated "true";
            """.trimIndent() + "\n",
        )
    }
}
