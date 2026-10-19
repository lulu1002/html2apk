package com.exemplo.tv

data class Channel(
    val name: String,
    val url: String,
    val groups: List<String>,
    val referrer: String?,
    val userAgent: String?
)

/** Lê playlists M3U/M3U8 (formato #EXTINF) usadas por listas públicas de IPTV. */
object M3uParser {

    private val attr = Regex("([\\w-]+)=\"([^\"]*)\"")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        val seen = HashSet<String>()

        var name: String? = null
        var groups: List<String> = emptyList()
        var referrer: String? = null
        var userAgent: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    name = line.substringAfterLast(',', "").trim().ifEmpty { attrs["tvg-name"] ?: "Canal" }
                    groups = (attrs["group-title"] ?: "")
                        .split(';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    referrer = null
                    userAgent = null
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    if (key == "http-referrer" || key == "http-referer") referrer = value
                    if (key == "http-user-agent") userAgent = value
                }
                line.startsWith("#") -> Unit
                line.startsWith("http://") || line.startsWith("https://") -> {
                    if (name != null && seen.add(line)) {
                        out.add(Channel(name, line, groups, referrer, userAgent))
                    }
                    name = null
                    groups = emptyList()
                    referrer = null
                    userAgent = null
                }
            }
        }
        return out
    }
}
