package app.mediatv

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

/**
 * Playback with the platform decoder, and our own controls over it.
 *
 * The WebView cannot demux Matroska, which is most of this library, so the video is played
 * by Android against the stream the NUC proxies. Everything around it is ours: a progress
 * bar, a play/pause, ten-second skips, and the audio and subtitle tracks the file carries.
 * No third-party libraries, same rule as the rest of this app.
 */
class PlayerActivity : Activity() {

    private companion object {
        const val ACCENT = "#6ea8ff"
        const val INK = "#f4f6fa"
        const val DIM = "#b3bcc9"
        const val PANEL = "#0b0d11"
        const val HIDE_AFTER_MS = 4500L
        const val TICK_MS = 400L
        const val SKIP_MS = 10_000
    }

    private lateinit var surface: SurfaceView
    private lateinit var overlay: FrameLayout
    private lateinit var sheet: LinearLayout          // the bottom control panel
    private lateinit var trackList: LinearLayout      // audio / subtitle chooser
    private lateinit var bar: ProgressBar
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var titleView: TextView
    private lateinit var playButton: TextView
    private lateinit var audioButton: TextView
    private lateinit var subsButton: TextView

    private var player: MediaPlayer? = null
    private var title = ""
    private var durationMs = 0
    private var selectedTrack = -1
    private var controlsVisible = true
    private var picking: TrackKind? = null
    private val main = Handler(Looper.getMainLooper())

    private enum class TrackKind { AUDIO, SUBTITLE }

    private val ticker = object : Runnable {
        override fun run() {
            refreshProgress()
            if (controlsVisible) main.postDelayed(this, TICK_MS)
        }
    }

    private val hideJob = Runnable { if (player?.isPlaying == true) hideControls() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        title = intent.getStringExtra("title") ?: ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#00000000")) }
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildOverlay()
        setContentView(root)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = start(url, holder)
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
        showControls()
    }

    // ------------------------------------------------------------------ the controls
    private fun buildOverlay() {
        titleView = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor(INK))
            textSize = 20f
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setPadding(48, 34, 48, 34)
        }
        overlay.addView(titleView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START))

        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 22, 48, 30)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(PANEL))
                cornerRadius = 26f
                setStroke(2, Color.parseColor("#232c37"))
            }
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        params.setMargins(40, 0, 40, 36)
        overlay.addView(sheet, params)

        val timing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        elapsed = timeText("0:00")
        duration = timeText("0:00")
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(ACCENT))
            // The unfilled track has to read too: on a dark panel #2a313c is invisible.
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#46505f"))
            isFocusable = true
            setOnFocusChangeListener { _, focused -> if (focused) flash() }
        }
        timing.addView(elapsed, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // A bar with real height: at ten feet the default one pixed tall reads as nothing.
        timing.addView(bar, LinearLayout.LayoutParams(0, dp(14), 1f)
            .apply { marginStart = dp(18); marginEnd = dp(18) })
        timing.addView(duration, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.addView(timing)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 14, 0, 0)
        }
        playButton = pill("Play") { togglePlay() }
        buttons.addView(playButton)
        buttons.addView(pill("\u00ab 10s") { skip(-SKIP_MS) })
        buttons.addView(pill("10s \u00bb") { skip(SKIP_MS) })
        audioButton = pill("Audio") { openTracks(TrackKind.AUDIO) }
        buttons.addView(audioButton)
        subsButton = pill("Subtitles") { openTracks(TrackKind.SUBTITLE) }
        buttons.addView(subsButton)
        buttons.addView(pill("Close") { finish() })
        sheet.addView(buttons)

        trackList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(16, 16, 16, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#171b22"))
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#232c37"))
            }
        }
        overlay.addView(trackList, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER).apply { setMargins(40, 40, 40, 40) })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun timeText(value: String) = TextView(this).apply {
        text = value
        setTextColor(Color.parseColor(DIM))
        textSize = 15f
        typeface = android.graphics.Typeface.MONOSPACE
    }

    /** A control that reads as one thing: rounded, quiet until focused, then accent. */
    private fun pill(label: String, action: () -> Unit): TextView {
        val view = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(INK))
            textSize = 17f
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(30, 20, 30, 20)
            background = pillBackground(false)
            setOnClickListener { action(); showControls() }
            setOnFocusChangeListener { _, focused ->
                setBackground(pillBackground(focused))
                if (focused) flash()
            }
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = 14 }
        view.layoutParams = params
        return view
    }

    private fun pillBackground(focused: Boolean) = GradientDrawable().apply {
        setColor(Color.parseColor(if (focused) ACCENT else "#20262f"))
        cornerRadius = 999f
        if (!focused) setStroke(2, Color.parseColor("#2a323d"))
    }

    // ------------------------------------------------------------------ playback
    private fun start(url: String, holder: SurfaceHolder) {
        val media = MediaPlayer()
        player = media
        try {
            media.setDataSource(url)
            media.setSurface(holder.surface)
            media.setOnPreparedListener {
                durationMs = it.duration
                duration.text = clock(durationMs)
                applyDefaultTracks()
                it.start()
                updatePlayButton()
                showControls()
            }
            media.setOnCompletionListener { finish() }
            media.setOnErrorListener { _, what, extra ->
                titleView.text = "This file will not play here ($what/$extra)"
                titleView.setTextColor(Color.parseColor("#ff9b9b"))
                true
            }
            media.prepareAsync()
        } catch (failure: Exception) {
            titleView.text = "Could not open this title: " + failure.message
            titleView.setTextColor(Color.parseColor("#ff9b9b"))
        }
    }

    /** Prefer an English audio track and no subtitles unless the file forces one on. */
    private fun applyDefaultTracks() {
        val media = player ?: return
        val tracks = try { media.trackInfo } catch (e: Exception) { null } ?: return
        tracks.forEachIndexed { index, info ->
            if (info.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                val language = info.language ?: "und"
                if (language.startsWith("en") && index != 0) {
                    if (runCatching { media.selectTrack(index) }.isSuccess) selectedTrack = index
                }
            }
        }
    }

    private fun togglePlay() {
        val media = player ?: return
        if (media.isPlaying) media.pause() else media.start()
        updatePlayButton()
    }

    private fun skip(delta: Int) {
        val media = player ?: return
        val target = (media.currentPosition + delta).coerceIn(0, maxOf(0, durationMs - 1000))
        media.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST_SYNC)
        refreshProgress()
    }

    private fun updatePlayButton() {
        val playing = player?.isPlaying == true
        playButton.text = if (playing) "Pause" else "Play"
    }

    private fun refreshProgress() {
        val media = player ?: return
        val position = runCatching { media.currentPosition }.getOrDefault(0)
        if (durationMs > 0) {
            bar.progress = (1000L * position / durationMs).toInt().coerceIn(0, 1000)
        }
        elapsed.text = clock(position)
        updatePlayButton()
    }

    private fun clock(ms: Int): String {
        val total = ms / 1000
        return if (total >= 3600) String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
        else String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    // ------------------------------------------------------------------ tracks
    private fun openTracks(kind: TrackKind) {
        val media = player ?: return
        val tracks = runCatching { media.trackInfo }.getOrNull() ?: return
        val wanted = when (kind) {
            TrackKind.AUDIO -> setOf(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO)
            TrackKind.SUBTITLE -> setOf(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE,
                                        MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)
        }
        val choices = tracks.mapIndexedNotNull { index, info ->
            if (info.trackType in wanted) index to info else null
        }
        if (choices.isEmpty()) {
            titleView.text = if (kind == TrackKind.AUDIO) "No other audio tracks" else "No subtitles in this file"
            titleView.setTextColor(Color.parseColor(DIM))
            showControls()
            return
        }

        picking = kind
        trackList.removeAllViews()
        trackList.addView(sheetHeading(if (kind == TrackKind.AUDIO) "Audio" else "Subtitles"))
        trackList.addView(trackItem("Off", selected = selectedTrack < 0) {
            runCatching { media.selectTrack(-1) }
            selectedTrack = -1
            closeTracks()
        })
        choices.forEach { (index, info) ->
            val label = describe(info, index)
            trackList.addView(trackItem(label, selectedTrack == index) {
                runCatching { media.selectTrack(index) }
                selectedTrack = index
                closeTracks()
            })
        }
        trackList.visibility = View.VISIBLE
        sheet.visibility = View.GONE
        titleView.visibility = View.GONE
        trackList.getChildAt(1)?.requestFocus()
    }

    private fun describe(info: MediaPlayer.TrackInfo, index: Int): String {
        val language = info.language?.takeIf { it.isNotBlank() && it != "und" } ?: "Track ${index + 1}"
        val codec = runCatching { info.format?.getString("mime") }.getOrNull()
        return if (codec != null) "$language  \u00b7  " + codec.substringAfter('/') else language
    }

    private fun sheetHeading(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor(DIM))
        textSize = 13f
        letterSpacing = 0.14f
        setPadding(18, 10, 18, 12)
    }

    private fun trackItem(label: String, selected: Boolean, action: () -> Unit): TextView {
        val view = TextView(this).apply {
            text = (if (selected) "\u2713  " else "     ") + label
            setTextColor(Color.parseColor(if (selected) ACCENT else INK))
            textSize = 18f
            isFocusable = true
            isClickable = true
            setPadding(22, 16, 30, 16)
            background = pillBackground(false)
            setOnClickListener { action() }
            setOnFocusChangeListener { _, focused -> setBackground(pillBackground(focused)) }
        }
        view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = 6 }
        return view
    }

    private fun closeTracks() {
        picking = null
        trackList.visibility = View.GONE
        trackList.removeAllViews()
        sheet.visibility = View.VISIBLE
        titleView.visibility = View.VISIBLE
        showControls()
    }

    // ------------------------------------------------------------------ the overlay
    private fun showControls() {
        controlsVisible = true
        overlay.visibility = View.VISIBLE
        main.removeCallbacks(hideJob)
        main.removeCallbacks(ticker)
        main.post(ticker)
        if (player?.isPlaying == true) main.postDelayed(hideJob, HIDE_AFTER_MS)
    }

    private fun hideControls() {
        if (picking != null) return
        controlsVisible = false
        overlay.visibility = View.GONE
        main.removeCallbacks(hideJob)
        main.removeCallbacks(ticker)
    }

    private fun flash() {
        main.removeCallbacks(hideJob)
        if (player?.isPlaying == true) main.postDelayed(hideJob, HIDE_AFTER_MS + 2000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val media = player ?: return super.onKeyDown(keyCode, event)

        if (picking != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { closeTracks(); return true }
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (controlsVisible) { hideControls(); return true }
                finish()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { skip(-30_000); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { skip(30_000); showControls(); return true }
        }

        if (!controlsVisible) {
            // Any press wakes the controls rather than acting blind.
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    showControls()
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && currentFocus == null) playButton.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { skip(-SKIP_MS); showControls(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { skip(SKIP_MS); showControls(); return true }
            }
            return super.onKeyDown(keyCode, event)
        }

        // The progress bar is a scrubber: left and right move through the film.
        if (currentFocus === bar) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { skip(-SKIP_MS); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { skip(SKIP_MS); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                playButton.requestFocus()
                flash()
                return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            hideControls()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val focused = currentFocus
            if (focused == null) { playButton.requestFocus(); flash(); return true }
            focused.performClick()
            flash()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            flash()
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        player?.takeIf { it.isPlaying }?.pause()
        updatePlayButton()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        super.onDestroy()
    }
}
