package com.mini.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 闹钟响了就交给 Worker 去跑，不在这里干长活（超过 10 秒会被系统杀）。
 * 顺带负责开机 / 升级后把闹钟重新排上——AlarmManager 的闹钟不跨重启。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            TaskScheduler.ACTION_ALARM -> {
                val id = intent.getStringExtra(TaskScheduler.EXTRA_TASK_ID).orEmpty()
                if (id.isNotEmpty()) DailyWorker.enqueue(ctx, id)
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                TaskStore.migrateLegacy(ctx)
                TaskScheduler.scheduleAll(ctx)
            }
        }
    }
}
