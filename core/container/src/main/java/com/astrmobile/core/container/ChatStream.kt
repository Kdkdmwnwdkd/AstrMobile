package com.astrmobile.core.container

import org.json.JSONObject

/**
 * 一次试聊的累积状态。
 *
 * [text] 是机器人已经说出来的部分，[thinking] 是思考过程（DeepSeek-R1 这类
 * 推理模型会先吐一段 reasoning）。两者分开存，因为界面上要区别对待——
 * 思考过程用灰字小号，回复正文才是主体。
 */
data class ChatStreamState(
    val text: String = "",
    val thinking: String = "",
    val done: Boolean = false,
    val error: String? = null,
)

/**
 * AstrBot 试聊接口（`POST /api/chat/send`）的 SSE 解析。
 *
 * 做成纯函数而不是塞进网络层，是因为这里的规则比看起来多，而且全都能
 * 离线验证：给一行文本，断言解析结果。塞进 HTTP 读取循环里就只能靠
 * 真机连一遍才知道对不对。
 *
 * 事件格式：
 * ```
 * data: {"type":"plain","data":"你好","streaming":true}
 * data: {"type":"plain","data":"，世界","streaming":true}
 * data: {"type":"complete","data":""}
 * : heartbeat            ← 每秒一条，防连接超时
 * ```
 *
 * 几个容易踩的点：
 *
 * 1. **streaming 决定是追加还是替换。** 服务端 `streaming=true` 时
 *    `data` 是本段增量，要累加；`false` 时 `data` 是整段全文，要覆盖。
 *    当作增量处理会导致全文重复 N 遍。
 * 2. **chain_type 不是正文。** `tool_call` / `tool_call_result` 是工具
 *    调用记录，`reasoning` 是思考过程。全塞进正文会出现满屏 JSON。
 * 3. **心跳是 `: ` 开头的注释行**，按 SSE 规范要忽略。不忽略的话
 *    每行都会尝试解析成 JSON 然后失败。
 * 4. **`complete` 和 `end` 都会出现**，语义都是"这轮结束了"，但一个
 *    回复里可能来好几次（工具调用分段）。所以 [ChatStreamState.done]
 *    只置位、不回退，界面据此停止转圈。
 */
object ChatStream {

    private const val DATA_PREFIX = "data:"

    /** 处理一行 SSE 文本，返回新状态。无法识别的行原样返回，绝不抛异常。 */
    fun consume(state: ChatStreamState, line: String): ChatStreamState {
        val trimmed = line.trim()

        // 空行分隔事件；":" 开头是心跳注释
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return state
        if (!trimmed.startsWith(DATA_PREFIX)) return state

        val payload = trimmed.removePrefix(DATA_PREFIX).trim()
        if (payload.isEmpty()) return state
        if (payload == "[DONE]") return state.copy(done = true)

        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return state

        return when (json.optString("type")) {
            "plain" -> consumePlain(state, json)
            "error" -> state.copy(
                error = json.optString("data").ifBlank { "对话失败" },
                done = true,
            )
            "end", "complete" -> state.copy(done = true)
            // session_id / user_message_saved / message_saved / agent_stats /
            // attachment_saved 都是元数据，界面上用不到
            else -> state
        }
    }

    private fun consumePlain(state: ChatStreamState, json: JSONObject): ChatStreamState {
        val delta = json.optString("data")
        val streaming = json.optBoolean("streaming", false)

        return when (json.optString("chain_type")) {
            // 工具调用是过程记录，不是要说给用户听的话
            "tool_call", "tool_call_result" -> state

            "reasoning" -> state.copy(
                thinking = if (streaming) state.thinking + delta else delta,
            )

            else -> state.copy(
                text = if (streaming) state.text + delta else delta,
            )
        }
    }
}
