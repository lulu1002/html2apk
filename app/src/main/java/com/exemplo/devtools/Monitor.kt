package com.exemplo.devtools

import android.app.AlertDialog
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

private fun normalizeUrl(s: String): String {
    val t = s.trim()
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
}

private fun checkSite(url: String): Triple<Int, Long, String?> {
    return try {
        var r = Http.request("HEAD", url, mapOf("User-Agent" to "DevToolbox-Monitor"), null, 10_000)
        if (r.code == 405 || r.code == 501 || r.code == 403) {
            r = Http.request("GET", url, mapOf("User-Agent" to "DevToolbox-Monitor"), null, 10_000)
        }
        Triple(r.code, r.ms, null)
    } catch (e: Exception) {
        Triple(-1, 0L, e.message ?: e.javaClass.simpleName)
    }
}

fun MainActivity.monitorView(): View {
    val box = vbox()
    val sites = (prefs.getString("monitor_sites", "") ?: "").lines().filter { it.isNotBlank() }.toMutableList()
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val statusViews = HashMap<String, TextView>()

    fun save() = prefs.edit().putString("monitor_sites", sites.joinToString("\n")).apply()

    fun checkOne(url: String) {
        val view = statusViews[url] ?: return
        view.text = "⏳ verificando…"
        view.setTextColor(GRAY)
        async({ checkSite(url) }) { res ->
            res.fold(onSuccess = { (code, ms, err) ->
                when {
                    code in 200..399 -> { view.text = "✅ $code • $ms ms"; view.setTextColor(GREEN) }
                    code > 0 -> { view.text = "⚠️ HTTP $code • $ms ms"; view.setTextColor(YELLOW) }
                    else -> { view.text = "❌ $err"; view.setTextColor(RED) }
                }
            }, onFailure = { view.text = "❌ ${it.message}"; view.setTextColor(RED) })
        }
    }

    fun render() {
        list.removeAllViews()
        statusViews.clear()
        for (url in sites) {
            val row = vbox(10).apply {
                setBackgroundColor(CARD)
                setOnClickListener { checkOne(url) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@monitorView).setTitle("Remover site?").setMessage(url)
                        .setPositiveButton("Remover") { _, _ -> sites.remove(url); save(); render() }
                        .setNegativeButton("Cancelar", null).show()
                    true
                }
            }
            row.add(tv(url, 15f), 0)
            statusViews[url] = row.add(tv("toque para verificar", 12f, GRAY), 2)
            list.add(row, 4)
        }
    }

    val input = box.add(ed("exemplo.com ou https://api.exemplo.com/health"), 0)
    val buttons = hbox()
    buttons.add(btn("Adicionar") {
        val u = input.text.toString().trim()
        if (u.isEmpty()) return@btn
        val n = normalizeUrl(u)
        if (n !in sites) { sites.add(n); save(); render() }
        input.setText("")
    }, 0, 1f, 0)
    buttons.add(btn("Verificar todos") { sites.forEach { checkOne(it) } }, 0, 1f, 0)
    box.add(buttons, 4)
    box.add(tv("Toque para verificar um site • segure para remover", 11f, GRAY), 2)
    box.add(list, 8)
    render()
    return scroll(box)
}
