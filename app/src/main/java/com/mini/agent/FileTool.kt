package com.mini.agent

import android.content.Context
import android.util.Base64
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 二进制文件能力：file_write（写）+ download（下载）。
 *
 * 为什么单独开一个工具，而不改 save：
 * save 是「写文本」的老工具，模型已经形成习惯（内容直接给字符串）。
 * 往里塞 base64 参数，模型多半会一头雾水然后乱填。
 * 分开写、各自描述清楚，模型选工具时不容易错。
 *
 * base64 是绕不开的：模型的输出通道是文本，要传字节只能靠它编码。
 */

/** 写文件：文本或 base64 二进制 */
class FileWriteTool(private val ctx: Context) : Tool {

    override val name = "file_write"
    override val description = """写一个本地文件，能写文本也能写二进制。
文本：给 content（普通字符串），encoding 留空或填 text。
二进制：给 contentBase64（base64 字符串），encoding 填 base64 —— 图片、PDF、docx、xlsx、zip 这类。
filename 给文件名（例 图.png）或相对路径（例 agent/www/a.js）；不带斜杠就存到 agent/saved/。
写完读回核对大小，返回「写 N 字节、读回 N 字节」；不一致说明没写全，要重写。
要发给用户：先 file_write 再 share。
别用 run_js 的 bridge 写二进制——那个只收文本，会把字节写坏。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("filename", JSONObject().apply {
                        put("type", "string")
                        put("description", "文件名或相对路径，例 图.png / note.md")
                    })
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "文本内容（encoding 为 text 时用）")
                    })
                    put("contentBase64", JSONObject().apply {
                        put("type", "string")
                        put("description", "base64 编码的二进制内容（encoding 为 base64 时用）")
                    })
                    put("encoding", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("text").put("base64"))
                        put("description", "text（默认）或 base64")
                    })
                    put("overwrite", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "文件已存在时是否覆盖，默认 true")
                    })
                })
                put("required", JSONArray().put("filename"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val rawName = args.optString("filename").trim()
        if (rawName.isEmpty()) return "错误：filename 不能为空"

        val encoding = args.optString("encoding").trim().lowercase()
        val isBase64 = encoding == "base64"

        val text = args.optString("content")
        val b64 = args.optString("contentBase64")

        if (isBase64 && b64.isBlank()) return "错误：encoding=base64 时必须给 contentBase64。"
        if (!isBase64 && text.isBlank() && b64.isBlank()) {
            return "错误：没给内容。要么给 content，要么给 contentBase64。"
        }

        // base64 内容可能被模型带上换行和 data: 前缀，先洗一遍
        val payload = if (isBase64) {
            b64.substringAfterLast(',').replace(Regex("\\s"), "")
        } else {
            text
        }

        val bytes = try {
            if (isBase64) Base64.decode(payload, Base64.DEFAULT)
            else payload.toByteArray(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return "错误：base64 解不开（${e.message ?: ""}）。检查内容是不是完整、别漏了尾部字符。"
        }

        if (bytes.isEmpty()) return "错误：内容是空的，写了也是空文件。"

        return try {
            val f = resolve(rawName)
            if (f.exists() && !args.optBoolean("overwrite", true)) {
                return "文件已存在：${f.name}（${f.length()} 字节）。要覆盖就加 overwrite=true。"
            }
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)

            // 读回核对，别只看没抛异常就宣布成功
            val back = if (f.exists()) f.length() else -1L
            "已写入 ${f.name}（${bytes.size} 字节，读回 $back 字节，" +
                (if (back == bytes.size.toLong()) "一致）" else "不一致，可能没写全）") +
                "\n路径：${f.absolutePath}"
        } catch (e: Exception) {
            "写入失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    private fun resolve(filename: String): File {
        var name = filename.trim().replace("\\", "/")
        name = name.replace("..", "_").trimStart('/')
        if (name.isEmpty()) name = "file-${System.currentTimeMillis()}"
        val rel = if (name.contains('/')) name else "agent/saved/$name"
        val f = File(ctx.filesDir, rel)
        val base = ctx.filesDir.canonicalFile
        if (!f.canonicalFile.path.startsWith(base.path)) throw SecurityException("路径越界")
        return f
    }
}

/** 下载网络文件到本地（二进制安全） */
class DownloadTool(private val ctx: Context) : Tool {

    override val name = "download"
    override val description = """把一个网址下载成本地文件，二进制安全（图片、PDF、安装包、zip 都行）。
用：把网上的图片/报告/资料存到手机；配合 share 分享给用户。
url 要给完整的 http(s) 地址。
filename 可选：不给就按网址推断名字；给了就按给的存。
下完用 share 分享，或者告诉用户文件在哪。
mode 可选：auto（默认，小文件直接下，大文件自动转系统下载器）/ direct（一律直接下）/ system（一律走系统下载器，后台下，进度通知）。
一般不用管 mode，auto 就够。
下不了的：需要登录的页面、要验证码的直链、返回 HTML 错误页的地址（这种会存成 .html）。"""

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
                    put("mode", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("auto").put("direct").put("system"))
                        put("description", "下载方式，默认 auto：普通文件直接下，太大的文件自动转系统下载器")
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

        val mode = args.optString("mode", "auto").trim().ifEmpty { "auto" }

        // mode=system：直接转交给系统下载器
        if (mode == "system") {
            return NativeDownloadTool(ctx).execute(args)
        }

        // mode=auto：明显是大件的东西，直接丢给系统下载器
        // 理由：OkHttp 这条路是同步读进内存的，几百 MB 的安装包/视频会直接把 App 撑爆；
        // 而系统下载器是流式落盘、后台跑、App 被杀也不断。
        if (mode == "auto" && isBigFileByUrl(url)) {
            return NativeDownloadTool(ctx).execute(args)
        }

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) MiniAgent/0.1")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return "下载失败：HTTP ${resp.code}"
                }
                val body = resp.body ?: return "下载失败：响应是空的"

                // 大小保护：50MB 封顶，手机上再大也没意义
                val declared = body.contentLength()
                if (declared > 50L * 1024 * 1024) {
                    return "文件太大（${declared / 1024 / 1024} MB），超过 50 MB 上限。"
                }

                // auto 模式：响应头说这个文件超过 10MB，改走系统下载器
                // （这一步只读了响应头，body 还没读，切换没有额外浪费）
                if (mode == "auto" && declared > 10L * 1024 * 1024) {
                    return NativeDownloadTool(ctx).execute(args)
                }

                val bytes = body.bytes()
                if (bytes.isEmpty()) return "下载失败：文件是空的"

                val name = args.optString("filename").trim().ifEmpty { guessName(url, resp.header("Content-Type")) }
                val f = resolve(name)
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)

                "已下载 ${f.name}（${bytes.size} 字节，${resp.header("Content-Type") ?: "未知类型"}）\n" +
                    "路径：${f.absolutePath}\n" +
                    "要发给用户就再调 share。"
            }
        } catch (e: Exception) {
            "下载失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    /**
 * 从网址后缀判断是不是「大件」。
 * 这些类型基本都是几十 MB 到几 GB，同步读进内存会出事。
 */
private fun isBigFileByUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    val ext = path.substringAfterLast('.', "")
    return ext in setOf(
        "apk", "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
        "mp4", "mkv", "mov", "avi", "flv", "wmv", "rmvb", "m4v",
        "iso", "bin", "img", "exe", "dmg", "pkg", "deb", "rpm"
    )
}

/** 从网址末尾或 Content-Type 猜文件名 */    private fun guessName(url: String, contentType: String?): String {
        val path = url.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        if (last.isNotEmpty() && last.contains('.')) {
            return last.take(80)
        }
        // 按 Content-Type 补扩展名
        val ext = when {
            contentType == null -> ""
            contentType.contains("png") -> ".png"
            contentType.contains("jpeg") || contentType.contains("jpg") -> ".jpg"
            contentType.contains("gif") -> ".gif"
            contentType.contains("webp") -> ".webp"
            contentType.contains("svg") -> ".svg"
            contentType.contains("pdf") -> ".pdf"
            contentType.contains("zip") -> ".zip"
            contentType.contains("html") -> ".html"
            contentType.contains("json") -> ".json"
            contentType.contains("text") -> ".txt"
            else -> ""
        }
        return "download-${System.currentTimeMillis()}$ext"
    }

    private fun resolve(filename: String): File {
        var name = filename.trim().replace("\\", "/")
        name = name.replace("..", "_").trimStart('/')
        if (name.isEmpty()) name = "download-${System.currentTimeMillis()}"
        val rel = if (name.contains('/')) name else "agent/saved/$name"
        val f = File(ctx.filesDir, rel)
        val base = ctx.filesDir.canonicalFile
        if (!f.canonicalFile.path.startsWith(base.path)) throw SecurityException("路径越界")
        return f
    }
}
