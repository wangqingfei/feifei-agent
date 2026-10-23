package com.mini.agent

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志：把「App 挂掉时的现场」存到本地文件。
 *
 * 为什么必须自己存：
 *  - 系统日志（logcat）手机上根本看不到，App 一闪退，用户只看到「它关了」，
 *    什么错、错在哪行，一概不知，开发者也只能靠猜
 *  - 没有 logcat 就没有任何复现线索，只能反复试
 *
 * 所以这里把未捕获异常连同完整堆栈写进 files/agent/crash.log，
 * 设置页里能看、能复制、能发出去。
 *
 * 另外还有一类「不崩但出事」的异常：被各处 catch (e: Exception) 静默吞掉的。
 * 那些走 report() 记到这里，同样能查。
 */
object CrashLog {

    private const val MAX_BYTES = 300_000L
    private const val KEEP = 50

    private fun file(ctx: Context) = File(ctx.filesDir, "agent/crash.log")

    private fun fmt(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ts))

    /**
     * 记一条崩溃。**绝对不能自己抛异常**——
     * 这个方法常常就是在异常处理链里被调用的，它自己再炸就真炸了。
     */
    @Synchronized
    fun record(ctx: Context, tag: String, t: Throwable) {
        try {
            val f = file(ctx)
            f.parentFile?.mkdirs()

            val sw = StringWriter()
            PrintWriter(sw).use { t.printStackTrace(it) }
            val stack = sw.toString()

            val sb = StringBuilder()
            sb.append("───── ").append(fmt(System.currentTimeMillis())).append(" ─────\n")
            sb.append("类型：").append(tag).append('\n')
            sb.append("版本：").append(appVersion(ctx)).append('\n')
            sb.append("设备：").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            sb.append("异常：").append(t.javaClass.name).append(": ").append(t.message ?: "").append('\n')
            sb.append("堆栈：\n").append(stack).append('\n')

            f.appendText(sb.toString())
            if (f.length() > MAX_BYTES) trim(f)
        } catch (e: Throwable) {
            // 连日志都写不了，那就只能算了，绝不能在这里再抛
        }
    }

    /** 记一条「没崩但出问题了」的异常 */
    fun report(ctx: Context, tag: String, t: Throwable) = record(ctx, tag, t)

    // ---------- 上次是不是崩着退的 ----------

    /**
     * 记一个标记：这次启动之前，App 崩过。
     * 下次启动时读出来提醒用户，否则崩溃就是彻底无声的。
     */
    fun markPendingRecover(ctx: Context) {
        try {
            ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
                .edit().putLong("pending", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
        }
    }

    /** 上次崩溃的时间戳；没有则 null */
    fun pendingRecover(ctx: Context): Long? {
        return try {
            val v = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
                .getLong("pending", 0L)
            if (v > 0L) v else null
        } catch (e: Exception) {
            null
        }
    }

    fun clearPendingRecover(ctx: Context) {
        try {
            ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
                .edit().putLong("pending", 0L).apply()
        } catch (e: Exception) {
        }
    }

    /** 最近 n 条崩溃，纯文本，给设置页直接显示 */
    fun tail(ctx: Context, limit: Int = 10): String {
        val f = file(ctx)
        if (!f.exists() || f.length() == 0L) return "还没有崩溃记录"

        return try {
            val text = f.readText()
            val blocks = text.split("─────").filter { it.isNotBlank() }
            if (blocks.isEmpty()) "还没有崩溃记录"
            else {
                val sb = StringBuilder()
                blocks.takeLast(limit).forEachIndexed { i, b ->
                    if (i > 0) sb.append("\n\n")
                    sb.append("─────").append(b.trim()).append("\n")
                }
                sb.toString()
            }
        } catch (e: Exception) {
            "读取崩溃日志失败：${e.message}"
        }
    }

    /** 有没有崩溃记录 */
    fun count(ctx: Context): Int {
        val f = file(ctx)
        if (!f.exists()) return 0
        return try {
            f.readText().split("─────").count { it.isNotBlank() }
        } catch (e: Exception) {
            0
        }
    }

    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (e: Exception) {
        }
    }

    /** 把日志文件复制一份到 saved 目录，用户好发出来 */
    fun export(ctx: Context): String? {
        return try {
            val src = file(ctx)
            if (!src.exists()) return null
            val dir = File(ctx.filesDir, "agent/saved")
            dir.mkdirs()
            val out = File(dir, "crash-${System.currentTimeMillis()}.log")
            src.copyTo(out, overwrite = true)
            out.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun trim(f: File) {
        try {
            val kept = f.readText().split("─────").filter { it.isNotBlank() }.takeLast(KEEP)
            f.writeText(kept.joinToString("\n─────") { it.trim() } + "\n")
        } catch (e: Exception) {
        }
    }

    private fun appVersion(ctx: Context): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = try {
            @Suppress("DEPRECATION")
            pi.versionCode
        } catch (e: Exception) {
            0
        }
        "${pi.versionName} ($code)"
    } catch (e: Exception) {
        "未知"
    }
}
