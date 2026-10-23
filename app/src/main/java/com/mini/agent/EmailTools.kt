package com.mini.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 读邮件：列列表、搜索、读正文。三个动作合一个工具，避免工具列表膨胀。 */
class EmailTool(private val ctx: Context) : Tool {

    override val name = "email"
    override val description = """读用户邮箱（IMAP）。
list 列最近邮件（可只看未读）；search 按关键词搜主题和发件人；read 读正文（需 uid，从结果里抄）。
发信用 email_send。
正文会传给模型服务方，涉密内容先问用户要不要读。"""

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
                        put("enum", JSONArray().put("list").put("search").put("read"))
                        put("description", "list / search / read")
                    })
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "search 的关键词，搜主题和发件人")
                    })
                    put("uid", JSONObject().apply {
                        put("type", "integer")
                        put("description", "read 的 uid，从结果里抄")
                    })
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "条数，默认 8，最多 20")
                    })
                    put("unread_only", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "只看未读")
                    })
                })
                put("required", JSONArray().put("action"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase()
        val limit = args.optInt("limit", 8).coerceIn(1, 20)

        return try {
            when (action) {
                "list" -> EmailClient.list(ctx, limit, args.optBoolean("unread_only", false))
                "search" -> {
                    val q = args.optString("query").trim()
                    if (q.isEmpty()) "错误：搜索需要 query" else EmailClient.search(ctx, q, limit)
                }
                "read" -> {
                    val uid = args.optLong("uid", -1L)
                    if (uid <= 0) "错误：读取需要 uid，先用 list 或 search 拿到 [uid=xxx]"
                    else EmailClient.read(ctx, uid)
                }
                else -> "错误：action 只能是 list / search / read"
            }
        } catch (e: Exception) {
            "邮箱操作失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }
}

/** 发邮件。默认关闭，必须在设置里显式开启。 */
class EmailSendTool(private val ctx: Context) : Tool {

    override val name = "email_send"
    override val description = """发邮件（SMTP），发出不可撤回。
发给用户自己：直接发。发给别人：先复述「收件人 + 主题」，等同意再发。
地址不确定就问，不要猜，不要拿名字拼。
设置里没开发信权限会被拒绝。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("to", JSONObject().apply {
                        put("type", "string")
                        put("description", "收件人地址，多个用逗号分隔")
                    })
                    put("subject", JSONObject().apply {
                        put("type", "string")
                        put("description", "主题，60 字内")
                    })
                    put("body", JSONObject().apply {
                        put("type", "string")
                        put("description", "正文纯文本")
                    })
                })
                put("required", JSONArray().put("to").put("subject").put("body"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val to = args.optString("to").trim()
        val subject = args.optString("subject").trim()
        val body = args.optString("body")
        if (to.isEmpty() || subject.isEmpty()) return "错误：收件人和主题不能为空"
        if (body.isBlank()) return "错误：正文不能为空"

        return try {
            EmailClient.send(ctx, to, subject, body)
        } catch (e: Exception) {
            "发信失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }
}
