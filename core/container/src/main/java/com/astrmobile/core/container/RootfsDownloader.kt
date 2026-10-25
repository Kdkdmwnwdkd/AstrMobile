package com.astrmobile.core.container

import com.astrmobile.core.common.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** 下载进度。total 为 -1 表示服务器没给长度。 */
data class DownloadProgress(
    val downloaded: Long,
    val total: Long,
    val attempt: Int,
    val attemptCount: Int,
    val resumed: Boolean,
) {
    /** 0..1；长度未知时返回 -1，界面据此显示不确定进度条。 */
    val fraction: Float
        get() = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 1f) else -1f

    val label: String
        get() {
            val done = RootfsPackage.describeBytes(downloaded)
            return if (total > 0L) "$done / ${RootfsPackage.describeBytes(total)}" else done
        }
}

/**
 * 把系统包下载到私有目录，支持断点续传与多源回退。
 *
 * 为什么值得单独写一个下载器：
 * - 这份包 101MB，国内直连 GitHub 常常几百 KB/s，中途断一次就前功尽弃，
 *   所以必须能带着已有字节接着下（Range 请求）。
 * - 加速镜像不可靠，可能返回 HTML 错误页、可能限速、可能直接 5xx。
 *   校验值对不上就当这次没下过，换下一个源。
 * - 下载完成后必须验 SHA-256：镜像回错内容时，解压会在几分钟后才失败，
 *   那时用户已经等了很久，错误信息还指向"解压失败"，极难排查。
 */
class RootfsDownloader(
    private val cacheDir: File,
    private val candidates: List<String> = RootfsPackage.mirrorUrls(),
    private val expectedSha256: String = RootfsPackage.SHA256,
    private val targetName: String = RootfsPackage.remoteFile,
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_REDIRECTS = 5
        private const val BUFFER_SIZE = 128 * 1024
        private const val REPORT_INTERVAL = 512 * 1024L

        /** HttpURLConnection 里没有 416 的常量，只能自己写。 */
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }

    /** 下载完成后的归档。 */
    val target: File get() = File(cacheDir, targetName)

    /** 半成品：有它下次就能续传。 */
    val partFile: File get() = File(cacheDir, targetName + RootfsPackage.PART_SUFFIX)

    /** 本地已经有校验通过的归档时直接返回，不会碰网络。 */
    fun existingArchive(): File? {
        val file = target
        if (!file.exists() || file.length() <= 0L) return null
        return if (RootfsPackage.matches(expectedSha256, sha256(file))) file else null
    }

    /**
     * 拿到一份可用的归档：本地有就用本地的，否则依次尝试各镜像。
     *
     * 全部失败时保留 `.part`，用户点重试会从断点继续，而不是回到 0%。
     */
    suspend fun ensure(
        onProgress: suspend (DownloadProgress) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        existingArchive()?.let { return@withContext Result.success(it) }

        cacheDir.mkdirs()
        var lastError: Throwable? = null

        for ((index, url) in candidates.withIndex()) {
            coroutineContext.ensureActive()
            val attempt = index + 1
            try {
                downloadFrom(url, attempt, candidates.size, onProgress)

                val actual = sha256(partFile)
                if (!RootfsPackage.matches(expectedSha256, actual)) {
                    // 内容不对，这份半成品没有续传价值，删掉换源重下
                    partFile.delete()
                    throw IOException("校验不通过（期望 ${expectedSha256.take(12)}…，实际 ${actual.take(12)}…）")
                }

                if (!partFile.renameTo(target)) {
                    partFile.copyTo(target, overwrite = true)
                    partFile.delete()
                }
                AppLog.app("系统包下载完成（第 $attempt 个源）")
                return@withContext Result.success(target)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                lastError = error
                AppLog.app("系统包源 $attempt/${candidates.size} 失败：${error.message}")
            }
        }

        val detail = lastError?.message ?: "未知错误"
        Result.failure(
            IOException("系统包下载失败：$detail。可以换个网络重试，或用「从文件导入」选本地安装包。"),
        )
    }

    private suspend fun downloadFrom(
        url: String,
        attempt: Int,
        attemptCount: Int,
        onProgress: suspend (DownloadProgress) -> Unit,
    ) {
        var offset = if (partFile.exists()) partFile.length() else 0L
        var resetDone = false

        while (true) {
            val resumed = offset > 0L
            val connection = openWithRedirects(url, offset)
            try {
                val code = connection.responseCode

                if (code == HTTP_RANGE_NOT_SATISFIABLE && !resetDone) {
                    // 半成品比服务器上的文件还大，八成是上次换过包：丢掉从头来
                    partFile.delete()
                    offset = 0L
                    resetDone = true
                    continue
                }
                if (code == HttpURLConnection.HTTP_OK) {
                    // 服务器忽略 Range（或我们本来就没要求）：只能从头写
                    offset = 0L
                } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("HTTP $code")
                }

                val contentLength = connection.contentLengthLong
                val total = if (contentLength > 0L) offset + contentLength else -1L

                FileOutputStream(partFile, offset > 0L).use { output ->
                    connection.inputStream.use { stream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = offset
                        var lastReport = 0L

                        while (true) {
                            coroutineContext.ensureActive()
                            val read = stream.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            downloaded += read

                            // 每 512KB 报一次：再密就会把界面刷成幻灯片
                            if (downloaded - lastReport >= REPORT_INTERVAL) {
                                lastReport = downloaded
                                onProgress(
                                    DownloadProgress(downloaded, total, attempt, attemptCount, resumed),
                                )
                            }
                        }
                        output.flush()
                        onProgress(DownloadProgress(downloaded, total, attempt, attemptCount, resumed))
                    }
                }
                return
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    /** 手动跟重定向：每跳都补上 Range，避免被 302 到 CDN 后从头开始。 */
    private fun openWithRedirects(rawUrl: String, offset: Long): HttpURLConnection {
        var current = rawUrl
        var hops = 0

        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AstrMobile-Rootfs/1.0")
                setRequestProperty("Accept", "application/octet-stream,*/*")
                if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
            }

            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("HTTP $code 但没给跳转地址")
                hops++
                if (hops > MAX_REDIRECTS) throw IOException("重定向次数过多")
                current = if (location.startsWith("http")) {
                    location
                } else {
                    URL(URL(current), location).toString()
                }
                continue
            }
            return connection
        }
    }

    private fun sha256(file: File): String = RootfsPackage.sha256Of(file)

    /** 已下载字节数，供界面在重试时提示"将从 x% 继续"。 */
    fun pendingBytes(): Long = if (partFile.exists()) partFile.length() else 0L

    /** 放弃当前半成品（用户主动换源或清空缓存时用）。 */
    fun discardPartial() {
        runCatching { partFile.delete() }
    }
}
