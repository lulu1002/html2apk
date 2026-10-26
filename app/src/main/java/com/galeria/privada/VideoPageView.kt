package com.galeria.privada

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/** Página de vídeo: TextureView com ajuste manual de proporção (sem as falhas do VideoView em ViewPager). */
class VideoPageView(ctx: Context) : FrameLayout(ctx), TextureView.SurfaceTextureListener {

    private val texture = TextureView(ctx)
    private val playIcon = ImageView(ctx)
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var path: String? = null
    private var prepared = false
    private var vw = 0
    private var vh = 0

    init {
        addView(texture, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = this
        val size = (72 * ctx.resources.displayMetrics.density).toInt()
        playIcon.setImageResource(R.drawable.ic_play)
        addView(playIcon, LayoutParams(size, size, Gravity.CENTER))
        setOnClickListener { toggle() }
    }

    fun bind(filePath: String) {
        path = filePath
        release()
        playIcon.visibility = View.VISIBLE
        if (texture.isAvailable && surface != null) prepare()
    }

    fun pause() {
        val p = player ?: return
        if (prepared && p.isPlaying) p.pause()
        playIcon.visibility = View.VISIBLE
    }

    fun release() {
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
            playIcon.visibility = View.VISIBLE
        } else {
            p.start()
            playIcon.visibility = View.GONE
        }
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
            }
            mp.setOnCompletionListener { playIcon.visibility = View.VISIBLE }
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
