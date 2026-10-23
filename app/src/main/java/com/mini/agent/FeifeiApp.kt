package com.mini.agent

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * 全局异常处理器。
 *
 * 之前这个 App 崩溃时什么都留不下：
 * 系统把堆栈打进 logcat，手机上看不到，App 也没存，
 * 用户只看到「它自己关了」，开发者只能靠猜。
 *
 * 现在：任何没被接住的异常都会先写进 files/agent/crash.log，
 * 再交给系统默认处理器（否则进程不会退，用户看到的是卡死而不是崩溃）。
 */
class FeifeiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler(this)

        // 上次是崩着退的？这次启动时告诉用户一声，别让它闷声闪退
        CrashLog.pendingRecover(this)?.let {
            // 记录已读，免得每次启动都弹
            CrashLog.clearPendingRecover(this)
            // 延迟一点弹：Application.onCreate 阶段还没有 Activity，弹窗没地方挂
            android.os.Handler(mainLooper).postDelayed({
                try {
                    NotificationHelper.ensureChannel(this)
                    NotificationHelper.send(
                        this,
                        "上次意外退出了",
                        "飞飞上次崩溃了，已记下原因。打开设置页可以看详细堆栈。"
                    )
                } catch (e: Throwable) {
                    // 通知发不出去就算了，不能因此再崩
                }
            }, 3000)
        }
    }

    private fun installCrashHandler(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // 记下这次崩溃，包括是哪个线程
                CrashLog.record(ctx, "未捕获异常（线程：${thread.name}）", throwable)
                CrashLog.markPendingRecover(ctx)
                // 同时打一份到 logcat，有 adb 的人还能看到
                Log.e("Feifei", "未捕获异常：${throwable.javaClass.name}: ${throwable.message}", throwable)
            } catch (e: Throwable) {
                // 记日志本身失败就算了，绝不能在这里再抛一遍
            }

            // 交还给系统：它会打日志、结束进程、发「应用已停止」提示。
            // 不交还的话进程会带着坏状态继续跑，用户感觉是卡死而不是崩溃。
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }
}
