package com.astrmobile.core.container

import com.astrmobile.core.common.AppLog
import com.astrmobile.core.common.RingBuffer
import android.system.Os
import android.system.OsConstants
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

    /**
     * NapCat 的独立看护状态。
     *
     * 容器的"活着"只等于 AstrBot 活着（start.sh 里 exec 的是它，NapCat 是 nohup 后台）。
     * Electron 被系统 OOM 掉以后，proot 不动、AstrBot 不动，光看进程会把"QQ 已掉线"
     * 一直显示成"运行中"。所以这里单独盯 6099，连续失败就只重启 QQ 协议端。
     */
    @Volatile private var containerStartedAt = 0L
    @Volatile private var napcatRestartInFlight = false
    private var napcatDownTicks = 0
    private var lastNapcatRestartAt = 0L

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
                containerStartedAt = System.currentTimeMillis()
                napcatDownTicks = 0
                lastNapcatRestartAt = 0L
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
            napcatDownTicks = 0
            val current = process ?: run {
                // App 重启过、内存里没有进程句柄，但容器可能还在跑（孤儿），也要收掉
                killContainerRemnants()
                _state.value = ContainerState.Stopped
                return@withLock
            }
            _state.value = ContainerState.Stopping
            appendLog(AppLog.container("请求停止容器"))
            runCatching { paths.stopSentinel.writeText("1") }

            // 先温和终止：proot 会把信号转发给容器内的 AstrBot / NapCat。
            // 真机上这一步经常走不通（proot 收到 TERM 后直接退出、tracee 变孤儿），
            // 所以不能靠它保证干净退出 —— 兜底交给 start.sh 启动前的清场。
            runCatching { current.destroy() }
            val exited = waitForExit(current, timeoutMs = 8_000)
            if (!exited) {
                appendLog(AppLog.container("优雅退出超时，强制结束进程"))
                runCatching { current.destroyForcibly() }
                waitForExit(current, timeoutMs = 3_000)
            }

            // proot 死了不代表容器里的进程也死了：它们会变成孤儿，继续占着
            // astrbot.lock 和 6185/6099 端口，下次启动直接报
            // "Cannot acquire lock file"。这里按绝对路径收一遍。
            killContainerRemnants()

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

        // 先收掉上次留下的孤儿 AstrBot / QQ：它们占着 astrbot.lock，
        // 不清理的话起容器只会得到一句 "Cannot acquire lock file"。
        killContainerRemnants()

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

    /**
     * 清理容器遗留进程（AstrBot / QQ / NapCat）。
     *
     * proot 只是 ptrace 的观察者，它被 SIGKILL 后，容器里的进程会被内核 detach
     * 并继续运行（Android 上 `--kill-on-exit` 对此不可靠）。这些孤儿会一直占着
     * `/root/astrbot.lock`，导致下次启动报 "Cannot acquire lock file"。
     *
     * 按绝对路径匹配而不是按名字匹配：宿主上可能有用户的官方 QQ，
     * 只认 `/opt/astrbot/bin`、`/opt/QQ/`、`/root/napcat/` 这种容器内独有的路径。
     */
    private fun killContainerRemnants() {
        val patterns = listOf("/opt/astrbot/bin", "/opt/QQ/", "/root/napcat/", "/root/launcher.sh")
        val pids = runCatching {
            File("/proc").list()?.filter { name -> name.all(Char::isDigit) }
        }.getOrNull().orEmpty()
        if (pids.isEmpty()) return

        val victims = pids.mapNotNull { name ->
            val pid = name.toIntOrNull() ?: return@mapNotNull null
            if (pid == android.os.Process.myPid()) return@mapNotNull null
            // 只读前 512 字节：命令行可能上千字节，读全没有意义
            val cmdline = runCatching {
                File("/proc/$name/cmdline").inputStream().use { stream ->
                    val buffer = ByteArray(512)
                    val read = stream.read(buffer).coerceAtLeast(0)
                    String(buffer, 0, read, Charsets.UTF_8)
                }.replace('\u0000', ' ')
            }.getOrNull().orEmpty()
            if (cmdline.isBlank()) return@mapNotNull null
            pid.takeIf { patterns.any(cmdline::contains) }
        }
        if (victims.isEmpty()) return

        victims.forEach { pid -> runCatching { Os.kill(pid, OsConstants.SIGTERM) } }
        Thread.sleep(600)
        victims.forEach { pid -> runCatching { Os.kill(pid, OsConstants.SIGKILL) } }
        appendLog(AppLog.container("清理容器残留进程 ${victims.size} 个（AstrBot / QQ 未随 proot 退出）"))
        // 进程都死了，锁一定已释放；文件留着只会让下次启动多一次误判
        runCatching { File(paths.rootfsDir, "root/astrbot.lock").delete() }
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
            // 主动停止时我们会关掉输出流，读取线程必然抛 InterruptedIOException。
            // 这是预期行为，不该在日志里显示成一条红色 ERROR 吓人。
            if (_state.value !is ContainerState.Stopping && !userStopRequested) {
                appendLog(AppLog.error("CT", "读取容器输出失败", error))
            }
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
                // 只认 AstrBot：它才是容器的主进程，NapCat 单独活着说明不了容器健康
                healthyTicks = if (astrbotReady) healthyTicks + 1 else 0
                if (healthyTicks >= 30) restartAttempts = 0

                watchNapcat(astrbotReady, napcatReady)
                delay(2_000)
            }
        }
    }

    /**
     * 单独看护 NapCat：6099 连续 [NAPCAT_FAIL_TICKS] 次探测失败就重启 QQ 协议端。
     *
     * 触发条件刻意收紧，避免误杀正常的冷启动：
     * - AstrBot 已就绪（说明容器本体没问题，问题出在 QQ 这一侧）；
     * - 已过宽限期（Electron + QQ 客户端冷启动本身就慢）；
     * - launcher.sh 存在（没装 NapCat 的机器不该被反复重启）。
     */
    private fun watchNapcat(astrbotReady: Boolean, napcatReady: Boolean) {
        if (!autoRestartEnabled) return
        if (napcatReady) {
            napcatDownTicks = 0
            return
        }
        if (!astrbotReady) return
        if (System.currentTimeMillis() - containerStartedAt < NAPCAT_GRACE_MS) return
        // 没装 NapCat（安装失败或用户主动跳过）时，重启也不会有结果
        if (!File(paths.rootfsDir, "root/launcher.sh").exists()) return

        napcatDownTicks++
        if (napcatDownTicks < NAPCAT_FAIL_TICKS) return

        val now = System.currentTimeMillis()
        if (napcatRestartInFlight) return
        if (now - lastNapcatRestartAt < NAPCAT_RESTART_COOLDOWN_MS) return

        napcatDownTicks = 0
        lastNapcatRestartAt = now
        restartNapcat()
    }

    /** 只重启 NapCat，不触碰 AstrBot 与容器本身。 */
    private fun restartNapcat() {
        napcatRestartInFlight = true
        appendLog(
            AppLog.container(
                "NapCat 连续 ${NAPCAT_FAIL_TICKS * 2} 秒无响应，单独重启 QQ 协议端（AstrBot 不受影响）",
            ),
        )
        scope.launch {
            try {
                val command = ProotCommand.build(
                    paths = paths,
                    workdir = "/root",
                    argv = listOf("/bin/bash", "/root/restart-napcat.sh"),
                    containerEnv = ProotCommand.defaultContainerEnv(paths, currentTimezone()),
                    // 这个 proot 是短命进程，退出时不能带走刚拉起的 NapCat
                    killOnExit = false,
                )
                val builder = ProcessBuilder(command)
                    .directory(paths.filesDir)
                    .redirectErrorStream(true)
                ProotCommand.applyHostEnvironment(builder, paths)

                val proc = builder.start()
                proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.filter { it.isNotBlank() }.forEach { appendLog(it) }
                }
                appendLog(AppLog.container("NapCat 重启流程结束，exit=${proc.waitFor()}"))
            } catch (error: Throwable) {
                appendLog(AppLog.error("CT", "NapCat 重启失败", error))
            } finally {
                napcatRestartInFlight = false
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

        /** 2 秒探测一次，连续 30 次（60 秒）失败才认定 NapCat 真的挂了。 */
        private const val NAPCAT_FAIL_TICKS = 30

        /** 容器启动后的宽限期：Electron + QQ 客户端冷启动本身就要半分钟以上。 */
        private const val NAPCAT_GRACE_MS = 120_000L

        /** 两次重启之间至少隔 5 分钟，避免反复拉起把 CPU 和内存打满。 */
        private const val NAPCAT_RESTART_COOLDOWN_MS = 300_000L
    }
}
