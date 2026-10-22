package com.example.launcher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.max

object Glass {

    /** Devolve uma versão bem desfocada (e pequena) da imagem; o desenho a amplia suavemente. */
    fun blur(src: Bitmap, radius: Int): Bitmap {
        val w0 = 128
        val h0 = max(1, src.height * w0 / max(1, src.width))
        val small = Bitmap.createScaledBitmap(src, w0, h0, true)
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)

        val r = IntArray(w * h)
        val g = IntArray(w * h)
        val b = IntArray(w * h)
        for (i in px.indices) {
            r[i] = Color.red(px[i])
            g[i] = Color.green(px[i])
            b[i] = Color.blue(px[i])
        }
        val rad = radius.coerceIn(1, 20)
        repeat(3) {
            boxBlur(r, w, h, rad)
            boxBlur(g, w, h, rad)
            boxBlur(b, w, h, rad)
        }
        for (i in px.indices) px[i] = Color.rgb(r[i], g[i], b[i])

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        return out
    }

    /** Desfoque de caixa (média móvel) horizontal e depois vertical, com bordas repetidas. */
    private fun boxBlur(ch: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(max(w, h))
        val div = 2 * r + 1

        for (y in 0 until h) {
            val o = y * w
            var sum = 0
            for (i in -r..r) sum += ch[o + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[x] = sum / div
                sum += ch[o + (x + r + 1).coerceAtMost(w - 1)] - ch[o + (x - r).coerceAtLeast(0)]
            }
            for (x in 0 until w) ch[o + x] = tmp[x]
        }

        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += ch[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                tmp[y] = sum / div
                sum += ch[(y + r + 1).coerceAtMost(h - 1) * w + x] - ch[(y - r).coerceAtLeast(0) * w + x]
            }
            for (y in 0 until h) ch[y * w + x] = tmp[y]
        }
    }
}

/**
 * Fundo de "vidro fosco": mostra, atrás da view, o pedaço correspondente da imagem de fundo
 * já desfocada, com um leve escurecimento e uma cor por cima.
 * [frame] é a view que contém a imagem de fundo (preenche a tela inteira, em "center crop").
 */
class GlassDrawable(
    private val frame: View,
    private val blurred: Bitmap,
    private val radius: Float,
    private val dimAlpha: Int,
    private val tint: Int
) : Drawable() {

    private val sh = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = sh }
    private val over = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val a = IntArray(2)
    private val b = IntArray(2)
    private val rect = RectF()

    /** Acha a View que usa este drawable (diretamente ou dentro de um LayerDrawable). */
    private fun host(): View? {
        var cb: Drawable.Callback? = callback
        while (cb is Drawable) cb = (cb as Drawable).callback
        return cb as? View
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val v = host()
        val fw = frame.width.toFloat()
        val fh = frame.height.toFloat()
        if (v != null && fw > 0f && fh > 0f) {
            v.getLocationOnScreen(a)
            frame.getLocationOnScreen(b)
            val bw = blurred.width.toFloat()
            val bh = blurred.height.toFloat()
            val s = max(fw / bw, fh / bh)
            m.setScale(s, s)
            m.postTranslate(
                (fw - bw * s) / 2f - (a[0] - b[0]),
                (fh - bh * s) / 2f - (a[1] - b[1])
            )
            sh.setLocalMatrix(m)
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        over.color = Color.argb(dimAlpha, 0, 0, 0)
        canvas.drawRoundRect(rect, radius, radius, over)
        over.color = tint
        canvas.drawRoundRect(rect, radius, radius, over)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
