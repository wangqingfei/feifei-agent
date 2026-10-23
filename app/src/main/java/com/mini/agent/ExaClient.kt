package com.mini.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Exa 托管的 MCP 服务，零配置、不需要 Key。
 *
 * MCP 不是普通 REST：要先 initialize 拿 session，再 tools/call。
 * 这里把 session 缓存住，第二次搜索就只剩一次往返。
 * 任何一步失败都返回 null，让调用方退回 degoog / 必应。
 */
object ExaClient {

    private const val ENDPOINT = "https://mcp.exa.ai/mcp"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private var sessionId: String? = null
    private var seq = 1

    /** 冷却时间戳：Exa 免费额度很低，触发限流后一段时间内直接跳过，别每次都白等一次往返 */
    private var cooldownUntil = 0L

    private data class Http(val code: Int, val sid: String?, val body: String)

    @Synchronized
    fun search(query: String, limit: Int): String? {
        if (System.currentTimeMillis() < cooldownUntil) return null

        repeat(2) {
            val sid = sessionId ?: initSession() ?: run {
                cool(3 * 60_000)
                return null
            }
            when (val r = callSearch(sid, query, limit)) {
                is Ok -> {
                    cool(90_000)
                    return r.text
                }
                Limited -> {
                    cool(10 * 60_000)
                    return null
                }
                Failed -> {
                    sessionId = null   // session 可能过期，丢掉重建一次
                }
            }
        }
        cool(3 * 60_000)
        return null
    }

    private fun cool(ms: Long) {
        cooldownUntil = System.currentTimeMillis() + ms
    }

    private sealed class Outcome
    private data class Ok(val text: String) : Outcome()
    private object Limited : Outcome()
    private object Failed : Outcome()

    private fun initSession(): String? {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", seq++)
            .put("method", "initialize")
            .put("params", JSONObject()
                .put("protocolVersion", "2025-03-26")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject()
                    .put("name", "mini-agent")
                    .put("version", "0.5")))

        val res = post(payload, null) ?: return null
        if (res.code !in 200..299) return null
        val sid = res.sid ?: return null

        post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), sid)
        sessionId = sid
        return sid
    }

    private fun callSearch(sid: String, query: String, limit: Int): Outcome {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", seq++)
            .put("method", "tools/call")
            .put("params", JSONObject()
                .put("name", "web_search_exa")
                .put("arguments", JSONObject()
                    .put("query", query)
                    .put("objective", "查找关于「$query」的最新、可靠信息")
                    .put("numResults", limit.coerceIn(1, 10))))

        val res = post(payload, sid) ?: return Failed
        if (res.code !in 200..299) return Failed

        val json = firstSseData(res.body) ?: return Failed
        val result = json.optJSONObject("result") ?: return Failed
        if (result.optBoolean("isError", false)) return Failed

        val content = result.optJSONArray("content") ?: return Failed
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val t = content.getJSONObject(i).optString("text", "")
            if (t.isNotEmpty()) sb.append(t)
        }
        if (sb.isEmpty()) return Failed

        val text = sb.toString()
        // 免费额度用尽时 Exa 返回 200 + 一段提示文本，必须认出来，否则会被当成搜索结果
        if (text.contains("rate limit", ignoreCase = true) ||
            text.contains("hit Exa", ignoreCase = true) ||
            text.contains("exaApiKey", ignoreCase = true)
        ) {
            return Limited
        }

        return Ok(compress(text, 3200))
    }

    private fun post(payload: JSONObject, sid: String?): Http? {
        return try {
            val b = Request.Builder()
                .url(ENDPOINT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("User-Agent", UA)
                .apply { if (sid != null) header("mcp-session-id", sid) }
                .post(payload.toString().toRequestBody(mediaJson))
                .build()

            client.newCall(b).execute().use { r ->
                Http(r.code, r.header("mcp-session-id"), r.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            null
        }
    }

    /** MCP 用 SSE 回包，真正的 JSON 在 data: 行里 */
    private fun firstSseData(body: String): JSONObject? {
        for (line in body.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val raw = line.substring(5).trim()
            if (raw.isEmpty()) continue
            try {
                return JSONObject(raw)
            } catch (e: Exception) {
                // 继续找下一行
            }
        }
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    /** Exa 会返回很长的正文摘要，按条截断，别把上下文吃光 */
    private fun compress(text: String, max: Int): String {
        if (text.length <= max) return text
        val sb = StringBuilder()
        for (part in text.split(Regex("(?m)(?=^Title: )"))) {
            if (part.isBlank()) continue
            if (sb.length + part.length > max) break
            sb.append(part)
        }
        return if (sb.isEmpty()) {
            text.take(max) + "\n（已截断）"
        } else {
            sb.toString().trimEnd() + "\n（已截断，还有更多结果）"
        }
    }
}
