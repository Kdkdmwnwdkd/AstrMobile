package com.astrmobile.core.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cron 表达式翻译。
 *
 * 原则：**认不出来就不猜**。把 `0 9 * * 1-5` 显示成「每天 09:00」比显示原文更糟——
 * 用户会以为周末也会响，然后某天早上被一条莫名其妙的群消息弄醒。
 */
class CronScheduleTest {

    @Test
    fun `每天固定时刻`() {
        assertEquals("每天 08:00", CronSchedule.describe("0 8 * * *"))
        assertEquals("每天 23:30", CronSchedule.describe("30 23 * * *"))
    }

    @Test
    fun `每 N 分钟`() {
        assertEquals("每 30 分钟", CronSchedule.describe("*/30 * * * *"))
        assertEquals("每 15 分钟", CronSchedule.describe("*/15 * * * *"))
    }

    @Test
    fun `工作日`() {
        assertEquals("工作日 09:00", CronSchedule.describe("0 9 * * 1-5"))
        assertEquals("工作日 09:00", CronSchedule.describe("0 9 * * 1,2,3,4,5"))
    }

    @Test
    fun `每周某天`() {
        assertEquals("每周日 22:00", CronSchedule.describe("0 22 * * 0"))
        assertEquals("每周六 10:00", CronSchedule.describe("0 10 * * 6"))
    }

    @Test
    fun `每月某日`() {
        assertEquals("每月 1 日 07:30", CronSchedule.describe("30 7 1 * *"))
    }

    @Test
    fun `认不出来的形态返回 null 而不是瞎猜`() {
        // 分钟和小时都是 *，既不是固定时刻也不是每 N 分钟
        assertNull(CronSchedule.describe("* * * * *"))
        // 段数不对
        assertNull(CronSchedule.describe("0 8 * *"))
        // 中文
        assertNull(CronSchedule.describe("每天八点"))
        // 复杂区间
        assertNull(CronSchedule.describe("0 9-18 * * *"))
    }

    @Test
    fun `合法性粗校验挡掉明显错误`() {
        assertTrue(CronSchedule.looksValid("0 8 * * *"))
        assertTrue(CronSchedule.looksValid("*/5 * * * *"))
        assertTrue(CronSchedule.looksValid("30 7 1 * 1-5"))

        assertFalse(CronSchedule.looksValid("每天八点"))
        assertFalse(CronSchedule.looksValid("0 8 * *"))
        assertFalse(CronSchedule.looksValid(""))
    }

    // ---------------------------------------------------------------- 时间规格

    @Test
    fun `每天 生成标准五段式`() {
        val spec = CronSchedule.ScheduleSpec(CronSchedule.ScheduleSpec.Kind.Daily, 7, 30)
        assertEquals("30 7 * * *", CronSchedule.build(spec))
    }

    @Test
    fun `工作日 用区间写法`() {
        val spec = CronSchedule.ScheduleSpec(CronSchedule.ScheduleSpec.Kind.Weekdays, 9, 0)
        assertEquals("0 9 * * 1-5", CronSchedule.build(spec))
    }

    @Test
    fun `每周日 写成 0 而不是 7`() {
        // cron 里 0 和 7 都表示周日，写 7 有些实现不认
        val spec = CronSchedule.ScheduleSpec(
            CronSchedule.ScheduleSpec.Kind.Weekly, 20, 0, weekday = 7,
        )
        assertEquals("0 20 * * 0", CronSchedule.build(spec))
    }

    @Test
    fun `每月 用日字段`() {
        val spec = CronSchedule.ScheduleSpec(
            CronSchedule.ScheduleSpec.Kind.Monthly, 8, 0, dayOfMonth = 15,
        )
        assertEquals("0 8 15 * *", CronSchedule.build(spec))
    }

    @Test
    fun `越界的时分被夹到合法范围`() {
        val spec = CronSchedule.ScheduleSpec(CronSchedule.ScheduleSpec.Kind.Daily, 25, 90)
        assertEquals("59 23 * * *", CronSchedule.build(spec))
    }

    @Test
    fun `解析每天`() {
        val spec = CronSchedule.parse("30 7 * * *")
        assertEquals(CronSchedule.ScheduleSpec.Kind.Daily, spec?.kind)
        assertEquals(7, spec?.hour)
        assertEquals(30, spec?.minute)
    }

    @Test
    fun `解析工作日 两种写法都认`() {
        assertEquals(
            CronSchedule.ScheduleSpec.Kind.Weekdays,
            CronSchedule.parse("0 9 * * 1-5")?.kind,
        )
        assertEquals(
            CronSchedule.ScheduleSpec.Kind.Weekdays,
            CronSchedule.parse("0 9 * * 1,2,3,4,5")?.kind,
        )
    }

    @Test
    fun `解析每周日 0 和 7 都归一成 7`() {
        assertEquals(7, CronSchedule.parse("0 20 * * 0")?.weekday)
        assertEquals(7, CronSchedule.parse("0 20 * * 7")?.weekday)
        assertEquals(3, CronSchedule.parse("0 20 * * 3")?.weekday)
    }

    @Test
    fun `解析每月`() {
        val spec = CronSchedule.parse("0 8 15 * *")
        assertEquals(CronSchedule.ScheduleSpec.Kind.Monthly, spec?.kind)
        assertEquals(15, spec?.dayOfMonth)
    }

    @Test
    fun `认不出的形态返回 null 而不是猜`() {
        // 这几种硬塞进「每天」或「每月」会改变含义
        assertNull(CronSchedule.parse("0 9-18 * * *"))
        assertNull(CronSchedule.parse("0 0 1,15 * *"))
        assertNull(CronSchedule.parse("*/30 * * * *"))
        assertNull(CronSchedule.parse("0 8 * * 1-3"))
        assertNull(CronSchedule.parse("0 8 * 3 *"))
        assertNull(CronSchedule.parse("每天八点"))
        assertNull(CronSchedule.parse("0 8 * *"))
    }

    @Test
    fun `构建与解析往返一致`() {
        val cases = listOf(
            CronSchedule.ScheduleSpec(CronSchedule.ScheduleSpec.Kind.Daily, 7, 30),
            CronSchedule.ScheduleSpec(CronSchedule.ScheduleSpec.Kind.Weekdays, 9, 0),
            CronSchedule.ScheduleSpec(
                CronSchedule.ScheduleSpec.Kind.Weekly, 20, 15, weekday = 5,
            ),
            CronSchedule.ScheduleSpec(
                CronSchedule.ScheduleSpec.Kind.Monthly, 8, 0, dayOfMonth = 1,
            ),
        )
        cases.forEach { spec ->
            assertEquals(spec, CronSchedule.parse(CronSchedule.build(spec)))
        }
    }
}
