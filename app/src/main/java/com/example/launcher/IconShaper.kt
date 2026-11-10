package com.example.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Recorta os ícones dos apps num formato escolhido (círculo, squircle, gota…). */
object IconShaper {

    private val cache = HashMap<String, Drawable>()

    fun clear() {
        cache.clear()
    }

    /** [shape]: 0 = original do sistema; veja Prefs.SHAPE_NAMES. */
    fun shaped(ctx: Context, src: Drawable, key: String, shape: Int, px: Int): Drawable {
        if (shape == 0 || px <= 0) return src
        val k = "$key|$shape|$px"
        cache[k]?.let { return it }
        val d: Drawable = try {
            BitmapDrawable(ctx.resources, render(ctx, src, shape, px))
        } catch (e: Throwable) {
            src
        }
        cache[k] = d
        return d
    }

    private fun render(ctx: Context, src: Drawable, shape: Int, px: Int): Bitmap {
        val content = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cc = Canvas(content)
        // Trabalha numa cópia para não mexer nos limites do drawable original (compartilhado).
        val d = src.constantState?.newDrawable(ctx.resources)?.mutate() ?: src

        if (d is AdaptiveIconDrawable) {
            // As camadas têm 108 unidades e só 72 aparecem: estende 25% para cada lado.
            val q = (px * 0.25f).toInt()
            d.background?.let { layer(it, cc, -q, -q, px + q, px + q) }
            d.foreground?.let { layer(it, cc, -q, -q, px + q, px + q) }
        } else {
            // Ícone antigo (sem camadas): fundo neutro e ícone com margem.
            cc.drawColor(0xFFEEEEEE.toInt())
            val m = (px * 0.14f).toInt()
            layer(d, cc, m, m, px - m, px - m)
        }

        // Máscara com borda suave: desenha a forma e mantém só o que cai dentro dela.
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        oc.drawPath(path(shape, px.toFloat()), p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        oc.drawBitmap(content, 0f, 0f, p)
        return out
    }

    private fun layer(d: Drawable, c: Canvas, l: Int, t: Int, r: Int, b: Int) {
        d.setBounds(l, t, r, b)
        d.draw(c)
    }

    private fun path(shape: Int, s: Float): Path {
        val p = Path()
        val rect = RectF(0f, 0f, s, s)
        when (shape) {
            1 -> p.addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW)
            2 -> p.addRoundRect(rect, s * 0.24f, s * 0.24f, Path.Direction.CW)
            3 -> {
                // Superelipse (n = 4): o "squircle" dos ícones modernos.
                val steps = 120
                for (i in 0..steps) {
                    val t = 2.0 * PI * i / steps
                    val ct = cos(t)
                    val st = sin(t)
                    val x = s / 2f + (s / 2f) * (sign(ct) * abs(ct).pow(0.5)).toFloat()
                    val y = s / 2f + (s / 2f) * (sign(st) * abs(st).pow(0.5)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            4 -> {
                // Gota: três cantos redondos e um quase reto (canto superior direito).
                val big = s / 2f
                val small = s * 0.08f
                p.addRoundRect(
                    rect,
                    floatArrayOf(big, big, small, small, big, big, big, big),
                    Path.Direction.CW
                )
            }
            else -> p.addRoundRect(rect, s * 0.06f, s * 0.06f, Path.Direction.CW)
        }
        return p
    }
}
