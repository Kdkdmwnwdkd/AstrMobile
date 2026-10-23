package com.astrmobile.core.container

import org.json.JSONObject
import java.util.Locale

/** 单个模型的今日用量。`costYuan` 为 null 表示没匹配到牌价。 */
data class ModelUsage(
    val model: String,
    val tokens: Long,
    val costYuan: Double?,
)

/** 趋势图上的一个点：某一天的 token 总数。 */
data class UsagePoint(
    val timestampSec: Long,
    val tokens: Long,
)

/** 某个会话在这段时间里的用量。 */
data class UmoUsage(
    val umo: String,
    val displayName: String,
    val tokens: Long,
)

/**
 * 一次用量快照。
 *
 * 数据来自 AstrBot 的 `/api/v1/stats/provider-tokens`，字段名与它的返回体一致：
 * `today_total_tokens` / `today_total_calls` / `today_by_model` 等。
 * 这些 key 是跨版本契约，解析时全部按可选处理——AstrBot 改字段不该让 App 崩。
 */
data class UsageSnapshot(
    val days: Int,
    val todayTokens: Long,
    val todayCalls: Int,
    val todayCostYuan: Double,
    val todayModels: List<ModelUsage>,
    val rangeTokens: Long,
    val rangeCalls: Int,
    /** 按天的 token 序列，来自 `trend.total_series`。 */
    val trend: List<UsagePoint> = emptyList(),
    /** 区间内按会话的分布，用来回答"哪个群最费钱"。 */
    val rangeByUmo: List<UmoUsage> = emptyList(),
    /** 有模型没匹配到牌价，金额只覆盖了部分用量。 */
    val hasUnpricedModel: Boolean,
) {
    val todayCostText: String get() = formatYuan(todayCostYuan)

    val isFreeOrEmpty: Boolean get() = todayTokens == 0L

    /**
     * 区间（7 天 / 30 天）的估算花费。
     *
     * 接口只给今日的按模型分布，没有区间内的分布，所以拿今日的平均单价
     * （元/token）去折算整段。这个近似在模型没换过的时候很准，换了模型
     * 就会偏——所以界面上必须写明"按今日均价折算"，不能只给一个数字。
     *
     * 今日还没用量时返回 null：没有单价可用，编一个 0 反而误导。
     */
    val rangeCostYuan: Double?
        get() {
            if (todayTokens <= 0L || rangeTokens <= 0L) return null
            return rangeTokens * (todayCostYuan / todayTokens)
        }

    companion object {
        fun formatYuan(value: Double): String = when {
            value <= 0.0 -> "¥0.00"
            // 几分钱的场景给两位有效数字，否则永远是 ¥0.00，看不出差别
            value < 0.01 -> "¥<0.01"
            value < 1.0 -> String.format(Locale.US, "¥%.3f", value)
            else -> String.format(Locale.US, "¥%.2f", value)
        }

        /** 纯解析，不联网，方便单测。 */
        fun parse(data: JSONObject, days: Int): UsageSnapshot {
            val todayTokens = data.optLong("today_total_tokens", 0L)
            val todayCalls = data.optInt("today_total_calls", 0)

            var unpriced = false
            val models = buildList {
                val array = data.optJSONArray("today_by_model")
                if (array != null) {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val name = item.optString("provider_model").ifBlank { "未知模型" }
                        val tokens = item.optLong("tokens", 0L)
                        val cost = UsagePricing.estimateCostYuan(name, tokens)
                        if (cost == null) unpriced = true
                        add(ModelUsage(model = name, tokens = tokens, costYuan = cost))
                    }
                }
            }

            val trend = buildList {
                val series = data.optJSONObject("trend")?.optJSONArray("total_series")
                if (series != null) {
                    for (index in 0 until series.length()) {
                        val pair = series.optJSONArray(index) ?: continue
                        // 只有时间戳没有数值的残项直接跳过
                        if (pair.length() < 2) continue
                        val ts = pair.optLong(0, 0L)
                        if (ts <= 0L) continue
                        add(UsagePoint(timestampSec = ts, tokens = pair.optLong(1, 0L)))
                    }
                }
            }

            val rangeByUmo = buildList {
                val array = data.optJSONArray("range_by_umo")
                if (array != null) {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val umo = item.optString("umo")
                        if (umo.isBlank()) continue
                        add(
                            UmoUsage(
                                umo = umo,
                                displayName = item.optString("display_name").ifBlank { umo },
                                tokens = item.optLong("tokens", 0L),
                            ),
                        )
                    }
                }
            }

            return UsageSnapshot(
                days = days,
                todayTokens = todayTokens,
                todayCalls = todayCalls,
                todayCostYuan = models.sumOf { it.costYuan ?: 0.0 },
                todayModels = models.sortedByDescending { it.tokens },
                rangeTokens = data.optLong("range_total_tokens", 0L),
                rangeCalls = data.optInt("range_total_calls", 0),
                trend = trend,
                rangeByUmo = rangeByUmo.sortedByDescending { it.tokens },
                hasUnpricedModel = unpriced,
            )
        }
    }
}
