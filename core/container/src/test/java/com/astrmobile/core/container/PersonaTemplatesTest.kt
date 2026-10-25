package com.astrmobile.core.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人格模板与 id 校验。
 *
 * 这两块看起来琐碎，但错了的后果都不轻：
 * - 模板的示例对话必须是偶数条，否则 AstrBot 会把最后一句当成"对方说的"，
 *   语气示例直接错位
 * - id 里混进斜杠，AstrBot 拼路径时行为不可预测，接口还照样返回 ok
 */
class PersonaTemplatesTest {

    @Test
    fun `每个模板都是成对的示例对话`() {
        PersonaTemplates.all.forEach { template ->
            assertTrue(
                "模板「${template.name}」的示例对话必须是偶数条",
                template.dialogs.size % 2 == 0,
            )
            assertTrue("模板「${template.name}」人设为空", template.prompt.isNotBlank())
            assertTrue("模板「${template.name}」没有示例对话", template.dialogs.isNotEmpty())
        }
    }

    @Test
    fun `模板里没有占位的空句子`() {
        PersonaTemplates.all.forEach { template ->
            template.dialogs.forEach { line ->
                assertTrue("模板「${template.name}」里有一句是空的", line.isNotBlank())
            }
        }
    }

    @Test
    fun `示例对话奇数条时截掉最后一句`() {
        val odd = listOf("在吗", "在", "问你个事")
        assertEquals(listOf("在吗", "在"), PersonaTemplates.pairsOf(odd))
    }

    @Test
    fun `示例对话保留空位但不改顺序`() {
        val pairs = listOf(" 甲 ", " 乙 ")
        assertEquals(listOf("甲", "乙"), PersonaTemplates.pairsOf(pairs))
    }

    @Test
    fun `id 里的非法字符被替换`() {
        assertEquals("my-persona", PersonaTemplates.sanitizeId("my persona"))
        assertEquals("a-b-c", PersonaTemplates.sanitizeId("a/b\\c"))
    }

    @Test
    fun `纯非 ASCII 的 id 退回兜底名`() {
        // AstrBot 用 id 拼路径和数据库 key，中文名一律换掉
        assertEquals("persona", PersonaTemplates.sanitizeId("中文"))
    }

    @Test
    fun `id 全为空时给个兜底名`() {
        assertEquals("persona", PersonaTemplates.sanitizeId("   "))
        assertEquals("persona", PersonaTemplates.sanitizeId("///"))
    }

    @Test
    fun `id 过长会截断`() {
        val long = "a".repeat(80)
        assertTrue(PersonaTemplates.sanitizeId(long).length <= 32)
    }

    @Test
    fun `合法 id 通过校验`() {
        assertTrue(PersonaTemplates.isValidId("persona1"))
        assertTrue(PersonaTemplates.isValidId("my-persona_2"))
        assertFalse(PersonaTemplates.isValidId("has space"))
        assertFalse(PersonaTemplates.isValidId(""))
        assertFalse(PersonaTemplates.isValidId("a".repeat(33)))
    }

    @Test
    fun `按名字查模板`() {
        assertNotNull(PersonaTemplates.byName("话少的室友"))
        assertNull(PersonaTemplates.byName("不存在的模板"))
    }

    @Test
    fun `解析人格列表时读出示例对话与兜底回复`() {
        val json = """
            {
              "status": "ok",
              "data": [
                {
                  "persona_id": "default",
                  "system_prompt": "你是一个室友",
                  "begin_dialogs": ["今天好累", "冰箱里有剩饭"],
                  "custom_error_message": "我先静静",
                  "tools": null
                }
              ]
            }
        """.trimIndent()

        // 走一遍与 AstrBotApi 相同的解析路径：取 data 层（数组）再逐项读
        val root = org.json.JSONObject(json).optJSONArray("data")!!
        val item = root.optJSONObject(0)!!
        val dialogs = item.optJSONArray("begin_dialogs")!!
        assertTrue(dialogs.length() == 2)
        assertEquals("冰箱里有剩饭", dialogs.optString(1))
        assertEquals("我先静静", item.optString("custom_error_message"))
    }
}
