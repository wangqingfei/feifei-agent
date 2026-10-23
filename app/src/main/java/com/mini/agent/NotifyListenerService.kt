package com.mini.agent

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知监听：系统每来一条通知就回调一次，我们把标题和正文抄进本地文件。
 *
 * 这是「特殊访问」，不是运行时权限：
 * 用户得去 系统设置 → 通知使用权 里手动把「飞飞」打开，才会调到这个服务。
 * Manifest 里的 BIND_NOTIFICATION_LISTENER_SERVICE 只是声明资格，不是授权。
 *
 * 只做「记录」，不做「拦截、回复、清除」——那些要额外 API 且风险高，先不碰。
 */
class NotifyListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // App 内的总开关：关了就不采集。注意这只是「不写盘」，
        // 系统仍然会绑定这个服务（进程保活），要彻底不耗只能去系统里关通知使用权。
        if (!AgentFactory.prefs(this).getBoolean("notifEnabled", true)) return

        sbn ?: return
        val n = sbn.notification ?: return
        val ex = n.extras

        val app = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        // 只读 EXTRA_TITLE / EXTRA_TEXT 会漏掉一大半通知：
        // 长文本在 EXTRA_BIG_TEXT，多行在 EXTRA_TEXT_LINES，
        // IM 类通知（MessagingStyle）正文在 messages 里，副标题/摘要是另外两个字段。
        // 之前这些字段一律没读，导致「内容读不到」——看着像被过滤掉了，其实是压根没取。
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val conv = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val summary = ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim().orEmpty()
        val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString("；")
            .orEmpty()

        // IM 通知里的每条消息：正文可能只在这里
        var lastMsg = ""
        var msgCount = 0
        try {
            val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (msgs != null) {
                for (m in msgs) {
                    val b = m as? Bundle ?: continue
                    val t = b.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                    if (t.isNotEmpty()) {
                        msgCount++
                        lastMsg = t
                    }
                }
            }
        } catch (e: Exception) {
        }

        // 正文兜底顺序：大文本 > 正文 > 多行 > 消息体 > 副标题 > 摘要
        val body = listOf(big, text, lines, lastMsg, sub, summary)
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

        // 标题兜底：标题 > 会话标题 > 应用名。
        // 之前标题空 + 正文空就整条丢掉——那正是「读不到通知」的元凶。
        val finalTitle = listOf(title, conv).firstOrNull { it.isNotEmpty() }.orEmpty()
        if (finalTitle.isEmpty() && body.isEmpty()) return

        // 多条消息时补一句条数，IM 通知经常只给最后一条
        val fullBody = if (msgCount > 1) "$body（该通知共 $msgCount 条消息）" else body

        NotificationStore.append(applicationContext, app, finalTitle.ifEmpty { app }, fullBody)
    }
}
