package com.mini.agent

import org.json.JSONObject

/**
 * 全部核心逻辑，就是这一个循环。
 * 没有框架、没有反射、没有注解——出问题你能从第一行读到第十五行。
 */
class AgentLoop(
    private val llm: LlmClient,
    private val registry: ToolRegistry,
    private val store: Store,
    private val onStep: (String) -> Unit,
    private val onDelta: (String) -> Unit = {},
    private val onThinking: () -> Unit = {},
    /** 工具参数生成进度（已累积的字符数） */
    private val onToolProgress: (Int) -> Unit = {},
    /** 内部循环的过程记录：思考、工具调用、返回结果，都往这里送 */
    private val onTrace: (TraceEvent) -> Unit = {},
    private val location: String = "",
    private val userEmail: String = "",
    /** 外置的系统提示词，为空则用内置默认 */
    private val prompt: String = "",
    /** 这一轮锁定在哪个会话；中途切换会话也不会写错地方 */
    private val sessionId: String = "",
    /** 对话场景少轮=快；后台任务多轮=能深挖 */
    private val maxTurns: Int = MAX_TURNS,
    /** 一轮可能并行多个工具，所以还要单独卡总次数 */
    private val maxToolCalls: Int = MAX_TOOL_CALLS,
    /** 用户点了停止。每轮开头、工具等待、流式读取三处都查这个 */
    private val isCancelled: () -> Boolean = { false }
) {

    private val sid: String get() = sessionId.ifEmpty { store.currentId() }

    /**
     * 当前这一轮的过程记录。
     * 收集下来，最后跟回答一起写进会话文件——
     * 这样切走再切回来，还能看到它当时到底干了什么。
     */
    private val turnTrace = ArrayList<TraceEvent>()

    /** 把一条过程记录同时送出去（给界面）和存下来（给以后回看） */
    private fun trace(e: TraceEvent) {
        // 思考是增量来的：每次都带完整内容，所以只保留最新一条，
        // 不然一条思考会变成几十条几乎一样的记录。
        // 注意只在「紧邻的那条也是思考」时才合并——
        // 否则第 2 轮的思考会把第 1 轮的覆盖掉，界面上就只剩最后一轮。
        if (e.kind == TraceEvent.THINK) {
            val last = turnTrace.lastOrNull()
            if (last != null && last.kind == TraceEvent.THINK) {
                turnTrace[turnTrace.size - 1] = e
            } else {
                turnTrace.add(e)
            }
        } else {
            turnTrace.add(e)
        }
        onTrace(e)
    }

    fun run(userInput: String): String {
        turnTrace.clear()
        try {
            return runInner(userInput)
        } catch (e: CancelledException) {
            // 停在工具阶段时，这一轮还没有任何回答落盘。
            // 补一条，否则重开会话只看到自己问的那句话、没有回应，像丢了。
            if (!e.persisted) persistAnswer("（已停止）")
            throw e
        }
    }

    private fun runInner(userInput: String): String {
        val summary = maybeCompress()
        recentCalls.clear()

        // 先把用户这句话写进会话文件。中途新建对话/切走也不会丢，标题也立刻有了
        store.appendMessageOf(sid, ChatMessage("user", userInput))

        val messages = mutableListOf<ChatMessage>()

        // 1. 系统提示词 + 长期记忆 + 旧对话摘要
        messages += ChatMessage("system", buildSystemPrompt(summary))

        // 2. 最近几轮对话（短期记忆）。这里已经包含刚写进去的这句用户消息
        messages += store.loadHistoryOf(sid).takeLast(MAX_HISTORY)

        var turn = 0
        var called = 0
        while (turn < maxTurns) {
            turn++
            if (isCancelled()) throw CancelledException()

            // 最后一轮收回工具：逼它用已有信息作答，而不是继续无限搜下去
            val isLastTurn = turn == maxTurns
            val tools = if (isLastTurn) null else registry.schemas()

            trace(TraceEvent(
                TraceEvent.NOTE,
                "第 $turn 轮 · 请求模型",
                if (isLastTurn) "本轮不再提供工具，让它基于已有信息作答" else "可用工具 ${tools?.length() ?: 0} 个"
            ))

            // 每一轮都是一次独立的模型调用，思考内容也要各自从头算
            val reasoningBuf = StringBuilder()

            val reply = llm.chatStream(
                messages, tools, onDelta, onThinking, onToolProgress, isCancelled
            ) { piece ->
                // 推理内容：拼起来存一份，同时实时送到界面上
                reasoningBuf.append(piece)
                trace(TraceEvent(TraceEvent.THINK, "思考中", reasoningBuf.toString()))
            }

            // 流到一半被停：半截正文已经给用户看过了，写进会话再抛，免得重开对话就没了
            if (reply.cancelled) {
                val partial = reply.content?.trim().orEmpty()
                persistAnswer(if (partial.isEmpty()) "（已停止）" else "$partial\n\n（已停止）")
                throw CancelledException(partial, persisted = true)
            }
            messages += reply

            // 没有工具调用 = 它说完了
            if (reply.toolCalls.isEmpty()) {
                var answer = reply.content?.trim().orEmpty()

                // 流式偶尔只吐推理、正文为空。非流式重来一次，且不给工具，逼它基于已有信息作答
                if (answer.isEmpty()) {
                    onStep("重新组织回答")
                    answer = try {
                        llm.chat(messages.dropLast(1), null).content?.trim().orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                    if (answer.isNotEmpty()) onDelta(answer)
                }

                if (answer.isEmpty()) answer = "没拿到回答，换个说法再说一遍。"
                persistAnswer(answer)
                return answer
            }

            // 有工具调用 = 并行干活（一次发多个不用排队），然后按原顺序把结果塞回去
            val pending = mutableListOf<Pending>()
            for (call in reply.toolCalls) {
                val sig = signature(call)
                when {
                    // 同一个工具、同一份参数、连来三次，就不要再给它第四次机会
                    isDoomLoop(sig) -> {
                        remember(sig)
                        trace(TraceEvent(
                            TraceEvent.NOTE,
                            "跳过重复调用 ${call.name}",
                            "相同参数连着来三次了，拦下来"
                        ))
                        pending += Pending(call, note = doomNote(call.name))
                    }
                    called >= maxToolCalls -> {
                        trace(TraceEvent(
                            TraceEvent.NOTE,
                            "工具调用已达上限",
                            "本次最多 $maxToolCalls 次"
                        ))
                        pending += Pending(
                            call,
                            note = "已达到本次工具调用上限（$maxToolCalls 次），请基于已有信息回答。"
                        )
                    }
                    else -> {
                        called++
                        remember(sig)
                        onStep(describe(call))
                        // 记下调了什么、传了什么参数
                        trace(TraceEvent(
                            TraceEvent.TOOL,
                            "调用 ${call.name} · ${describe(call)}",
                            prettyArgs(call.arguments)
                        ))
                        pending += Pending(
                            call,
                            future = pool.submit<String> {
                                registry.run(call.name, call.arguments)
                            }
                        )
                    }
                }
            }

            try {
                for (p in pending) {
                    val result = when {
                        p.note != null -> p.note
                        else -> awaitTool(p.future!!)
                    }
                    // 记下工具返回了什么。这是排查问题最有用的一段
                    trace(TraceEvent(
                        TraceEvent.RESULT,
                        "${p.call.name} 返回",
                        result.take(600)
                    ))
                    messages += ChatMessage("tool", clip(result), toolCallId = p.call.id)
                }
            } catch (e: CancelledException) {
                // 其余还在跑的也一起掐掉，别让它们在后台白占网络
                pending.forEach { it.future?.cancel(true) }
                throw e
            }

            // 一轮可能并行灌进好几份长结果，这里是唯一能拦住"一轮之内爆上下文"的地方
            trimContext(messages)
        }

        persistAnswer(STOPPED)
        return STOPPED
    }

    /** 最近几次工具调用的指纹，用来识别「同一件事反复做」 */
    private val recentCalls = ArrayDeque<String>()

    /** 一个待回填的工具位：要么有 future 在跑，要么带一句 note 直接当结果 */
    private class Pending(
        val call: ToolCall,
        val future: java.util.concurrent.Future<String>? = null,
        val note: String? = null
    )

    /** 把工具调用翻译成人话，让状态行能看出它在忙什么 */
    private fun describe(call: ToolCall): String {
        return when (call.name) {
            "web_search" -> {
                val q = try {
                    JSONObject(call.arguments).optString("query").trim()
                } catch (e: Exception) {
                    ""
                }
                if (q.isEmpty()) "搜索网页" else "搜索「${q.take(20)}」"
            }
            "web_fetch" -> {
                val u = try {
                    JSONObject(call.arguments).optString("url").trim()
                } catch (e: Exception) {
                    ""
                }
                if (u.isEmpty()) "读取网页" else "读取 " + u.removePrefix("https://").removePrefix("http://").take(28)
            }
            "remember" -> "记下来"
            "notify" -> "发通知"
            "save" -> "存文件"
            "weather" -> "查天气"
            "email" -> "看邮箱"
            "email_send" -> "发邮件"
            "run_js" -> "算一下"
            "self" -> "自省"
            else -> "调用 ${call.name}"
        }
    }

    // ---------- 上下文预算与死循环 ----------

    /** 工具名 + 去掉空白的参数当指纹 */
    private fun signature(call: ToolCall): String =
        call.name + "|" + call.arguments.replace(Regex("\\s+"), "")

    /**
     * 把工具参数整理成人能看的样子。
     * 原始 JSON 一行挤在一起，看到的是 {"query":"xxx","limit":5}，
     * 整理成 key: value 一行一个，才像人话。
     */
    private fun prettyArgs(argsJson: String): String {
        return try {
            val o = JSONObject(argsJson)
            val sb = StringBuilder()
            o.keys().forEach { k ->
                val v = o.opt(k)
                val s = if (v == null || v == JSONObject.NULL) "" else v.toString()
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(k).append(": ").append(s.take(200))
            }
            if (sb.isEmpty()) argsJson.take(300) else sb.toString()
        } catch (e: Exception) {
            argsJson.take(300)
        }
    }

    private fun isDoomLoop(sig: String): Boolean =
        recentCalls.size >= DOOM_THRESHOLD &&
            recentCalls.takeLast(DOOM_THRESHOLD).all { it == sig }

    private fun remember(sig: String) {
        recentCalls.addLast(sig)
        while (recentCalls.size > DOOM_THRESHOLD) recentCalls.removeFirst()
    }

    private fun doomNote(name: String): String =
        "你已经用完全相同的参数连续调用 $name 三次，每次结果都一样。" +
            "别再试第四次：换参数、换工具，或者直接告诉用户这条路走不通、卡在哪一步。"

    /**
     * 单条工具结果上限。web_fetch 自己会截到 6000 字，这里再收一道——
     * 四个网页并发回来就是 2.4 万字，光这一轮就够把上下文撑死。
     */
    private fun clip(s: String): String {
        if (s.length <= TOOL_RESULT_MAX) return s
        val head = TOOL_RESULT_MAX * 2 / 3
        return s.take(head) +
            "\n…（中间 ${s.length - TOOL_RESULT_MAX} 字已省略）…\n" +
            s.takeLast(TOOL_RESULT_MAX - head)
    }

    /**
     * 总量兜底。run 开头的 maybeCompress 只管"上一次对话留下的历史"，
     * 管不了"这一轮里工具刚灌进来的新内容"——那是真正会突然溢出的地方。
     * 按字数估算（中文 1 字≈1 token），超了就压最早的工具结果，最近两条不动。
     */
    private fun trimContext(messages: MutableList<ChatMessage>) {
        var total = messages.sumOf { (it.content ?: "").length }
        if (total <= CONTEXT_BUDGET) return

        val toolIdx = messages.indices.filter { messages[it].role == "tool" }
        for (i in toolIdx.dropLast(2)) {
            if (total <= CONTEXT_BUDGET) return
            val c = messages[i].content ?: continue
            if (c.length <= 500) continue
            val shrunk = c.take(260) +
                "\n…（中间 ${c.length - 420} 字已省略，避免上下文溢出）…\n" +
                c.takeLast(160)
            total -= (c.length - shrunk.length)
            messages[i] = messages[i].copy(content = shrunk)
        }
    }

    /**
     * 等工具跑完，但每 200ms 看一眼有没有人点停止。
     * 直接 get(90s) 的话，点了停止还得干等一分多钟——那正是这个按钮要解决的问题。
     */
    private fun awaitTool(future: java.util.concurrent.Future<String>): String {
        val deadline = System.currentTimeMillis() + TOOL_TIMEOUT_MS
        while (true) {
            if (isCancelled()) {
                future.cancel(true)
                throw CancelledException()
            }
            try {
                return future.get(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                if (System.currentTimeMillis() > deadline) {
                    future.cancel(true)
                    return "工具执行超时（${TOOL_TIMEOUT_MS / 1000} 秒），可能网络卡住或服务没响应。"
                }
            } catch (e: Exception) {
                return "工具执行失败：${e.javaClass.simpleName}"
            }
        }
    }

    /** 用户那句已经在 run 开头写过了，这里只补回答 */
    private fun persistAnswer(assistant: String) {
        // 过程记录一起存：切走再切回来，还看得到它当时干了什么
        store.appendMessageOf(
            sid,
            ChatMessage("assistant", assistant, trace = turnTrace.toList())
        )
    }

    /** 历史太长时，把旧对话压成摘要，只留最近几条原文 */
    private fun maybeCompress(): String {
        val history = store.loadHistoryOf(sid)
        val existing = store.readSummaryOf(sid)

        // 条数够多、或者字数够多，任一满足就压。
        // 只看条数会漏掉"三五条但每条上千字"的情况，那种一样会把上下文撑爆。
        val chars = history.sumOf { (it.content ?: "").length }
        if (history.size <= KEEP_RECENT + 12 && chars < COMPRESS_CHARS) return existing

        val old = history.dropLast(KEEP_RECENT)
        val sb = StringBuilder()
        if (existing.isNotEmpty()) {
            sb.append("已有摘要：").append(existing).append("\n\n")
        }
        sb.append("需要并入摘要的对话：\n")
        old.takeLast(30).forEach {
            sb.append(if (it.role == "user") "用户：" else "助手：")
            sb.append((it.content ?: "").take(150)).append("\n")
        }
        sb.append("\n把上面内容合并成一段不超过 300 字的摘要，")
        sb.append("只保留事实、结论、用户偏好和未完成的事，不要客套话，不要分点。")

        return try {
            val sum = llm.chat(listOf(ChatMessage("user", sb.toString())), null)
                .content?.trim().orEmpty()
            if (sum.isNotEmpty()) {
                store.writeSummaryOf(sid, sum)
                store.saveHistoryOf(sid, history.takeLast(KEEP_RECENT))
                sum
            } else {
                existing
            }
        } catch (e: Exception) {
            // 压缩失败不影响这轮对话，继续用旧摘要
            existing
        }
    }

    private fun buildSystemPrompt(summary: String): String {
        val mem = store.readMemory()
        val sb = StringBuilder()

        // 人格与规则来自 system.md，用户和模型都能改
        sb.append(if (prompt.isNotBlank()) prompt else PromptStore.DEFAULT)
        sb.append("\n")

        if (mem.isNotEmpty()) {
            sb.append("\n关于用户的已知事实（直接用，不要反问）：\n")
            sb.append(mem)
            sb.append("\n")
        }

        if (summary.isNotEmpty()) {
            sb.append("\n更早对话的摘要（背景信息，不必复述）：\n")
            sb.append(summary)
            sb.append("\n")
        }

        // 模型不知道今天是几号，会把新信息当假消息，也会把旧政策当现政
        val now = java.time.LocalDateTime.now()
        val week = now.dayOfWeek.getDisplayName(
            java.time.format.TextStyle.FULL, java.util.Locale.CHINA
        )
        sb.append("\n现在是 ").append(now.toLocalDate()).append("（").append(week).append("）")
        sb.append(String.format("%02d:%02d", now.hour, now.minute)).append("。")
        sb.append("判断信息新旧、政策是否现行、几点该做什么时以此为准。")
        sb.append("你的训练数据早于这个日期，所以工具查到的结果通常比你的记忆新。\n")

        if (location.isNotEmpty()) {
            sb.append("\n用户当前所在：").append(location)
            sb.append("。问天气、找本地信息时直接用这个地点，不要反问用户在哪。\n")
        }

        if (userEmail.isNotEmpty()) {
            sb.append("\n用户的邮箱是 ").append(userEmail)
            sb.append("。用户说「给我自己发」「发到我邮箱」时直接用这个地址。\n")
            sb.append("发给别人时，用户没给地址就先问清楚，不要猜。\n")
        }

        // 把预算告诉它，否则它会按「任务需要几步」规划，然后被硬掐断
        sb.append("\n本轮预算：大约能调 ").append(maxTurns - 1).append("~").append(maxTurns)
        sb.append(" 次工具（总上限 ").append(maxToolCalls).append(" 次）。")
        sb.append("先做最关键的那一步，别把轮次耗在次要细节上；")
        sb.append("信息够了就停下来回答，不要为了完整再多查一轮。\n")

        return sb.toString()
    }

    companion object {
        const val MAX_TURNS = 4
        const val MAX_TOOL_CALLS = 10
        const val MAX_HISTORY = 6

        /** 压缩后保留的原文条数 */
        private const val KEEP_RECENT = 8

        /** 超过这么多字才触发压缩（约等于 6000 token 的中文） */
        private const val COMPRESS_CHARS = 6000

        /** 工具并发池：一次发多个工具时并行跑，最多同时 3 个 */
        private val pool = java.util.concurrent.Executors.newFixedThreadPool(3)

        /** 同一个工具、同一份参数，连来这么多次就认定在原地打转 */
        private const val DOOM_THRESHOLD = 3

        /** 单条工具结果回填时的字数上限 */
        private const val TOOL_RESULT_MAX = 3000

        /** 一次请求里所有消息的字数预算，超了压旧工具结果 */
        private const val CONTEXT_BUDGET = 20000

        /** 单个工具的硬超时；停止按钮的轮询粒度是 200ms */
        private const val TOOL_TIMEOUT_MS = 90_000L

        private const val STOPPED = "这次没能在限定轮数内完成，我停下来了。把问题说小一点再试一次。"
    }
}
