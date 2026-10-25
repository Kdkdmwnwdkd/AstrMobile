package com.astrmobile.core.container

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「钱为什么花得快」的判断。
 *
 * 这套文案是要拿去给用户看结论的，所以阈值必须钉死：
 * 一千 token 一次的私聊被说成"上下文膨胀"，比不提示更糟。
 */
class CostDiagnosisTest {

    private fun snapshot(tokens: Long, calls: Int, cost: Double = 0.0, unpriced: Long = 0L) =
        UsageSnapshot(
            days = 1,
            todayTokens = tokens,
            todayCalls = calls,
            todayCostYuan = cost,
            todayModels = emptyList(),
            rangeTokens = tokens,
            rangeCalls = calls,
            hasUnpricedModel = unpriced > 0,
            unpricedTokens = unpriced,
        )

    @Test
    fun `没有调用时不给判断`() {
        assertNull(CostDiagnosis.analyze(snapshot(0, 0)))
    }

    @Test
    fun `平均一千 token 属于正常`() {
        val hint = CostDiagnosis.analyze(snapshot(100_000, 100, cost = 0.2))!!
        assertEquals(CostDiagnosis.LEVEL_OK, hint.level)
    }

    @Test
    fun `平均三千 token 提示偏高`() {
        val hint = CostDiagnosis.analyze(snapshot(300_000, 100, cost = 0.6))!!
        assertEquals(CostDiagnosis.LEVEL_WATCH, hint.level)
        assertTrue(hint.headline.contains("偏高"))
    }

    @Test
    fun `平均过万 token 判为上下文膨胀`() {
        val hint = CostDiagnosis.analyze(snapshot(1_200_000, 100, cost = 2.4))!!
        assertEquals(CostDiagnosis.LEVEL_BAD, hint.level)
        assertTrue(hint.headline.contains("滚雪球"))
        // 必须给出下一步动作，而不是只说"请注意"
        assertTrue(hint.detail.contains("10"))
    }

    @Test
    fun `用量正常但单价很高时点名模型`() {
        // 1000 token/次，但 100 次花了 20 元 = 200 元/百万
        val hint = CostDiagnosis.analyze(snapshot(100_000, 100, cost = 20.0))!!
        assertEquals(CostDiagnosis.LEVEL_WATCH, hint.level)
        assertTrue(hint.headline.contains("单价高"))
    }

    @Test
    fun `单价分母只用有牌价的 token`() {
        val snap = snapshot(100_000, 10, cost = 1.0, unpriced = 50_000)
        // 1 元 / 5 万有价 token = 20 元/百万，而不是 10 元
        assertEquals(20.0, CostDiagnosis.blendedPricePerMillion(snap), 0.01)
    }
}
