package com.vpsmanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Emulador de terminal MÍNIMO: cursor na linha, \r, backspace, apagar linha,
 * cores ANSI básicas. NÃO suporta apps de tela cheia (vim, htop, nano).
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
    fun toAnnotated(): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < chars.size) {
            val col = colors[i]
            val sb = StringBuilder()
            var j = i
            while (j < chars.size && colors[j] == col) {
                sb.append(chars[j]); j++
            }
            if (col == 0) append(sb.toString())
            else withStyle(SpanStyle(color = Color(col))) { append(sb.toString()) }
            i = j
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
                38, 48 -> {
                    if (list.getOrNull(i + 1) == 5) i += 2
                    else if (list.getOrNull(i + 1) == 2) i += 4
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

class TerminalState {
    var text by mutableStateOf(AnnotatedString(""))
    var connected by mutableStateOf(false)
    var connecting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var started = false

    private val buffer = AnsiBuffer()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writer = Executors.newSingleThreadExecutor()
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var out: OutputStream? = null

    suspend fun connect(h: Host) {
        if (connected || connecting) return
        connecting = true
        error = null
        try {
            withContext(Dispatchers.IO) {
                val s = SshRunner.openSession(h)
                try {
                    val ch = s.openChannel("shell") as ChannelShell
                    ch.setPtyType("xterm")
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
            try {
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    buffer.feed(buf, n)
                    text = buffer.toAnnotated()
                }
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

private val keyButtons = listOf(
    "Ctrl+C" to "\u0003",
    "Ctrl+D" to "\u0004",
    "Ctrl+L" to "\u000C",
    "Tab" to "\t",
    "Esc" to "\u001B",
    "↑" to "\u001B[A",
    "↓" to "\u001B[B"
)

@Composable
fun TerminalTab(
    host: Host,
    state: TerminalState,
    onUntrusted: (HostKeyException, (Host) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    fun connect(h: Host) {
        scope.launch {
            try {
                state.connect(h)
            } catch (e: HostKeyException) {
                onUntrusted(e) { trusted -> connect(trusted) }
            } catch (e: Exception) {
                state.error = "Erro: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!state.started) {
            state.started = true
            connect(host)
        }
    }
    LaunchedEffect(state.text.length) { scroll.scrollTo(scroll.maxValue) }

    fun sendLine() {
        state.send(input + "\r")
        input = ""
    }

    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!state.connected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.error ?: if (state.connecting) "Conectando…" else "Desconectado",
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { connect(host) }, enabled = !state.connecting) { Text("Conectar") }
            }
        }
        Surface(tonalElevation = 3.dp, modifier = Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                Text(
                    state.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.verticalScroll(scroll).padding(8.dp)
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(keyButtons) { (label, seq) ->
                AssistChip(onClick = { state.send(seq) }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Digite e envie") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(onSend = { sendLine() })
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { sendLine() }, enabled = state.connected) { Text("Enviar") }
        }
    }
}
