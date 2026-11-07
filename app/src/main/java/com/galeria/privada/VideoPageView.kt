package com.galeria.privada

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/** Página de vídeo: TextureView com ajuste manual de proporção + controles (play/pausa, barra, tempo). */
@SuppressLint("ClickableViewAccessibility")
class VideoPageView(ctx: Context) : FrameLayout(ctx), TextureView.SurfaceTextureListener {

    private val texture = TextureView(ctx)
    private val centerIcon = ImageView(ctx)

    private val controls: LinearLayout
    private val btnPlay: ImageView
    private val seekBar: SeekBar
    private val tvTime: TextView

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var path: String? = null
    private var prepared = false
    private var vw = 0
    private var vh = 0
    private var userSeeking = false

    private val handler = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (player?.isPlaying == true) handler.postDelayed(this, 300)
        }
    }
    private val autoHideRunnable = Runnable { if (player?.isPlaying == true) setControlsVisible(false) }

    init {
        addView(texture, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = this

        val centerSize = (64 * density).toInt()
        centerIcon.setImageResource(R.drawable.ic_play)
        centerIcon.setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        centerIcon.setBackgroundColor(0x55000000)
        addView(centerIcon, LayoutParams(centerSize, centerSize, Gravity.CENTER))

        val pad = (10 * density).toInt()
        btnPlay = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_pause)
            setPadding(pad, pad, pad, pad)
        }
        tvTime = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            text = "0:00 / 0:00"
            setPadding(pad, 0, pad, 0)
        }
        seekBar = SeekBar(ctx).apply { max = 1000 }

        controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(btnPlay, LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt()))
            addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tvTime, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(controls, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        btnPlay.setOnClickListener { toggle() }
        centerIcon.setOnClickListener { toggle() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(autoHideRunnable)
            }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val p = player ?: return
                if (fromUser && prepared) {
                    val pos = (progress.toLong() * p.duration / 1000).toInt()
                    tvTime.text = "${fmt(pos)} / ${fmt(p.duration)}"
                }
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                val p = player
                if (p != null && prepared) {
                    val pos = (sb.progress.toLong() * p.duration / 1000).toInt()
                    p.seekTo(pos)
                }
                userSeeking = false
                scheduleAutoHide()
            }
        })

        setOnClickListener { setControlsVisible(controls.visibility != View.VISIBLE) }
        setControlsVisible(true)
    }

    fun bind(filePath: String) {
        path = filePath
        release()
        centerIcon.setImageResource(R.drawable.ic_play)
        btnPlay.setImageResource(R.drawable.ic_pause)
        seekBar.progress = 0
        tvTime.text = "0:00 / 0:00"
        setControlsVisible(true)
        if (texture.isAvailable && surface != null) prepare()
    }

    fun pause() {
        val p = player ?: return
        if (prepared && p.isPlaying) p.pause()
        onPausedUi()
    }

    fun release() {
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(autoHideRunnable)
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        prepared = false
    }

    private fun toggle() {
        val p = player ?: return
        if (!prepared) return
        if (p.isPlaying) {
            p.pause()
            onPausedUi()
        } else {
            p.start()
            onPlayingUi()
        }
    }

    private fun onPlayingUi() {
        centerIcon.visibility = View.GONE
        btnPlay.setImageResource(R.drawable.ic_pause)
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
        scheduleAutoHide()
    }

    private fun onPausedUi() {
        centerIcon.visibility = View.VISIBLE
        btnPlay.setImageResource(R.drawable.ic_play)
        handler.removeCallbacks(progressRunnable)
        handler.removeCallbacks(autoHideRunnable)
        setControlsVisible(true)
    }

    private fun setControlsVisible(visible: Boolean) {
        controls.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && player?.isPlaying == true) scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
        handler.postDelayed(autoHideRunnable, 3000)
    }

    private fun updateProgress() {
        val p = player ?: return
        if (!prepared || userSeeking) return
        val dur = p.duration
        if (dur <= 0) return
        val pos = p.currentPosition
        seekBar.progress = (pos.toLong() * 1000 / dur).toInt()
        tvTime.text = "${fmt(pos)} / ${fmt(dur)}"
    }

    private fun fmt(ms: Int): String {
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return String.format(Locale.getDefault(), "%d:%02d", m, s)
    }

    private fun prepare() {
        val file = path ?: return
        val s = surface ?: return
        release()
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(file)
            mp.setSurface(s)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                vw = w
                vh = h
                fit()
            }
            mp.setOnPreparedListener {
                prepared = true
                vw = it.videoWidth
                vh = it.videoHeight
                fit()
                it.seekTo(1L, MediaPlayer.SEEK_CLOSEST)
                tvTime.text = "0:00 / ${fmt(it.duration)}"
            }
            mp.setOnCompletionListener {
                onPausedUi()
                seekBar.progress = 1000
            }
            mp.setOnErrorListener { _, _, _ ->
                prepared = false
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            release()
        }
    }

    private fun fit() {
        if (vw <= 0 || vh <= 0 || width <= 0 || height <= 0) return
        val videoAspect = vw.toFloat() / vh
        val viewAspect = width.toFloat() / height
        var sx = 1f
        var sy = 1f
        if (videoAspect > viewAspect) sy = viewAspect / videoAspect else sx = videoAspect / viewAspect
        val m = Matrix()
        m.setScale(sx, sy, width / 2f, height / 2f)
        texture.setTransform(m)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        prepare()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        fit()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
