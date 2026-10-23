package com.astrmobile.core.container

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * AstrBot 后台登录凭据的解析规则。
 *
 * 这段逻辑有个容易踩的坑：配置里的 `password` 存的是 **md5 哈希**，不是明文。
 * 早期版本直接把它当密码显示，用户抄进去必然登录失败。所以这里覆盖三种情形：
 * 没改过（哈希等于默认口令）、从没设过（两个字段都空）、用户自己改过（无法还原）。
 */
class AstrBotConfigReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var paths: ContainerPaths
    private lateinit var configFile: File

    /** md5("astrbot")，与 AstrBotConfigReader 内部常量一致。 */
    private val defaultPasswordMd5 = "77b90590a8945a7d36c963981a307dc9"

    private fun newPaths(): ContainerPaths =
        ContainerPaths(temp.newFolder("files"), temp.newFolder("lib"))

    private fun writeConfig(content: String) {
        paths = newPaths()
        configFile = File(paths.rootfsDir, "root/data/cmd_config.json")
        configFile.parentFile?.mkdirs()
        configFile.writeText(content)
    }

    private fun dashboard(vararg pairs: Pair<String, Any?>): String {
        val obj = JSONObject()
        pairs.forEach { (key, value) -> if (value != null) obj.put(key, value) }
        return JSONObject().put("dashboard", obj).toString()
    }

    @Test
    fun `未改过的默认口令能还原成明文`() {
        writeConfig(
            dashboard(
                "username" to "astrbot",
                "password" to defaultPasswordMd5,
                "password_change_required" to false,
            ),
        )

        val credentials = AstrBotConfigReader.readDashboard(paths)

        assertEquals("astrbot", credentials?.username)
        assertEquals("astrbot", credentials?.password)
    }

    @Test
    fun `两个字段都为空时按默认口令处理`() {
        writeConfig(dashboard("username" to "", "password" to ""))

        val credentials = AstrBotConfigReader.readDashboard(paths)

        assertEquals("astrbot", credentials?.username)
        assertEquals("astrbot", credentials?.password)
    }

    @Test
    fun `用户改过密码时返回 null 而不是猜一个错误的值`() {
        writeConfig(
            dashboard(
                "username" to "admin",
                "password" to "5f4dcc3b5aa765d61d8327deb882cf99",
                "pbkdf2_password" to "pbkdf2_sha256\$600000\$abc\$def",
            ),
        )

        assertNull(AstrBotConfigReader.readDashboard(paths))
    }

    @Test
    fun `自定义用户名保留原值`() {
        writeConfig(dashboard("username" to "mybot", "password" to defaultPasswordMd5))

        assertEquals("mybot", AstrBotConfigReader.readDashboard(paths)?.username)
    }

    @Test
    fun `配置文件不存在时返回 null`() {
        paths = newPaths()

        assertNull(AstrBotConfigReader.readDashboard(paths))
    }

    @Test
    fun `配置不是合法 JSON 时返回 null 而不是崩溃`() {
        writeConfig("{ 这不是 json")

        assertNull(AstrBotConfigReader.readDashboard(paths))
    }

    @Test
    fun `缺少 dashboard 字段时返回 null`() {
        writeConfig("""{"provider": []}""")

        assertNull(AstrBotConfigReader.readDashboard(paths))
    }
}
