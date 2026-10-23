package com.mini.agent

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 分享：把文本或本地文件交给系统分享面板（微信、邮件、备忘录…）。
 *
 * 补的是一个真实缺口——save 只写进 App 私有目录，用户自己拿不到。
 */
class ShareTool(private val ctx: Context) : Tool {

    override val name = "share"
    override val description = """把内容交给别的 App（微信、备忘录、文件管理器），弹系统面板让用户选。
text 分享文字；filename 分享已 save 的文件，二选一。
正式发邮件用 email_send。
返回「已打开面板」不等于发送成功，不要当成已发送跟用户确认。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", "分享的文字。和 filename 二选一")
                    })
                    put("filename", JSONObject().apply {
                        put("type", "string")
                        put("description", "已 save 的文件名。和 text 二选一")
                    })
                    put("title", JSONObject().apply {
                        put("type", "string")
                        put("description", "面板标题，可选")
                    })
                })
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val text = args.optString("text")
        val fileName = args.optString("filename").trim()
        val title = args.optString("title").ifBlank { "分享" }

        return try {
            if (fileName.isNotEmpty()) {
                shareFile(fileName, text, title)
            } else if (text.isNotBlank()) {
                shareText(text, title)
            } else {
                "错误：text 和 filename 至少给一个"
            }
        } catch (e: Exception) {
            "分享失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    private fun shareText(text: String, title: String): String {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return "已打开分享面板（文字 ${text.length} 字）"
    }

    private fun shareFile(fileName: String, text: String, title: String): String {
        val dir = File(ctx.filesDir, "agent/saved")
        val f = File(dir, fileName.replace("/", "_").replace("..", "_"))
        if (!f.exists()) {
            val list = dir.listFiles()?.joinToString("、") { it.name } ?: "（空）"
            return "找不到文件 $fileName。saved 目录里有：$list"
        }

        val uri = FileProvider.getUriForFile(
            ctx,
            ctx.packageName + ".fileprovider",
            f
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = guessMime(f.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return "已打开分享面板（文件 $fileName，${f.length()} 字节）"
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".md") -> "text/markdown"
        name.endsWith(".txt") -> "text/plain"
        name.endsWith(".json") -> "application/json"
        name.endsWith(".csv") -> "text/csv"
        name.endsWith(".html") -> "text/html"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".pdf") -> "application/pdf"
        else -> "application/octet-stream"
    }
}
