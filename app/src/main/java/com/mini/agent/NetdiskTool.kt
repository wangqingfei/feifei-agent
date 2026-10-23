package com.mini.agent

import android.content.Context
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 百度网盘：走开放平台 REST，不接 MCP。
 *
 * 为什么不接 MCP：官方 SSE 模式不暴露上传工具，而手机上没有 Node 进程跑不了 stdio；
 * REST 反而更全，上传三步都在。
 *
 * 鉴权：轻量版，手动粘贴 access_token（30 天有效）。没有 refresh_token，
 * 过期只能让用户重新复制——这是不建应用换来的代价，代码里不要假装能自动续。
 */
class NetdiskTool(private val ctx: Context) : Tool {

    override val name = "netdisk"
    override val description = """百度网盘：看剩余空间、列目录、搜文件、建文件夹、取下载直链、把手机上的文件传上去。
什么时候用：用户说「网盘里有什么」「搜一下网盘里的XX」「存到网盘」「还剩多少空间」。
什么时候别用：本地文件操作走 save；百度网盘以外的网盘不支持。
动作：quota 配额 / ls 列目录 / search 搜文件 / mkdir 建文件夹 / dlink 取直链 / upload 上传。
token 失效会返回明确提示，照实转告用户去「设置 → 百度网盘」换一个，不要自己猜或重试。"""

    override fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray()
                            .put("quota").put("ls").put("search")
                            .put("mkdir").put("dlink").put("upload"))
                        put("description", "动作，取值见 enum")
                    })
                    put("dir", JSONObject().apply {
                        put("type", "string")
                        put("description", "网盘目录，默认 /")
                    })
                    put("key", JSONObject().apply {
                        put("type", "string")
                        put("description", "search 的关键词")
                    })
                    put("name", JSONObject().apply {
                        put("type", "string")
                        put("description", "mkdir 的文件夹名；upload 的远端文件名（不传用本地文件名）")
                    })
                    put("fsid", JSONObject().apply {
                        put("type", "string")
                        put("description", "dlink 的文件 fs_id，ls 的结果里带")
                    })
                    put("local", JSONObject().apply {
                        put("type", "string")
                        put("description", "upload 的本地文件，相对 App 私有目录，如 agent/saved/a.md")
                    })
                })
                put("required", JSONArray().put("action"))
            })
        })
    }

    override fun execute(args: JSONObject): String {
        val token = AgentFactory.prefs(ctx).getString("netdiskToken", "").orEmpty().trim()
        if (token.isEmpty()) {
            return "还没配百度网盘。请让用户去 设置 → 百度网盘，把 access_token 粘贴进去再试。"
        }

        val action = args.optString("action").trim().lowercase()
        return try {
            when (action) {
                "quota" -> quota(token)
                "ls" -> list(token, args.optString("dir").ifBlank { "/" })
                "search" -> search(token, args.optString("key").trim(), args.optString("dir").ifBlank { "/" })
                "mkdir" -> mkdir(token, args.optString("dir").ifBlank { "/" }, args.optString("name").trim())
                "dlink" -> dlink(token, args.optString("fsid").trim())
                "upload" -> upload(token, args.optString("local").trim(), args.optString("dir").ifBlank { "/apps/飞飞" }, args.optString("name").trim())
                else -> "错误：不认识 action=$action"
            }
        } catch (e: Exception) {
            "网盘请求失败：${e.javaClass.simpleName}: ${e.message ?: ""}"
        }
    }

    // ---------- 各动作 ----------

    private fun quota(token: String): String {
        val json = obj(get("https://pan.baidu.com/api/quota?access_token=$token&checkfree=1&checkexpire=1"))
        errno(json, token)?.let { return it }
        val total = json.optLong("total")
        val used = json.optLong("used")
        val free = json.optLong("free")
        val sb = StringBuilder("百度网盘空间：\n")
        sb.append("总容量 ").append(size(total)).append("\n")
        sb.append("已用 ").append(size(used)).append("\n")
        sb.append("剩余 ").append(size(free))
        return sb.toString()
    }

    private fun list(token: String, dir: String): String {
        val d = normDir(dir)
        val url = "https://pan.baidu.com/rest/2.0/xpan/file?method=list&access_token=$token" +
                "&dir=${enc(d)}&order=name&start=0&limit=100&web=1"
        return renderList(get(url), token, d)
    }

    private fun search(token: String, key: String, dir: String): String {
        if (key.isEmpty()) return "错误：search 需要 key（搜什么）。"
        val d = normDir(dir)
        val url = "https://pan.baidu.com/rest/2.0/xpan/file?method=search&access_token=$token" +
                "&key=${enc(key)}&dir=${enc(d)}&recursion=1&web=1"
        return renderList(get(url), token, d, prefix = "搜到「$key」的结果：")
    }

    private fun mkdir(token: String, dir: String, name: String): String {
        if (name.isEmpty()) return "错误：mkdir 需要 name（文件夹名）。"
        val path = normDir(dir) + name
        val body = FormBody.Builder()
            .add("path", path)
            .add("isdir", "1")
            .add("size", "0")
            .add("block_list", "[]")
            .build()
        val json = obj(post("https://pan.baidu.com/rest/2.0/xpan/file?method=create&access_token=$token", body))
        errno(json, token)?.let { if (json.optInt("errno") != 31061) return it }
        return "已创建文件夹：$path"
    }

    private fun dlink(token: String, fsid: String): String {
        if (fsid.isEmpty()) return "错误：dlink 需要 fsid，用 ls 列一次就拿到了。"
        val url = "https://pan.baidu.com/rest/2.0/xpan/multimedia?method=filemetas&access_token=$token" +
                "&fsids=[$fsid]&dlink=1"
        val json = obj(get(url))
        errno(json, token)?.let { return it }
        val arr = json.optJSONArray("list")
        if (arr == null || arr.length() == 0) return "没拿到文件信息，fsid 可能不对。"
        val o = arr.optJSONObject(0) ?: return "没拿到文件信息。"
        val dlink = o.optString("dlink").trim()
        if (dlink.isEmpty()) return "这个文件没有直链（可能是文件夹，或需要会员权限）。"
        // 直链必须带 pan.baidu.com 的 UA，否则 403；这里把这事说给用户，浏览器可能打不开
        return "下载直链（有效期约 8 小时）：\n$dlink\n\n注意：在浏览器里打开时可能 403，需要带百度网盘 UA，或直接用网盘客户端。"
    }

    /**
     * 上传三步：precreate 报分片 → superfile2 逐片传 → create 落库。
     * 分片固定 4MB，这是百度要求的粒度，不是随便选的。
     */
    private fun upload(token: String, local: String, dir: String, name: String): String {
        if (local.isEmpty()) return "错误：upload 需要 local（本地文件路径）。"

        val f = if (local.startsWith("/")) File(local) else File(ctx.filesDir, local)
        if (!f.exists() || !f.isFile) return "本地文件不存在：${f.absolutePath}"
        if (f.length() == 0L) return "文件是空的，传上去没意义。"

        val dirPath = normDir(dir)
        val fileName = if (name.isNotBlank()) name else f.name
        val remote = "$dirPath$fileName"

        // 1. 算分片 MD5
        val chunk = CHUNK
        val blockList = JSONArray()
        RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(chunk)
            var read: Int
            while (raf.read(buf).also { read = it } > 0) {
                val md = MessageDigest.getInstance("MD5").digest(buf.copyOf(read))
                blockList.put(md.joinToString("") { "%02x".format(it) })
            }
        }
        val blocks = blockList.toString()

        // 2. 预上传
        val pre = obj(post(
            "https://pan.baidu.com/rest/2.0/xpan/file?method=precreate&access_token=$token",
            FormBody.Builder()
                .add("path", remote)
                .add("size", f.length().toString())
                .add("isdir", "0")
                .add("autoinit", "1")
                .add("block_list", blocks)
                .build()
        ))
        errno(pre, token)?.let { return it }
        val uploadId = pre.optString("uploadid").trim()
        if (uploadId.isEmpty()) return "预上传没拿到 uploadid：${pre.toString().take(200)}"

        // 3. 逐片传
        RandomAccessFile(f, "r").use { raf ->
            var seq = 0
            var offset = 0L
            val buf = ByteArray(chunk)
            var read: Int
            while (raf.read(buf).also { read = it } > 0) {
                val part = buf.copyOf(read)
                val url = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2?method=upload&access_token=$token" +
                        "&type=tmpfile&path=${enc(remote)}&uploadid=${enc(uploadId)}&partseq=$seq"
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", fileName,
                        part.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                    )
                    .build()
                val res = postRaw(url, body)
                val rj = try { JSONObject(res) } catch (e: Exception) { null }
                if (rj != null && rj.has("errno") && rj.optInt("errno") != 0) {
                    return "第 $seq 片上传失败（errno=${rj.optInt("errno")}）"
                }
                offset += read
                seq++
            }
        }

        // 4. 落库
        val create = obj(post(
            "https://pan.baidu.com/rest/2.0/xpan/file?method=create&access_token=$token",
            FormBody.Builder()
                .add("path", remote)
                .add("size", f.length().toString())
                .add("isdir", "0")
                .add("block_list", blocks)
                .add("uploadid", uploadId)
                .build()
        ))
        errno(create, token)?.let { return it }

        return "已上传：$remote（${size(f.length())}，${(blockList.length())} 个分片）"
    }

    // ---------- 公共 ----------

    private fun renderList(raw: String, token: String, dir: String, prefix: String = ""): String {
        val json = obj(raw)
        errno(json, token)?.let { return it }
        val arr = json.optJSONArray("list") ?: return "目录是空的：$dir"
        if (arr.length() == 0) return "目录是空的：$dir"

        val sb = StringBuilder(prefix.ifEmpty { "目录 $dir：" })
        sb.append("\n")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val isDir = o.optInt("isdir") == 1
            val path = o.optString("path")
            val nm = o.optString("server_filename").ifEmpty { path.substringAfterLast('/') }
            if (isDir) {
                sb.append("  ").append(nm).append("/\n")
            } else {
                sb.append("  ").append(nm)
                sb.append("  ").append(size(o.optLong("size")))
                val mtime = o.optLong("server_mtime")
                if (mtime > 0) sb.append("  ").append(date(mtime))
                sb.append("  fs_id=").append(o.optString("fs_id"))
                sb.append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    /** errno 非 0 就翻译成人话；0 返回 null */
    private fun errno(json: JSONObject, token: String): String? {
        val code = json.optInt("errno", 0)
        if (code == 0) return null
        return when (code) {
            -6 -> "百度网盘的 access_token 失效了（有效期 30 天）。请让用户去 设置 → 百度网盘 重新粘贴一个，这一步只能手动做。"
            31061 -> "已经有同名的了。"
            -7 -> "路径不合法。第三方应用上传只能放在 /apps/ 下面。"
            -9 -> "文件或目录不存在。"
            111 -> "token 已过期，需要重新授权。"
            else -> "百度网盘返回错误 errno=$code。原文：${json.toString().take(200)}"
        }
    }

    /** 字符串转 JSON，失败也不抛——工具层不能因为服务端吐了 HTML 就崩 */
    private fun obj(s: String): JSONObject = try {
        JSONObject(s)
    } catch (e: Exception) {
        JSONObject().put("errno", -1).put("msg", s.take(300))
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            return body.ifEmpty { "{\"errno\":-1,\"msg\":\"空响应 HTTP ${r.code}\"}" }
        }
    }

    private fun post(url: String, body: FormBody): String {
        return postRaw(url, body)
    }

    private fun postRaw(url: String, body: okhttp3.RequestBody): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).post(body).build()
        client.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            return try { JSONObject(s) } catch (e: Exception) {
                JSONObject().put("errno", -1).put("msg", s.take(300))
            }.toString()
        }
    }

    private fun normDir(d: String): String {
        var s = d.trim().ifEmpty { "/" }
        if (!s.startsWith("/")) s = "/$s"
        if (!s.endsWith("/")) s = "$s/"
        return s
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun size(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format("%.1f MB", b / 1048576.0)
        b >= 1024 -> String.format("%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    private fun date(sec: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(sec * 1000))

    companion object {
        private const val UA = "pan.baidu.com"
        private const val CHUNK = 4 * 1024 * 1024
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
