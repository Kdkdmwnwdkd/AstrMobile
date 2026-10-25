package com.astrmobile.core.container

import org.json.JSONArray
import org.json.JSONObject

/** 一个插件在每次请求里占掉的固定开销。 */
data class PluginFootprint(
    val pluginName: String,
    val displayName: String,
    val toolCount: Int,
    /** 工具 schema 序列化后的字符数。 */
    val schemaChars: Int,
    /** 折算成 token。 */
    val estimatedTokens: Int,
    /** 工具名，界面上列前几个。 */
    val toolNames: List<String>,
    /** 是否是内置工具（不来自插件）。 */
    val builtin: Boolean = false,
)

/**
 * 统计"谁在吃 token"。
 *
 * AstrBot 用 function calling，每次请求都要把所有可用工具的 schema 一起发出去。
 * 装十个插件、每个注册几个工具，就是每次请求固定多几千 token——跟聊没聊、
 * 聊了几句都无关。这件事在界面上完全看不见，只能靠统计。
 *
 * 数据来自 `GET /api/v1/tools`，每个工具带 `origin_name`（来自哪个插件）、
 * `description` 和 `parameters`（JSON Schema）。把这三样拼起来算字符数，
 * 再折算 token，就是它每次请求的真实开销。
 */
object ToolFootprint {

    /**
     * 按字符数估 token。
     *
     * 英文（字段名、类型名）大约 4 字符一个 token，中文大约 1.3 字一个。
     * 工具 schema 恰好是两者混合：键名是英文，description 常是中文。
     * 所以分开算再加，比统一按 4 字符除要准得多——后者会把中文算少一半。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var ascii = 0
        var other = 0
        for (ch in text) {
            if (ch.code < 128) ascii++ else other++
        }
        return estimateTokens(ascii, other)
    }

    /** 已经分好类时的版本，避免为了算 token 再把大段 JSON 拼一遍。 */
    fun estimateTokens(ascii: Int, other: Int): Int {
        if (ascii <= 0 && other <= 0) return 0
        return (ascii / 4.0 + other / 1.3).toInt().coerceAtLeast(1)
    }

    /**
     * 按插件聚合。
     *
     * 只统计 `active != false` 的工具：被关掉的工具不会下发，算进来会虚高。
     * 无法识别来源的（没有 origin_name）归到「未知」而不是丢掉——丢掉会让
     * 总数对不上，用户会怀疑统计不准。
     */
    fun parse(tools: JSONArray): List<PluginFootprint> {
        val groups = linkedMapOf<String, MutableList<JSONObject>>()

        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: continue
            if (!tool.optBoolean("active", true)) continue

            val origin = tool.optString("origin_name").takeIf { it.isNotBlank() }
                ?: tool.optString("origin").takeIf { it.isNotBlank() }
                ?: "未知来源"

            groups.getOrPut(origin) { mutableListOf() }.add(tool)
        }

        return groups.map { (origin, items) ->
            var chars = 0
            var ascii = 0
            var other = 0
            val names = mutableListOf<String>()

            items.forEach { tool ->
                val name = tool.optString("name")
                val description = tool.optString("description")
                val parameters = tool.opt("parameters")?.toString().orEmpty()
                // 按真实字符逐个分类：schema 里键名是英文、description 常是中文，
                // 统一按 4 字符除会把中文那一半算少一半
                for (text in listOf(name, description, parameters)) {
                    chars += text.length
                    for (ch in text) {
                        if (ch.code < 128) ascii++ else other++
                    }
                }
                if (name.isNotBlank()) names.add(name)
            }

            PluginFootprint(
                pluginName = origin,
                displayName = items.firstOrNull()
                    ?.optString("origin_display_name")
                    ?.takeIf { it.isNotBlank() }
                    ?: origin,
                toolCount = items.size,
                schemaChars = chars,
                estimatedTokens = estimateTokens(ascii, other),
                toolNames = names,
                builtin = origin == "builtin" || origin.startsWith("builtin_"),
            )
        }.sortedByDescending { it.estimatedTokens }
    }

    /** 全部插件的合计，用来算占比。 */
    fun totalTokens(items: List<PluginFootprint>): Int = items.sumOf { it.estimatedTokens }

    /**
     * 按每次请求的开销折算成本。
     *
     * 这个数不是"花了多少钱"，而是"什么都没聊，每天也要付的部分"——
     * 按每天 [callsPerDay] 次调用算。
     */
    fun dailyWasteYuan(items: List<PluginFootprint>, pricePerMillion: Double, callsPerDay: Int = 100): Double {
        if (pricePerMillion <= 0.0 || callsPerDay <= 0) return 0.0
        return totalTokens(items) / 1_000_000.0 * pricePerMillion * callsPerDay
    }
}
