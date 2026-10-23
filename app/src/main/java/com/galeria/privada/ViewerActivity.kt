package com.galeria.privada

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File

class ViewerActivity : AppCompatActivity() {

    private var files: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_viewer)

        files = Vault.list(this)
        if (files.isEmpty()) {
            finish()
            return
        }
        val pager = findViewById<ViewPager2>(R.id.pager)
        val tvPos = findViewById<TextView>(R.id.tvPos)
        pager.adapter = PageAdapter(files)
        val start = intent.getIntExtra("index", 0).coerceIn(0, files.size - 1)
        pager.setCurrentItem(start, false)
        tvPos.text = "${start + 1} / ${files.size}"
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                tvPos.text = "${position + 1} / ${files.size}"
            }
        })
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        if (!Session.appUnlocked) finish()
    }

    private class PageAdapter(private val files: List<File>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private class ImgHolder(val zoom: ZoomImageView) : RecyclerView.ViewHolder(zoom)
        private class VidHolder(val frame: FrameLayout, val video: VideoView, val play: ImageView) :
            RecyclerView.ViewHolder(frame)

        override fun getItemCount() = files.size

        override fun getItemViewType(position: Int) = if (Vault.isVideo(files[position])) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val full = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            if (viewType == 0) {
                val z = ZoomImageView(ctx)
                z.layoutParams = full
                return ImgHolder(z)
            }
            val frame = FrameLayout(ctx)
            frame.layoutParams = full
            val video = VideoView(ctx)
            frame.addView(
                video,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            val size = (72 * ctx.resources.displayMetrics.density).toInt()
            val play = ImageView(ctx)
            play.setImageResource(R.drawable.ic_play)
            frame.addView(play, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            return VidHolder(frame, video, play)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val f = files[position]
            when (holder) {
                is ImgHolder -> {
                    Thumbs.load(f, holder.zoom, 2048)
                    holder.zoom.reset()
                }
                is VidHolder -> {
                    holder.play.visibility = View.VISIBLE
                    holder.video.setVideoPath(f.absolutePath)
                    holder.video.setOnPreparedListener { holder.video.seekTo(1) }
                    holder.video.setOnCompletionListener { holder.play.visibility = View.VISIBLE }
                    holder.video.setOnClickListener {
                        if (holder.video.isPlaying) {
                            holder.video.pause()
                            holder.play.visibility = View.VISIBLE
                        } else {
                            holder.video.start()
                            holder.play.visibility = View.GONE
                        }
                    }
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is VidHolder) holder.video.stopPlayback()
            super.onViewRecycled(holder)
        }
    }
}
