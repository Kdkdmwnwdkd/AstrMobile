package com.astrmobile.core.container

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「今天花了多少钱」这套算法的验证。
 *
 * 这里最容易错的两个地方：
 * 1. 模型名匹配顺序——`gpt-4o-mini` 必须命中 mini 的价，不能落到 `gpt-4o`
 * 2. 输入/输出拆分——接口只给合计 token，得按固定比例估
 *
 * 金额本身是估算，但**估算规则必须稳定且可解释**，否则用户看到一个
 * 飘忽的数字，会以为坏掉了。
 */
class UsagePricingTest {

    @Test
    fun `deepseek-chat 一百万 token 按 3比1 拆分`() {
        // 输入 75 万 * 2 元/百万 = 1.5；输出 25 万 * 8 元/百万 = 2.0
        val cost = UsagePricing.estimateCostYuan("deepseek-chat", 1_000_000)

        assertEquals(3.5, cost!!, 0.0001)
    }

    @Test
    fun `更具体的模型名先匹配`() {
        // gpt-4o-mini 是 1/4 元，gpt-4o 是 18/72 元，差 18 倍，串价会非常明显
        val mini = UsagePricing.priceFor("gpt-4o-mini")!!
        val full = UsagePricing.priceFor("gpt-4o")!!

        assertEquals(1.0, mini.inputCnyPerMillion, 0.001)
        assertEquals(18.0, full.inputCnyPerMillion, 0.001)
    }

    @Test
    fun `大小写与前后缀不影响匹配`() {
        assertEquals(
            UsagePricing.priceFor("deepseek-chat"),
            UsagePricing.priceFor("DeepSeek-Chat-0324"),
        )
    }

    @Test
    fun `未知模型返回 null 而不是编一个价格`() {
        assertNull(UsagePricing.priceFor("some-local-llama-70b"))
        assertNull(UsagePricing.estimateCostYuan("some-local-llama-70b", 100_000))
    }

    @Test
    fun `零 token 记为 0 元`() {
        assertEquals(0.0, UsagePricing.estimateCostYuan("deepseek-chat", 0)!!, 0.0001)
    }

    @Test
    fun `输出比例越界会被夹到 0 到 1`() {
        val normal = UsagePricing.estimateCostYuan("deepseek-chat", 1_000_000, outputRatio = 0.25)!!
        val clamped = UsagePricing.estimateCostYuan("deepseek-chat", 1_000_000, outputRatio = 5.0)!!

        // 全部按输出计价：1 百万 * 8 元
        assertEquals(8.0, clamped, 0.0001)
        assertTrue(clamped > normal)
    }
}

class UsageSnapshotTest {

    private fun payload(
        todayTokens: Long,
        todayCalls: Int,
        models: List<Pair<String, Long>>,
        rangeTokens: Long = 0,
        rangeCalls: Int = 0,
    ): JSONObject {
        val array = JSONArray()
        models.forEach { (name, tokens) ->
            array.put(JSONObject().put("provider_model", name).put("tokens", tokens))
        }
        return JSONObject()
            .put("today_total_tokens", todayTokens)
            .put("today_total_calls", todayCalls)
            .put("today_by_model", array)
            .put("range_total_tokens", rangeTokens)
            .put("range_total_calls", rangeCalls)
    }

    @Test
    fun `按模型分别计价并汇总`() {
        val data = payload(
            todayTokens = 1_500_000,
            todayCalls = 30,
            models = listOf("deepseek-chat" to 1_000_000, "gpt-4o" to 500_000),
        )

        val snapshot = UsageSnapshot.parse(data, days = 1)

        assertEquals(1_500_000, snapshot.todayTokens)
        assertEquals(30, snapshot.todayCalls)
        // deepseek 3.5；gpt-4o 50 万 token 拆成 37.5 万输入(18 元/百万)=6.75
        // + 12.5 万输出(72 元/百万)=9 → 15.75
        assertEquals(3.5 + 15.75, snapshot.todayCostYuan, 0.0001)
        assertFalse(snapshot.hasUnpricedModel)
        assertEquals(2, snapshot.todayModels.size)
    }

    @Test
    fun `模型按 token 从多到少排序`() {
        val data = payload(
            todayTokens = 300,
            todayCalls = 3,
            models = listOf("glm-4" to 100, "deepseek-chat" to 200),
        )

        val snapshot = UsageSnapshot.parse(data, days = 1)

        assertEquals("deepseek-chat", snapshot.todayModels.first().model)
    }

    @Test
    fun `有模型查不到牌价时给出标记且不把它算成 0 元`() {
        val data = payload(
            todayTokens = 1_000_000,
            todayCalls = 5,
            models = listOf("deepseek-chat" to 500_000, "my-private-model" to 500_000),
        )

        val snapshot = UsageSnapshot.parse(data, days = 1)

        assertTrue(snapshot.hasUnpricedModel)
        // 只算能算的部分，不把未知模型当免费的
        assertEquals(1.75, snapshot.todayCostYuan, 0.0001)
        assertNull(snapshot.todayModels.first { it.model == "my-private-model" }.costYuan)
    }

    @Test
    fun `字段缺失时按零处理而不是崩溃`() {
        val snapshot = UsageSnapshot.parse(JSONObject(), days = 1)

        assertEquals(0, snapshot.todayTokens)
        assertEquals(0, snapshot.todayCalls)
        assertTrue(snapshot.todayModels.isEmpty())
        assertTrue(snapshot.isFreeOrEmpty)
    }

    @Test
    fun `金额显示分档`() {
        assertEquals("¥0.00", UsageSnapshot.formatYuan(0.0))
        assertEquals("¥<0.01", UsageSnapshot.formatYuan(0.004))
        assertEquals("¥0.500", UsageSnapshot.formatYuan(0.5))
        assertEquals("¥12.35", UsageSnapshot.formatYuan(12.346))
    }
}

/**
 * 牌价表的顺序仍然是这张表最脆的地方：新增模型时把 mini 写在正主后面，
 * 金额会静默放大十几倍，而界面上看不出任何异常。
 */
class UsagePricingOrderTest {

    @Test
    fun `gpt-4_1-mini 不会命中 gpt-4_1`() {
        val mini = UsagePricing.priceFor("gpt-4.1-mini")!!
        val full = UsagePricing.priceFor("gpt-4.1")!!
        assertTrue(mini.inputCnyPerMillion < full.inputCnyPerMillion)
    }

    @Test
    fun `gpt-5-mini 不会命中 gpt-5`() {
        val mini = UsagePricing.priceFor("gpt-5-mini")!!
        val full = UsagePricing.priceFor("gpt-5")!!
        assertTrue(mini.outputCnyPerMillion < full.outputCnyPerMillion)
    }

    @Test
    fun `qwen3 不会落到 qwen 兜底价`() {
        val qwen3 = UsagePricing.priceFor("qwen3-max")!!
        assertEquals(2.0, qwen3.inputCnyPerMillion, 0.001)
    }

    @Test
    fun `中转常见前缀不影响匹配`() {
        // 中转服务常把模型名写成 "openai/gpt-4o-mini" 或 "anthropic/claude-sonnet-4"
        assertEquals(1.0, UsagePricing.priceFor("openai/gpt-4o-mini")!!.inputCnyPerMillion, 0.001)
        assertEquals(22.0, UsagePricing.priceFor("anthropic/claude-sonnet-4")!!.inputCnyPerMillion, 0.001)
    }

    @Test
    fun `完全不认识的名字返回 null 而不是 0`() {
        assertNull(UsagePricing.priceFor("some-random-model-v9"))
    }
}
