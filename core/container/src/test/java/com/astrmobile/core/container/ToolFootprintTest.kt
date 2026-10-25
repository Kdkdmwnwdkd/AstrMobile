package com.astrmobile.core.container

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具开销统计。
 *
 * 这个功能的唯一作用是给用户一个"该删谁"的依据，所以算错比不算更糟：
 * 数字虚高会让人删掉在用的插件，虚低则完全没用。
 */
class ToolFootprintTest {

    private fun tool(
        name: String,
        origin: String,
        description: String = "",
        params: String = "{}",
        active: Boolean = true,
    ): JSONObject = JSONObject().apply {
        put("name", name)
        put("origin_name", origin)
        put("description", description)
        put("parameters", JSONObject(params))
        put("active", active)
    }

    @Test
    fun `按插件聚合并按开销排序`() {
        val tools = JSONArray().apply {
            put(tool("big_tool", "plugin_a", "做一件很复杂的事", """{"type":"object","properties":{"x":{"type":"string"}}}"""))
            put(tool("big_tool2", "plugin_a", "还有一件", "{}"))
            put(tool("small", "plugin_b", "", "{}"))
        }

        val result = ToolFootprint.parse(tools)

        assertEquals(2, result.size)
        assertEquals("plugin_a", result[0].pluginName)
        assertEquals(2, result[0].toolCount)
        assertTrue(result[0].estimatedTokens > result[1].estimatedTokens)
    }

    @Test
    fun `禁用的工具不计入`() {
        val tools = JSONArray().apply {
            put(tool("on", "plugin_a", "开着的"))
            put(tool("off", "plugin_a", "关掉的", active = false))
        }

        val result = ToolFootprint.parse(tools)

        assertEquals(1, result.size)
        assertEquals(1, result[0].toolCount)
    }

    @Test
    fun `没有来源的归到未知而不是丢掉`() {
        // 丢掉会让合计对不上，用户会怀疑统计不准
        val tools = JSONArray().apply {
            put(JSONObject().put("name", "orphan").put("active", true))
        }

        val result = ToolFootprint.parse(tools)

        assertEquals(1, result.size)
        assertEquals("未知来源", result[0].pluginName)
    }

    @Test
    fun `空列表`() {
        assertEquals(emptyList<PluginFootprint>(), ToolFootprint.parse(JSONArray()))
    }

    @Test
    fun `中文比英文占更多 token`() {
        // 统一按 4 字符除会把中文算少一半，这是这个统计最容易犯的错
        val english = ToolFootprint.estimateTokens("a".repeat(100))
        val chinese = ToolFootprint.estimateTokens("中".repeat(100))

        assertTrue("中文应该明显更贵：$english vs $chinese", chinese > english * 2)
    }

    @Test
    fun `空文本不产生 token`() {
        assertEquals(0, ToolFootprint.estimateTokens(""))
    }

    @Test
    fun `合计与占比`() {
        val tools = JSONArray().apply {
            put(tool("a", "p1", "x".repeat(400)))
            put(tool("b", "p2", "y".repeat(400)))
        }

        val result = ToolFootprint.parse(tools)
        assertEquals(
            result.sumOf { it.estimatedTokens },
            ToolFootprint.totalTokens(result),
        )
    }

    @Test
    fun `每天固定开销`() {
        val items = listOf(
            PluginFootprint(
                pluginName = "p",
                displayName = "P",
                toolCount = 1,
                schemaChars = 4000,
                estimatedTokens = 100_000,
                toolNames = listOf("t"),
            ),
        )

        // 10 万 token / 次 × 100 次 = 1000 万 token = 10 × 3.5 元
        assertEquals(35.0, ToolFootprint.dailyWasteYuan(items, 3.5, 100), 0.01)
        assertEquals(0.0, ToolFootprint.dailyWasteYuan(items, 0.0, 100), 0.01)
    }

    @Test
    fun `内置工具被标记出来`() {
        val tools = JSONArray().apply {
            put(tool("web_search", "builtin"))
            put(tool("my_tool", "some_plugin"))
        }

        val result = ToolFootprint.parse(tools)
        assertTrue(result.first { it.pluginName == "builtin" }.builtin)
        assertTrue(!result.first { it.pluginName == "some_plugin" }.builtin)
    }
}
