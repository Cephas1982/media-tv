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
import android.widget.ScrollView
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
    private lateinit var root: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var sheet: LinearLayout
    private lateinit var trackList: LinearLayout
    private lateinit var trackPanel: ScrollView
    private val controls = mutableListOf<TextView>()
    private lateinit var bar: ProgressView
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
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
    private var availableSubs: List<Triple<Int, String, String>> = emptyList()
    private var activeSub = -1              // stream index chosen by the viewer, -1 for none
    private var resumeAt = 0                // where to start, for a restart after a change
    private var firstResume = 0             // the intent's resume point, used once
    private var cues: List<Cue> = emptyList()
    private var menuIndex = 0
    private var menuActions: List<() -> Unit> = emptyList()
    private var menuRows: List<TextView> = emptyList()

    private data class Cue(val start: Int, val end: Int, val text: String)
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
        firstResume = intent.getIntExtra("resume", 0)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildOverlay()
        root.bringChildToFront(subtitleView)
        setContentView(root)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = open(itemId, title, holder, firstResume)
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
        showControls()
        main.postDelayed(saver, SAVE_EVERY_MS)
    }

    // ------------------------------------------------------------------ the controls
    private fun buildOverlay() {
        subtitleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
            setShadowLayer(10f, 0f, 3f, Color.BLACK)
            setLineSpacing(4f, 1f)
            maxWidth = resources.displayMetrics.widthPixels * 3 / 4
        }
        root.addView(subtitleView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(120) })

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
        controls.add(playButton)
        buttons.addView(playButton)
        val back = pill("\u00ab 10s") { skip(-SKIP_MS) }
        controls.add(back)
        buttons.addView(back)
        val forward = pill("10s \u00bb") { skip(SKIP_MS) }
        controls.add(forward)
        buttons.addView(forward)
        audioButton = pill("Audio") { openTracks(TrackKind.AUDIO) }
        controls.add(audioButton)
        buttons.addView(audioButton)
        subsButton = pill("Subtitles") { openTracks(TrackKind.SUBTITLE) }
        controls.add(subsButton)
        buttons.addView(subsButton)
        val close = pill("Close") { finish() }
        controls.add(close)
        buttons.addView(close)
        sheet.addView(buttons)

        trackList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        // A film can carry forty subtitle tracks; the panel scrolls rather than running
        // off the screen.
        trackPanel = ScrollView(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#171b22"))
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#232c37"))
            }
            isFocusable = false
            addView(trackList)
        }
        overlay.addView(trackPanel, FrameLayout.LayoutParams(
            dp(620), dp(760), Gravity.CENTER).apply { setMargins(40, 40, 40, 40) })
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
    private fun open(id: String, label: String, holder: SurfaceHolder, resumeMs: Int = -1) {
        itemId = id
        title = label
        titleView.text = label
        // A resume point belongs to exactly one open: reusing the intent's would seek the
        // next episode to the last one's position and end it immediately.
        resumeAt = if (resumeMs >= 0) resumeMs else 0
        // Playback starts at once. Subtitles are fetched on demand, because extracting a
        // track out of a Matroska file costs Jellyfin a minute the first time, and nobody
        // wants to wait that to watch a film.
        start(id, holder)
        Thread {
            nextUp = fetchNext(id)
            availableSubs = fetchSubtitleList(id)
        }.start()
    }

    private fun start(id: String, holder: SurfaceHolder) {
        releasePlayer()
        val media = MediaPlayer()
        player = media
        try {
            media.setDataSource(APP + "/api/stream/" + id)
            media.setSurface(holder.surface)
            media.setOnPreparedListener {
                durationMs = it.duration
                duration.text = clock(durationMs)
                applyDefaultTracks()
                it.start()
                // Seek AFTER start: a seek issued before playback begins is dropped on
                // some devices, which is why a resume quietly restarted the film.
                if (resumeAt > 5000 && resumeAt < durationMs - 20000) {
                    val target = resumeAt
                    Log.d(TAG, "resume to " + target + "ms")
                    it.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST_SYNC)
                    main.postDelayed({ runCatching { it.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST_SYNC) } }, 400)
                }
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
        if (cues.isNotEmpty()) {
            val cue = cues.firstOrNull { position >= it.start && position <= it.end }
            val wanted = cue?.text ?: ""
            if (subtitleView.text.toString() != wanted) subtitleView.text = wanted
        } else if (subtitleView.text.isNotEmpty()) {
            subtitleView.text = ""
        }
    }

    /** WebVTT in, cues out. Jellyfin emits one file per subtitle track, already in VTT. */
    /**
     * Subtitles are drawn here, not by the platform: its timed-text path stalls this
     * stream, and drawing them means changing tracks never interrupts the film.
     */
    private fun applySubtitle(index: Int) {
        Log.d(TAG, "subtitle -> " + index)
        activeSub = index
        closeTracks()
        cues = emptyList()
        subtitleView.text = ""
        if (index < 0) return
        Thread {
            val file = subtitleFile(itemId, index)
            val parsed = file?.let { parseVtt(it.readText()) } ?: emptyList()
            Log.d(TAG, "cues loaded: " + parsed.size)
            runOnUiThread { cues = parsed }
        }.start()
    }

    /** WebVTT in, cues out. Jellyfin emits one file per subtitle track, already in VTT. */
    private fun parseVtt(text: String): List<Cue> {
        val stamp = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})\.(\d{1,3})""")
        fun toMs(groups: MatchResult): Int {
            val hours = groups.groupValues[1].toIntOrNull() ?: 0
            val minutes = groups.groupValues[2].toInt()
            val seconds = groups.groupValues[3].toInt()
            val millis = groups.groupValues[4].padEnd(3, '0').toInt()
            return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
        }
        val out = mutableListOf<Cue>()
        text.replace("\r\n", "\n").split("\n\n").forEach { block ->
            val lines = block.trim().split("\n")
            val arrow = lines.indexOfFirst { it.contains("-->") }
            if (arrow < 0) return@forEach
            val times = stamp.findAll(lines[arrow]).toList()
            if (times.size < 2) return@forEach
            val body = lines.drop(arrow + 1).joinToString("\n")
                .replace(Regex("<[^>]*>"), "").trim()
            if (body.isNotEmpty()) out.add(Cue(toMs(times[0]), toMs(times[1]), body))
        }
        return out.sortedBy { it.start }
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
        advance()
    }

    // Used by the remote's next-track key: move on without claiming the episode was watched.
    private fun advance() {
        val next = nextUp
        val id = next?.optString("id") ?: ""
        if (id.isNotEmpty()) {
            val label = next?.optString("title") ?: ""
            main.removeCallbacks(saver)
            main.post(saver)
            open(id, label, surface.holder, 0)
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

    private fun fetchSubtitleList(id: String): List<Triple<Int, String, String>> = runCatching {
        val list = JSONArray(get(APP + "/api/subs/" + id))
        val out = mutableListOf<Triple<Int, String, String>>()
        for (i in 0 until list.length()) {
            val row = list.getJSONObject(i)
            out.add(Triple(row.optInt("index"), row.optString("label"), row.optString("url")))
        }
        out
    }.getOrDefault(emptyList())

    private fun subtitleFile(id: String, index: Int): File? = runCatching {
        val file = File(cacheDir, "sub-" + id + "-" + index + ".vtt")
        if (!file.exists() || file.length() == 0L) {
            val row = availableSubs.firstOrNull { it.first == index }
            val path = row?.third ?: ("/api/subs/" + id + "/" + index + ".vtt")
            file.writeBytes(getBytes(APP + path))
        }
        if (file.length() > 0) file else null
    }.getOrNull()

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
        val choices = tracks.indices.filter { tracks[it].trackType in wanted }
        if (kind == TrackKind.AUDIO && choices.isEmpty()) {
            titleView.text = "No other audio tracks"
            titleView.setTextColor(Color.parseColor(DIM))
            showControls()
            return
        }
        if (kind == TrackKind.SUBTITLE && availableSubs.isEmpty()) {
            // The list arrives in the background; if the viewer asks first, fetch it now.
            availableSubs = fetchSubtitleList(itemId)
        }

        Log.d(TAG, "tracks: " + kind + " audio=" + choices.size + " subs=" + availableSubs.size)
        picking = kind
        menuIndex = 0
        menuActions = mutableListOf()
        menuRows = mutableListOf()
        trackList.removeAllViews()
        trackList.addView(sheetHeading(if (kind == TrackKind.AUDIO) "Audio" else "Subtitles"))

        val rows = mutableListOf<TextView>()
        val actions = mutableListOf<() -> Unit>()
        menuRows = rows
        menuActions = actions

        fun addRow(label: String, selected: Boolean, action: () -> Unit) {
            val row = menuRow(label, selected)
            trackList.addView(row)
            rows.add(row)
            actions.add(action)
        }

        if (kind == TrackKind.SUBTITLE) {
            addRow("Off", activeSub < 0) { applySubtitle(-1) }
            if (availableSubs.isEmpty()) {
                addRow("No subtitle tracks in this file", false) { closeTracks() }
            }
            for ((index, label, _) in availableSubs) {
                addRow(label, activeSub == index) { applySubtitle(index) }
            }
        } else {
            addRow("Off", selectedTrack < 0) {
                runCatching { media.selectTrack(-1) }
                selectedTrack = -1
                closeTracks()
            }
            choices.forEach { index ->
                addRow(describe(tracks[index], index), selectedTrack == index) {
                    runCatching { media.selectTrack(index) }
                    selectedTrack = index
                    closeTracks()
                }
            }
            menuIndex = if (selectedTrack >= 0) choices.indexOf(selectedTrack) + 1 else 0
        }
        menuIndex = menuIndex.coerceIn(0, maxOf(0, menuRows.size - 1))
        paintMenu()
        trackPanel.visibility = View.VISIBLE
        sheet.visibility = View.GONE
        titleView.visibility = View.GONE
    }

    /** A row that the menu paints, not one the focus system owns. */
    private fun menuRow(label: String, selected: Boolean): TextView = TextView(this).apply {
        text = (if (selected) "\u2713  " else "     ") + label
        setTextColor(Color.parseColor(INK))
        textSize = 18f
        setPadding(22, 16, 30, 16)
        background = pillBackground(false)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = 6 }
    }

    private fun paintMenu() {
        menuRows.forEachIndexed { index, row ->
            val chosen = index == menuIndex
            row.setBackgroundColor(if (chosen) Color.parseColor("#232b36") else Color.TRANSPARENT)
            row.setTextColor(Color.parseColor(if (chosen) ACCENT else INK))
            if (chosen) trackPanel.smoothScrollTo(0, maxOf(0, row.top - dp(120)))
        }
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

    private fun closeTracks() {
        picking = null
        trackPanel.visibility = View.GONE
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
        focusControl(controls.indexOfFirst { it === currentFocus })
        if (player?.isPlaying == true) main.postDelayed(hideJob, HIDE_AFTER_MS)
    }

    /** Put focus on a control by index, or on the first one when the index is not one. */
    private fun focusControl(index: Int) {
        val target = controls.getOrNull(index) ?: controls.firstOrNull() ?: return
        target.requestFocus()
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

    // The track menu takes its keys here: once nothing is focusable, onKeyDown stops being
    // called for the centre button, and a menu that ignores its own button is useless.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (picking == null) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> closeTracks()
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                menuIndex = (menuIndex + step).coerceIn(0, maxOf(0, menuRows.size - 1))
                paintMenu()
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                menuActions.getOrNull(menuIndex)?.invoke()
            }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        player ?: return super.onKeyDown(keyCode, event)

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
            KeyEvent.KEYCODE_MEDIA_NEXT -> { advance(); return true }
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
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { focusControl(0); flash(); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { hideControls(); return true }
            return super.onKeyDown(keyCode, event)
        }

        // The buttons are one row, so left and right step through them by hand.
        val seat = controls.indexOfFirst { it === currentFocus }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val step = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            val next = if (seat < 0) 0 else (seat + step).coerceIn(0, controls.size - 1)
            focusControl(next)
            flash()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            bar.requestFocus()
            flash()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val focused = currentFocus
            if (focused == null || seat < 0) { focusControl(0); flash(); return true }
            focused.performClick()
            flash()
            return true
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
