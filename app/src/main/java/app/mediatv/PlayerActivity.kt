package app.mediatv

import android.app.Activity
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView

/**
 * Playback with the platform decoder, not through the page.
 *
 * The WebView cannot demux Matroska, which is most of this library, so the video is played
 * by Android's own player against the stream the NUC proxies. No third-party libraries:
 * the same rule as the rest of this app.
 */
class PlayerActivity : Activity() {

    private lateinit var video: VideoView
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val title = intent.getStringExtra("title") ?: ""

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this).apply {
            setOnErrorListener { _, what, extra ->
                hint.text = "This file will not play here ($what/$extra)"
                true
            }
            setOnPreparedListener { player: MediaPlayer ->
                hint.text = title
                player.start()
            }
        }
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                     ViewGroup.LayoutParams.MATCH_PARENT))
        hint = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#b3bcc9"))
            textSize = 18f
            setPadding(32, 22, 32, 22)
        }
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                                    Gravity.TOP or Gravity.START))
        setContentView(root)
        video.setVideoPath(url)
        video.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (video.isPlaying) video.pause() else video.start()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> return seekBy(-10000)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return seekBy(10000)
            KeyEvent.KEYCODE_MEDIA_REWIND -> return seekBy(-30000)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return seekBy(30000)
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun seekBy(delta: Int): Boolean {
        val target = (video.currentPosition + delta).coerceAtLeast(0)
        video.seekTo(target)
        return true
    }

    override fun onDestroy() {
        video.stopPlayback()
        super.onDestroy()
    }
}
