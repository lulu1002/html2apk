package com.exemplo.radio

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Busca estações brasileiras no diretório público Radio Browser (radio-browser.info). */
object RadioBrowser {

    data class Result(val name: String, val url: String, val detail: String)

    // Espelhos da API; tenta um por vez se algum estiver fora do ar.
    private val hosts = listOf(
        "de1.api.radio-browser.info",
        "de2.api.radio-browser.info",
        "fi1.api.radio-browser.info"
    )

    /** Chamar fora da thread principal. [query] vazio = estações mais populares do Brasil. */
    fun search(query: String): List<Result> {
        val name = if (query.isBlank()) "" else "&name=" + URLEncoder.encode(query.trim(), "UTF-8")
        var lastError: Exception? = null

        for (host in hosts) {
            try {
                val url = "https://$host/json/stations/search?countrycode=BR&hidebroken=true" +
                    "&order=clickcount&reverse=true&limit=60$name"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "RadioApp/1.0")
                try {
                    if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    return parse(body)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Sem resposta")
    }

    private fun parse(json: String): List<Result> {
        val arr = JSONArray(json)
        val seen = HashSet<String>()
        val out = ArrayList<Result>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val streamUrl = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
            val name = o.optString("name").trim()
            if (streamUrl.isEmpty() || name.isEmpty() || !seen.add(streamUrl)) continue

            val codec = o.optString("codec")
            val bitrate = o.optInt("bitrate", 0)
            val state = o.optString("state")
            val detail = listOf(
                if (codec.isNotBlank()) codec else "",
                if (bitrate > 0) "${bitrate}kbps" else "",
                state
            ).filter { it.isNotBlank() }.joinToString(" • ")
            out.add(Result(name, streamUrl, detail))
        }
        return out
    }
}
