package com.astrmobile.core.container

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.InputStream

/**
 * 一份可解压的系统归档：来源可以是 APK 内置、下载缓存，或用户手动导入的文件。
 *
 * 用「打开流的函数 + 字节数」而不是直接持有流，是因为解压失败重试时要能重新
 * 从头读一遍——流是一次性的，而文件可以反复打开。
 */
class RootfsArchive(
    val label: String,
    val totalBytes: Long,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()
}

/**
 * 决定这次安装从哪儿拿归档。
 *
 * 优先级：APK 内置 → 下载缓存 → 网络下载。
 *
 * 保留「APK 内置」这一路，是为了自己编译的人还能把包装进 APK 离线安装；
 * 官方包里没有这个文件，会直接走下载。
 */
class RootfsProvider(
    private val context: Context,
    private val paths: ContainerPaths,
) {

    private val cacheDir: File = File(paths.runtimeDir, RootfsPackage.CACHE_DIR)

    val downloader: RootfsDownloader = RootfsDownloader(cacheDir)

    /** APK 里是否真的带了归档（官方包不带）。 */
    fun hasBundledArchive(): Boolean = runCatching {
        context.assets.list("rootfs")?.any { it == "ubuntu-rootfs.tar.xz" } == true
    }.getOrDefault(false)

    /** 本地是否已有可直接解压的归档，用于界面上提前告知"无需下载"。 */
    fun hasLocalArchive(): Boolean =
        hasBundledArchive() || downloader.existingArchive() != null

    /**
     * 准备好归档。
     *
     * [onDownload] 只在真的需要联网时才会被调用；本地已有内容时不会触发，
     * 界面因此不会闪一下"下载 0%"。
     */
    suspend fun acquire(
        onDownload: suspend (DownloadProgress) -> Unit = {},
    ): Result<RootfsArchive> {
        bundledArchive()?.let { return Result.success(it) }

        val cached = downloader.existingArchive()
        if (cached != null) {
            return Result.success(archiveOf(cached))
        }

        return downloader.ensure(onDownload).map { archiveOf(it) }
    }

    /** 用户从系统文件选择器导入的归档：拷进缓存目录，再按正常流程解压。 */
    fun adoptImportedFile(source: File): Result<RootfsArchive> = runCatching {
        require(source.exists() && source.length() > 0L) { "文件不存在或为空" }
        cacheDir.mkdirs()
        val target = downloader.target
        source.copyTo(target, overwrite = true)

        require(RootfsPackage.matches(RootfsPackage.SHA256, RootfsPackage.sha256Of(target))) {
            "这个文件不是本版本所需的系统包（校验值不匹配）。请确认选的是 Ubuntu rootfs 归档。"
        }
        archiveOf(target)
    }.onFailure {
        // 导入失败不能留下一个"看起来存在但没用"的文件，否则下次启动会反复解压失败
        runCatching { downloader.target.delete() }
    }

    private fun bundledArchive(): RootfsArchive? {
        if (!hasBundledArchive()) return null
        val size = runCatching { context.assets.openFd(RootfsPackage.ASSET_PATH).use { it.length } }
            .getOrDefault(0L)
        return RootfsArchive(
            label = "APK 内置",
            totalBytes = size,
            opener = {
                context.assets.open(
                    RootfsPackage.ASSET_PATH,
                    AssetManager.ACCESS_STREAMING,
                )
            },
        )
    }

    private fun archiveOf(file: File): RootfsArchive = RootfsArchive(
        label = file.name,
        totalBytes = file.length(),
        opener = { file.inputStream() },
    )
}
