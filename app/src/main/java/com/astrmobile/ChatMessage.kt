package com.astrmobile

/** 试聊里的一条消息。 */
data class ChatMessage(
    val role: Role,
    val text: String,
    val thinking: String = "",
    /** 还在流式接收中，界面上要显示光标或转圈。 */
    val streaming: Boolean = false,
    val error: String? = null,
) {
    enum class Role { User, Bot }
}
