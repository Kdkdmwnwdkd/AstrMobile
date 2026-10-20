package com.astrmobile.core.container

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** 插件市场里的一条数据。 */
data class MarketPlugin(
    val id: String,
    val displayName: String,
    val description: String,
    val author: String,
    val repo: String,
    val tags: List<String>,
    val stars: Int,
    val version: String,
)

/** 已装进容器的插件。 */
data class InstalledPlugin(
    val dirName: String,
    val displayName: String,
    val version: String,
    val description: String,
)

/**
 * 插件市场与安装。
 *
 * 市场索引是 AstrBot 官方维护的公开 JSON（1300+ 条），不需要登录 AstrBot 后台，
 * 也不依赖它的 REST 接口——那条路要账号密码，而密码在配置里只存哈希。
 *
 * 安装本质就是：把插件仓库的 zip 解到 `data/plugins/<目录名>/`，再重启容器让它加载。
 */
object PluginRepository {

    /** 官方插件集合仓库里的缓存文件，比 api.soulter.top 稳（后者会 301）。 */
    private const val INDEX_PRIMARY =
        "https://gh-proxy.com/https://raw.githubusercontent.com/AstrBotDevs/AstrBot_Plugins_Collection/refs/heads/main/plugin_cache_original.json"

    private const val INDEX_FALLBACK = "https://api.soulter.top/astrbot/plugins"

    /** 手机直连 GitHub 基本拉不动，统一走加速。 */
    private const val GITHUB_PROXY = "https://gh-proxy.com/"

    private const val CACHE_FILE = "plugin_market.json"
    private const val CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L

    private const val PLUGINS_RELATIVE = "root/data/plugins"

    // ---------------------------------------------------------------- 市场索引

    /** 读市场索引，优先本地缓存；缓存过期或不存在才联网。 */
    fun loadMarket(context: Context, forceRefresh: Boolean = false): Result<List<MarketPlugin>> {
        val cache = File(context.filesDir, CACHE_FILE)
        val fresh = cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS

        if (!forceRefresh && fresh) {
            parse(cache.readText()).getOrNull()?.let { return Result.success(it) }
        }

        val text = downloadText(INDEX_PRIMARY) ?: downloadText(INDEX_FALLBACK)
        if (text == null) {
            // 联网失败时退回旧缓存，总比空白页强。
            if (cache.exists()) {
                parse(cache.readText()).getOrNull()?.let { return Result.success(it) }
            }
            return Result.failure(IllegalStateException("拉取插件列表失败，请检查网络后重试"))
        }

        runCatching { cache.writeText(text) }
        return parse(text)
    }

    private fun parse(text: String): Result<List<MarketPlugin>> = runCatching {
        val root = JSONObject(text)
        buildList {
            root.keys().forEach { id ->
                val item = root.optJSONObject(id) ?: return@forEach
                val repo = item.optString("repo")
                if (repo.isBlank()) return@forEach
                add(
                    MarketPlugin(
                        id = id,
                        displayName = item.optString("display_name").ifBlank { id },
                        description = item.optString("desc").replace("\n", " ").trim(),
                        author = item.optString("author"),
                        repo = repo,
                        tags = buildList {
                            val tags = item.optJSONArray("tags") ?: return@buildList
                            for (i in 0 until tags.length()) add(tags.optString(i))
                        },
                        stars = item.optInt("stars"),
                        version = item.optString("version"),
                    ),
                )
            }
        }.sortedByDescending { it.stars }
    }

    // ---------------------------------------------------------------- 已装插件

    fun listInstalled(paths: ContainerPaths): List<InstalledPlugin> {
        val dir = File(paths.rootfsDir, PLUGINS_RELATIVE)
        val children = dir.listFiles() ?: return emptyList()

        return children.filter { it.isDirectory }.mapNotNull { folder ->
            val meta = File(folder, "metadata.yaml")
            val name = folder.name
            // 内置插件和没 metadata 的目录跳过，避免把半成品列出来。
            if (name.startsWith(".") || !meta.exists()) return@mapNotNull null

            val raw = runCatching { meta.readText() }.getOrNull().orEmpty()
            InstalledPlugin(
                dirName = name,
                displayName = yamlValue(raw, "display_name")
                    ?: yamlValue(raw, "name")
                    ?: name,
                version = yamlValue(raw, "version").orEmpty(),
                description = yamlValue(raw, "desc")
                    ?: yamlValue(raw, "description")
                    ?: "",
            )
        }.sortedBy { it.dirName }
    }

    // ---------------------------------------------------------------- 安装

    /**
     * 安装插件：下载仓库压缩包 → 解压到 data/plugins/<目录名>。
     *
     * 只做最必要的事，失败时保留原有目录不破坏环境。
     */
    fun install(
        paths: ContainerPaths,
        plugin: MarketPlugin,
        onProgress: (String) -> Unit = {},
    ): Result<Unit> = runCatching {
        val slug = repoSlug(plugin.repo) ?: error("插件仓库地址无法识别：${plugin.repo}")
        val dirName = slug.substringAfter('/')
        val target = File(paths.rootfsDir, "$PLUGINS_RELATIVE/$dirName")

        check(!target.exists()) { "这个插件已经装过了（$dirName）" }

        onProgress("正在下载 ${plugin.displayName}…")
        val zipFile = File(paths.tmpDir, "$dirName.zip")
        paths.tmpDir.mkdirs()

        var downloaded = false
        for (branch in listOf("main", "master")) {
            val url = "${GITHUB_PROXY}https://github.com/$slug/archive/refs/heads/$branch.zip"
            if (downloadTo(url, zipFile)) {
                downloaded = true
                break
            }
        }
        check(downloaded) { "下载失败，可能是网络问题或仓库默认分支不是 main/master" }

        onProgress("正在解压…")
        val staging = File(paths.tmpDir, "$dirName-staging")
        staging.deleteRecursively()
        staging.mkdirs()

        unzip(zipFile, staging)
        zipFile.delete()

        // GitHub 的压缩包外层带 `<repo>-<branch>/`，要剥掉这层才能被 AstrBot 识别。
        val payload = resolvePayload(staging)
            ?: run {
                staging.deleteRecursively()
                error("压缩包里没有找到 metadata.yaml，可能不是标准 AstrBot 插件")
            }

        target.parentFile?.mkdirs()
        if (!payload.renameTo(target)) {
            payload.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        } else {
            staging.deleteRecursively()
        }

        check(File(target, "metadata.yaml").exists()) { "安装后没有找到 metadata.yaml" }
        onProgress("已安装 ${plugin.displayName}")
        Unit
    }

    fun uninstall(paths: ContainerPaths, dirName: String): Result<Unit> = runCatching {
        val target = File(paths.rootfsDir, "$PLUGINS_RELATIVE/$dirName")
        check(target.exists()) { "插件目录不存在" }
        check(target.deleteRecursively()) { "删除失败，可能存在只读文件" }
    }

    // ---------------------------------------------------------------- 内部工具

    /** `https://github.com/owner/repo` → `owner/repo` */
    private fun repoSlug(repo: String): String? {
        val cleaned = repo.trim().removeSuffix("/").removeSuffix(".git")
        val match = Regex("""github\.com/([^/]+)/([^/]+)""").find(cleaned) ?: return null
        return "${match.groupValues[1]}/${match.groupValues[2]}"
    }

    /**
     * 找到真正含 metadata.yaml 的那一层。
     * 压缩包首层通常是 `repo-main/`，也可能直接是文件。
     */
    private fun resolvePayload(staging: File): File? {
        if (File(staging, "metadata.yaml").exists()) return staging
        val kids = staging.listFiles()?.filter { it.isDirectory } ?: return null
        kids.firstOrNull { File(it, "metadata.yaml").exists() }?.let { return it }
        // 再深一层：有些仓库把插件放在子目录里
        kids.forEach { kid ->
            kid.listFiles()?.filter { it.isDirectory }?.forEach { inner ->
                if (File(inner, "metadata.yaml").exists()) return inner
            }
        }
        return null
    }

    private fun downloadText(url: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }.getOrNull()

    private fun downloadTo(url: String, target: File): Boolean = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            return false
        }
        connection.inputStream.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        connection.disconnect()
        target.length() > 0
    }.getOrDefault(false)

    private fun unzip(zip: File, destination: File) {
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { stream ->
            var entry = stream.nextEntry
            val rootPath = destination.canonicalPath
            while (entry != null) {
                val name = entry.name
                if (name.isNotBlank()) {
                    val outFile = File(destination, name)
                    // 防路径穿越：压缩包里的 ../ 不能跳出目标目录。
                    if (!outFile.canonicalPath.startsWith(rootPath)) {
                        entry = stream.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output -> stream.copyTo(output) }
                    }
                }
                stream.closeEntry()
                entry = stream.nextEntry
            }
        }
    }

    /** 极简 YAML 取值：只认 `key: value` 单行，插件的 metadata 够用。 */
    private fun yamlValue(raw: String, key: String): String? {
        val regex = Regex("""(?m)^\s*$key\s*:\s*(.+?)\s*$""")
        val value = regex.find(raw)?.groupValues?.getOrNull(1)?.trim() ?: return null
        return value.removeSurrounding("\"").removeSurrounding("'").ifBlank { null }
    }
}
