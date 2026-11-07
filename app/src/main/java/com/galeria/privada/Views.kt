package com.galeria.privada

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class SquareImageView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : AppCompatImageView(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/** ImageView com zoom por pinça, arrastar e toque duplo. */
class ZoomImageView(ctx: Context) : AppCompatImageView(ctx) {
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private val scaleDetector = ScaleGestureDetector(ctx,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scale = (scale * detector.scaleFactor).coerceIn(1f, 5f)
                applyTransform()
                return true
            }
        })

    private val gestures = GestureDetector(ctx,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                scale = if (scale > 1f) 1f else 2.5f
                applyTransform()
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scale <= 1f) return false
                tx -= dx
                ty -= dy
                applyTransform()
                return true
            }
        })

    fun reset() {
        scale = 1f
        applyTransform()
    }

    private fun applyTransform() {
        if (scale <= 1f) {
            tx = 0f; ty = 0f
        } else {
            val maxX = width * (scale - 1f) / 2f
            val maxY = height * (scale - 1f) / 2f
            tx = tx.coerceIn(-maxX, maxX)
            ty = ty.coerceIn(-maxY, maxY)
        }
        scaleX = scale
        scaleY = scale
        translationX = tx
        translationY = ty
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(scale > 1f || event.pointerCount > 1)
        return true
    }
}
