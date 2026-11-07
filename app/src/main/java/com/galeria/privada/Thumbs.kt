package com.galeria.privada

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.concurrent.Executors

object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(file: File, target: ImageView, px: Int) {
        val key = "${file.absolutePath}|${file.lastModified()}|$px"
        target.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            target.setImageBitmap(hit)
            return
        }
        target.setImageDrawable(null)
        pool.execute {
            val bmp = decode(file, px) ?: return@execute
            cache.put(key, bmp)
            main.post { if (target.tag == key) target.setImageBitmap(bmp) }
        }
    }

    fun decode(file: File, px: Int): Bitmap? = try {
        if (Vault.isVideo(file)) videoFrame(file, px) else imageBitmap(file, px)
    } catch (e: Throwable) {
        null
    }

    private fun imageBitmap(file: File, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var w = bounds.outWidth
        var h = bounds.outHeight
        var sample = 1
        while (w / 2 >= px && h / 2 >= px) {
            w /= 2; h /= 2; sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return rotate(bmp, file)
    }

    private fun rotate(bmp: Bitmap, file: File): Bitmap {
        return try {
            val o = ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val deg = when (o) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg == 0f) bmp
            else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        } catch (e: Exception) {
            bmp
        }
    }

    private fun videoFrame(file: File, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val f = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0)
            return f?.let { scaleDown(it, px) }
        } finally {
            r.release()
        }
    }

    private fun scaleDown(bmp: Bitmap, px: Int): Bitmap {
        val m = minOf(bmp.width, bmp.height)
        if (m <= px * 2) return bmp
        val s = px.toFloat() / m
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
    }
}
