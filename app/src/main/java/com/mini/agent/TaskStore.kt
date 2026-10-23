package com.mini.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 一条任务。
 *
 * 两种形态：
 * - 重复任务：time = "HH:mm"，days = 1-7（空 = 每天），到点跑，跑完排下一次
 * - 一次性任务：once = true，runAt = 绝对毫秒时间戳，跑完自动从文件里删掉
 */
data class AgentTask(
    val id: String,
    val name: String,
    val time: String,
    val days: List<Int>,
    val prompt: String,
    val enabled: Boolean = true,
    /** 只跑一次，跑完自动删除 */
    val once: Boolean = false,
    /** 一次性任务的绝对触发时间；0 表示还没算过，按 time 推 */
    val runAt: Long = 0L
) {

    fun timeParts(): Pair<Int, Int> {
        val p = time.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: 7
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        return h.coerceIn(0, 23) to m.coerceIn(0, 59)
    }

    /** 人话：每天 / 周一三五 / 一次性 10-23 08:05 */
    fun daysText(): String {
        if (once) {
            val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            return "一次 " + sdf.format(java.util.Date(if (runAt > 0) runAt else System.currentTimeMillis()))
        }
        if (days.isEmpty() || days.size >= 7) return "每天"
        val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
        return "周" + days.sorted().joinToString("") { names[(it - 1).coerceIn(0, 6)] }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("time", time)
        put("prompt", prompt)
        put("enabled", enabled)
        put("days", JSONArray(days))
        put("once", once)
        put("runAt", runAt)
    }

    companion object {

        fun from(o: JSONObject?): AgentTask? {
            if (o == null) return null
            val id = o.optString("id").trim().ifEmpty { return null }
            return AgentTask(
                id = id,
                name = o.optString("name", "定时任务").trim().ifEmpty { "定时任务" },
                time = normalizeTime(o.optString("time", "07:00")),
                days = parseDays(o.optJSONArray("days")),
                prompt = o.optString("prompt", "").trim(),
                enabled = o.optBoolean("enabled", true),
                once = o.optBoolean("once", false),
                runAt = o.optLong("runAt", 0L)
            )
        }

        /** 容忍 7:00 / 7点 / 07：00 这些写法 */
        fun normalizeTime(raw: String): String {
            val s = raw.trim().replace("：", ":").replace(".", ":").replace("点", ":")
            val p = s.split(":")
            val h = p.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: 7
            val m = p.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            return String.format(Locale.CHINA, "%02d:%02d", h.coerceIn(0, 23), m.coerceIn(0, 59))
        }

        private fun parseDays(arr: JSONArray?): List<Int> {
            if (arr == null) return emptyList()
            val out = mutableListOf<Int>()
            for (i in 0 until arr.length()) {
                val v = arr.optInt(i, -1)
                if (v in 1..7 && v !in out) out.add(v)
            }
            return out
        }
    }
}

/**
 * 任务的唯一真相来源：files/agent/tasks.json
 * 改完下次调度生效（在设置页点保存，或发一句「重排」）。
 */
object TaskStore {

    private const val FILE_NAME = "tasks.json"

    fun file(ctx: Context): File {
        val dir = File(ctx.filesDir, "agent")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    fun load(ctx: Context): MutableList<AgentTask> {
        val f = file(ctx)
        val out = mutableListOf<AgentTask>()
        if (!f.exists()) return out
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("tasks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                AgentTask.from(arr.optJSONObject(i))?.let { out.add(it) }
            }
            out
        } catch (e: Exception) {
            // 文件被改坏了也不崩，留个备份再从空开始
            try {
                f.renameTo(File(f.parentFile, "tasks.broken.json"))
            } catch (e2: Exception) {
            }
            mutableListOf()
        }
    }

    fun save(ctx: Context, list: List<AgentTask>) {
        val root = JSONObject()
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        root.put("tasks", arr)
        try {
            file(ctx).writeText(root.toString(2))
        } catch (e: Exception) {
        }
    }

    fun find(ctx: Context, id: String): AgentTask? =
        load(ctx).firstOrNull { it.id == id }

    fun add(
        ctx: Context,
        name: String,
        time: String,
        days: List<Int>,
        prompt: String,
        enabled: Boolean = true
    ): AgentTask {
        val list = load(ctx)
        val t = AgentTask(
            id = newId(),
            name = name.trim().ifEmpty { "定时任务" },
            time = AgentTask.normalizeTime(time),
            days = days.filter { it in 1..7 }.distinct().sorted(),
            prompt = prompt.trim(),
            enabled = enabled
        )
        list.add(t)
        save(ctx, list)
        return t
    }

    /** 一次性任务：到 runAt 那一毫秒跑一次，跑完自己消失 */
    fun addOnce(ctx: Context, name: String, runAt: Long, prompt: String): AgentTask {
        val list = load(ctx)
        val t = AgentTask(
            id = newId(),
            name = name.trim().ifEmpty { "提醒" },
            time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(runAt)),
            days = emptyList(),
            prompt = prompt.trim(),
            enabled = true,
            once = true,
            runAt = runAt
        )
        list.add(t)
        save(ctx, list)
        return t
    }

    fun update(ctx: Context, t: AgentTask): Boolean {        val list = load(ctx)
        val i = list.indexOfFirst { it.id == t.id }
        if (i < 0) return false
        list[i] = t
        save(ctx, list)
        return true
    }

    fun remove(ctx: Context, id: String): Boolean {
        val list = load(ctx)
        val ok = list.removeAll { it.id == id }
        if (ok) save(ctx, list)
        return ok
    }

    fun setEnabled(ctx: Context, id: String, on: Boolean): AgentTask? {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return null
        val t = list[i].copy(enabled = on)
        list[i] = t
        save(ctx, list)
        return t
    }

    private fun newId(): String = "t" + System.currentTimeMillis().toString(36)

    /**
     * 老版本只有一条写死 7:00 的简报，存在 SharedPreferences 里。
     * 第一次进新版本时搬过来，避免用户以为任务丢了。
     */
    fun migrateLegacy(ctx: Context) {
        val f = file(ctx)
        if (f.exists()) return

        val p = AgentFactory.prefs(ctx)
        val prompt = p.getString(AgentFactory.KEY_DAILY_TASK, "").orEmpty().trim()
        val on = p.getBoolean(AgentFactory.KEY_DAILY_ON, false)

        val seed = AgentTask(
            id = newId(),
            name = "今日简报",
            time = "07:00",
            days = emptyList(),
            prompt = prompt.ifEmpty { DailyWorker.DEFAULT_TASK },
            // 只有明确开过才默认开启；没配过就放一条关着的样例，让他知道格式
            enabled = on
        )
        save(ctx, listOf(seed))
    }
}
