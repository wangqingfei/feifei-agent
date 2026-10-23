package com.mini.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import org.json.JSONArray
import org.json.JSONObject

/**
 * 拨号。两种模式，默认是最保守的那种：
 *
 * - 默认：ACTION_DIAL，只把号码填进拨号盘，**用户按一下才真正拨出**。不需要任何权限。
 * - 设置里打开「允许直接拨出」且有 CALL_PHONE 权限：ACTION_CALL，直接呼出。
 *
 * 为什么默认不直接拨：一个会自己打电话的助手，误拨一次的代价比省下的一次点击大得多。
 */
class PhoneTool(private val ctx: Context) : Tool {

    override val name = "phone"
    override val sensitive = true
    override val description = """打电话：给某个人或某个号码拨号。
什么时候用：用户说「给XX打个电话」「拨 138xxxx」。
什么时候别用：发短信走 sms；查号码走 contact。
参数给 name（会去通讯录找，必须有读取通讯录权限）或 number。
默认只是打开拨号盘、把号码填好，需要用户按下拨出键——你回复时要说清这一点，
不要说「已经帮你拨通了」。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("name", JSONObject().apply {
                        put("type", "string")
                        put("description", "联系人姓名，去通讯录匹配")
                    })
                    put("number", JSONObject().apply {
                        put("type", "string")
                        put("description", "直接给号码，给了就不查通讯录")
                    })
                })
                put("required", JSONArray())
            })
        })
    }

    override fun execute(args: JSONObject): String {
        var number = args.optString("number").trim()
        val name = args.optString("name").trim()
        var who = number

        if (number.isEmpty() && name.isNotEmpty()) {
            val found = lookup(name)
            when {
                found.isEmpty() -> return "通讯录里没找到「$name」，也没给号码。请让用户说出号码，或者先确认名字。"
                found.size > 1 -> return "有 ${found.size} 个「$name」：\n" +
                    found.joinToString("\n") { "· ${it.second}" } +
                    "\n要打哪个？让用户指定号码。"
                else -> {
                    number = found[0].second
                    who = found[0].first
                }
            }
        }
        if (number.isEmpty()) return "错误：要给 name 或 number。"

        val clean = number.replace(" ", "").replace("-", "")
        val direct = AgentFactory.prefs(ctx).getBoolean("allowDirectCall", false) &&
            AppPermissions.has(ctx, AppPermissions.CALL)

        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL)
        intent.data = Uri.parse("tel:$clean")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            ctx.startActivity(intent)
            if (direct) "已直接拨出：$who（$clean）。通话由系统接管。"
            else "已打开拨号盘并填好 $who（$clean），用户按一下拨出键才会真的打出去。"
        } catch (e: Exception) {
            // Android 10+ 后台启动界面会被拦，定时任务里常见
            "没法打开拨号界面（${e.javaClass.simpleName}）。" +
                "如果是定时任务触发的，系统不允许后台直接弹拨号界面——改成发通知提醒用户来点。"
        }
    }

    /** 返回 (姓名, 号码) 列表，最多 10 条 */
    private fun lookup(name: String): List<Pair<String, String>> {
        if (!AppPermissions.has(ctx, AppPermissions.CONTACTS_READ)) {
            return emptyList()
        }
        val out = mutableListOf<Pair<String, String>>()
        try {
            ctx.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
                "${Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                "${Phone.DISPLAY_NAME} ASC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 10) {
                    val n = c.getString(0) ?: continue
                    val p = c.getString(1) ?: continue
                    if (!out.any { it.second == p }) out += n to p
                }
            }
        } catch (e: Exception) {
        }
        return out
    }
}
