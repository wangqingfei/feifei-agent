package com.mini.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 用系统自带的 WebView 跑 JS —— 零体积、无解释器。
 *
 * 只做纯计算：没有网络、没有文件、没有 DOM。
 * 需要取数据就先用 web_fetch，再把结果作为 input 传进来处理。
 *
 * 性能取舍（v0.63）：WebView 是个吃内存的东西（几十 MB），以前 App 一启动就建、一直不释放，
 * 哪怕一次 run_js 都没用过。现在改成：
 *   - 懒加载：启动只记 Context，第一次 run_js 才真正建 WebView
 *   - 空闲回收：连续 90 秒没用就 destroy，下次用再重建
 * 代价是第一次 run_js 要等 WebView 建起来（几百毫秒），后续使用和原来一样快。
 *
 * 已知风险：JS 死循环会卡住所在线程。调用方在后台线程等待，
 * 超时后返回提示，但 WebView 内部那个循环要等系统回收。
 */
object JsRunner {

    private var appCtx: Context? = null
    private var webView: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastUsed = 0L

    /** 空闲这么久就销毁 WebView，省内存 */
    private const val IDLE_RELEASE_MS = 90_000L

    /** 启动时调用，只记 Context，不再建 WebView */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /** 现在表示「已初始化、能按需建引擎」，不再表示「WebView 正活着」 */
    fun available(): Boolean = appCtx != null

    @Synchronized
    fun run(code: String, input: String, timeoutMs: Long = 15_000L): String {
        val w = ensureWebView() ?: return "JS 引擎没准备好（需要先打开一次 App 主界面）"
        lastUsed = System.currentTimeMillis()
        scheduleIdleRelease()

        val script = buildScript(code, input)
        val latch = CountDownLatch(1)
        var result = "执行超时"

        main.post {
            try {
                w.evaluateJavascript(script) { raw ->
                    result = decode(raw)
                    latch.countDown()
                }
            } catch (e: Exception) {
                result = "执行失败：${e.message}"
                latch.countDown()
            }
        }

        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result
        else "执行超时（15 秒）。可能是死循环，写得简单点再试。"
    }

    // ---------- WebView 生命周期 ----------

    /** 拿现成的；没有就在主线程现建一个（首次会多等几百毫秒） */
    private fun ensureWebView(): WebView? {
        webView?.let { return it }
        val app = appCtx ?: return null

        val latch = CountDownLatch(1)
        main.post {
            try {
                create(app)
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(3, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
        }
        return webView
    }

    private fun create(app: Context) {
        try {
            val w = WebView(app)
            w.settings.javaScriptEnabled = true
            w.settings.allowFileAccess = false
            w.settings.allowContentAccess = false
            w.settings.blockNetworkLoads = true
            w.addJavascriptInterface(JsBridge(app), "bridge")
            w.loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "UTF-8", null)
            webView = w
        } catch (e: Exception) {
            webView = null
        }
    }

    private val releaseTask = Runnable { releaseIfIdle() }

    private fun scheduleIdleRelease() {
        main.removeCallbacks(releaseTask)
        main.postDelayed(releaseTask, IDLE_RELEASE_MS)
    }

    private fun releaseIfIdle() {
        if (System.currentTimeMillis() - lastUsed >= IDLE_RELEASE_MS) {
            val w = webView ?: return
            webView = null
            main.post { try { w.destroy() } catch (e: Exception) {} }
        }
    }

    // ---------- 执行 ----------

    private fun buildScript(code: String, input: String): String {
        val inputJson = JSONObject.quote(input)
        val codeJson = JSONObject.quote(code)
        return """
            (function(){
              try{
                var input = $inputJson;
                var fn = new Function("input", $codeJson);
                var r = fn(input);
                var v = (typeof r === "string") ? r : JSON.stringify(r);
                return JSON.stringify({ok:true, v: v === undefined ? "" : v});
              }catch(e){
                return JSON.stringify({ok:false, e: String((e && e.message) || e)});
              }
            })()
        """.trimIndent()
    }

    /** evaluateJavascript 回来的是「JSON 编码后的字符串」，要解两层 */
    private fun decode(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return "（没有返回值）"
        return try {
            val inner = JSONTokener(raw).nextValue()
            val text = if (inner is String) inner else raw
            val obj = JSONObject(text)
            if (obj.optBoolean("ok", false)) {
                obj.optString("v").ifEmpty { "（返回空）" }
            } else {
                "JS 报错：" + obj.optString("e")
            }
        } catch (e: Exception) {
            raw.take(2000)
        }
    }
}
