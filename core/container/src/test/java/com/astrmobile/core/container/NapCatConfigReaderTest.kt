package com.astrmobile.core.container

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * NapCat WebUI 口令的定位规则。
 *
 * 难点在于 config 目录的位置随安装方式变化（`/root/napcat/config`、
 * `/opt/QQ/.../napcat/config` 等），App 侧又不能递归遍历——QQ 的 resources
 * 目录有数万文件，在手机上遍历会卡好几秒。所以这里是三级定位：
 * 标记文件 → 固定候选 → 启动日志。
 */
class NapCatConfigReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var paths: ContainerPaths

    private fun prepare(): ContainerPaths {
        paths = ContainerPaths(temp.newFolder("files"), temp.newFolder("lib"))
        paths.rootfsDir.mkdirs()
        return paths
    }

    private fun writeHint(containerPath: String) {
        val hint = File(paths.rootfsDir, "root/.astrmobile-napcat-config")
        hint.parentFile?.mkdirs()
        hint.writeText(containerPath)
    }

    private fun writeWebui(relativeDir: String, token: String?) {
        val dir = File(paths.rootfsDir, relativeDir)
        dir.mkdirs()
        val json = JSONObject()
        if (token != null) json.put("token", token)
        File(dir, "webui.json").writeText(json.toString())
    }

    private fun writeLog(content: String) {
        val log = File(paths.rootfsDir, "root/napcat.log")
        log.parentFile?.mkdirs()
        log.writeText(content)
    }

    @Test
    fun `按标记文件定位配置目录`() {
        prepare()
        writeHint("/root/napcat/config")
        writeWebui("root/napcat/config", "hint-token-123456")

        assertEquals("hint-token-123456", NapCatConfigReader.readToken(paths))
    }

    @Test
    fun `没有标记文件时退回固定候选目录`() {
        prepare()
        writeWebui("opt/QQ/resources/app/app_launcher/napcat/config", "candidate-token-123")

        assertEquals("candidate-token-123", NapCatConfigReader.readToken(paths))
    }

    @Test
    fun `标记文件指向不存在的目录时继续找候选`() {
        prepare()
        writeHint("/nonexistent/napcat/config")
        writeWebui("root/napcat/config", "fallback-token-123")

        assertEquals("fallback-token-123", NapCatConfigReader.readToken(paths))
    }

    @Test
    fun `webui_json 里没有 token 时从启动日志抓`() {
        prepare()
        writeWebui("root/napcat/config", null)
        writeLog("[NapCat] WebUI token: logtoken987654321\n")

        assertEquals("logtoken987654321", NapCatConfigReader.readToken(paths))
    }

    @Test
    fun `完全没有配置时从启动日志抓`() {
        prepare()
        writeLog("NapCat started\ntoken: abcdefgh12345678\n")

        assertEquals("abcdefgh12345678", NapCatConfigReader.readToken(paths))
    }

    @Test
    fun `日志里的 token 太短时不误判`() {
        prepare()
        writeLog("token: abc\n")

        assertNull(NapCatConfigReader.readToken(paths))
    }

    @Test
    fun `什么线索都没有时返回 null`() {
        prepare()

        assertNull(NapCatConfigReader.readToken(paths))
    }

    @Test
    fun `日志不存在时不会因为读文件崩溃`() {
        prepare()
        writeWebui("root/napcat/config", null)

        assertNull(NapCatConfigReader.readToken(paths))
    }
}
