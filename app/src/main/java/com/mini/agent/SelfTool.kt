package com.mini.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自省工具：让它能看见自己。
 *
 * 只读为主，唯一能改的是长期记忆（删条目）。
 * 代码在 APK 里，运行时改不了——这不是权限问题，是编译型语言的物理限制。
 */
class SelfTool(private val ctx: Context) : Tool {

    override val name = "self"
    override val description = """查看和改造自己，改动下一轮生效。
看：stats 概览 / logs 运行日志 / config 接口配置 / memories / history / prompt / tools。
改提示词：set_prompt（整段替换，先 prompt 读原文）、reset_prompt（恢复默认）。
改记忆：forget（按关键词删）；summary（看当前会话的压缩摘要，clear=true 清空）。
加工具：add_tool（HTTP 工具，传 json）、remove_tool。
改接口：set_provider / use_provider / remove_provider / set_params。
换模型：use_model（传 model 名，取值见 config 里的「可选模型」清单）。
何时用：工具连续失败两次以上查 logs；缺现成能力就 add_tool；换模型别猜，先 config 看清单。
改不了 Kotlin 代码——那是编译进 APK 的。"""

    /** 内置工具清单，加工具时同步这里 */
    private val builtinTools = listOf(
        "web_search", "web_fetch", "weather", "email", "email_send",
        "remember", "notify", "save", "share", "run_js", "web_app", "self", "task",
        "netdisk", "contact", "phone", "sms", "location", "map", "notifications"
    )

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray()
                            .put("stats").put("logs").put("memories").put("forget").put("history")
                            .put("prompt").put("set_prompt").put("reset_prompt")
                            .put("tools").put("add_tool").put("remove_tool")
                            .put("config").put("set_provider").put("use_provider")
                            .put("use_model")
                            .put("remove_provider").put("set_params")
                            .put("summary")
                            .put("read"))
                        put("description", "动作，取值见 enum")
                    })
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "条数，默认 20")
                    })
                    put("only_failures", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "只看失败的")
                    })
                    put("match", JSONObject().apply {
                        put("type", "string")
                        put("description", "关键词，包含即删")
                    })
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", "提示词全文，整段替换")
                    })
                    put("json", JSONObject().apply {
                        put("type", "string")
                        put("description",
                            "JSON 定义：id + name + baseUrl + apiKey + model（可选 temperature/maxTokens）。" +
                                "add_tool 时用：name + description + method + url（含 {参数占位}）+ params + headers")
                    })
                    put("tool_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "工具名")
                    })
                    put("provider_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "接口 id，见 config")
                    })
                    put("model", JSONObject().apply {
                        put("type", "string")
                        put("description", "模型名，use_model 时传，取值见 config 的「可选模型」")
                    })
                    put("reveal", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "显示完整 key，默认打码")
                    })
                    put("clear", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "summary 时传 true 清空摘要")
                    })
                    put("file", JSONObject().apply {
                        put("type", "string")
                        put("description",
                            "read 时传文件名，见 agent 目录：config.json / memory.md / " +
                                "tasks.json / suggestions.json / system.md / log.jsonl / custom_tools.json")
                    })
                })
                put("required", JSONArray().put("action"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase()
        val limit = args.optInt("limit", 20).coerceIn(1, 100)

        return try {
            when (action) {
                "stats" -> stats()
                "logs" -> RunLog.tail(ctx, limit, args.optBoolean("only_failures", false))
                "memories" -> memories()
                "forget" -> forget(args.optString("match").trim())
                "history" -> history(limit)
                "prompt" -> PromptStore.load(ctx)
                "set_prompt" -> PromptStore.save(ctx, args.optString("text"))
                "reset_prompt" -> PromptStore.reset(ctx)
                "tools" -> CustomTools.list(ctx)
                "add_tool" -> CustomTools.add(ctx, args.optString("json"))
                "remove_tool" -> CustomTools.remove(ctx, args.optString("tool_name").trim())
                "config" -> showConfig(args.optBoolean("reveal", false))
                "set_provider" -> AgentConfig.upsertProvider(ctx, parseJson(args.optString("json")))
                "use_provider" -> AgentConfig.setActive(ctx, args.optString("provider_id").trim())
                "use_model" -> AgentConfig.setModel(ctx, args.optString("model").trim())
                "remove_provider" ->
                    AgentConfig.removeProvider(ctx, args.optString("provider_id").trim())
                "set_params" -> AgentConfig.setParams(ctx, parseJson(args.optString("json")))
                "summary" -> summaryInfo(args)
                "read" -> readAgentFile(args.optString("file"))
                else -> "错误：不认识 action=$action"
            }
        } catch (e: Exception) {
            "自省失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    // ---------- stats ----------

    /**
     * 把 config.json 的原文读出来。这是模型「看见自己的接口配置」的唯一入口。
     * 之前配置藏在 SharedPreferences 里，它看不到实体，问起来只能编。
     */
    private fun showConfig(reveal: Boolean): String {
        val path = AgentConfig.file(ctx).absolutePath
        val o = try {
            JSONObject(AgentConfig.raw(ctx))
        } catch (e: Exception) {
            return "读取配置失败：${e.message}"
        }

        if (!reveal) {
            val arr = o.optJSONArray("providers")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    it.put("apiKey", AgentConfig.maskKey(it.optString("apiKey")))
                }
            }
        }

        val cur = AgentConfig.active(ctx)
        val sb = StringBuilder()
        sb.append("配置文件：").append(path).append("\n")
        sb.append("当前生效：").append(cur.name).append("（").append(cur.id).append("）")
        sb.append(" · ").append(cur.model).append(" · temp=").append(cur.temperature)
        sb.append(" · max_tokens=").append(cur.maxTokens).append("\n\n")

        // 把可选模型列出来：不然用户说「换成 deepseek」，模型只知道当前这一个
        sb.append("可选模型（用 use_model 切换，传 model 名）：\n")
        AgentConfig.models(ctx).forEach { (id, note) ->
            sb.append("  · ").append(id)
            if (note.isNotEmpty()) sb.append("  —  ").append(note)
            if (id == cur.model) sb.append("   ← 当前在用")
            sb.append("\n")
        }
        sb.append("\n")

        sb.append(try {
            o.toString(2)
        } catch (e: Exception) {
            AgentConfig.raw(ctx)
        })
        if (!reveal) sb.append("\n\n（apiKey 已打码。要看完整值加 reveal=true）")
        return sb.toString()
    }

    /** 看/清当前会话的压缩摘要 */
    private fun summaryInfo(args: JSONObject): String {
        val store = AgentFactory.store(ctx)
        val sid = store.currentId()

        if (args.optBoolean("clear", false)) {
            store.writeSummaryOf(sid, "")
            return "已清空当前会话的摘要。历史消息不受影响。"
        }

        val s = store.readSummaryOf(sid)
        if (s.isBlank()) {
            return "当前会话还没有摘要。对话超过约 6000 字或 20 条时才会自动压缩。"
        }
        return "当前会话摘要（共 ${s.length} 字）：\n$s"
    }

    /** 读 files/agent 下的文件：让「你自己怎么配的」这类问题有事实可依，不用编 */
    private fun readAgentFile(name: String): String {
        val n = name.trim().removePrefix("agent/").removePrefix("/")
        if (n.isEmpty() || n.contains("..")) return "文件名不对"

        val f = java.io.File(ctx.filesDir, "agent/$n")
        if (!f.exists() || f.isDirectory) {
            val list = java.io.File(ctx.filesDir, "agent")
                .listFiles()?.joinToString("、") { it.name }.orEmpty()
            return "没有 $n。agent 目录下现在有：$list"
        }
        return try {
            val t = f.readText()
            if (t.length > 6000) t.take(6000) + "\n…（截断，全文 ${t.length} 字）" else t
        } catch (e: Exception) {
            "读失败：${e.message}"
        }
    }

    private fun parseJson(s: String): JSONObject = try {
        if (s.isBlank()) JSONObject() else JSONObject(s)
    } catch (e: Exception) {
        JSONObject()
    }

    private fun stats(): String {
        val p = AgentFactory.prefs(ctx)
        val store = AgentFactory.store(ctx)
        val sb = StringBuilder()

        sb.append("飞飞 v").append(versionName()).append("\n")

        val api = AgentConfig.active(ctx)
        sb.append("当前接口：").append(api.name).append("（").append(api.id).append("）")
        sb.append(" · ").append(api.model).append("\n")
        sb.append("接口地址：").append(api.baseUrl).append("\n")
        sb.append("API Key：").append(if (api.apiKey.isEmpty()) "未填" else "已填").append("\n")

        val all = AgentConfig.providers(ctx)
        sb.append("已配置接口 ").append(all.size).append(" 个")
        if (all.size > 1) sb.append("：").append(all.joinToString("、") { it.id })
        sb.append("（用 config 查看全文）\n")

        val gp = AgentConfig.params(ctx)
        sb.append("轮数：对话 ").append(gp.maxTurns).append(" / 后台 ").append(gp.backgroundTurns)
        sb.append("，工具上限 ").append(gp.maxToolCalls).append(" 次\n")

        sb.append("内置工具 ").append(builtinTools.size).append(" 个：")
        sb.append(builtinTools.joinToString("、")).append("\n")

        val custom = CustomTools.raw(ctx).length()
        if (custom > 0) {
            sb.append("自定义工具：").append(custom).append(" 个（用 tools 查看）\n")
        }

        val promptLen = PromptStore.load(ctx).length
        sb.append("系统提示词：").append(promptLen).append(" 字")
        sb.append(if (PromptStore.file(ctx).exists()) "（自定义）" else "（默认）").append("\n")

        val memLines = store.readMemory().lines().count { it.isNotBlank() }
        sb.append("长期记忆：").append(memLines).append(" 条\n")
        sb.append("会话记录：").append(store.loadHistory().size).append(" 条\n")

        val logs = RunLog.count(ctx)
        val fails = RunLog.failureCount(ctx)
        sb.append("运行日志：").append(logs).append(" 条")
        if (fails > 0) sb.append("，其中失败 ").append(fails).append(" 条")
        sb.append("\n")

        val mail = p.getString("mailUser", "").orEmpty()
        if (mail.isEmpty()) {
            sb.append("邮箱：未配置\n")
        } else {
            sb.append("邮箱：").append(mail)
            sb.append("，发信").append(if (EmailClient.canSend(ctx)) "已开启" else "关闭")
            sb.append("\n")
        }

        val loc = LocationHelper.current(ctx)
        sb.append("位置：").append(loc.ifEmpty { "未识别" }).append("\n")

        return sb.toString().trim()
    }

    private fun versionName(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    // ---------- memories ----------

    private fun memories(): String {
        val mem = AgentFactory.store(ctx).readMemory()
        if (mem.isBlank()) return "还没有长期记忆"

        val lines = mem.lines().filter { it.isNotBlank() }
        return "长期记忆 ${lines.size} 条：\n" + lines.mapIndexed { i, l ->
            "${i + 1}. ${l.removePrefix("- ")}"
        }.joinToString("\n")
    }

    private fun forget(match: String): String {
        if (match.isBlank()) return "错误：forget 需要 match 参数"

        val store = AgentFactory.store(ctx)
        val all = store.readMemory().lines().filter { it.isNotBlank() }
        val kept = all.filterNot { it.contains(match, ignoreCase = true) }

        if (kept.size == all.size) {
            return "没找到包含「$match」的记忆。可以先用 memories 看看有哪些。"
        }
        val removed = all.size - kept.size
        store.writeMemory(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        return "已删除 $removed 条记忆"
    }

    // ---------- history ----------

    private fun history(limit: Int): String {
        val all = AgentFactory.store(ctx).loadHistory()
        if (all.isEmpty()) return "还没有对话记录"

        return all.takeLast(limit).joinToString("\n\n") {
            val who = if (it.role == "user") "用户" else "飞飞"
            "$who：${(it.content ?: "").take(200)}"
        }
    }
}
