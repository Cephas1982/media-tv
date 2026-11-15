package app.mediatv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Jellyfin's own client, in its own window.
 *
 * It used to be loaded into the app's WebView, which meant that once something started
 * playing there was no way back: the player owns the page and Back only stops the film.
 * As a separate activity, Back always lands on the app again, and the browsing state of
 * each side survives.
 */
class JellyfinActivity : Activity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: "http://192.168.1.121:8096/web/"

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0b0d11"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
            }
            webChromeClient = object : WebChromeClient() {}
            WebView.setWebContentsDebuggingEnabled(true)
        }
        setContentView(web)
        web.loadUrl(url)
    }

    // Back leaves Jellyfin rather than trapping the viewer in it.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && !web.canGoBack()) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
