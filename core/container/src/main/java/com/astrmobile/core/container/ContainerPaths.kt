package com.astrmobile.core.container

import java.io.File

/**
 * 容器相关的全部路径定义。
 *
 * 目录布局（与 ElymBot 的落地经验保持一致，便于复用其资产与排错经验）：
 *
 * files/runtime/
 *   bin/            proot、loader、busybox、bash 的可执行入口（软链到 nativeLibraryDir）
 *   rootfs/ubuntu/  解压后的 Ubuntu 根文件系统
 *   assets/         首次安装用的脚本与 rootfs 归档
 *   logs/           容器输出、安装日志
 *   run/            pid、停止哨兵、安装状态
 *   tmp/            容器内 /tmp 与 /dev/shm 的宿主映射
 *   home/           容器内 /root 的宿主侧备份目录
 */
class ContainerPaths(
    val filesDir: File,
    val nativeLibDir: File,
) {
    val runtimeDir: File = File(filesDir, "runtime")
    val binDir: File = File(runtimeDir, "bin")
    val rootfsDir: File = File(runtimeDir, "rootfs/ubuntu")
    val assetsDir: File = File(runtimeDir, "assets")
    val logDir: File = File(runtimeDir, "logs")
    val runDir: File = File(runtimeDir, "run")
    val tmpDir: File = File(runtimeDir, "tmp")
    val homeDir: File = File(runtimeDir, "home")

    val containerLog: File = File(logDir, "container.log")
    val installLog: File = File(logDir, "install.log")
    val pidFile: File = File(runDir, "container.pid")
    val stopSentinel: File = File(runDir, "stop.requested")
    val installStateFile: File = File(runDir, "install.state")

    val prootBin: File = File(binDir, "proot")
    val loaderBin: File = File(binDir, "loader")
    val busyboxBin: File = File(binDir, "busybox")
    val bashBin: File = File(binDir, "bash")

    /** 容器内 AstrBot WebUI 端口。 */
    val astrbotPort: Int = 6185

    /** 容器内 NapCat WebUI 端口。 */
    val napcatPort: Int = 6099

    /** OneBot 反向 WebSocket 端口，AstrBot 作为服务端监听。 */
    val onebotPort: Int = 6199

    fun ensureDirectories() {
        listOf(runtimeDir, binDir, rootfsDir, assetsDir, logDir, runDir, tmpDir, homeDir)
            .forEach { it.mkdirs() }
        // 容器内 /tmp 与 /dev/shm 都指向这里：必须是 1777（sticky + 全可写），
        // 否则 Xvfb / Qt / Electron 会因无法创建临时文件而启动失败。
        runCatching { android.system.Os.chmod(tmpDir.absolutePath, 0x3FF) }
        runCatching { android.system.Os.chmod(runDir.absolutePath, 0x1C0) } // 0700
    }

    fun isRootfsInstalled(): Boolean = File(rootfsDir, INSTALL_MARKER).exists()

    /** 解压完成标记：只看 usr/bin/env 会把"解压到一半"误判为完成。 */
    fun markRootfsInstalled() {
        runCatching { File(rootfsDir, INSTALL_MARKER).writeText("ok") }
    }

    fun isToolchainReady(): Boolean =
        prootBin.exists() && loaderBin.exists() && File(binDir, "libtalloc.so.2").exists()

    fun isBootstrapFinished(): Boolean = installStateFile.readTextOrNull()?.trim() == "done"

    fun markBootstrapFinished() {
        installStateFile.writeText("done")
    }

    fun resetBootstrapState() {
        if (installStateFile.exists()) installStateFile.delete()
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    companion object {
        private const val INSTALL_MARKER = ".rootfs-installed"
    }
}
