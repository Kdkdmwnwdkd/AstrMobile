package com.astrmobile.core.container

/**
 * 模型牌价表：把"用了多少 token"折成"花了多少钱"。
 *
 * AstrBot 自己只记录 token 数（`provider_stats` 表里是 input/output 分开的），
 * 但它的统计接口只吐出合计值，不吐金额，也不拆输入输出。所以：
 *
 * - **金额是估算**，不是账单。真实扣费以各厂商后台为准。
 * - **输入/输出比例按 3:1 估**。聊天场景下这个比例比较接近实际；
 *   如果你主要用它跑长文档摘要，实际会偏向输入，估算会偏高。
 *
 * 价格单位是「元 / 百万 token」，取的是各家公开牌价的近似值。
 * 牌价会变，这里只作为参考——数值集中在这张表里，改起来是改数据不是改逻辑。
 */
object UsagePricing {

    data class ModelPrice(
        val inputCnyPerMillion: Double,
        val outputCnyPerMillion: Double,
    )

    /** 估算用的输出占比。聊天里回复通常比提问短，1/4 是个保守中值。 */
    const val DEFAULT_OUTPUT_RATIO: Double = 0.25

    /**
     * 匹配规则：模型名转小写后做 contains 判断，**顺序敏感**——
     * 越具体的放前面（`gpt-4o-mini` 必须在 `gpt-4o` 之前）。
     */
    private val TABLE: List<Pair<String, ModelPrice>> = listOf(
        // —— OpenAI ——
        // mini / nano 必须排在正主前面：`gpt-4.1-mini` 会命中 `gpt-4.1`，
        // 单价差十几倍，一次匹配错就把"今天花了 3 块"算成 40 块。
        "gpt-5-mini" to ModelPrice(2.0, 8.0),
        "gpt-5-nano" to ModelPrice(0.5, 2.0),
        "gpt-5" to ModelPrice(9.0, 72.0),
        "gpt-4.1-mini" to ModelPrice(3.0, 12.0),
        "gpt-4.1-nano" to ModelPrice(0.8, 3.2),
        "gpt-4.1" to ModelPrice(14.0, 56.0),
        "gpt-4o-mini" to ModelPrice(1.0, 4.0),
        "gpt-4o" to ModelPrice(18.0, 72.0),
        "gpt-3.5" to ModelPrice(0.5, 1.5),
        "o4-mini" to ModelPrice(8.0, 32.0),
        "o3-mini" to ModelPrice(8.0, 32.0),
        "o3" to ModelPrice(14.0, 56.0),
        "o1-mini" to ModelPrice(8.0, 32.0),
        "o1" to ModelPrice(108.0, 432.0),

        // —— Anthropic ——
        "claude-3-5-haiku" to ModelPrice(1.8, 9.0),
        "claude-haiku" to ModelPrice(1.8, 9.0),
        "claude-3-5-sonnet" to ModelPrice(22.0, 108.0),
        "claude-sonnet-4" to ModelPrice(22.0, 108.0),
        "claude-sonnet" to ModelPrice(22.0, 108.0),
        "claude-opus-4" to ModelPrice(108.0, 540.0),
        "claude-opus" to ModelPrice(108.0, 540.0),

        // —— DeepSeek ——
        "deepseek-reasoner" to ModelPrice(4.0, 16.0),
        "deepseek-r1" to ModelPrice(4.0, 16.0),
        "deepseek-chat" to ModelPrice(2.0, 8.0),
        "deepseek-v3" to ModelPrice(2.0, 8.0),
        "deepseek" to ModelPrice(2.0, 8.0),

        // —— Google ——
        "gemini-2.5-flash" to ModelPrice(1.0, 4.0),
        "gemini-2.5-pro" to ModelPrice(9.0, 72.0),
        "gemini-2.0-flash" to ModelPrice(0.5, 2.0),
        "gemini-1.5-pro" to ModelPrice(9.0, 36.0),
        "gemini-flash" to ModelPrice(0.5, 2.0),
        "gemini" to ModelPrice(9.0, 36.0),

        // —— 国内 ——
        // qwen3 要排在 qwen 前面，否则永远命中兜底价
        "qwen3" to ModelPrice(2.0, 8.0),
        "qwen-max" to ModelPrice(20.0, 60.0),
        "qwen-plus" to ModelPrice(0.8, 2.0),
        "qwen-turbo" to ModelPrice(0.3, 0.6),
        "qwen" to ModelPrice(0.8, 2.0),
        "glm-4.5" to ModelPrice(2.0, 8.0),
        "glm-4" to ModelPrice(1.0, 1.0),
        "glm" to ModelPrice(1.0, 1.0),
        "kimi-k2" to ModelPrice(4.0, 16.0),
        "moonshot" to ModelPrice(4.0, 16.0),
        "kimi" to ModelPrice(4.0, 16.0),
        "doubao" to ModelPrice(0.8, 2.0),
        "hunyuan" to ModelPrice(4.0, 16.0),
        "minimax" to ModelPrice(1.0, 8.0),
        "spark" to ModelPrice(0.5, 2.0),
        "ernie" to ModelPrice(0.8, 2.0),
        "yi-" to ModelPrice(4.0, 16.0),
    )

    /** 找不到牌价返回 null——宁可显示"未配置单价"，也不要编一个数出来。 */
    fun priceFor(model: String): ModelPrice? {
        val name = model.lowercase()
        return TABLE.firstOrNull { (pattern, _) -> name.contains(pattern) }?.second
    }

    /**
     * 估算一次调用的金额（元）。
     *
     * @param tokens 该模型的合计 token（输入 + 输出）
     * @param outputRatio 输出 token 占比，0~1
     */
    fun estimateCostYuan(
        model: String,
        tokens: Long,
        outputRatio: Double = DEFAULT_OUTPUT_RATIO,
    ): Double? {
        if (tokens <= 0) return 0.0
        val price = priceFor(model) ?: return null

        val ratio = outputRatio.coerceIn(0.0, 1.0)
        val outputTokens = tokens * ratio
        val inputTokens = tokens - outputTokens

        return (inputTokens / 1_000_000.0) * price.inputCnyPerMillion +
            (outputTokens / 1_000_000.0) * price.outputCnyPerMillion
    }
}
