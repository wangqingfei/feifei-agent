package com.mini.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 代码卡片：一个独立的块，不混在正文里。
 *
 * 顶部一行是语言名 + 复制；下面是等宽代码，超宽横向滚动。
 * 为什么独立成 View 而不是文本 span：span 没法在块内放按钮，
 * 也没法让代码横向滚动（TextView 只能整段换行）。
 */
class CodeCardView(
    context: Context,
    private val code: String,
    lang: String
) : LinearLayout(context) {

    private val copyBtn: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        background = context.getDrawable(R.drawable.bg_code_card)
        setPadding(0, 0, 0, dp(4))

        // 顶栏
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(6), dp(2))
        }

        val label = TextView(context).apply {
            text = lang.ifBlank { "code" }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(context.getColor(R.color.muted))
            typeface = Typeface.MONOSPACE
        }

        copyBtn = TextView(context).apply {
            text = "复制"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(context.getColor(R.color.accent))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { copy() }
        }

        bar.addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(copyBtn)
        addView(bar)

        // 代码：不换行 + 横向滚动
        val tv = TextView(context).apply {
            text = code
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.MONOSPACE
            setTextColor(context.getColor(R.color.code_fg))
            setPadding(dp(12), dp(2), dp(12), dp(10))
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            setHorizontallyScrolling(true)
        }

        val hs = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            // 必须用 FrameLayout.LayoutParams：HorizontalScrollView 是 FrameLayout 的子类，
            // 传 LinearLayout.LayoutParams 编译能过，运行时 addView 会抛类型转换异常
            addView(
                tv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        addView(hs)
    }

    private fun copy() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("code", code))
            copyBtn.text = "已复制"
            Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
            copyBtn.postDelayed({ copyBtn.text = "复制" }, 1500)
        } catch (e: Exception) {
            copyBtn.text = "复制失败"
            copyBtn.postDelayed({ copyBtn.text = "复制" }, 1500)
        }
    }
}
