package app.mediatv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import org.json.JSONObject

/**
 * The TV shell. It owns three surfaces and nothing else: Jellyfin for watching,
 * Media Desk for asking, and a Recent page for what arrived or is arriving.
 *
 * Deliberately framework-only: no support libraries, no third-party code, so the
 * APK stays a few hundred kilobytes and the app cannot drift out of date on its own.
 */
class MainActivity : Activity() {

    companion object {
        // All three are served by the NUC. The TV draws the frame; the logic stays there.
        private const val WATCH_URL = "http://192.168.1.121:8096/web/"
        private const val ASK_URL = "http://192.168.1.121:3012/"
        private const val RECENT_URL = "http://192.168.1.121:3012/recent"
        private const val SPEECH_REQUEST = 101
        private const val TAG = "MediaTV"
    }

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111318"))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(24, 12, 24, 12)
        }

        val watch = menuButton("Watch") { load(WATCH_URL) }
        bar.addView(watch)
        bar.addView(menuButton("Ask") { load(ASK_URL) })
        bar.addView(menuButton("Recent") { load(RECENT_URL) })
        bar.addView(menuButton("Talk") { listen() })
        bar.addView(menuButton("Reload") { web.reload() })

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#111318"))
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
                // The page is the thing the remote should drive, so it takes the focus
                // as soon as it is on screen.
                override fun onPageFinished(view: WebView, url: String) {
                    view.requestFocus()
                }
            }
            // Page console goes to logcat, so a page-side bug is visible with adb logcat.
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d(TAG, "console: " + msg.message() + " (line " + msg.lineNumber() + ")")
                    return true
                }
            }
            WebView.setWebContentsDebuggingEnabled(true)
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        watch.requestFocus()
        load(WATCH_URL)
    }

    private fun menuButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { onClick() }
            setPadding(28, 8, 28, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 16 }
        }

    private fun load(url: String) {
        Log.d(TAG, "load " + url)
        web.loadUrl(url)
    }

    /**
     * Media Desk's box is a React-controlled field: assigning .value leaves React's
     * state stale, so this runs the native value setter and then dispatches input and
     * change, which is what a real keystroke would produce.
     */
    private fun inject(text: String) {
        val quoted = JSONObject.quote(text)
        val js = "(function(){" +
            "var t=" + quoted + ";" +
            "var e=document.activeElement;" +
            "if(!e||!(e.tagName==='INPUT'||e.tagName==='TEXTAREA')){e=document.querySelector('textarea, input[type=text], input:not([type])');}" +
            "if(!e){return 'nofield';}" +
            "e.focus();" +
            "var proto=(e.tagName==='TEXTAREA')?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
            "var setter=Object.getOwnPropertyDescriptor(proto,'value').set;" +
            "setter.call(e,t);" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));" +
            "e.dispatchEvent(new Event('change',{bubbles:true}));" +
            "return 'ok';})()"
        web.evaluateJavascript(js) { result -> Log.d(TAG, "inject -> " + result) }
    }

    /**
     * The remote's microphone through the system recognizer. Apps cannot capture the
     * remote mic directly, but they can ask the recognizer that can, and put the
     * transcript into the focused field.
     */
    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "What should we watch?")
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            startActivityForResult(intent, SPEECH_REQUEST)
        } catch (failure: Exception) {
            Log.w(TAG, "no speech recognizer: " + failure.message)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPEECH_REQUEST || resultCode != RESULT_OK) return
        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        Log.d(TAG, "heard: " + said)
        inject(said)
    }

    // A WebView consumes arrow keys for its own scrolling, which on a television means the
    // page moves instead of the row. While the page has focus the D-pad is therefore
    // forwarded to the page's own navigation; the toolbar keeps normal focus movement.
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
        val way = direction(event.keyCode)
        if (way != null && web.hasFocus()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                web.evaluateJavascript("window.tvNav && window.tvNav('" + way + "')", null)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Back closes what the page has open before it closes the app, so a viewer never
        // lands on the launcher by accident mid-film.
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
