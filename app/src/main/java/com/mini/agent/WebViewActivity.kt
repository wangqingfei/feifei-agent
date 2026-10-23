package com.mini.agent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 内置浏览器。
 * 两种用法：
 * - 给 port + path：预览 web_app 起的本地服务
 * - 给 url：打开对话里点到的外部链接
 */
class WebViewActivity : Activity() {

    private lateinit var web: WebView
    private var currentUrl = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val port = intent.getIntExtra("port", 0)
        val path = intent.getStringExtra("path") ?: ""
        val external = intent.getStringExtra("url").orEmpty()

        val target = when {
            external.isNotEmpty() -> external
            port > 0 -> "http://127.0.0.1:$port/$path"
            else -> ""
        }

        if (target.isEmpty()) {
            setContentView(TextView(this).apply {
                text = "没有可打开的地址"
                gravity = Gravity.CENTER
            })
            return
        }
        currentUrl = target

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }

        // 顶部一条：返回 + 域名 + 用外部浏览器打开
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(10), dp(6))
            setBackgroundColor(getColor(R.color.surface))
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(getColor(R.color.ink))
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { finish() }
        }

        val title = TextView(this).apply {
            text = hostOf(target)
            textSize = 13f
            setTextColor(getColor(R.color.muted))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { finish() }
        }

        val outer = TextView(this).apply {
            text = "浏览器"
            textSize = 12.5f
            setTextColor(getColor(R.color.accent))
            setPadding(dp(10), dp(5), dp(4), dp(5))
            setOnClickListener { openExternal() }
        }

        bar.addView(back)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(outer)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(web, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        web.loadUrl(target)
    }

    private fun hostOf(url: String): String = try {
        Uri.parse(url).host ?: url
    } catch (e: Exception) {
        url
    }

    private fun openExternal() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
        } catch (e: Exception) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    /** 页面内还能后退就后退，退到头了再关页面 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack() else finish()
    }

    override fun onDestroy() {
        try {
            web.destroy()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
