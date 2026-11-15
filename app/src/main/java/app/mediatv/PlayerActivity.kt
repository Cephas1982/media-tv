package app.mediatv

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Playback with the platform decoder, and our own controls over it.
 *
 * The WebView cannot demux Matroska, which is most of this library, so the video is played
 * by Android against the stream the NUC proxies. Everything around it is ours: a progress
 * bar, play/pause, ten-second skips, the audio and subtitle tracks the file carries, the
 * subtitle files that live beside it, the position saved back to Jellyfin for whoever is
 * watching, and the next episode starting by itself.
 */
class PlayerActivity : Activity() {

    private companion object {
        const val ACCENT = "#6ea8ff"
        const val INK = "#f4f6fa"
        const val DIM = "#b3bcc9"
        const val PANEL = "#0b0d11"
        const val HIDE_AFTER_MS = 4500L
        const val TICK_MS = 400L
        const val SAVE_EVERY_MS = 30_000L
        const val SKIP_MS = 10_000
        const val APP = "http://192.168.1.121:3012"
        const val TAG = "MediaTV"
    }

    private lateinit var surface: SurfaceView
    private lateinit var overlay: FrameLayout
    private lateinit var sheet: LinearLayout
    private lateinit var trackList: LinearLayout
    private lateinit var bar: ProgressView
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var titleView: TextView
    private lateinit var playButton: TextView
    private lateinit var audioButton: TextView
    private lateinit var subsButton: TextView

    private var player: MediaPlayer? = null
    private var title = ""
    private var itemId = ""
    private var who = "together"
    private var durationMs = 0
    private var controlsVisible = true
    private var picking: TrackKind? = null
    private var selectedTrack = -1
    private var externalSubs: List<Pair<String, File>> = emptyList()
    private var nextUp: JSONObject? = null
    private var lastSaved = 0L
    private val main = Handler(Looper.getMainLooper())

    private enum class TrackKind { AUDIO, SUBTITLE }

    private val ticker = object : Runnable {
        override fun run() {
            refreshProgress()
            if (controlsVisible) main.postDelayed(this, TICK_MS)
        }
    }

    private val hideJob = Runnable { if (player?.isPlaying == true) hideControls() }

    private val saver = object : Runnable {
        override fun run() {
            reportProgress()
            main.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        title = intent.getStringExtra("title") ?: ""
        itemId = url.substringAfterLast('/').substringBefore('?')
        who = intent.getStringExtra("who") ?: "together"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildOverlay()
        setContentView(root)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = open(itemId, title, holder)
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
        showControls()
        main.postDelayed(saver, SAVE_EVERY_MS)
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
        bar = ProgressView(this).apply {
            isFocusable = true
            setOnFocusChangeListener { _, focused -> if (focused) flash() }
        }
        timing.addView(elapsed, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        timing.addView(bar, LinearLayout.LayoutParams(0, dp(22), 1f)
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
        view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = 14 }
        return view
    }

    private fun pillBackground(focused: Boolean) = GradientDrawable().apply {
        setColor(Color.parseColor(if (focused) ACCENT else "#20262f"))
        cornerRadius = 999f
        if (!focused) setStroke(2, Color.parseColor("#2a323d"))
    }

    /** A scrub bar drawn by hand: the platform's is too thin to see at ten feet. */
    private inner class ProgressView(context: Context) : View(context) {
        var fraction = 0f
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5b6678") }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(ACCENT) }

        override fun onDraw(canvas: Canvas) {
            val height = dp(9).toFloat()
            val middle = this.height / 2f
            val radius = height / 2f
            canvas.drawRoundRect(0f, middle - radius, this.width.toFloat(), middle + radius,
                                 radius, radius, track)
            if (fraction > 0f) {
                canvas.drawRoundRect(0f, middle - radius, this.width * fraction, middle + radius,
                                     radius, radius, fill)
            }
        }
    }

    // ------------------------------------------------------------------ opening a title
    /**
     * Load a title: ask the server for its subtitle files and for what follows it, then
     * hand everything to the platform player. Both answers are cheap and cached, and they
     * are what let the player show external subtitles and start the next episode itself.
     */
    private fun open(id: String, label: String, holder: SurfaceHolder) {
        itemId = id
        title = label
        titleView.text = label
        Thread {
            val subs = fetchSubtitles(id)
            val next = fetchNext(id)
            runOnUiThread { start(id, subs, next, holder) }
        }.start()
    }

    private fun start(id: String, subs: List<Pair<String, File>>, next: JSONObject?,
                      holder: SurfaceHolder) {
        externalSubs = subs
        nextUp = next
        releasePlayer()
        val media = MediaPlayer()
        player = media
        try {
            for ((_, file) in subs) {
                runCatching { media.addTimedTextSource(file.absolutePath, MediaPlayer.MEDIA_MIMETYPE_TEXT_VTT) }
            }
            media.setDataSource(APP + "/api/stream/" + id)
            media.setSurface(holder.surface)
            media.setOnPreparedListener {
                durationMs = it.duration
                duration.text = clock(durationMs)
                val resume = intent.getIntExtra("resume", 0)
                if (resume > 5000 && resume < durationMs - 20000) it.seekTo(resume)
                applyDefaultTracks()
                it.start()
                updatePlayButton()
                showControls()
            }
            media.setOnCompletionListener { onFinished() }
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

    private fun releasePlayer() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
    }

    /** Prefer an English audio track; leave subtitles off unless someone asks for them. */
    private fun applyDefaultTracks() {
        val media = player ?: return
        val tracks = runCatching { media.trackInfo }.getOrNull() ?: return
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
        if (media.isPlaying) {
            media.pause()
            reportProgress()
        } else {
            media.start()
        }
        updatePlayButton()
    }

    private fun skip(delta: Int) {
        val media = player ?: return
        val target = (media.currentPosition + delta).coerceIn(0, maxOf(0, durationMs - 1000))
        media.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST_SYNC)
        refreshProgress()
    }

    private fun updatePlayButton() {
        playButton.text = if (player?.isPlaying == true) "Pause" else "Play"
    }

    private fun refreshProgress() {
        val media = player ?: return
        val position = runCatching { media.currentPosition }.getOrDefault(0)
        if (durationMs > 0) {
            bar.fraction = (position.toFloat() / durationMs).coerceIn(0f, 1f)
            bar.invalidate()
        }
        elapsed.text = clock(position)
        updatePlayButton()
    }

    private fun clock(ms: Int): String {
        val total = ms / 1000
        return if (total >= 3600)
            String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
        else String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    // ------------------------------------------------------------------ what follows
    private fun onFinished() {
        reportProgress(played = true)
        val next = nextUp
        val id = next?.optString("id") ?: ""
        if (id.isNotEmpty()) {
            val label = next.optString("title")
            main.removeCallbacks(saver)
            main.post(saver)
            open(id, label, surface.holder)
        } else {
            finish()
        }
    }

    // ------------------------------------------------------------------ talking to the server
    private fun reportProgress(played: Boolean = false) {
        val media = player ?: return
        val position = runCatching { media.currentPosition }.getOrDefault(0)
        if (!played && position < 15_000) return
        lastSaved = System.currentTimeMillis()
        val body = JSONObject()
            .put("item", itemId)
            .put("ticks", position.toLong() * 10_000L)
            .put("played", played)
            .put("who", who)
            .toString()
        Thread {
            runCatching {
                val connection = URL(APP + "/api/progress").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
                connection.responseCode
                connection.disconnect()
            }.onFailure { Log.w(TAG, "progress post failed: " + it.message) }
        }.start()
    }

    private fun fetchNext(id: String): JSONObject? = runCatching {
        val text = get(APP + "/api/next/" + id)
        val json = JSONObject(text)
        if (json.optString("id").isEmpty()) null else json
    }.getOrNull()

    private fun fetchSubtitles(id: String): List<Pair<String, File>> = runCatching {
        val list = JSONArray(get(APP + "/api/subs/" + id))
        val out = mutableListOf<Pair<String, File>>()
        for (i in 0 until list.length()) {
            val row = list.getJSONObject(i)
            val label = row.optString("label")
            val file = File(cacheDir, "sub-" + id + "-" + row.optInt("index") + ".vtt")
            if (!file.exists() || file.length() == 0L) {
                file.writeBytes(getBytes(APP + row.optString("url")))
            }
            if (file.length() > 0) out.add(label to file)
        }
        out
    }.getOrDefault(emptyList())

    private fun get(url: String): String =
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 4000
            readTimeout = 8000
            val body = inputStream.bufferedReader().use { it.readText() }
            disconnect()
            body
        }

    private fun getBytes(url: String): ByteArray =
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 4000
            readTimeout = 15000
            val body = inputStream.readBytes()
            disconnect()
            body
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
        val textIndices = tracks.indices.filter {
            tracks[it].trackType in setOf(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE,
                                          MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)
        }
        val choices = tracks.indices.filter { tracks[it].trackType in wanted }
        if (choices.isEmpty()) {
            titleView.text = if (kind == TrackKind.AUDIO) "No other audio tracks" else "No subtitles in this file"
            titleView.setTextColor(Color.parseColor(DIM))
            showControls()
            return
        }

        picking = kind
        trackList.removeAllViews()
        trackList.addView(sheetHeading(if (kind == TrackKind.AUDIO) "Audio" else "Subtitles"))
        trackList.addView(trackItem("Off", selectedTrack < 0) {
            runCatching { media.selectTrack(-1) }
            selectedTrack = -1
            closeTracks()
        })
        choices.forEach { index ->
            val info = tracks[index]
            // Files we added come after the embedded tracks, in the order we asked for them.
            val external = if (info.trackType != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                val position = textIndices.indexOf(index)
                val embedded = textIndices.size - externalSubs.size
                if (position >= embedded && position - embedded < externalSubs.size)
                    externalSubs[position - embedded].first else null
            } else null
            val label = external ?: describe(info, index)
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
        if (currentFocus == null) playButton.requestFocus()
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
        player ?: return super.onKeyDown(keyCode, event)

        if (picking != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { closeTracks(); return true }
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (controlsVisible) { hideControls(); return true }
                reportProgress()
                finish()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { skip(-30_000); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { skip(30_000); showControls(); return true }
        }

        if (!controlsVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { skip(-SKIP_MS); showControls(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { skip(SKIP_MS); showControls(); return true }
            }
            return super.onKeyDown(keyCode, event)
        }

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
            if (currentFocus === bar) { hideControls(); return true }
            bar.requestFocus()
            flash()
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
        reportProgress()
        updatePlayButton()
    }

    override fun onDestroy() {
        reportProgress()
        main.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }
}
