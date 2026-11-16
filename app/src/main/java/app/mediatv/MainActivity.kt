package app.mediatv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * The TV shell, reduced to the two things a web page cannot do by itself: forward the
 * D-pad to the page (a WebView eats arrow keys) and open the system speech recogniser.
 *
 * All of the chrome — the left rail, the ask sheet, the rows — is drawn by the page on the
 * NUC, so the interface can change without shipping a new APK. This file stays small on
 * purpose: no support libraries, no third-party code.
 */
class MainActivity : Activity() {

    companion object {
        private const val HOME_URL = "http://192.168.1.121:3012/tv"
        private const val APP_PREFIX = "http://192.168.1.121:3012/"
        private const val SPEECH_REQUEST = 101
        private const val HOLD_MS = 520L
        private const val TAG = "MediaTV"
    }

    private lateinit var web: WebView

    // The page tells us when its ask sheet is up, because Back has to close the sheet
    // before it leaves the page, and that has to be decided synchronously.
    @Volatile
    private var sheetOpen = false

    // The page decides what a press and a hold mean; the shell only tells them apart. Its
    // own pages get this treatment, Jellyfin's do not.
    @Volatile
    private var appPage = false
    private var held = false
    private val holdJob = Runnable {
        held = true
        callJs("window.tvLongPress && window.tvLongPress()")
    }

    /** What the page is allowed to ask the shell for. */
    inner class Bridge {
        @JavascriptInterface
        fun sheet(open: Boolean) {
            sheetOpen = open
        }

        @JavascriptInterface
        fun listen() {
            runOnUiThread { startListening() }
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "page: " + message)
        }

        /** Playback is native: the page names a stream, the platform decodes it. */
        @JavascriptInterface
        fun play(url: String, title: String, who: String, resumeMs: Int) {
            if (!url.startsWith(APP_PREFIX + "api/stream/")) return
            runOnUiThread {
                startActivity(Intent(this@MainActivity, PlayerActivity::class.java)
                    .putExtra("url", url).putExtra("title", title)
                    .putExtra("who", who).putExtra("resume", resumeMs))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    // Jellyfin's client opens in its own window: the player it starts owns
                    // the page it is in, so Back has to lead out of that window instead.
                    if (request.isForMainFrame && url.contains(":8096/")) {
                        startActivity(Intent(this@MainActivity, JellyfinActivity::class.java)
                            .putExtra("url", url))
                        return true
                    }
                    return false
                }

                // The page is the thing the remote should drive, so it takes the focus as
                // soon as it is on screen.
                override fun onPageFinished(view: WebView, url: String) {
                    appPage = url.startsWith(APP_PREFIX)
                    view.requestFocus()
                    view.postDelayed({ view.requestFocus() }, 200)
                    android.webkit.CookieManager.getInstance().flush()
                    // The WebView focuses its first focusable element as it settles, which on
                    // this page is the rail; ask the page to put focus where it belongs.
                    view.postDelayed({
                        if (appPage) callJs("window.tvClaim && window.tvClaim()")
                    }, 250)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d(TAG, "console: " + msg.message() + " (line " + msg.lineNumber() + ")")
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "MediaTV")
            WebView.setWebContentsDebuggingEnabled(true)
            // The chosen profile lives in a cookie; a WebView only writes its cookie store
            // to disk when asked, so a killed app forgot who was watching.
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        }

        setContentView(web)
        web.loadUrl(HOME_URL)
    }

    private fun callJs(script: String) {
        web.evaluateJavascript(script, null)
    }

    /**
     * The remote's microphone. An app cannot read the remote mic directly, but the system
     * recogniser can — the page asks for it and gets the transcript back.
     */
    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "What should we watch?")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            startActivityForResult(intent, SPEECH_REQUEST)
        } catch (missing: Exception) {
            // A box with no recogniser (an emulator, some builds): say so in the sheet
            // rather than looking like it is listening and never answering.
            Log.w(TAG, "no speech recogniser: " + missing.message)
            callJs("window.tvNoMic && window.tvNoMic()")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPEECH_REQUEST) return
        // A cancelled dialog is the viewer changing their mind: do not fall back to the
        // keyboard they just declined.
        if (resultCode != RESULT_OK) return
        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (said.isNullOrBlank()) {
            callJs("window.tvNoMic && window.tvNoMic()")
            return
        }
        Log.d(TAG, "heard: " + said)
        callJs("window.tvHeard && window.tvHeard(" + JSONObject.quote(said) + ")")
    }

    private fun direction(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        else -> null
    }

    // A WebView consumes a D-pad key before the Activity sees it, so onKeyDown never runs
    // for an arrow while the page has focus. dispatchKeyEvent comes first, which is the
    // only place this can be intercepted.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The centre button is where "press" and "hold" differ, so it is taken here and
        // handed to the page either as tvLongPress() or as tvSelect().
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && appPage) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    held = false
                    web.postDelayed(holdJob, HOLD_MS)
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                web.removeCallbacks(holdJob)
                if (!held) callJs("window.tvSelect && window.tvSelect()")
                held = false
                return true
            }
            return true
        }
        val way = direction(event.keyCode)
        // Our own page owns the D-pad outright: requiring the WebView to hold focus meant
        // a press just after load went to the rail instead of the focused card.
        if (way != null && appPage) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                callJs("window.tvNav && window.tvNav('" + way + "')")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Back closes the ask sheet, then walks back through the page, and only then leaves.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (sheetOpen) {
                callJs("window.tvCloseAsk && window.tvCloseAsk()")
                return true
            }
            if (web.canGoBack()) {
                web.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // Anything that takes focus away from the page (the IME, a system dialog) gives it back
    // when the window is ours again.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && appPage) web.requestFocus()
    }

    override fun onDestroy() {
        web.removeCallbacks(holdJob)
        web.destroy()
        super.onDestroy()
    }
}
