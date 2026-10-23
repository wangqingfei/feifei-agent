package com.mini.agent

import android.content.Context
import android.webkit.JavascriptInterface
import java.io.File

/**
 * 文件桥：JS 里直接操作 App 私有目录。
 *
 * 根目录是 filesDir，也就是 /data/data/com.mini.agent/files/，
 * 里面有 agent/sessions、agent/saved、agent/www、memory.md 等全部数据。
 *
 * 越界路径（跳出 filesDir）一律拒绝——不是限制你，是 Android 会抛
 * SecurityException 让 App 崩掉。返回错误字符串比崩溃好。
 */
class JsBridge(private val ctx: Context) {

    private fun root(): File = ctx.filesDir

    private fun safe(path: String): File? {
        val base = root()
        val clean = path.replace("..", "_").trimStart('/')
        if (clean.isEmpty()) return null
        val f = File(base, clean)
        return if (f.canonicalPath.startsWith(base.canonicalPath)) f else null
    }

    @JavascriptInterface
    fun read(path: String): String {
        val f = safe(path) ?: return "错误：路径非法"
        if (!f.exists()) return "错误：不存在 $path"
        if (f.isDirectory) return "错误：$path 是目录，用 list"
        if (f.length() > 2_000_000) return "错误：文件太大（${f.length()} 字节），超过 2MB"
        return try {
            f.readText()
        } catch (e: Exception) {
            "错误：${e.message}"
        }
    }

    @JavascriptInterface
    fun write(path: String, content: String): String {
        val f = safe(path) ?: return "错误：路径非法"
        return try {
            f.parentFile?.mkdirs()
            f.writeText(content)
            "ok 已写入 $path（${content.length} 字符，${f.length()} 字节）"
        } catch (e: Exception) {
            "错误：${e.message}"
        }
    }

    /** 列目录，传空串列根目录 */
    @JavascriptInterface
    fun list(dir: String): String {
        val f = if (dir.isBlank()) root() else (safe(dir) ?: return "错误：路径非法")
        if (!f.isDirectory) return "错误：$dir 不是目录"
        return try {
            f.listFiles()?.sortedBy { it.name }?.joinToString("\n") {
                val suffix = if (it.isDirectory) "/" else " (${it.length()}B)"
                it.name + suffix
            } ?: ""
        } catch (e: Exception) {
            "错误：${e.message}"
        }
    }

    @JavascriptInterface
    fun remove(path: String): String {
        val f = safe(path) ?: return "错误：路径非法"
        return try {
            if (f.isDirectory) f.deleteRecursively() else f.delete()
            "ok 已删除 $path"
        } catch (e: Exception) {
            "错误：${e.message}"
        }
    }

    @JavascriptInterface
    fun exists(path: String): Boolean = safe(path)?.exists() ?: false

    @JavascriptInterface
    fun mkdir(path: String): String {
        val f = safe(path) ?: return "错误：路径非法"
        return if (f.mkdirs() || f.isDirectory) "ok" else "错误：创建失败"
    }

    @JavascriptInterface
    fun size(path: String): Long = safe(path)?.length() ?: -1L

    /** 拼绝对路径，方便 JS 里展示 */
    @JavascriptInterface
    fun abs(path: String): String = safe(path)?.absolutePath ?: ""
}
