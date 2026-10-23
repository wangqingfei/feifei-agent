package com.mini.agent

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.io.File

/**
 * 捕获到的通知，存成环形 jsonl。
 *
 * 为什么存下来：通知监听服务收到一条就闪过去了，模型下一轮问「刚才谁找我」时得有个地方读。
 * 为什么限制条数：验证码、消息内容都是敏感信息，留最近 100 条够用了，再多就是囤隐私。
 */
object NotificationStore {

    private const val NAME = "notifications.jsonl"
    private const val MAX = 100

    fun file(ctx: Context) = File(ctx.filesDir, "agent/$NAME")

    fun append(ctx: Context, app: String, title: String, text: String) {
        if (title.isBlank() && text.isBlank()) return
        val o = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("app", app)
            .put("title", title)
            .put("text", text)
        try {
            val f = file(ctx)
            f.parentFile?.mkdirs()
            f.appendText(o.toString() + "\n")
            trim(ctx)
        } catch (e: Exception) {
        }
    }

    /** 读最近 limit 条，新的在前 */
    fun read(ctx: Context, limit: Int): List<JSONObject> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines()
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .takeLast(limit)
                .reversed()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun trim(ctx: Context) {
        try {
            val f = file(ctx)
            val lines = f.readLines()
            if (lines.size <= MAX) return
            f.writeText(lines.takeLast(MAX).joinToString("\n", postfix = if (lines.size > MAX) "\n" else ""))
        } catch (e: Exception) {
        }
    }

    /** 通知监听有没有被用户授权。这是特殊访问，不在普通权限弹窗里 */
    fun isEnabled(ctx: Context): Boolean {
        return try {
            val flat = Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            flat.contains(ctx.packageName)
        } catch (e: Exception) {
            false
        }
    }
}
