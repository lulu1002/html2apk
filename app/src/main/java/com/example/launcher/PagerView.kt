package com.example.launcher

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.roundToInt

/** Páginas lado a lado com "encaixe" (uma página por vez) ao deslizar. */
class PagerView(ctx: Context) : HorizontalScrollView(ctx) {

    private val box = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    var onPageChanged: ((Int) -> Unit)? = null
    var page = 0
        private set
    private var startPage = 0
    private var flung = false

    val count: Int get() = box.childCount

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
        addView(
            box,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    fun setPages(views: List<View>, keep: Int) {
        box.removeAllViews()
        for (v in views) {
            (v.parent as? ViewGroup)?.removeView(v)
            box.addView(v, LinearLayout.LayoutParams(max(width, 1), LinearLayout.LayoutParams.MATCH_PARENT))
        }
        applyWidths()
        goTo(keep.coerceIn(0, max(0, views.size - 1)), false)
    }

    private fun applyWidths() {
        val w = width
        if (w <= 0) return
        for (i in 0 until box.childCount) {
            val c = box.getChildAt(i)
            val lp = c.layoutParams
            if (lp.width != w) {
                lp.width = w
                c.layoutParams = lp
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        applyWidths()
        post { scrollTo(page * width, 0) }
    }

    fun goTo(p: Int, animate: Boolean) {
        val target = p.coerceIn(0, max(0, count - 1))
        val changed = target != page
        page = target
        val x = target * width
        if (animate) smoothScrollTo(x, 0) else scrollTo(x, 0)
        if (changed) onPageChanged?.invoke(target)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val w = width
        if (w <= 0 || count == 0) return
        val p = (l / w.toFloat()).roundToInt().coerceIn(0, count - 1)
        if (p != page) {
            page = p
            onPageChanged?.invoke(p)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            startPage = page
            flung = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val r = super.onTouchEvent(ev)
        val a = ev.actionMasked
        if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && !flung && width > 0) {
            goTo((scrollX / width.toFloat()).roundToInt(), true)
        }
        return r
    }

    override fun fling(velocityX: Int) {
        flung = true
        val target = when {
            velocityX > 600 -> startPage + 1
            velocityX < -600 -> startPage - 1
            else -> if (width > 0) (scrollX / width.toFloat()).roundToInt() else page
        }
        goTo(target, true)
    }
}
