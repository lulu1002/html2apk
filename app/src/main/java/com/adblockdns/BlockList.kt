package com.adblockdns

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class BlockList private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("blocklist", Context.MODE_PRIVATE)

    @Volatile private var listed: Set<String> = emptySet()
    @Volatile private var customBlock: Set<String> = emptySet()
    @Volatile private var customAllow: Set<String> = emptySet()

    val loadedCount = MutableStateFlow(0)
    val lastUpdate = MutableStateFlow(prefs.getLong(KEY_LAST_UPDATE, 0L))

    init {
        loadCustom()
    }

    private fun loadCustom() {
        customBlock = prefs.getStringSet(KEY_BLOCK, emptySet())?.toSet() ?: emptySet()
        customAllow = prefs.getStringSet(KEY_ALLOW, emptySet())?.toSet() ?: emptySet()
    }

    /** Relê as listas do disco (pode demorar: chame fora da thread principal). */
    fun reload() {
        val set = HashSet<String>(200_000)
        try {
            context.assets.open("default_blocklist.txt").bufferedReader().use { parseInto(it, set) }
        } catch (_: Exception) {
        }
        SOURCES.indices.forEach { i ->
            val f = File(context.filesDir, "list_$i.txt")
            if (f.exists()) {
                try {
                    f.bufferedReader().use { parseInto(it, set) }
                } catch (_: Exception) {
                }
            }
        }
        loadCustom()
        listed = set
        loadedCount.value = set.size
    }

    private fun parseInto(reader: BufferedReader, set: MutableSet<String>) {
        reader.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#' || line[0] == '!' || line.startsWith("@@")) return@forEachLine
            val domain: String? = if (line.startsWith("||")) {
                val end = line.indexOf('^')
                if (end > 2) line.substring(2, end) else null
            } else {
                val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1] else null
            }
            val d = domain?.lowercase() ?: return@forEachLine
            if (isValidDomain(d)) set.add(d)
        }
    }

    fun isBlocked(domain: String): Boolean {
        if (matches(customAllow, domain)) return false
        if (matches(customBlock, domain)) return true
        return matches(listed, domain)
    }

    /** Casa o domínio e todos os domínios-pai (sub.ads.com → ads.com → com). */
    private fun matches(set: Set<String>, domain: String): Boolean {
        if (set.isEmpty()) return false
        var d = domain
        while (true) {
            if (d in set) return true
            val i = d.indexOf('.')
            if (i < 0) return false
            d = d.substring(i + 1)
        }
    }

    // ----- listas do usuário -----
    fun customBlocked(): List<String> = customBlock.sorted()
    fun customAllowed(): List<String> = customAllow.sorted()

    fun addBlock(input: String) {
        val d = normalize(input) ?: return
        customBlock = customBlock + d
        customAllow = customAllow - d
        persist()
    }

    fun addAllow(input: String) {
        val d = normalize(input) ?: return
        customAllow = customAllow + d
        customBlock = customBlock - d
        persist()
    }

    fun removeBlock(d: String) {
        customBlock = customBlock - d
        persist()
    }

    fun removeAllow(d: String) {
        customAllow = customAllow - d
        persist()
    }

    private fun persist() {
        prefs.edit()
            .putStringSet(KEY_BLOCK, HashSet(customBlock))
            .putStringSet(KEY_ALLOW, HashSet(customAllow))
            .apply()
    }

    private fun normalize(input: String): String? {
        val d = input.trim().lowercase().removePrefix("*.")
        return if (isValidDomain(d)) d else null
    }

    private fun isValidDomain(d: String): Boolean {
        if (d.length > 253 || !d.contains('.')) return false
        if (d.all { it.isDigit() || it == '.' }) return false
        return d.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }
    }

    /** Baixa as listas e recarrega. Retorna quantos domínios estão ativos. */
    suspend fun updateFromNetwork(): Int = withContext(Dispatchers.IO) {
        var ok = 0
        var lastError: Exception? = null
        SOURCES.forEachIndexed { i, url ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val tmp = File(context.filesDir, "list_$i.tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(File(context.filesDir, "list_$i.txt"))
                ok++
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (ok == 0) throw lastError ?: Exception("nenhuma fonte disponível")
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_UPDATE, now).apply()
        lastUpdate.value = now
        reload()
        loadedCount.value
    }

    companion object {
        private const val KEY_BLOCK = "custom_block"
        private const val KEY_ALLOW = "custom_allow"
        private const val KEY_LAST_UPDATE = "last_update"

        val SOURCES = listOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
        )

        @Volatile private var instance: BlockList? = null

        fun get(context: Context): BlockList =
            instance ?: synchronized(this) {
                instance ?: BlockList(context.applicationContext).also {
                    instance = it
                    Thread { it.reload() }.start()
                }
            }
    }
}
