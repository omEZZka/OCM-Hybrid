package app.opencodemob

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Sandboxed host for an external engine's own web dashboard (Hermes / TITAN).
 *
 * These dashboards are third-party web apps served from loopback. They get a WebView with no
 * `JavascriptInterface` at all — exactly like [PreviewActivity] — so a dashboard (or anything
 * it loads) can never reach the app's native bridge, key store or file helpers. Loopback
 * cleartext is the only exception to the app-wide HTTPS rule and it is scoped to 127.0.0.1.
 */
class EngineActivity : ComponentActivity() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val id = intent?.getStringExtra("engine").orEmpty()
        val spec = Engines.byId(id)
        val port = intent?.getIntExtra("port", 0) ?: 0
        if (spec == null || port <= 0) { finish(); return }
        val url = "http://127.0.0.1:$port/"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D0D0F.toInt())
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF141417.toInt())
            setPadding(12, 10, 12, 10)
        }
        val close = Button(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#E4E4E7"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = spec.title
            setTextColor(Color.parseColor("#E4E4E7"))
            textSize = 14f
            maxLines = 1
        }
        val reload = Button(this).apply {
            text = "⟳"
            setTextColor(Color.parseColor("#E4E4E7"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { web?.reload() }
        }
        bar.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(reload, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false; max = 100; progress = 0
            visibility = ProgressBar.VISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6))

        val w = WebView(this).apply {
            setBackgroundColor(0xFF0D0D0F.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress >= 100) ProgressBar.GONE else ProgressBar.VISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                    // Never let a dashboard pull a non-loopback cleartext URL: the app-wide policy
                    // forbids it and it would silently bypass the HTTPS rule.
                    if (r.url.scheme == "http" && r.url.host != "127.0.0.1" && r.url.host != "localhost") {
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                            java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u: Uri = r.url
                    if (u.host == "127.0.0.1" || u.host == "localhost") return false
                    if (u.scheme == "http" || u.scheme == "https") {
                        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                        return true
                    }
                    return true
                }
            }
            // no addJavascriptInterface on purpose: a third-party dashboard must not reach OC.*
        }
        web = w
        root.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        w.loadUrl(url)

        onBackPressedDispatcher.addCallback(this) {
            val view = w
            if (view != null && view.canGoBack()) view.goBack() else finish()
        }
    }

    override fun onDestroy() {
        val w = web
        try { (w?.parent as? ViewGroup)?.removeView(w) } catch (_: Exception) {}
        try { w?.destroy() } catch (_: Exception) {}
        web = null
        super.onDestroy()
    }

    companion object {
        fun open(a: Activity, id: String, port: Int) {
            a.startActivity(Intent(a, EngineActivity::class.java)
                .putExtra("engine", id).putExtra("port", port))
        }
    }
}