package com.astrmobile.core.container

import java.io.File

/**
 * 构造 proot 命令行。
 *
 * 这些参数是在安卓上跑通 Ubuntu 的关键，缺一不可：
 * - `-0`            伪装 root，dpkg/apt 才不会因权限报错
 * - `--link2symlink` 私有目录禁止 hardlink，dpkg 解包依赖它
 * - `-b /dev /proc /sys /system /apex` 复用宿主内核接口
 * - `/dev/shm` 指向可写 tmp，否则 Qt/Electron 启动即崩
 */
object ProotCommand {

    fun build(
        paths: ContainerPaths,
        workdir: String,
        argv: List<String>,
        containerEnv: Map<String, String>,
        // 短命令（如重启 NapCat）必须关掉，否则 proot 退出会连带杀掉刚拉起的后台进程。
        killOnExit: Boolean = true,
    ): List<String> {
        val command = mutableListOf(
            paths.prootBin.absolutePath,
            "-0",
        )
        if (killOnExit) command += "--kill-on-exit"
        command += listOf(
            "-r", paths.rootfsDir.absolutePath,
            "--link2symlink",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",
            "-b", "/apex",
            "-b", "/dev/pts",
            // 注意：不要再绑 /proc/self/fd/0..2。/ Android App 没有 stdin，源路径不存在，
            // proot 会为每个失败的绑定打一条 warning，且容器内 /dev/stdin 本来就是坏的。
            "-b", "${paths.tmpDir.absolutePath}:${paths.tmpDir.absolutePath}",
            "-b", "${paths.tmpDir.absolutePath}:/dev/shm",
            "-b", "${paths.filesDir.absolutePath}:${paths.filesDir.absolutePath}",
            "-w", workdir,
            "/usr/bin/env", "-i",
        )

        containerEnv.forEach { (key, value) -> command += "$key=$value" }
        command += argv
        return command
    }

    /** proot 自身在宿主侧需要的环境变量。 */
    fun applyHostEnvironment(builder: ProcessBuilder, paths: ContainerPaths) {
        val env = builder.environment()
        env["PROOT_LOADER"] = paths.loaderBin.absolutePath
        env["PROOT_TMP_DIR"] = paths.tmpDir.absolutePath
        env["LD_LIBRARY_PATH"] = listOf(paths.binDir.absolutePath, paths.nativeLibDir.absolutePath)
            .joinToString(":")
        env["HOME"] = paths.homeDir.absolutePath
        // 部分厂商 ROM 的 seccomp 会拦截 proot 的 ptrace，遇到崩溃时由用户切到兼容模式。
        env.remove("PROOT_NO_SECCOMP")
    }

    fun defaultContainerEnv(paths: ContainerPaths, timezone: String): Map<String, String> = mapOf(
        "HOME" to "/root",
        "TMPDIR" to paths.tmpDir.absolutePath,
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "TZ" to timezone,
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "DEBIAN_FRONTEND" to "noninteractive",
        "APT_LISTCHANGES_FRONTEND" to "none",
    )

    fun ensureExecutable(file: File) {
        if (file.exists()) {
            runCatching { android.system.Os.chmod(file.absolutePath, 0b111101101) }
        }
    }
}
