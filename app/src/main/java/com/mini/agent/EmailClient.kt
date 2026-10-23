package com.mini.agent

import android.content.Context
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.BodyPart
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.SubjectTerm

/**
 * 邮箱：IMAP 收、SMTP 发。走标准协议，不依赖任何 CLI 或脚本。
 *
 * 每次操作新建连接、用完关闭——慢 1~3 秒，但不会因为连接过期而莫名其妙失败。
 */
object EmailClient {

    data class Cfg(
        val user: String,
        val pass: String,
        val imapHost: String,
        val smtpHost: String
    )

    // ---------- 配置 ----------

    fun config(ctx: Context): Cfg? {
        val p = AgentFactory.prefs(ctx)
        val user = p.getString("mailUser", "").orEmpty().trim()
        val pass = p.getString("mailPass", "").orEmpty().trim()
        if (user.isEmpty() || pass.isEmpty()) return null

        val auto = guessHosts(user)
        val imap = p.getString("mailImap", "").orEmpty().trim().ifEmpty { auto.first }
        val smtp = p.getString("mailSmtp", "").orEmpty().trim().ifEmpty { auto.second }
        if (imap.isEmpty() || smtp.isEmpty()) return null

        return Cfg(user, pass, imap, smtp)
    }

    /** 配置哪里缺了，说清楚，别让模型瞎猜 */
    fun diagnose(ctx: Context): String {
        val p = AgentFactory.prefs(ctx)
        val user = p.getString("mailUser", "").orEmpty().trim()
        val pass = p.getString("mailPass", "").orEmpty().trim()

        if (user.isEmpty()) return "邮箱还没填。请让用户去 设置 → 邮箱 填写邮箱地址（例如 yourname@example.com）。"
        if (!user.contains("@")) return "邮箱地址不完整：「$user」缺少 @域名。139 邮箱应填 手机号@139.com。"
        if (pass.isEmpty()) return "授权码还没填。请让用户去 设置 → 邮箱 填写授权码（不是登录密码）。"

        val auto = guessHosts(user)
        val imap = p.getString("mailImap", "").orEmpty().trim().ifEmpty { auto.first }
        if (imap.isEmpty()) {
            return "无法根据「$user」自动判断邮件服务器，请让用户在设置 → 邮箱里手动填写 IMAP 和 SMTP 服务器地址。"
        }
        return "配置看起来是全的，可能是认证失败。请让用户确认授权码有效、且邮箱后台已开启 IMAP 服务。"
    }

    fun guessHosts(addr: String): Pair<String, String> {
        val domain = addr.substringAfter('@', "").lowercase()
        return when (domain) {
            "139.com", "188.com" -> "imap.139.com" to "smtp.139.com"
            "qq.com", "foxmail.com" -> "imap.qq.com" to "smtp.qq.com"
            "163.com" -> "imap.163.com" to "smtp.163.com"
            "126.com" -> "imap.126.com" to "smtp.126.com"
            "yeah.net" -> "imap.yeah.net" to "smtp.yeah.net"
            "gmail.com" -> "imap.gmail.com" to "smtp.gmail.com"
            "outlook.com", "hotmail.com", "live.com" -> "outlook.office365.com" to "smtp.office365.com"
            "sina.com" -> "imap.sina.com" to "smtp.sina.com"
            "aliyun.com" -> "imap.aliyun.com" to "smtp.aliyun.com"
            else -> "" to ""
        }
    }

    fun canSend(ctx: Context): Boolean =
        AgentFactory.prefs(ctx).getBoolean("mailAllowSend", false)

    // ---------- 收 ----------

    private fun imapProps(host: String): Properties = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", host)
        put("mail.imaps.port", "993")
        put("mail.imaps.ssl.enable", "true")
        put("mail.imaps.connectiontimeout", "15000")
        put("mail.imaps.timeout", "25000")
        put("mail.imaps.writetimeout", "15000")
    }

    private fun openStore(cfg: Cfg): IMAPStore {
        val session = Session.getInstance(imapProps(cfg.imapHost))
        val store = session.getStore("imaps") as IMAPStore
        store.connect(cfg.imapHost, 993, cfg.user, cfg.pass)

        // 网易系要求先发 ID，否则报 Unsafe Login
        try {
            store.id(mapOf("name" to "feifly", "version" to "0.11", "vendor" to "mini-agent"))
        } catch (e: Exception) {
            // 其他服务商不支持就算了
        }
        return store
    }

    fun list(ctx: Context, limit: Int, unreadOnly: Boolean): String {
        val cfg = config(ctx) ?: return diagnose(ctx)
        val store = openStore(cfg)
        return try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            try {
                val total = inbox.messageCount
                if (total == 0) return "收件箱是空的"

                val from = maxOf(1, total - limit * 4)
                val msgs = inbox.getMessages(from, total)

                val sb = StringBuilder()
                var n = 0
                for (i in msgs.indices.reversed()) {
                    if (n >= limit) break
                    val m = msgs[i]
                    val unread = !m.flags.contains(Flags.Flag.SEEN)
                    if (unreadOnly && !unread) continue

                    n++
                    val uid = (inbox as IMAPFolder).getUID(m)
                    sb.append("[uid=").append(uid).append("] ")
                    if (unread) sb.append("[未读] ")
                    sb.append(clean(m.subject)).append(" — ")
                    sb.append(fromOf(m)).append(" — ")
                    sb.append(fmtDate(m.sentDate)).append("\n")
                }
                if (sb.isEmpty()) "没有符合条件的邮件" else sb.toString().trim()
            } finally {
                runCatching { inbox.close(false) }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    fun search(ctx: Context, query: String, limit: Int): String {
        val cfg = config(ctx) ?: return diagnose(ctx)
        val store = openStore(cfg)
        return try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            try {
                val term = OrTerm(
                    SubjectTerm(query),
                    FromStringTerm(query)
                )
                val found = inbox.search(term)
                if (found.isEmpty()) return "没搜到包含「$query」的邮件"

                val sb = StringBuilder()
                for (m in found.takeLast(limit).reversed()) {
                    val uid = (inbox as IMAPFolder).getUID(m)
                    val unread = !m.flags.contains(Flags.Flag.SEEN)
                    sb.append("[uid=").append(uid).append("] ")
                    if (unread) sb.append("[未读] ")
                    sb.append(clean(m.subject)).append(" — ")
                    sb.append(fromOf(m)).append(" — ")
                    sb.append(fmtDate(m.sentDate)).append("\n")
                }
                sb.toString().trim()
            } finally {
                runCatching { inbox.close(false) }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    fun read(ctx: Context, uid: Long): String {
        val cfg = config(ctx) ?: return diagnose(ctx)
        val store = openStore(cfg)
        return try {
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_ONLY)
            try {
                val m = inbox.getMessageByUID(uid) ?: return "找不到 uid=$uid 的邮件"
                val sb = StringBuilder()
                sb.append("主题：").append(clean(m.subject)).append("\n")
                sb.append("发件人：").append(fromOf(m)).append("\n")
                sb.append("时间：").append(fmtDate(m.sentDate)).append("\n")
                sb.append("收件人：").append(addrOf(m, Message.RecipientType.TO)).append("\n\n")

                val text = bodyText(m).replace(Regex("[ \\t]+"), " ").trim()
                sb.append(if (text.length > 4000) text.take(4000) + "\n（正文已截断）" else text)
                sb.toString()
            } finally {
                runCatching { inbox.close(false) }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    // ---------- 发 ----------

    fun send(ctx: Context, to: String, subject: String, body: String): String {
        val cfg = config(ctx) ?: return diagnose(ctx)
        if (!canSend(ctx)) {
            return "发信权限未开启。请让用户在设置 → 邮箱里打开「允许发信」后再试。"
        }

        val props = Properties().apply {
            put("mail.smtp.host", cfg.smtpHost)
            put("mail.smtp.port", "465")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "25000")
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(cfg.user, cfg.pass)
        })

        val msg = MimeMessage(session)
        msg.setFrom(InternetAddress(cfg.user))
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        msg.subject = subject
        msg.setText(body, "UTF-8")

        Transport.send(msg)
        return "已发送给 $to，主题「$subject」"
    }

    // ---------- 解析 ----------

    private fun bodyText(part: Part): String {
        return try {
            when {
                part.isMimeType("text/plain") -> part.content?.toString().orEmpty()
                part.isMimeType("text/html") ->
                    Jsoup.parse(part.content?.toString().orEmpty()).text()
                part.isMimeType("multipart/*") -> {
                    val mp = part.content as Multipart
                    val sb = StringBuilder()
                    for (i in 0 until mp.count) {
                        val bp: BodyPart = mp.getBodyPart(i)
                        if (bp.disposition == Part.ATTACHMENT) continue
                        sb.append(bodyText(bp)).append("\n")
                    }
                    sb.toString()
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun fromOf(m: Message): String {
        return try {
            val a = m.from?.firstOrNull() ?: return "(未知)"
            val name = (a as? InternetAddress)?.personal
            if (name.isNullOrBlank()) a.toString() else "$name <${a}>"
        } catch (e: Exception) {
            "(未知)"
        }
    }

    private fun addrOf(m: Message, type: Message.RecipientType): String {
        return try {
            m.getRecipients(type)?.joinToString(", ") { it.toString() } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun clean(s: String?): String =
        (s ?: "(无主题)").replace(Regex("\\s+"), " ").trim().ifEmpty { "(无主题)" }

    private fun fmtDate(d: Date?): String {
        if (d == null) return ""
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(d)
    }

    const val NOT_CONFIGURED =
        "邮箱还没配置。请让用户在设置 → 邮箱里填写邮箱地址和授权码。"
}
