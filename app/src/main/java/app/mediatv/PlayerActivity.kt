package app.mediatv

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecList
import android.media.MediaFormat
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
/**
 * The control icons, drawn rather than typed. Android renders arrows and circles as
 * coloured emoji, which is why the first version of these controls looked like clip-art.
 */
private enum class Glyph { PLAY, PAUSE, BACK10, FORWARD10, AUDIO, SUBTITLES, NEXT, CLOSE }

private class IconButton(context: Context, private var glyph: Glyph, private val label: String) :
    View(context) {

    var onPress: (() -> Unit)? = null
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#f4f6fa")
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#f4f6fa") }
    private var focusedNow = false

    init {
        isFocusable = true
        isClickable = true
        contentDescription = label
        setOnClickListener { onPress?.invoke() }
        setOnFocusChangeListener { _, focused ->
            focusedNow = focused
            ink.color = Color.parseColor(if (focused) "#0b0d11" else "#f4f6fa")
            fill.color = Color.parseColor(if (focused) "#0b0d11" else "#f4f6fa")
            invalidate()
        }
    }

    fun setGlyph(next: Glyph) { glyph = next; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = size * 0.28f
        if (focusedNow) {
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6ea8ff") }
            canvas.drawCircle(cx, cy, size * 0.46f, halo)
        }
        when (glyph) {
            Glyph.PLAY -> {
                val path = Path()
                path.moveTo(cx - r * 0.8f, cy - r)
                path.lineTo(cx + r, cy)
                path.lineTo(cx - r * 0.8f, cy + r)
                path.close()
                canvas.drawPath(path, fill)
            }
            Glyph.PAUSE -> {
                val barW = r * 0.55f
                canvas.drawRect(cx - r * 0.9f, cy - r, cx - r * 0.9f + barW, cy + r, fill)
                canvas.drawRect(cx + r * 0.35f, cy - r, cx + r * 0.35f + barW, cy + r, fill)
            }
            Glyph.BACK10, Glyph.FORWARD10 -> {
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                val sweep = if (glyph == Glyph.BACK10) -280f else 280f
                canvas.drawArc(rect, -90f, sweep, false, ink)
                // the arrow head at the open end of the circle
                val head = r * 0.42f
                val tipX = if (glyph == Glyph.BACK10) cx - r else cx + r
                val dir = if (glyph == Glyph.BACK10) -1f else 1f
                canvas.drawLine(tipX, cy, tipX - dir * head, cy - head, ink)
                canvas.drawLine(tipX, cy, tipX - dir * head, cy + head, ink)
                val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ink.color
                    textSize = size * 0.26f
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                }
                canvas.drawText("10", cx, cy + digits.textSize * 0.36f, digits)
            }
            Glyph.AUDIO -> {
                val path = Path()
                path.moveTo(cx - r * 0.9f, cy - r * 0.35f)
                path.lineTo(cx - r * 0.45f, cy - r * 0.35f)
                path.lineTo(cx, cy - r)
                path.lineTo(cx, cy + r)
                path.lineTo(cx - r * 0.45f, cy + r * 0.35f)
                path.lineTo(cx - r * 0.9f, cy + r * 0.35f)
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawArc(RectF(cx - r * 0.1f, cy - r * 0.7f, cx + r * 0.9f, cy + r * 0.7f),
                               -50f, 100f, false, ink)
                canvas.drawArc(RectF(cx - r * 0.1f, cy - r * 1.05f, cx + r * 1.35f, cy + r * 1.05f),
                               -50f, 100f, false, ink)
            }
            Glyph.SUBTITLES -> {
                val box = RectF(cx - r * 1.15f, cy - r * 0.75f, cx + r * 1.15f, cy + r * 0.75f)
                canvas.drawRoundRect(box, r * 0.25f, r * 0.25f, ink)
                canvas.drawLine(box.left + r * 0.3f, cy + r * 0.28f, box.left + r * 0.75f, cy + r * 0.28f, ink)
                canvas.drawLine(box.right - r * 0.75f, cy + r * 0.28f, box.right - r * 0.3f, cy + r * 0.28f, ink)
            }
            Glyph.NEXT -> {
                val path = Path()
                path.moveTo(cx - r, cy - r)
                path.lineTo(cx + r * 0.45f, cy)
                path.lineTo(cx - r, cy + r)
                path.close()
                canvas.drawPath(path, fill)
                canvas.drawRect(cx + r * 0.62f, cy - r, cx + r * 0.62f + r * 0.3f, cy + r, fill)
            }
            Glyph.CLOSE -> {
                canvas.drawLine(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, ink)
                canvas.drawLine(cx + r * 0.7f, cy - r * 0.7f, cx - r * 0.7f, cy + r * 0.7f, ink)
            }
        }
    }
}

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
    private val controls = mutableListOf<View>()
    private lateinit var bar: ProgressView
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var playButton: IconButton
    private lateinit var audioButton: IconButton
    private lateinit var subsButton: IconButton
    private lateinit var sheetTag: TextView

    private var player: MediaPlayer? = null
    private var title = ""
    private var itemId = ""
    private var who = "together"
    private var durationMs = 0
    private var controlsVisible = true
    private var picking: TrackKind? = null
    private var selectedTrack = -1          // audio track chosen by the viewer, -1 = the file's
    private var audioTracks: List<Int> = emptyList()
    private var defaultAudio = -1           // the track that plays when nobody chooses one
    private var audioMuted = false
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

    /** The audio track that is playing: the viewer's choice, else the file's own default. */
    private val activeAudio: Int
        get() = when {
            audioMuted -> -1
            selectedTrack >= 0 -> selectedTrack
            else -> defaultAudio
        }

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
        // Subtitles belong to the picture, not to the controls: the overlay comes and goes,
        // the words do not.
        root.addView(subtitleView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(150) })

        titleView = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor(INK))
            textSize = 20f
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setPadding(48, 34, 48, 34)
            visibility = View.GONE        // the control row names it instead
        }
        overlay.addView(titleView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START))

        // No panel: the controls sit on the picture under a soft gradient, the way every
        // streaming app does it. A rounded box in the middle of the screen reads as a dialog.
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(26), dp(48), dp(30))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF20B0D11.toInt(), 0xCC0B0D11.toInt(), 0x000B0D11)).apply {
                cornerRadius = 0f
            }
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        overlay.addView(sheet, params)

        val timing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        elapsed = timeText("0:00").apply { visibility = View.GONE }
        duration = timeText("0:00")
        bar = ProgressView(this).apply {
            isFocusable = true
            setOnFocusChangeListener { _, focused -> if (focused) flash() }
        }
        timing.addView(elapsed, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        timing.addView(bar, LinearLayout.LayoutParams(0, dp(26), 1f)
            .apply { marginStart = dp(10); marginEnd = dp(10) })
        timing.addView(duration, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.addView(timing)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun button(glyph: Glyph, name: String, press: () -> Unit): IconButton {
            val view = IconButton(this, glyph, name)
            view.onPress = { press(); showControls() }
            view.layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                .apply { marginEnd = dp(14) }
            controls.add(view)
            buttons.addView(view)
            return view
        }

        playButton = button(Glyph.PLAY, "Play or pause") { togglePlay() }
        button(Glyph.BACK10, "Back ten seconds") { skip(-SKIP_MS) }
        button(Glyph.FORWARD10, "Forward ten seconds") { skip(SKIP_MS) }
        audioButton = button(Glyph.AUDIO, "Audio track") { openTracks(TrackKind.AUDIO) }

        // What is playing, in the middle of the row, so the icons never shift.
        sheetTag = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor(INK))
            textSize = 17f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(10), 0)
        }
        buttons.addView(sheetTag, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        button(Glyph.NEXT, "Next episode") { advance() }
        subsButton = button(Glyph.SUBTITLES, "Subtitles") { openTracks(TrackKind.SUBTITLE) }
        button(Glyph.CLOSE, "Close") { finish() }
        sheet.addView(buttons)

        trackList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        trackPanel = LimitedScroll(this, minOf(dp(760), resources.displayMetrics.heightPixels - dp(80))).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#171b22"))
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#232c37"))
            }
            isFocusable = false
            addView(trackList)
        }
        val screen = resources.displayMetrics
        overlay.addView(trackPanel, FrameLayout.LayoutParams(
            minOf(dp(620), (screen.widthPixels * 0.62f).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER).apply { setMargins(dp(24), dp(24), dp(24), dp(24)) })
    }

    private class LimitedScroll(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun timeText(value: String) = TextView(this).apply {
        text = value
        setTextColor(Color.parseColor(DIM))
        textSize = 15f
        typeface = android.graphics.Typeface.MONOSPACE
    }

    /** A scrub bar drawn by hand: a thin line with the playhead on it. */
    private inner class ProgressView(context: Context) : View(context) {
        var fraction = 0f
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A93A0") }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#f4f6fa") }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(ACCENT) }

        override fun onDraw(canvas: Canvas) {
            val line = dp(3).toFloat()
            val middle = this.height / 2f
            val played = (this.width * fraction).coerceIn(0f, this.width.toFloat())
            canvas.drawRoundRect(0f, middle - line / 2, this.width.toFloat(), middle + line / 2,
                                 line / 2, line / 2, track)
            canvas.drawRoundRect(0f, middle - line / 2, played, middle + line / 2,
                                 line / 2, line / 2, fill)
            canvas.drawCircle(played, middle, dp(7).toFloat(), dot)
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
                say("This file will not play here ($what/$extra)", "#ff9b9b")
                true
            }
            media.prepareAsync()
        } catch (failure: Exception) {
            say("Could not open this title: " + failure.message, "#ff9b9b")
        }
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
    }

    /**
     * Play the audio track the file itself defaults to.
     *
     * The old rule was "prefer an English track", which — because the default usually is
     * English — selected the first English track that was NOT index 0: a commentary, or a
     * codec this box has no decoder for, which is how a show played with no sound at all.
     * Selecting nothing is the container's own default, which is what every other player
     * plays, so that is the default here too. The one reason to pick a track is a default
     * this device cannot decode: then the first track it can decode is taken instead.
     * Either way the choice is logged, because silence has to be diagnosable from logcat.
     */
    private fun applyDefaultTracks() {
        val media = player ?: return
        val tracks = runCatching { media.trackInfo }.getOrNull() ?: return
        audioTracks = tracks.indices.filter {
            tracks[it].trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO
        }
        selectedTrack = -1
        audioMuted = false
        defaultAudio = audioTracks.firstOrNull() ?: -1

        val mime = tracks.getOrNull(defaultAudio)?.let { mimeOf(it) }
        if (mime != null && !decodable(mime)) {
            val rescue = audioTracks.drop(1).firstOrNull { other ->
                mimeOf(tracks[other])?.let { decodable(it) } == true
            }
            if (rescue != null && runCatching { media.selectTrack(rescue) }.isSuccess) {
                selectedTrack = rescue
                Log.w(TAG, "audio: default track " + defaultAudio + " is " + mime +
                    ", which this box cannot decode; playing track " + rescue)
            } else {
                Log.w(TAG, "audio: default track " + defaultAudio + " is " + mime +
                    ", which this box cannot decode, and no other audio track can either")
            }
        }
        reportAudio(tracks)
    }

    /**
     * Say something over the picture. The title line is hidden while playback is fine (the
     * control row names the episode), so anything that tells the viewer what just happened
     * has to bring it back: a line written into a hidden view is a button that does nothing.
     */
    private fun say(message: String, colour: String = DIM) {
        titleView.text = message
        titleView.setTextColor(Color.parseColor(colour))
        titleView.visibility = View.VISIBLE
    }

    private fun reportAudio(tracks: Array<MediaPlayer.TrackInfo>) {
        if (audioTracks.isEmpty()) {
            // Nothing to choose from is worth saying out loud: on some files the platform
            // reports the picture and no sound at all, and a log that names the tracks it
            // got is the difference between "the app picked wrong" and "there was nothing
            // to pick".
            Log.w(TAG, "audio: the platform reports no audio track for this file (" +
                tracks.size + " track(s): " + tracks.withIndex().joinToString(", ") { (index, info) ->
                    index.toString() + "=type" + info.trackType + "/" + (mimeOf(info) ?: "?")
                } + ")")
            return
        }
        val playing = tracks.getOrNull(activeAudio)
        Log.d(TAG, "audio: playing track " + activeAudio +
            " language=" + (playing?.language ?: "?") +
            " mime=" + (playing?.let { mimeOf(it) } ?: "?") +
            " of " + audioTracks.size + " [" + audioTracks.joinToString(", ") { index ->
                index.toString() + ":" + (tracks[index].language ?: "?") + "/" +
                    (mimeOf(tracks[index]) ?: "?") + (if (index == activeAudio) "*" else "")
            } + "]")
    }

    private fun mimeOf(info: MediaPlayer.TrackInfo): String? =
        runCatching { info.format?.getString(MediaFormat.KEY_MIME) }.getOrNull()

    /**
     * Whether this device has a decoder for a MIME type. The platform player does not
     * transcode — a server might, ours does not — so a track whose codec is not on the box
     * plays as silence with the picture running perfectly.
     */
    private fun decodable(mime: String): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findDecoderForFormat(MediaFormat().apply { setString(MediaFormat.KEY_MIME, mime) }) != null
    } catch (unsure: Exception) {
        // A throw here means the format was too thin for the codec list to answer, not that
        // nothing can play it: assume the default track is fine rather than change it.
        true
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
        playButton.setGlyph(if (player?.isPlaying == true) Glyph.PAUSE else Glyph.PLAY)
    }

    private fun refreshProgress() {
        val media = player ?: return
        val position = runCatching { media.currentPosition }.getOrDefault(0)
        if (durationMs > 0) {
            bar.fraction = (position.toFloat() / durationMs).coerceIn(0f, 1f)
            bar.invalidate()
        }
        elapsed.text = clock(position)
        elapsed.visibility = if (currentFocus === bar) View.VISIBLE else View.GONE
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
            say("No audio track this box can use")
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
        trackList.addView(sheetHeading(
            if (kind == TrackKind.AUDIO) "Audio"
            else "Subtitles \u00b7 " + availableSubs.size + " tracks \u00b7 back to close"))

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
            addRow("Off \u00b7 no sound", audioMuted) {
                runCatching { media.selectTrack(-1) }
                audioMuted = true
                closeTracks()
                Log.d(TAG, "audio: the viewer turned the sound off")
            }
            choices.forEach { index ->
                // The language alone is not enough to tell two English tracks apart: one is
                // the film, the other is a commentator, and the codec is what decides
                // whether the box can play it at all — so both are on the row.
                addRow(describe(tracks[index], index), !audioMuted && activeAudio == index) {
                    if (runCatching { media.selectTrack(index) }.isSuccess) {
                        selectedTrack = index
                        audioMuted = false
                        Log.d(TAG, "audio: the viewer chose track " + index +
                            " language=" + (tracks[index].language ?: "?") +
                            " mime=" + (mimeOf(tracks[index]) ?: "?"))
                    }
                    closeTracks()
                }
            }
            menuIndex = choices.indexOf(activeAudio) + if (activeAudio >= 0) 1 else 0
        }
        menuIndex = menuIndex.coerceIn(0, maxOf(0, menuRows.size - 1))
        paintMenu()
        trackPanel.visibility = View.VISIBLE
        sheet.visibility = View.GONE
    }

    /** A row that the menu paints, not one the focus system owns. */
    private fun menuRow(label: String, selected: Boolean): TextView = TextView(this).apply {
        text = (if (selected) "\u2713  " else "     ") + label
        setTextColor(Color.parseColor(INK))
        textSize = 18f
        setPadding(22, 16, 30, 16)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = 12f
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = 6 }
    }

    private fun paintMenu() {
        menuRows.forEachIndexed { index, row ->
            val chosen = index == menuIndex
            row.setBackgroundColor(if (chosen) Color.parseColor("#232b36") else Color.TRANSPARENT)
            row.setTextColor(Color.parseColor(if (chosen) ACCENT else INK))
            if (chosen) {
                val room = trackPanel.height - row.height - dp(24)
                trackPanel.scrollTo(0, (row.top - dp(60)).coerceIn(0, maxOf(0, room)))
            }
        }
    }

    private fun describe(info: MediaPlayer.TrackInfo, index: Int): String {
        val language = languageName(info.language).ifBlank { "Track " + (index + 1) }
        val mime = mimeOf(info)
        val codec = codecName(mime)
        var label = if (codec.isBlank()) language else language + "  \u00b7  " + codec
        if (mime != null && !decodable(mime)) label += "  \u00b7  this box cannot play it"
        return label
    }

    /** "eng" is the language code a file carries; "English" is what a person reads. */
    private fun languageName(code: String?): String {
        val key = (code ?: "").trim().lowercase(Locale.ROOT)
        return when (key) {
            "", "und", "unknown", "mul", "zxx" -> ""
            "en", "eng" -> "English"
            "es", "spa" -> "Spanish"
            "fr", "fra", "fre" -> "French"
            "de", "deu", "ger" -> "German"
            "it", "ita" -> "Italian"
            "pt", "por" -> "Portuguese"
            "ja", "jpn" -> "Japanese"
            "ko", "kor" -> "Korean"
            "zh", "zho", "chi" -> "Chinese"
            "ru", "rus" -> "Russian"
            "hi", "hin" -> "Hindi"
            "ar", "ara" -> "Arabic"
            "nl", "nld", "dut" -> "Dutch"
            "sv", "swe" -> "Swedish"
            "no", "nor" -> "Norwegian"
            "da", "dan" -> "Danish"
            "pl", "pol" -> "Polish"
            else -> key.uppercase(Locale.ROOT)
        }
    }

    /** The MIME subtype is what the decoder list speaks; a person reads AC3, not "ac3". */
    private fun codecName(mime: String?): String {
        val tail = (mime ?: "").substringAfter('/').lowercase(Locale.ROOT)
        return when (tail) {
            "", "raw" -> ""
            "ac3", "ac-3" -> "AC3"
            "eac3", "ec-3", "e-ac-3" -> "E-AC3"
            "truehd" -> "TrueHD"
            "dts", "dts-hd", "dtshd" -> "DTS"
            "mp4a-latm", "mp4a", "aac", "aac-latm" -> "AAC"
            "opus" -> "Opus"
            "vorbis" -> "Vorbis"
            "flac" -> "FLAC"
            "alac" -> "ALAC"
            "pcm", "pcm-s16le", "lpcm" -> "PCM"
            else -> tail.uppercase(Locale.ROOT)
        }
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
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // One press in the middle of the film pauses it, and the controls come
                    // up so the next press has somewhere to go.
                    togglePlay()
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
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
