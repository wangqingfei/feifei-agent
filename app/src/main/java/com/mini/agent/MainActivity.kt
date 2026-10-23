package com.mini.agent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.method.LinkMovementMethod
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout

class MainActivity : Activity() {

    private lateinit var scroll: ScrollView
    private lateinit var msgList: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var store: Store
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerPanel: android.view.View
    private lateinit var sessionList: LinearLayout

    // ---------- 会话多选批量删除 ----------
    private var manageMode = false
    private val selectedSessions = LinkedHashSet<String>()
    private lateinit var sessionActionBar: android.view.View
    private lateinit var txtSelectedCount: TextView
    private lateinit var btnManageSessions: TextView

    private var busy = false
    /** 停止按钮：UI 线程置位，后台循环和流式读取都查它 */
    private val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 每次点「新会话」或「重新发送」都 +1，旧请求的结果一律作废 */
    private var generation = 0
    /** 哪些会话有请求正在后台跑。切回这些会话时要告诉用户"它还在写" */
    private val runningSessions = java.util.Collections.synchronizedSet(HashSet<String>())
    private var currentAssistant: TextView? = null
    /** 助手消息的卡片容器：有代码块时整块重建成「正文 + 代码卡片」 */
    private var currentAssistantBox: LinearLayout? = null
    /** 卡片里真正放正文/代码块的那一层。头部（标题+复制按钮）不能被重建掉 */
    private var currentAssistantContent: LinearLayout? = null
    /** 当前这条助手消息的原文缓冲。复制按钮按消息各自取，不会复制错 */
    private var currentAssistantSrc: StringBuilder? = null
    /** 这一轮的过程记录面板 */
    private var currentTrace: TraceView? = null
    private var welcome: View? = null
    /** 标题栏里的小飞：常驻，随对话状态变表情 */
    private var face: AgentFaceView? = null
    private var statusView: TextView? = null
    private var lastFlush = 0L
    private var lastScroll = 0L

    private val maxBubble by lazy { (resources.displayMetrics.widthPixels * 0.78f).toInt() }

    /** 语音输入：要麦克风权限，识别结果回填输入框 */
    private val REQ_VOICE = 2001
    private val REQ_VOICE_RESULT = 2002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scroll = findViewById(R.id.scroll)
        msgList = findViewById(R.id.msgList)
        input = findViewById(R.id.input)
        sendBtn = findViewById(R.id.btnSend)
        store = AgentFactory.store(this)
        AgentFactory.migrate(this)
        // 代码框的圆角/内边距按屏幕密度算，硬编码 dp 在 2x 屏上会显得很挤
        Markdown.density = resources.displayMetrics.density
        AgentConfig.load(this)   // 首次启动把旧配置迁成 config.json，之后以文件为准
        PromptStore.migrate(this)
        LocationHelper.refreshAsync(this)
        JsRunner.init(this)

        // 每次启动校准一次闹钟：迁移过来的任务能排上，被系统清掉的也能补回来
        TaskStore.migrateLegacy(this)
        TaskScheduler.scheduleAll(this)

        renderHistory()
        updateSendState()

        sendBtn.setOnClickListener { if (busy) stop() else send() }
        findViewById<ImageView>(R.id.btnVoice).setOnClickListener { startVoice() }
        drawer = findViewById(R.id.drawer)
        drawerPanel = findViewById(R.id.drawerPanel)
        sessionList = findViewById(R.id.sessionList)
        sessionActionBar = findViewById(R.id.sessionActionBar)
        txtSelectedCount = findViewById(R.id.txtSelectedCount)
        btnManageSessions = findViewById(R.id.btnManageSessions)

        btnManageSessions.setOnClickListener {
            if (manageMode) exitManageMode() else enterManageMode()
        }
        findViewById<TextView>(R.id.btnSelectAll).setOnClickListener {
            val all = store.listSessions().map { it.id }
            if (selectedSessions.size == all.size) selectedSessions.clear()
            else {
                selectedSessions.clear()
                selectedSessions.addAll(all)
            }
            renderSessions()
        }
        findViewById<TextView>(R.id.btnDeleteSelected).setOnClickListener { confirmDeleteSelected() }
        findViewById<TextView>(R.id.btnCancelManage).setOnClickListener { exitManageMode() }

        findViewById<ImageView>(R.id.btnMenu).setOnClickListener {
            renderSessions()
            drawer.openDrawer(drawerPanel)
        }
        findViewById<ImageView>(R.id.btnNew).setOnClickListener { newChat() }

        // 小飞挪进标题栏：之前只在欢迎屏，发第一条消息就没了，
        // 而"思考时歪头"恰恰只有对话进行时才有意义
        face = AgentFaceView(this).apply { mood = AgentFaceView.Mood.IDLE }
        findViewById<FrameLayout>(R.id.faceSlot).addView(
            face,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        findViewById<TextView>(R.id.btnDrawerSettings).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateSendState()
        })

        if (!AgentFactory.hasKey(this)) {
            toast("先去设置里填 API Key")
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 提前把通知渠道建出来，用户去系统设置里能看见「助手提醒」，而不是一个渠道都没有
        NotificationHelper.ensureChannel(this)
        askNotifyPermission()
    }

    // ---------- 欢迎屏 ----------

    private fun showWelcome() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(30), 0, 0)
        }

        // 小飞只在欢迎屏存在；欢迎屏移除后 View 被 detach，动画自动停
        box.addView(
            AgentFaceView(this).apply { mood = AgentFaceView.Mood.IDLE },
            LinearLayout.LayoutParams(dp(126), dp(126))
        )

        box.addView(TextView(this).apply {
            text = "有什么可以帮你的？"
            textSize = 21f
            setTextColor(getColor(R.color.ink))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        })

        box.addView(TextView(this).apply {
            text = "能搜网页、读长文、记事，也会主动发通知"
            textSize = 13f
            setTextColor(getColor(R.color.muted))
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(22))
        })

        // 示例来自 files/agent/suggestions.json，模型也能读、能改
        for (q in SuggestionStore.load(this)) {
            box.addView(chip(q), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(9) })
        }

        msgList.addView(box)
        welcome = box
    }

    private fun chip(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(getColor(R.color.ink_soft))
        gravity = Gravity.CENTER
        setPadding(dp(17), dp(11), dp(17), dp(11))
        background = getDrawable(R.drawable.bg_input)
        setOnClickListener {
            input.setText(text)
            input.setSelection(text.length)
            send()   // 示例是"一键开始"，不是"帮你打字"
        }
    }

    private fun removeWelcome() {
        welcome?.let { msgList.removeView(it) }
        welcome = null
    }

    // ---------- 消息渲染 ----------

    private fun addUser(text: String, autoScroll: Boolean = true) {
        removeWelcome()

        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15.5f
            setTextColor(getColor(R.color.on_accent))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = getDrawable(R.drawable.bg_user_bubble)
            maxWidth = maxBubble
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        // 复制按钮放在气泡左边。气泡本身已经能长按选中，
        // 但长按选字在小屏上容易误触，给个明确的按钮更稳
        row.addView(TextView(this).apply {
            // 注意：外层有个同名参数 text，这里要指定 this.text，不然赋值到外层那个 val 上
            this.text = "复制"
            textSize = 11f
            setTextColor(getColor(R.color.muted))
            setPadding(dp(8), 0, dp(8), 0)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { copyToClipboard(text) }
        })
        row.addView(bubble)

        addMsg(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })
        if (autoScroll) scrollDown(true)
    }

    private fun newAssistant(): TextView {
        return newAssistant(null)
    }

    /**
     * 建一条助手消息。
     *
     * src 是这条消息的原文缓冲。每条消息各有一个——
     * 之前所有消息共用一个 streamBuf，复制按钮就只会复制到最新那一条，
     * 翻历史时复制到的内容是错的。
     */
    private fun newAssistant(src: StringBuilder?): TextView {
        removeWelcome()

        // 这条消息自己的原文。流式时往里写，历史渲染时直接给它原文
        val source = src ?: StringBuilder()
        currentAssistantSrc = source

        // 助手消息也做成一块：浅色卡片，和右边的橙色用户气泡分开，一屏里能看出谁说哪句
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_assistant_card)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }

        // 头部：左边「飞飞」，右边「复制」。
        // 为什么要显式放复制按钮：正文里的链接要能点，movementMethod 被链接占着，
        // 长按选字就不可靠了。按钮是百分之百能用的那条路。
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(2))
        }
        header.addView(TextView(this).apply {
            text = "飞飞"
            textSize = 11.5f
            setTextColor(getColor(R.color.muted))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "复制"
            textSize = 11.5f
            setTextColor(getColor(R.color.accent))
            setPadding(dp(10), dp(3), dp(2), dp(3))
            setOnClickListener { copyAnswer(source) }
        })
        card.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // 正文/代码块都放这一层。重建时只清它，头部（复制按钮）留着
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        card.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val tv = TextView(this).apply {
            textSize = 15.5f
            setTextColor(getColor(R.color.ink_soft))
            setLineSpacing(dp(5).toFloat(), 1f)
            // 正文里的链接要能点，必须给 movementMethod
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
        content.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        addMsg(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        currentAssistantBox = card
        currentAssistantContent = content
        return tv
    }

    /**
     * 复制这条助手回答。
     * 复制的是纯文本（去掉 markdown 记号），粘到别处能直接看。
     */
    private fun copyAnswer(source: StringBuilder) {
        val raw = source.toString()
        if (raw.isBlank()) {
            toast("还没有内容可以复制")
            return
        }
        copyToClipboard(stripMarkdown(raw))
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("飞飞", text))
            toast("已复制 ${text.length} 字")
        } catch (e: Exception) {
            toast("复制失败：${e.message}")
        }
    }

    /** 把 markdown 的记号去掉，只留能读的文字 */
    private fun stripMarkdown(md: String): String {
        val sb = StringBuilder()
        md.lineSequence().forEach { line ->
            var l = line.trim()
            if (l.startsWith("```")) return@forEach
            l = l.replace(Regex("^#{1,6}\\s*"), "")
            l = l.replace(Regex("^>\\s*"), "")
            l = l.replace(Regex("^[-*+]\\s+"), "")
            l = l.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            l = l.replace(Regex("__(.+?)__"), "$1")
            l = l.replace(Regex("\\*(.+?)\\*"), "$1")
            l = l.replace(Regex("`(.+?)`"), "$1")
            sb.append(l).append('\n')
        }
        return sb.toString().trim()
    }

    /**
     * 把一段完整回答拆成「正文 / 代码卡片」交替的块。
     * 只在流式结束后调用一次 —— 流式期间每 60ms 重建会卡。
     */
    private fun renderSegments(box: LinearLayout, md: String) {
        // 只清正文层，头部（飞飞 + 复制按钮）留着
        box.removeAllViews()
        val segs = Markdown.split(md)
        if (segs.isEmpty()) return

        segs.forEach { seg ->
            if (seg.isCode) {
                box.addView(
                    CodeCardView(this, seg.body, seg.lang),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(6)
                        bottomMargin = dp(6)
                    }
                )
            } else {
                val tv = TextView(this).apply {
                    text = Markdown.render(seg.body)
                    textSize = 15.5f
                    setTextColor(getColor(R.color.ink_soft))
                    setLineSpacing(dp(5).toFloat(), 1f)
                    movementMethod = LinkMovementMethod.getInstance()
                    highlightColor = Color.TRANSPARENT
                }
                box.addView(tv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(5)
                    bottomMargin = dp(5)
                })
            }
        }
    }

    /** 插入到状态行之前；没有状态行就追加到末尾 */
    private fun addMsg(v: View, lp: LinearLayout.LayoutParams) {
        val idx = statusView?.let { msgList.indexOfChild(it) } ?: -1
        if (idx >= 0) msgList.addView(v, idx, lp) else msgList.addView(v, lp)
    }

    // ---------- 发送 ----------

    private fun send() {
        if (busy) return

        val text = input.text.toString().trim()
        if (text.isEmpty()) return

        if (!AgentFactory.hasKey(this)) {
            toast("先填 API Key")
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        input.setText("")
        addUser(text)

        cancelFlag.set(false)
        val gen = ++generation
        val sid = store.currentId()
        runningSessions.add(sid)
        currentAssistant = null
        // 这一轮自己的原文缓冲。历史里每条消息各有一个，复制按钮才不会复制错
        val src = StringBuilder()
        currentAssistantSrc = src
        lastFlush = 0
        setBusy(true)

        // 过程记录面板：一开始就建好，边跑边往里加。默认收起
        val trace = TraceView(this)
        currentTrace = trace
        addMsg(trace, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
            bottomMargin = dp(2)
        })

        val loop = AgentFactory.loop(
            this,
            onStep = { step ->
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    face?.mood = AgentFaceView.Mood.WORKING
                    setStatus(step)
                    if (src.isNotEmpty()) {
                        src.append("\n\n")
                        currentAssistant?.text = Markdown.render(src.toString())
                        scrollDown(false)
                    }
                }
            },
            onDelta = { piece ->
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    val tv = currentAssistant ?: newAssistant(src).also { currentAssistant = it }
                    src.append(piece)

                    val now = System.currentTimeMillis()
                    if (now - lastFlush < 60) return@runOnUiThread
                    lastFlush = now

                    val s = src.toString()
                    tv.text = if (s.length < 1600) Markdown.render(s) else s
                    scrollDown(false)
                }
            },
            onThinking = { runOnUiThread { if (gen == generation) setStatus("思考中") } },
            onToolProgress = { n ->
                runOnUiThread { if (gen == generation) setStatus("正在写内容 · $n 字") }
            },
            onTrace = { e ->
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    trace.append(e)
                    scrollDown(false)
                }
            },
            isCancelled = { cancelFlag.get() }
        )

        Thread {
            try {
                loop.run(text)
                runOnUiThread {
                    runningSessions.remove(sid)
                    if (gen == generation) {
                        finishTurn(null)
                    } else if (sid == store.currentId()) {
                        // 你切回了这个会话，把刚写进去的回答补到界面上
                        renderHistory()
                    }
                }
            } catch (e: CancelledException) {
                runOnUiThread {
                    runningSessions.remove(sid)
                    if (gen == generation) finishStopped()
                    else if (sid == store.currentId()) renderHistory()
                }
            } catch (e: Exception) {
                // 后台线程的异常 App 不会崩，但用户只会看到一句「出错了」。
                // 记一份堆栈进去，否则出了问题完全无从查起
                CrashLog.report(this, "对话轮次异常", e)
                runOnUiThread {
                    runningSessions.remove(sid)
                    if (gen == generation) {
                        finishTurn(e.message ?: e.javaClass.simpleName)
                    } else if (sid == store.currentId()) {
                        renderHistory()
                    }
                }
            }
        }.start()
    }

    private fun finishTurn(err: String?) {
        clearStatus()

        if (err != null) {
            val src = currentAssistantSrc ?: StringBuilder().also { currentAssistantSrc = it }
            val tv = currentAssistant ?: newAssistant(src).also { currentAssistant = it }
            src.append(if (src.isEmpty()) "" else "\n\n").append("出错了：").append(err)
            tv.text = src.toString()
            tv.setTextColor(getColor(R.color.danger))
        } else if (currentAssistant == null) {
            newAssistant().text = "没拿到回答，换个说法再试一次。"
        } else {
            val s = currentAssistantSrc?.toString().orEmpty()
            val box = currentAssistantContent
            if (box != null && s.contains("```")) {
                // 有代码块：整块重建，代码才能变成带复制按钮的卡片
                renderSegments(box, s)
                currentAssistant = null
            } else {
                currentAssistant?.apply {
                    text = Markdown.render(s)
                }
            }
        }

        setBusy(false)
        scrollDown(true)
    }

    // ---------- 语音输入 ----------

    /**
     * 点麦克风：先要权限，再拉起系统语音识别。
     * 用 ACTION_RECOGNIZE_SPEECH 走系统自带的识别界面，不自己管麦克风——
     * 国产 ROM 基本都内置识别服务（讯飞那类），比自己接 SpeechRecognizer 稳得多。
     */
    private fun startVoice() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VOICE)
            return
        }
        launchRecognizer()
    }

    private fun launchRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话")
        }
        try {
            startActivityForResult(intent, REQ_VOICE_RESULT)
        } catch (e: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setTitle("没有语音识别服务")
                .setMessage("这台手机没找到可用的语音识别。\n\n可以先打字，或者装一个带语音识别的输入法。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    /**
     * 识别结果回填输入框，不自动发——语音识别的文字经常有小错，
     * 让你看一眼再按发送，比发出去一句错话再撤回强。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE_RESULT) return
        if (resultCode != Activity.RESULT_OK) return

        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()?.trim().orEmpty()
        if (said.isEmpty()) return

        val old = input.text.toString()
        input.setText(if (old.isBlank()) said else "$old $said")
        input.setSelection(input.text.length)
        input.requestFocus()
    }

    // ---------- 状态行（跟随对话流，在最后一条消息下方） ----------

    private fun setStatus(text: String) {
        val tv = statusView ?: TextView(this).apply {
            textSize = 12.5f
            setTextColor(getColor(R.color.accent))
            setPadding(0, dp(12), 0, 0)
        }.also { statusView = it }

        tv.text = "· $text…"

        if (tv.parent == null) {
            msgList.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        } else {
            // 放到最末尾，保证永远在最新内容下方
            msgList.removeView(tv)
            msgList.addView(tv)
        }
        scrollDown(false)
    }

    private fun clearStatus() {
        statusView?.let { msgList.removeView(it) }
        statusView = null
    }

    private fun scrollDown(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastScroll < 60) return
        lastScroll = now
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- 菜单 ----------

    // ---------- 会话 ----------

    /** 把当前会话的历史消息重新画到界面上 */
    private fun renderHistory() {
        msgList.removeAllViews()
        currentAssistant = null
        currentAssistantSrc = null
        currentTrace = null
        statusView = null
        welcome = null

        val msgs = store.loadHistory()
        if (msgs.isEmpty()) {
            showWelcome()
        } else {
            for (m in msgs) {
                if (m.role == "user") {
                    addUser(m.content.orEmpty(), autoScroll = false)
                } else {
                    val content = m.content.orEmpty()

                    // 这一轮的过程记录：存盘时一起存了，切回来还能看到它当时干了什么
                    if (m.trace.isNotEmpty()) {
                        val tv = TraceView(this).apply {
                            m.trace.forEach { append(it) }
                        }
                        addMsg(tv, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dp(12)
                            bottomMargin = dp(2)
                        })
                    }

                    // 每条消息一个自己的原文缓冲，复制按钮才不会复制到别条去
                    val src = StringBuilder(content)
                    val tv = newAssistant(src)
                    if (content.contains("```")) {
                        // 历史里的代码块同样拆成卡片，不能回退成带底色的文本
                        currentAssistantContent?.let { renderSegments(it, content) }
                        currentAssistant = null
                    } else {
                        tv.text = Markdown.render(content)
                    }
                }
            }
            scrollDown(true)
        }

        // 切回一个还在后台跑的会话时，告诉用户它在写，不是没反应
        if (runningSessions.contains(store.currentId())) {
            setStatus("这一轮还在后台生成，答完会自动补上")
        } else {
            clearStatus()
        }
    }

    // ---------- 抽屉里的会话列表 ----------

    private fun renderSessions() {
        sessionList.removeAllViews()
        val list = store.listSessions()
        updateManageBar()
        if (list.isEmpty()) return

        val current = store.currentId()

        for (m in list) {
            val row = LinearLayout(this).apply {
                orientation = if (manageMode) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                setPadding(dp(20), dp(11), dp(20), dp(11))
                if (m.id == current) setBackgroundColor(0x14C4693F)
            }

            if (manageMode) {
                row.addView(TextView(this).apply {
                    text = if (selectedSessions.contains(m.id)) "☑" else "☐"
                    textSize = 17f
                    setTextColor(getColor(R.color.accent))
                    gravity = Gravity.CENTER
                    setPadding(0, 0, dp(12), 0)
                })
            }

            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = if (manageMode) {
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                } else {
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
            }

            body.addView(TextView(this).apply {
                text = m.title
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (m.id == current) getColor(R.color.accent) else getColor(R.color.ink_soft))
            })

            body.addView(TextView(this).apply {
                val running = runningSessions.contains(m.id)
                text = if (running) "· 正在生成…" else "${m.count} 条"
                textSize = 11f
                setTextColor(if (running) getColor(R.color.accent) else getColor(R.color.muted))
                setPadding(0, dp(3), 0, 0)
            })

            row.addView(body)

            row.setOnClickListener {
                if (manageMode) {
                    if (!selectedSessions.remove(m.id)) selectedSessions.add(m.id)
                    renderSessions()
                } else {
                    generation++
                    setBusy(false)
                    clearStatus()
                    store.switchTo(m.id)
                    renderHistory()
                    renderSessions()
                    drawer.closeDrawer(drawerPanel)
                }
            }

            row.setOnLongClickListener {
                if (manageMode) {
                    if (!selectedSessions.remove(m.id)) selectedSessions.add(m.id)
                    renderSessions()
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("删除「${m.title}」？")
                        .setPositiveButton("删除") { _, _ ->
                            store.deleteSession(m.id)
                            renderSessions()
                            renderHistory()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                true
            }

            sessionList.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun enterManageMode() {
        manageMode = true
        selectedSessions.clear()
        btnManageSessions.text = "完成"
        sessionActionBar.visibility = android.view.View.VISIBLE
        renderSessions()
    }

    private fun exitManageMode() {
        manageMode = false
        selectedSessions.clear()
        btnManageSessions.text = "管理"
        sessionActionBar.visibility = android.view.View.GONE
        renderSessions()
    }

    private fun updateManageBar() {
        if (!manageMode) {
            sessionActionBar.visibility = android.view.View.GONE
            return
        }
        sessionActionBar.visibility = android.view.View.VISIBLE
        txtSelectedCount.text = "已选 ${selectedSessions.size} 条"
    }

    private fun confirmDeleteSelected() {
        if (selectedSessions.isEmpty()) {
            toast("先勾选要删的会话")
            return
        }
        val n = selectedSessions.size
        val names = selectedSessions.toList()
        val msg = if (n == 1) {
            "删除「${store.listSessions().firstOrNull { it.id == names[0] }?.title ?: "该会话"}」？"
        } else {
            "删除选中的 $n 个会话？删掉就找不回来了。"
        }
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton("删除") { _, _ ->
                for (id in names) store.deleteSession(id)
                selectedSessions.clear()
                renderSessions()
                renderHistory()
                toast("已删除 $n 个会话")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun newChat() {
        val wasRunning = busy
        generation++          // 让正在跑的请求作废，它的结果不再画到界面上
        setBusy(false)
        clearStatus()
        drawer.closeDrawer(drawerPanel)
        store.createSession()
        renderHistory()
        renderSessions()

        if (wasRunning) {
            toast("上一轮还在后台跑，答完会存进原来那段对话")
        }
    }

    // ---------- 杂项 ----------

    private fun updateSendState() {
        val typed = input.text.toString().isNotBlank()
        // 忙的时候这个按钮就是「停止」，所以它必须可点
        val enabled = busy || typed
        sendBtn.background = getDrawable(
            if (busy || typed) R.drawable.bg_send_on else R.drawable.bg_send_off
        )
        sendBtn.alpha = if (enabled) 1f else 0.55f
        sendBtn.text = if (busy) "■" else "↑"
        sendBtn.isEnabled = enabled
    }

    /** 停止：只置标记，真正的收尾发生在后台线程抛 CancelledException 之后 */
    private fun stop() {
        if (!busy) return
        cancelFlag.set(true)
        setStatus("正在停止")
        sendBtn.isEnabled = false
        sendBtn.alpha = 0.55f
    }

    /** 停止后的收尾：屏幕上已经显示出来的半截内容保留，加一个标记 */
    private fun finishStopped() {
        clearStatus()
        val src = currentAssistantSrc ?: StringBuilder()
        val s = src.toString().trim()
        if (s.isEmpty()) {
            newAssistant().text = "（已停止）"
        } else {
            val tv = currentAssistant
            if (tv != null) tv.text = Markdown.render(s + "\n\n（已停止）")
            else newAssistant(src).text = s + "\n\n（已停止）"
        }
        currentAssistant = null
        setBusy(false)
        scrollDown(true)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        face?.mood = if (b) AgentFaceView.Mood.THINKING else AgentFaceView.Mood.IDLE
        updateSendState()
    }

    private fun askNotifyPermission() {
        NotifyPermission.request(this)
    }

    /** 用户点了「不允许」得给条后路，否则定时任务永远弹不出结果 */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_VOICE) {
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (ok) launchRecognizer()
            else toast("没有麦克风权限，语音输入用不了")
            return
        }
        if (requestCode != NotifyPermission.REQ) return
        val ok = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!ok) {
            AlertDialog.Builder(this)
                .setTitle("没有通知权限")
                .setMessage("定时任务到点还是会跑，但结果弹不出来，等于白跑。\n\n去「设置 → 通知 → 飞飞」打开。")
                .setPositiveButton("去开启") { _, _ -> NotifyPermission.openSettings(this) }
                .setNegativeButton("先不管", null)
                .show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
