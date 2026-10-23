package com.mini.agent

import org.json.JSONArray
import org.json.JSONObject

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class ChatMessage(
    val role: String,
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    /** 这一轮被用户中途停掉了，content 只有半截，不能当成完整回答 */
    val cancelled: Boolean = false,
    /**
     * 这一轮内部循环的过程记录（思考、工具调用、返回结果）。
     * 只在 assistant 消息上有，用来在界面上还原「它当时干了什么」。
     */
    val trace: List<TraceEvent> = emptyList()
) {
    /** 发给模型时的完整格式 */
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("role", role)
        o.put("content", content ?: "")

        if (role == "assistant" && toolCalls.isNotEmpty()) {
            val arr = JSONArray()
            for (c in toolCalls) {
                val fn = JSONObject()
                fn.put("name", c.name)
                fn.put("arguments", c.arguments)
                val item = JSONObject()
                item.put("id", c.id)
                item.put("type", "function")
                item.put("function", fn)
                arr.put(item)
            }
            o.put("tool_calls", arr)
        }
        if (role == "tool") {
            o.put("tool_call_id", toolCallId ?: "")
        }
        return o
    }

    /** 存本地历史时的精简格式：只留 role + content，外加这一轮的过程记录 */
    fun toHistoryJson(): JSONObject {
        val o = JSONObject()
        o.put("role", role)
        o.put("content", content ?: "")
        if (trace.isNotEmpty()) {
            o.put("trace", TraceEvent.listToJson(trace))
        }
        return o
    }

    companion object {
        fun fromHistoryJson(o: JSONObject): ChatMessage {
            val role = o.optString("role", "user")
            val content = if (o.isNull("content")) "" else o.optString("content", "")
            val trace = TraceEvent.listFromJson(o.optJSONArray("trace"))
            return ChatMessage(role, content, trace = trace)
        }
    }
}

/** 用户点了停止。partial 是已经吐出来的半截内容，persisted 表示这条回答是否已写进会话 */
class CancelledException(
    val partial: String = "",
    val persisted: Boolean = false
) : Exception("用户已停止")
