package com.astrmobile.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 用于本地直连模型的一份凭据（看屏幕这类不经 AstrBot 的功能要用）。 */
data class VisionConfig(
    val apiBase: String,
    val model: String,
    val apiKey: String,
)

/**
 * 保存「当前使用的模型」的地址与密钥，供 App 自己直连模型时使用。
 *
 * 为什么要单独存一份：AstrBot 的 provider 接口返回的 Key 是脱敏的（只剩
 * 前几位和后几位），拿不到明文就无法由 App 直接调用。所以在保存模型的
 * 时候顺手留一份，只存在本机。
 *
 * 明文不能落盘，用 Android Keystore 里的 AES 密钥加密后再写入
 * SharedPreferences。密钥存放在 TEE/StrongBox 中，无法导出；换机、清数据、
 * 卸载重装后旧密文解不开，会静默失效并退回本地 OCR。
 */
class VisionStore(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun save(config: VisionConfig) {
        val payload = JSONObject()
            .put("api_base", config.apiBase)
            .put("model", config.model)
            .put("api_key", config.apiKey)
            .toString()
        val sealed = runCatching { encrypt(payload) }.getOrNull() ?: return
        prefs.edit().putString(KEY_BLOB, sealed).apply()
    }

    fun load(): VisionConfig? {
        val sealed = prefs.getString(KEY_BLOB, null) ?: return null
        val plain = runCatching { decrypt(sealed) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(plain) }.getOrNull() ?: return null
        val apiBase = json.optString("api_base")
        val model = json.optString("model")
        val apiKey = json.optString("api_key")
        if (apiBase.isBlank() || model.isBlank() || apiKey.isBlank()) return null
        return VisionConfig(apiBase = apiBase, model = model, apiKey = apiKey)
    }

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    // ---------------------------------------------------------------- 加解密

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    /** 输出 base64(iv || 密文)，IV 每次随机，不用额外存。 */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(sealed: String): String {
        val combined = Base64.decode(sealed, Base64.NO_WRAP)
        if (combined.size <= IV_LENGTH) error("密文不完整")
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val body = combined.copyOfRange(IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private companion object {
        const val PREF_NAME = "vision_secret"
        const val KEY_BLOB = "blob"
        const val KEY_ALIAS = "astrmobile_vision_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
