package com.astrmobile.core.container

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文设置。
 *
 * 这个页面直接决定用户花多少钱，但它改的是**整份配置**里的一个子键。
 * `PUT /system-config` 是整份替换，写坏一次就会把模型、人格、插件配置
 * 一起冲掉。所以这里的重点是"只动该动的"。
 */
class ContextConfigTest {

    private fun config(compression: String? = null): JSONObject {
        val inner = compression ?: """{"max_turns": -1, "overflow_strategy": "llm_compress"}"""
        return JSONObject(
            """
            {
              "agent_runner": {
                "runner_type": "local",
                "config": {
                  "model": {"provider_id": "deepseek", "request_max_retries": 5},
                  "persona": {"persona_id": "chatty", "safety_mode": true},
                  "compression": $inner,
                  "misc": {"max_steps": 128}
                }
              },
              "platform_settings": {"reply_prefix": ""}
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `默认配置读出来是不限轮数`() {
        val settings = ContextConfig.read(config())
        assertTrue(settings.unlimited)
        assertEquals(-1, settings.maxTurns)
        assertEquals(ContextSettings.STRATEGY_LLM_COMPRESS, settings.overflowStrategy)
    }

    @Test
    fun `写入时把 0 翻译成 -1`() {
        val target = config()
        assertTrue(ContextConfig.apply(target, ContextSettings(maxTurns = 0)))

        val compression = target.optJSONObject("agent_runner")!!
            .optJSONObject("config")!!
            .optJSONObject("compression")!!
        assertEquals(-1, compression.optInt("max_turns"))
    }

    @Test
    fun `改为十轮只动这两个键`() {
        val target = config()
        assertTrue(
            ContextConfig.apply(
                target,
                ContextSettings(maxTurns = 10, overflowStrategy = ContextSettings.STRATEGY_TRUNCATE),
            ),
        )

        val runnerConfig = target.optJSONObject("agent_runner")!!.optJSONObject("config")!!
        val compression = runnerConfig.optJSONObject("compression")!!
        assertEquals(10, compression.optInt("max_turns"))
        assertEquals("truncate_by_turns", compression.optString("overflow_strategy"))

        // 这三块必须原样保留，否则用户保存一次就丢了模型和人格
        assertEquals("deepseek", runnerConfig.optJSONObject("model")!!.optString("provider_id"))
        assertEquals("chatty", runnerConfig.optJSONObject("persona")!!.optString("persona_id"))
        assertEquals(128, runnerConfig.optJSONObject("misc")!!.optInt("max_steps"))
        assertEquals(
            "",
            target.optJSONObject("platform_settings")!!.optString("reply_prefix"),
        )
    }

    @Test
    fun `值没变时返回 false 不发请求`() {
        val target = config("""{"max_turns": 10, "overflow_strategy": "truncate_by_turns", "trim_turns": 1}""")
        assertFalse(
            ContextConfig.apply(
                target,
                ContextSettings(maxTurns = 10, overflowStrategy = ContextSettings.STRATEGY_TRUNCATE),
            ),
        )
    }

    @Test
    fun `缺少 compression 段时能补上`() {
        val target = JSONObject("""{"agent_runner": {"runner_type": "local", "config": {}}}""")
        assertTrue(ContextConfig.apply(target, ContextConfig.recommended()))

        val compression = target.optJSONObject("agent_runner")!!
            .optJSONObject("config")!!
            .optJSONObject("compression")!!
        assertEquals(10, compression.optInt("max_turns"))
        // 截断步长是 AstrBot 的必填项，补配置时必须一起给，否则它按默认 1 走也算错
        assertEquals(1, compression.optInt("trim_turns"))
    }

    @Test
    fun `连 agent_runner 都没有时也能补`() {
        val target = JSONObject("{}")
        assertTrue(ContextConfig.apply(target, ContextConfig.recommended()))
        assertEquals(10, ContextConfig.read(target).maxTurns)
    }

    @Test
    fun `推荐值就是十轮加直接丢弃`() {
        val recommended = ContextConfig.recommended()
        assertEquals(10, recommended.maxTurns)
        assertEquals(ContextSettings.STRATEGY_TRUNCATE, recommended.overflowStrategy)
    }

    @Test
    fun `轮数标签`() {
        assertEquals("不限", ContextSettings.turnLabel(0))
        assertEquals("10 轮", ContextSettings.turnLabel(10))
    }
}
