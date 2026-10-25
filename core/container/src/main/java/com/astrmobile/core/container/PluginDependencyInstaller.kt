package com.astrmobile.core.container

import com.astrmobile.core.common.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 在容器里给插件装 Python 依赖。
 *
 * 为什么需要这一步：插件的安装只是把 zip 解开，`pip install` 不会自动跑。
 * 缺依赖时 AstrBot 的报错只有一句"缺少模块 xxx"，日志在几千行里，
 * 用户根本对不上是哪个插件的问题——所以他会在市场里点装、装完发现没反应。
 *
 * 这里不做"自动检测所有插件"：那会在每次启动时触发一堆 pip 调用。
 * 只在用户明确点「装依赖」时执行，命令与输出全部回显。
 */
class PluginDependencyInstaller(
    private val paths: ContainerPaths,
) {

    companion object {
        private const val PLUGINS_RELATIVE = "root/data/plugins"
        private const val REQUIREMENTS = "requirements.txt"
    }

    /** 这个插件是否有 requirements.txt，供界面决定要不要显示「装依赖」。 */
    fun hasRequirements(dirName: String): Boolean =
        FeaturedPlugins.isSafeDirName(dirName) &&
            File(paths.rootfsDir, "$PLUGINS_RELATIVE/$dirName/$REQUIREMENTS").exists()

    /**
     * 执行 `pip install -r requirements.txt`。
     *
     * [onLine] 会收到 pip 的原始输出，界面直接铺在日志区——
     * 卡在编译 wheel 上时，用户至少能看到它在编译什么，而不是干等。
     */
    suspend fun install(
        dirName: String,
        pipIndex: String,
        onLine: suspend (String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 目录名会被拼进 shell 命令，必须挡住空格、分号、反引号
            require(FeaturedPlugins.isSafeDirName(dirName)) { "插件目录名不合法：$dirName" }

            val pluginDir = File(paths.rootfsDir, "$PLUGINS_RELATIVE/$dirName")
            require(pluginDir.isDirectory) { "插件目录不存在，先把它装上" }

            val requirements = File(pluginDir, REQUIREMENTS)
            if (!requirements.exists()) {
                onLine("这个插件没有 $REQUIREMENTS，不需要装依赖。")
                return@runCatching
            }

            require(paths.isToolchainReady()) { "容器工具链还没就绪，先完成首次安装" }

            val containerDir = "/root/data/plugins/$dirName"
            val env = ProotCommand.defaultContainerEnv(paths, "Asia/Shanghai") + mapOf(
                "PIP_INDEX_URL" to pipIndex,
                "PIP_DISABLE_PIP_VERSION_CHECK" to "1",
            )

            // --no-cache-dir：手机上磁盘比下载时间宝贵，缓存在这里只会占地方
            val script = "cd '$containerDir' && pip install --no-cache-dir -r $REQUIREMENTS"
            val command = ProotCommand.build(
                paths = paths,
                workdir = "/root",
                argv = listOf("/bin/bash", "-lc", script),
                containerEnv = env,
            )

            onLine("$ $script")
            val process = ProcessBuilder(command)
                .directory(paths.filesDir)
                .redirectErrorStream(true)
                .apply { ProotCommand.applyHostEnvironment(this, paths) }
                .start()

            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val raw = reader.readLine() ?: break
                        val line = raw.replace(Regex("\u001B\\[[;\\d]*[ -/]*[@-~]"), "").trimEnd()
                        if (line.isNotBlank()) onLine(line)
                    }
                }
                val code = process.waitFor()
                AppLog.app("插件 $dirName 依赖安装退出码 $code")
                check(code == 0) {
                    "pip 退出码 $code。多半是缺系统库（python3-dev / build-essential），" +
                        "或者这个依赖没有 arm64 的现成包。"
                }
                onLine("依赖安装完成，重启 AstrBot 后生效。")
            } finally {
                runCatching { process.destroy() }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLog.app("插件 $dirName 依赖安装失败：${error.message}")
        }
    }
}
