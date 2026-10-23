package com.mini.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 内部循环里发生的一件事。
 *
 * 为什么要它：以前模型「思考了什么、调用了什么、拿到了什么」全都只活在
 * AgentLoop 的局部变量里，函数一返回就没了。用户看到的只有一行会被覆盖的状态文本，
 * 想知道它到底干了什么，只能靠猜。
 *
 * 现在每一轮都会把过程记成一批 TraceEvent：
 *   THINK  模型自己在想什么（流式，会不断变长）
 *   TOOL   决定调用哪个工具、传了什么参数
 *   RESULT 工具返回了什么
 *   NOTE   系统层面的提示（轮次、上限、停止）
 *   ERROR  出了什么问题
 */
data class TraceEvent(
    val kind: String,
    val title: String,
    val detail: String = "",
    val ts: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("k", kind)
        .put("t", title)
        .put("d", detail)
        .put("ts", ts)

    companion object {
        const val THINK = "think"
        const val TOOL = "tool"
        const val RESULT = "result"
        const val NOTE = "note"
        const val ERROR = "error"

        /** 落盘时的上限，防止一个长思考把会话文件撑爆 */
        const val MAX_DETAIL = 400

        /**
         * 思考内容单独放宽。
         * 它是这堆记录里最有价值的一段——模型当时在想什么、为什么这么想，
         * 截太短就看不出推理链条了。工具返回值反而可以截。
         */
        const val MAX_DETAIL_THINK = 2000

        const val MAX_EVENTS = 40

        fun fromJson(o: JSONObject): TraceEvent = TraceEvent(
            kind = o.optString("k", NOTE),
            title = o.optString("t", ""),
            detail = o.optString("d", ""),
            ts = o.optLong("ts", 0L)
        )

        fun listToJson(events: List<TraceEvent>): JSONArray {
            val arr = JSONArray()
            events.takeLast(MAX_EVENTS).forEach { arr.put(it.toJson()) }
            return arr
        }

        fun listFromJson(arr: JSONArray?): List<TraceEvent> {
            if (arr == null) return emptyList()
            val out = ArrayList<TraceEvent>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val e = fromJson(o)
                val limit = if (e.kind == THINK) MAX_DETAIL_THINK else MAX_DETAIL
                out += e.copy(detail = e.detail.take(limit))
            }
            return out
        }
    }
}
