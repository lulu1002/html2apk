package com.exemplo.devtools

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

val BLUE = Color.parseColor("#42A5F5")
val GREEN = Color.parseColor("#66BB6A")
val RED = Color.parseColor("#EF5350")
val YELLOW = Color.parseColor("#FFCA28")
val GRAY = Color.parseColor("#9E9E9E")
val CARD = Color.parseColor("#1E1E1E")

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.tv(text: CharSequence, size: Float = 15f, color: Int = Color.WHITE, mono: Boolean = false) =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (mono) typeface = Typeface.MONOSPACE
    }

fun Context.vbox(pad: Int = 12) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.hbox() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.btn(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
}

fun Context.ed(hint: String, lines: Int = 1, mono: Boolean = false) = EditText(this).apply {
    this.hint = hint
    setTextColor(Color.WHITE)
    setHintTextColor(Color.GRAY)
    if (mono) typeface = Typeface.MONOSPACE
    if (lines > 1) {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = lines
        maxLines = 30
        gravity = Gravity.TOP
    } else {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine()
    }
}

fun Context.spinner(items: List<String>) = Spinner(this).apply {
    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
}

fun Context.outputView(mono: Boolean = true) = tv("", 13f, Color.WHITE, mono).apply {
    setTextIsSelectable(true)
    setBackgroundColor(CARD)
    setPadding(dp(10), dp(10), dp(10), dp(10))
}

fun Context.scroll(v: View) = ScrollView(this).apply { addView(v) }

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun Context.copyText(text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("devtoolbox", text))
    toast("Copiado")
}

fun Context.pasteText(): String {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
}

fun <T : View> LinearLayout.add(v: T, top: Int = 6, weight: Float = 0f, w: Int = -1, h: Int = -2): T {
    addView(v, LinearLayout.LayoutParams(w, h, weight).apply {
        topMargin = (top * resources.displayMetrics.density).toInt()
    })
    return v
}

/** Roda [work] em outra thread e entrega o resultado na thread da UI. */
fun <T> Activity.async(work: () -> T, done: (Result<T>) -> Unit) {
    Thread {
        val r = runCatching(work)
        runOnUiThread { if (!isFinishing && !isDestroyed) done(r) }
    }.start()
}

fun fmtSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.1f MB".format(b / 1048576.0)
}
