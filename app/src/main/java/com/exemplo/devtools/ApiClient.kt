package com.exemplo.devtools

import android.app.AlertDialog
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

fun prettyJsonOrNull(s: String): String? = try {
    when (val v = JSONTokener(s.trim()).nextValue()) {
        is JSONObject -> v.toString(2)
        is JSONArray -> v.toString(2)
        else -> null
    }
} catch (e: Exception) {
    null
}

fun MainActivity.apiClientView(): View {
    val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
    val box = vbox()

    val method = box.add(spinner(methods), 0)
    val url = box.add(ed("https://api.exemplo.com/rota").apply { setText("https://api.github.com/zen") })
    val headers = box.add(ed("Headers (um por linha)\nAuthorization: Bearer xxx", 3, true))
    val body = box.add(ed("Body (POST/PUT/PATCH)", 4, true))
    val out = outputView()
    val info = tv("", 13f, GRAY)

    fun addHistory(m: String, u: String, h: String, b: String) {
        val old = try { JSONArray(prefs.getString("api_history", "[]")) } catch (e: Exception) { JSONArray() }
        val arr = JSONArray()
        arr.put(JSONObject().put("m", m).put("u", u).put("h", h).put("b", b))
        for (i in 0 until old.length()) {
            if (arr.length() >= 20) break
            val o = old.getJSONObject(i)
            if (o.optString("m") == m && o.optString("u") == u) continue
            arr.put(o)
        }
        prefs.edit().putString("api_history", arr.toString()).apply()
    }

    val row = hbox()
    row.add(btn("Enviar") {
        val m = method.selectedItem.toString()
        val u = url.text.toString().trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) { toast("URL deve começar com http(s)://"); return@btn }
        val hdrs = LinkedHashMap<String, String>()
        headers.text.toString().lines().forEach { l ->
            val i = l.indexOf(':')
            if (i > 0) hdrs[l.substring(0, i).trim()] = l.substring(i + 1).trim()
        }
        val b = body.text.toString()
        addHistory(m, u, headers.text.toString(), b)
        info.text = "Enviando…"
        out.text = ""
        async({ Http.request(m, u, hdrs, b.ifEmpty { null }) }) { res ->
            res.fold(onSuccess = { r ->
                info.text = "${r.code} • ${r.ms} ms • ${fmtSize(r.bytes.toLong())}"
                info.setTextColor(if (r.code in 200..299) GREEN else RED)
                val hs = r.headers.entries.sortedBy { it.key.lowercase() }
                    .joinToString("\n") { "${it.key}: ${it.value.joinToString(", ")}" }
                out.text = "$hs\n\n${prettyJsonOrNull(r.body) ?: r.body}"
            }, onFailure = {
                info.text = "Erro"
                info.setTextColor(RED)
                out.text = it.message ?: it.toString()
            })
        }
    }, 0, 1f, 0)
    row.add(btn("Histórico") {
        val arr = try { JSONArray(prefs.getString("api_history", "[]")) } catch (e: Exception) { JSONArray() }
        if (arr.length() == 0) { toast("Histórico vazio"); return@btn }
        val labels = Array(arr.length()) { "${arr.getJSONObject(it).optString("m")}  ${arr.getJSONObject(it).optString("u")}" }
        AlertDialog.Builder(this).setTitle("Histórico").setItems(labels) { _, i ->
            val o = arr.getJSONObject(i)
            method.setSelection(methods.indexOf(o.optString("m")).coerceAtLeast(0))
            url.setText(o.optString("u"))
            headers.setText(o.optString("h"))
            body.setText(o.optString("b"))
        }.show()
    }, 0, 1f, 0)
    row.add(btn("Copiar") { copyText(out.text.toString()) }, 0, 1f, 0)
    box.add(row, 8)
    box.add(info, 4)
    box.add(out, 6)
    return scroll(box)
}
