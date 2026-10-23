package com.mini.agent

import android.content.Context
import java.io.File

/**
 * 一处创建，主界面和定时任务共用。
 * 保证「手动问」和「自动跑」用的是同一套配置、同一套工具。
 */
object AgentFactory {

    const val DEFAULT_BASE = "https://zhenze-huhehaote.cmecloud.cn/v1"
    const val DEFAULT_MODEL = "Qwen3.5-27B"

    const val KEY_BASE = "baseUrl"
    const val KEY_MODEL = "model"
    const val KEY_API = "apiKey"
    const val KEY_DAILY_TASK = "dailyTask"
    const val KEY_DAILY_ON = "dailyOn"

    fun prefs(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun store(ctx: Context) = Store(ctx)

    fun registry(ctx: Context): ToolRegistry {
        val s = store(ctx)
        return ToolRegistry(
            ctx,
            listOf(
                WebSearchTool(),
                WebFetchTool(),
                WeatherTool(ctx),
                EmailTool(ctx),
                EmailSendTool(ctx),
                RememberTool(s.memory),
                NotifyTool(ctx),
                SaveTool(ctx.filesDir),
                ShareTool(ctx),
                WebAppTool(ctx),
                TaskTool(ctx),
                NetdiskTool(ctx),
                ContactTool(ctx),
                PhoneTool(ctx),
                SmsTool(ctx),
                LocationTool(ctx),
                MapTool(ctx),
                NotificationsTool(ctx),
                SelfTool(ctx),
                JsTool(),
                FileWriteTool(ctx),
                DownloadTool(ctx),
                DownloadStatusTool(ctx),
                ImageTool(ctx)
            ) + CustomTools.load(ctx)
        )
    }

    fun loop(
        ctx: Context,
        onStep: (String) -> Unit,
        onDelta: (String) -> Unit = {},
        onThinking: () -> Unit = {},
        onToolProgress: (Int) -> Unit = {},
        onTrace: (TraceEvent) -> Unit = {},
        maxTurns: Int = 0,
        isCancelled: () -> Boolean = { false }
    ): AgentLoop {
        val p = prefs(ctx)
        val st = store(ctx)

        // 接口配置来自 config.json，不在 SharedPreferences 里写死了
        val api = AgentConfig.active(ctx)
        val llm = LlmClient(
            api.baseUrl.ifEmpty { DEFAULT_BASE },
            api.apiKey,
            api.model.ifEmpty { DEFAULT_MODEL },
            api.temperature,
            api.maxTokens
        )
        val params = AgentConfig.params(ctx)

        return AgentLoop(
            llm = llm,
            registry = registry(ctx),
            store = st,
            onStep = onStep,
            onDelta = onDelta,
            onThinking = onThinking,
            onToolProgress = onToolProgress,
            onTrace = onTrace,
            location = LocationHelper.current(ctx),
            userEmail = p.getString("mailUser", "").orEmpty().trim(),
            prompt = PromptStore.load(ctx),
            sessionId = st.currentId(),
            maxTurns = if (maxTurns > 0) maxTurns else params.maxTurns,
            maxToolCalls = params.maxToolCalls,
            isCancelled = isCancelled
        )
    }

    fun hasKey(ctx: Context): Boolean = AgentConfig.active(ctx).apiKey.isNotEmpty()

    /**
     * 一次性迁移：v0.7 之前默认模型是 deepseek-v4.1-flash，
     * 它在工具调用后经常不吐正文。若用户没主动改过，就换成行为更稳的 Qwen3.5-27B。
     */
    fun migrate(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("v7_model_migrated", false)) return
        if (p.getString(KEY_MODEL, "") == "deepseek-v4.1-flash") {
            p.edit().putString(KEY_MODEL, DEFAULT_MODEL).apply()
        }
        p.edit().putBoolean("v7_model_migrated", true).apply()
    }
}
