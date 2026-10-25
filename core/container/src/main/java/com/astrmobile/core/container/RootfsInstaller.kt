package com.astrmobile.core.container

import android.system.Os
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
 * 把 Ubuntu rootfs（tar.xz）解压到私有目录。
 *
 * 归档从哪儿来由 [RootfsProvider] 决定（APK 内置 / 下载缓存 / 手动导入），
 * 这里只负责解压本身，不关心来源。
 *
 * 这里不再"猜"顶层目录前缀，原因是一个真实的坑：
 * - Python tarfile 读出的首条目是 `ubuntu-noble-aarch64`（无尾斜杠）
 * - Commons Compress 读同一份归档得到的是 `ubuntu-noble-aarch64/`（**带尾斜杠**）
 * 任何基于名字的判断都会被这两种形式之一打脸，导致剥离静默失效。
 *
 * 因此改为结构判定：先原样解压到 staging，再找"真正包含 usr/bin/env 的那一层"
 * 作为根，最后 rename 到目标位置（同分区 rename 是 O(1)，不会真的搬 300MB）。
 */
class RootfsInstaller(
    private val paths: ContainerPaths,
) {

    companion object {
        private const val MIN_FREE_BYTES = 1200L * 1024 * 1024
    }

    suspend fun install(
        archive: RootfsArchive,
        onProgress: suspend (Float, String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (paths.isRootfsInstalled()) {
                onProgress(1f, "rootfs 已存在")
                return@runCatching
            }

            val free = paths.filesDir.usableSpace
            require(free > MIN_FREE_BYTES) {
                "存储空间不足：需要至少 ${MIN_FREE_BYTES / 1024 / 1024}MB，当前仅 ${free / 1024 / 1024}MB"
            }

            val staging = File(paths.runtimeDir, "rootfs-staging")
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()

            val totalBytes = archive.totalBytes

            onProgress(0f, "准备解压 ${archive.label}")
            val counting = CountingInputStream(archive.open())

            XZInputStream(BufferedInputStream(counting, 64 * 1024)).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var entry: TarArchiveEntry? = tar.nextEntry
                    var entries = 0
                    while (entry != null) {
                        val current = entry
                        extractEntry(tar, current, staging)

                        entries++
                        if (entries % 200 == 0) {
                            val fraction = if (totalBytes > 0) {
                                (counting.count.toFloat() / totalBytes).coerceIn(0f, 0.99f)
                            } else {
                                0f
                            }
                            onProgress(fraction * 0.9f, "解压 ${current.name.take(48)}")
                        }
                        entry = tar.nextEntry
                    }
                }
            }

            onProgress(0.92f, "识别根目录结构")
            val resolvedRoot = resolveRoot(staging)
                ?: error("rootfs 结构异常：找不到 usr/bin/env。顶层内容：" + describeTops(staging))

            onProgress(0.95f, "整理根目录")
            if (paths.rootfsDir.exists()) paths.rootfsDir.deleteRecursively()
            paths.rootfsDir.parentFile?.mkdirs()

            val movedByRename = resolvedRoot.renameTo(paths.rootfsDir)
            if (!movedByRename) {
                // 极端情况（跨分区/权限）退化为复制，慢但正确
                resolvedRoot.copyRecursively(paths.rootfsDir, overwrite = true)
            }
            if (staging.exists()) staging.deleteRecursively()

            check(File(paths.rootfsDir, "usr/bin/env").exists()) {
                "rootfs 整理后仍缺少 usr/bin/env，顶层内容：" + describeTops(paths.rootfsDir)
            }
            check(File(paths.rootfsDir, "usr/bin/bash").exists()) {
                "rootfs 整理后仍缺少 usr/bin/bash，顶层内容：" + describeTops(paths.rootfsDir)
            }

            paths.markRootfsInstalled()
            onProgress(1f, "rootfs 解压完成")
        }
    }

    /**
     * 找到真正的根：自己就是根，或者唯一一个含 usr/bin/env 的直接子目录。
     * 兼容归档"带外层目录"和"不带外层目录"两种打包方式。
     */
    private fun resolveRoot(dir: File): File? {
        if (File(dir, "usr/bin/env").exists()) return dir
        val children = dir.listFiles()?.filter { it.isDirectory } ?: return null
        return children.firstOrNull { File(it, "usr/bin/env").exists() }
    }

    /** 失败时把顶层结构写进错误信息，避免用户只看到"失败"两个字。 */
    private fun describeTops(dir: File): String {
        val names = dir.list()?.take(8) ?: return "目录不可读"
        return if (names.isEmpty()) "空目录" else names.joinToString(", ")
    }

    private fun extractEntry(tar: TarArchiveInputStream, entry: TarArchiveEntry, root: File) {
        val name = entry.name.trimStart('.', '/')
        if (name.isBlank()) return

        val target = File(root, name)
        val rootPath = root.canonicalPath
        val targetPath = runCatching { target.canonicalPath }.getOrElse { return }
        if (!targetPath.startsWith(rootPath)) return

        when {
            entry.isDirectory -> {
                target.mkdirs()
                applyMode(target, entry.mode)
            }

            entry.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                runCatching {
                    if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) {
                        target.delete()
                    }
                    Os.symlink(entry.linkName, target.absolutePath)
                }
            }

            entry.isLink -> {
                target.parentFile?.mkdirs()
                val source = File(root, entry.linkName.trimStart('.', '/'))
                runCatching {
                    if (target.exists()) target.delete()
                    if (source.exists()) source.copyTo(target, overwrite = true)
                }
            }

            // 字符设备 / 块设备 / FIFO：不能落成普通文件，运行时会由 -b /dev 覆盖
            entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> Unit

            else -> {
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> tar.copyTo(output) }
                applyMode(target, entry.mode)
            }
        }
    }

    private fun applyMode(file: File, mode: Int) {
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
