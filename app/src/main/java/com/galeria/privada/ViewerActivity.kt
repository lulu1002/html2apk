package com.galeria.privada

import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
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

        files = Albums.files(this, intent.getBooleanExtra("video", false), intent.getStringExtra("album"))
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
        private class VidHolder(val page: VideoPageView) : RecyclerView.ViewHolder(page)

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
            val v = VideoPageView(ctx)
            v.layoutParams = full
            return VidHolder(v)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val f = files[position]
            when (holder) {
                is ImgHolder -> {
                    Thumbs.load(f, holder.zoom, 2048)
                    holder.zoom.reset()
                }
                is VidHolder -> holder.page.bind(f.absolutePath)
            }
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            if (holder is VidHolder) holder.page.pause()
            super.onViewDetachedFromWindow(holder)
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is VidHolder) holder.page.release()
            super.onViewRecycled(holder)
        }
    }
}
