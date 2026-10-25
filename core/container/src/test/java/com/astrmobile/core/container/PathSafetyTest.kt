package com.astrmobile.core.container

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 路径边界。
 *
 * 这个判断错过一次就够写穿 App 私有目录：压缩包和 tar 包里的 `../` 会
 * 落到隔壁的 databases / shared_prefs。所以这里花的力气比"看起来值"多。
 */
class PathSafetyTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File =
        File.createTempFile("pathsafe", "").let {
            it.delete()
            it.mkdirs()
            tempDirs.add(it)
            it
        }

    @After
    fun cleanup() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    @Test
    fun `正常子路径通过`() {
        val root = File(tempDir(), "plugins")
        root.mkdirs()
        assertTrue(PathSafety.isInside(root, File(root, "a/b/c.txt")))
    }

    @Test
    fun `根目录自身通过`() {
        val root = tempDir()
        assertTrue(PathSafety.isInside(root, root))
    }

    @Test
    fun `同级前缀目录不通过`() {
        // 这是最容易写错的一种：字符串前缀比较会把它当成"在里面"
        val base = tempDir()
        val root = File(base, "b").apply { mkdirs() }
        val sibling = File(base, "bc/evil.txt")

        assertFalse(PathSafety.isInside(root, sibling))
    }

    @Test
    fun `上跳不通过`() {
        val base = tempDir()
        val root = File(base, "b").apply { mkdirs() }
        assertFalse(PathSafety.isInside(root, File(root, "../outside.txt")))
        assertFalse(PathSafety.isInside(root, File(root, "../../etc/passwd")))
    }

    @Test
    fun `不存在的深层路径也按父目录判断`() {
        val root = File(tempDir(), "plugins").apply { mkdirs() }
        // 文件还没创建，canonicalPath 会解析已存在的父目录
        assertTrue(PathSafety.isInside(root, File(root, "not/created/yet.txt")))
        assertFalse(PathSafety.isInside(root, File(root, "../escape.txt")))
    }
}
