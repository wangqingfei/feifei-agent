package com.mini.agent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 通知权限。
 *
 * Android 13 起是运行时权限：Manifest 里声明了不等于有。
 * 不给的后果特别隐蔽——任务到点照跑，NotificationHelper 静默吞掉 SecurityException，
 * 用户眼里就是「定时任务没生效」。
 *
 * 三个界面都要问，所以抽出来，避免各写一遍还写得不一样。
 */
object NotifyPermission {

    const val REQ = 101

    fun isGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 没权限就弹系统对话框。
     * 注意：用户拒绝过一次之后系统不会再弹，这时只能引导去设置页，见 openSettings。
     */
    fun request(activity: Activity) {
        if (isGranted(activity)) return
        try {
            activity.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ
            )
        } catch (e: Exception) {
        }
    }

    /** 跳到「设置 → 通知 → 飞飞」；跳不过去就退到应用详情页 */
    fun openSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        } catch (e: Exception) {
        }
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e2: Exception) {
        }
    }
}
