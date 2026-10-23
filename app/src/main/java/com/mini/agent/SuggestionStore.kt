package com.mini.agent

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 首页那三个示例问题。
 *
 * 之前硬编码在 MainActivity 里 —— 用户问「这三句哪来的」，模型没有任何可读的实体，
 * 只能按训练数据编一段 Kotlin 出来。和当初「问接口配置它编配置类」是同一个病。
 *
 * 现在落到 files/agent/suggestions.json：
 * - 用户和模型都能读到
 * - 模型可以用 save 工具直接改
 * - 改完重开首页就生效
 */
object SuggestionStore {

    private const val FILE_NAME = "suggestions.json"

    val DEFAULTS = listOf(
        "今天有什么科技热点",
        "抓一下少数派最新 3 篇文章",
        "AI 圈今天发生了什么"
    )

    private const val MAX = 5

    fun file(ctx: Context) = File(ctx.filesDir, "agent/$FILE_NAME")

    fun load(ctx: Context): List<String> {
        val f = file(ctx)
        if (!f.exists()) {
            save(ctx, DEFAULTS)
            return DEFAULTS
        }
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) out.add(s)
            }
            out.take(MAX).ifEmpty { DEFAULTS }
        } catch (e: Exception) {
            DEFAULTS
        }
    }

    fun save(ctx: Context, list: List<String>) {
        val arr = JSONArray()
        list.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX).forEach { arr.put(it) }
        try {
            val f = file(ctx)
            f.parentFile?.mkdirs()
            f.writeText(arr.toString(2))
        } catch (e: Exception) {
        }
    }
}
