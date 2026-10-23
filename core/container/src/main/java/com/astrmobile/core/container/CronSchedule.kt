package com.astrmobile.core.container

/**
 * Cron 表达式 → 人话。
 *
 * 定时任务最大的门槛不是"会不会用"，是"看不看得懂"。`0 9 * * 1-5`
 * 这种表达式对写代码的人也要想两秒，对普通用户完全是天书。
 *
 * 这里只覆盖常见的几种形态，认不出来就原样显示——**不猜**。
 * 猜错的后果是任务在错误的时间触发，比看不懂严重得多。
 */
object CronSchedule {

    /** 一条定时任务的时间描述，形如「每天 08:00」。 */
    fun describe(expression: String): String? {
        val parts = expression.trim().split(Regex("\\s+"))
        if (parts.size != 5) return null

        val (minute, hour, day, month, weekday) = parts

        // 只处理"分钟/小时是固定数字或 */n"的情况，其余原样返回
        val everyMinutes = step(minute)
        if (everyMinutes != null && hour == "*" && day == "*" && month == "*" && weekday == "*") {
            return "每 $everyMinutes 分钟"
        }

        val hourValue = hour.toIntOrNull()
        val minuteValue = minute.toIntOrNull()
        if (hourValue == null || minuteValue == null) return null

        val time = String.format(java.util.Locale.US, "%02d:%02d", hourValue, minuteValue)

        return when {
            day == "*" && month == "*" && weekday == "*" -> "每天 $time"
            day == "*" && month == "*" && weekday == "1-5" -> "工作日 $time"
            day == "*" && month == "*" && weekday == "1,2,3,4,5" -> "工作日 $time"
            day == "*" && month == "*" -> weekdayName(weekday)?.let { "每$it $time" }
            month == "*" && weekday == "*" -> day.toIntOrNull()?.let { "每月 $it 日 $time" }
            else -> null
        }
    }

    /** 解析「每 N 分钟」的步进写法（星号 + 斜杠 + 数字）；其它形态返回 null。 */
    private fun step(field: String): Int? {
        if (!field.startsWith("*/")) return null
        return field.removePrefix("*/").toIntOrNull()?.takeIf { it in 1..59 }
    }

    private fun weekdayName(field: String): String? = when (field) {
        "0", "7" -> "周日"
        "1" -> "周一"
        "2" -> "周二"
        "3" -> "周三"
        "4" -> "周四"
        "5" -> "周五"
        "6" -> "周六"
        else -> null
    }

    /**
     * 粗校验：5 段、每段字符合法。
     *
     * 不做完整语法校验——真正的判断交给 AstrBot（它用 APScheduler 解析，
     * 报错信息比我们能给的准）。这里只挡掉明显的错，比如"每天八点"这种中文输入。
     */
    fun looksValid(expression: String): Boolean {
        val parts = expression.trim().split(Regex("\\s+"))
        if (parts.size != 5) return false
        return parts.all { field -> field.matches(Regex("[0-9*/,\\-]+")) }
    }
}
