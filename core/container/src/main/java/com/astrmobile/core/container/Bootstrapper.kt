package com.astrmobile.core.container

import android.content.Context
import com.astrmobile.core.common.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 首次安装编排器。
 *
 * 流程：工具链 → 系统包（下载/复用本地）→ 解压 → 脚本注入 → 容器内 bootstrap。
 * 全程不做阻塞式桥接：回调本身就是 suspend，取消能正常传播到安装协程。
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

    private val rootfsProvider = RootfsProvider(context, paths)
    private val rootfsInstaller = RootfsInstaller(paths)
    private val binaryInstaller = BinaryInstaller(paths)

    @Volatile
    private var activeProcess: Process? = null

    @Volatile
    private var cancelled = false

    fun isReady(): Boolean = paths.isRootfsInstalled() && paths.isBootstrapFinished()

    /** 用户主动取消：杀掉安装脚本，让协程从 readLine 上退出。 */
    fun cancel() {
        cancelled = true
        runCatching { activeProcess?.destroyForcibly() }
        activeProcess = null
    }

    suspend fun install(
        onStage: suspend (Stage) -> Unit,
        onLine: suspend (String) -> Unit,
        aptMirror: String = RootfsConfigurator.DEFAULT_APT_MIRROR,
        pipIndex: String = "https://pypi.tuna.tsinghua.edu.cn/simple",
        githubProxy: String = "https://ghfast.top/",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        cancelled = false
        try {
            paths.ensureDirectories()
            onStage(Stage.Running("检查工具链", 0.02f, "映射 proot / busybox"))

            val missing = binaryInstaller.install()
            check(missing.isEmpty()) {
                "APK 缺少原生组件：${missing.joinToString()}。请确认安装的是 arm64 设备专用包。"
            }

            if (!paths.isRootfsInstalled()) {
                if (rootfsProvider.hasLocalArchive()) {
                    onStage(Stage.Running("准备系统环境", 0.05f, "本地已有安装包，无需下载"))
                } else {
                    onStage(Stage.Running("下载系统环境", 0.05f, "约 61MB，请保持网络通畅"))
                }

                val archive = rootfsProvider.acquire { progress ->
                    val fraction = if (progress.fraction >= 0f) progress.fraction else 0f
                    val detail = buildString {
                        append(progress.label)
                        if (progress.attemptCount > 1) {
                            append("，源 ${progress.attempt}/${progress.attemptCount}")
                        }
                        if (progress.resumed) append("，续传中")
                    }
                    onStage(
                        Stage.Running("下载系统环境", 0.05f + fraction * 0.13f, detail),
                    )
                }.getOrThrow()

                onStage(Stage.Running("解压系统环境", 0.18f, "首次安装需要 3-8 分钟"))
                rootfsInstaller.install(archive) { fraction, detail ->
                    onStage(Stage.Running("解压系统环境", 0.18f + fraction * 0.17f, detail))
                }.getOrThrow()
            } else {
                onStage(Stage.Running("解压系统环境", 0.35f, "已存在，跳过"))
            }

            onStage(Stage.Running("注入启动脚本", 0.37f, "写入网络与 apt 源配置"))
            injectScripts()
            // 必须在跑 bootstrap 之前写好：自带 resolv.conf 指向 127.0.0.53，
            // 容器里没有 systemd-resolved，不覆盖就会 DNS 解析失败、apt 全面超时。
            RootfsConfigurator.apply(paths, aptMirror)

            onStage(Stage.Running("安装 AstrBot 与 NapCat", 0.40f, "需要联网，可能耗时较久"))
            val exitCode = runBootstrap(
                onLine = onLine,
                pipIndex = pipIndex,
                githubProxy = githubProxy,
                onProgress = { fraction ->
                    onStage(
                        Stage.Running(
                            "安装 AstrBot 与 NapCat",
                            fraction,
                            "下载依赖中，请保持屏幕常亮",
                        ),
                    )
                },
            )

            check(exitCode == 0) { "安装脚本退出码 $exitCode，请查看日志中的报错行后重试" }

            paths.markBootstrapFinished()
            onStage(Stage.Done)
            Result.success(Unit)
        } catch (error: Throwable) {
            if (cancelled || error is CancellationException) {
                AppLog.app("安装已被用户取消")
                // 协程已取消，普通挂起调用会立刻再次抛出；界面状态必须照常复位
                withContext(NonCancellable) { onStage(Stage.Idle) }
                Result.failure(IllegalStateException("已取消安装"))
            } else {
                AppLog.app("安装失败：${error.message}")
                onStage(Stage.Failed("安装失败", error.message ?: error.javaClass.simpleName))
                Result.failure(error)
            }
        }
    }

    /**
     * 用本地文件替代下载。
     *
     * 给两种情况用：镜像全挂时的兜底（用户从电脑下好、用数据线或网盘传到手机），
     * 以及内网批量部署。校验不过会直接失败，不会留下一个坏包等下次解压才暴露。
     */
    fun adoptImportedRootfs(file: File): Result<Unit> =
        rootfsProvider.adoptImportedFile(file).map { Unit }

    private fun injectScripts() {
        ScriptInjector.inject(context, paths)
        // 额外留一份到 assets 目录，方便用户在日志里对照排查
        listOf("bootstrap.sh", "start.sh").forEach { name ->
            runCatching {
                val content = File(paths.rootfsDir, "root/$name").readBytes()
                File(paths.assetsDir, name).writeBytes(content)
            }
        }
    }

    private suspend fun runBootstrap(
        onLine: suspend (String) -> Unit,
        pipIndex: String,
        githubProxy: String,
        onProgress: suspend (Float) -> Unit,
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
        activeProcess = process
        val logFile = paths.installLog
        logFile.parentFile?.mkdirs()

        var stepCounter = 0
        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        try {
            while (true) {
                val raw = reader.readLine() ?: break
                val line = raw.stripAnsi()
                if (line.isBlank()) continue

                onLine(line)
                runCatching { logFile.appendText(line + "\n") }

                if (line.contains("[bootstrap]")) {
                    stepCounter++
                    // 安装脚本共 6 步，映射到 40% - 95%
                    onProgress(0.40f + (stepCounter.coerceAtMost(6) / 6f) * 0.55f)
                }
            }
        } finally {
            runCatching { reader.close() }
            activeProcess = null
        }
        process.waitFor()
    }

    private fun String.stripAnsi(): String =
        replace(Regex("\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
}
