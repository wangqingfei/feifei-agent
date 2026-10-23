package com.mini.agent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志：环形写入，超过 200KB 自动裁到最近 300 条。
 *
 * 它存在的意义不是给人看，是让模型能读到「上次这个工具失败了」，
 * 从而这一轮换个做法，而不是每次都从零试错。
 */
object RunLog {

    private const val MAX_BYTES = 200_000L
    private const val KEEP_LINES = 300

    private fun file(ctx: Context) = File(ctx.filesDir, "agent/log.jsonl")

    @Synchronized
    fun append(
        ctx: Context,
        tool: String,
        arg: String,
        result: String,
        ms: Long,
        ok: Boolean
    ) {
        try {
            val f = file(ctx)
            f.parentFile?.mkdirs()
            val o = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("tool", tool)
                .put("arg", arg.take(300))
                .put("res", result.take(300))
                .put("ms", ms)
                .put("ok", ok)
            f.appendText(o.toString() + "\n")
            if (f.length() > MAX_BYTES) trim(f)
        } catch (e: Exception) {
            // 日志写失败绝不能影响主流程
        }
    }

    private fun trim(f: File) {
        try {
            val kept = f.readLines().filter { it.isNotBlank() }.takeLast(KEEP_LINES)
            f.writeText(kept.joinToString("\n") + "\n")
        } catch (e: Exception) {
        }
    }

    fun tail(ctx: Context, limit: Int, onlyFailures: Boolean): String {
        val f = file(ctx)
        if (!f.exists()) return "还没有运行记录"

        return try {
            var lines = f.readLines().filter { it.isNotBlank() }
            if (onlyFailures) {
                lines = lines.filter {
                    try {
                        !JSONObject(it).optBoolean("ok", true)
                    } catch (e: Exception) {
                        false
                    }
                }
            }
            if (lines.isEmpty()) {
                return if (onlyFailures) "没有失败记录，挺顺的" else "还没有运行记录"
            }
            lines.takeLast(limit).joinToString("\n") { fmt(it) }
        } catch (e: Exception) {
            "读日志失败：${e.message}"
        }
    }

    private fun fmt(line: String): String {
        return try {
            val o = JSONObject(line)
            val ts = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(o.optLong("t")))
            val ok = o.optBoolean("ok", true)
            val sb = StringBuilder()
            sb.append(ts).append(' ')
            sb.append(if (ok) "ok  " else "FAIL").append(' ')
            sb.append(o.optString("tool")).append(' ')

            val arg = o.optString("arg")
            if (arg.isNotBlank() && arg != "{}") sb.append(arg.take(50)).append(" -> ")

            sb.append(o.optString("res").replace("\n", " ").take(80))
            sb.append(" (").append(o.optLong("ms")).append("ms)")
            sb.toString()
        } catch (e: Exception) {
            line
        }
    }

    fun count(ctx: Context): Int = try {
        val f = file(ctx)
        if (f.exists()) f.readLines().count { it.isNotBlank() } else 0
    } catch (e: Exception) {
        0
    }

    fun failureCount(ctx: Context): Int = try {
        val f = file(ctx)
        if (!f.exists()) 0
        else f.readLines().count {
            it.isNotBlank() && !JSONObject(it).optBoolean("ok", true)
        }
    } catch (e: Exception) {
        0
    }

    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (e: Exception) {
        }
    }
}
