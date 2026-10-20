package com.astrmobile.core.container

import android.content.Context
import android.system.Os
import com.astrmobile.core.common.AppLog
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
 * 为什么不用 busybox 解压：Android 各厂商 ROM 的 toybox/busybox 对 xz 支持不一致，
 * 而首次安装失败最难排查。这里用纯 Java 的 XZ + Commons Compress，
 * 行为完全可控，并且能精确上报进度、保留符号链接与权限位。
 */
class RootfsInstaller(
    private val context: Context,
    private val paths: ContainerPaths,
) {

    companion object {
        private const val ASSET_PATH = "rootfs/ubuntu-rootfs.tar.xz"
        private const val MIN_FREE_BYTES = 900L * 1024 * 1024
    }

    /**
     * @param onProgress 进度 0f..1f 与当前处理的条目描述
     */
    fun install(onProgress: (Float, String) -> Unit): Result<Unit> = runCatching {
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

        paths.rootfsDir.mkdirs()
        onProgress(0f, "准备解压 rootfs")

        val raw = context.assets.open(ASSET_PATH, android.content.res.AssetManager.ACCESS_STREAMING)
        val counting = CountingInputStream(raw)

        XZInputStream(BufferedInputStream(counting, 64 * 1024)).use { xz ->
            TarArchiveInputStream(xz).use { tar ->
                var entry: TarArchiveEntry? = tar.nextEntry
                var entries = 0
                while (entry != null) {
                    val current = entry
                    extractEntry(tar, current)

                    entries++
                    if (entries % 200 == 0) {
                        val fraction = if (totalBytes > 0) {
                            (counting.count.toFloat() / totalBytes).coerceIn(0f, 0.99f)
                        } else {
                            0f
                        }
                        onProgress(fraction, "解压 ${current.name.take(48)}")
                    }
                    entry = tar.nextEntry
                }
            }
        }

        check(File(paths.rootfsDir, "usr/bin/env").exists()) { "rootfs 解压完成但缺少 usr/bin/env" }
        check(File(paths.rootfsDir, "bin/bash").exists()) { "rootfs 解压完成但缺少 bin/bash" }
        paths.markRootfsInstalled()
        onProgress(1f, "rootfs 解压完成")
        AppLog.app("rootfs 解压完成：${paths.rootfsDir.absolutePath}")
    }

    private fun extractEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry) {
        val name = entry.name.trimStart('.', '/')
        if (name.isBlank()) return

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
                    if (target.exists()) target.delete()
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
    }
}
