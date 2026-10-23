package com.astrmobile.core.container

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 趋势与区间聚合的解析。
 *
 * 按模型计价的那些用例在 UsagePricingTest 里的 UsageSnapshotTest，
 * 这里只管 days/trend/range_by_umo 这几块。
 */
class UsageTrendTest {

    private fun parse(json: String, days: Int = 7) =
        UsageSnapshot.parse(JSONObject(json.trimIndent()), days)

    @Test
    fun `解析按天的趋势序列`() {
        val snapshot = parse(
            """
            {
              "days": 7,
              "trend": {
                "series": [],
                "total_series": [
                  [1729000000, 12000],
                  [1729086400, 5000],
                  [1729172800, 20000]
                ]
              },
              "range_total_tokens": 37000,
              "range_total_calls": 40
            }
            """,
        )

        assertEquals(7, snapshot.days)
        assertEquals(3, snapshot.trend.size)
        assertEquals(12000L, snapshot.trend[0].tokens)
        assertEquals(20000L, snapshot.trend[2].tokens)
        assertEquals(37000L, snapshot.rangeTokens)
        assertEquals(40, snapshot.rangeCalls)
    }

    @Test
    fun `时间戳为 0 的点被丢掉`() {
        // 服务端补位时可能给空桶，画到图上就是一根根 0 高度的柱子
        val snapshot = parse(
            """
            {
              "trend": { "total_series": [[0, 0], [1729000000, 8000]] }
            }
            """,
        )

        assertEquals(1, snapshot.trend.size)
        assertEquals(8000L, snapshot.trend.single().tokens)
    }

    @Test
    fun `按会话的分布按 token 降序`() {
        val snapshot = parse(
            """
            {
              "range_by_umo": [
                {"umo": "aiocqhttp:GroupMessage:111", "display_name": "摸鱼群", "tokens": 5000},
                {"umo": "aiocqhttp:GroupMessage:222", "display_name": "工作群", "tokens": 30000},
                {"umo": "aiocqhttp:FriendMessage:333", "display_name": "私聊", "tokens": 12000}
              ]
            }
            """,
        )

        assertEquals(3, snapshot.rangeByUmo.size)
        assertEquals("工作群", snapshot.rangeByUmo[0].displayName)
        assertEquals(30000L, snapshot.rangeByUmo[0].tokens)
        assertEquals("私聊", snapshot.rangeByUmo[1].displayName)
    }

    @Test
    fun `display_name 为空时退回 umo`() {
        val snapshot = parse(
            """
            {"range_by_umo": [{"umo": "aiocqhttp:GroupMessage:111", "tokens": 100}]}
            """,
        )

        assertEquals("aiocqhttp:GroupMessage:111", snapshot.rangeByUmo.single().displayName)
    }

    @Test
    fun `区间金额按今日均价折算`() {
        // 今日 1000 token 花 0.01 元 → 单价 1e-5 元/token
        // 7 天共 50000 token → 0.5 元
        val snapshot = parse(
            """
            {
              "today_total_tokens": 1000,
              "today_total_calls": 2,
              "today_by_model": [
                {"provider_model": "deepseek-chat", "tokens": 1000}
              ],
              "range_total_tokens": 50000
            }
            """,
        )

        val cost = snapshot.rangeCostYuan
        assertEquals(0.5, cost!!, 1e-9)
    }

    @Test
    fun `今日没有用量时不给区间金额`() {
        // 没有今日单价可用，编一个 0 元会让人以为这段时间没花钱
        val snapshot = parse(
            """
            { "today_total_tokens": 0, "range_total_tokens": 30000 }
            """,
        )

        assertNull(snapshot.rangeCostYuan)
    }

    @Test
    fun `缺字段时返回空列表而不是崩`() {
        val snapshot = parse("{}")

        assertTrue(snapshot.trend.isEmpty())
        assertTrue(snapshot.rangeByUmo.isEmpty())
        assertEquals(0L, snapshot.rangeTokens)
        assertNull(snapshot.rangeCostYuan)
    }

    @Test
    fun `畸形数组元素被跳过`() {
        val snapshot = parse(
            """
            {
              "trend": { "total_series": [[1729000000], [], [1729086400, 900]] },
              "range_by_umo": [{}, {"umo": "", "tokens": 10}]
            }
            """,
        )

        // 只有第三项有完整的两元素
        assertEquals(1, snapshot.trend.size)
        assertEquals(900L, snapshot.trend.single().tokens)
        // umo 为空的那条被丢掉
        assertTrue(snapshot.rangeByUmo.isEmpty())
    }
}
