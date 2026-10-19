package com.exemplo.devtools

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.View
import android.widget.CheckBox
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.StringReader
import java.io.StringWriter
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.regex.Pattern
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

// ---------------------------------------------------------------- JSON / XML

fun MainActivity.formatterView(): View {
    val box = vbox()
    val input = box.add(ed("Cole aqui o JSON ou XML", 8, true), 0)
    val out = outputView()

    fun exec(op: (String) -> String) {
        try {
            out.setTextColor(Color.WHITE)
            out.text = op(input.text.toString())
        } catch (e: Exception) {
            out.setTextColor(RED)
            out.text = "Inválido: ${e.message}"
        }
    }

    fun parseJson(s: String): Any {
        val v = JSONTokener(s.trim()).nextValue()
        if (v !is JSONObject && v !is JSONArray) throw IllegalArgumentException("não é um objeto/array JSON")
        return v
    }

    val r1 = hbox()
    r1.add(btn("JSON bonito") {
        exec { when (val v = parseJson(it)) { is JSONObject -> v.toString(2); else -> (v as JSONArray).toString(2) } }
    }, 0, 1f, 0)
    r1.add(btn("JSON mini") { exec { parseJson(it).toString() } }, 0, 1f, 0)
    r1.add(btn("XML bonito") {
        exec { xml ->
            val t = TransformerFactory.newInstance().newTransformer()
            t.setOutputProperty(OutputKeys.INDENT, "yes")
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            val w = StringWriter()
            t.transform(StreamSource(StringReader(xml.trim())), StreamResult(w))
            w.toString().trim()
        }
    }, 0, 1f, 0)
    box.add(r1, 6)

    val r2 = hbox()
    r2.add(btn("Colar") { input.setText(pasteText()) }, 0, 1f, 0)
    r2.add(btn("Copiar saída") { copyText(out.text.toString()) }, 0, 1f, 0)
    r2.add(btn("Limpar") { input.setText(""); out.text = "" }, 0, 1f, 0)
    box.add(r2, 0)
    box.add(out, 8)
    return scroll(box)
}

// ---------------------------------------------------------------- Conversores

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

private fun digest(alg: String, s: String) = MessageDigest.getInstance(alg).digest(s.toByteArray()).hex()

private fun fmtDate(ms: Long, utc: Boolean): String {
    val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    if (utc) f.timeZone = TimeZone.getTimeZone("UTC")
    return f.format(Date(ms))
}

private fun b64url(s: String): String = String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))

private val OPS: LinkedHashMap<String, (String) -> String> = linkedMapOf(
    "Base64 codificar" to { s: String -> Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP) },
    "Base64 decodificar" to { s: String -> String(Base64.decode(s.trim().replace('-', '+').replace('_', '/'), Base64.DEFAULT)) },
    "URL codificar" to { s: String -> URLEncoder.encode(s, "UTF-8") },
    "URL decodificar" to { s: String -> URLDecoder.decode(s, "UTF-8") },
    "Hex codificar" to { s: String -> s.toByteArray().hex() },
    "Hex decodificar" to { s: String ->
        val h = s.filter { !it.isWhitespace() }
        require(h.length % 2 == 0) { "quantidade ímpar de dígitos" }
        String(ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    },
    "MD5" to { s: String -> digest("MD5", s) },
    "SHA-1" to { s: String -> digest("SHA-1", s) },
    "SHA-256" to { s: String -> digest("SHA-256", s) },
    "SHA-512" to { s: String -> digest("SHA-512", s) },
    "Timestamp → data" to { s: String ->
        if (s.isBlank()) {
            val now = System.currentTimeMillis()
            "Agora: ${now / 1000} (s) / $now (ms)\nUTC:   ${fmtDate(now, true)}\nLocal: ${fmtDate(now, false)}"
        } else {
            val v = s.trim().toLong()
            val ms = if (v > 100_000_000_000L) v else v * 1000
            "UTC:   ${fmtDate(ms, true)}\nLocal: ${fmtDate(ms, false)}"
        }
    },
    "Data → timestamp" to { s: String ->
        if (s.isBlank()) {
            val now = System.currentTimeMillis()
            "${now / 1000} (s)\n$now (ms)"
        } else {
            val txt = s.trim()
            val patterns = listOf(
                "yyyy-MM-dd'T'HH:mm:ss'Z'" to true,
                "yyyy-MM-dd HH:mm:ss" to false,
                "yyyy-MM-dd'T'HH:mm:ss" to false,
                "yyyy-MM-dd" to false
            )
            var result: String? = null
            for ((p, utc) in patterns) {
                try {
                    val f = SimpleDateFormat(p, Locale.US)
                    if (utc) f.timeZone = TimeZone.getTimeZone("UTC")
                    val d = f.parse(txt) ?: continue
                    result = "${d.time / 1000} (s)\n${d.time} (ms)" + if (utc) "\n(interpretado como UTC)" else "\n(interpretado no fuso local)"
                    break
                } catch (e: Exception) { /* tenta o próximo formato */ }
            }
            result ?: "Formato não reconhecido. Use 2026-10-19 14:30:00 ou 2026-10-19T14:30:00Z"
        }
    },
    "JWT decodificar" to { s: String ->
        val parts = s.trim().split('.')
        require(parts.size >= 2) { "JWT precisa ter ao menos 2 partes separadas por ponto" }
        val header = prettyJsonOrNull(b64url(parts[0])) ?: b64url(parts[0])
        val payloadRaw = b64url(parts[1])
        val payload = prettyJsonOrNull(payloadRaw) ?: payloadRaw
        val extra = try {
            val o = JSONObject(payloadRaw)
            buildString {
                if (o.has("exp")) append("\nexp: ${fmtDate(o.getLong("exp") * 1000, true)} UTC")
                if (o.has("iat")) append("\niat: ${fmtDate(o.getLong("iat") * 1000, true)} UTC")
            }
        } catch (e: Exception) { "" }
        "HEADER\n$header\n\nPAYLOAD\n$payload$extra\n\n(a assinatura não é verificada)"
    },
    "UUID aleatório" to { _: String -> UUID.randomUUID().toString() }
)

fun MainActivity.convertView(): View {
    val box = vbox()
    val op = box.add(spinner(OPS.keys.toList()), 0)
    val input = box.add(ed("Texto de entrada (algumas operações aceitam vazio)", 5, true))
    val out = outputView()
    box.add(btn("Converter") {
        try {
            out.setTextColor(Color.WHITE)
            out.text = OPS[op.selectedItem.toString()]!!.invoke(input.text.toString())
        } catch (e: Exception) {
            out.setTextColor(RED)
            out.text = "Erro: ${e.message ?: e.javaClass.simpleName}"
        }
    })
    val r = hbox()
    r.add(btn("Colar") { input.setText(pasteText()) }, 0, 1f, 0)
    r.add(btn("Copiar saída") { copyText(out.text.toString()) }, 0, 1f, 0)
    box.add(r, 0)
    box.add(out, 8)
    return scroll(box)
}

// ---------------------------------------------------------------- Regex

fun MainActivity.regexView(): View {
    val box = vbox()
    val pattern = box.add(ed("Expressão regular (ex.: (\\d+)-(\\w+))", 1, true), 0)
    val flags = hbox()
    val ci = CheckBox(this).apply { text = "i (ignorar caixa)"; setTextColor(Color.WHITE) }
    val ml = CheckBox(this).apply { text = "m (multilinha)"; setTextColor(Color.WHITE) }
    val dot = CheckBox(this).apply { text = "s (. pega \\n)"; setTextColor(Color.WHITE) }
    flags.addView(ci); flags.addView(ml); flags.addView(dot)
    box.add(flags, 2)
    val text = box.add(ed("Texto de teste", 6, true))
    val out = outputView()

    fun test() {
        val p = pattern.text.toString()
        val t = text.text.toString()
        var f = 0
        if (ci.isChecked) f = f or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if (ml.isChecked) f = f or Pattern.MULTILINE
        if (dot.isChecked) f = f or Pattern.DOTALL
        if (p.isEmpty()) { out.text = ""; return }
        out.setTextColor(Color.WHITE)
        out.text = "Executando…"
        async({
            val m = Pattern.compile(p, f).matcher(t)
            val sb = StringBuilder()
            var n = 0
            while (m.find() && n < 100) {
                n++
                sb.appendLine("#$n [${m.start()}-${m.end()}] \"${m.group()}\"")
                for (g in 1..m.groupCount()) sb.appendLine("     \$$g = ${m.group(g)}")
            }
            if (n == 0) "Nenhuma correspondência" else "$n correspondência(s)${if (n == 100) " (limitado a 100)" else ""}\n\n$sb"
        }) { res ->
            res.fold(onSuccess = { out.text = it }, onFailure = {
                out.setTextColor(RED)
                out.text = "Regex inválida: ${it.message}"
            })
        }
    }
    box.add(btn("Testar") { test() }, 6)
    box.add(out, 6)
    return scroll(box)
}

// ---------------------------------------------------------------- Cores

fun MainActivity.colorView(): View {
    val box = vbox()
    val input = box.add(ed("#1E88E5   ou   30, 136, 229", 1, true).apply { setText("#1E88E5") }, 0)
    val swatch = View(this).apply { setBackgroundColor(Color.parseColor("#1E88E5")) }
    box.add(swatch, 8, h = dp(80))
    val out = box.add(outputView(), 8)

    fun update() {
        val s = input.text.toString().trim()
        val rgb: Triple<Int, Int, Int>? = try {
            if (s.startsWith("#") || Regex("^[0-9a-fA-F]{6}$").matches(s) || Regex("^[0-9a-fA-F]{3}$").matches(s)) {
                var h = s.removePrefix("#")
                if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
                val c = Color.parseColor("#$h")
                Triple(Color.red(c), Color.green(c), Color.blue(c))
            } else {
                val n = Regex("\\d+").findAll(s).map { it.value.toInt() }.toList()
                if (n.size >= 3 && n.take(3).all { it in 0..255 }) Triple(n[0], n[1], n[2]) else null
            }
        } catch (e: Exception) { null }

        if (rgb == null) { out.text = "Cor inválida"; return }
        val (r, g, b) = rgb
        swatch.setBackgroundColor(Color.rgb(r, g, b))
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        // HSL a partir de RGB
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val mx = maxOf(rf, gf, bf); val mn = minOf(rf, gf, bf)
        val l = (mx + mn) / 2f
        val sat = if (mx == mn) 0f else (mx - mn) / (1f - Math.abs(2f * l - 1f))
        out.text = "HEX   #%02X%02X%02X\nRGB   %d, %d, %d\nHSL   %d°, %d%%, %d%%\nHSV   %d°, %d%%, %d%%\nAndroid  0xFF%02X%02X%02X".format(
            r, g, b, r, g, b,
            hsv[0].toInt(), (sat * 100).toInt(), (l * 100).toInt(),
            hsv[0].toInt(), (hsv[1] * 100).toInt(), (hsv[2] * 100).toInt(),
            r, g, b
        )
    }
    input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = update()
    })
    update()
    box.add(btn("Copiar") { copyText(out.text.toString()) })
    return scroll(box)
}
