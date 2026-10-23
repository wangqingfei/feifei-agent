package com.mini.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 读最近捕获的通知，顺带识别验证码。
 *
 * 验证码是「通知读取」最实在的用途——银行、App 登录的验证码短信，飞飞现在能看见了。
 * 识别规则刻意收窄：只在正文含「验证码/动态码/口令」这类词时，才去抓 4~8 位连续数字。
 * 不会见到数字就当成验证码报出去，那会闹笑话。
 */
class NotificationsTool(private val ctx: Context) : Tool {

    override val name = "notifications"
    override val sensitive = true
    override val description = """读最近捕获到的系统通知（谁给你发了什么），并识别里面的验证码。
什么时候用：用户问「刚才谁找我」「有什么新通知」「验证码是多少」「微信/银行有没有找我」。
什么时候别用：这是通知的摘录，不是短信全文——要读完整短信用 sms 的 list/search；
没开通知监听就没有数据，照实说并让用户去 设置 → 隐私权限 开「通知读取」。
一次最多回 10 条。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "读几条，默认 5，最多 10")
                    })
                })
                put("required", JSONArray())
            })
        })
    }

    override fun execute(args: JSONObject): String {
        if (!NotificationStore.isEnabled(ctx)) {
            return "通知监听还没开。请让用户去 设置 → 隐私权限 里点「通知读取」，" +
                "会跳到系统「通知使用权」页面，把飞飞打开就行。开完再来查。"
        }

        val limit = args.optInt("limit", 5).coerceIn(1, 10)
        val items = NotificationStore.read(ctx, limit)
        if (items.isEmpty()) {
            return "监听了，但还没有捕获到任何通知。等有消息进来之后再来查。"
        }

        val sb = StringBuilder("最近 ${items.size} 条通知：\n")
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        for (o in items) {
            val t = fmt.format(java.util.Date(o.optLong("t")))
            val app = o.optString("app", "未知应用")
            val title = o.optString("title")
            val text = o.optString("text")

            sb.append("· ").append(t).append("  ").append(app).append("\n")
            val body = (if (title.isNotEmpty() && title != text) "$title：$text" else text)
                .replace("\n", " ")
            sb.append("  ").append(if (body.length > 70) body.take(70) + "…" else body)

            val code = extractCode(title, text)
            if (code != null) sb.append("  【验证码 $code】")
            sb.append("\n")
        }
        // 提醒模型：通知是摘录，用户要全文时该走 sms
        sb.append("（通知是系统推送的摘录，可能不全；要短信全文用 sms 的 list 或 search。）")
        return sb.toString().trimEnd()
    }

    private fun extractCode(title: String, text: String): String? {
        val s = "$title $text"
        val keywords = listOf("验证码", "动态码", "口令", "校验码", "激活码")
        if (keywords.none { s.contains(it) }) return null
        return Regex("\\d{4,8}").find(s)?.value
    }
}
