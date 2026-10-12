package com.example.gerenciador

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.util.TypedValue
import android.widget.EditText

/** EditText que desenha os números das linhas lógicas numa "calha" à esquerda. */
class LineNumberEditText(ctx: Context) : EditText(ctx) {

    private val density = ctx.resources.displayMetrics.density
    private val basePad = (8 * density).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private var gutter = 0
    private var ready = false

    var showNumbers = true
        set(value) {
            field = value
            updateGutter()
            invalidate()
        }

    init {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
        bgPaint.color = tv.data
        setPadding(basePad, basePad, basePad, basePad)
        ready = true
        updateGutter()
    }

    fun updateGutter() {
        if (!ready) return
        paint.textSize = textSize * 0.85f
        paint.typeface = typeface
        val digits = maxOf(2, lineCount.toString().length)
        val w = if (showNumbers) (paint.measureText("0") * digits + 12 * density).toInt() else 0
        if (w != gutter) {
            gutter = w
            setPadding(basePad + gutter, basePad, basePad, basePad)
        }
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        if (ready) post { updateGutter() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showNumbers || gutter == 0) return
        val l: Layout = layout ?: return
        val t = text
        val offsetTop = extendedPaddingTop
        val firstLine = l.getLineForVertical(maxOf(0, scrollY - offsetTop))
        val lastLine = l.getLineForVertical(maxOf(0, scrollY + height - offsetTop))

        // cobre o texto que rolou para baixo da calha (quando a quebra de linha está desligada)
        canvas.drawRect(
            scrollX.toFloat(), scrollY.toFloat(),
            (scrollX + gutter).toFloat(), (scrollY + height).toFloat(), bgPaint
        )

        paint.textSize = textSize * 0.85f
        paint.color = currentTextColor
        paint.alpha = 120
        paint.textAlign = Paint.Align.RIGHT

        var n = 1
        val end = l.getLineStart(firstLine)
        for (k in 0 until end) if (t[k] == '\n') n++

        val x = (scrollX + gutter - 6 * density)
        for (i in firstLine..lastLine) {
            val start = l.getLineStart(i)
            val paraStart = start == 0 || t[start - 1] == '\n'
            if (paraStart && i != firstLine) n++
            if (paraStart) {
                canvas.drawText(n.toString(), x, (l.getLineBaseline(i) + offsetTop).toFloat(), paint)
            }
        }
    }
}
