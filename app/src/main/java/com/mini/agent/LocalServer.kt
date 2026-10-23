package com.mini.agent

import android.content.Context
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简 HTTP 服务器：只服务 files/agent/www/ 目录，只监听 127.0.0.1。
 *
 * 只绑本机，外部访问不到——想看网页就用 App 内的 WebView 打开。
 * 要局域网访问得改 bind 地址，那属于另一个安全级别，没做。
 */
object LocalServer {

    /** 固定的首选端口，写死在文档里，用户可以直接敲 */
    const val PREFERRED_PORT = 8848

    private var serverSocket: ServerSocket? = null
    private var port = 0
    private val running = AtomicBoolean(false)

    fun rootDir(ctx: Context) = File(ctx.filesDir, "agent/www").apply { mkdirs() }

    @Synchronized
    fun start(ctx: Context): Int {
        if (running.get() && port > 0) return port

        // 优先用固定端口，方便用户直接在浏览器地址栏敲；被占用再退到随机
        val ss = try {
            ServerSocket(PREFERRED_PORT, 20, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        }
        serverSocket = ss
        port = ss.localPort
        running.set(true)

        Thread {
            while (running.get()) {
                try {
                    val client = ss.accept()
                    Thread { handle(client, ctx) }.start()
                } catch (e: Exception) {
                    break
                }
            }
        }.apply { isDaemon = true; name = "local-http" }.start()

        return port
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        serverSocket = null
        port = 0
    }

    fun currentPort(): Int = if (running.get()) port else 0

    // ---------- 请求处理 ----------

    private fun handle(socket: Socket, ctx: Context) {
        try {
            socket.soTimeout = 5000

            val input = socket.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            // 读完请求头，否则连接不干净
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val rawPath = parts[1]

            val path = rawPath.substringBefore('?').removePrefix("/").ifEmpty { "index.html" }
            val file = resolve(ctx, path)

            if (file == null || !file.exists() || file.isDirectory) {
                respond(socket, 404, "text/plain; charset=utf-8",
                    "404 Not Found\n\n找个 index.html 放进 www 目录试试。".toByteArray())
            } else {
                respond(socket, 200, mimeOf(file.name), file.readBytes())
            }
        } catch (e: Exception) {
            // 单个请求失败不能拖垮服务器
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun resolve(ctx: Context, path: String): File? {
        val root = rootDir(ctx)
        val clean = path.replace("..", "_").trimStart('/')
        val f = File(root, clean)
        return if (f.canonicalPath.startsWith(root.canonicalPath)) f else null
    }

    private fun respond(socket: Socket, code: Int, type: String, body: ByteArray) {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(code).append(' ')
            .append(if (code == 200) "OK" else "Not Found").append("\r\n")
            .append("Content-Type: ").append(type).append("\r\n")
            .append("Content-Length: ").append(body.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n\r\n")
            .toString()

        val out = socket.getOutputStream()
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".html") || name.endsWith(".htm") -> "text/html; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".js") -> "application/javascript; charset=utf-8"
        name.endsWith(".json") -> "application/json; charset=utf-8"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".ico") -> "image/x-icon"
        else -> "text/plain; charset=utf-8"
    }
}
