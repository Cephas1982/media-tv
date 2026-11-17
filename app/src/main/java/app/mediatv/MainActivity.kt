package app.mediatv

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

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
        private const val RETRY_MS = 5000L
        private const val LOAD_GRACE_MS = 8000L
        private const val TAG = "MediaTV"
    }

    private lateinit var web: WebView
    private lateinit var root: FrameLayout

    // The platform's own failure page ("Webpage not available … ERR_CONNECTION_TIMED_OUT")
    // is what a viewer saw for a whole evening: the page could not be reached at launch and
    // the WebView never tries again. A load that fails now puts this screen up instead, and
    // it keeps retrying until the box is reachable, so a dropped connection looks like the
    // app waiting rather than like debugging output.
    private lateinit var offline: LinearLayout
    private var loadFailed = false
    // A load that never finishes is the failure this box actually has: when the page cannot
    // be reached the connection hangs rather than refuses, and the platform says nothing for
    // half a minute. The watchdog turns that silence into the same waiting screen.
    private var pageShown = false
    // Which attempt is the current one, and when a load last finished. A late error from an
    // attempt the viewer already moved past must not cover a page that has since arrived.
    private var loadStartedAt = 0L
    private var lastLoadedAt = 0L
    // onPageFinished also fires for the platform's own error page, so a finish only counts
    // as a page when no failure was reported during that same attempt.
    private var lastErrorAt = 0L
    private val watchdog = Runnable {
        if (!pageShown) {
            Log.w(TAG, "nothing finished in " + LOAD_GRACE_MS + "ms")
            showOffline()
        }
    }
    private val retryHandler = Handler(Looper.getMainLooper())
    private val retry = Runnable {
        if (loadFailed) {
            Log.d(TAG, "retrying " + HOME_URL)
            web.loadUrl(HOME_URL)
        }
    }

    // While a speech sheet is up it owns the screen, so this window keeps its hands off the
    // focus: taking focus back from a recogniser that draws itself over us is one way a
    // sheet closes by itself a second after it opened.
    @Volatile
    private var listening = false

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
                    Log.d(TAG, "loaded " + url + " (our page: " + appPage + ")")
                    view.requestFocus()
                    view.postDelayed({ view.requestFocus() }, 200)
                    android.webkit.CookieManager.getInstance().flush()
                    // The WebView focuses its first focusable element as it settles, which on
                    // this page is the rail; ask the page to put focus where it belongs.
                    view.postDelayed({
                        if (appPage) callJs("window.tvClaim && window.tvClaim()")
                    }, 250)
                    // A clean finish is the proof that the box is reachable: trust it and
                    // take the waiting screen down, whatever an earlier attempt said.
                    if (url.startsWith(APP_PREFIX) && lastErrorAt < loadStartedAt) {
                        pageShown = true
                        lastLoadedAt = SystemClock.uptimeMillis()
                        retryHandler.removeCallbacks(watchdog)
                        showOnline()
                    }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (!url.startsWith(APP_PREFIX)) return
                    // A retry starts from the waiting screen; it stays up until the page
                    // really arrives, so a second hang never flashes black at the viewer.
                    pageShown = false
                    loadStartedAt = SystemClock.uptimeMillis()
                    retryHandler.removeCallbacks(watchdog)
                    retryHandler.postDelayed(watchdog, LOAD_GRACE_MS)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (!request.isForMainFrame) return
                    Log.w(TAG, "load failed: " + error.errorCode + " " + error.description)
                    showOffline()
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(
                    view: WebView,
                    errorCode: Int,
                    description: String,
                    failingUrl: String,
                ) {
                    if (!failingUrl.startsWith(APP_PREFIX)) return
                    Log.w(TAG, "load failed: " + errorCode + " " + description)
                    showOffline()
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    response: WebResourceResponse,
                ) {
                    if (!request.isForMainFrame || response.statusCode < 500) return
                    Log.w(TAG, "load failed: HTTP " + response.statusCode)
                    showOffline()
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

        offline = buildOfflineView()
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0b0d11"))
            addView(web, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(offline, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        web.loadUrl(HOME_URL)
        retryHandler.postDelayed(watchdog, LOAD_GRACE_MS)
    }

    /**
     * What fills the screen when the page cannot be reached: one plain sentence, a spinner,
     * and the promise that it will go away by itself. No address, no error code — a viewer
     * should never have to read one.
     */
    private fun buildOfflineView(): LinearLayout {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0b0d11"))
            setPadding(dp(64), dp(64), dp(64), dp(64))
            visibility = View.GONE
            isFocusable = false
        }
        val title = TextView(this).apply {
            text = "Can't reach the library"
            setTextColor(Color.parseColor("#f2f4f8"))
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            isFocusable = false
        }
        val hint = TextView(this).apply {
            text = "Trying again — this goes away by itself."
            setTextColor(Color.parseColor("#8b93a3"))
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            isFocusable = false
        }
        val spinner = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(28) }
            isFocusable = false
        }
        box.addView(title)
        box.addView(hint)
        box.addView(spinner)
        return box
    }

    private fun showOffline() {
        // The current attempt already produced a page: a stale failure is not news.
        if (lastLoadedAt > loadStartedAt) {
            Log.d(TAG, "ignoring a failure from an earlier attempt")
            return
        }
        loadFailed = true
        lastErrorAt = SystemClock.uptimeMillis()
        if (this::offline.isInitialized) offline.visibility = View.VISIBLE
        retryHandler.removeCallbacks(retry)
        retryHandler.postDelayed(retry, RETRY_MS)
    }

    private fun showOnline() {
        loadFailed = false
        retryHandler.removeCallbacks(retry)
        if (this::offline.isInitialized && offline.visibility != View.GONE) {
            Log.d(TAG, "the library is reachable again")
            offline.visibility = View.GONE
        }
    }

    private fun retryNow() {
        retryHandler.removeCallbacks(retry)
        pageShown = false
        retryHandler.removeCallbacks(watchdog)
        retryHandler.postDelayed(watchdog, LOAD_GRACE_MS)
        web.loadUrl(HOME_URL)
    }

    private fun callJs(script: String) {
        web.evaluateJavascript(script, null)
    }

    /**
     * The remote's microphone. An app cannot read the remote mic directly, but the system
     * recogniser can — the page asks for it and gets the transcript back.
     *
     * This box does not run Google's recogniser: Lee set a transcription service as the
     * handler, and a service that draws its own sheet decides for itself when the speaker
     * has finished. The length and silence extras below are how the platform asks for
     * patience; a service that honours them keeps the sheet up long enough to say a
     * sentence rather than closing between two words.
     */
    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "What should we watch?")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        }
        Log.d(TAG, "speech: " + describeRecogniser(intent))
        listening = true
        try {
            startActivityForResult(intent, SPEECH_REQUEST)
        } catch (missing: Exception) {
            // A box with no recogniser (an emulator, some builds): say so in the sheet
            // rather than looking like it is listening and never answering.
            listening = false
            Log.w(TAG, "no speech recogniser: " + missing.message)
            callJs("window.tvNoMic && window.tvNoMic()")
        }
    }

    /** Which service would answer, and whether the box thinks it has one at all. */
    private fun describeRecogniser(intent: Intent): String {
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(this) }.getOrDefault(false)
        val handler = runCatching {
            intent.resolveActivity(packageManager)?.flattenToShortString()
        }.getOrNull()
        val candidates = runCatching {
            packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        return "available=" + available + " handler=" + (handler ?: "none") +
            " candidates=" + candidates
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPEECH_REQUEST) return
        listening = false
        web.requestFocus()
        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        // A sheet that closed itself and a viewer who changed their mind are the same event
        // from here, and doing nothing on either one is what made this look broken: the
        // screen went back to what it was with no sign that anything had been asked.
        if (resultCode != RESULT_OK) {
            Log.d(TAG, "speech: closed with result " + resultCode +
                " results=" + (data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.size ?: 0))
            callJs("window.tvHeardNothing && window.tvHeardNothing()")
            return
        }
        if (said.isNullOrBlank()) {
            Log.d(TAG, "speech: came back without a transcript")
            callJs("window.tvHeardNothing && window.tvHeardNothing()")
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
        // With no page on screen the remote has nothing to drive: the centre button asks
        // again now instead of waiting for the next automatic try, and Back still leaves.
        if (loadFailed && event.keyCode != KeyEvent.KEYCODE_BACK) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.action == KeyEvent.ACTION_DOWN) {
                retryNow()
            }
            return true
        }
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

    // Back closes whatever the page has open before it walks back through the page, and
    // only then leaves. The page keeps that list itself — a card's menu, the season list,
    // the profile picker, selection mode — and tvBack() answers with what it took.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && appPage) {
            // Asking the page is asynchronous, so the press is consumed here and the
            // fall-through happens in the callback: returning false now would let the
            // Activity finish before the page had a chance to answer.
            web.evaluateJavascript("window.tvBack ? window.tvBack() : ''") { answer ->
                Log.d(TAG, "back: the page answered " + answer)
                if (!pageTookIt(answer)) stepBack()
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Log.d(TAG, "back: not our page (appPage=" + appPage + "), the platform has it")
        }
        return super.onKeyDown(keyCode, event)
    }

    /** evaluateJavascript answers in JSON: `""` and `null` both mean the page ignored it. */
    private fun pageTookIt(answer: String?): Boolean =
        answer != null && answer.isNotBlank() && answer != "null" &&
            answer.trim('"').isNotBlank()

    /**
     * One page back, or out of the app.
     *
     * Deliberately not web.canGoBack(): on this box it answers false for a page the page
     * itself navigated to. Standing on a title page, the page's own history is two deep and
     * history.back() walks back to the board, while canGoBack() insists there is nothing
     * behind it — and trusting that is what made Back leave the app from a title page. The
     * page knows how deep it is, so it is asked.
     */
    private fun stepBack() {
        web.evaluateJavascript("String(window.history ? window.history.length : 1)") { depth ->
            val behind = depth?.trim('"')?.toIntOrNull() ?: 1
            when {
                behind > 1 -> {
                    Log.d(TAG, "back: " + behind + " entries behind this page, going back one")
                    web.evaluateJavascript("history.back()", null)
                }
                web.canGoBack() -> web.goBack()
                else -> {
                    Log.d(TAG, "back: nothing behind this page, leaving the app")
                    finish()
                }
            }
        }
    }

    // Anything that takes focus away from the page (the IME, a system dialog) gives it back
    // when the window is ours again — except while the speech sheet is up, which is another
    // window with its own claim on the focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && appPage && !listening) web.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // A box that slept through the outage wakes to a screen that already knows the load
        // failed; ask again as soon as the window is visible instead of waiting for the timer.
        if (this::web.isInitialized && loadFailed) retryNow()
    }

    override fun onDestroy() {
        listening = false
        web.removeCallbacks(holdJob)
        retryHandler.removeCallbacks(retry)
        retryHandler.removeCallbacks(watchdog)
        web.destroy()
        super.onDestroy()
    }
}
