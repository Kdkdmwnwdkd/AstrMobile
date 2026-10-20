package com.astrmobile.core.container

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class QqLoginStatus(
    val isLogin: Boolean,
    val isOffline: Boolean,
    val loginPhase: String,
    val qrcode: String?,
    val error: String?,
)

/**
 * NapCat 本地 WebUI 的最小客户端，只做扫码登录这一条链路。
 *
 * 接口来自 napcat-webui-backend：
 * - POST /api/auth/login     body {hash: <webui token>} → data.Credential
 * - POST /api/QQLogin/GetQQLoginQrcode → data.qrcode
 * - POST /api/QQLogin/CheckLoginStatus → data.{isLogin, loginPhase, loginError}
 *
 * 全部走 127.0.0.1，不经过外网。
 */
class NapCatApi(private val port: Int = 6099) {

    private val baseUrl = "http://127.0.0.1:$port"

    /** 登录 WebUI，返回后续请求要带的 Credential。 */
    suspend fun login(token: String): Result<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("hash", token).toString()
        val response = post("/api/auth/login", body, credential = null)
            ?: return@withContext Result.failure(IllegalStateException("NapCat 未响应，可能还没启动完成"))

        val credential = response.optJSONObject("data")?.optString("Credential").orEmpty()
        if (credential.isBlank()) {
            val message = response.optString("message").ifBlank { "Token 校验失败" }
            return@withContext Result.failure(IllegalStateException(message))
        }
        Result.success(credential)
    }

    suspend fun fetchQrcode(credential: String): Result<String> = withContext(Dispatchers.IO) {
        val response = post("/api/QQLogin/GetQQLoginQrcode", "{}", credential)
            ?: return@withContext Result.failure(IllegalStateException("获取二维码失败：无响应"))

        val qrcode = response.optJSONObject("data")?.optString("qrcode").orEmpty()
        if (qrcode.isBlank()) {
            val message = response.optString("message").ifBlank { "二维码为空，可能已登录" }
            return@withContext Result.failure(IllegalStateException(message))
        }
        Result.success(qrcode)
    }

    suspend fun checkStatus(credential: String): QqLoginStatus? = withContext(Dispatchers.IO) {
        val response = post("/api/QQLogin/CheckLoginStatus", "{}", credential) ?: return@withContext null
        val data = response.optJSONObject("data") ?: return@withContext null
        QqLoginStatus(
            isLogin = data.optBoolean("isLogin"),
            isOffline = data.optBoolean("isOffline"),
            loginPhase = data.optString("loginPhase"),
            qrcode = data.optString("qrcodeurl").takeIf { it.isNotBlank() },
            error = data.optString("loginError").takeIf { it.isNotBlank() },
        )
    }

    private fun post(path: String, body: String, credential: String?): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                // NapCat 同时接受裸 Credential 与 Bearer 前缀，这里优先用 Bearer
                if (!credential.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $credential")
                }
            }
            connection.outputStream.use { it.write(body.toByteArray()) }

            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: return null
            JSONObject(text)
        } catch (error: Throwable) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}

/** 把 NapCat 返回的二维码转成 Bitmap：支持 data URL、纯 base64、http 地址三种形式。 */
object QrCodeDecoder {

    suspend fun decode(raw: String): Bitmap? = withContext(Dispatchers.IO) {
        when {
            raw.startsWith("http://") || raw.startsWith("https://") ->
                runCatching {
                    URL(raw).openStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()

            raw.startsWith("data:image") -> decodeBase64(raw.substringAfter(',', ""))

            else -> decodeBase64(raw)
        }
    }

    private fun decodeBase64(text: String): Bitmap? = runCatching {
        val bytes = Base64.decode(text, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
