package com.mini.agent

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * 一条定时任务的执行体。
 * 被 AlarmReceiver 叫起来 → 跑这一条 → 发通知 → 把下一次排上。
 */
class DailyWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getString(KEY_TASK_ID).orEmpty()

        TaskStore.migrateLegacy(ctx)
        val task = TaskStore.find(ctx, id) ?: return Result.success()
        if (!task.enabled || task.prompt.isBlank()) return Result.success()

        // 先排下一次：万一下面跑崩了或超时，明天的闹钟也还在。
        // 一次性任务不排；重复任务重新读一遍文件——排队期间可能被改过时间，不能拿旧值覆盖
        if (!task.once) {
            TaskStore.find(ctx, id)?.takeIf { it.enabled }?.let { TaskScheduler.schedule(ctx, it) }
        }

        if (!AgentFactory.hasKey(ctx)) return Result.success()

        return try {
            val answer = AgentFactory.loop(
                ctx,
                onStep = {},
                maxTurns = AgentConfig.params(ctx).backgroundTurns
            ).run(task.prompt)

            val sent = NotificationHelper.send(ctx, "飞飞 · " + task.name, answer)
            RunLog.append(
                ctx, "task", task.name,
                answer.take(120) + if (sent) "" else "（通知没发出去：无通知权限）",
                0L, sent
            )
            if (task.once) TaskStore.remove(ctx, task.id)
            Result.success()
        } catch (e: Exception) {
            // 定时任务跑挂了，用户只会看到一条「没跑成」。
            // 记下堆栈，下次才能查出到底哪一步出的问题
            CrashLog.report(ctx, "定时任务「${task.name}」执行异常", e)
            NotificationHelper.send(ctx, "「" + task.name + "」没跑成", e.message ?: e.javaClass.simpleName)
            if (task.once) TaskStore.remove(ctx, task.id)
            Result.success()   // 不重试，避免反复烧额度
        }
    }

    companion object {
        const val KEY_TASK_ID = "taskId"

        const val DEFAULT_TASK =
            "抓取 https://sspai.com/feed 和 https://www.ithome.com/rss/ 两个源，" +
                "从中挑出 3 条最值得知道的，每条用一句话说清是什么、为什么重要。" +
                "不要开场白，不要总结，直接列三条。"

        /** 闹钟响了之后排队进 WorkManager */
        fun enqueue(ctx: Context, taskId: String) {
            // 故意不加「必须有网」约束：到点就得跑。
            // 没网会快速失败并发一条「没跑成」，也好过静默等到有网——
            // 那时候「5 分钟后提醒我」早就没意义了。
            val req = OneTimeWorkRequestBuilder<DailyWorker>()
                .setInputData(workDataOf(KEY_TASK_ID to taskId))
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}
