package com.mini.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 画图：Canvas 直接画，输出 PNG。
 *
 * 零依赖是关键——没有图表库，所以柱/折/饼都自己算。
 * 好处是 APK 不胖、离线可用；代价是只支持这几种图，不追求美化。
 *
 * 为什么不把 HTML 截图：WebView 截图要先加载页面，慢且占内存，
 * 而「一个简单柱状图」用 Canvas 画完只要几毫秒。
 */
class ImageTool(private val ctx: Context) : Tool {

    override val name = "make_image"
    override val description = """画一张图存成 PNG（纯本地绘制，离线可用）。
用：做统计图、示意图、二维码、纯色图片；用户说「画个图」「做个统计图」。
type 选一种：
  bar     柱状图  data=[{label,value}]
  line    折线图  data=[{label,value}]
  pie     饼图    data=[{label,value}]
  qrcode  二维码  data="要编码的文本"（比如一个网址）
  card    信息卡  title=大标题, lines=["行1","行2"]
width/height 可选，默认 800x600（卡片 800x400，二维码 400x400）。
画完用 share 分享给用户。
画不了：渐变阴影、复杂配色主题、多系列对比图——别硬凑。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("type", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("bar").put("line").put("pie").put("qrcode").put("card"))
                        put("description", "图画什么")
                    })
                    put("filename", JSONObject().apply {
                        put("type", "string")
                        put("description", "文件名，例 统计图.png")
                    })
                    put("title", JSONObject().apply {
                        put("type", "string")
                        put("description", "图表标题；card 类型是大标题")
                    })
                    put("data", JSONObject().apply {
                        put("description", "bar/line/pie 用数组 [{\"label\":\"一\",\"value\":12}]；qrcode 用字符串")
                    })
                    put("lines", JSONObject().apply {
                        put("type", "array")
                        put("description", "card 类型用，文本行数组")
                        put("items", JSONObject().apply { put("type", "string") })
                    })
                    put("width", JSONObject().apply {
                        put("type", "integer")
                        put("description", "宽度像素，默认 800")
                    })
                    put("height", JSONObject().apply {
                        put("type", "integer")
                        put("description", "高度像素，默认 600")
                    })
                })
                put("required", JSONArray().put("type"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val type = args.optString("type").trim().lowercase()
        val w = args.optInt("width", 0).coerceIn(200, 2000)
        val h = args.optInt("height", 0).coerceIn(200, 2000)

        return try {
            val bmp: Bitmap
            val desc: String
            when (type) {
                "qrcode" -> {
                    val content = when {
                        args.optString("data").isNotEmpty() -> args.optString("data")
                        else -> return "错误：qrcode 要给 data（要编码的文本或网址）"
                    }
                    val size = if (w > 0 && args.has("width")) w else 400
                    bmp = QrCode.encode(content, size)
                    desc = "二维码（${content.take(30)}）"
                }

                "card" -> {
                    val title = args.optString("title")
                    val lines = args.optJSONArray("lines")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    } ?: emptyList()
                    if (title.isEmpty() && lines.isEmpty()) {
                        return "错误：card 要给 title 或 lines"
                    }
                    val cw = if (args.has("width")) w else 800
                    val ch = if (args.has("height")) h else max(300, 200 + lines.size * 70)
                    bmp = drawCard(title, lines, cw, ch)
                    desc = "信息卡"
                }

                "bar", "line", "pie" -> {
                    val data = parseData(args)
                    if (data.isEmpty()) {
                        return "错误：data 不能为空，格式 [{\"label\":\"甲\",\"value\":10}]"
                    }
                    val title = args.optString("title")
                    val cw = if (args.has("width")) w else 800
                    val ch = if (args.has("height")) h else 600
                    bmp = when (type) {
                        "bar" -> drawBar(title, data, cw, ch)
                        "line" -> drawLine(title, data, cw, ch)
                        else -> drawPie(title, data, cw, ch)
                    }
                    desc = "${type} 图（${data.size} 项）"
                }

                else -> return "错误：type 只能是 bar / line / pie / qrcode / card"
            }

            val name = args.optString("filename").trim()
                .ifEmpty { "image-${System.currentTimeMillis()}.png" }
                .let { if (it.contains('.')) it else "$it.png" }
                .replace("/", "_").replace("..", "_")

            val f = File(ctx.filesDir, "agent/saved/$name")
            f.parentFile?.mkdirs()
            val bw = bmp.width
            val bh = bmp.height
            FileOutputStream(f).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bmp.recycle()

            "已生成 $desc：${f.name}（${f.length()} 字节，${bw}x${bh}）\n" +
                "路径：${f.absolutePath}\n" +
                "要发给用户就再调 share。"
        } catch (e: Exception) {
            "画图失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    private data class Item(val label: String, val value: Float)

    private fun parseData(args: JSONObject): List<Item> {
        val raw = args.opt("data")
        val arr = when (raw) {
            is JSONArray -> raw
            is String -> try { JSONArray(raw) } catch (e: Exception) { null }
            else -> null
        } ?: return emptyList()

        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("label").ifEmpty { "项${i + 1}" }
            val value = when (val v = o.opt("value")) {
                is Number -> v.toFloat()
                is String -> v.toFloatOrNull() ?: 0f
                else -> 0f
            }
            out.add(Item(label, value))
        }
        return out
    }

    // ---------- 绘制基元 ----------

    private fun paint(color: Int, size: Float = 32f, bold: Boolean = false): Paint = Paint().apply {
        this.color = color
        this.textSize = size
        this.isAntiAlias = true
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val accent = Color.parseColor("#C4693F")
    private val ink = Color.parseColor("#222222")
    private val muted = Color.parseColor("#8A8A8A")
    private val grid = Color.parseColor("#E6E6E6")

    private val palette = intArrayOf(
        Color.parseColor("#C4693F"), Color.parseColor("#3D6FB4"), Color.parseColor("#4E9E6A"),
        Color.parseColor("#D9A441"), Color.parseColor("#8A6BBE"), Color.parseColor("#5FA8C4"),
        Color.parseColor("#C4567A"), Color.parseColor("#7A8B99")
    )

    // ---------- 柱状图 ----------

    private fun drawBar(title: String, data: List<Item>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val padL = 70f * w / 800f
        val padR = 30f * w / 800f
        val padT = 70f * h / 600f
        val padB = 70f * h / 600f
        val scale = min(w / 800f, h / 600f)

        val chartL = padL
        val chartR = w - padR
        val chartT = padT
        val chartB = h - padB

        if (title.isNotEmpty()) {
            c.drawText(title, padL, 42f * scale, paint(ink, 30f * scale, true))
        }

        val maxV = data.maxOf { it.value }.coerceAtLeast(1f)
        // 纵轴刻度：4 档
        val gridPaint = paint(grid, 2f * scale)
        val axisPaint = paint(muted, 20f * scale)
        for (i in 0..4) {
            val y = chartB - (chartB - chartT) * i / 4f
            c.drawLine(chartL, y, chartR, y, gridPaint)
            val label = fmt(maxV * i / 4f)
            c.drawText(label, chartL - 8f * scale - axisPaint.measureText(label), y + 6f * scale, axisPaint)
        }

        val slot = (chartR - chartL) / data.size
        val barW = slot * 0.55f
        for ((i, item) in data.withIndex()) {
            val bh = (chartB - chartT) * (item.value / maxV)
            val left = chartL + slot * i + (slot - barW) / 2f
            val rect = RectF(left, chartB - bh, left + barW, chartB)
            val p = paint(palette[i % palette.size])
            c.drawRoundRect(rect, 6f * scale, 6f * scale, p)

            // 数值标在柱顶
            val vLabel = fmt(item.value)
            val vp = paint(ink, 20f * scale)
            c.drawText(vLabel, left + barW / 2f - vp.measureText(vLabel) / 2f, chartB - bh - 8f * scale, vp)

            // 标签在下方
            val lp = paint(muted, 20f * scale)
            val tw = lp.measureText(item.label)
            c.drawText(item.label, left + barW / 2f - tw / 2f, chartB + 26f * scale, lp)
        }

        c.drawLine(chartL, chartB, chartR, chartB, paint(Color.parseColor("#BBBBBB"), 2f * scale))
        return bmp
    }

    // ---------- 折线图 ----------

    private fun drawLine(title: String, data: List<Item>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val scale = min(w / 800f, h / 600f)
        val padL = 70f * scale
        val padR = 30f * scale
        val padT = 70f * scale
        val padB = 70f * scale
        val chartL = padL
        val chartR = w - padR
        val chartT = padT
        val chartB = h - padB

        if (title.isNotEmpty()) {
            c.drawText(title, padL, 42f * scale, paint(ink, 30f * scale, true))
        }

        val maxV = data.maxOf { it.value }.coerceAtLeast(1f)
        val minV = min(0f, data.minOf { it.value })
        val span = (maxV - minV).coerceAtLeast(0.0001f)

        val gridPaint = paint(grid, 2f * scale)
        val axisPaint = paint(muted, 20f * scale)
        for (i in 0..4) {
            val y = chartB - (chartB - chartT) * i / 4f
            c.drawLine(chartL, y, chartR, y, gridPaint)
            val label = fmt(minV + span * i / 4f)
            c.drawText(label, chartL - 8f * scale - axisPaint.measureText(label), y + 6f * scale, axisPaint)
        }

        val stepX = if (data.size > 1) (chartR - chartL) / (data.size - 1) else 0f
        val path = Path()
        val dots = ArrayList<Pair<Float, Float>>()
        for ((i, item) in data.withIndex()) {
            val x = chartL + stepX * i
            val y = chartB - (chartB - chartT) * ((item.value - minV) / span)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            dots.add(x to y)
        }
        c.drawPath(path, paint(accent, 4f * scale).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        })

        val dotPaint = paint(accent, 12f * scale)
        val lp = paint(muted, 20f * scale)
        for ((i, item) in data.withIndex()) {
            val (x, y) = dots[i]
            c.drawCircle(x, y, 7f * scale, dotPaint)
            c.drawText(fmt(item.value), x - dotPaint.measureText(fmt(item.value)) / 2f, y - 14f * scale,
                paint(ink, 20f * scale))
            val tw = lp.measureText(item.label)
            c.drawText(item.label, x - tw / 2f, chartB + 26f * scale, lp)
        }

        c.drawLine(chartL, chartB, chartR, chartB, paint(Color.parseColor("#BBBBBB"), 2f * scale))
        return bmp
    }

    // ---------- 饼图 ----------

    private fun drawPie(title: String, data: List<Item>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val scale = min(w / 800f, h / 600f)
        val padL = 40f * scale
        val padT = 70f * scale
        val padB = 40f * scale

        if (title.isNotEmpty()) {
            c.drawText(title, padL, 42f * scale, paint(ink, 30f * scale, true))
        }

        val total = data.sumOf { it.value.toDouble() }.toFloat()
        if (total <= 0f) {
            c.drawText("数据全为 0，画不了饼图", padL, padT + 60f * scale, paint(muted, 24f * scale))
            return bmp
        }

        val legendW = 220f * scale
        val availW = w - padL * 2 - legendW
        val availH = h - padT - padB
        val size = min(availW, availH) * 0.9f
        val radius = size / 2f
        val cx = padL + radius
        val cy = padT + availH / 2f
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        var startAngle = -90f
        for ((i, item) in data.withIndex()) {
            val sweep = 360f * (item.value / total)
            val p = paint(palette[i % palette.size])
            c.drawArc(rect, startAngle, sweep, true, p)
            startAngle += sweep
        }

        // 图例
        val lp = paint(ink, 22f * scale)
        val vp = paint(muted, 20f * scale)
        var ly = cy - radius + 10f * scale
        val lx = cx + radius + 30f * scale
        for ((i, item) in data.withIndex()) {
            c.drawRect(lx, ly, lx + 22f * scale, ly + 22f * scale, paint(palette[i % palette.size]))
            c.drawText(item.label, lx + 34f * scale, ly + 18f * scale, lp)
            val pct = (100.0 * item.value / total).roundToInt()
            c.drawText("${fmt(item.value)}（$pct%）", lx + 34f * scale, ly + 42f * scale, vp)
            ly += 56f * scale
        }
        return bmp
    }

    // ---------- 信息卡 ----------

    private fun drawCard(title: String, lines: List<String>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val scale = min(w / 800f, h / 400f)
        val pad = 50f * scale

        // 顶部一条彩色带
        c.drawRect(0f, 0f, w.toFloat(), 10f * scale, paint(accent))

        if (title.isNotEmpty()) {
            c.drawText(title, pad, 90f * scale, paint(ink, 40f * scale, true))
        }
        var y = if (title.isNotEmpty()) 160f * scale else 70f * scale
        for (line in lines) {
            c.drawCircle(pad + 6f * scale, y - 8f * scale, 5f * scale, paint(accent))
            c.drawText(line, pad + 26f * scale, y, paint(ink, 26f * scale))
            y += 52f * scale
        }
        return bmp
    }

    /** 数字友好显示：最多两位小数，去掉多余的 .0 */
    private fun fmt(v: Float): String {
        val r = (v * 100).roundToInt() / 100f
        return if (abs(r - r.roundToInt()) < 0.001f) r.roundToInt().toString() else r.toString()
    }
}
