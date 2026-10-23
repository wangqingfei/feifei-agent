package com.mini.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 接口配置外置成 config.json，模型和用户都能直接改。
 *
 * 之前配置只存在 SharedPreferences 里，模型看不见实体，问它「配置文件在哪」只能瞎编。
 * 现在它是一个真实文件：files/agent/config.json
 *
 * 结构：
 * ```
 * {
 *   "active": "cmecloud",
 *   "providers": [
 *     { "id":"cmecloud", "name":"移动云",
 *       "baseUrl":"https://.../v1", "apiKey":"sk-...", "model":"Qwen3.5-27B",
 *       "temperature":0.5, "maxTokens":4096 }
 *   ],
 *   "agent": { "maxTurns":4, "backgroundTurns":6, "maxToolCalls":10 }
 * }
 * ```
 */
object AgentConfig {

    private const val NAME = "config.json"

    /**
     * 可选模型清单。
     * 内置一份，同时会写进 config.json —— 否则模型只能看到"当前用的那个"，
     * 用户让它「换成 deepseek」时它读不到清单，只能回「配置文件里没有」。
     */
    val DEFAULT_MODELS = listOf(
        "Qwen3.5-27B" to "最稳，工具后必出正文，约 1.3 秒",
        "deepseek-v4.1-flash" to "最快，偶尔不吐正文",
        "glm-5.3-flash" to "约 1 秒，答案易混在推理里",
        "qwen3.5-397b-a17b" to "约 1.7 秒，能力最强",
        "Qwen3.5-35B-A3B" to "约 2.1 秒"
    )

    fun file(ctx: Context) = File(ctx.filesDir, "agent/$NAME")

    data class Provider(
        val id: String,
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val temperature: Double,
        val maxTokens: Int
    )

    data class Params(
        val maxTurns: Int,
        val backgroundTurns: Int,
        val maxToolCalls: Int
    )

    // ---------- 读写 ----------

    /** 不存在就从旧 SharedPreferences 迁一份出来，之后以文件为准 */
    fun load(ctx: Context): JSONObject {
        val f = file(ctx)
        if (f.exists()) {
            try {
                return ensureModels(ctx, JSONObject(f.readText()))
            } catch (e: Exception) {
                // 文件被改坏了，先备份再重建，别让 App 起不来
                try {
                    f.renameTo(File(f.parentFile, "config.broken.json"))
                } catch (e2: Exception) {
                }
            }
        }
        val fresh = fromPrefs(ctx)
        save(ctx, fresh)
        return fresh
    }

    /** 老配置没有 models 字段时补一份，用户和模型都看得到有哪些可选 */
    private fun ensureModels(ctx: Context, o: JSONObject): JSONObject {
        val arr = o.optJSONArray("models")
        if (arr != null && arr.length() > 0) return o
        o.put("models", modelsJson())
        try {
            save(ctx, o)
        } catch (e: Exception) {
        }
        return o
    }

    private fun modelsJson(): JSONArray {
        val arr = JSONArray()
        DEFAULT_MODELS.forEach {
            arr.put(JSONObject().put("id", it.first).put("note", it.second))
        }
        return arr
    }

    /** 可选模型：模型名 to 备注 */
    fun models(ctx: Context): List<Pair<String, String>> {
        val arr = load(ctx).optJSONArray("models") ?: return DEFAULT_MODELS
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("id").trim()
            if (id.isNotEmpty()) out.add(id to m.optString("note", ""))
        }
        return out.ifEmpty { DEFAULT_MODELS }
    }

    /**
     * 切换当前接口用的模型。只改 model 字段，地址和 key 不动。
     * 清单外的名字也允许（用户可能自己部署了别的），但会提醒一句。
     */
    fun setModel(ctx: Context, model: String): String {
        val m = model.trim()
        if (m.isEmpty()) return "模型名不能为空"

        val o = load(ctx)
        val id = o.optString("active").trim()
        val arr = o.optJSONArray("providers") ?: return "配置里没有 providers"
        var hit = false
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            if (p.optString("id") == id) {
                p.put("model", m)
                hit = true
            }
        }
        if (!hit) return "没找到当前接口 $id，先看 config"

        val known = models(ctx).map { it.first }
        val warn = if (m in known) "" else "（注意：$m 不在可选清单里，可能调不通）"

        save(ctx, o)
        AgentFactory.prefs(ctx).edit().putString(AgentFactory.KEY_MODEL, m).apply()
        return "已切换：接口 $id 现在用 $m$warn。下一轮对话生效，不用重启。"
    }

    fun save(ctx: Context, o: JSONObject) {
        val f = file(ctx)
        f.parentFile?.mkdirs()
        f.writeText(o.toString(2))
    }

    fun raw(ctx: Context): String = try {
        load(ctx).toString(2)
    } catch (e: Exception) {
        "{}"
    }

    private fun fromPrefs(ctx: Context): JSONObject {
        val p = AgentFactory.prefs(ctx)
        val base = p.getString(AgentFactory.KEY_BASE, AgentFactory.DEFAULT_BASE)
            ?: AgentFactory.DEFAULT_BASE
        val key = p.getString(AgentFactory.KEY_API, "").orEmpty()
        val model = p.getString(AgentFactory.KEY_MODEL, AgentFactory.DEFAULT_MODEL)
            ?: AgentFactory.DEFAULT_MODEL

        return JSONObject()
            .put("active", "default")
            .put("providers", JSONArray().put(
                JSONObject()
                    .put("id", "default")
                    .put("name", "移动云")
                    .put("baseUrl", base)
                    .put("apiKey", key)
                    .put("model", model)
                    .put("temperature", 0.5)
                    .put("maxTokens", 4096)
            ))
            .put("agent", JSONObject()
                .put("maxTurns", AgentLoop.MAX_TURNS)
                .put("backgroundTurns", 6)
                .put("maxToolCalls", AgentLoop.MAX_TOOL_CALLS))
    }

    // ---------- 读取结构化 ----------

    fun providers(ctx: Context): List<Provider> {
        val arr = load(ctx).optJSONArray("providers") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim().ifEmpty { "p$i" }
            Provider(
                id = id,
                name = o.optString("name").trim().ifEmpty { id },
                baseUrl = o.optString("baseUrl").trim(),
                apiKey = o.optString("apiKey").trim(),
                model = o.optString("model").trim(),
                temperature = o.optDouble("temperature", 0.5),
                maxTokens = o.optInt("maxTokens", 4096)
            )
        }
    }

    fun activeId(ctx: Context): String = load(ctx).optString("active").trim()

    /** 当前生效的接口；配错时返回一个能报错的兜底值，而不是崩 */
    fun active(ctx: Context): Provider {
        val list = providers(ctx)
        val id = activeId(ctx)
        return list.firstOrNull { it.id == id }
            ?: list.firstOrNull()
            ?: Provider(
                "default", "未配置", AgentFactory.DEFAULT_BASE, "",
                AgentFactory.DEFAULT_MODEL, 0.5, 4096
            )
    }

    fun params(ctx: Context): Params {
        val a = load(ctx).optJSONObject("agent")
        return Params(
            maxTurns = a?.optInt("maxTurns", AgentLoop.MAX_TURNS) ?: AgentLoop.MAX_TURNS,
            backgroundTurns = a?.optInt("backgroundTurns", 6) ?: 6,
            maxToolCalls = a?.optInt("maxToolCalls", AgentLoop.MAX_TOOL_CALLS)
                ?: AgentLoop.MAX_TOOL_CALLS
        )
    }

    // ---------- 修改 ----------

    fun setActive(ctx: Context, id: String): String {
        val o = load(ctx)
        val exists = providers(ctx).any { it.id == id }
        if (!exists) return "错误：没有 id 为 $id 的接口。现有：${providers(ctx).joinToString { it.id }}"
        o.put("active", id)
        save(ctx, o)
        val p = providers(ctx).first { it.id == id }
        return "已切换到「${p.name}」：${p.model} @ ${p.baseUrl}"
    }

    /**
     * 新增或覆盖一个接口。id 相同就是改，不再就是加。
     * 只传部分字段时，未传的沿用旧值（新增时用默认值）。
     */
    fun upsertProvider(ctx: Context, patch: JSONObject): String {
        if (patch.length() == 0) {
            return "错误：没收到有效的接口定义（json 为空、或不是合法 JSON）。" +
                "示例：{\"id\":\"local\",\"baseUrl\":\"http://127.0.0.1:11434/v1\"," +
                "\"model\":\"qwen3\",\"apiKey\":\"x\"}"
        }
        val id = patch.optString("id").trim()
        if (id.isEmpty()) return "错误：必须给 id，例如 cmecloud / deepseek / local"
        if (!id.matches(Regex("[a-zA-Z0-9_-]{1,32}"))) {
            return "错误：id 只能用字母数字下划线短横线，最长 32"
        }

        val o = load(ctx)
        val arr = o.optJSONArray("providers") ?: JSONArray()

        var target: JSONObject? = null
        var index = -1
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("id") == id) {
                target = it
                index = i
                break
            }
        }

        val base = target ?: JSONObject()
            .put("id", id)
            .put("name", id)
            .put("baseUrl", AgentFactory.DEFAULT_BASE)
            .put("apiKey", "")
            .put("model", AgentFactory.DEFAULT_MODEL)
            .put("temperature", 0.5)
            .put("maxTokens", 4096)

        for (k in listOf("name", "baseUrl", "apiKey", "model")) {
            if (patch.has(k)) base.put(k, patch.optString(k).trim())
        }
        if (patch.has("temperature")) base.put("temperature", patch.optDouble("temperature", 0.5))
        if (patch.has("maxTokens")) base.put("maxTokens", patch.optInt("maxTokens", 4096))

        if (target == null) arr.put(base) else arr.put(index, base)
        o.put("providers", arr)

        // 第一个接口自动成为当前
        if (o.optString("active").isBlank()) o.put("active", id)
        save(ctx, o)

        return if (target == null) "已新增接口「$id」" else "已更新接口「$id」"
    }

    fun removeProvider(ctx: Context, id: String): String {
        val o = load(ctx)
        val arr = o.optJSONArray("providers") ?: return "错误：没有可删的接口"
        if (arr.length() <= 1) return "错误：至少留一个接口，不然没得用了"

        val keep = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("id") == id && !removed) {
                removed = true
                continue
            }
            keep.put(it)
        }
        if (!removed) return "错误：没有 id 为 $id 的接口"

        o.put("providers", keep)
        if (o.optString("active") == id) {
            o.put("active", keep.optJSONObject(0)?.optString("id") ?: "")
        }
        save(ctx, o)
        return "已删除接口「$id」"
    }

    fun setParams(ctx: Context, patch: JSONObject): String {
        val o = load(ctx)
        val a = o.optJSONObject("agent") ?: JSONObject()
        for (k in listOf("maxTurns", "backgroundTurns", "maxToolCalls")) {
            if (patch.has(k)) a.put(k, patch.optInt(k).coerceIn(1, 30))
        }
        o.put("agent", a)
        save(ctx, o)
        return "已更新运行参数：$a"
    }

    /** 给界面用：把当前接口的改动写回配置文件 */
    fun updateActive(ctx: Context, baseUrl: String, apiKey: String, model: String): String {
        val cur = active(ctx)
        val patch = JSONObject()
            .put("id", cur.id)
            .put("baseUrl", baseUrl)
            .put("apiKey", apiKey)
            .put("model", model)
        val r = upsertProvider(ctx, patch)
        // 同步回 prefs，兼容还在读旧位置的老代码
        AgentFactory.prefs(ctx).edit()
            .putString(AgentFactory.KEY_BASE, baseUrl)
            .putString(AgentFactory.KEY_API, apiKey)
            .putString(AgentFactory.KEY_MODEL, model)
            .apply()
        return r
    }

    /** 密钥打码，防止对话记录或日志把 key 带出去 */
    fun maskKey(k: String): String {
        if (k.length <= 8) return if (k.isEmpty()) "" else "****"
        return k.take(4) + "****" + k.takeLast(4)
    }
}
