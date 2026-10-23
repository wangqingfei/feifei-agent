package com.mini.agent

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 原生下载：走系统 DownloadManager。
 *
 * 为什么要有这条路子（OkHttp 那条已经够用了）：
 *   - 系统服务代下，App 被杀、界面切走都能接着下
 *   - 断点续传
 *   - 有系统下载通知，用户看得见进度
 *   - 落公共 Download 目录，用户在文件管理器里直接能找到
 *
 * 代价：
 *   - 异步，得轮询等完成
 *   - 公共目录文件 App 自己读要靠 MediaStore/ContentResolver
 *   - 老系统（API 26-28）写公共目录要 WRITE_EXTERNAL_STORAGE 权限
 *     → 这台机器上降级到 App 专属外部目录，不需要任何权限
 *
 * 所以：两条路子并存，模型按场景选。
 */
class NativeDownloadTool(private val ctx: Context) : Tool {

    override val name = "download_native"

    // 说明：这个类本身不单独注册进工具表，只作为 download 工具 mode=system 时的后台实现。
    // description 保留完整，方便将来要拆成独立工具时直接用。
    override val description = """用安卓系统下载器下载文件，App 关了也能下完，有系统进度通知。
什么时候用 download（普通那个）就够了：网页、小图片、PDF 这类。
什么时候用这个：文件很大（安装包、视频、压缩包）、用户明确说「后台下」「下到下载目录」、
或者用户想在自己手机里找到这个文件。
url 给完整 http(s) 地址。
filename 可选，不给就按网址推断。
返回里带 downloadId；要查进度用 download_status。
下完的文件在手机的「下载」目录里，用户自己能找到。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "完整网址，http(s):// 开头")
                    })
                    put("filename", JSONObject().apply {
                        put("type", "string")
                        put("description", "保存的文件名，可选")
                    })
                })
                put("required", JSONArray().put("url"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val url = args.optString("url").trim()
        if (url.isEmpty()) return "错误：url 不能为空"
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "错误：url 必须以 http:// 或 https:// 开头"
        }

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return "错误：这个系统没有下载服务，换用 download 试试"

        return try {
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType("application/octet-stream")
                setTitle(args.optString("filename").ifBlank { "飞飞正在下载" })
                setDescription(url)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setAllowedOverRoaming(false)

                val name = args.optString("filename").trim()
                    .ifEmpty { guessName(url) }
                // 老系统写公共目录要存储权限，这里降级到 App 专属外部目录，
                // 免得为了下载弹一个存储权限窗。API 29+ 直接进公共下载目录。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                } else {
                    setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name)
                }
            }
            val id = dm.enqueue(req)
            "已加入系统下载队列（任务号 $id）。\n" +
                "文件会存在手机的「下载」目录，用户自己能找到。\n" +
                "查进度用 download_status，参数 downloadId=$id。"
        } catch (e: Exception) {
            "下载失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    private fun guessName(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        if (last.isNotEmpty() && last.contains('.')) return last.take(80)
        return "download-${System.currentTimeMillis()}.bin"
    }
}

/**
 * 查系统下载任务的状态。
 *
 * 单开一个工具而不是塞进 download_native：
 * 「发起下载」和「查进度」是两次动作，参数不一样，
 * 混在一个工具里模型容易把 downloadId 填到 url 上。
 */
class DownloadStatusTool(private val ctx: Context) : Tool {

    override val name = "download_status"

    override val description = """查系统下载任务的进度。
downloadId 是 download（mode=system）返回的那个任务号。
状态：进行中 / 已完成 / 失败。
已完成的话会把文件复制到 App 目录，这样就能 share 分享了。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("downloadId", JSONObject().apply {
                        put("type", "number")
                        put("description", "download 用 mode=system 时返回的任务号")
                    })
                })
                put("required", JSONArray().put("downloadId"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val id = args.optLong("downloadId", -1L)
        if (id < 0) return "错误：downloadId 不对，要用 download_native 返回的那个数字"

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return "错误：这个系统没有下载服务"

        return try {
            val q = DownloadManager.Query().setFilterById(id)
            dm.query(q).use { c: Cursor ->
                if (!c.moveToFirst()) return "错误：没有这个下载任务（$id）"

                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                val name = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val saved = copyToAppDir(id, name)
                        if (saved == null) {
                            "下载完成：$name\n" +
                                "位置：$uri\n" +
                                "但复制到 App 目录失败了，文件还在手机的「下载」目录里，用户自己能找到。"
                        } else {
                            "下载完成：$name（${File(saved).length()} 字节）\n" +
                                "已复制到 App 目录，可以直接 share 分享：\n$saved"
                        }
                    }
                    DownloadManager.STATUS_FAILED -> "下载失败（原因码 $reason）"
                    else -> {
                        val pct = if (total > 0) "（${done * 100 / total}%）" else ""
                        "还在下载$pct，已下载 $done / $total 字节。"
                    }
                }
            }
        } catch (e: Exception) {
            "查询失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    /**
     * 把下载好的文件复制到 App 私有目录。
     *
     * 为什么要复制：公共下载目录的文件，share 工具的 FileProvider 覆盖不到
     * （file_paths.xml 只开了 files-path）。复制一份到 filesDir，
     * share 就能直接用了，用户那边公共目录那份也还在。
     */
    private fun copyToAppDir(downloadId: Long, title: String?): String? {
        return try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val contentUri = dm.getUriForDownloadedFile(downloadId) ?: return null
            val name = (title ?: "downloaded").substringAfterLast('/')
                .replace("..", "_").trim().ifEmpty { "download-$downloadId.bin" }
            val dir = File(ctx.filesDir, "agent/saved")
            dir.mkdirs()
            val out = File(dir, name)
            ctx.contentResolver.openInputStream(contentUri)?.use { input ->
                out.outputStream().use { o -> input.copyTo(o) }
            } ?: return null
            out.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
