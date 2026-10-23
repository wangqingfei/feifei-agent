package com.mini.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 多会话存储。
 *
 * 目录结构：
 *   sessions/{id}.json   每个会话一份：标题、时间、消息、摘要
 *   current_session.txt  当前在哪个会话
 *   memory.md            长期记忆，所有会话共享
 *
 * 对外仍暴露 loadHistory / saveHistory，AgentLoop 不用改，
 * 它们只作用于「当前会话」。
 */
class Store(private val ctx: Context) {

    private val root = File(ctx.filesDir, "agent").apply { mkdirs() }
    private val sessionsDir = File(root, "sessions").apply { mkdirs() }
    private val currentFile = File(root, "current_session.txt")
    private val memoryFile = File(root, "memory.md")

    val memory: File get() = memoryFile

    data class Meta(val id: String, val title: String, val updated: Long, val count: Int)

    // ---------- 会话管理 ----------

    fun listSessions(): List<Meta> {
        val out = mutableListOf<Meta>()
        sessionsDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            try {
                val o = JSONObject(f.readText())
                out += Meta(
                    id = o.optString("id", f.nameWithoutExtension),
                    title = o.optString("title", "未命名"),
                    updated = o.optLong("updated", 0L),
                    count = o.optJSONArray("messages")?.length() ?: 0
                )
            } catch (e: Exception) {
            }
        }
        return out.sortedByDescending { it.updated }
    }

    fun currentId(): String {
        val saved = try {
            if (currentFile.exists()) currentFile.readText().trim() else ""
        } catch (e: Exception) {
            ""
        }
        if (saved.isNotEmpty() && File(sessionsDir, "$saved.json").exists()) return saved
        return createSession()
    }

    fun switchTo(id: String) {
        if (File(sessionsDir, "$id.json").exists()) {
            try {
                currentFile.writeText(id)
            } catch (e: Exception) {
            }
        }
    }

    fun createSession(): String {
        val id = System.currentTimeMillis().toString()
        writeSession(id, "新对话", JSONArray(), "")
        try {
            currentFile.writeText(id)
        } catch (e: Exception) {
        }
        return id
    }

    fun deleteSession(id: String) {
        try {
            File(sessionsDir, "$id.json").delete()
        } catch (e: Exception) {
        }
        val cur = try {
            if (currentFile.exists()) currentFile.readText().trim() else ""
        } catch (e: Exception) {
            ""
        }
        if (cur == id) {
            val rest = listSessions().firstOrNull()
            if (rest != null) switchTo(rest.id) else createSession()
        }
    }

    // ---------- 指定会话的读写（AgentLoop 用，避免中途切换导致写错地方） ----------

    fun loadHistoryOf(id: String): List<ChatMessage> {
        val o = readObject(id) ?: return emptyList()
        val arr = o.optJSONArray("messages") ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            try {
                ChatMessage.fromHistoryJson(arr.getJSONObject(it))
            } catch (e: Exception) {
                null
            }
        }
    }

    fun saveHistoryOf(id: String, messages: List<ChatMessage>) {
        val o = readObject(id) ?: JSONObject().put("id", id)

        val arr = JSONArray()
        messages.takeLast(MAX_KEEP).forEach { arr.put(it.toHistoryJson()) }
        o.put("messages", arr)
        o.put("updated", System.currentTimeMillis())

        if (o.optString("title").isBlank() || o.optString("title") == "新对话") {
            messages.firstOrNull { it.role == "user" }?.content?.let {
                o.put("title", it.replace(Regex("\\s+"), " ").take(16))
            }
        }
        write(id, o)
    }

    /**
     * 追加一条消息并立刻落盘。
     * 关键：用户发完话就写进文件，而不是等整轮回答结束——
     * 否则中途新建对话，旧会话里什么都看不到，像丢了一样。
     */
    fun appendMessageOf(id: String, msg: ChatMessage) {
        val o = readObject(id) ?: JSONObject().put("id", id)
        val old = o.optJSONArray("messages") ?: JSONArray()

        val list = ArrayList<JSONObject>()
        for (i in 0 until old.length()) {
            old.optJSONObject(i)?.let { list.add(it) }
        }
        list.add(msg.toHistoryJson())

        val arr = JSONArray()
        list.takeLast(MAX_KEEP).forEach { arr.put(it) }
        o.put("messages", arr)
        o.put("updated", System.currentTimeMillis())

        if (o.optString("title").isBlank() || o.optString("title") == "新对话") {
            if (msg.role == "user") {
                val c = msg.content.orEmpty()
                if (c.isNotBlank()) {
                    o.put("title", c.replace(Regex("\\s+"), " ").take(16))
                }
            }
        }
        write(id, o)
    }

    fun readSummaryOf(id: String): String = readObject(id)?.optString("summary").orEmpty()

    fun writeSummaryOf(id: String, text: String) {
        val o = readObject(id) ?: JSONObject().put("id", id)
        o.put("summary", text.trim())
        write(id, o)
    }

    // ---------- 当前会话的消息 ----------

    fun loadHistory(): List<ChatMessage> = loadHistoryOf(currentId())

    fun saveHistory(messages: List<ChatMessage>) = saveHistoryOf(currentId(), messages)

    fun clearHistory() {
        val id = currentId()
        val o = readObject(id) ?: return
        o.put("messages", JSONArray())
        o.put("summary", "")
        o.put("title", "新对话")
        o.put("updated", System.currentTimeMillis())
        write(id, o)
    }

    // ---------- 摘要（跟着会话走） ----------

    fun readSummary(): String = readSummaryOf(currentId())

    fun writeSummary(text: String) = writeSummaryOf(currentId(), text)

    fun clearSummary() = writeSummary("")

    // ---------- 长期记忆（所有会话共享） ----------

    fun readMemory(): String =
        if (memoryFile.exists()) memoryFile.readText().trim() else ""

    fun writeMemory(text: String) {
        memoryFile.parentFile?.mkdirs()
        memoryFile.writeText(text)
    }

    // ---------- 内部 ----------

    private fun readObject(id: String): JSONObject? {
        return try {
            val f = File(sessionsDir, "$id.json")
            if (!f.exists()) null else JSONObject(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    private fun write(id: String, o: JSONObject) {
        try {
            sessionsDir.mkdirs()
            File(sessionsDir, "$id.json").writeText(o.toString())
        } catch (e: Exception) {
            // 写失败意味着这次对话可能丢，得留下痕迹
            CrashLog.report(ctx, "写会话 $id 失败", e)
        }
    }

    private fun writeSession(id: String, title: String, messages: JSONArray, summary: String) {
        val o = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("created", System.currentTimeMillis())
            .put("updated", System.currentTimeMillis())
            .put("messages", messages)
            .put("summary", summary)
        write(id, o)
    }

    companion object {
        const val MAX_KEEP = 60
    }
}
