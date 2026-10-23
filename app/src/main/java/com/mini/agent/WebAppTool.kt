package com.mini.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 写网页 → 起本地服务 → 用系统浏览器打开。
 *
 * 默认走浏览器而不是 App 内预览：全屏预览会把对话盖住，切回来还要等。
 * 手机浏览器和 App 在同一台设备上，127.0.0.1 是通用的，所以绑本机就够。
 */
class WebAppTool(private val ctx: Context) : Tool {

    override val name = "web_app"
    override val description = """写个网页在手机上真跑起来：写入本地目录 → 起 HTTP 服务（127.0.0.1:8848）→ 浏览器打开。
用：做界面、图表、能点的小工具。
HTML 要完整，样式脚本内联，手机竖屏优先，字号 ≥14px，别做宽表格。
target：browser（默认，不影响对话）/ inapp（全屏挡对话）/ none（只给地址）。
返回地址和自检结果（HTTP 状态 + 字节数），自检失败要修，别宣布完成。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("html", JSONObject().apply {
                        put("type", "string")
                        put("description", "完整 HTML，样式脚本内联")
                    })
                    put("name", JSONObject().apply {
                        put("type", "string")
                        put("description", "文件名，默认 index.html")
                    })
                    put("target", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("browser").put("inapp").put("none"))
                        put("description",
                            "browser=系统浏览器（默认），inapp=App 内全屏预览，none=只起服务不开窗口")
                    })
                })
                put("required", JSONArray().put("html"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val html = args.optString("html")
        if (html.isBlank()) return "错误：html 不能为空"

        var fileName = args.optString("name").trim().ifEmpty { "index.html" }
        fileName = fileName.replace("..", "_").replace("/", "_")
        if (!fileName.contains(".")) fileName += ".html"

        return try {
            val dir = LocalServer.rootDir(ctx)
            File(dir, fileName).writeText(html)

            val port = LocalServer.start(ctx)
            val url = "http://127.0.0.1:$port/$fileName"
            val check = selfCheck(url)

            when (args.optString("target", "browser").lowercase()) {
                "none" -> "页面已生成，服务在跑。\n地址：$url\n在手机浏览器里输入这个地址就能打开。"

                "inapp" -> {
                    ctx.startActivity(Intent(ctx, WebViewActivity::class.java).apply {
                        putExtra("port", port)
                        putExtra("path", fileName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    "已生成 $fileName（${html.length} 字），在 App 内打开。\n地址：$url"
                }

                else -> {
                    val opened = openBrowser(url, port, fileName)
                    if (opened) {
                        "已生成 $fileName（${html.length} 字），已用浏览器打开。\n" +
                            "地址：$url\n$check\n刷新页面就能看到最新内容。"
                    } else {
                        "已生成 $fileName，但没能打开浏览器。\n地址：$url\n$check"
                    }
                }
            }
        } catch (e: Exception) {
            "打开失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    /** 自己抓一次本地地址，确认页面真的能返回——这是它能做的端到端验证 */
    private fun selfCheck(url: String): String {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                val len = r.body?.bytes()?.size ?: 0
                if (r.isSuccessful) "自检：HTTP ${r.code}，返回 $len 字节" else "自检失败：HTTP ${r.code}"
            }
        } catch (e: Exception) {
            "自检失败：连不上本地服务（${e.javaClass.simpleName}）"
        }
    }

    private fun openBrowser(url: String, port: Int, fileName: String): Boolean {
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (e: Exception) {
            // 不再自动退回全屏预览——那会把对话盖住，用户毫无预期
            false
        }
    }
}
