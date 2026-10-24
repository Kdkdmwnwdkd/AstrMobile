package com.astrmobile.core.container

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 探测一个 OpenAI 兼容端点是否可用。
 *
 * 填完 API Key 之后，现在唯一的验证方式是"去群里说句话看看回不回"。
 * 配错了要等到那时候才发现，而且分不清是 Key 错了、地址错了、还是模型名错了。
 *
 * 这里只发一个 `GET {base}/models`，是最轻的鉴权验证：
 * 不消耗 token，返回 200 就说明地址通、Key 有效。
 */
object ModelProbe {

    data class Result(
        val ok: Boolean,
        /** 端到端耗时，毫秒。 */
        val latencyMs: Long,
        val detail: String,
    )

    suspend fun probe(
        baseUrl: String,
        apiKey: String,
        timeoutMs: Int = 10_000,
    ): Result = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        val key = apiKey.trim()

        if (base.isEmpty()) {
            return@withContext Result(false, 0, "接口地址是空的")
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return@withContext Result(false, 0, "接口地址要以 http:// 或 https:// 开头")
        }

        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null

        try {
            connection = (URL("$base/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Accept", "application/json")
            }

            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            val elapsed = System.currentTimeMillis() - started

            when {
                code in 200..299 -> {
                    val count = runCatching {
                        JSONObject(body).optJSONArray("data")?.length() ?: 0
                    }.getOrDefault(0)
                    Result(
                        ok = true,
                        latencyMs = elapsed,
                        detail = if (count > 0) "可用 · $count 个模型" else "可用",
                    )
                }

                code == 401 || code == 403 -> Result(
                    false,
                    elapsed,
                    "Key 不被接受（HTTP $code），检查是否复制完整",
                )

                // 很多中转不实现 /models。这不代表不能用，不能报成"配置错误"。
                code == 404 || code == 405 -> Result(
                    false,
                    elapsed,
                    "这个端点没有 /models 接口，测不了。保存后可以去「试聊」验证",
                )

                else -> Result(
                    false,
                    elapsed,
                    "HTTP $code · ${body.take(120).ifBlank { "没有返回内容" }}",
                )
            }
        } catch (error: Throwable) {
            Result(
                ok = false,
                latencyMs = System.currentTimeMillis() - started,
                detail = "连不上：${error.message ?: error.javaClass.simpleName}",
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
