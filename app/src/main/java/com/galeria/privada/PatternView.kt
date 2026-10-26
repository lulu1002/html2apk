package com.galeria.privada

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Padrão de desbloqueio em grade 3x3, próprio do app (não usa a tela do sistema). */
class PatternView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    /** Chamado quando o dedo é solto, com os índices (0-8) na ordem desenhada. Vazio se nada foi tocado. */
    var onComplete: ((List<Int>) -> Unit)? = null

    var errorMode = false
        set(value) {
            field = value
            invalidate()
        }

    private val centers = Array(9) { PointF() }
    private val selected = mutableListOf<Int>()
    private var dragX = 0f
    private var dragY = 0f
    private var dragging = false

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4A4D70") }
    private val dotSelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8E7BFF") }
    private val dotErrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF6B6B") }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.parseColor("#33374F")
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 10f; color = Color.parseColor("#8E7BFF")
        strokeCap = Paint.Cap.ROUND
    }
    private val lineErrPaint = Paint(linePaint).apply { color = Color.parseColor("#FF6B6B") }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = w * 0.14f
        val usable = w - pad * 2
        for (row in 0 until 3) for (col in 0 until 3) {
            val idx = row * 3 + col
            centers[idx] = PointF(pad + usable * col / 2f, pad + usable * row / 2f)
        }
    }

    private fun dotRadius() = width * 0.045f
    private fun hitRadius() = width * 0.11f

    fun reset() {
        selected.clear()
        dragging = false
        errorMode = false
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                selected.clear()
                errorMode = false
                dragging = true
                dragX = event.x; dragY = event.y
                hitTest(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                dragX = event.x; dragY = event.y
                hitTest(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onComplete?.invoke(selected.toList())
                invalidate()
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float) {
        val r = hitRadius()
        for (i in 0 until 9) {
            if (i in selected) continue
            val c = centers[i]
            if (hypot((x - c.x).toDouble(), (y - c.y).toDouble()) <= r) selected.add(i)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val lp = if (errorMode) lineErrPaint else linePaint
        for (i in 0 until selected.size - 1) {
            val a = centers[selected[i]]; val b = centers[selected[i + 1]]
            canvas.drawLine(a.x, a.y, b.x, b.y, lp)
        }
        if (dragging && selected.isNotEmpty()) {
            val a = centers[selected.last()]
            canvas.drawLine(a.x, a.y, dragX, dragY, lp)
        }
        for (i in 0 until 9) {
            val c = centers[i]
            canvas.drawCircle(c.x, c.y, hitRadius(), ringPaint)
            val p = if (i in selected) (if (errorMode) dotErrPaint else dotSelPaint) else dotPaint
            canvas.drawCircle(c.x, c.y, dotRadius(), p)
        }
    }
}
