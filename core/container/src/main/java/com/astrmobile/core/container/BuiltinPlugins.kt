package com.astrmobile.core.container

import android.content.Context
import com.astrmobile.core.common.AppLog
import java.io.File

/**
 * 随 APK 一起发布的内置插件。
 *
 * 为什么要有内置：市场里的记忆插件要么依赖数据库服务，要么每轮都把
 * 相关内容注入 prompt。这个"记忆库"的取舍不一样——只存档、按需检索，
 * 平时一个字都不读。既然逻辑是我们自己定的，就别让它去市场里漂着，
 * 直接跟版本一起走，用户点一下就能用上。
 *
 * 升级策略是覆盖：每次启用都把 assets 里的版本重新写一遍。插件代码
 * 只有几 KB，覆盖比做版本比对简单，也不会出现"装了旧版一直不更新"。
 * 用户自己改过的部分只可能在 data 目录，那里不碰。
 */
object BuiltinPlugins {

    const val MEMORY_VAULT = "astrbot_plugin_memory_vault"

    private const val ASSET_ROOT = "plugins"
    private const val PLUGINS_RELATIVE = "root/data/plugins"

    data class Entry(
        val name: String,
        val displayName: String,
        val assetDir: String,
        val summary: String,
        val note: String,
    )

    val all: List<Entry> = listOf(
        Entry(
            name = MEMORY_VAULT,
            displayName = "记忆库",
            assetDir = "$ASSET_ROOT/memory_vault",
            summary = "对话全文按天存档；要记的事实按分类存成文件",
            note = "平时一个字都不读，只有你问到过去的事，它才去翻。" +
                "常驻成本只有两个工具定义，不聊天的时候它连模型都不碰。",
        ),
    )

    fun isInstalled(paths: ContainerPaths, name: String): Boolean =
        File(paths.rootfsDir, "$PLUGINS_RELATIVE/$name/metadata.yaml").exists()

    /** 把 assets 里的插件写到容器目录，已存在则覆盖。 */
    fun install(context: Context, paths: ContainerPaths, name: String): Result<Unit> =
        runCatching {
            val entry = all.firstOrNull { it.name == name }
                ?: error("没有这个内置插件：$name")

            val target = File(paths.rootfsDir, "$PLUGINS_RELATIVE/$name")
            target.mkdirs()

            val written = copyAssetDir(context, entry.assetDir, target)
            check(written > 0) { "APK 里找不到 ${entry.assetDir}，这不是一个完整的安装包" }
            check(File(target, "metadata.yaml").exists()) { "写入后缺少 metadata.yaml" }

            AppLog.app("内置插件 $name 已写入 ${target.absolutePath}（$written 个文件）")
        }

    /** 递归拷贝 assets 子目录，返回写入的文件数。 */
    private fun copyAssetDir(context: Context, assetPath: String, target: File): Int {
        val children = context.assets.list(assetPath) ?: return 0
        if (children.isEmpty()) {
            // list 返回空数组说明这是个文件而不是目录
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return 1
        }

        target.mkdirs()
        var count = 0
        children.forEach { child ->
            count += copyAssetDir(context, "$assetPath/$child", File(target, child))
        }
        return count
    }
}
