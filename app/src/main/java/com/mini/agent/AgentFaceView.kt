package com.mini.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 小飞 —— 这只鸟自己会动。
 *
 * 三条设计约束：
 * 1. 静止时它就是一个完整标记（能当图标），动起来才是呼吸；
 * 2. 只在可见时跑，切走立刻停，不占后台 CPU；
 * 3. 全部 Canvas 绘制，无图片、无 Lottie。每帧二十来条指令。
 *
 * 立体感靠三样东西，不是靠描边：
 * 身体一道径向渐变（左上受光）、翅膀一道线性渐变、身子底下一条接触阴影。
 */
class AgentFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mood { IDLE, THINKING, WORKING, DONE, ERROR }

    var mood: Mood = Mood.IDLE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var startNanos = System.nanoTime()

    /** 每帧只负责触发重绘，时间从 nanoTime 算，避免长动画的时间抖动 */
    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    // 渐变按尺寸缓存，避免每帧 new
    private var bodyShader: Shader? = null
    private var wingShader: Shader? = null
    private var cachedU = 0f

    private fun colors() = intArrayOf(
        context.getColor(R.color.face_light),
        context.getColor(R.color.face_mid),
        context.getColor(R.color.face_dark)
    )

    private fun buildShaders(u: Float) {
        if (u == cachedU) return
        cachedU = u
        val c = colors()
        // 光源在左上，半径略大于身体，形成柔和过渡而不是硬边
        bodyShader = RadialGradient(
            -7f * u, -9f * u, 30f * u,
            intArrayOf(c[0], c[1], c[2]),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        wingShader = LinearGradient(
            0f, -14f * u, 0f, 8f * u,
            c[0], c[2],
            Shader.TileMode.CLAMP
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (!ticker.isStarted) {
            startNanos = System.nanoTime()
            ticker.start()
        }
    }

    private fun stop() {
        if (ticker.isStarted) ticker.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val u = min(w, h) / 120f
        buildShaders(u)

        val cx = w / 2f
        val t = (System.nanoTime() - startNanos) / 1_000_000_000f

        val bob = sin(t * 1.5f) * 2.2f * u
        val work = mood == Mood.WORKING
        val flapSpeed = if (work) 5.4f else 2.3f
        val flap = 0.5f + 0.5f * sin(t * flapSpeed)
        val blink = blinkAt(t)

        // 身子整体上移一点，给阴影留位置
        val cy = h / 2f + bob - 6f * u

        drawShadow(canvas, cx, cy, u, bob)
        drawTail(canvas, cx, cy, u)

        canvas.save()
        canvas.translate(cx, cy)
        if (mood == Mood.THINKING) {
            // 思考时歪头：绕中心小角度，符合"在琢磨"的感觉
            canvas.rotate(-6f + sin(t * 1.1f) * 2.4f)
        }
        drawBody(canvas, u)
        drawBelly(canvas, u)
        drawWings(canvas, u, flap)
        drawTuft(canvas, u, t, work)
        drawEyes(canvas, u, blink)
        drawBeak(canvas, u)
        canvas.restore()
    }

    /** 接触阴影：随浮动缩放，是"浮在空中"最廉价也最有效的手段 */
    private fun drawShadow(canvas: Canvas, cx: Float, cy: Float, u: Float, bob: Float) {
        val k = 1f - bob / (26f * u) * 0.35f
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0x22000000
        val rx = 17f * u * k
        val ry = 4.2f * u * k
        val yy = cy + 30f * u
        canvas.drawOval(cx - rx, yy - ry, cx + rx, yy + ry, paint)
    }

    private fun drawTail(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        val c = colors()
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4.4f * u
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = c[2]

        path.reset()
        path.moveTo(cx - 3.5f * u, cy + 15f * u)
        path.quadTo(cx - 11f * u, cy + 23f * u, cx - 15f * u, cy + 30f * u)
        canvas.drawPath(path, paint)

        path.reset()
        path.moveTo(cx + 3.5f * u, cy + 15f * u)
        path.quadTo(cx + 11f * u, cy + 23f * u, cx + 15f * u, cy + 30f * u)
        canvas.drawPath(path, paint)
    }

    private fun drawBody(canvas: Canvas, u: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = bodyShader
        canvas.drawOval(-20f * u, -18.5f * u, 20f * u, 18.5f * u, paint)
        paint.shader = null
    }

    private fun drawBelly(canvas: Canvas, u: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0x8CFFF3E8.toInt()
        canvas.drawOval(-12f * u, -3.5f * u, 12f * u, 15f * u, paint)

        // 左上高光：立体感的关键，压住整体不要发灰
        paint.color = 0x3DFFFFFF
        canvas.drawOval(-14f * u, -15f * u, 1f * u, -4f * u, paint)
    }

    private fun drawWings(canvas: Canvas, u: Float, flap: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = wingShader

        // 左翅
        path.reset()
        path.moveTo(-17f * u, -1f * u)
        path.quadTo(-30f * u, -7f * u - flap * 5f * u, -35f * u, 5f * u - flap * 7f * u)
        path.quadTo(-25f * u, 6f * u, -17f * u, 5f * u)
        path.close()
        canvas.drawPath(path, paint)

        // 右翅
        path.reset()
        path.moveTo(17f * u, -1f * u)
        path.quadTo(30f * u, -7f * u - flap * 5f * u, 35f * u, 5f * u - flap * 7f * u)
        path.quadTo(25f * u, 6f * u, 17f * u, 5f * u)
        path.close()
        canvas.drawPath(path, paint)

        paint.shader = null

        // 翅根压一道深色，让翅膀和身体分层
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * u
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0x33000000
        path.reset()
        path.moveTo(-17f * u, 4f * u)
        path.quadTo(-26f * u, 5f * u, -33f * u, 4f * u - flap * 6f * u)
        canvas.drawPath(path, paint)
        path.reset()
        path.moveTo(17f * u, 4f * u)
        path.quadTo(26f * u, 5f * u, 33f * u, 4f * u - flap * 6f * u)
        canvas.drawPath(path, paint)
    }

    private fun drawTuft(canvas: Canvas, u: Float, t: Float, work: Boolean) {
        val c = colors()
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 2.3f * u
        paint.color = c[2]

        if (mood == Mood.ERROR) {
            // 出错：呆毛耷拉
            path.reset()
            path.moveTo(0f, -17.5f * u)
            path.quadTo(2f * u, -21f * u, -3f * u, -23.5f * u)
            canvas.drawPath(path, paint)
            return
        }

        val swing = sin(t * (if (work) 4.2f else 2.6f)) * 1.8f * u
        path.reset()
        path.moveTo(0f, -17.5f * u)
        path.quadTo(2f * u, -24f * u, 7f * u + swing, -26f * u)
        canvas.drawPath(path, paint)
    }

    private fun drawEyes(canvas: Canvas, u: Float, blink: Float) {
        val ry = max(0.5f, 3.7f * blink) * u
        paint.style = Paint.Style.FILL
        paint.color = 0xFF2E2620.toInt()

        canvas.drawOval(-10.6f * u, -3.5f * u - ry, -3.4f * u, -3.5f * u + ry, paint)
        canvas.drawOval(3.4f * u, -3.5f * u - ry, 10.6f * u, -3.5f * u + ry, paint)

        // 眼里的高光：一个小点就够，多了发假
        if (blink > 0.55f) {
            paint.color = 0xEBFFFFFF.toInt()
            canvas.drawOval(-9.6f * u, -6.2f * u, -6.2f * u, -3.4f * u, paint)
            canvas.drawOval(4.4f * u, -6.2f * u, 7.8f * u, -3.4f * u, paint)
        }
    }

    private fun drawBeak(canvas: Canvas, u: Float) {
        val beak = context.getColor(R.color.face_beak)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = beak

        path.reset()
        path.moveTo(-3.2f * u, 3.2f * u)
        path.lineTo(3.2f * u, 3.2f * u)
        path.lineTo(0f, 8.2f * u)
        path.close()
        canvas.drawPath(path, paint)

        // 喙的下缘压一点深色，避免像贴上去的贴纸
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.9f * u
        paint.color = 0x33000000
        path.reset()
        path.moveTo(-2.6f * u, 3.6f * u)
        path.lineTo(0f, 7.6f * u)
        path.lineTo(2.6f * u, 3.6f * u)
        canvas.drawPath(path, paint)
    }

    /** 每 3.6 秒眨一次，闭合 0.26 秒 */
    private fun blinkAt(t: Float): Float {
        val p = t % 3.6f
        return if (p > 3.34f) max(0.06f, 1f - (p - 3.34f) / 0.26f) else 1f
    }
}
