package com.astrmobile.core.container

/**
 * 决定"什么时候该发告警通知"。
 *
 * 手机上跑机器人，人不在跟前时最怕的不是报错，是**报错了没人知道**：
 * QQ 掉线半天、容器反复重启，等发现时已经错过一堆消息。
 *
 * 这里刻意做成纯逻辑：输入状态 + 当前时间，输出该发什么通知、该撤什么通知。
 * 不碰 Android，才能把"掉线多久算掉线""重启几次才吵人"这些规则写成单测。
 *
 * 三条设计约束：
 * - **降噪**：同一个告警不重复弹；持续状态每 10 分钟最多提一次。
 * - **可恢复**：状态恢复后自动撤销对应通知，不留"僵尸红点"。
 * - **只报异常**：正常启停不打扰。"启动中"不算异常，"运行失败"才算。
 */
class AlertPolicy(
    /** NapCat 掉线持续多久才提醒。秒级抖动（心跳重连）不该吵人。 */
    private val offlineThresholdMs: Long = 120_000,
    /** 同一告警的重复提醒间隔。 */
    private val repeatSuppressMs: Long = 10 * 60_000,
) {

    sealed interface Effect {
        data class Raise(
            val key: String,
            val title: String,
            val body: String,
            val critical: Boolean,
        ) : Effect

        data class Clear(val key: String) : Effect
    }

    private var qqDownSince: Long? = null
    private val raisedAt = mutableMapOf<String, Long>()

    fun update(state: ContainerState, now: Long): List<Effect> = buildList {
        when (state) {
            is ContainerState.Failed -> {
                qqDownSince = null
                addAll(clearExcept("failed"))
                maybeRaise(KEY_FAILED, "机器人运行失败", state.reason, critical = true, now = now)
            }

            is ContainerState.Restarting -> {
                qqDownSince = null
                addAll(clearExcept("restarting"))
                maybeRaise(
                    KEY_RESTARTING,
                    "机器人异常退出，正在重启",
                    "第 ${state.attempt}/${state.maxAttempts} 次重试，${state.delaySeconds} 秒后开始。原因：${state.lastReason}",
                    critical = false,
                    now = now,
                )
            }

            is ContainerState.Running -> {
                if (state.napcatReady) {
                    qqDownSince = null
                    addAll(clearExcept(null))
                } else {
                    addAll(clearExcept("qq_offline"))
                    // 容器活着但 QQ 没连上：先等，超过阈值再报——心跳重连很常见，秒级抖动不该吵
                    val since = qqDownSince ?: now.also { qqDownSince = it }
                    if (now - since >= offlineThresholdMs) {
                        maybeRaise(
                            KEY_QQ_OFFLINE,
                            "QQ 掉线了",
                            "容器还在运行，但 QQ 协议端已经 ${(now - since) / 60_000} 分钟没有连上。打开 App 看一眼，可能需要重新扫码。",
                            critical = true,
                            now = now,
                        )
                    }
                }
            }

            ContainerState.Starting, ContainerState.Stopping -> {
                qqDownSince = null
                addAll(clearExcept(null))
            }

            ContainerState.Stopped, ContainerState.NotInstalled -> {
                qqDownSince = null
                addAll(clearExcept(null))
            }
        }
    }

    /** 撤回除 [keep] 之外所有已发出的通知，并清掉抑制记录。 */
    private fun clearExcept(keep: String?): List<Effect> =
        raisedAt.keys.filter { it != keep }.map { key ->
            raisedAt.remove(key)
            Effect.Clear(key)
        }

    private fun MutableList<Effect>.maybeRaise(
        key: String,
        title: String,
        body: String,
        critical: Boolean,
        now: Long,
    ) {
        val last = raisedAt[key]
        if (last != null && now - last < repeatSuppressMs) return

        raisedAt[key] = now
        add(Effect.Raise(key, title, body, critical))
    }

    companion object {
        const val KEY_FAILED = "failed"
        const val KEY_RESTARTING = "restarting"
        const val KEY_QQ_OFFLINE = "qq_offline"
    }
}
