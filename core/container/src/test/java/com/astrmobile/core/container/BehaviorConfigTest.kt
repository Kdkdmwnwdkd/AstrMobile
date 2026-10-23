package com.astrmobile.core.container

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorConfigTest {

    private fun config(json: String) = JSONObject(json)

    @Test
    fun `从完整配置读出各项`() {
        val raw = config(
            """
            {
              "wake_prefix": ["/", "!"],
              "platform_settings": {
                "reply_prefix": "[bot] ",
                "reply_with_mention": true,
                "reply_with_quote": true,
                "friend_message_needs_wake_prefix": true,
                "unique_session": true,
                "enable_id_white_list": false,
                "id_whitelist": ["123456", "7890"]
              }
            }
            """.trimIndent(),
        )

        val settings = BehaviorConfig.read(raw)

        assertEquals(listOf("/", "!"), settings.wakePrefixes)
        assertEquals("[bot] ", settings.replyPrefix)
        assertTrue(settings.replyWithMention)
        assertTrue(settings.replyWithQuote)
        assertTrue(settings.friendNeedsWake)
        assertTrue(settings.uniqueSession)
        assertFalse(settings.whiteListEnabled)
        assertEquals(listOf("123456", "7890"), settings.whiteList)
    }

    @Test
    fun `字段缺失时用默认值而不是报错`() {
        val settings = BehaviorConfig.read(config("{}"))

        assertEquals(listOf("/"), settings.wakePrefixes)
        assertEquals("", settings.replyPrefix)
        assertFalse(settings.replyWithMention)
        assertTrue(settings.whiteListEnabled)
        assertTrue(settings.whiteList.isEmpty())
    }

    @Test
    fun `明确写成空数组表示不要唤醒前缀`() {
        // 和"字段缺失"必须区分：空数组是用户故意的，回退成 ["/"] 会让人以为设置没生效
        val settings = BehaviorConfig.read(config("""{"wake_prefix": []}"""))

        assertTrue(settings.wakePrefixes.isEmpty())
    }

    @Test
    fun `写回只动认识的字段`() {
        val raw = config(
            """
            {
              "config_version": 5,
              "wake_prefix": ["/"],
              "admins_id": ["astrbot"],
              "platform_settings": {
                "reply_prefix": "",
                "rate_limit": {"time": 60, "count": 8, "strategy": "stall"},
                "segmented_reply": {"enable": true}
              },
              "provider_settings": {"enable": true}
            }
            """.trimIndent(),
        )

        BehaviorConfig.apply(raw, BehaviorSettings(wakePrefixes = listOf("#")))

        // 改的
        assertEquals("#", raw.getJSONArray("wake_prefix").getString(0))
        // 没动的必须原样还在 —— 这是整份替换接口，漏一个就丢配置
        assertEquals(listOf("astrbot"), raw.getJSONArray("admins_id").toList())
        assertEquals(5, raw.getInt("config_version"))
        assertEquals(8, raw.getJSONObject("platform_settings").getJSONObject("rate_limit").getInt("count"))
        assertTrue(raw.getJSONObject("platform_settings").getJSONObject("segmented_reply").getBoolean("enable"))
        assertTrue(raw.getJSONObject("provider_settings").getBoolean("enable"))
    }

    @Test
    fun `值没变时返回 false，不触发写请求`() {
        val raw = config(
            """{"wake_prefix": ["/"], "platform_settings": {"reply_with_mention": false}}""",
        )

        val changed = BehaviorConfig.apply(
            raw,
            BehaviorSettings(wakePrefixes = listOf("/"), replyWithMention = false),
        )

        assertFalse(changed)
    }

    @Test
    fun `改一个布尔项也算改动`() {
        val raw = config("""{"wake_prefix": ["/"]}""")

        val changed = BehaviorConfig.apply(raw, BehaviorSettings(replyWithMention = true))

        assertTrue(changed)
        assertTrue(raw.getJSONObject("platform_settings").getBoolean("reply_with_mention"))
    }

    @Test
    fun `没有 platform_settings 时自动补一个`() {
        val raw = config("""{"wake_prefix": ["/"]}""")

        BehaviorConfig.apply(raw, BehaviorSettings(replyWithQuote = true))

        assertTrue(raw.has("platform_settings"))
        assertTrue(raw.getJSONObject("platform_settings").getBoolean("reply_with_quote"))
    }

    @Test
    fun `白名单增删能正确写回`() {
        val raw = config(
            """{"platform_settings": {"id_whitelist": ["111"]}}""",
        )

        val changed = BehaviorConfig.apply(
            raw,
            BehaviorSettings(whiteList = listOf("111", "222")),
        )

        assertTrue(changed)
        assertEquals(
            listOf("111", "222"),
            raw.getJSONObject("platform_settings").getJSONArray("id_whitelist").toList(),
        )
    }

    @Test
    fun `回复前缀前后的空白被去掉`() {
        val raw = config("{}")

        BehaviorConfig.apply(raw, BehaviorSettings(replyPrefix = "  [bot]  "))

        assertEquals("[bot]", raw.getJSONObject("platform_settings").getString("reply_prefix"))
    }

    @Test
    fun `往返一致`() {
        val original = BehaviorSettings(
            wakePrefixes = listOf("/", "小助手"),
            replyPrefix = "（小声）",
            replyWithMention = true,
            replyWithQuote = false,
            friendNeedsWake = true,
            uniqueSession = true,
            whiteListEnabled = false,
            whiteList = listOf("g1", "g2"),
        )

        val raw = config("{}")
        BehaviorConfig.apply(raw, original)

        assertEquals(original, BehaviorConfig.read(raw))
    }

    private fun JSONArray.toList(): List<String> =
        (0 until length()).map { optString(it) }
}
