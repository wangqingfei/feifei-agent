package com.mini.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 只做一件事：把 messages 发过去，把 assistant 的回复解析回来。
 * 换模型只改 baseUrl + model，代码不动。
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val temperature: Double = 0.5,
    private val maxTokens: Int = 4096
) {
    private val mediaJson = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun chat(messages: List<ChatMessage>, tools: JSONArray?): ChatMessage {
        val payload = buildPayload(messages, tools)

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(mediaJson))
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}：${body.take(400)}")
            }
            return parse(body)
        }
    }

    /**
     * 流式：文字边收边回调，工具调用按 index 拼完整再返回。
     * 注意 delta 里的 tool_calls 是碎片，不能拿到一段就执行。
     */
    fun chatStream(
        messages: List<ChatMessage>,
        tools: JSONArray?,
        onDelta: (String) -> Unit,
        onThinking: () -> Unit = {},
        onToolProgress: (Int) -> Unit = {},
        /** 用户点停止时返回 true。读到一半就收手，别等模型把话说完 */
        isCancelled: () -> Boolean = { false },
        /** 推理内容增量。模型「想」的过程，用户以前完全看不到 */
        onReasoning: (String) -> Unit = {}
    ): ChatMessage {
        val payload = buildPayload(messages, tools)
        payload.put("stream", true)

        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(mediaJson))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw IOException("HTTP ${resp.code}：${err.take(400)}")
            }
            val source = resp.body?.source() ?: throw IOException("响应为空")

            val content = StringBuilder()
            val reasoning = StringBuilder()
            val builders = HashMap<Int, CallBuilder>()
            var thinkingNotified = false
            var truncated = false
            var argAt = 0L
            var argLen = 0

            while (true) {
                // 停止按钮：每读一行查一次。半截正文照样带回去，用户已经看到的部分不能丢
                if (isCancelled()) {
                    return ChatMessage("assistant", content.toString().ifEmpty { reasoning.toString() }
                        .ifEmpty { null }, emptyList(), cancelled = true)
                }
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break

                val obj = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    continue
                }
                val choices = obj.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue
                val ch0 = choices.getJSONObject(0)
                if (ch0.optString("finish_reason", "").equals("length", true)) truncated = true
                val delta = ch0.optJSONObject("delta") ?: continue

                // 有些模型先吐推理过程，正文还没开始。此时得给界面一个「它在动」的信号。
                val rc = delta.optString("reasoning_content", "")
                if (rc.isNotEmpty()) {
                    reasoning.append(rc)
                    // 推理内容也往外送：用户要看「它在想什么」，不只是「它想完了」
                    onReasoning(rc)
                    if (!thinkingNotified) {
                        thinkingNotified = true
                        onThinking()
                    }
                }

                if (!delta.isNull("content")) {
                    val piece = delta.optString("content", "")
                    if (piece.isNotEmpty()) {
                        content.append(piece)
                        onDelta(piece)
                    }
                }

                val tcs = delta.optJSONArray("tool_calls")
                if (tcs != null) {
                    for (i in 0 until tcs.length()) {
                        val item = tcs.getJSONObject(i)
                        val idx = item.optInt("index", i)
                        val b = builders.getOrPut(idx) { CallBuilder() }
                        val id = item.optString("id", "")
                        if (id.isNotEmpty()) b.id = id
                        val fn = item.optJSONObject("function") ?: continue
                        val fnName = fn.optString("name", "")
                        if (fnName.isNotEmpty()) b.name = fnName
                        b.args.append(fn.optString("arguments", ""))

                        // 生成大段参数（比如整页 HTML）时界面是空的，这里报个字数让用户知道它在动
                        val total = builders.values.sumOf { it.args.length }
                        val now = System.currentTimeMillis()
                        if (total > argLen && now - argAt > 250) {
                            argAt = now
                            argLen = total
                            onToolProgress(total)
                        }
                    }
                }
            }

            // 被长度上限截断时明说，否则用户只会觉得"它怎么话没说完"
            if (truncated && builders.isEmpty()) {
                val tail = "\n\n（回答到长度上限被截断了，说一声我接着写）"
                content.append(tail)
                onDelta(tail)
            }

            val calls = builders.entries.sortedBy { it.key }.map { (i, b) ->
                ToolCall(b.id.ifEmpty { "call_$i" }, b.name, b.args.toString().ifEmpty { "{}" })
            }

            // 正文为空时用推理内容兜底：总比显示「没有回答」强
            val finalText = content.toString().ifEmpty { reasoning.toString() }
            return ChatMessage("assistant", finalText.ifEmpty { null }, calls)
        }
    }

    private class CallBuilder {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    private fun buildPayload(messages: List<ChatMessage>, tools: JSONArray?): JSONObject {
        val payload = JSONObject()
        payload.put("model", model)
        payload.put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
        if (tools != null && tools.length() > 0) {
            payload.put("tools", tools)
            payload.put("tool_choice", "auto")
        }
        payload.put("temperature", temperature)
        // 不设的话服务端常按 1024 截断，长回答会莫名其妙断在半句上
        if (maxTokens > 0) payload.put("max_tokens", maxTokens)
        return payload
    }

    private fun parse(body: String): ChatMessage {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices")
            ?: throw IOException("响应里没有 choices：${body.take(300)}")
        if (choices.length() == 0) throw IOException("choices 是空的")

        val msg = choices.getJSONObject(0).optJSONObject("message")
            ?: throw IOException("响应里没有 message")

        val raw = if (msg.isNull("content")) "" else msg.optString("content", "")
        val reasoning = msg.optString("reasoning_content", "")
        val content = raw.ifEmpty { reasoning }

        val calls = mutableListOf<ToolCall>()
        val arr = msg.optJSONArray("tool_calls")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val fn = item.optJSONObject("function") ?: continue
                calls += ToolCall(
                    id = item.optString("id", "call_$i"),
                    name = fn.optString("name", ""),
                    arguments = fn.optString("arguments", "{}")
                )
            }
        }
        return ChatMessage("assistant", content.ifEmpty { null }, calls)
    }

    /** 设置页的「测试连接」用 */
    fun ping(): String {
        val reply = chat(listOf(ChatMessage("user", "只回复两个字：可用")), null)
        return reply.content?.trim().orEmpty().ifEmpty { "(模型返回了空内容)" }
    }
}
