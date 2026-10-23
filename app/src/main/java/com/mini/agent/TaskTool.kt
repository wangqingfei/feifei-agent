package com.mini.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 让模型自己管定时任务。
 * 用户在对话里说「每天早上七点半给我说天气」「把简报改到八点」，走的就是这里。
 */
class TaskTool(private val ctx: Context) : Tool {

    override val name = "task"

    override val description = """定时任务：到点自动跑一段指令，结果发通知。
两种任务：
1. 重复任务——给 time（HH:mm）+ days（1-7，不传=每天）
2. 一次性——给 delayMinutes（几分钟后），跑完自动删除
用户说「几分钟后」「半小时后」「两小时后」用 delayMinutes，最不容易算错；
说「每天七点」「每周一三五」用 time + days。
action：list 看全部；add 新增；update 改；remove 删；enable/disable 开关；run 立刻跑一次。
改时间用 update，别删了重建。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("description", "动作，取值见 enum")
                        put("enum", JSONArray(listOf("list", "add", "update", "remove", "enable", "disable", "run")))
                    })
                    put("id", JSONObject().apply {
                        put("type", "string")
                        put("description", "任务 id，list 里能看到；update/remove/enable/disable/run 必填")
                    })
                    put("name", JSONObject().apply {
                        put("type", "string")
                        put("description", "任务名，例 今日简报、喝水提醒")
                    })
                    put("time", JSONObject().apply {
                        put("type", "string")
                        put("description", "重复任务的触发时间，24 小时制 HH:mm，例 07:00")
                    })
                    put("delayMinutes", JSONObject().apply {
                        put("type", "integer")
                        put("description", "一次性任务：多少分钟后触发。传了这个就忽略 time/days，跑完自动删")
                    })
                    put("days", JSONObject().apply {
                        put("type", "array")
                        put("description", "星期几触发，1=周一 … 7=周日；不传=每天")
                        put("items", JSONObject().apply { put("type", "integer") })
                    })
                    put("prompt", JSONObject().apply {
                        put("type", "string")
                        put("description", "到点要执行的那句话，写给助手自己看的命令式指令")
                    })
                })
                put("required", JSONArray(listOf("action")))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        return try {
            when (args.optString("action").trim().lowercase()) {
                "list" -> list()
                "add" -> add(args)
                "update" -> update(args)
                "remove" -> remove(args)
                "enable" -> toggle(args, true)
                "disable" -> toggle(args, false)
                "run" -> runNow(args)
                else -> "action 只能是 list/add/update/remove/enable/disable/run。"
            }
        } catch (e: Exception) {
            "任务操作失败：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun list(): String {
        val all = TaskStore.load(ctx)
        if (all.isEmpty()) return "现在没有任何定时任务。要加就用 task add。"

        val sb = StringBuilder("共 ${all.size} 个任务：\n")
        all.forEachIndexed { i, t ->
            sb.append(i + 1).append(". ").append(t.name)
            sb.append("（id ").append(t.id).append("）")
            sb.append(if (t.once) " " + t.daysText() else " " + t.time + " " + t.daysText())
            sb.append(if (t.enabled) " · 开启" else " · 已关闭")
            sb.append("\n   指令：").append(t.prompt.take(60))
            if (t.prompt.length > 60) sb.append("…")
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    private fun add(args: JSONObject): String {
        val prompt = args.optString("prompt").trim()
        if (prompt.isEmpty()) return "要加任务得给 prompt：说到点具体做什么。"

        // 一次性：几分钟后
        val delay = args.optInt("delayMinutes", 0)
        if (delay > 0) {
            val runAt = System.currentTimeMillis() + delay * 60_000L
            val t = TaskStore.addOnce(ctx, args.optString("name"), runAt, prompt)
            TaskScheduler.schedule(ctx, t)
            return "已加一次性提醒：${t.name}，${delay} 分钟后（${t.daysText()}，id ${t.id}），跑完自动删除。"
        }

        val time = AgentTask.normalizeTime(args.optString("time", "07:00"))
        val days = daysOf(args)
        val t = TaskStore.add(ctx, args.optString("name"), time, days, prompt, true)
        TaskScheduler.schedule(ctx, t)

        return "已加：${t.name}，${t.time} ${t.daysText()}（id ${t.id}）。到点会发通知。"
    }

    private fun update(args: JSONObject): String {
        val id = args.optString("id").trim()
        val old = TaskStore.find(ctx, id) ?: return "没找到 id 为 $id 的任务，先 task list 看看。"

        var t = old.copy(
            name = args.optString("name").trim().ifEmpty { old.name },
            time = if (args.has("time")) AgentTask.normalizeTime(args.optString("time")) else old.time,
            days = if (args.has("days")) daysOf(args) else old.days,
            prompt = args.optString("prompt").trim().ifEmpty { old.prompt }
        )

        // 一次性任务改时间必须重算绝对时间点，否则改了等于没改
        if (t.once) {
            t = when {
                args.has("delayMinutes") -> t.copy(
                    runAt = System.currentTimeMillis() + args.optInt("delayMinutes") * 60_000L
                )
                args.has("time") -> t.copy(runAt = TaskScheduler.nextTimeOfDay(t.time))
                t.runAt <= System.currentTimeMillis() ->
                    return "这条一次性任务已经过期了。想再提醒就 add 一条新的。"
                else -> t
            }
        }

        TaskStore.update(ctx, t)
        TaskScheduler.cancel(ctx, old)
        if (t.enabled) TaskScheduler.schedule(ctx, t)

        val whenText = if (t.once) t.daysText() else t.time + " " + t.daysText()
        return "已改：${t.name} → $whenText。"
    }

    private fun remove(args: JSONObject): String {
        val id = args.optString("id").trim()
        val t = TaskStore.find(ctx, id) ?: return "没找到 id 为 $id 的任务。"
        TaskScheduler.cancel(ctx, t)
        TaskStore.remove(ctx, id)
        return "已删除「${t.name}」。"
    }

    private fun toggle(args: JSONObject, on: Boolean): String {
        val id = args.optString("id").trim()
        val t = TaskStore.setEnabled(ctx, id, on) ?: return "没找到 id 为 $id 的任务。"
        if (on) TaskScheduler.schedule(ctx, t) else TaskScheduler.cancel(ctx, t)
        return if (on) "已开启「${t.name}」，下次 ${t.time} ${t.daysText()}。"
        else "已关闭「${t.name}」，不会再自动跑。"
    }

    private fun runNow(args: JSONObject): String {
        val id = args.optString("id").trim()
        val t = TaskStore.find(ctx, id) ?: return "没找到 id 为 $id 的任务。"
        if (!AgentFactory.hasKey(ctx)) return "还没填 API Key，跑不了。"
        return try {
            AgentFactory.loop(ctx, onStep = {}, maxTurns = AgentConfig.params(ctx).backgroundTurns)
                .run(t.prompt)
        } catch (e: Exception) {
            "跑「${t.name}」失败了：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun daysOf(args: JSONObject): List<Int> {
        val arr = args.optJSONArray("days") ?: return emptyList()
        val out = mutableListOf<Int>()
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, -1)
            if (v in 1..7 && v !in out) out.add(v)
        }
        return out.sorted()
    }
}
