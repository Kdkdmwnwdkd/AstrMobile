package com.astrmobile.core.container

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 备份结果。 */
data class BackupResult(
    val file: File,
    val entryCount: Int,
    val bytes: Long,
)

/**
 * AstrBot 数据的备份与恢复。
 *
 * 备份的是容器里的 `root/data`：配置、人格、插件、会话数据库、知识库。
 * 不含 rootfs 和 Python 环境——那些能重装，没必要占几百 MB。
 *
 * 排除项是刻意留的：`temp`、`logs`、`__pycache__` 属于可再生的东西，
 * 打进包里只会让包变大、恢复变慢。
 */
object BackupRepository {

    private const val DATA_RELATIVE = "root/data"
    private const val BACKUP_DIR = "backups"

    private val EXCLUDED_DIRS = setOf(
        "temp", "logs", "__pycache__", ".cache", "cache", ".git", "node_modules",
    )

    /** 备份包放在 filesDir/backups 下，便于 FileProvider 分享。 */
    fun backupDir(paths: ContainerPaths): File =
        File(paths.filesDir, BACKUP_DIR).apply { mkdirs() }

    /**
     * 打包 data 目录。
     *
     * @param onProgress 传入已处理的文件数与总文件数，UI 自己算百分比。
     */
    fun create(
        paths: ContainerPaths,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Result<BackupResult> = runCatching {
        val dataDir = File(paths.rootfsDir, DATA_RELATIVE)
        check(dataDir.isDirectory) { "还没初始化数据目录，先启动一次容器" }

        val files = collect(dataDir)
        check(files.isNotEmpty()) { "数据目录是空的，没什么可备份" }

        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        val target = File(backupDir(paths), "astrmobile-backup-$stamp.zip")

        var done = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target), 64 * 1024)).use { zip ->
            zip.setLevel(6)
            files.forEach { file ->
                val relative = file.relativeTo(dataDir).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(relative))
                BufferedInputStream(FileInputStream(file), 64 * 1024).use { input ->
                    input.copyTo(zip, 64 * 1024)
                }
                zip.closeEntry()
                done++
                if (done % 20 == 0 || done == files.size) onProgress(done, files.size)
            }
        }

        BackupResult(file = target, entryCount = files.size, bytes = target.length())
    }

    /**
     * 从备份包恢复。
     *
     * 先把现有 data 改名成 `data.previous`（同分区 rename，瞬间完成且可回滚），
     * 解压成功才删掉它；失败就把半成品删掉、改名还原。
     */
    fun restore(
        paths: ContainerPaths,
        zipFile: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Result<Unit> = runCatching {
        val dataDir = File(paths.rootfsDir, DATA_RELATIVE)
        val previous = File(dataDir.parentFile, "data.previous")

        // 先确认包是能用的，别把现有数据改走了才发现包坏了
        val entries = listEntries(zipFile)
        check(entries.isNotEmpty()) { "这个压缩包里没有文件" }
        check(entries.any { it.endsWith("cmd_config.json") }) {
            "这不像是 AstrBot 的备份包（里面没有 cmd_config.json）"
        }

        previous.deleteRecursively()
        if (dataDir.exists()) {
            check(dataDir.renameTo(previous)) { "无法移动现有数据目录，可能容器还在运行" }
        }
        dataDir.mkdirs()

        try {
            var done = 0
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile), 64 * 1024)).use { zip ->
                val rootPath = dataDir.canonicalPath
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val out = File(dataDir, entry.name)
                        // 防路径穿越：包里的 ../ 不能写到 data 目录之外
                        if (out.canonicalPath.startsWith(rootPath)) {
                            out.parentFile?.mkdirs()
                            BufferedOutputStream(FileOutputStream(out), 64 * 1024).use { output ->
                                zip.copyTo(output, 64 * 1024)
                            }
                        }
                    }
                    zip.closeEntry()
                    done++
                    if (done % 20 == 0) onProgress(done, entries.size)
                    entry = zip.nextEntry
                }
            }
            previous.deleteRecursively()
        } catch (error: Throwable) {
            dataDir.deleteRecursively()
            previous.renameTo(dataDir)
            throw error
        }
    }

    /** 列出已有备份，最新的在前。 */
    fun list(paths: ContainerPaths): List<File> =
        backupDir(paths).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun delete(file: File): Boolean = file.delete()

    // ---------------------------------------------------------------- 内部

    private fun collect(root: File): List<File> = buildList {
        root.walkTopDown()
            .onEnter { dir ->
                val name = dir.name.lowercase()
                dir == root || name !in EXCLUDED_DIRS
            }
            .filter { it.isFile }
            .forEach { add(it) }
    }

    private fun listEntries(zip: File): List<String> = runCatching {
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { stream ->
            buildList {
                var entry = stream.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) add(entry.name)
                    stream.closeEntry()
                    entry = stream.nextEntry
                }
            }
        }
    }.getOrDefault(emptyList())

    /** 人类可读的大小。 */
    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
