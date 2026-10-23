package com.astrmobile.core.container

import org.json.JSONArray
import org.json.JSONObject

/**
 * 机器人的行为设置。
 *
 * 只收录「会反复调」的几项。AstrBot 的配置有几百个字段，全搬上来结果是
 * 一个比 WebUI 更难用的表单——那还不如不做。
 */
data class BehaviorSettings(
    /** 唤醒前缀。空列表表示任何消息都触发。 */
    val wakePrefixes: List<String> = listOf("/"),
    /** 回复时在开头加的文字。 */
    val replyPrefix: String = "",
    /** 回复时 @ 对方。 */
    val replyWithMention: Boolean = false,
    /** 引用对方那条消息来回复。 */
    val replyWithQuote: Boolean = false,
    /** 私聊里是否也需要唤醒前缀。 */
    val friendNeedsWake: Boolean = false,
    /** 每个群/每个人独立上下文。 */
    val uniqueSession: Boolean = false,
    /** 是否启用 ID 白名单。关掉则任何人任何群都能用。 */
    val whiteListEnabled: Boolean = true,
    /** 白名单 ID。群号或 QQ 号。 */
    val whiteList: List<String> = emptyList(),
)

/**
 * 行为设置与 AstrBot 配置之间的映射。
 *
 * 放在 core 层并做成纯函数，因为这里有一个绕不开的危险：
 * `PUT /api/v1/system-config` 是**整份替换**，不是局部更新。
 * 也就是说读写过程中只要弄错一个字段，整份配置都会被写坏。
 *
 * 所以 [apply] 的约定是：只动自己认识的那几个键，其余原样保留；
 * 值没变就不写。这两条都有单测盯着。
 */
object BehaviorConfig {

    fun read(config: JSONObject): BehaviorSettings {
        val platform = config.optJSONObject("platform_settings") ?: JSONObject()

        return BehaviorSettings(
            wakePrefixes = stringList(config.opt("wake_prefix")).ifEmpty {
                // 字段缺失和"故意清空"要分开：缺失时用默认值，空数组就真的是空
                if (config.has("wake_prefix")) emptyList() else listOf("/")
            },
            replyPrefix = platform.optString("reply_prefix"),
            replyWithMention = platform.optBoolean("reply_with_mention", false),
            replyWithQuote = platform.optBoolean("reply_with_quote", false),
            friendNeedsWake = platform.optBoolean("friend_message_needs_wake_prefix", false),
            uniqueSession = platform.optBoolean("unique_session", false),
            whiteListEnabled = platform.optBoolean("enable_id_white_list", true),
            whiteList = stringList(platform.opt("id_whitelist")),
        )
    }

    /**
     * 把设置写回配置对象，返回是否真的改动了什么。
     *
     * 返回 false 时调用方应该跳过网络请求——用户只是点进来看看又退出去，
     * 不该产生一次全量写入。
     */
    fun apply(config: JSONObject, settings: BehaviorSettings): Boolean {
        var changed = false

        val currentWake = stringList(config.opt("wake_prefix"))
        if (currentWake != settings.wakePrefixes || !config.has("wake_prefix")) {
            config.put("wake_prefix", JSONArray(settings.wakePrefixes))
            changed = true
        }

        val platform = config.optJSONObject("platform_settings") ?: JSONObject().also {
            config.put("platform_settings", it)
        }

        changed = putIfChanged(platform, "reply_prefix", settings.replyPrefix.trim()) || changed
        changed = putIfChanged(platform, "reply_with_mention", settings.replyWithMention) || changed
        changed = putIfChanged(platform, "reply_with_quote", settings.replyWithQuote) || changed
        changed = putIfChanged(
            platform,
            "friend_message_needs_wake_prefix",
            settings.friendNeedsWake,
        ) || changed
        changed = putIfChanged(platform, "unique_session", settings.uniqueSession) || changed
        changed = putIfChanged(platform, "enable_id_white_list", settings.whiteListEnabled) || changed

        if (stringList(platform.opt("id_whitelist")) != settings.whiteList) {
            platform.put("id_whitelist", JSONArray(settings.whiteList))
            changed = true
        }

        return changed
    }

    private fun putIfChanged(target: JSONObject, key: String, value: Any): Boolean {
        if (target.has(key) && target.opt(key) == value) return false
        target.put(key, value)
        return true
    }

    private fun stringList(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length())
            .mapNotNull { index -> value.optString(index).takeIf { it.isNotBlank() } }
        is String -> listOf(value).filter { it.isNotBlank() }
        else -> emptyList()
    }
}
