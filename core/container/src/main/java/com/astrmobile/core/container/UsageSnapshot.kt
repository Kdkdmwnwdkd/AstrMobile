package com.astrmobile.core.container

import org.json.JSONObject
import java.util.Locale

/** 单个模型的今日用量。`costYuan` 为 null 表示没匹配到牌价。 */
data class ModelUsage(
    val model: String,
    val tokens: Long,
    val costYuan: Double?,
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
    /** 有模型没匹配到牌价，金额只覆盖了部分用量。 */
    val hasUnpricedModel: Boolean,
) {
    val todayCostText: String get() = formatYuan(todayCostYuan)

    val isFreeOrEmpty: Boolean get() = todayTokens == 0L

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

            return UsageSnapshot(
                days = days,
                todayTokens = todayTokens,
                todayCalls = todayCalls,
                todayCostYuan = models.sumOf { it.costYuan ?: 0.0 },
                todayModels = models.sortedByDescending { it.tokens },
                rangeTokens = data.optLong("range_total_tokens", 0L),
                rangeCalls = data.optInt("range_total_calls", 0),
                hasUnpricedModel = unpriced,
            )
        }
    }
}
