package com.astrmobile.core.container

/**
 * 内置 Ubuntu 系统包（rootfs）的来源与校验信息。
 *
 * 这份 101MB 的归档以前直接塞在 APK 的 assets 里，代价是每发一个版本都要
 * 重新下载上百 MB 的安装包，装的却还是同一份系统。现在改成首次启动时单独下载：
 *
 * - APK 从 ~105MB 降到 ~15MB，日常更新几秒就下完
 * - 系统包内容不变，用户重装 App 不用重新下一遍（留在私有目录里）
 * - 文件名里带校验值前缀，内容一改名字就变，CDN 与镜像不会拿旧缓存糊弄校验
 *
 * 仍然保留 assets 分支：自己编译、想把包装进 APK 的人照样能用，只需把文件
 * 放到 [ASSET_PATH]，[RootfsProvider] 会优先用它。
 */
object RootfsPackage {

    /** 自编译包里内置归档的位置。有就优先用，没有就走下载。 */
    const val ASSET_PATH = "rootfs/ubuntu-rootfs.tar.xz"

    /** 归档的 SHA-256。换包时必须同步改这里，否则下载完会被判为损坏。 */
    const val SHA256 = "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa"

    /** 归档解压后的体积量级，仅用于给用户一个"需要多少空间"的预期。 */
    const val UNPACKED_BYTES = 320L * 1024 * 1024

    /**
     * 远程文件名。带 SHA 前缀而不是叫 `ubuntu-rootfs.tar.xz`：
     * 同一个 URL 的内容永远不变，镜像缓存、断点续传、用户手里的残留文件
     * 都不会出现"下了一半发现是旧版本"这种事。
     */
    val remoteFile: String = "ubuntu-rootfs-${SHA256.take(8)}.tar.xz"

    /** 我们自己 release 上的固定位置，tag 恒为 latest。 */
    val releaseUrl: String =
        "https://github.com/Kdkdmwnwdkd/AstrMobile/releases/download/latest/$remoteFile"

    /**
     * 上游原始出处。只作为我们自己的 release 资产缺失时的兜底：
     * 那是第三方仓库，随时可能改路径，所以永远排在最后。
     */
    val upstreamUrl: String =
        "https://raw.githubusercontent.com/undertaker33/ElymBot/main/" +
            "app/src/main/assets/runtime/assets/ubuntu-rootfs.tar.xz"

    /** 加速前缀，按实测速度排序。空串表示直连 GitHub。 */
    private val mirrorPrefixes: List<String> = listOf(
        "https://ghfast.top",
        "https://gh-proxy.com",
        "https://ghproxy.net",
        "",
    )

    /**
     * 候选下载地址，按尝试顺序排列。
     *
     * 加速前缀的拼法是 `https://ghfast.top/https://github.com/...`，
     * 也就是「前缀 + 斜杠 + 完整原始地址」，不是替换 host。
     */
    fun mirrorUrls(
        prefixes: List<String> = mirrorPrefixes,
        origin: String = releaseUrl,
        fallback: String = upstreamUrl,
    ): List<String> {
        val mirrored = prefixes.map { prefix ->
            if (prefix.isBlank()) origin else prefix.trimEnd('/') + "/" + origin
        }
        // fallback 也过一遍前缀，raw.githubusercontent.com 在国内同样需要加速
        val upstreamMirrored = if (fallback.isBlank()) {
            emptyList()
        } else {
            prefixes.filter { it.isNotBlank() }.map { it.trimEnd('/') + "/" + fallback }
        }
        return (mirrored + upstreamMirrored + listOf(fallback))
            .distinct()
            .filter { it.isNotBlank() }
    }

    /** 下载缓存目录（相对 runtimeDir）。 */
    const val CACHE_DIR = "rootfs-download"

    /** 未下完的半成品后缀。有它就能续传，不用从 0 开始。 */
    const val PART_SUFFIX = ".part"

    /**
     * 校验值和实际文件是否一致。
     *
     * 大小写不敏感：某些工具输出大写十六进制，直接 `==` 会误判。
     */
    fun matches(expected: String, actual: String): Boolean =
        expected.isNotBlank() && expected.equals(actual, ignoreCase = true)

    /** 算文件 SHA-256，小写十六进制。下载器与手动导入共用，避免两份实现漂移。 */
    fun sha256Of(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** 给用户看的体积，如 "101 MB"。 */
    fun describeBytes(bytes: Long): String = when {
        bytes <= 0L -> "未知大小"
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / 1024.0 / 1024)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}
