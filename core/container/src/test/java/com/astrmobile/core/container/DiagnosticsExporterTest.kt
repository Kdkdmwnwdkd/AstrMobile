package com.astrmobile.core.container

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * 诊断包的唯一硬要求：**发出去的包里不能有明文密钥**。
 *
 * 用户把诊断包发出来是为了求助，不是为了泄露自己的 API Key。所以这里除了
 * 逐字段验证，还做一次"整体扫描"：把 zip 里每个条目的内容拼起来，断言里面
 * 不存在原始密钥字符串。字段级断言可能漏掉某个嵌套层级，整体扫描不会。
 */
class DiagnosticsExporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun prepare(): ContainerPaths {
        val paths = ContainerPaths(temp.newFolder("files"), temp.newFolder("lib"))
        File(paths.rootfsDir, "root/data").mkdirs()
        return paths
    }

    private fun writeAstrBotConfig(paths: ContainerPaths) {
        val config = """
            {
              "dashboard": {
                "username": "astrbot",
                "password": "77b90590a8945a7d36c963981a307dc9",
                "pbkdf2_password": "pbkdf2_sha256${'$'}600000${'$'}salt${'$'}digest"
              },
              "provider": [
                {
                  "id": "astrmobile_llm",
                  "key": ["sk-super-secret-1234"],
                  "api_base": "https://api.deepseek.com/v1",
                  "model": "deepseek-chat"
                }
              ],
              "platform_settings": {
                "rate_limit": { "time": 60, "count": 8, "strategy": "stall" }
              }
            }
        """.trimIndent()

        File(paths.rootfsDir, "root/data/cmd_config.json").writeText(config)
    }

    private fun writeNapCatConfig(paths: ContainerPaths) {
        val dir = File(paths.rootfsDir, "root/napcat/config")
        dir.mkdirs()
        File(dir, "webui.json").writeText("""{"token":"napcat-plain-token-9999"}""")
    }

    private fun writeLogs(paths: ContainerPaths) {
        paths.containerLog.parentFile?.mkdirs()
        paths.containerLog.writeText("line 1\n启动 AstrBot\n容器运行中\n")
        paths.installLog.parentFile?.mkdirs()
        paths.installLog.writeText("apt install 完成\n")
    }

    private fun zipContents(file: File): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ZipFile(file).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                result[entry.name] = zip.getInputStream(entry).bufferedReader().readText()
            }
        }
        return result
    }

    @Test
    fun `密钥字段被替换但普通字段保留`() {
        val raw = """
            {
              "dashboard": { "username": "astrbot", "password": "abc123hash" },
              "provider": [ { "key": ["sk-1234567890"], "api_base": "https://api.deepseek.com/v1" } ],
              "platform_settings": { "rate_limit": { "count": 8 } }
            }
        """.trimIndent()

        val redacted = DiagnosticsExporter.redactJson(raw)

        assertFalse("password 没被替换", redacted.contains("abc123hash"))
        assertFalse("api key 没被替换", redacted.contains("sk-1234567890"))
        assertTrue("username 应保留", redacted.contains("astrbot"))
        assertTrue("api_base 应保留", redacted.contains("api.deepseek.com"))
        assertTrue("普通配置应保留", redacted.contains("\"count\": 8"))
    }

    @Test
    fun `非法 JSON 退化成逐行脱敏而不是原样输出`() {
        val broken = """{ "token": "plain-secret-value", "broken": """

        val redacted = DiagnosticsExporter.redactJson(broken)

        assertFalse(redacted.contains("plain-secret-value"))
        assertTrue(redacted.contains("token"))
    }

    @Test
    fun `空值保持为空 不伪装成已配置`() {
        val redacted = DiagnosticsExporter.redactJson("""{"token": "", "secret": null}""")

        val json = JSONObject(redacted)
        assertEquals("", json.getString("token"))
    }

    @Test
    fun `导出的 zip 里不含任何明文密钥`() {
        val paths = prepare()
        writeAstrBotConfig(paths)
        writeNapCatConfig(paths)
        writeLogs(paths)

        val target = File(temp.newFolder("out"), "diagnostics.zip")
        val exported = DiagnosticsExporter.export(
            paths = paths,
            target = target,
            meta = mapOf("app" to "0.17.3", "android" to "14"),
        ).getOrThrow()

        assertTrue("zip 没生成", exported.isFile && exported.length() > 0)

        val entries = zipContents(exported)
        assertTrue("缺 meta", entries.containsKey("meta.txt"))
        assertTrue("缺容器日志", entries.containsKey("logs/container.log"))
        assertTrue("缺安装日志", entries.containsKey("logs/install.log"))
        assertTrue("缺 AstrBot 配置", entries.containsKey("config/cmd_config.json"))
        assertTrue("缺 NapCat 配置", entries.containsKey("config/napcat/webui.json"))

        val everything = entries.values.joinToString("\n")
        listOf(
            "sk-super-secret-1234",
            "napcat-plain-token-9999",
            "77b90590a8945a7d36c963981a307dc9",
        ).forEach { secret ->
            assertFalse("zip 里出现了明文密钥：$secret", everything.contains(secret))
        }

        // 配置本身还在（否则"脱敏"可以靠"什么都不带"蒙混过去）
        assertTrue(entries.getValue("config/cmd_config.json").contains("deepseek"))
        assertTrue(entries.getValue("config/cmd_config.json").contains("\"count\": 8"))
    }

    @Test
    fun `日志只带尾部并标注截断`() {
        val paths = prepare()
        writeAstrBotConfig(paths)
        val huge = buildString {
            repeat(40_000) { appendLine("填充行 $it") }
            appendLine("最后一行：QQ 扫码失败")
        }
        paths.containerLog.parentFile?.mkdirs()
        paths.containerLog.writeText(huge)

        val target = File(temp.newFolder("out"), "diagnostics.zip")
        DiagnosticsExporter.export(paths, target, emptyMap()).getOrThrow()

        val log = zipContents(target).getValue("logs/container.log")
        assertTrue("应标注截断", log.startsWith("…（已截断"))
        assertTrue("应保留尾部内容", log.contains("QQ 扫码失败"))
        assertTrue("不应带上全文", log.length < huge.length)
    }

    @Test
    fun `没有日志文件时不会失败`() {
        val paths = prepare()

        val target = File(temp.newFolder("out"), "diagnostics.zip")
        val result = DiagnosticsExporter.export(paths, target, emptyMap())

        assertTrue(result.isSuccess)
        val entries = zipContents(target)
        assertTrue(entries.getValue("logs/container.log").contains("没有日志"))
    }
}
