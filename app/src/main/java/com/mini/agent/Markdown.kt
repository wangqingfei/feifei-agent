package com.mini.agent

import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View

/**
 * 极简 Markdown 渲染：只认模型最常用的那几种，
 * 多了反而会误伤正文（比如中文里的星号）。
 */
object Markdown {

    /** 由 MainActivity.onCreate 按屏幕密度设置，代码框的圆角/内边距要跟着密度走 */
    var density: Float = 3f

    // 暖色主题下的一套配色
    private const val CODE_BG = 0xFFF2E8DD.toInt()
    private const val CODE_BORDER = 0xFFE0CFBC.toInt()
    private const val CODE_FG = 0xFF6B3E22.toInt()
    private const val INLINE_BG = 0xFFF0E2D3.toInt()
    private const val INLINE_FG = 0xFF9A5A2E.toInt()
    private const val LINK_FG = 0xFFA85B33.toInt()

    private val INLINE = Regex(
        "\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)\\]\\(([^)\\s]+)\\)"
    )

    /** 一段内容：要么是正文，要么是一整块代码 */
    data class Segment(val isCode: Boolean, val body: String, val lang: String)

    /**
     * 把 markdown 切成「正文 / 代码」两种段。
     * 界面据此把代码渲染成独立卡片（带复制按钮），正文才走 spannable。
     */
    fun split(src: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var text = StringBuilder()
        var code = StringBuilder()
        var inCode = false
        var lang = ""

        for (line in src.split("\n")) {
            val t = line.trimStart()
            if (t.startsWith("```")) {
                if (!inCode) {
                    if (text.isNotBlank()) out.add(Segment(false, text.toString().trim('\n'), ""))
                    text = StringBuilder()
                    inCode = true
                    lang = t.removePrefix("```").trim()
                    code = StringBuilder()
                } else {
                    inCode = false
                    out.add(Segment(true, code.toString().trimEnd(), lang))
                }
                continue
            }
            if (inCode) code.append(line).append('\n') else text.append(line).append('\n')
        }

        if (inCode) {
            // 流式还没闭合的代码块，也先按代码段渲染
            out.add(Segment(true, code.toString().trimEnd(), lang))
        } else if (text.isNotBlank()) {
            out.add(Segment(false, text.toString().trim('\n'), ""))
        }
        return out
    }

    fun render(src: String): CharSequence {
        val out = SpannableStringBuilder()
        var inCode = false
        val codeBuf = StringBuilder()

        for (line in src.split("\n")) {
            val t = line.trimStart()

            if (t.startsWith("```")) {
                if (!inCode) {
                    inCode = true
                    codeBuf.setLength(0)
                } else {
                    inCode = false
                    appendCodeBlock(out, codeBuf.toString())
                }
                continue
            }
            if (inCode) {
                codeBuf.append(line).append('\n')
                continue
            }

            when {
                t.startsWith("### ") -> appendHeading(out, t.removePrefix("### "), 1.12f)
                t.startsWith("## ") -> appendHeading(out, t.removePrefix("## "), 1.2f)
                t.startsWith("# ") -> appendHeading(out, t.removePrefix("# "), 1.3f)
                t.startsWith("- ") || t.startsWith("* ") -> {
                    out.append("•  ")
                    appendInline(out, t.substring(2))
                    out.append('\n')
                }
                else -> {
                    appendInline(out, line)
                    out.append('\n')
                }
            }
        }

        if (inCode && codeBuf.isNotEmpty()) appendCodeBlock(out, codeBuf.toString())

        // 去掉末尾多余空行
        while (out.isNotEmpty() && out[out.length - 1] == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    private fun appendHeading(out: SpannableStringBuilder, text: String, scale: Float) {
        val s = out.length
        appendInline(out, text)
        out.setSpan(RelativeSizeSpan(scale), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append('\n')
    }

    /**
     * 逐行挂 span：每行一个 CodeBlockSpan，靠 first/last 拼出完整的圆角框。
     * span 范围要连换行符一起包进去，否则空行不会有背景，框会断。
     */
    private fun appendCodeBlock(out: SpannableStringBuilder, code: String) {
        val lines = code.trimEnd().split("\n")
        val radius = 9f * density
        val padV = 6f * density

        out.append('\n')

        lines.forEachIndexed { i, line ->
            val s = out.length
            out.append(line)
            out.append('\n')

            out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(
                ForegroundColorSpan(CODE_FG), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            out.setSpan(
                CodeBlockSpan(
                    CODE_BG, CODE_BORDER, radius, padV,
                    first = i == 0,
                    last = i == lines.lastIndex
                ),
                s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        out.append('\n')
    }

    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var last = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first > last) out.append(text, last, m.range.first)

            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> {
                    val s = out.length
                    out.append(g[1])
                    out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                g[2].isNotEmpty() -> {
                    val s = out.length
                    out.append(g[2])
                    out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(INLINE_BG), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(INLINE_FG), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    val s = out.length
                    out.append(g[3])
                    val url = g[4]
                    out.setSpan(
                        object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                openInApp(widget.context, url)
                            }

                            override fun updateDrawState(ds: TextPaint) {
                                ds.color = LINK_FG
                                ds.isUnderlineText = false
                            }
                        },
                        s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            last = m.range.last + 1
        }
        if (last < text.length) out.append(text, last, text.length)
    }

    /** 链接一律走内置浏览器：跳到外部 App 再返回，对话就找不到位置了 */
    private fun openInApp(ctx: android.content.Context, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        try {
            ctx.startActivity(
                Intent(ctx, WebViewActivity::class.java).apply {
                    putExtra("url", url)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
        }
    }
}
