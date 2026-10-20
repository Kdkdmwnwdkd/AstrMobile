package com.astrmobile.core.container

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 一个知识库。 */
data class KnowledgeBase(
    val id: String,
    val name: String,
    val documentCount: Int,
    val embeddingProviderId: String,
)

/** 知识库里的一个文档。 */
data class KnowledgeDocument(
    val id: String,
    val name: String,
    val status: String,
)

/** 向量模型（embedding）配置。 */
data class EmbeddingConfig(
    val id: String,
    val apiBase: String,
    val model: String,
    val hasKey: Boolean,
)

/**
 * 知识库的读写。
 *
 * 为什么不能「把文件丢进容器就行」：
 * AstrBot 的知识库是混合检索（向量 + 关键词），文档必须经过
 * 切块 → 逐块算 embedding → 入向量库，才能在对话时被检索到。
 * 所以只能走它的接口，让它自己完成索引。
 *
 * 前置条件：必须有一个 embedding 类型的 provider，创建知识库时会真的
 * 调一次 `get_embedding("astrbot")` 做校验，配错直接失败。
 */
object KnowledgeBaseRepository {

    private const val CONFIG_RELATIVE_PATH = "root/data/cmd_config.json"
    private const val EMBEDDING_PROVIDER_ID = "astrmobile_embedding"

    // ---------------------------------------------------------------- 向量模型

    fun readEmbeddingConfig(paths: ContainerPaths): EmbeddingConfig? {
        val file = java.io.File(paths.rootfsDir, CONFIG_RELATIVE_PATH)
        if (!file.exists()) return null

        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("provider") ?: return null
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optString("provider_type") != "embedding") continue
                val key = item.optString("embedding_api_key")
                return EmbeddingConfig(
                    id = item.optString("id"),
                    apiBase = item.optString("embedding_api_base"),
                    model = item.optString("embedding_model"),
                    hasKey = key.isNotBlank(),
                )
            }
            null
        }.getOrNull()
    }

    /**
     * 写入向量模型配置。
     *
     * 字段名与 openai_embedding_source 一致：接口地址是 `embedding_api_base`、
     * 密钥是 `embedding_api_key`（字符串，不是数组）、模型名是 `embedding_model`。
     */
    fun saveEmbeddingConfig(
        paths: ContainerPaths,
        apiBase: String,
        apiKey: String,
        model: String,
    ): Result<Unit> = runCatching {
        require(apiBase.isNotBlank()) { "接口地址不能为空" }
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        require(model.isNotBlank()) { "模型名不能为空" }

        val file = java.io.File(paths.rootfsDir, CONFIG_RELATIVE_PATH)
        check(file.exists()) { "AstrBot 还没初始化，请先启动一次容器" }

        val root = JSONObject(file.readText())
        val providers = root.optJSONArray("provider") ?: JSONArray()

        var updated = false
        for (index in 0 until providers.length()) {
            val item = providers.optJSONObject(index) ?: continue
            if (item.optString("provider_type") != "embedding") continue
            item.put("embedding_api_base", apiBase.trim())
            item.put("embedding_api_key", apiKey.trim())
            item.put("embedding_model", model.trim())
            item.put("enable", true)
            updated = true
            break
        }

        if (!updated) {
            providers.put(
                JSONObject().apply {
                    put("id", EMBEDDING_PROVIDER_ID)
                    put("type", "openai_embedding")
                    put("provider", "openai")
                    put("provider_type", "embedding")
                    put("enable", true)
                    put("embedding_api_base", apiBase.trim())
                    put("embedding_api_key", apiKey.trim())
                    put("embedding_model", model.trim())
                    put("embedding_dimensions_mode", "auto")
                    put("timeout", 20)
                    put("proxy", "")
                },
            )
        }

        root.put("provider", providers)
        file.writeText(root.toString(2))
    }

    // ---------------------------------------------------------------- 知识库

    fun list(cookie: String, port: Int = 6185): Result<List<KnowledgeBase>> {
        val response = request(cookie, port, "GET", "/knowledge-bases?page=1&page_size=50", null, null)
            ?: return Result.failure(IllegalStateException("读取知识库失败：无响应"))
        if (!response.ok) {
            return Result.failure(IllegalStateException(response.message.ifBlank { "读取失败" }))
        }

        val data = response.data ?: return Result.success(emptyList())
        val array = when {
            data is JSONArray -> data
            data is JSONObject -> data.optJSONArray("items")
                ?: data.optJSONArray("knowledge_bases")
                ?: JSONArray()
            else -> JSONArray()
        }

        val items = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("kb_id").ifBlank { item.optString("id") }
                if (id.isBlank()) continue
                add(
                    KnowledgeBase(
                        id = id,
                        name = item.optString("kb_name").ifBlank {
                            item.optString("name").ifBlank { id }
                        },
                        documentCount = item.optInt("document_count")
                            .takeIf { it > 0 }
                            ?: item.optInt("doc_count"),
                        embeddingProviderId = item.optString("embedding_provider_id"),
                    ),
                )
            }
        }
        return Result.success(items)
    }

    fun create(
        cookie: String,
        name: String,
        embeddingProviderId: String,
        port: Int = 6185,
    ): Result<Unit> {
        val body = JSONObject()
            .put("name", name)
            .put("embedding_provider_id", embeddingProviderId)
            .toString()

        val response = request(cookie, port, "POST", "/knowledge-bases", body, "application/json")
            ?: return Result.failure(IllegalStateException("创建失败：无响应"))
        if (!response.ok) {
            return Result.failure(IllegalStateException(response.message.ifBlank { "创建失败" }))
        }
        return Result.success(Unit)
    }

    fun delete(cookie: String, kbId: String, port: Int = 6185): Result<Unit> {
        val response = request(cookie, port, "DELETE", "/knowledge-bases/$kbId", null, null)
            ?: return Result.failure(IllegalStateException("删除失败：无响应"))
        if (!response.ok) {
            return Result.failure(IllegalStateException(response.message.ifBlank { "删除失败" }))
        }
        return Result.success(Unit)
    }

    // ---------------------------------------------------------------- 文档

    fun listDocuments(
        cookie: String,
        kbId: String,
        port: Int = 6185,
    ): Result<List<KnowledgeDocument>> {
        val response = request(
            cookie,
            port,
            "GET",
            "/knowledge-bases/$kbId/documents?page=1&page_size=100",
            null,
            null,
        ) ?: return Result.failure(IllegalStateException("读取文档失败：无响应"))
        if (!response.ok) {
            return Result.failure(IllegalStateException(response.message.ifBlank { "读取文档失败" }))
        }

        val data = response.data ?: return Result.success(emptyList())
        val array = when {
            data is JSONArray -> data
            data is JSONObject -> data.optJSONArray("items")
                ?: data.optJSONArray("documents")
                ?: JSONArray()
            else -> JSONArray()
        }

        val items = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("document_id").ifBlank { item.optString("id") }
                if (id.isBlank()) continue
                add(
                    KnowledgeDocument(
                        id = id,
                        name = item.optString("file_name").ifBlank {
                            item.optString("name").ifBlank { id }
                        },
                        status = item.optString("status"),
                    ),
                )
            }
        }
        return Result.success(items)
    }

    /**
     * 上传并索引一个文档。
     *
     * 接口是 multipart，文件字段名用 `file`（服务端也认 `files[]`）。
     * 上传后会异步切块向量化，接口返回 task_id，这里不等它跑完——
     * 文档列表里的状态会自己从 processing 变成 completed。
     */
    fun uploadDocument(
        cookie: String,
        kbId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        port: Int = 6185,
    ): Result<Unit> = runCatching {
        val boundary = "----AstrMobile${UUID.randomUUID().toString().replace("-", "")}"

        val body = ByteArrayOutputStream()
        fun writeText(text: String) = body.write(text.toByteArray(Charsets.UTF_8))

        // 普通表单字段（chunk 参数用服务端默认值即可）
        writeText("--$boundary\r\n")
        writeText("Content-Disposition: form-data; name=\"kb_id\"\r\n\r\n")
        writeText("$kbId\r\n")

        // 文件部分：文件名按 RFC 惯例做简单转义，避免中文名把分隔符打乱
        val safeName = fileName.replace("\"", "'").replace("\r", "").replace("\n", "")
        writeText("--$boundary\r\n")
        writeText(
            "Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n",
        )
        writeText("Content-Type: $mimeType\r\n\r\n")
        body.write(bytes)
        writeText("\r\n--$boundary--\r\n")

        val response = request(
            cookie = cookie,
            port = port,
            method = "POST",
            path = "/knowledge-bases/$kbId/documents",
            body = null,
            contentType = "multipart/form-data; boundary=$boundary",
            rawBody = body.toByteArray(),
            readTimeoutMs = 180_000,
        ) ?: error("上传失败：AstrBot 无响应")

        if (!response.ok) {
            error(response.message.ifBlank { "上传失败" })
        }
    }

    fun deleteDocument(
        cookie: String,
        kbId: String,
        documentId: String,
        port: Int = 6185,
    ): Result<Unit> {
        val response = request(
            cookie,
            port,
            "DELETE",
            "/knowledge-bases/$kbId/documents/$documentId",
            null,
            null,
        ) ?: return Result.failure(IllegalStateException("删除失败：无响应"))
        if (!response.ok) {
            return Result.failure(IllegalStateException(response.message.ifBlank { "删除失败" }))
        }
        return Result.success(Unit)
    }

    // ---------------------------------------------------------------- 传输

    private class ApiResponse(val code: Int, val body: JSONObject?) {
        val ok: Boolean
            get() = code in 200..299 &&
                body?.optString("status").orEmpty().lowercase() != "error"
        val message: String
            get() = body?.optString("message").orEmpty().ifBlank {
                body?.optString("detail").orEmpty()
            }
        val data: Any? get() = body?.opt("data")
    }

    private fun request(
        cookie: String,
        port: Int,
        method: String,
        path: String,
        body: String?,
        contentType: String?,
        rawBody: ByteArray? = null,
        readTimeoutMs: Int = 20_000,
    ): ApiResponse? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("http://127.0.0.1:$port/api/v1$path").openConnection()
                as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8_000
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cookie", cookie)
                if (contentType != null) setRequestProperty("Content-Type", contentType)
                if (body != null || rawBody != null) doOutput = true
            }

            when {
                rawBody != null -> connection.outputStream.use { it.write(rawBody) }
                body != null -> connection.outputStream.use { it.write(body.toByteArray()) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText)

            ApiResponse(code, runCatching { JSONObject(text.orEmpty()) }.getOrNull())
        } catch (error: Throwable) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
