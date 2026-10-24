package com.astrmobile.core.container

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AlertJournalTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("alert-journal").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun record(ts: Long, title: String = "QQ 掉线了", critical: Boolean = true) =
        AlertRecord(timestamp = ts, title = title, detail = "容器还在运行", critical = critical)

    @Test
    fun `写入后能读回来`() {
        AlertJournal.append(dir, record(1_700_000_000_000))

        val list = AlertJournal.read(dir)
        assertEquals(1, list.size)
        assertEquals("QQ 掉线了", list[0].title)
        assertEquals("容器还在运行", list[0].detail)
        assertTrue(list[0].critical)
    }

    @Test
    fun `新的排在前面`() {
        AlertJournal.append(dir, record(100, "第一条"))
        AlertJournal.append(dir, record(200, "第二条"))
        AlertJournal.append(dir, record(300, "第三条"))

        val titles = AlertJournal.read(dir).map { it.title }
        assertEquals(listOf("第三条", "第二条", "第一条"), titles)
    }

    @Test
    fun `超过上限时丢掉最旧的`() {
        repeat(55) { index ->
            AlertJournal.append(dir, record(index.toLong(), "第 $index 条"))
        }

        val list = AlertJournal.read(dir)
        assertEquals(50, list.size)
        // 最新的还在，最旧的被挤掉
        assertEquals("第 54 条", list.first().title)
        assertEquals("第 5 条", list.last().title)
    }

    @Test
    fun `文件损坏时返回空列表而不是崩溃`() {
        File(dir, "alert-history.json").writeText("{这不是合法 JSON")

        assertTrue(AlertJournal.read(dir).isEmpty())
    }

    @Test
    fun `空目录返回空列表`() {
        assertTrue(AlertJournal.read(dir).isEmpty())
    }

    @Test
    fun `缺标题的记录被跳过`() {
        File(dir, "alert-history.json").writeText(
            """[{"timestamp":1,"title":""},{"timestamp":2,"title":"有效"}]""",
        )

        val list = AlertJournal.read(dir)
        assertEquals(1, list.size)
        assertEquals("有效", list[0].title)
    }

    @Test
    fun `清空之后读到空`() {
        AlertJournal.append(dir, record(100))
        AlertJournal.clear(dir)

        assertTrue(AlertJournal.read(dir).isEmpty())
    }

    @Test
    fun `损坏之后仍能继续写入`() {
        File(dir, "alert-history.json").writeText("坏了")
        AlertJournal.append(dir, record(500, "新的"))

        val list = AlertJournal.read(dir)
        assertEquals(1, list.size)
        assertEquals("新的", list[0].title)
    }
}
