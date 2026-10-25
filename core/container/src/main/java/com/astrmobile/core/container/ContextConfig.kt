package com.astrmobile.core.container

import org.json.JSONObject

/**
 * 上下文压缩设置——也就是「为什么聊同样久，别人花一块，我花几百块」的开关。
 *
 * AstrBot 的默认值是这样的：
 *
 * ```json
 * "compression": { "max_turns": -1, "overflow_strategy": "llm_compress", ... }
 * ```
 *
 * `max_turns = -1` 表示**不限轮数**。而每一轮对话都会把整段历史重新发给模型，
 * 所以单次请求的成本是随轮数线性涨的，一天下来的总消耗接近**平方级**：
 * 第 30 轮那一次请求，光历史就可能几万 token。
 *
 * `llm_compress` 表示"撑到窗口上限才用模型总结"，而总结本身是一次付费调用。
 *
 * 这两项都改成保守值之后，同样的聊天量通常能降一个数量级。
 */
data class ContextSettings(
    /** 保留最近几轮。<= 0 表示不限，和 AstrBot 的 -1 同义。 */
    val maxTurns: Int = -1,
    /** 超出上限时怎么办。 */
    val overflowStrategy: String = STRATEGY_LLM_COMPRESS,
) {
    val unlimited: Boolean get() = maxTurns <= 0

    val strategyLabel: String
        get() = when (overflowStrategy) {
            STRATEGY_TRUNCATE -> "直接丢掉最老的几轮（不花钱）"
            else -> "让模型总结旧对话（每次总结都要付费）"
        }

    companion object {
        const val STRATEGY_TRUNCATE = "truncate_by_turns"
        const val STRATEGY_LLM_COMPRESS = "llm_compress"

        /**
         * 可选的保留轮数。0 用"不限"，与 AstrBot 的 -1 对应。
         *
         * 这里的「一轮」是 AstrBot 的定义：一条用户消息 + 它的回复
         * （含中间的工具调用），不是"一条消息"。所以 10 轮 = 10 个完整来回。
         *
         * 不提供 3/5 这种极端值：设成 3 轮之后它连"刚才说到哪"都接不上，
         * 用户会以为是模型变笨了，而不是自己把记忆砍了。
         */
        val TURN_OPTIONS: List<Int> = listOf(10, 20, 30, 0)

        /** 每档大致覆盖多少内容，帮用户判断"够不够"。 */
        fun turnHint(turns: Int): String = when {
            turns <= 0 -> "整段历史都带上，聊越久越贵"
            turns <= 10 -> "够接住当前话题，跨话题的旧事会忘"
            turns <= 20 -> "能记住一次完整讨论，日常够用"
            else -> "长对话友好，单次成本约为 10 轮的两三倍"
        }

        fun turnLabel(turns: Int): String = if (turns <= 0) "不限" else "$turns 轮"
    }
}

object ContextConfig {

    private const val KEY_TRUNCATE = "trim_turns"

    fun read(config: JSONObject): ContextSettings {
        val compression = config.optJSONObject("agent_runner")
            ?.optJSONObject("config")
            ?.optJSONObject("compression")
            ?: return ContextSettings()

        return ContextSettings(
            maxTurns = compression.optInt("max_turns", -1),
            overflowStrategy = compression.optString("overflow_strategy")
                .ifBlank { ContextSettings.STRATEGY_LLM_COMPRESS },
        )
    }

    /**
     * 只改 compression 里的两个键，其余原样保留。
     *
     * `PUT /api/v1/system-config` 是整份替换，写错一个键就会把用户
     * 配好的模型、插件、人格一起冲掉。返回值表示"是否真的有变化"，
     * 没有变化就不该发这次请求。
     */
    fun apply(config: JSONObject, settings: ContextSettings): Boolean {
        val runner = config.optJSONObject("agent_runner").orPut(config, "agent_runner")
        val runnerConfig = runner.optJSONObject("config").orPut(runner, "config")
        val compression = runnerConfig.optJSONObject("compression").orPut(runnerConfig, "compression")

        var changed = false

        // AstrBot 用 -1 表示不限，界面上用 0，这里翻译一次
        val turns = if (settings.maxTurns <= 0) -1 else settings.maxTurns
        if (compression.optInt("max_turns", Int.MIN_VALUE) != turns) {
            compression.put("max_turns", turns)
            changed = true
        }

        if (compression.optString("overflow_strategy") != settings.overflowStrategy) {
            compression.put("overflow_strategy", settings.overflowStrategy)
            changed = true
        }

        // 每次截断丢几轮：保持 1，一次只丢最老的一轮，不会一刀切得太狠
        if (!compression.has(KEY_TRUNCATE)) {
            compression.put(KEY_TRUNCATE, 1)
            changed = true
        }

        return changed
    }

    /**
     * 推荐值。
     *
     * 10 轮大概能撑住"接着上一句往下聊"的体感，再长就是拿钱买记性了。
     * 策略选 truncate：llm_compress 每次总结都要调一次模型，而且用的是
     * 同一个贵模型——省下的 token 钱可能还不如总结本身贵。
     */
    fun recommended(): ContextSettings = ContextSettings(
        maxTurns = 10,
        overflowStrategy = ContextSettings.STRATEGY_TRUNCATE,
    )

    private fun JSONObject?.orPut(parent: JSONObject, key: String): JSONObject =
        this ?: JSONObject().also { parent.put(key, it) }
}
