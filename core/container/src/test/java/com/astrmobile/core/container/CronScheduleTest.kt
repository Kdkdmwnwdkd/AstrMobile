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
}
