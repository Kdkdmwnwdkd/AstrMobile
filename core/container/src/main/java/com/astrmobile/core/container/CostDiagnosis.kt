package com.astrmobile.core.container

import java.util.Locale

/**
 * 一条关于"钱为什么花得快"的判断。
 *
 * 这类提示最容易写成正确的废话（"请注意控制成本"），所以在文案上定了两条规矩：
 * 一是必须给出具体数字，二是必须指出下一步动作。
 */
data class CostHint(
    val level: Int,
    val headline: String,
    val detail: String,
)

/**
 * 从一次用量快照里读出"钱花在哪"。
 *
 * 核心指标只有一个：**平均每次调用的 token 数**。
 *
 * AstrBot 每次请求都会把整个会话历史重新发给模型。所以同样聊 50 句，
 * 第 1 句的成本和它后面的第 50 句完全不是一回事——后者要连带前面 49 句
 * 一起付费。平均 token 悄悄涨上去，就是这件事在发生。
 *
 * 光看总 token 看不出来：一天聊 3 句和一天聊 300 句，总量一样但性质完全不同。
 */
object CostDiagnosis {

    const val LEVEL_OK = 0
    const val LEVEL_WATCH = 1
    const val LEVEL_BAD = 2

    /** 一轮正常闲聊（带点上下文）大致落在这个区间。 */
    private const val NORMAL_AVG_TOKENS = 1_500L
    private const val HEAVY_AVG_TOKENS = 5_000L

    /**
     * 折算到"每百万 token 多少钱"，用来判断是不是模型本身贵。
     *
     * 25 元/百万大致是分水岭：deepseek-chat 3.5、gpt-4o-mini 1.75、
     * qwen-plus 1.1 都在下面；gpt-4o 31.5、claude-sonnet 43.5、
     * claude-opus 216 都在上面。同样的聊天量，上下差几十倍。
     */
    private const val PRICEY_PER_MILLION = 25.0

    fun avgTokensPerCall(snapshot: UsageSnapshot): Long =
        if (snapshot.todayCalls <= 0) 0L else snapshot.todayTokens / snapshot.todayCalls

    fun blendedPricePerMillion(snapshot: UsageSnapshot): Double =
        if (snapshot.pricedTokens <= 0L) 0.0 else snapshot.todayCostYuan / snapshot.pricedTokens * 1_000_000

    fun analyze(snapshot: UsageSnapshot): CostHint? {
        if (snapshot.todayCalls <= 0) return null

        val avg = avgTokensPerCall(snapshot)
        val price = blendedPricePerMillion(snapshot)

        // 上下文膨胀优先报：它的影响是数量级的，而单价只差几倍
        if (avg >= HEAVY_AVG_TOKENS) {
            return CostHint(
                level = LEVEL_BAD,
                headline = "每次请求平均 ${thousands(avg)} token，上下文在滚雪球",
                detail = "普通闲聊一轮大约 800–1,500 token。到 ${thousands(avg)} 这个量级，" +
                    "要么是「每轮都在重发几十轮历史」，要么是插件和工具太多、每次请求都带着" +
                    "一大堆定义。先翻一下工具栏和插件列表——如果装了十几个，关掉一半，比改任何" +
                    "参数都有效。历史的原因则把「保留最近几轮」设成 10–20。",
            )
        }

        if (avg >= NORMAL_AVG_TOKENS) {
            // 这个区间有两种成因，解法完全相反：一种是历史在累积，另一种是
            // 压根没聊几句、但每次都要带上工具定义和插件提示词。只说前者，
            // "明明没聊多少"的人会觉得诊断是瞎编的。
            return CostHint(
                level = LEVEL_WATCH,
                headline = "每次请求平均 ${thousands(avg)} token，比正常偏高",
                detail = "两种可能，看你的聊天记录有多少：\n" +
                    "· 聊得挺久 → 历史在重复发送，把「保留最近几轮」设成 10–20\n" +
                    "· 其实没聊几句 → 多半是工具定义和插件提示词。AstrBot 每次请求都会" +
                    "把已装插件注册的工具一并发出去，一个工具通常几百 token，装十个就是几千，" +
                    "跟你聊没聊无关。关掉不用的插件比什么都省。",
            )
        }

        // 没牌价时金额恒为 0，如果不管，就会被判成"一切正常"。
        // 用户看到的是 ¥0.00，以为没花钱——比不显示更糟。
        if (snapshot.pricedTokens <= 0L && snapshot.todayTokens > 0L) {
            val model = snapshot.unpricedModels.firstOrNull() ?: "未知模型"
            return CostHint(
                level = LEVEL_WATCH,
                headline = "这个模型没有牌价，金额算不出来",
                detail = "今天用了 ${thousands(snapshot.todayTokens)} token（${snapshot.todayCalls} 次），" +
                    "但「$model」不在内置牌价表里。不是没花钱，是没法折算。" +
                    "真实用量以厂商后台为准。",
            )
        }

        if (price >= PRICEY_PER_MILLION) {
            return CostHint(
                level = LEVEL_WATCH,
                headline = "上下文不算长，但单价高（约 ${String.format(Locale.US, "%.0f", price)} 元/百万 token）",
                detail = "问题不在用量，在模型本身。同一个用法换到 deepseek-chat" +
                    "（约 2 元/百万输入）能省下大部分开销；贵模型留着处理难问题更划算。",
            )
        }

        return CostHint(
            level = LEVEL_OK,
            headline = "每次请求平均 ${thousands(avg)} token，属于正常范围",
            detail = "上下文没有明显膨胀。如果金额仍然偏高，通常是调用次数多而不是单次贵。",
        )
    }

    private fun thousands(value: Long): String =
        if (value >= 10_000) {
            String.format(Locale.US, "%.1f 万", value / 10_000.0)
        } else {
            String.format(Locale.US, "%,d", value)
        }
}
