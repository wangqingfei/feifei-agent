package com.mini.agent

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

interface Tool {
    val name: String
    val description: String
    fun schema(): JSONObject
    fun execute(args: JSONObject): String

    /**
     * 返回内容含隐私（电话、短信正文）时置 true。
     * 日志里只记长度不记内容——log.jsonl 是明文文件，模型自己也能读它。
     */
    val sensitive: Boolean get() = false
}

class ToolRegistry(
    private val ctx: Context?,
    private val tools: List<Tool>
) {

    fun schemas(): JSONArray {
        val arr = JSONArray()
        tools.forEach { arr.put(it.schema()) }
        return arr
    }

    /**
     * 关键约定：永不抛异常。
     * 任何失败都变成一段文字回给模型，它才有机会自己改参数重试。
     * 抛出去 = App 崩 = 用户看到一屏红色。
     */
    fun run(name: String, argsJson: String): String {
        val tool = tools.firstOrNull { it.name == name }
        if (tool == null) {
            val msg = "错误：不存在名为 $name 的工具。可用工具：${tools.joinToString { it.name }}"
            log(name, argsJson, msg, 0L, false)
            return msg
        }

        val args = try {
            if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
        } catch (e: Exception) {
            val msg = "错误：参数不是合法 JSON（$argsJson）。请重新生成，只输出 JSON。"
            log(name, argsJson, msg, 0L, false)
            return msg
        }

        val t0 = System.currentTimeMillis()
        val result = try {
            tool.execute(args)
        } catch (e: Exception) {
            // 工具抛异常时不能让它把 App 带崩，但也不能悄悄吞掉——
            // 吞掉就等于没发生过，出问题的时候连线索都没有
            ctx?.let { CrashLog.report(it, "工具 $name 执行异常", e) }
            "工具执行失败：${e.javaClass.simpleName}: ${e.message}"
        }
        log(name, argsJson, result, System.currentTimeMillis() - t0, !looksFailed(result))
        return result
    }

    private fun log(name: String, arg: String, result: String, ms: Long, ok: Boolean) {
        ctx ?: return
        val tool = tools.firstOrNull { it.name == name }
        val body = if (tool?.sensitive == true) "（含隐私内容 ${result.length} 字，未记录）" else result
        RunLog.append(ctx, name, arg.take(120), body, ms, ok)
    }

    /** 工具都返回自然语言，只能靠措辞判断成败 */
    private fun looksFailed(s: String): Boolean =
        s.startsWith("错误") || s.contains("失败") || s.contains("连不上") ||
            s.contains("没拿到结果") || s.contains("未开启") || s.contains("还没")
}

/** 抓网页 -> 纯文本 */
class WebFetchTool : Tool {
    override val name = "web_fetch"
    override val description = """读一个已知网址的正文，返回纯文本（去标签，超 6000 字截断）。
用：有链接要读内容；读 RSS（自动抽条目）。
别用：不知道网址用 web_search；要登录的页面（华医网）抓回来是登录页；PDF/图片读不了。"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "完整地址，http(s):// 开头，例 https://sspai.com/post/12345")
                    })
                })
                put("required", JSONArray().put("url"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val url = args.optString("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "错误：url 必须以 http:// 或 https:// 开头"
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) MiniAgent/0.1")
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return "抓取失败：HTTP ${resp.code}"

            val head = body.trimStart().take(200)
            // RSS / Atom：只抽标题、摘要、链接，避免被 XML 噪音占满上下文
            if (head.startsWith("<?xml") || head.startsWith("<rss") || head.startsWith("<feed")) {
                val digest = parseFeed(body)
                if (digest.isNotEmpty()) {
                    return if (digest.length > 6000) digest.take(6000) + "\n（已截断）" else digest
                }
            }

            val text = if (body.trimStart().startsWith("<")) {
                val doc = Jsoup.parse(body, url)
                doc.select("script,style,nav,footer,header,aside,form").remove()
                doc.body()?.text().orEmpty()
            } else {
                body
            }

            val clean = text.replace(Regex("\\s+"), " ").trim()
            return when {
                clean.isEmpty() -> "（页面没有可读正文）"
                clean.length > 6000 -> clean.take(6000) + "\n\n（已截断，原文更长）"
                else -> clean
            }
        }
    }

    /** 把 RSS/Atom 压成「标题 + 一句摘要 + 链接」的清单 */
    private fun parseFeed(xml: String): String {
        return try {
            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            val items = doc.select("item, entry")
            if (items.isEmpty()) return ""

            val sb = StringBuilder()
            for (item in items.take(25)) {
                val title = item.selectFirst("title")?.text().orEmpty().trim()
                if (title.isEmpty()) continue
                sb.append("- ").append(title).append('\n')

                val raw = item.selectFirst("description, summary, content")?.text().orEmpty()
                val desc = Jsoup.parse(raw).text().replace(Regex("\\s+"), " ").trim()
                if (desc.isNotEmpty()) sb.append("  ").append(desc.take(160)).append('\n')

                val linkEl = item.selectFirst("link")
                val link = linkEl?.let { it.attr("href").ifEmpty { it.text() } }.orEmpty().trim()
                if (link.isNotEmpty()) sb.append("  ").append(link).append('\n')
            }
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * 关键词搜索：不知道网址时用它找线索。
 * 主通道 degoog（返回 JSON，干净），备通道必应中国（抓 HTML）。
 * 两条都不需要 API Key。
 */
class WebSearchTool : Tool {
    override val name = "web_search"
    override val description = """按关键词搜网页，返回标题、链接、摘要，有则带发布时间。
别用：有明确网址直接 web_fetch；天气用 weather；邮件用 email。
一轮没结果就换说法，别用同一个词反复搜；要交叉验证就换 2–3 个角度。"""

    private val mediaJson = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "关键词，空格分隔，中文即可")
                    })
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "条数，默认 5，最多 10")
                    })
                })
                put("required", JSONArray().put("query"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return "错误：query 不能为空"
        val limit = args.optInt("limit", 5).coerceIn(1, 10)

        // 依次降级：Exa（质量最高） → degoog（快且干净） → 必应（兜底）
        ExaClient.search(query, limit)?.let { return "[Exa]\n$it" }
        searchDegoog(query, limit)?.let { return "[degoog]\n$it" }
        searchBing(query, limit)?.let { return "[必应]\n$it" }

        return "搜索没拿到结果（三个通道都失败了）。可以换个说法，或直接给我具体网址。"
    }

    private fun searchDegoog(query: String, limit: Int): String? {
        return try {
            val payload = JSONObject().put("query", query).put("page", 1)
            val req = Request.Builder()
                .url("https://degoog.org/api/search")
                .header("Content-Type", "application/json")
                .header("User-Agent", UA)
                .post(payload.toString().toRequestBody(mediaJson))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val root = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    return null
                }
                val arr = root.optJSONArray("results") ?: return null
                if (arr.length() == 0) return null

                val sb = StringBuilder()
                for (i in 0 until minOf(arr.length(), limit)) {
                    val r = arr.getJSONObject(i)
                    val title = r.optString("title").trim()
                    val url = r.optString("url").trim()
                    if (title.isEmpty() || url.isEmpty()) continue

                    // 发布时间是判断时效的关键，之前丢掉了
                    val date = r.optString("publishedAt").trim()
                    sb.append(i + 1).append(". ").append(title)
                    if (date.isNotEmpty()) sb.append("（").append(date).append("）")
                    sb.append('\n')
                    sb.append("   ").append(url).append('\n')

                    val snippet = r.optString("snippet").trim()
                    if (snippet.isNotEmpty()) {
                        sb.append("   ").append(snippet.take(180)).append('\n')
                    }
                }
                if (sb.isEmpty()) null else trim(sb.toString())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun searchBing(query: String, limit: Int): String? {
        return try {
            val url = "https://cn.bing.com/search?q=" +
                URLEncoder.encode(query, "UTF-8") + "&setlang=zh-CN"
            val req = Request.Builder().url(url).header("User-Agent", UA).build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string().orEmpty()
                val doc = Jsoup.parse(html, url)
                val items = doc.select("li.b_algo")
                if (items.isEmpty()) return null

                val sb = StringBuilder()
                var n = 0
                for (el in items) {
                    if (n >= limit) break
                    val a = el.selectFirst("h2 a") ?: continue
                    val title = a.text().trim()
                    val href = a.attr("href").trim()
                    if (title.isEmpty() || href.isEmpty()) continue
                    n++
                    sb.append(n).append(". ").append(title).append('\n')
                    sb.append("   ").append(href).append('\n')
                    val p = el.selectFirst("p")?.text()?.trim().orEmpty()
                    if (p.isNotEmpty()) sb.append("   ").append(p.take(180)).append('\n')
                }
                if (sb.isEmpty()) null else trim(sb.toString())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun trim(s: String): String =
        if (s.length > 3500) s.take(3500) + "\n（结果太多已截断）" else s

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}

/** 写长期记忆 */
class RememberTool(private val memoryFile: File) : Tool {
    override val name = "remember"
    override val description = """把一条关于用户的稳定事实写进长期记忆，以后每轮自动带上。
记：他自己说过的偏好、习惯、固定安排（复查、缴费、纪念日这类）。
不记：一次性的对话内容，也不要记你没被告诉过的事。
这是写入工具，查记忆用 self 的 memories；记忆里没有就说不知道，不要为了回答去写一条。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("fact", JSONObject().apply {
                        put("type", "string")
                        put("description", "一句陈述，只写他自己说过的，例：不吃香菜")
                    })
                })
                put("required", JSONArray().put("fact"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val fact = args.optString("fact").trim()
        if (fact.isEmpty()) return "错误：fact 不能为空"
        memoryFile.parentFile?.mkdirs()
        memoryFile.appendText("- $fact\n")
        return "已记住：$fact"
    }
}

/** 发一条系统通知：让结果能主动出现在你眼前 */
class NotifyTool(private val ctx: Context) : Tool {
    override val name = "notify"
    override val description = """发手机通知，用户不开 App 也能看到。
用：事情办完、用户说「好了告诉我」、需要在几小时后提醒他的事。
别用：正在对话时直接回答就行；只是进度（界面有状态行）；后台定时任务的结果由系统自动推送，不用调这个。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("title", JSONObject().apply {
                        put("type", "string")
                        put("description", "标题，20 字内")
                    })
                    put("body", JSONObject().apply {
                        put("type", "string")
                        put("description", "正文，200 字内，第一句给结论")
                    })
                })
                put("required", JSONArray().put("title").put("body"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val title = args.optString("title").ifBlank { "飞飞" }
        val body = args.optString("body")
        if (body.isBlank()) return "错误：body 不能为空"

        val ok = NotificationHelper.send(ctx, title, body)
        return if (ok) {
            "已发出通知：$title"
        } else {
            "没发出去：没有通知权限，或者系统把通知关了。" +
                "直接告诉用户去「设置 → 通知 → 飞飞」打开，不要说你已经发了。"
        }
    }
}

/** 存成本地文件，适合长内容（简报、抓下来的资料） */
class SaveTool(private val root: File) : Tool {
    override val name = "save"
    override val description = """把文本写成本地文件，写完读回核对长度。
只给文件名 → agent/saved/；给相对路径（agent/www/app.js）→ 相对 files/，自动建目录。
要发给别人：先 save 再 share。
返回「写 N 字、读回 N 字」，不一致说明没写全，要重写。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("filename", JSONObject().apply {
                        put("type", "string")
                        put("description", "文件名或相对路径，例 note.md 或 agent/www/app.js")
                    })
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "要写入的内容")
                    })
                })
                put("required", JSONArray().put("filename").put("content"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val rawName = args.optString("filename").trim()
        val content = args.optString("content")
        if (content.isBlank()) return "错误：content 不能为空"

        // 带斜杠就当相对 App 私有目录的路径；不带就落到 agent/saved/
        val base = rawName.ifBlank { "note-" + System.currentTimeMillis() }
        val rel = if (base.contains("/")) base else "agent/saved/$base"
        val clean = rel.replace("..", "_").trimStart('/')
        val name = if (clean.contains(".")) clean else "$clean.md"

        return try {
            val f = File(root, name)
            if (!f.canonicalPath.startsWith(root.canonicalPath)) return "错误：路径越界"

            f.parentFile?.mkdirs()
            f.writeText(content)

            // 写完读回来核对一遍，别只看 writeText 没抛异常就宣布成功
            val back = if (f.exists()) f.readText() else ""
            val same = back.length == content.length
            "已保存 $name（写 ${content.length} 字，读回 ${back.length} 字，" +
                (if (same) "一致）" else "不一致，可能没写全）")
        } catch (e: Exception) {
            "保存失败：${e.message}"
        }
    }
}
