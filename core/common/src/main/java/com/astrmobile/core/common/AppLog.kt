package com.astrmobile.core.common

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 统一的行式日志格式，宿主侧与容器侧共用同一套前缀，
 * 便于把 "App 日志" 和 "容器输出" 混排在同一条时间线上排查问题。
 */
object AppLog {

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun line(tag: String, message: String): String =
        "[${LocalTime.now().format(timeFormatter)}][$tag] $message"

    fun container(message: String): String = line("CT", message)

    fun app(message: String): String = line("APP", message)

    fun error(tag: String, message: String, throwable: Throwable? = null): String {
        val suffix = throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        return line(tag, "ERROR $message$suffix")
    }
}

/** 简单的可增长环形缓冲，避免长时间运行后日志列表无限增长。 */
class RingBuffer<T>(private val capacity: Int) {

    private val items = ArrayDeque<T>(capacity)

    @Synchronized
    fun add(item: T) {
        if (items.size >= capacity) {
            items.removeFirst()
        }
        items.addLast(item)
    }

    @Synchronized
    fun addAll(values: Collection<T>) {
        values.forEach(::add)
    }

    @Synchronized
    fun snapshot(): List<T> = items.toList()

    @Synchronized
    fun clear() = items.clear()
}
