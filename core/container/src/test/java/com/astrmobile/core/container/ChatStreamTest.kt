package com.astrmobile.core.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamTest {

    private fun feed(vararg lines: String): ChatStreamState =
        lines.fold(ChatStreamState()) { state, line -> ChatStream.consume(state, line) }

    @Test
    fun `流式增量逐条累加`() {
        val state = feed(
            """data: {"type":"plain","data":"你好","streaming":true}""",
            """data: {"type":"plain","data":"，世界","streaming":true}""",
            """data: {"type":"plain","data":"！","streaming":true}""",
        )

        assertEquals("你好，世界！", state.text)
        assertFalse(state.done)
    }

    @Test
    fun `非流式事件是整段覆盖而不是追加`() {
        // streaming=false 时 data 是全文。当成增量会得到 "你好你好，世界"
        val state = feed(
            """data: {"type":"plain","data":"你好","streaming":false}""",
            """data: {"type":"plain","data":"你好，世界","streaming":false}""",
        )

        assertEquals("你好，世界", state.text)
    }

    @Test
    fun `心跳与空行被忽略`() {
        val state = feed(
            ": heartbeat",
            "",
            "   ",
            """data: {"type":"plain","data":"ok","streaming":true}""",
            ": heartbeat",
        )

        assertEquals("ok", state.text)
        assertNull(state.error)
    }

    @Test
    fun `工具调用记录不进正文`() {
        val state = feed(
            """data: {"type":"plain","data":"{\"name\":\"search\"}","streaming":true,"chain_type":"tool_call"}""",
            """data: {"type":"plain","data":"结果...","streaming":true,"chain_type":"tool_call_result"}""",
            """data: {"type":"plain","data":"查到了","streaming":true}""",
        )

        assertEquals("查到了", state.text)
    }

    @Test
    fun `推理过程单独存放`() {
        val state = feed(
            """data: {"type":"plain","data":"先想一下","streaming":true,"chain_type":"reasoning"}""",
            """data: {"type":"plain","data":"……","streaming":true,"chain_type":"reasoning"}""",
            """data: {"type":"plain","data":"答案是 42","streaming":true}""",
        )

        assertEquals("答案是 42", state.text)
        assertEquals("先想一下……", state.thinking)
    }

    @Test
    fun `坏的 JSON 不中断整个流`() {
        val state = feed(
            """data: {"type":"plain","data":"前半","streaming":true}""",
            "data: {不是合法 JSON",
            """data: {"type":"plain","data":"后半","streaming":true}""",
        )

        assertEquals("前半后半", state.text)
        assertNull(state.error)
    }

    @Test
    fun `error 事件带出原因并结束`() {
        val state = feed(
            """data: {"type":"error","data":"模型额度不足"}""",
        )

        assertEquals("模型额度不足", state.error)
        assertTrue(state.done)
    }

    @Test
    fun `error 没有文案时给兜底提示`() {
        val state = feed("""data: {"type":"error","data":""}""")

        assertEquals("对话失败", state.error)
    }

    @Test
    fun `complete 置位 done 且后续事件不回退`() {
        val state = feed(
            """data: {"type":"plain","data":"好的","streaming":true}""",
            """data: {"type":"complete","data":""}""",
            """data: {"type":"agent_stats","data":{}}""",
        )

        assertTrue(state.done)
        assertEquals("好的", state.text)
    }

    @Test
    fun `非 data 开头的行一律忽略`() {
        val state = feed(
            "event: message",
            "id: 42",
            "retry: 3000",
            "hello",
        )

        assertEquals(ChatStreamState(), state)
    }
}
