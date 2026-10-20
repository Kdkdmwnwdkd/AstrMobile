package com.astrmobile.core.container

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** 自检项的结论。 */
enum class CheckLevel { OK, WARN, FAIL }

/**
 * 一条自检结果。
 *
 * @param action 不通过时该做什么，直接写成人话，不要「请检查配置」这种废话。
 */
data class CheckItem(
    val title: String,
    val level: CheckLevel,
    val detail: String,
    val action: String? = null,
)

/**
 * 运行状态自检。
 *
 * 目的很单一：让用户一眼看出「现在能不能用」，以及不能用时卡在哪一步。
 * 每一项都直接读事实（状态、端口、配置文件），不做推测。
 */
object Diagnostics {

    /** TCP 探活，判断容器内的服务是否真的在监听。 */
    fun probePort(port: Int, timeoutMs: Int = 800): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    /** 容器与端口。 */
    fun checkRuntime(state: ContainerState): List<CheckItem> {
        val items = mutableListOf<CheckItem>()

        items += when (state) {
            is ContainerState.Running -> {
                val astrbot = probePort(6185)
                val napcat = probePort(6099)
                CheckItem(
                    title = "容器",
                    level = if (astrbot && napcat) CheckLevel.OK else CheckLevel.WARN,
                    detail = "运行中 · AstrBot ${if (astrbot) "在线" else "未响应"} · NapCat ${if (napcat) "在线" else "未响应"}",
                    action = if (!astrbot) "AstrBot 还在启动，等 30 秒再看；一直没起来就去看实时日志" else null,
                )
            }
            is ContainerState.Starting -> CheckItem(
                title = "容器",
                level = CheckLevel.WARN,
                detail = "正在启动",
                action = "等一会儿再点「重新检查」",
            )
            is ContainerState.Failed -> CheckItem(
                title = "容器",
                level = CheckLevel.FAIL,
                detail = state.reason,
                action = "看实时日志最后 30 行，那里有失败原因",
            )
            is ContainerState.Restarting -> CheckItem(
                title = "容器",
                level = CheckLevel.WARN,
                detail = "异常退出，第 ${state.attempt}/${state.maxAttempts} 次自动重启" +
                    "（${state.delaySeconds} 秒后）",
                action = "原因：${state.lastReason}。反复重启就看实时日志",
            )
            ContainerState.Stopping -> CheckItem(
                title = "容器",
                level = CheckLevel.WARN,
                detail = "正在停止",
                action = "等几秒再点「重新检查」",
            )
            ContainerState.Stopped, ContainerState.NotInstalled -> CheckItem(
                title = "容器",
                level = CheckLevel.FAIL,
                detail = "未运行",
                action = "回到控制台点「启动机器人」",
            )
            else -> CheckItem(
                title = "容器",
                level = CheckLevel.WARN,
                detail = "状态未知",
            )
        }

        return items
    }

    /** 模型配置。 */
    fun checkModel(paths: ContainerPaths): CheckItem {
        val models = runCatching { ModelConfigRepository.read(paths) }.getOrDefault(emptyList())
        val enabled = models.filter { it.enabled }
        return when {
            enabled.isEmpty() -> CheckItem(
                title = "对话模型",
                level = CheckLevel.FAIL,
                detail = "没有可用模型，收到消息也不会回复",
                action = "去「对话模型」填一个，保存后重启",
            )
            else -> CheckItem(
                title = "对话模型",
                level = CheckLevel.OK,
                detail = "已配置 ${enabled.first().model}",
            )
        }
    }

    /** QQ 侧：NapCat 是否在跑、QQ 是否已登录。 */
    suspend fun checkQq(paths: ContainerPaths, napcatPort: Int = 6099): CheckItem {
        if (!probePort(napcatPort)) {
            return CheckItem(
                title = "QQ 登录",
                level = CheckLevel.WARN,
                detail = "NapCat 还没起来，查不了登录状态",
                action = "等容器启动完成后再检查；一直这样就看日志里 [start] 开头那几行",
            )
        }

        val api = NapCatApi(napcatPort)
        val tokens = listOfNotNull(
            NapCatApi.DEFAULT_TOKEN,
            runCatching { NapCatConfigReader.readToken(paths) }.getOrNull(),
        ).distinct()

        var credential: String? = null
        var lastError = ""
        for (token in tokens) {
            val result = api.login(token)
            if (result.isSuccess) {
                credential = result.getOrNull()
                break
            }
            lastError = result.exceptionOrNull()?.message.orEmpty()
        }

        if (credential.isNullOrBlank()) {
            return CheckItem(
                title = "QQ 登录",
                level = CheckLevel.WARN,
                detail = "连不上 NapCat 控制接口（${lastError.ifBlank { "口令不匹配" }}）",
                action = "重启一次容器；仍不行就把这行发我",
            )
        }

        val status = api.checkStatus(credential)
            ?: return CheckItem(
                title = "QQ 登录",
                level = CheckLevel.WARN,
                detail = "读取登录状态失败",
                action = "重启容器后再试",
            )

        return when {
            status.isLogin -> CheckItem(
                title = "QQ 登录",
                level = CheckLevel.OK,
                detail = "已登录并在线",
            )
            status.isOffline -> CheckItem(
                title = "QQ 登录",
                level = CheckLevel.FAIL,
                detail = "账号掉线了",
                action = "去「QQ 扫码登录」重新扫一次",
            )
            status.loginPhase == "qrcode_scanned" ||
                status.loginPhase == "initializing" -> CheckItem(
                    title = "QQ 登录",
                    level = CheckLevel.WARN,
                    detail = "已扫码，正在确认登录",
                )
            else -> CheckItem(
                title = "QQ 登录",
                level = CheckLevel.FAIL,
                detail = "还没登录",
                action = "去「QQ 扫码登录」用手机 QQ 扫码",
            )
        }
    }

    /** 插件。 */
    fun checkPlugins(paths: ContainerPaths): CheckItem {
        val plugins = runCatching { PluginRepository.listInstalled(paths) }.getOrDefault(emptyList())
        val names = plugins.take(3).joinToString("、") { it.displayName.ifBlank { it.dirName } }
        return when {
            plugins.isEmpty() -> CheckItem(
                title = "插件",
                level = CheckLevel.WARN,
                detail = "一个都没装（不影响基础对话）",
                action = "想要表情包、记忆、主动聊天，去「插件」装",
            )
            else -> CheckItem(
                title = "插件",
                level = CheckLevel.OK,
                detail = "已装 ${plugins.size} 个：$names${if (plugins.size > 3) " 等" else ""}",
            )
        }
    }

    /**
     * 消息通道。
     *
     * 没有直接的「连接状态」接口，但 AstrBot 连上 NapCat 时会在日志里留下
     * aiocqhttp 相关的记录，从最近的日志里找它是最可靠的旁证。
     */
    fun checkChannel(logs: List<String>): CheckItem {
        val tail = logs.takeLast(300)
        // 只认「aiocqhttp/onebot」且带连接语义的行。
        // 不能只看 "bot"——AstrBot 自己的名字里就有 bot，会全部误判成已连接。
        val connected = tail.any { line ->
            val lower = line.lowercase()
            (lower.contains("aiocqhttp") || lower.contains("onebot")) &&
                (lower.contains("connect") || lower.contains("已连接") ||
                    lower.contains("online") || lower.contains("session"))
        }
        val platformReady = tail.any { it.contains("AstrBot started") }

        return when {
            connected -> CheckItem(
                title = "消息通道",
                level = CheckLevel.OK,
                detail = "AstrBot 已收到 QQ 侧连接",
            )
            platformReady -> CheckItem(
                title = "消息通道",
                level = CheckLevel.WARN,
                detail = "AstrBot 已启动，但还没看到 QQ 连上来的记录",
                action = "确认扫码登录成功；仍不通就在群里 @ 它试一次，再看日志",
            )
            else -> CheckItem(
                title = "消息通道",
                level = CheckLevel.WARN,
                detail = "日志里还没看到 AstrBot 启动完成",
                action = "等启动完成，或看实时日志",
            )
        }
    }

    /** 配置文件是否齐全（防止「装了一半」）。 */
    fun checkInstall(paths: ContainerPaths): CheckItem {
        val rootfs = paths.isRootfsInstalled()
        val config = File(paths.rootfsDir, "root/data/cmd_config.json").exists()
        return when {
            !rootfs -> CheckItem(
                title = "环境",
                level = CheckLevel.FAIL,
                detail = "Ubuntu 环境没装好",
                action = "回到安装向导重新安装",
            )
            !config -> CheckItem(
                title = "环境",
                level = CheckLevel.WARN,
                detail = "AstrBot 还没生成配置（首次启动会自动建）",
                action = "启动一次容器",
            )
            else -> CheckItem(
                title = "环境",
                level = CheckLevel.OK,
                detail = "Ubuntu 与 AstrBot 配置就绪",
            )
        }
    }

    /** 把结果压成一句结论。 */
    fun summary(items: List<CheckItem>): String {
        val failed = items.count { it.level == CheckLevel.FAIL }
        val warned = items.count { it.level == CheckLevel.WARN }
        return when {
            failed == 0 && warned == 0 -> "全部就绪，去群里 @ 它试试"
            failed == 0 -> "基本可用，有 $warned 项待处理"
            else -> "有 $failed 项没通过，先解决标红的那条"
        }
    }
}
