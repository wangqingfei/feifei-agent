package com.mini.agent

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 过程记录面板：一个可展开/收起的折叠块。
 *
 * 形态参考别的智能体：默认收起，只留一行标题告诉你「它干了 5 件事」；
 * 点标题展开，看得到每一步是什么、参数是什么、返回了什么。
 *
 * 为什么单独做成 View 而不是拼进助手气泡：
 *  - 过程和回答是两件事，混在一起正文就被撑得很长
 *  - 折叠状态要能记住，不能每次重绘都弹开
 *  - 过程是流式长出来的（思考边想边写、工具逐个返回），得能增量更新
 */
class TraceView(context: Context) : LinearLayout(context) {

    private val header: LinearLayout
    private val titleView: TextView
    private val arrow: TextView
    private val body: LinearLayout

    /** 「复制」按钮：只在展开时出现，收起时没东西可复制 */
    private lateinit var copyBtn: TextView

    /** 展开状态。用户手动点过之后就记住，别每次刷新都弹开 */
    private var expanded = false

    /** 正在流式更新的「思考」行 */
    private var thinkingRow: TextView? = null

    private var count = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        background = context.getDrawable(R.drawable.bg_trace_card)
        setPadding(dp(12), dp(8), dp(12), dp(10))
        // 一开始是空的，先藏起来。第一条记录进来才显示，
        // 否则每轮对话底部都会挂一个空面板，占地方还让人以为要点
        visibility = GONE

        // ---------- 标题行（点它展开/收起） ----------
        header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { toggle() }
        }

        titleView = TextView(context).apply {
            text = "过程记录"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(context.getColor(R.color.muted))
        }

        arrow = TextView(context).apply {
            text = "▸"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(context.getColor(R.color.muted))
            setPadding(dp(6), 0, 0, 0)
        }

        header.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // 「复制」：把整段过程记录复制走，方便发给开发者排查
        copyBtn = TextView(context).apply {
            this.text = "复制"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(context.getColor(R.color.accent))
            setPadding(dp(8), dp(2), dp(8), dp(2))
            visibility = GONE
            setOnClickListener {
                val text = collectText()
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("过程记录", text))
                    Toast.makeText(context, "已复制过程记录", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
        header.addView(copyBtn)
        header.addView(arrow)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ---------- 展开后的内容区 ----------
        body = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = GONE
        }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** 展开 / 收起切换 */
    fun toggle() {
        expanded = !expanded
        body.visibility = if (expanded) VISIBLE else GONE
        arrow.text = if (expanded) "▾" else "▸"
        // 收起时没什么可复制的，复制按钮就藏起来
        copyBtn.visibility = if (expanded) VISIBLE else GONE
    }

    /** 把面板里的内容拼成一段能读懂的纯文本 */
    private fun collectText(): String {
        val sb = StringBuilder()
        for (i in 0 until body.childCount) {
            val row = body.getChildAt(i) as? LinearLayout ?: continue
            val tag = (row.getChildAt(0) as? TextView)?.text?.toString().orEmpty()
            val body0 = (row.getChildAt(1) as? TextView)?.text?.toString().orEmpty()
            sb.append('【').append(tag).append("】").append(body0).append('\n')
        }
        return sb.toString().trim()
    }

    /**
     * 追加一条过程记录。
     * kind 决定左边的小标签长什么样。
     */
    fun append(e: TraceEvent) {
        // 「思考」是流式的一条，来了新的就把上一条覆盖掉，而不是一行行往下堆
        if (e.kind == TraceEvent.THINK) {
            updateThinking(e.detail)
            return
        }

        // 有内容了才显示出来
        if (visibility != VISIBLE) visibility = VISIBLE

        // 出现了别类型的事件，说明这一轮的思考已经过去了。
        // 下一轮再来思考时得另起一行，不然两轮的思考会挤在同一行里
        thinkingRow = null

        count++
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
        }

        row.addView(TextView(context).apply {
            this.text = badge(e.kind)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(context.getColor(R.color.accent))
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(1), dp(8), dp(1))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val text = TextView(context).apply {
            // 写 this.text 明确一点：外面有个同名局部变量，容易看错
            this.text = if (e.detail.isBlank()) e.title else "${e.title}\n${e.detail}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(context.getColor(R.color.ink_soft))
            setLineSpacing(dp(2).toFloat(), 1f)
            // 详情也允许选中复制，不然查问题的时候没法把一段抠出来
            setTextIsSelectable(true)
        }
        row.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        body.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refreshTitle()
    }

    /**
     * 流式更新「思考」内容。
     * 思考是边想边吐的，所以这里只维护一行，反复往里写。
     */
    fun updateThinking(text: String) {
        if (text.isBlank()) return
        if (visibility != VISIBLE) visibility = VISIBLE
        // 思考是反复更新的同一行，只在第一次建行时计数，
        // 不然标题会变成「过程记录 · 300」，全是同一条思考
        val tv = thinkingRow ?: run {
            count++
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(TextView(context).apply {
                // 参数名 text 遮蔽了 TextView.text 属性，得写 this.text
                this.text = badge(TraceEvent.THINK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(context.getColor(R.color.accent))
                typeface = Typeface.MONOSPACE
                setPadding(dp(8), dp(1), dp(8), dp(1))
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            val t = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(context.getColor(R.color.ink_soft))
                setLineSpacing(dp(2).toFloat(), 1f)
                setTextIsSelectable(true)
            }
            row.addView(t, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            body.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            thinkingRow = t
            t
        }
        tv.text = text
        refreshTitle()
    }

    /** 标题上显示「过程记录 · N」 */
    private fun refreshTitle() {
        titleView.text = if (count > 0) "过程记录 · $count" else "过程记录"
    }

    private fun badge(kind: String): String = when (kind) {
        TraceEvent.THINK -> "思考"
        TraceEvent.TOOL -> "调用"
        TraceEvent.RESULT -> "返回"
        TraceEvent.NOTE -> "提示"
        TraceEvent.ERROR -> "错误"
        else -> "·"
    }
}
