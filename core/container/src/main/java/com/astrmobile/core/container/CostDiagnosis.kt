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

    /** 折算到"每百万 token 多少钱"，用来判断是不是模型本身贵。 */
    private const val PRICEY_PER_MILLION = 40.0

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
                    "基本等于「每轮都在重发几十轮历史」——聊得越久，单次越贵，" +
                    "一天的总量接近平方增长。把「保留最近几轮」设成 10，通常能降一个数量级。",
            )
        }

        if (avg >= NORMAL_AVG_TOKENS) {
            return CostHint(
                level = LEVEL_WATCH,
                headline = "每次请求平均 ${thousands(avg)} token，比正常偏高",
                detail = "多出来的部分大概率是历史消息：AstrBot 每次请求都会把整个会话" +
                    "重新发一遍。短对话感觉不到，聊上一整天就很明显。限制到 10 轮可以压住。",
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
