package com.luyan.wifiscout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Gráfico de linha com as últimas leituras de RSSI. */
class SignalGraphView(context: Context) : View(context) {

    private val values = ArrayList<Int>()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()

    fun add(rssi: Int) {
        values.add(rssi)
        if (values.size > MAX_POINTS) values.removeAt(0)
        invalidate()
    }

    fun clear() {
        values.clear()
        invalidate()
    }

    private fun yFor(v: Int, h: Float): Float {
        val clamped = v.coerceIn(MIN_DBM, MAX_DBM)
        return h * (1f - (clamped - MIN_DBM).toFloat() / (MAX_DBM - MIN_DBM).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Linhas de grade com a escala em dBm
        for (db in intArrayOf(-40, -50, -60, -70, -80, -90)) {
            val y = yFor(db, h)
            canvas.drawLine(0f, y, w, y, gridPaint)
            canvas.drawText("$db", 4f, y - 4f, textPaint)
        }

        if (values.size < 2) return

        val step = w / (MAX_POINTS - 1).toFloat()
        path.reset()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = yFor(v, h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        val last = values.last()
        linePaint.color = qualityColor(last)
        canvas.drawPath(path, linePaint)

        dotPaint.color = qualityColor(last)
        canvas.drawCircle((values.size - 1) * step, yFor(last, h), 5f * resources.displayMetrics.density, dotPaint)
    }

    companion object {
        private const val MAX_POINTS = 100
        private const val MIN_DBM = -100
        private const val MAX_DBM = -30
    }
}
