package com.mini.agent

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 声明式 HTTP 工具：模型写一段 JSON，就多一个工具。
 * 不需要解释器、不需要重新编译。
 *
 * 定义格式：
 * {
 *   "name": "get_rate",
 *   "description": "查汇率",
 *   "method": "GET",
 *   "url": "https://api.example.com/rate?from={from}&to={to}",
 *   "params": {"from": "源货币代码", "to": "目标货币代码"},
 *   "headers": {"Authorization": "Bearer sk-xxx"},   // 鉴权，可选，支持 {param}
 *   "body": "{\"q\":\"{from}\"}"        // 仅 POST 需要，可选
 * }
 */
object CustomTools {

    private const val NAME = "custom_tools.json"
    private const val MAX = 12

    val BUILTIN = setOf(
        "web_search", "web_fetch", "weather", "email", "email_send",
        "remember", "notify", "save", "share", "run_js", "web_app", "self"
    )

    fun file(ctx: Context) = File(ctx.filesDir, "agent/$NAME")

    fun raw(ctx: Context): JSONArray {
        return try {
            val f = file(ctx)
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun write(ctx: Context, arr: JSONArray) {
        val f = file(ctx)
        f.parentFile?.mkdirs()
        f.writeText(arr.toString(2))
    }

    /** 加载成可执行工具 */
    fun load(ctx: Context): List<Tool> {
        val arr = raw(ctx)
        val out = mutableListOf<Tool>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name").isBlank() || o.optString("url").isBlank()) continue
            out.add(HttpTemplateTool(o))
        }
        return out
    }

    fun list(ctx: Context): String {
        val arr = raw(ctx)
        if (arr.length() == 0) return "还没有自定义工具"

        val sb = StringBuilder("自定义工具 ${arr.length()} 个（上限 $MAX）：\n")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            sb.append(i + 1).append(". ").append(o.optString("name"))
            sb.append(" — ").append(o.optString("description").take(60))
            sb.append("\n   ").append(o.optString("method", "GET")).append(' ')
            sb.append(o.optString("url").take(90)).append("\n")
        }
        return sb.toString().trim()
    }

    fun add(ctx: Context, json: String): String {
        val def = try {
            JSONObject(json)
        } catch (e: Exception) {
            return "错误：定义不是合法 JSON。$json"
        }

        val name = def.optString("name").trim()
        val url = def.optString("url").trim()

        if (name.isEmpty()) return "错误：缺少 name"
        if (!Regex("^[a-z][a-z0-9_]{1,30}$").matches(name)) {
            return "错误：name 只能用 2~31 位小写字母、数字、下划线，且以字母开头"
        }
        if (BUILTIN.contains(name)) return "错误：$name 是内置工具名，换一个"
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "错误：url 必须以 http:// 或 https:// 开头"
        }
        if (def.optString("description").isBlank()) return "错误：缺少 description，说明什么时候用它"

        val arr = raw(ctx)

        // 同名则覆盖
        var replaced = false
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optString("name") == name) {
                arr.put(i, def)
                replaced = true
                break
            }
        }
        if (!replaced) {
            if (arr.length() >= MAX) {
                return "错误：自定义工具已满 $MAX 个。先用 remove_tool 删掉不用的。"
            }
            arr.put(def)
        }

        write(ctx, arr)
        return if (replaced) "已更新工具 $name，下一轮对话生效" else "已添加工具 $name，下一轮对话生效"
    }

    fun remove(ctx: Context, name: String): String {
        if (name.isBlank()) return "错误：需要 name"
        val arr = raw(ctx)
        val kept = JSONArray()
        var hit = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") == name) hit = true else kept.put(o)
        }
        if (!hit) return "没有叫 $name 的自定义工具"
        write(ctx, kept)
        return "已删除工具 $name"
    }

    fun clear(ctx: Context): String {
        try {
            file(ctx).delete()
            return "已清空全部自定义工具"
        } catch (e: Exception) {
            return "清空失败：${e.message}"
        }
    }
}

/** 一条定义变成的活工具 */
class HttpTemplateTool(private val def: JSONObject) : Tool {

    override val name: String = def.optString("name")
    override val description: String = def.optString("description")

    private val method = def.optString("method", "GET").uppercase()
    private val urlTpl = def.optString("url")
    private val bodyTpl = def.optString("body")
    private val params = def.optJSONObject("params") ?: JSONObject()

    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                val props = JSONObject()
                for (k in params.keys()) {
                    props.put(k, JSONObject().apply {
                        put("type", "string")
                        put("description", params.optString(k))
                    })
                }
                put("properties", props)
                if (params.length() > 0) {
                    put("required", JSONArray().apply { params.keys().forEach { put(it) } })
                }
            })
        })
    }

    override fun execute(args: JSONObject): String {
        return try {
            var url = urlTpl
            for (k in params.keys()) {
                url = url.replace("{$k}", enc(args.optString(k)))
            }

            val b = Request.Builder()
                .url(url)
                .header("User-Agent", UA)

            // 自定义请求头，支持 {param} 占位，用来塞 API Key
            def.optJSONObject("headers")?.let { hs ->
                for (k in hs.keys()) {
                    var v = hs.optString(k)
                    for (pk in params.keys()) v = v.replace("{$pk}", args.optString(pk))
                    if (v.isNotBlank()) b.header(k, v)
                }
            }

            if (method == "POST" || method == "PUT") {
                var body = bodyTpl.ifBlank { "{}" }
                for (k in params.keys()) body = body.replace("{$k}", args.optString(k))
                b.method(method, body.toRequestBody(mediaJson))
            } else {
                b.get()
            }

            client.newCall(b.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "HTTP ${resp.code}：${text.take(300)}"

                val out = if (text.trimStart().startsWith("<")) {
                    Jsoup.parse(text).body()?.text().orEmpty()
                } else {
                    text
                }.replace(Regex("\\s+"), " ").trim()

                when {
                    out.isEmpty() -> "（返回空内容）"
                    out.length > 4000 -> out.take(4000) + "\n（已截断）"
                    else -> out
                }
            }
        } catch (e: Exception) {
            "调用失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
