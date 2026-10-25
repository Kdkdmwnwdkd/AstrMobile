package com.astrmobile.core.container

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 精选插件与依赖安装的边界。
 *
 * 这两块都要拼路径、拼 shell 命令，所以「名字里带分号」这种输入
 * 必须被挡住——注释里写"注意安全"没有用，得让测试真的喂进去。
 */
class FeaturedPluginsTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File =
        File.createTempFile("plugins", "").let {
            it.delete()
            it.mkdirs()
            tempDirs.add(it)
            it
        }

    @After
    fun cleanup() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun pathsFor(filesDir: File): ContainerPaths =
        ContainerPaths(filesDir = filesDir, nativeLibDir = File(filesDir, "lib"))

    @Test
    fun `每个精选插件都指向合法的 github 仓库`() {
        FeaturedPlugins.all.forEach { item ->
            assertTrue(
                "「${item.displayName}」的 repo 不是 owner/name：${item.repo}",
                item.repo.matches(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")),
            )
            assertTrue(
                "「${item.displayName}」的目录名不合法",
                FeaturedPlugins.isSafeDirName(FeaturedPlugins.dirNameOf(item.repo)),
            )
        }
    }

    @Test
    fun `每个精选插件都写了额外成本`() {
        // 这几个插件都会让账单变高，漏写等于骗人
        FeaturedPlugins.all.forEach { item ->
            assertTrue("「${item.displayName}」没写成本", item.costNote.isNotBlank())
            assertTrue("「${item.displayName}」没写说明", item.note.isNotBlank())
        }
    }

    @Test
    fun `仓库名转目录名`() {
        assertEquals(
            "astrbot_plugin_memorix",
            FeaturedPlugins.dirNameOf("exynos967/astrbot_plugin_memorix"),
        )
        assertEquals("plugin", FeaturedPlugins.dirNameOf("someone/plugin.git"))
    }

    @Test
    fun `危险的目录名被拒绝`() {
        // 这些会被拼进 `cd '<dir>' && pip install`，必须挡在门外
        assertFalse(FeaturedPlugins.isSafeDirName("a; rm -rf /"))
        assertFalse(FeaturedPlugins.isSafeDirName("a && curl evil"))
        assertFalse(FeaturedPlugins.isSafeDirName("../escape"))
        assertFalse(FeaturedPlugins.isSafeDirName("a b"))
        assertFalse(FeaturedPlugins.isSafeDirName(""))
    }

    @Test
    fun `正常的目录名通过`() {
        assertTrue(FeaturedPlugins.isSafeDirName("astrbot_plugin_memorix"))
        assertTrue(FeaturedPlugins.isSafeDirName("plugin-name.v2"))
    }

    @Test
    fun `按仓库找精选项`() {
        assertNotNull(FeaturedPlugins.byRepo("Renyus/astrbot_plugin_self_evolution"))
        // 大小写不该影响命中
        assertNotNull(FeaturedPlugins.byRepo("renyus/ASTRBOT_PLUGIN_SELF_EVOLUTION"))
        assertNull(FeaturedPlugins.byRepo("someone/unknown"))
    }

    @Test
    fun `有 requirements 才提示装依赖`() {
        val filesDir = tempDir()
        val paths = pathsFor(filesDir)
        val installer = PluginDependencyInstaller(paths)

        val withDeps = File(paths.rootfsDir, "root/data/plugins/demo_with_deps")
        withDeps.mkdirs()
        File(withDeps, "requirements.txt").writeText("jieba>=0.42")

        val withoutDeps = File(paths.rootfsDir, "root/data/plugins/demo_minimal")
        withoutDeps.mkdirs()

        assertTrue(installer.hasRequirements("demo_with_deps"))
        assertFalse(installer.hasRequirements("demo_minimal"))
        assertFalse(installer.hasRequirements("不存在的插件"))
    }

    @Test
    fun `危险目录名不会去读文件系统`() {
        val filesDir = tempDir()
        val installer = PluginDependencyInstaller(pathsFor(filesDir))
        assertFalse(installer.hasRequirements("../../../etc"))
        assertFalse(installer.hasRequirements("a;b"))
    }
}
