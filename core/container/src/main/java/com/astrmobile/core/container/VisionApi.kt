package com.astrmobile.core.container

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

import java.net.HttpURLConnection
import java.net.URL

/**
 * 直接调用「OpenAI 兼容」的多模态接口，把一张截图交给模型。
 *
 * 为什么不走 AstrBot：AstrBot 的 provider 接口只负责配置，不提供"把任意
 * 图片发给模型并取回回答"的入口；而 QQ 消息链路也只在收到图片时才传图。
 * 看屏幕这种「本地截图 → 立即问一句」的场景，直连模型最短。
 *
 * 只有在用户明确点「看图」时才会调用，截图会发往用户自己配置的服务商。
 */
class VisionApi {

    suspend fun describe(
        apiBase: String,
        apiKey: String,
        model: String,
        jpeg: ByteArray,
        prompt: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val url = endpoint(apiBase)
        if (url == null) {
            return@withContext Result.failure(IllegalStateException("接口地址填得不对：$apiBase"))
        }
        if (jpeg.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("截图是空的，重新截一次"))
        }

        val imageUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", imageUrl)),
            )

        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )
            .put("max_tokens", 1200)
            .toString()

        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }.getOrElse {
            return@withContext Result.failure(IllegalStateException("连不上 $url：${it.message}"))
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()

            if (code !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException(explain(code, text, model)),
                )
            }

            parseAnswer(text) ?: Result.failure(
                IllegalStateException("模型返回了空内容（$model）。换个支持看图的模型再试。"),
            )
        } catch (error: Throwable) {
            Result.failure(IllegalStateException("请求失败：${error.message}"))
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** `https://api.deepseek.com/v1` → `https://api.deepseek.com/v1/chat/completions` */
    private fun endpoint(apiBase: String): String? {
        val base = apiBase.trim().trimEnd('/')
        if (base.isEmpty() || !base.startsWith("http")) return null
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun parseAnswer(raw: String): Result<String>? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val message = choice.optJSONObject("message") ?: return null
        val answer = message.optString("content").trim()
        if (answer.isNotBlank()) return Result.success(answer)

        // 有些推理模型会把思考放在 reasoning_content，长度不够时 content 是空的
        val reasoning = message.optString("reasoning_content")
        if (reasoning.isNotBlank()) {
            return Result.failure(
                IllegalStateException(
                    "模型只输出了思考过程就被长度上限截断了。把问题问得短一些再试。",
                ),
            )
        }
        return null
    }

    private fun explain(code: Int, raw: String, model: String): String {
        val detail = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty().ifBlank { raw.take(200) }

        return when (code) {
            401, 403 -> "密钥无效或没有权限（$model）。去「模型」页重新保存一次 Key。"
            402 -> "账户余额不足。"
            404 -> "接口地址或模型名不对（$model）。确认地址结尾是 /v1。"
            429 -> "触发了限流，等一分钟再试。"
            400 -> if (
                detail.contains("image", ignoreCase = true) ||
                detail.contains("multimodal", ignoreCase = true) ||
                detail.contains("vision", ignoreCase = true)
            ) {
                "「$model」不支持图片输入。换 deepseek-flash 这类多模态模型，" +
                    "或者点下面的「只提取文字」，那样不上传图片。"
            } else {
                "请求被拒绝：$detail"
            }
            in 500..599 -> "服务商那边出错了（$code），稍后再试。"
            else -> "请求失败（$code）：$detail"
        }
    }
}
