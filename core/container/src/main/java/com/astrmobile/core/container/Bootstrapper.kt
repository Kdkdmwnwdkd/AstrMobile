package com.astrmobile.core.container

import android.content.Context
import com.astrmobile.core.common.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 首次安装编排器。
 *
 * 流程：工具链 → rootfs → 脚本注入 → 容器内 bootstrap（apt / uv / AstrBot / NapCat）。
 * 每一步都落日志，失败时保留现场，允许用户重试而不是从头再来。
 */
class Bootstrapper(
    private val context: Context,
    private val paths: ContainerPaths,
) {

    sealed interface Stage {
        data object Idle : Stage
        data class Running(val title: String, val progress: Float, val detail: String) : Stage
        data object Done : Stage
        data class Failed(val title: String, val detail: String) : Stage
    }

    private val rootfsInstaller = RootfsInstaller(context, paths)
    private val binaryInstaller = BinaryInstaller(paths)

    fun isReady(): Boolean = paths.isRootfsInstalled() && paths.isBootstrapFinished()

    /**
     * 执行完整安装。`onStage` 会在 IO 线程被频繁回调，UI 侧需自行切主线程。
     */
    suspend fun install(
        onStage: suspend (Stage) -> Unit,
        onLine: suspend (String) -> Unit,
        pipIndex: String = "https://pypi.tuna.tsinghua.edu.cn/simple",
        githubProxy: String = "https://ghfast.top/",
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                paths.ensureDirectories()
                onStage(Stage.Running("检查工具链", 0.02f, "映射 proot / busybox"))

                val missing = binaryInstaller.install()
                check(missing.isEmpty()) {
                    "APK 缺少原生组件：${missing.joinToString()}。请确认安装的是 arm64 设备专用包。"
                }

                if (!paths.isRootfsInstalled()) {
                    onStage(Stage.Running("解压系统环境", 0.05f, "首次安装需要 3-8 分钟"))
                    val result = rootfsInstaller.install { fraction, detail ->
                        // 解压占整体进度的 5% - 35%
                        val mapped = 0.05f + fraction * 0.30f
                        kotlinx.coroutines.runBlocking {
                            onStage(Stage.Running("解压系统环境", mapped, detail))
                        }
                    }
                    result.getOrThrow()
                } else {
                    onStage(Stage.Running("解压系统环境", 0.35f, "已存在，跳过"))
                }

                onStage(Stage.Running("注入启动脚本", 0.37f, "写入 bootstrap / start"))
                injectScripts()

                onStage(Stage.Running("安装 AstrBot 与 NapCat", 0.40f, "需要联网，可能耗时较久"))
                val exitCode = runBootstrap(
                    onLine = onLine,
                    pipIndex = pipIndex,
                    githubProxy = githubProxy,
                ) { fraction ->
                    kotlinx.coroutines.runBlocking {
                        onStage(Stage.Running("安装 AstrBot 与 NapCat", fraction, "下载依赖中，请保持屏幕常亮"))
                    }
                }

                check(exitCode == 0) { "安装脚本退出码 $exitCode，请查看日志中的报错行后重试" }

                paths.markBootstrapFinished()
                onStage(Stage.Done)
            }.onFailure { error ->
                onStage(Stage.Failed("安装失败", error.message ?: error.javaClass.simpleName))
            }
        }

    private fun injectScripts() {
        listOf("bootstrap.sh", "start.sh").forEach { name ->
            val content = context.assets.open("scripts/$name").use { it.readBytes() }
            val target = File(paths.rootfsDir, "root/$name")
            target.parentFile?.mkdirs()
            target.writeBytes(content)
            runCatching { android.system.Os.chmod(target.absolutePath, 0b111101101) }
            // 同时留一份到 assets 目录，方便用户在日志里对照排查。
            runCatching {
                val copy = File(paths.assetsDir, name)
                copy.writeBytes(content)
            }
        }
    }

    private suspend fun runBootstrap(
        onLine: suspend (String) -> Unit,
        pipIndex: String,
        githubProxy: String,
        onProgress: (Float) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val env = ProotCommand.defaultContainerEnv(paths, "Asia/Shanghai") + mapOf(
            "PIP_INDEX_URL" to pipIndex,
            "UV_DEFAULT_INDEX" to pipIndex,
            "GITHUB_PROXY" to githubProxy,
        )
        val command = ProotCommand.build(
            paths = paths,
            workdir = "/root",
            argv = listOf("/bin/bash", "/root/bootstrap.sh"),
            containerEnv = env,
        )

        val builder = ProcessBuilder(command)
            .directory(paths.filesDir)
            .redirectErrorStream(true)
        ProotCommand.applyHostEnvironment(builder, paths)

        val process = builder.start()
        val logFile = paths.installLog
        logFile.parentFile?.mkdirs()

        var stepCounter = 0
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { raw ->
                val line = raw.stripAnsi()
                if (line.isBlank()) return@forEach
                kotlinx.coroutines.runBlocking { onLine(line) }
                runCatching { logFile.appendText(line + "\n") }

                if (line.contains("[bootstrap]")) {
                    stepCounter++
                    // 安装脚本共 6 步，映射到 40% - 95%
                    val fraction = (0.40f + (stepCounter.coerceAtMost(6) / 6f) * 0.55f)
                    onProgress(fraction)
                }
            }
        }
        process.waitFor()
    }

    private fun String.stripAnsi(): String =
        replace(Regex("\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
}
