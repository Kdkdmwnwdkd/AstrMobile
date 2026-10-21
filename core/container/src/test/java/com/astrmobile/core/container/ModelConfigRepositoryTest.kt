package com.astrmobile.core.container

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * AstrBot 4.29 会把 provider 拆成 provider + provider_sources 两层结构，
 * 这里把「迁移前」「迁移后」两种文件都跑一遍，防止回显又变空。
 */
class ModelConfigRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var paths: ContainerPaths
    private lateinit var configFile: File

    private fun prepare(content: String) {
        val filesDir = temp.newFolder("files")
        paths = ContainerPaths(filesDir, temp.newFolder("lib"))
        configFile = File(paths.rootfsDir, "root/data/cmd_config.json")
        configFile.parentFile.mkdirs()
        configFile.writeText(content)
    }

    private fun readConfig(): JSONObject = JSONObject(configFile.readText())

    private fun legacyConfig() = """
        {
          "provider": [
            {
              "id": "astrmobile_llm",
              "provider": "openai",
              "type": "openai_chat_completion",
              "provider_type": "chat_completion",
              "enable": true,
              "key": ["sk-legacy-1234567890"],
              "api_base": "https://api.deepseek.com/v1",
              "model": "deepseek-chat",
              "timeout": 120,
              "proxy": "",
              "custom_headers": {}
            }
          ]
        }
    """.trimIndent()

    /** AstrBot 迁移之后的真实形态：provider 只剩 6 个字段，其余进了 provider_sources */
    private fun migratedConfig() = """
        {
          "provider": [
            {
              "id": "astrmobile_llm",
              "provider_source_id": "astrmobile_llm_source",
              "model": "deepseek-chat",
              "modalities": [],
              "custom_extra_body": {},
              "enable": true
            }
          ],
          "provider_sources": [
            {
              "id": "astrmobile_llm_source",
              "provider": "openai",
              "type": "openai_chat_completion",
              "provider_type": "chat_completion",
              "key": ["sk-migrated-abcdefgh"],
              "api_base": "https://api.deepseek.com/v1",
              "timeout": 120,
              "proxy": "",
              "custom_headers": {}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `旧结构能读出来`() {
        prepare(legacyConfig())
        val models = ModelConfigRepository.read(paths)
        assertEquals(1, models.size)
        assertEquals("deepseek-chat", models[0].model)
        assertEquals("https://api.deepseek.com/v1", models[0].apiBase)
        assertTrue(models[0].keyHint.startsWith("sk-l"))
        assertTrue(models[0].enabled)
    }

    @Test
    fun `迁移后的新结构也能读出来`() {
        prepare(migratedConfig())
        val models = ModelConfigRepository.read(paths)
        assertEquals("迁移后必须还能读到，否则界面显示「还没有配置模型」", 1, models.size)
        assertEquals("deepseek-chat", models[0].model)
        assertEquals("https://api.deepseek.com/v1", models[0].apiBase)
        assertEquals("sk-m****efgh", models[0].keyHint)
    }

    @Test
    fun `保存旧结构会转成新结构`() {
        prepare(legacyConfig())
        val result = ModelConfigRepository.save(paths, "https://api.openai.com/v1", "sk-new-key-0001", "gpt-4o-mini")
        assertTrue(result.isSuccess)

        val root = readConfig()
        val provider = root.getJSONArray("provider").getJSONObject(0)
        assertFalse("别名 api_base 必须从 provider 里搬走", provider.has("api_base"))
        assertFalse(provider.has("key"))
        assertEquals("astrmobile_llm_source", provider.getString("provider_source_id"))
        assertEquals("gpt-4o-mini", provider.getString("model"))

        val source = root.getJSONArray("provider_sources").getJSONObject(0)
        assertEquals("https://api.openai.com/v1", source.getString("api_base"))
        assertEquals("sk-new-key-0001", source.getJSONArray("key").getString(0))
        assertEquals("openai_chat_completion", source.getString("type"))
    }

    @Test
    fun `新结构下换 Key 会写进 provider_sources`() {
        prepare(migratedConfig())
        ModelConfigRepository.save(paths, "https://api.moonshot.cn/v1", "sk-brand-new-key", "moonshot-v1-8k")

        val root = readConfig()
        assertEquals(1, root.getJSONArray("provider_sources").length())
        val source = root.getJSONArray("provider_sources").getJSONObject(0)
        assertEquals("sk-brand-new-key", source.getJSONArray("key").getString(0))
        assertEquals("https://api.moonshot.cn/v1", source.getString("api_base"))

        val provider = root.getJSONArray("provider").getJSONObject(0)
        assertEquals("moonshot-v1-8k", provider.getString("model"))
        assertFalse("provider 里不能残留旧 api_base，否则会盖掉新值", provider.has("api_base"))

        // 再读一遍，模拟用户退出页面重新进来
        val models = ModelConfigRepository.read(paths)
        assertEquals(1, models.size)
        assertEquals("moonshot-v1-8k", models[0].model)
        assertTrue(models[0].keyHint.contains("key"))
    }

    @Test
    fun `移除会把 provider 与 source 一起删掉`() {
        prepare(migratedConfig())
        ModelConfigRepository.remove(paths)

        val root = readConfig()
        assertEquals(0, root.getJSONArray("provider").length())
        assertNotNull(root.optJSONArray("provider_sources"))
        assertEquals(0, root.getJSONArray("provider_sources").length())
        assertTrue(ModelConfigRepository.read(paths).isEmpty())
    }

    @Test
    fun `用户自己加的其他模型不会被当成我们那条`() {
        prepare(
            """
            {
              "provider": [
                { "id": "other", "provider_source_id": "other_source", "model": "glm-4", "enable": true },
                { "id": "astrmobile_llm", "provider_source_id": "astrmobile_llm_source",
                  "model": "deepseek-chat", "enable": true }
              ],
              "provider_sources": [
                { "id": "other_source", "type": "openai_chat_completion",
                  "provider_type": "chat_completion", "api_base": "https://open.bigmodel.cn", "key": ["k1"] },
                { "id": "astrmobile_llm_source", "type": "openai_chat_completion",
                  "provider_type": "chat_completion", "api_base": "https://api.deepseek.com", "key": ["k2"] }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(2, ModelConfigRepository.read(paths).size)

        ModelConfigRepository.remove(paths)
        val root = readConfig()
        assertEquals(1, root.getJSONArray("provider").length())
        assertEquals("other", root.getJSONArray("provider").getJSONObject(0).getString("id"))
        assertEquals(1, root.getJSONArray("provider_sources").length())
    }
}
