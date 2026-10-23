package com.mini.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * 到点叫醒 App。
 *
 * 为什么不用 WorkManager 的周期任务：PeriodicWorkRequest 的 setInitialDelay 只对第一次生效，
 * 之后是「上次跑完 + 24 小时」，加上 Doze 推迟，几天就能漂到九点多。
 * 这里用 AlarmManager 每天/每周重新算绝对时间点，跑完再排下一次，漂移不累积。
 */
object TaskScheduler {

    const val ACTION_ALARM = "com.mini.agent.TASK_ALARM"
    const val EXTRA_TASK_ID = "taskId"

    fun scheduleAll(ctx: Context) {
        TaskStore.load(ctx).forEach { t ->
            if (t.enabled) schedule(ctx, t) else cancel(ctx, t)
        }
    }

    fun schedule(ctx: Context, task: AgentTask) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = nextTrigger(task)
        if (at <= 0) return   // 过期的一次性任务，不用排了
        val pi = pending(ctx, task.id)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 没拿到「闹钟和提醒」权限，只能退到不精确但能穿透 Doze 的模式
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e2: Exception) {
            }
        } catch (e: Exception) {
        }
    }

    fun cancel(ctx: Context, task: AgentTask) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            am.cancel(pending(ctx, task.id))
        } catch (e: Exception) {
        }
    }

    /** 下一次该响的毫秒时间戳。一次性任务过期返回 -1（不排） */
    fun nextTrigger(task: AgentTask, from: Long = System.currentTimeMillis()): Long {
        if (task.once) {
            val at = if (task.runAt > 0) task.runAt else nextTimeOfDay(task.time, from)
            return if (at > from) at else -1L
        }

        val (h, m) = task.timeParts()
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= from) add(Calendar.DAY_OF_YEAR, 1)
        }
        if (task.days.isEmpty()) return cal.timeInMillis

        repeat(8) {
            if (isoDayOfWeek(cal) in task.days) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** 只按 "HH:mm" 算：今天还没到就是今天，过了就是明天 */
    fun nextTimeOfDay(time: String, from: Long = System.currentTimeMillis()): Long {
        val p = time.split(":")
        val h = (p.getOrNull(0)?.toIntOrNull() ?: 7).coerceIn(0, 23)
        val m = (p.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59)
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= from) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** Calendar 里周日是 1，这里统一成 1=周一 … 7=周日 */
    private fun isoDayOfWeek(cal: Calendar): Int {
        val d = cal.get(Calendar.DAY_OF_WEEK)
        return if (d == Calendar.SUNDAY) 7 else d - 1
    }

    private fun pending(ctx: Context, taskId: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            putExtra(EXTRA_TASK_ID, taskId)
        }
        // requestCode 必须跟任务一一对应，否则两条任务会互相覆盖
        return PendingIntent.getBroadcast(
            ctx,
            taskId.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
