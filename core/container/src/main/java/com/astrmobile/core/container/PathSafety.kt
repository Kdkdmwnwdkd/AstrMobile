package com.astrmobile.core.container

import java.io.File

/**
 * 路径边界判断。
 *
 * 这里只有一个函数，但它错过一次就够写穿整个 App 的数据目录：
 *
 * ```kotlin
 * // 错的写法
 * target.canonicalPath.startsWith(root.canonicalPath)
 * ```
 *
 * root 是 `/a/b` 时，`/a/bc/evil` 也能通过。压缩包、tar 包里的
 * `../bc/evil` 正好能落在这个位置——隔壁就是 databases 和 shared_prefs。
 *
 * 正确做法是按分隔符比较，或者路径完全相等。
 */
object PathSafety {

    fun isInside(root: File, target: File): Boolean {
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return false
        val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return false
        if (targetPath == rootPath) return true
        val prefix = rootPath.trimEnd(File.separatorChar) + File.separator
        return targetPath.startsWith(prefix)
    }
}
