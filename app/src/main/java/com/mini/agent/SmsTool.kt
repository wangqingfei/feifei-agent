package com.mini.agent

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 短信：读收件箱、发短信。
 *
 * 两条必须守住的线：
 * 1. 发送默认关闭，要用户在设置里显式打开——发短信是要花钱的，而且发错人是收不回来的
 * 2. 返回值不说「已送达」。sendTextMessage 是丢给系统就返回，送达与否我们不知道，
 *    说自己"已发送成功"就是在骗用户
 */
class SmsTool(private val ctx: Context) : Tool {

    /** 自增主键，作为「最新」的兜底排序键——date=0 的短信只能靠它定位 */
    private val SMS_ID = "_id"

    override val name = "sms"
    override val sensitive = true
    override val description = """短信：读短信、给某人发短信。
什么时候用：用户说「谁给我发短信了」「看看最新短信」「我发过什么」「有没有关于X的短信」「给XX发条短信说…」。
什么时候别用：发邮件用 email_send；打电话用 phone；看应用推送（微信、App 提醒）用 notifications。
list 读某个信箱最近几条：box=inbox 收件箱（默认）/ sent 已发送 / draft 草稿 / outbox 待发 / all 全部。
用户问「所有短信」「我发过的」时用 box=all 或 sent，别只给收件箱。
用户问「最新短信」「刚收到的验证码」时，用 box=all 并把 limit 调大（10 以上），这样能捞到全部信箱里刚进来的短信。
search 按关键词搜正文和号码，跨全部信箱；用户报验证码时 keyword 就填「验证码」。
发送开关默认是关的，没开就如实告诉用户去哪开，不要反复重试。
发完只说「已交给系统发送」，不要说「已送达」——我们确认不了。"""

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
                        put("enum", JSONArray().put("list").put("search").put("send"))
                        put("description", "动作，取值见 enum")
                    })
                    put("box", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("inbox").put("sent").put("draft").put("outbox").put("all"))
                        put("description", "list/search 用：读哪个信箱。inbox 收到的（默认）、sent 我发的、draft 草稿、outbox 待发、all 全部")
                    })
                    put("keyword", JSONObject().apply {
                        put("type", "string")
                        put("description", "search 用：正文或号码里包含的词，例「验证码」")
                    })
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "list 时读几条，默认 5，最多 20")
                    })
                    put("name", JSONObject().apply {
                        put("type", "string")
                        put("description", "send 时的联系人姓名")
                    })
                    put("number", JSONObject().apply {
                        put("type", "string")
                        put("description", "send 时的号码，给了就不查通讯录")
                    })
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", "send 时的短信正文")
                    })
                })
                put("required", JSONArray().put("action"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        return try {
            when (args.optString("action").trim().lowercase()) {
                "list" -> list(
                    args.optInt("limit", 5).coerceIn(1, 20),
                    args.optString("box").trim().lowercase().ifEmpty { "inbox" },
                    ""
                )
                "search" -> {
                    val kw = args.optString("keyword").trim()
                    if (kw.isEmpty()) "错误：search 需要 keyword（要搜的词）。"
                    else search(kw, args.optString("box").trim().lowercase().ifEmpty { "all" },
                        args.optInt("limit", 10).coerceIn(1, 20))
                }
                "send" -> send(
                    args.optString("name").trim(),
                    args.optString("number").trim(),
                    args.optString("text").trim()
                )
                else -> "错误：action 只能是 list / search / send"
            }
        } catch (e: SecurityException) {
            AppPermissions.need(AppPermissions.SMS_READ)
        } catch (e: Exception) {
            "短信操作失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    // ---------- 读 ----------

    /**
     * 之前只查收件箱，用户问「我发过的」「有没有某条短信」就答不上来。
     * 现在按 box 选信箱，all 走全部短信表，type 字段用来标出收/发。
     */
    private fun uriFor(box: String) = when (box) {
        "sent" -> Telephony.Sms.Sent.CONTENT_URI
        "draft" -> Telephony.Sms.Draft.CONTENT_URI
        "outbox" -> Telephony.Sms.Outbox.CONTENT_URI
        "all" -> Telephony.Sms.CONTENT_URI
        else -> Telephony.Sms.Inbox.CONTENT_URI
    }

    private fun boxLabel(box: String) = when (box) {
        "sent" -> "已发送"
        "draft" -> "草稿"
        "outbox" -> "待发送"
        "all" -> "全部信箱"
        else -> "收件箱"
    }

    /** type：0 收 / 1 发 / 2 草稿 / 3 待发 */
    private fun dirLabel(type: Int) = when (type) {
        1 -> "发"
        2 -> "草稿"
        3 -> "待发"
        else -> "收"
    }

    /**
     * 一条短信的原始记录。
     * id 用于去重和排序兜底：有些国产 ROM 写进来的短信 date 是 0，
     * 单靠 date DESC 排，这类短信永远沉在最底下——正好是用户最想要的验证码短信。
     */
    private data class SmsRow(
        val id: Long,
        val addr: String,
        val body: String,
        val date: Long,
        val type: Int
    ) {
        val timeKnown get() = date > 0
    }

    private fun queryRows(
        uri: android.net.Uri,
        sel: String?,
        selArgs: Array<String>?,
        order: String,
        limit: Int
    ): List<SmsRow> {
        val cols = arrayOf(
            SMS_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE
        )
        val out = ArrayList<SmsRow>()
        try {
            ctx.contentResolver.query(uri, cols, sel, selArgs, order)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out.add(
                        SmsRow(
                            id = c.getLong(0),
                            addr = c.getString(1) ?: "未知号码",
                            body = c.getString(2) ?: "",
                            date = c.getLong(3),
                            type = c.getInt(4)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            throw e      // 交给上层给出人话提示
        }
        return out
    }

    private fun list(limit: Int, box: String, keyword: String): String {
        if (!AppPermissions.has(ctx, AppPermissions.SMS_READ)) {
            return AppPermissions.need(AppPermissions.SMS_READ)
        }

        val uri = uriFor(box)
        val sel = if (keyword.isEmpty()) null else "(body LIKE ? OR address LIKE ?)"
        val selArgs = if (keyword.isEmpty()) null else arrayOf("%$keyword%", "%$keyword%")

        // 两条查询取并集：
        // A 按时间排（正常短信的主序），B 按插入序号排（捞回 date=0 的最新短信）
        val byDate: List<SmsRow>
        val byId: List<SmsRow>
        try {
            byDate = queryRows(uri, sel, selArgs, "${Telephony.Sms.DATE} DESC, ${SMS_ID} DESC", limit)
            byId = queryRows(uri, sel, selArgs, "${SMS_ID} DESC", limit)
        } catch (e: Exception) {
            return "读不到短信（${e.javaClass.simpleName}）。部分手机要把飞飞设成默认短信应用才给读。"
        }

        val merged = LinkedHashMap<Long, SmsRow>()
        for (r in byDate) merged[r.id] = r
        for (r in byId) merged[r.id] = r

        // 时间未知的排前面（它们是刚插进来的新短信），其余按时间倒序
        val rows = merged.values.sortedWith(
            compareByDescending<SmsRow> { !it.timeKnown }
                .thenByDescending { if (it.timeKnown) it.date else 0L }
                .thenByDescending { it.id }
        ).take(limit)

        if (rows.isEmpty()) {
            return if (keyword.isEmpty()) {
                "${boxLabel(box)}里没有短信，或者系统不允许读取（部分手机需要把 App 设成默认短信应用）。" +
                    "如果用户要找的是 App 推送的验证码，改用 notifications 看通知。"
            } else {
                "全部信箱里没找到含「$keyword」的短信。" +
                    "验证码类短信有时只在通知里能看到，改用 notifications 找一下。"
            }
        }

        val sb = StringBuilder("最近 $limit 条短信（${boxLabel(box)}）：\n")
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        var unknownTime = 0
        for (r in rows) {
            val t = if (r.timeKnown) fmt.format(java.util.Date(r.date)) else "时间未知"
            if (!r.timeKnown) unknownTime++
            sb.append("· ").append(t).append("  ").append(r.addr)
                .append("  [").append(dirLabel(r.type)).append("]\n")
            val body = r.body.replace("\n", " ")
            sb.append("  ").append(if (body.length > 80) body.take(80) + "…" else body).append("\n")
        }
        if (unknownTime > 0) {
            sb.append("（有 $unknownTime 条短信系统没记录时间，是刚收到的最新短信。）\n")
        }
        return sb.toString().trimEnd()
    }

    /** 按关键词搜正文和号码，默认跨全部信箱 */
    private fun search(keyword: String, box: String, limit: Int): String =
        list(limit, box, keyword)

    // ---------- 发 ----------

    private fun send(name: String, number: String, text: String): String {
        if (text.isEmpty()) return "错误：send 需要 text（短信内容）。"

        var num = number
        var who = number
        if (num.isEmpty()) {
            if (name.isEmpty()) return "错误：send 要给 name 或 number。"
            val found = lookup(name)
            when {
                found.isEmpty() -> return "通讯录里没找到「$name」。让用户直接给号码。"
                found.size > 1 -> return "有 ${found.size} 个「$name」：" +
                    found.joinToString("；") { it } + "。要发给哪个？"
                else -> {
                    num = found[0]
                    who = name
                }
            }
        }

        if (!AgentFactory.prefs(ctx).getBoolean("allowSendSms", false)) {
            return "发送短信的开关没开。请让用户去 设置 → 隐私权限 打开「允许直接发短信」，再试。"
        }
        if (!AppPermissions.has(ctx, AppPermissions.SMS_SEND)) {
            return AppPermissions.need(AppPermissions.SMS_SEND)
        }

        val clean = num.replace(" ", "").replace("-", "")
        val mgr = SmsManager.getDefault()
        val parts = mgr.divideMessage(text)

        return try {
            // 长短信拆成多条，系统会自己拼回去
            mgr.sendMultipartTextMessage(clean, null, parts, null, null)
            "已交给系统发送给 $who（$clean），共 ${parts.size} 条。我确认不了是否送达，也没法知道对方收没收到。"
        } catch (e: Exception) {
            "发送失败（${e.javaClass.simpleName}）：${e.message ?: ""}。" +
                "可能是没插卡、双卡没选对，或者系统拦截了。"
        }
    }

    private fun lookup(name: String): List<String> {
        if (!AppPermissions.has(ctx, AppPermissions.CONTACTS_READ)) return emptyList()
        val out = mutableListOf<String>()
        try {
            ctx.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.NUMBER),
                "${Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )?.use { c ->
                while (c.moveToNext() && out.size < 10) {
                    c.getString(0)?.let { if (!out.contains(it)) out += it }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }
}
