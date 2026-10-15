package com.vpsmanager

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors

private fun rgb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

private fun cubeLevel(x: Int): Int = if (x == 0) 0 else 55 + 40 * x

/** Paleta xterm de 256 cores. */
private fun xterm256(n: Int, palette: IntArray, bright: IntArray): Int = when {
    n < 8 -> palette[n.coerceAtLeast(0)]
    n < 16 -> bright[n - 8]
    n < 232 -> {
        val v = n - 16
        rgb(cubeLevel(v / 36), cubeLevel((v / 6) % 6), cubeLevel(v % 6))
    }
    else -> {
        val g = (8 + (n - 232) * 10).coerceAtMost(255)
        rgb(g, g, g)
    }
}

/**
 * Realce por padrões: pinta erros, avisos, sucessos, URLs, IPs, caminhos, datas e o
 * prompt user@host. Só atua em texto que o servidor NÃO coloriu (cores ANSI vencem).
 */
object Highlighter {
    private class Rule(val regex: Regex, val style: SpanStyle)

    private val RED = SpanStyle(color = Color(0xFFE06C75))
    private val YELLOW = SpanStyle(color = Color(0xFFE5C07B))
    private val GREEN = SpanStyle(color = Color(0xFF98C379))
    private val BLUE_LINK = SpanStyle(color = Color(0xFF61AFEF), textDecoration = TextDecoration.Underline)
    private val CYAN = SpanStyle(color = Color(0xFF56B6C2))
    private val MAGENTA = SpanStyle(color = Color(0xFFC678DD))
    private val GRAY = SpanStyle(color = Color(0xFF7F7F7F))

    private val rules = listOf(
        Rule(
            Regex("""(?i)\b(error|erro|failed|failure|fatal|denied|refused|critical|panic|exception|segfault|not found|no such file or directory)\b"""),
            RED
        ),
        Rule(Regex("""(?i)\b(warn(ing)?|aviso|deprecated|timed? ?out)\b"""), YELLOW),
        Rule(Regex("""(?i)\b(success(fully)?|ok|done|passed|started|enabled|active \(running\)|running)\b"""), GREEN),
        Rule(Regex("""https?://[^\s"'<>)]+"""), BLUE_LINK),
        Rule(Regex("""\b\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?(?:Z|[+-]\d{2}:?\d{2})?"""), GRAY),
        Rule(Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?\b"""), MAGENTA),
        Rule(Regex("""(?m)^[\w.-]+@[\w.-]+(?=:)"""), GREEN),
        Rule(Regex("""(?<![\w./-])(?:~|\.{1,2})?/[\w.@%+=:,~/-]+"""), CYAN),
        Rule(Regex("""\b\d{2}:\d{2}:\d{2}\b"""), GRAY)
    )

    fun apply(text: String, colors: List<Int>, add: (Int, Int, SpanStyle) -> Unit) {
        val taken = BooleanArray(text.length)
        for (rule in rules) {
            for (m in rule.regex.findAll(text)) {
                val s = m.range.first
                val e = m.range.last + 1
                if (e <= s || e > text.length) continue
                if (colors[s] != 0 || colors[e - 1] != 0) continue
                var free = true
                for (k in s until e) {
                    if (taken[k]) { free = false; break }
                }
                if (!free) continue
                for (k in s until e) taken[k] = true
                add(s, e, rule.style)
            }
        }
    }
}

/**
 * Emulador de terminal MÍNIMO: cursor na linha, \r, backspace, apagar linha,
 * cores ANSI (16, 256 e RGB). NÃO suporta apps de tela cheia (vim, htop, nano).
 */
class AnsiBuffer(private val maxChars: Int = 30_000) {
    private val chars = ArrayList<Char>()
    private val colors = ArrayList<Int>()
    private var cursor = 0
    private var color = 0
    private var state = 0 // 0 normal, 1 ESC, 2 CSI, 3 OSC, 4 OSC+ESC, 5 pular 1 char
    private val params = StringBuilder()

    @Synchronized
    fun feed(text: CharArray, len: Int) {
        for (i in 0 until len) feedChar(text[i])
        val drop = chars.size - maxChars
        if (drop > 0) {
            chars.subList(0, drop).clear()
            colors.subList(0, drop).clear()
            cursor = maxOf(0, cursor - drop)
        }
    }

    @Synchronized
    fun toAnnotated(highlight: Boolean): AnnotatedString {
        val plain = String(CharArray(chars.size) { chars[it] })
        return buildAnnotatedString {
            append(plain)
            var i = 0
            while (i < colors.size) {
                val c = colors[i]
                var j = i
                while (j < colors.size && colors[j] == c) j++
                if (c != 0) addStyle(SpanStyle(color = Color(c)), i, j)
                i = j
            }
            if (highlight) Highlighter.apply(plain, colors) { s, e, style -> addStyle(style, s, e) }
        }
    }

    private fun lineStart(): Int {
        var i = cursor
        while (i > 0 && chars[i - 1] != '\n') i--
        return i
    }

    private fun lineEnd(): Int {
        var i = cursor
        while (i < chars.size && chars[i] != '\n') i++
        return i
    }

    private fun feedChar(c: Char) {
        when (state) {
            1 -> when (c) {
                '[' -> { state = 2; params.setLength(0) }
                ']' -> state = 3
                '(', ')', '#', '%' -> state = 5
                else -> state = 0
            }
            2 -> if (c in '\u0040'..'\u007E') { csi(c); state = 0 } else params.append(c)
            3 -> if (c == '\u0007') state = 0 else if (c == '\u001B') state = 4
            4, 5 -> state = 0
            else -> normal(c)
        }
    }

    private fun normal(c: Char) {
        when (c) {
            '\u001B' -> state = 1
            '\r' -> cursor = lineStart()
            '\n' -> {
                cursor = chars.size
                chars.add('\n'); colors.add(0)
                cursor = chars.size
            }
            '\b' -> if (cursor > lineStart()) cursor--
            '\t' -> repeat(8 - (cursor - lineStart()) % 8) { put(' ') }
            else -> if (c >= ' ') put(c)
        }
    }

    private fun put(c: Char) {
        if (cursor < chars.size && chars[cursor] != '\n') {
            chars[cursor] = c
            colors[cursor] = color
        } else {
            chars.add(cursor, c)
            colors.add(cursor, color)
        }
        cursor++
    }

    private fun csi(final: Char) {
        val raw = params.toString()
        if (raw.startsWith("?") || raw.startsWith(">")) return
        val nums = raw.split(';').map { it.toIntOrNull() }
        val n = nums.firstOrNull() ?: 0
        when (final) {
            'm' -> sgr(nums)
            'K' -> when (n) {
                0 -> {
                    val end = lineEnd()
                    chars.subList(cursor, end).clear(); colors.subList(cursor, end).clear()
                }
                1 -> for (k in lineStart() until cursor) { chars[k] = ' '; colors[k] = 0 }
                else -> {
                    val s = lineStart(); val e = lineEnd()
                    chars.subList(s, e).clear(); colors.subList(s, e).clear()
                    cursor = s
                }
            }
            'J' -> when (n) {
                0 -> { chars.subList(cursor, chars.size).clear(); colors.subList(cursor, colors.size).clear() }
                2, 3 -> { chars.clear(); colors.clear(); cursor = 0 }
            }
            'H', 'f' -> if (raw.isEmpty() || raw == "1;1") cursor = 0
            'C' -> repeat(maxOf(n, 1)) { if (cursor < lineEnd()) cursor++ }
            'D' -> repeat(maxOf(n, 1)) { if (cursor > lineStart()) cursor-- }
            'P' -> repeat(maxOf(n, 1)) {
                if (cursor < lineEnd()) { chars.removeAt(cursor); colors.removeAt(cursor) }
            }
            '@' -> repeat(maxOf(n, 1)) { chars.add(cursor, ' '); colors.add(cursor, 0) }
        }
    }

    private fun sgr(nums: List<Int?>) {
        val list = nums.map { it ?: 0 }
        var i = 0
        while (i < list.size) {
            when (val code = list[i]) {
                0, 39 -> color = 0
                in 30..37 -> color = PALETTE[code - 30]
                in 90..97 -> color = BRIGHT[code - 90]
                38 -> when (list.getOrNull(i + 1)) {
                    5 -> {
                        list.getOrNull(i + 2)?.let { color = xterm256(it, PALETTE, BRIGHT) }
                        i += 2
                    }
                    2 -> {
                        val r = list.getOrNull(i + 2) ?: 0
                        val g = list.getOrNull(i + 3) ?: 0
                        val b = list.getOrNull(i + 4) ?: 0
                        color = rgb(r, g, b)
                        i += 4
                    }
                }
                48 -> when (list.getOrNull(i + 1)) {
                    5 -> i += 2
                    2 -> i += 4
                }
            }
            i++
        }
    }

    companion object {
        private val PALETTE = intArrayOf(
            0xFF7F7F7F.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
            0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFDCDFE4.toInt()
        )
        private val BRIGHT = intArrayOf(
            0xFFA0A0A0.toInt(), 0xFFFF7B86.toInt(), 0xFFB5E890.toInt(), 0xFFFFD98E.toInt(),
            0xFF7FC4FF.toInt(), 0xFFE08FFF.toInt(), 0xFF6FD3E0.toInt(), 0xFFFFFFFF.toInt()
        )
    }
}

/** Um shell remoto (uma aba de terminal). */
class TerminalState(val number: Int = 1) {
    var text by mutableStateOf(AnnotatedString(""))
    var connected by mutableStateOf(false)
    var connecting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var started = false

    @Volatile private var highlight = true
    private val buffer = AnsiBuffer()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writer = Executors.newSingleThreadExecutor()
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var out: OutputStream? = null

    /** Liga/desliga o realce e redesenha o texto atual. */
    fun applyHighlight(v: Boolean) {
        highlight = v
        scope.launch { text = buffer.toAnnotated(v) }
    }

    suspend fun connect(h: Host) {
        if (connected || connecting) return
        connecting = true
        error = null
        try {
            withContext(Dispatchers.IO) {
                val s = SshRunner.openSession(h)
                try {
                    val ch = s.openChannel("shell") as ChannelShell
                    ch.setPtyType("xterm-256color")
                    ch.setPtySize(56, 30, 0, 0)
                    val input = ch.inputStream
                    out = ch.outputStream
                    ch.connect(10_000)
                    session = s
                    channel = ch
                    connected = true
                    startReader(input)
                } catch (e: Exception) {
                    s.disconnect()
                    throw e
                }
            }
        } finally {
            connecting = false
        }
    }

    private fun startReader(input: InputStream) {
        scope.launch {
            val reader = InputStreamReader(input, Charsets.UTF_8)
            val buf = CharArray(4096)
            var last = 0L
            try {
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    buffer.feed(buf, n)
                    val now = System.currentTimeMillis()
                    // só redesenha quando não há mais dados pendentes (ou a cada 100 ms)
                    if (!reader.ready() || now - last > 100) {
                        text = buffer.toAnnotated(highlight)
                        last = now
                    }
                }
                text = buffer.toAnnotated(highlight)
            } catch (_: Exception) {
            }
            connected = false
            error = "Sessão encerrada"
        }
    }

    fun send(s: String) {
        val o = out ?: return
        try {
            writer.execute {
                try {
                    o.write(s.toByteArray())
                    o.flush()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    fun close() {
        val ch = channel
        val s = session
        scope.cancel()
        writer.shutdownNow()
        Thread {
            try { ch?.disconnect(); s?.disconnect() } catch (_: Exception) {}
        }.start()
    }
}
