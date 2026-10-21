package com.astrmobile.core.container

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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

    companion object {
        /**
         * start.sh 会以 `NAPCAT_WEBUI_SECRET_KEY` 把 WebUI 口令固定成这个值。
         * NapCat 默认会在首次启动时把口令随机化、且只在 QQ 登录成功后才告知，
         * App 若不固定就永远登录不进它的本地接口。
         */
        const val DEFAULT_TOKEN = "astrmobile"
    }

    private val baseUrl = "http://127.0.0.1:$port"

    /**
     * 登录 WebUI，返回后续请求要带的 Credential。
     *
     * 注意：接口收的不是 token 原文，而是 `sha256(token + ".napcat")` 的十六进制串
     * （见 napcat-webui-backend 的 AuthHelper.generatePasswordHash）。
     * 直接把原文当 hash 传会一律返回 "token is invalid"。
     */
    suspend fun login(token: String): Result<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("hash", tokenHash(token)).toString()
        val response = post("/api/auth/login", body, credential = null)
            ?: return@withContext Result.failure(IllegalStateException("NapCat 未响应，可能还没启动完成"))

        val credential = response.optJSONObject("data")?.optString("Credential").orEmpty()
        if (credential.isBlank()) {
            val message = response.optString("message").ifBlank { "Token 校验失败" }
            return@withContext Result.failure(IllegalStateException(message))
        }
        Result.success(credential)
    }

    /** NapCat 的口令散列：sha256(token + ".napcat")。 */
    fun tokenHash(token: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((token + ".napcat").toByteArray(Charsets.UTF_8))
        // 必须显式按无符号展开：byte 是有符号的，负数直接格式化成 8 位 f。
        digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }.getOrDefault("")

    /**
     * 取二维码。
     *
     * 返回值**不是图片**，而是二维码承载的内容：新版 NapCat 的 `GetQQLoginQrcode`
     * 直接返回 `txz.qq.com` 的登录链接（它自己的网页版是用前端 JS 二维码库现画的），
     * 只有老版本才返回 PNG 字节的 hex / base64。两种形态都交给 QrCodeDecoder 处理。
     */
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

    /**
     * 让 NapCat 换一张新码。
     *
     * `GetQQLoginQrcode` 只是读当前码，同一张码会一直返回同一串内容；
     * 过期后必须调 RefreshQRcode 才会真正重新拉取，否则用户扫的是废码。
     * NapCat 正在重启 QQ 时会返回空串 + restarting=true，这里当失败让上层重试。
     */
    suspend fun refreshQrcode(credential: String): Result<String> = withContext(Dispatchers.IO) {
        val response = post("/api/QQLogin/RefreshQRcode", "{}", credential)
            ?: return@withContext Result.failure(IllegalStateException("刷新二维码失败：无响应"))

        val data = response.optJSONObject("data")
        val url = data?.optString("qrcodeurl").orEmpty()
        if (url.isBlank()) {
            val message = response.optString("message").ifBlank { "NapCat 正在重启 QQ，稍后再取" }
            return@withContext Result.failure(IllegalStateException(message))
        }
        Result.success(url)
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
            // 有些失败（端口被别的服务占用）会返回 HTML，直接 JSONObject() 会抛异常，
            // 上层只会看到「无响应」，排错时完全没用。
            runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("message", text.trim().take(120).ifBlank { "NapCat 返回空响应" })
            }
        } catch (error: Throwable) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}

/**
 * 把 NapCat 给的东西变成一张能扫的二维码图。
 *
 * 这里要同时兼容三代实现，顺序不能反：
 * 1. 新版（4.x）：`qrcode` 是**内容**（`https://txz.qq.com/p?k=...`），必须本地渲染；
 * 2. 老版：`qrcode` 是 PNG 字节的 **hex** 串；
 * 3. 更老的 / 第三方魔改：base64、data URL、图片直链。
 *
 * 判断顺序是「先当图片解，解不出来再当内容画」——反过来的话，一张 hex 图片
 * 会被当成几千字的文本去编码，直接抛异常。
 */
object QrCodeDecoder {

    /** 画出来的边长。显示区 260dp，720px 在 3x 屏上仍然清晰可扫。 */
    private const val RENDER_SIZE = 720

    suspend fun decode(raw: String): Bitmap? = withContext(Dispatchers.IO) {
        val text = raw.trim()
        if (text.isEmpty()) return@withContext null
        imageBitmap(text)?.let { return@withContext it }
        // 明显是图片字节却解不出来（截断的 hex、损坏的 base64），说明这串东西本身有问题。
        // 这时绝不能退回去当内容画码，否则会画出一张「扫出来是一串乱码」的假二维码。
        if (looksLikeImagePayload(text)) return@withContext null
        renderQr(text)
    }

    /** 这串东西是不是「本该是图片」。 */
    private fun looksLikeImagePayload(text: String): Boolean {
        if (text.startsWith("data:image", ignoreCase = true)) return true
        if (hexBytes(text) != null) return true
        if (text.length > 512 && !text.contains(':')) {
            val bytes = base64(text)
            if (bytes != null && hasImageMagic(bytes)) return true
        }
        return false
    }

    /** 二维码本身就是一张图的情况。 */
    private fun imageBitmap(text: String): Bitmap? {
        if (text.startsWith("data:image", ignoreCase = true)) {
            return decodeBytes(base64(text.substringAfter(',', "")))
        }
        // PNG 的 hex 串以 89504e47 开头，一眼能认出来
        hexBytes(text)?.let { return decodeBytes(it) }
        // base64：含 + / = 且没有 : 和 /（URL），长度也远大于登录链接
        if (text.length > 512 && !text.contains(':')) {
            decodeBytes(base64(text))?.let { return it }
        }
        if ((text.startsWith("http://", true) || text.startsWith("https://", true)) && looksLikeImageUrl(text)) {
            return downloadBitmap(text)
        }
        return null
    }

    private fun looksLikeImageUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".bmp")
    }

    private fun downloadBitmap(url: String): Bitmap? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 6_000
            instanceFollowRedirects = true
        }
        try {
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            runCatching { connection.disconnect() }
        }
    }.getOrNull()

    private fun base64(text: String): ByteArray? = runCatching {
        Base64.decode(text, Base64.DEFAULT)
    }.getOrNull() ?: runCatching {
        // 有些实现给的是 URL-safe 变体，'-' '_' 会解不出来
        Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP)
    }.getOrNull()

    private fun decodeBytes(bytes: ByteArray?): Bitmap? {
        if (bytes == null || bytes.size < 8) return null
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }

    private fun hexBytes(text: String): ByteArray? {
        if (text.length < 16 || text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        // 全 hex 的短文本（比如纯数字口令）不是图片，必须验魔数
        return out.takeIf { hasImageMagic(it) }
    }

    private fun hasImageMagic(bytes: ByteArray): Boolean {
        fun at(index: Int): Int = bytes[index].toInt() and 0xFF
        if (bytes.size < 4) return false
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> true // PNG
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> true // JPEG
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> true // GIF
            at(0) == 0x42 && at(1) == 0x4D -> true // BMP
            bytes.size >= 12 && at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 -> true // RIFF/WebP
            else -> false
        }
    }

    /** 把二维码内容真正画成二维码。这就是 NapCat 网页版前端干的事。 */
    private fun renderQr(text: String): Bitmap? = runCatching {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            // 留 1 格白边：手机识别靠四周静区，全贴边会扫不出
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, RENDER_SIZE, RENDER_SIZE, hints)
        val pixels = IntArray(RENDER_SIZE * RENDER_SIZE)
        for (y in 0 until RENDER_SIZE) {
            val row = y * RENDER_SIZE
            for (x in 0 until RENDER_SIZE) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, RENDER_SIZE, RENDER_SIZE, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
