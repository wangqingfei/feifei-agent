package com.mini.agent

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通讯录：查人、改号码、新增。
 *
 * 三条自我约束：
 * 1. 一次最多回 10 条——几百个联系人全吐出来会直接撞上上下文预算
 * 2. 改号码必须能唯一确定是哪个联系人，有歧义就返回候选让模型问清楚，不猜
 * 3. 结果标记 sensitive，日志里不落号码
 */
class ContactTool(private val ctx: Context) : Tool {

    override val name = "contact"
    override val sensitive = true
    override val description = """通讯录：按姓名或号码查人、改某个人的号码、新增联系人。
什么时候用：用户说「查一下XX的电话」「把XX的号码改成…」「存一下XX的号码」。
什么时候别用：只是要拨号用 phone；要发短信给某人用 sms（它自己会查号码）。
search 传 name 或 number，模糊匹配；update 必须给 name 和 newPhone，
同一个名字有多个联系人时不会改，会返回候选让你问用户；add 给 name 和 phone。
没有权限时会明确说缺哪个，照实转告用户去开，不要重试。"""

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
                        put("enum", JSONArray().put("search").put("update").put("add"))
                        put("description", "动作，取值见 enum")
                    })
                    put("name", JSONObject().apply {
                        put("type", "string")
                        put("description", "联系人姓名，search 时可模糊")
                    })
                    put("number", JSONObject().apply {
                        put("type", "string")
                        put("description", "search 时按号码查；add 时的新号码")
                    })
                    put("newPhone", JSONObject().apply {
                        put("type", "string")
                        put("description", "update 时改成的新号码")
                    })
                    put("oldPhone", JSONObject().apply {
                        put("type", "string")
                        put("description", "update 时指定改哪一个（一个人有多个号码时必须给）")
                    })
                })
                put("required", JSONArray().put("action"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        if (!AppPermissions.has(ctx, AppPermissions.CONTACTS_READ)) {
            return AppPermissions.need(AppPermissions.CONTACTS_READ)
        }
        return try {
            when (args.optString("action").trim().lowercase()) {
                "search" -> search(args.optString("name").trim(), args.optString("number").trim())
                "update" -> update(
                    args.optString("name").trim(),
                    args.optString("newPhone").trim(),
                    args.optString("oldPhone").trim()
                )
                "add" -> add(args.optString("name").trim(), args.optString("number").trim())
                else -> "错误：action 只能是 search / update / add"
            }
        } catch (e: SecurityException) {
            AppPermissions.need(AppPermissions.CONTACTS_READ)
        } catch (e: Exception) {
            "通讯录操作失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    // ---------- 查 ----------

    private data class Row(val contactId: String, val name: String, val phone: String, val dataId: String)

    private fun query(name: String, number: String): List<Row> {
        val sel = StringBuilder()
        val params = mutableListOf<String>()
        if (name.isNotEmpty()) {
            sel.append("${Phone.DISPLAY_NAME} LIKE ?")
            params += "%$name%"
        }
        if (number.isNotEmpty()) {
            if (sel.isNotEmpty()) sel.append(" OR ")
            sel.append("${Phone.NUMBER} LIKE ?")
            params += "%${number.replace(" ", "")}%"
        }

        val out = mutableListOf<Row>()
        ctx.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone._ID),
            if (sel.isEmpty()) null else sel.toString(),
            if (params.isEmpty()) null else params.toTypedArray(),
            "${Phone.DISPLAY_NAME} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                out += Row(
                    c.getString(0) ?: "",
                    c.getString(1) ?: "（无名）",
                    c.getString(2) ?: "",
                    c.getString(3) ?: ""
                )
                if (out.size >= 200) break   // 兜底，别把整个通讯录读进内存
            }
        }
        return out
    }

    private fun search(name: String, number: String): String {
        if (name.isEmpty() && number.isEmpty()) return "错误：search 要给 name 或 number。"
        val rows = query(name, number)
        if (rows.isEmpty()) return "通讯录里没找到${if (name.isNotEmpty()) "「$name」" else "「$number」"}。"

        // 同名多号码聚合成一个块，模型才看得懂「这个人有两个号」
        val grouped = rows.groupBy { it.contactId }
        val sb = StringBuilder("找到 ${grouped.size} 个联系人：\n")
        grouped.entries.take(10).forEach { (_, list) ->
            val r = list.first()
            sb.append("· ").append(r.name).append("：")
            sb.append(list.map { it.phone }.distinct().joinToString(" / "))
            sb.append("\n")
        }
        if (grouped.size > 10) sb.append("（只列了前 10 个，说具体名字再查）\n")
        return sb.toString().trimEnd()
    }

    // ---------- 改 ----------

    private fun update(name: String, newPhone: String, oldPhone: String): String {
        if (name.isEmpty()) return "错误：update 需要 name。"
        if (newPhone.isEmpty()) return "错误：update 需要 newPhone。"
        if (!AppPermissions.has(ctx, AppPermissions.CONTACTS_WRITE)) {
            return AppPermissions.need(AppPermissions.CONTACTS_WRITE)
        }

        val rows = query(name, "")
        if (rows.isEmpty()) return "通讯录里没有「$name」，先用 add 新增，或者确认下名字。"

        val byContact = rows.groupBy { it.contactId }
        if (byContact.size > 1) {
            val names = byContact.values.map { it.first().name + "（" + it.map { p -> p.phone }.joinToString("/") + "）" }
            return "有 ${byContact.size} 个叫「$name」的联系人，不知道改哪个：\n" +
                names.joinToString("\n") { "· $it" } +
                "\n请让用户说清楚是哪一个（给出号码更好）。这次没有改动任何数据。"
        }

        val list = byContact.values.first()
        val target = when {
            oldPhone.isNotEmpty() -> list.firstOrNull { it.phone.replace(" ", "").contains(oldPhone.replace(" ", "")) }
            list.size == 1 -> list.first()
            else -> null
        }

        if (target == null) {
            return "「$name」名下有好几个号码：" +
                list.map { it.phone }.joinToString(" / ") +
                "\n要改哪一个？让用户指定 oldPhone，或者直接说清楚是哪个号。这次没有改动。"
        }

        val values = ContentValues().apply { put(Phone.NUMBER, newPhone) }
        val n = ctx.contentResolver.update(
            ContactsContract.Data.CONTENT_URI,
            values,
            "${ContactsContract.Data._ID} = ?",
            arrayOf(target.dataId)
        )
        return if (n > 0) {
            "已把「${target.name}」的号码从 ${target.phone} 改成 $newPhone。"
        } else {
            "没有改动成功，可能这条记录被系统保护了（比如某个账号同步的联系人）。"
        }
    }

    // ---------- 新增 ----------

    private fun add(name: String, phone: String): String {
        if (name.isEmpty() || phone.isEmpty()) return "错误：add 需要 name 和 number。"
        if (!AppPermissions.has(ctx, AppPermissions.CONTACTS_WRITE)) {
            return AppPermissions.need(AppPermissions.CONTACTS_WRITE)
        }

        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, name)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, phone)
                .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                .build()
        )
        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        return "已新增联系人：$name · $phone"
    }
}
