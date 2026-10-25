package com.astrmobile.core.container

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首屏那一行到底显示什么。
 *
 * 这段逻辑出过一次真事：用户用的是一个牌价表里没有的中转模型，
 * 界面老老实实显示「今天 ¥0.00」——比什么都不显示更糟，因为它看起来
 * 像"确认没花钱"。现在的规则是：没单价就说没单价。
 */
class UsageCostLabelTest {

    private fun parse(json: String) = UsageSnapshot.parse(JSONObject(json.trimIndent()), 1)

    @Test
    fun `没有调用记录时显示零元`() {
        val snapshot = parse(
            """
            {"today_total_tokens": 0, "today_total_calls": 0, "today_by_model": []}
            """,
        )
        assertEquals("¥ 0.00", snapshot.todayCostLabel)
        assertTrue(snapshot.isFreeOrEmpty)
    }

    @Test
    fun `模型全部未收录时不显示金额`() {
        val snapshot = parse(
            """
            {
              "today_total_tokens": 120000,
              "today_total_calls": 8,
              "today_by_model": [
                {"provider_model": "mystery-proxy-v1", "tokens": 120000}
              ]
            }
            """,
        )
        assertEquals("未收录", snapshot.todayCostLabel)
        assertEquals(120000L, snapshot.unpricedTokens)
        assertEquals(listOf("mystery-proxy-v1"), snapshot.unpricedModels)
        // 金额本身仍是 0，但界面不该拿它当结论
        assertEquals(0.0, snapshot.todayCostYuan, 0.0001)
    }

    @Test
    fun `部分收录时只算认识的那部分并保留未收录名单`() {
        val snapshot = parse(
            """
            {
              "today_total_tokens": 1000000,
              "today_total_calls": 10,
              "today_by_model": [
                {"provider_model": "deepseek-chat", "tokens": 900000},
                {"provider_model": "mystery-proxy-v1", "tokens": 100000}
              ]
            }
            """,
        )
        // 90 万 token 的 deepseek-chat：输入 67.5 万 * 2 + 输出 22.5 万 * 8
        assertEquals(1.35 + 1.8, snapshot.todayCostYuan, 0.0001)
        assertEquals(100000L, snapshot.unpricedTokens)
        assertEquals(900000L, snapshot.pricedTokens)
        assertTrue(snapshot.hasUnpricedModel)
        assertTrue(snapshot.todayCostLabel.startsWith("¥"))
    }

    @Test
    fun `全部收录时正常给金额`() {
        val snapshot = parse(
            """
            {
              "today_total_tokens": 1000000,
              "today_total_calls": 4,
              "today_by_model": [
                {"provider_model": "deepseek-chat", "tokens": 1000000}
              ]
            }
            """,
        )
        assertEquals("¥3.50", snapshot.todayCostLabel)
        assertEquals(0L, snapshot.unpricedTokens)
    }
}
