package com.mini.agent

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.style.LineBackgroundSpan

/**
 * 代码块的圆角背景。
 *
 * 为什么不用 BackgroundColorSpan：那个只能刷一个方角矩形，文字紧贴边缘，像块补丁。
 * LineBackgroundSpan 能拿到每一行的 top/bottom，可以往外扩出内边距、自己画圆角。
 *
 * 一个代码块会被拆成「每行一个 span」，靠 first / last 决定哪几行带圆角，
 * 拼起来就是一个完整的圆角框。
 */
class CodeBlockSpan(
    private val bg: Int,
    private val border: Int,
    private val radius: Float,
    private val padV: Float,
    private val first: Boolean,
    private val last: Boolean
) : LineBackgroundSpan {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val p = Path()

    override fun drawBackground(
        canvas: Canvas,
        paint: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int
    ) {
        val l = left.toFloat()
        val r = right.toFloat()
        // 中间行往上下各多描一个圆角半径，和相邻行重叠，避免出现横向缝隙
        val t = if (first) top - padV else top - radius
        val b = if (last) bottom + padV else bottom + radius

        // 先描一圈边框色，再盖一层底色 —— 比逐边画线简单，圆角也自然
        fill.color = border
        build(l - 1.1f, t - 1.1f, r + 1.1f, b + 1.1f)
        canvas.drawPath(p, fill)

        fill.color = bg
        build(l, t, r, b)
        canvas.drawPath(p, fill)
    }

    private fun build(l: Float, t: Float, r: Float, b: Float) {
        p.reset()
        val d = Path.Direction.CW
        when {
            first && last -> p.addRoundRect(RectF(l, t, r, b), radius, radius, d)
            first -> p.addRoundRect(
                RectF(l, t, r, b),
                floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f), d
            )
            last -> p.addRoundRect(
                RectF(l, t, r, b),
                floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius), d
            )
            else -> p.addRect(l, t, r, b, d)
        }
    }
}
