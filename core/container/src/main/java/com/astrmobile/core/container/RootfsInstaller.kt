package com.astrmobile.core.container

import android.content.Context
import android.system.Os
import com.astrmobile.core.common.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * 把内置的 Ubuntu rootfs（tar.xz）解压到私有目录。
 *
 * 两个必须处理的细节：
 * 1. 归档的所有条目都带统一顶层目录前缀（实测 `ubuntu-noble-aarch64/`，12684 条无一例外），
 *    必须剥离，否则 rootfs 会落到 `rootfs/ubuntu/ubuntu-noble-aarch64/usr/...`，
 *    proot 用 `-r rootfs/ubuntu` 找不到 `/bin/bash`，首次安装必然失败。
 * 2. 归档内的字符设备（/dev/null 等）不能用普通文件代替，直接跳过——
 *    运行时会由 `-b /dev` 覆盖，写一个 0 字节普通文件反而会埋雷。
 */
class RootfsInstaller(
    private val context: Context,
    private val paths: ContainerPaths,
) {

    companion object {
        private const val ASSET_PATH = "rootfs/ubuntu-rootfs.tar.xz"
        private const val MIN_FREE_BYTES = 900L * 1024 * 1024

        /** FHS 标准根目录：出现这些名字说明归档没有外层目录，不能当作前缀剥离。 */
        private val STANDARD_ROOT_DIRS = setOf(
            "bin", "boot", "dev", "etc", "home", "lib", "lib32", "lib64",
            "media", "mnt", "opt", "proc", "root", "run", "sbin", "srv",
            "sys", "tmp", "usr", "var", "snap", "lost+found",
        )
    }

    suspend fun install(onProgress: suspend (Float, String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (paths.isRootfsInstalled()) {
                    onProgress(1f, "rootfs 已存在")
                    return@runCatching
                }

                val free = paths.filesDir.usableSpace
                require(free > MIN_FREE_BYTES) {
                    "存储空间不足：需要至少 ${MIN_FREE_BYTES / 1024 / 1024}MB，当前仅 ${free / 1024 / 1024}MB"
                }

                val totalBytes = runCatching { context.assets.openFd(ASSET_PATH).use { it.length } }
                    .getOrDefault(0L)

                // 上次解压中断可能留下半成品（例如 bin 已是实体目录而非符号链接），
                // 直接覆盖会因 EEXIST 静默失败，导致 bin/bash 永远缺失。先清干净。
                if (paths.rootfsDir.exists()) {
                    paths.rootfsDir.deleteRecursively()
                }
                paths.rootfsDir.mkdirs()
                onProgress(0f, "准备解压 rootfs")

                val raw = context.assets.open(
                    ASSET_PATH,
                    android.content.res.AssetManager.ACCESS_STREAMING,
                )
                val counting = CountingInputStream(raw)

                XZInputStream(BufferedInputStream(counting, 64 * 1024)).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        var entry: TarArchiveEntry? = tar.nextEntry
                        var entries = 0
                        var topLevelPrefix: String? = null
                        var prefixResolved = false

                        while (entry != null) {
                            val current = entry
                            val rawName = current.name.trimStart('.', '/')

                            if (!prefixResolved) {
                                prefixResolved = true
                                topLevelPrefix = detectTopLevel(current, rawName)
                                if (topLevelPrefix != null) {
                                    AppLog.app("rootfs 顶层目录：$topLevelPrefix（解压时剥离）")
                                }
                            }

                            val name = stripTopLevel(rawName, topLevelPrefix)
                            if (name.isNotBlank()) {
                                extractEntry(tar, current, name)
                            }

                            entries++
                            if (entries % 200 == 0) {
                                val fraction = if (totalBytes > 0) {
                                    (counting.count.toFloat() / totalBytes).coerceIn(0f, 0.99f)
                                } else {
                                    0f
                                }
                                onProgress(fraction, "解压 ${name.take(48)}")
                            }
                            entry = tar.nextEntry
                        }
                    }
                }

                check(File(paths.rootfsDir, "usr/bin/env").exists()) {
                    "rootfs 解压完成但缺少 usr/bin/env"
                }
                check(File(paths.rootfsDir, "bin/bash").exists()) {
                    "rootfs 解压完成但缺少 bin/bash"
                }

                paths.markRootfsInstalled()
                onProgress(1f, "rootfs 解压完成")
                AppLog.app("rootfs 解压完成：${paths.rootfsDir.absolutePath}")
            }
        }

    /**
     * 识别归档的统一顶层目录。
     *
     * 实测该归档首条目是目录 `ubuntu-noble-aarch64`（**不带尾斜杠**），
     * 所以不能用 "name 里是否含 /" 来判断。
     *
     * 同时必须排除 FHS 标准根目录名：如果某个 rootfs 直接在根下打包，
     * 首条目会是 `bin` / `usr` 这类目录，把它们当前缀会把 `bin/bash` 剥成 `bash`。
     */
    private fun detectTopLevel(entry: TarArchiveEntry, name: String): String? {
        if (!entry.isDirectory) return null
        if (name.contains('/')) return null
        val normalized = name.lowercase()
        if (normalized in STANDARD_ROOT_DIRS) return null
        return name.ifBlank { null }
    }

    private fun stripTopLevel(name: String, prefix: String?): String {
        if (prefix == null) return name
        if (name == prefix) return ""
        val withSlash = "$prefix/"
        return if (name.startsWith(withSlash)) name.removePrefix(withSlash) else name
    }

    private fun extractEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry, name: String) {
        val target = File(paths.rootfsDir, name)
        val rootPath = paths.rootfsDir.canonicalPath
        val targetPath = runCatching { target.canonicalPath }.getOrElse {
            // 路径穿越或非法名称，直接跳过而不是让安装整体失败。
            return
        }
        if (!targetPath.startsWith(rootPath)) return

        when {
            entry.isDirectory -> {
                target.mkdirs()
                applyMode(target, entry.mode)
            }

            entry.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                runCatching {
                    if (target.exists() || runCatching {
                            java.nio.file.Files.isSymbolicLink(target.toPath())
                        }.getOrDefault(false)
                    ) {
                        target.delete()
                    }
                    Os.symlink(entry.linkName, target.absolutePath)
                }
            }

            entry.isLink -> {
                target.parentFile?.mkdirs()
                val source = File(paths.rootfsDir, entry.linkName.trimStart('.', '/'))
                runCatching {
                    if (target.exists()) target.delete()
                    if (source.exists()) {
                        source.copyTo(target, overwrite = true)
                    }
                }
            }

            // 字符设备 / 块设备 / FIFO：不能落成普通文件，交给运行时 bind 的 /dev
            entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> Unit

            else -> {
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> tar.copyTo(output) }
                applyMode(target, entry.mode)
            }
        }
    }

    private fun applyMode(file: File, mode: Int) {
        // 只保留 rwx 权限位，绝不设置 setuid/setgid，避免在容器内提权。
        val permission = mode and 0x1FF
        if (permission != 0) {
            runCatching { Os.chmod(file.absolutePath, permission) }
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count++
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = super.read(b, off, len)
            if (read > 0) count += read
            return read
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) count += skipped
            return skipped
        }
    }
}
