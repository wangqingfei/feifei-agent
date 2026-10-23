package com.mini.agent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * 通讯录 / 拨号 / 短信这类权限的统一入口。
 *
 * 和 NotifyPermission 同一个套路：Manifest 声明了不等于有，必须运行时问。
 * 但这几个更麻烦——用户拒绝过一次之后系统不再弹窗，只能把人送到设置页。
 */
object AppPermissions {

    const val REQ = 102

    const val CONTACTS_READ = Manifest.permission.READ_CONTACTS
    const val CONTACTS_WRITE = Manifest.permission.WRITE_CONTACTS
    const val CALL = Manifest.permission.CALL_PHONE
    const val SMS_READ = Manifest.permission.READ_SMS
    const val SMS_SEND = Manifest.permission.SEND_SMS
    const val LOCATION = Manifest.permission.ACCESS_FINE_LOCATION

    /** 设置页按这个顺序列出来 */
    val ALL = listOf(CONTACTS_READ, CONTACTS_WRITE, CALL, SMS_READ, SMS_SEND, LOCATION)

    fun label(p: String): String = when (p) {
        CONTACTS_READ -> "读取通讯录"
        CONTACTS_WRITE -> "修改通讯录"
        CALL -> "直接拨打电话"
        SMS_READ -> "读取短信"
        SMS_SEND -> "发送短信"
        LOCATION -> "定位（精确）"
        else -> p
    }

    fun has(ctx: Context, p: String): Boolean =
        ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun request(activity: Activity, p: String) {
        if (has(activity, p)) return
        try {
            // 定位要连粗精度一起申请，否则部分机型只给一个、工具里还是查不到
            val perms = if (p == LOCATION) {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            } else {
                arrayOf(p)
            }
            activity.requestPermissions(perms, REQ)
        } catch (e: Exception) {
        }
    }

    /** 跳应用详情页。各家 ROM 的权限页路径不一样，统一入口反而最稳 */
    fun openSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
        }
    }

    /** 工具里用：没权限时给模型一句能转告用户的话 */
    fun need(p: String): String =
        "没有「${label(p)}」权限。请让用户去 设置 → 隐私权限 里开，开完再试。"
}
