package com.example.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Grade 3x3 de pontinhos para desenhar um padrão (próprio do app, não o do celular). */
class PatternView(ctx: Context) : View(ctx) {

    /** Chamado ao soltar o dedo, com o padrão como "0-1-2-5" (índices 0..8). */
    var onDone: ((String, Int) -> Unit)? = null
    var dotColor = 0xFF888888.toInt()
    var lineColor = 0xFF1E88E5.toInt()

    private val dots = ArrayList<Int>()
    private var fx = 0f
    private var fy = 0f
    private var drawing = false
    private var error = false

    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun cx(i: Int): Float = width * (1 + 2 * (i % 3)) / 6f
    private fun cy(i: Int): Float = height * (1 + 2 * (i / 3)) / 6f

    fun reset() {
        dots.clear()
        drawing = false
        error = false
        invalidate()
    }

    fun showError() {
        error = true
        drawing = false
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = min(MeasureSpec.getSize(w), (260 * resources.displayMetrics.density).toInt())
        setMeasuredDimension(s, s)
    }

    private fun hit(x: Float, y: Float) {
        val r = width / 6f * 0.8f
        for (i in 0 until 9) {
            if (i !in dots && hypot(x - cx(i), y - cy(i)) < r) {
                dots.add(i)
                break
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                parent?.requestDisallowInterceptTouchEvent(true)
                drawing = true
                fx = e.x
                fy = e.y
                hit(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                fx = e.x
                fy = e.y
                hit(e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drawing = false
                if (dots.isNotEmpty() && e.actionMasked == MotionEvent.ACTION_UP) {
                    onDone?.invoke(dots.joinToString("-"), dots.size)
                } else {
                    dots.clear()
                }
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        val unit = width / 6f
        val color = if (error) 0xFFE53935.toInt() else lineColor
        pLine.color = color
        pLine.strokeWidth = unit * 0.14f
        for (k in 1 until dots.size) {
            c.drawLine(cx(dots[k - 1]), cy(dots[k - 1]), cx(dots[k]), cy(dots[k]), pLine)
        }
        if (drawing && dots.isNotEmpty()) {
            val l = dots.last()
            c.drawLine(cx(l), cy(l), fx, fy, pLine)
        }
        for (i in 0 until 9) {
            val on = i in dots
            pDot.color = if (on) color else dotColor
            c.drawCircle(cx(i), cy(i), if (on) unit * 0.26f else unit * 0.16f, pDot)
        }
    }
}
