package com.astrmobile.core.container

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条告警记录：什么时候、因为什么。 */
data class AlertRecord(
    val timestamp: Long,
    val title: String,
    val detail: String,
    /** 失败、掉线这类需要马上处理的，和"正在重启"这种自愈中的区别开。 */
    val critical: Boolean,
)

/**
 * 本地告警历史。
 *
 * 通知弹过就没了。手机放在家里跑着，人回来看到状态正常，但中间可能掉过
 * 三次线、重启过两次——这些如果不记下来，就完全无从得知。
 *
 * 只记**触发**，不记恢复：用户想知道的是"出过几次事"，
 * 而不是"每次持续了多久"。后者要靠配对，配错一次比不显示更让人困惑。
 */
object AlertJournal {

    private const val FILE_NAME = "alert-history.json"
    private const val MAX_ENTRIES = 50

    /**
     * 追加一条，返回追加后的完整列表（新的在前）。
     *
     * 直接返回列表是为了让调用方省一次读盘——通知发出去的同时界面就可能
     * 在刷。
     */
    fun append(dir: File, record: AlertRecord): List<AlertRecord> = synchronized(this) {
        val updated = (listOf(record) + read(dir)).take(MAX_ENTRIES)
        write(dir, updated)
        updated
    }

    fun read(dir: File): List<AlertRecord> = synchronized(this) {
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return emptyList()

        val text = runCatching { file.readText() }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyList()

        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = item.optString("title")
                if (title.isBlank()) continue
                add(
                    AlertRecord(
                        timestamp = item.optLong("timestamp", 0L),
                        title = title,
                        detail = item.optString("detail"),
                        critical = item.optBoolean("critical", false),
                    ),
                )
            }
        }
    }

    fun clear(dir: File) = synchronized(this) {
        runCatching { File(dir, FILE_NAME).delete() }
        Unit
    }

    /**
     * 先写临时文件再改名。
     *
     * 进程被杀在写一半的时候，留下一个截断的 JSON 会让下次读直接失败——
     * 而这个文件存在的意义就是"事后看"，它自己坏了最讽刺。
     */
    private fun write(dir: File, records: List<AlertRecord>) {
        runCatching {
            dir.mkdirs()
            val array = JSONArray()
            records.forEach { record ->
                array.put(
                    JSONObject()
                        .put("timestamp", record.timestamp)
                        .put("title", record.title)
                        .put("detail", record.detail)
                        .put("critical", record.critical),
                )
            }
            val target = File(dir, FILE_NAME)
            val temp = File(dir, "$FILE_NAME.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(target)) {
                target.writeText(array.toString())
                temp.delete()
            }
        }
    }
}
