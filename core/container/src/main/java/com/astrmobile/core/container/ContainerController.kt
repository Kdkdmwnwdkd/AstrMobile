package com.astrmobile.core.container

import com.astrmobile.core.common.AppLog
import com.astrmobile.core.common.RingBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.time.ZoneId

/**
 * 容器生命周期控制器。
 *
 * 设计上刻意与 ElymBot 的“脚本 + 前台服务”解耦：
 * 这里只负责「进程是否活着 / 端口是否通 / 日志在哪里」，
 * 保活由 Android 前台服务负责，UI 通过 StateFlow 订阅。
 */
class ContainerController(
    private val context: android.content.Context,
    private val paths: ContainerPaths,
    private val logSink: (String) -> Unit = {},
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ContainerState>(ContainerState.Stopped)
    val state: StateFlow<ContainerState> = _state.asStateFlow()

    private val buffer = RingBuffer<String>(capacity = 3000)
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var process: Process? = null
    private var pumpJob: Job? = null
    private var healthJob: Job? = null

    /** 由 UI 在启动前注入的 apt 镜像，默认清华 ubuntu-ports。 */
    var aptMirror: String = RootfsConfigurator.DEFAULT_APT_MIRROR

    /** 异常退出后是否自动重启。开启后息屏被系统杀掉也能自愈。 */
    var autoRestartEnabled: Boolean = true

    private var restartAttempts = 0
    private var userStopRequested = false
    private var restartJob: Job? = null

    /** 日志写盘走单消费者，避免每个调用方都同步 open/close 文件。 */
    private val logQueue = Channel<String>(Channel.UNLIMITED)
    private var lastPublishAt = 0L

    init {
        scope.launch(Dispatchers.IO) {
            for (line in logQueue) {
                runCatching {
                    paths.logDir.mkdirs()
                    // 简单的体积上限：超过 8MB 清空，避免长期运行无限增长
                    if (paths.containerLog.length() > 8L * 1024 * 1024) {
                        paths.containerLog.writeText("")
                    }
                    paths.containerLog.appendText(line + "\n")
                }
            }
        }
    }

    fun refreshInstallState() {
        _state.value = when {
            !paths.isRootfsInstalled() -> ContainerState.NotInstalled
            process?.isAlive == true -> _state.value
            else -> ContainerState.Stopped
        }
    }

    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (process?.isAlive == true) {
                return@withLock Result.success(Unit)
            }
            // 只拦"rootfs 不存在"；可执行入口的缺失交给下面的 BinaryInstaller 自愈，
            // 否则软链断裂会导致"环境不就绪 → 不许启动 → 永远修不好"的死锁。
            if (!paths.isRootfsInstalled()) {
                _state.value = ContainerState.NotInstalled
                return@withLock Result.failure(IllegalStateException("容器环境尚未安装完成"))
            }

            runCatching {
                _state.value = ContainerState.Starting
                userStopRequested = false
                paths.ensureDirectories()

                // 每次启动前重新校验可执行入口：APK 覆盖升级后 nativeLibraryDir 会变，
                // 旧软链会指向已删除的目录，容器会以 "Permission denied" 直接失败。
                val missing = BinaryInstaller(paths).install()
                check(missing.isEmpty()) {
                    "APK 缺少原生组件：${missing.joinToString()}，请确认安装的是 arm64 专用包"
                }

                if (paths.stopSentinel.exists()) paths.stopSentinel.delete()
                prepareRootfsConfig()
                // 每次启动都用 APK 里的最新脚本覆盖容器内版本，
                // 避免"升级了 App 但容器还在跑旧脚本"
                ScriptInjector.inject(context, paths)
                appendLog(AppLog.container("启动容器：${paths.rootfsDir.absolutePath}"))

                val argv = listOf("/bin/bash", "/root/start.sh")
                val command = ProotCommand.build(
                    paths = paths,
                    workdir = "/root",
                    argv = argv,
                    containerEnv = ProotCommand.defaultContainerEnv(paths, currentTimezone()),
                )

                val builder = ProcessBuilder(command)
                    .directory(paths.filesDir)
                    .redirectErrorStream(true)
                ProotCommand.applyHostEnvironment(builder, paths)

                val started = builder.start()
                process = started
                started.safePid()?.let { pid ->
                    runCatching { paths.pidFile.writeText(pid.toString()) }
                }

                pumpJob = scope.launch { pumpOutput(started) }
                startHealthLoop(started)
            }.onFailure { error ->
                appendLog(AppLog.error("CT", "容器启动失败", error))
                _state.value = ContainerState.Failed(error.message ?: "容器启动失败")
            }
        }
    }

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            userStopRequested = true
            restartJob?.cancel()
            restartJob = null
            restartAttempts = 0
            val current = process ?: run {
                _state.value = ContainerState.Stopped
                return@withLock
            }
            _state.value = ContainerState.Stopping
            appendLog(AppLog.container("请求停止容器"))
            runCatching { paths.stopSentinel.writeText("1") }

            // 先温和终止：proot 会把信号转发给容器内的 AstrBot / NapCat。
            runCatching { current.destroy() }
            val exited = waitForExit(current, timeoutMs = 8_000)
            if (!exited) {
                appendLog(AppLog.container("优雅退出超时，强制结束进程"))
                runCatching { current.destroyForcibly() }
                waitForExit(current, timeoutMs = 3_000)
            }

            cleanupAfterExit(current)
        }
    }

    /** 杀掉上次遗留的进程（App 被系统杀死后常见）。 */
    suspend fun killStaleProcess() {
        // 容器正在启动/运行时不清理，避免误杀本次拉起的进程
        val currentState = _state.value
        if (currentState is ContainerState.Starting || currentState is ContainerState.Running) {
            return
        }

        val pid = runCatching { paths.pidFile.readText().trim().toLong() }.getOrNull() ?: return
        if (pid <= 0) {
            runCatching { paths.pidFile.delete() }
            return
        }

        // pid 会被系统复用：必须先确认这个 pid 确实是 proot，否则可能误杀无关进程。
        val cmdline = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull()
        if (cmdline == null || !cmdline.contains("proot")) {
            appendLog(AppLog.container("跳过清理：pid=$pid 已不是容器进程"))
            runCatching { paths.pidFile.delete() }
            return
        }

        runCatching {
            Runtime.getRuntime().exec(arrayOf("/system/bin/kill", "-9", pid.toString()))
            appendLog(AppLog.container("清理遗留容器进程 pid=$pid"))
        }
        runCatching { paths.pidFile.delete() }
    }

    /** 允许外部（如安装流程）把日志并入统一时间线，保证导出时能看到完整现场。 */
    fun pushLog(line: String) = appendLog(line)

    fun snapshotLogs(): List<String> = _logs.value

    fun clearLogs() {
        buffer.clear()
        _logs.value = emptyList()
        runCatching { paths.containerLog.writeText("") }
    }

    fun shutdown() {
        scope.launch {
            runCatching { stop() }
        }
    }

    private fun prepareRootfsConfig() {
        RootfsConfigurator.apply(paths, aptMirror)
    }

    private fun pumpOutput(target: Process) {
        val stream = target.inputStream.bufferedReader(Charsets.UTF_8)
        try {
            stream.useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        appendLog(line)
                    }
                }
            }
        } catch (error: Throwable) {
            appendLog(AppLog.error("CT", "读取容器输出失败", error))
        } finally {
            val exit = runCatching { target.exitValue() }.getOrDefault(-1)
            appendLog(AppLog.container("容器进程退出，exit=$exit"))
            if (_state.value !is ContainerState.Stopping) {
                when {
                    exit == 0 -> _state.value = ContainerState.Stopped
                    shouldAutoRestart() -> scheduleRestart(exit)
                    else -> _state.value = ContainerState.Failed(
                        "容器进程异常退出（exit=$exit），请查看日志",
                    )
                }
            }
            cleanupAfterExit(target)
        }
    }

    private fun shouldAutoRestart(): Boolean =
        autoRestartEnabled &&
            !userStopRequested &&
            restartAttempts < MAX_RESTART_ATTEMPTS &&
            paths.isRootfsInstalled()

    /**
     * 指数退避重启：5s → 20s → 60s。
     * 只对“异常退出”生效；用户主动停止不会被拉起来。
     */
    private fun scheduleRestart(exitCode: Int) {
        restartAttempts++
        val delaySeconds = when (restartAttempts) {
            1 -> 5
            2 -> 20
            else -> 60
        }
        _state.value = ContainerState.Restarting(
            attempt = restartAttempts,
            maxAttempts = MAX_RESTART_ATTEMPTS,
            delaySeconds = delaySeconds,
            lastReason = "exit=$exitCode",
        )
        appendLog(
            AppLog.container(
                "容器异常退出（exit=$exitCode），${delaySeconds}s 后执行第 ${restartAttempts}/$MAX_RESTART_ATTEMPTS 次自动重启",
            ),
        )
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(delaySeconds * 1000L)
            if (!userStopRequested) {
                appendLog(AppLog.container("开始自动重启容器"))
                start()
            }
        }
    }

    private fun startHealthLoop(target: Process) {
        healthJob?.cancel()
        healthJob = scope.launch {
            var astrbotReady = false
            var napcatReady = false
            var healthyTicks = 0
            while (isActive && target.isAlive) {
                astrbotReady = probePort(paths.astrbotPort)
                napcatReady = probePort(paths.napcatPort)
                if (_state.value is ContainerState.Starting ||
                    _state.value is ContainerState.Running
                ) {
                    _state.value = ContainerState.Running(astrbotReady, napcatReady)
                }
                // 稳定运行 60s 后清零重启计数，避免历史故障导致后续无法自愈
                healthyTicks = if (astrbotReady || napcatReady) healthyTicks + 1 else 0
                if (healthyTicks >= 30) restartAttempts = 0
                delay(2_000)
            }
        }
    }

    private fun probePort(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 400)
            true
        }
    }.getOrDefault(false)

    private fun appendLog(line: String) {
        buffer.add(line)
        logQueue.trySend(line)

        // 日志流做 120ms 节流：apt/dpkg 每秒数百行时，避免每行都复制 3000 项列表并触发一次重组
        val now = System.currentTimeMillis()
        if (now - lastPublishAt >= 120L) {
            lastPublishAt = now
            _logs.value = buffer.snapshot()
        }
        logSink(line)
    }

    /** 日志停止输出时补一次发布，保证最后几行不会滞留在缓冲区里。 */
    private fun flushLogs() {
        lastPublishAt = 0L
        _logs.value = buffer.snapshot()
    }

    private fun waitForExit(target: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!target.isAlive) return true
            Thread.sleep(120)
        }
        return !target.isAlive
    }

    private fun cleanupAfterExit(target: Process? = null) {
        // 只有"当前进程"退出才清理状态。否则停止后立刻重启时，
        // 旧进程的 finally 会把新进程的 healthJob / pidFile / 状态一起清掉。
        if (target != null && process !== target) return

        healthJob?.cancel()
        healthJob = null
        process = null
        pumpJob = null
        runCatching { paths.pidFile.delete() }

        val current = _state.value
        if (current is ContainerState.Starting ||
            current is ContainerState.Running ||
            current is ContainerState.Stopping
        ) {
            _state.value = ContainerState.Stopped
        }
        flushLogs()
    }

    private fun currentTimezone(): String = runCatching {
        ZoneId.systemDefault().id
    }.getOrDefault("Asia/Shanghai")

    /**
     * Android 的 java.lang.Process 直到较新 API 才暴露 pid()，
     * 这里用反射兜底：拿不到就算了，不影响启停（启停走 Process 引用）。
     */
    private fun Process.safePid(): Long? = runCatching {
        val method = javaClass.getMethod("pid")
        (method.invoke(this) as? Number)?.toLong()
    }.getOrNull()

    companion object {
        private const val MAX_RESTART_ATTEMPTS = 3
    }
}
