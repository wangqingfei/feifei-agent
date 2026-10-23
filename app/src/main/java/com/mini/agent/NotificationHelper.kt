package com.mini.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object NotificationHelper {

    private const val CHANNEL_ID = "agent_alert"
    private const val CHANNEL_NAME = "助手提醒"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "定时任务与提醒" }
                )
            }
        }
    }

    /**
     * 真的发出去了才返回 true。
     * 以前这里是空 catch，调用方一律回「已发送」——用户什么都没收到，还以为发了。
     */
    fun send(ctx: Context, title: String, body: String): Boolean {
        if (!NotifyPermission.isGranted(ctx)) return false

        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n: Notification = Notification.Builder(ctx, CHANNEL_ID)
            // 小图标必须是纯白剪影；用系统图标在各家 ROM 上会显示成一个白方块
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(if (body.length > 60) body.take(60) + "…" else body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        return try {
            // 固定 id：同名任务的新通知覆盖旧的，不会一天堆一条
            nm.notify(notifyId(title), n)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun notifyId(title: String): Int = (title.hashCode() and 0x7fffffff) % 100000
}
