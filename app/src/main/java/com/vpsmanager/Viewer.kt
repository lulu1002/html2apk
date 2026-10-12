package com.vpsmanager

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private const val MAX_IMAGE = 30 * 1024 * 1024
private const val MAX_TEXT = 512 * 1024
private const val MAX_PDF = 50L * 1024 * 1024

enum class FileKind { IMAGE, VIDEO, AUDIO, PDF, TEXT, OTHER }

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ogv", "mpg", "mpeg")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "mka", "amr", "mid", "midi")
private val TEXT_EXT = setOf(
    "txt", "log", "md", "markdown", "json", "xml", "yml", "yaml", "toml", "ini", "conf", "cfg", "env",
    "sh", "bash", "zsh", "py", "js", "ts", "jsx", "tsx", "vue", "html", "htm", "css", "scss", "less",
    "csv", "tsv", "sql", "java", "kt", "c", "h", "cpp", "go", "rs", "php", "rb", "lua", "pl", "swift",
    "dart", "bat", "ps1", "tf", "tex", "rst", "nfo", "srt", "vtt", "ass",
    "service", "list", "sources", "properties", "gradle", "lock", "rules", "timer", "socket", "mount",
    "gitignore", "dockerignore", "editorconfig"
)

fun kindOf(name: String): FileKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in IMAGE_EXT -> FileKind.IMAGE
        ext in VIDEO_EXT -> FileKind.VIDEO
        ext in AUDIO_EXT -> FileKind.AUDIO
        ext == "pdf" -> FileKind.PDF
        ext in TEXT_EXT -> FileKind.TEXT
        // sem extensão (Dockerfile, Makefile, README) ou dotfile (.bashrc)
        !name.substring(1.coerceAtMost(name.length)).contains('.') -> FileKind.TEXT
        else -> FileKind.OTHER
    }
}

/** Ícone usado na lista de arquivos. */
fun iconFor(f: RemoteFile): String = if (f.isDir) "📁" else when (kindOf(f.name)) {
    FileKind.IMAGE -> "🖼️"
    FileKind.VIDEO -> "🎬"
    FileKind.AUDIO -> "🎵"
    FileKind.PDF -> "📕"
    FileKind.TEXT -> "📝"
    FileKind.OTHER -> "📄"
}

/** Tela de navegação de arquivos: lista ↔ visualizador. */
@Composable
fun FilesTab(
    host: Host,
    state: SftpState,
    onUntrusted: (HostKeyException, (Host) -> Unit) -> Unit
) {
    var viewing by remember { mutableStateOf<RemoteFile?>(null) }
    val v = viewing
    if (v != null) {
        FileViewer(
            host = host,
            state = state,
            file = v,
            remote = joinPath(state.path, v.name),
            siblings = state.entries,
            onOpen = { viewing = it },
            onClose = { viewing = null }
        )
    } else {
        FileBrowser(host, state, onUntrusted) { viewing = it }
    }
}

@Composable
fun FileViewer(
    host: Host,
    state: SftpState,
    file: RemoteFile,
    remote: String,
    siblings: List<RemoteFile>,
    onOpen: (RemoteFile) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    BackHandler(enabled = state.fullscreen) { state.fullscreen = false }

    var forceText by remember(file.name) { mutableStateOf(false) }
    val kind = if (forceText) FileKind.TEXT else kindOf(file.name)

    // anterior / próximo entre os arquivos visualizáveis da pasta
    val playable = remember(siblings) {
        siblings.filter { !it.isDir && kindOf(it.name) != FileKind.OTHER }
    }
    val idx = playable.indexOfFirst { it.name == file.name }
    val prev = if (idx > 0) playable[idx - 1] else null
    val next = if (idx >= 0) playable.getOrNull(idx + 1) else null

    // ao terminar uma música, toca a próxima música da pasta
    val audioNext = remember(siblings, file.name) {
        val audios = siblings.filter { !it.isDir && kindOf(it.name) == FileKind.AUDIO }
        val i = audios.indexOfFirst { it.name == file.name }
        if (i >= 0) audios.getOrNull(i + 1) else null
    }

    // tela cheia (só vídeo): trava paisagem e esconde as barras do sistema
    val activity = LocalContext.current as? Activity
    val view = LocalView.current
    val fullscreen = state.fullscreen && kind == FileKind.VIDEO
    DisposableEffect(fullscreen) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(Unit) { onDispose { state.fullscreen = false } }

    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onClose) { Text("Fechar") }
                Column(Modifier.weight(1f)) {
                    Text(
                        file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        humanSize(file.size) + if (idx >= 0) "  ·  ${idx + 1}/${playable.size}" else "",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                TextButton(onClick = { prev?.let(onOpen) }, enabled = prev != null) { Text("‹") }
                TextButton(onClick = { next?.let(onOpen) }, enabled = next != null) { Text("›") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // key(): recria o conteúdo (e o player) ao trocar de arquivo
            key(file.name) {
                when (kind) {
                    FileKind.IMAGE -> ImageViewer(host, state, file, remote)
                    FileKind.VIDEO -> MediaViewer(
                        host, state, remote, isVideo = true, title = file.name,
                        fullscreen = fullscreen,
                        onToggleFullscreen = { state.fullscreen = !state.fullscreen },
                        onEnded = {}
                    )
                    FileKind.AUDIO -> MediaViewer(
                        host, state, remote, isVideo = false, title = file.name,
                        fullscreen = false,
                        onToggleFullscreen = {},
                        onEnded = { audioNext?.let(onOpen) }
                    )
                    FileKind.PDF -> PdfViewer(host, state, file, remote)
                    FileKind.TEXT -> TextViewer(host, state, file, remote)
                    FileKind.OTHER -> Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Sem visualização para este tipo de arquivo.")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { forceText = true }) { Text("Tentar abrir como texto") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Imagem

private fun exifRotation(bytes: ByteArray): Float = try {
    when (
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
} catch (_: Exception) {
    0f
}

private fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    val rotation = exifRotation(bytes)
    if (rotation == 0f) return bmp
    val m = Matrix().apply { postRotate(rotation) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

@Composable
fun ImageViewer(host: Host, state: SftpState, file: RemoteFile, remote: String) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(remote) {
        try {
            if (file.size > MAX_IMAGE) {
                errorMsg = "Imagem maior que ${humanSize(MAX_IMAGE.toLong())}."
                return@LaunchedEffect
            }
            val bytes = state.readHead(host, remote, MAX_IMAGE)
            val bmp = withContext(Dispatchers.Default) { decodeSampled(bytes, 2048) }
            if (bmp == null) errorMsg = "Formato de imagem não suportado neste aparelho."
            else bitmap = bmp.asImageBitmap()
        } catch (e: Exception) {
            errorMsg = e.message ?: "Erro ao carregar a imagem"
        }
    }

    val b = bitmap
    when {
        errorMsg != null -> CenteredText(errorMsg!!, isError = true)
        b == null -> CenteredLoading("Carregando imagem…")
        else -> Image(
            bitmap = b,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                // toque duplo: alterna entre 1x e 3x
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 3f
                        }
                    })
                }
                // pinça para zoom, arrastar para mover (limitado às bordas)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        val mx = size.width * (scale - 1f) / 2f
                        val my = size.height * (scale - 1f) / 2f
                        offset = if (scale == 1f) Offset.Zero else Offset(
                            (offset.x + pan.x).coerceIn(-mx, mx),
                            (offset.y + pan.y).coerceIn(-my, my)
                        )
                    }
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}

// ---------------------------------------------------------------- Texto

private fun strictUtf8(bytes: ByteArray, len: Int): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, 0, len))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

/** UTF-8 estrito; se o arquivo foi cortado, tolera até 3 bytes de caractere partido; senão cai para Latin-1. */
private fun decodeText(bytes: ByteArray, cutOff: Boolean): String {
    val tries = if (cutOff) 4 else 1
    for (cut in 0 until tries) {
        val len = bytes.size - cut
        if (len < 0) break
        strictUtf8(bytes, len)?.let { return it.removePrefix("\uFEFF") }
    }
    return if (cutOff) String(bytes, Charsets.UTF_8) else String(bytes, Charsets.ISO_8859_1)
}

private fun prettyJson(src: String): String = try {
    val t = src.trim()
    when {
        t.startsWith("{") -> JSONObject(t).toString(2)
        t.startsWith("[") -> JSONArray(t).toString(2)
        else -> src
    }.replace("\\/", "/")
} catch (_: Exception) {
    src
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Bullet(val text: String, val indent: Int) : MdBlock
    data class Code(val text: String) : MdBlock
    object Rule : MdBlock
}

private val NUMBERED = Regex("^\\d+\\. .*")

private fun parseMarkdown(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.split('\n')
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) {
            out.add(MdBlock.Para(para.toString().trim()))
            para.clear()
        }
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                out.add(MdBlock.Code(code.toString().trimEnd('\n')))
            }
            t.isEmpty() -> flush()
            t.startsWith("#") -> {
                flush()
                val level = t.takeWhile { it == '#' }.length.coerceAtMost(6)
                out.add(MdBlock.Heading(level, t.drop(level).trim()))
            }
            t == "---" || t == "***" || t == "___" -> {
                flush()
                out.add(MdBlock.Rule)
            }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") || NUMBERED.matches(t) -> {
                flush()
                val indent = line.takeWhile { it == ' ' }.length / 2
                val text = if (t[0].isDigit()) t else "• " + t.drop(2)
                out.add(MdBlock.Bullet(text, indent))
            }
            t.startsWith(">") -> {
                flush()
                out.add(MdBlock.Para("❝ " + t.trimStart('>').trim()))
            }
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(t)
            }
        }
        i++
    }
    flush()
    return out
}

/** **negrito** e `código` inline. */
private fun inlineMd(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        if (s.startsWith("**", i)) {
            val end = s.indexOf("**", i + 2)
            if (end > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        } else if (s[i] == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > 0) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33888888))) {
                    append(s.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        append(s[i])
        i++
    }
}

@Composable
private fun MarkdownView(src: String, baseSp: Int) {
    val blocks = remember(src) { parseMarkdown(src) }
    SelectionContainer {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(blocks) { b ->
                when (b) {
                    is MdBlock.Heading -> Text(
                        inlineMd(b.text),
                        fontWeight = FontWeight.Bold,
                        fontSize = (baseSp + when (b.level) {
                            1 -> 12
                            2 -> 8
                            3 -> 4
                            else -> 2
                        }).sp
                    )
                    is MdBlock.Para -> Text(inlineMd(b.text), fontSize = (baseSp + 2).sp)
                    is MdBlock.Bullet -> Text(
                        inlineMd(b.text),
                        fontSize = (baseSp + 2).sp,
                        modifier = Modifier.padding(start = (12 * b.indent).dp)
                    )
                    is MdBlock.Code -> Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            b.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = baseSp.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    MdBlock.Rule -> HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun TextViewer(host: Host, state: SftpState, file: RemoteFile, remote: String) {
    var raw by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var fromEnd by remember { mutableStateOf(false) }
    var fontSp by remember { mutableStateOf(12) }
    var pretty by remember { mutableStateOf(false) }
    var rendered by remember { mutableStateOf(true) }

    val truncated = file.size > MAX_TEXT
    val ext = file.name.substringAfterLast('.', "").lowercase()
    val isMd = ext == "md" || ext == "markdown"
    val isJson = ext == "json"

    LaunchedEffect(remote, fromEnd) {
        raw = null
        errorMsg = null
        try {
            val bytes = if (fromEnd) state.readTail(host, remote, file.size, MAX_TEXT)
            else state.readHead(host, remote, MAX_TEXT)
            if (bytes.any { it == 0.toByte() }) {
                errorMsg = "Arquivo binário: não dá para exibir como texto."
                return@LaunchedEffect
            }
            var text = decodeText(bytes, truncated)
            // lendo o fim: descarta a primeira linha, que veio cortada
            if (fromEnd) text = text.substringAfter('\n', text)
            raw = text.replace("\r", "")
        } catch (e: Exception) {
            errorMsg = e.message ?: "Erro ao ler o arquivo"
        }
    }

    val shown = remember(raw, pretty) {
        val r = raw ?: ""
        if (pretty && isJson) prettyJson(r) else r
    }
    val lines = remember(shown) { shown.split('\n') }
    val listState = rememberLazyListState()
    LaunchedEffect(lines, fromEnd) {
        if (fromEnd && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { fontSp = (fontSp - 2).coerceAtLeast(8) }) { Text("A−") }
            TextButton(onClick = { fontSp = (fontSp + 2).coerceAtMost(28) }) { Text("A+") }
            if (isMd) {
                TextButton(onClick = { rendered = !rendered }) {
                    Text(if (rendered) "Ver texto" else "Ver formatado")
                }
            }
            if (isJson && !truncated) {
                TextButton(onClick = { pretty = !pretty }) {
                    Text(if (pretty) "JSON original" else "Formatar JSON")
                }
            }
            if (truncated) {
                TextButton(onClick = { fromEnd = !fromEnd }) {
                    Text(if (fromEnd) "Ver início" else "Ver fim")
                }
            }
        }
        if (truncated) {
            Text(
                (if (fromEnd) "Mostrando só os últimos " else "Mostrando só os primeiros ") +
                    "${humanSize(MAX_TEXT.toLong())} de ${humanSize(file.size)}.",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                errorMsg != null -> CenteredText(errorMsg!!, isError = true)
                raw == null -> CenteredLoading("Carregando…")
                isMd && rendered -> MarkdownView(shown, fontSp)
                else -> SelectionContainer {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        state = listState
                    ) {
                        items(lines) { line ->
                            Text(
                                if (line.isEmpty()) " " else line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSp.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Vídeo / Áudio

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

@OptIn(UnstableApi::class)
@Composable
fun MediaViewer(
    host: Host,
    state: SftpState,
    remote: String,
    isVideo: Boolean,
    title: String,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onEnded: () -> Unit
) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var aspect by remember { mutableStateOf(16f / 9f) }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableStateOf(0f) }
    var speedIdx by remember { mutableStateOf(0) }
    val endedCb by rememberUpdatedState(onEnded)
    val player = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) endedCb()
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMsg = "Não foi possível reproduzir: ${error.message}"
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(remote) {
        try {
            state.connect(host)
            val uri = Uri.Builder().scheme("sftp").path(remote).build()
            val factory = DataSource.Factory { SftpDataSource { state.newChannel() } }
            val source = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(uri))
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true
            ready = true
        } catch (e: Exception) {
            errorMsg = e.message ?: "Erro ao abrir a mídia"
        }
    }

    LaunchedEffect(ready) {
        while (ready) {
            position = player.currentPosition
            val d = player.duration
            duration = if (d > 0) d else 0L
            delay(300)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isVideo) {
            val boxMod = if (fullscreen) Modifier.fillMaxWidth().weight(1f)
            else Modifier.fillMaxWidth().aspectRatio(aspect)
            Box(boxMod, contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).also {
                            it.keepScreenOn = true
                            player.setVideoSurfaceView(it)
                        }
                    },
                    modifier = Modifier.aspectRatio(aspect)
                )
            }
        } else {
            Text("🎵", fontSize = 64.sp, modifier = Modifier.padding(top = 32.dp, bottom = 8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (buffering && errorMsg == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (errorMsg != null) {
            Text(errorMsg!!, color = MaterialTheme.colorScheme.error)
        } else {
            val maxV = maxOf(duration, 1L).toFloat()
            Slider(
                value = (if (seeking) seekValue else position.toFloat()).coerceIn(0f, maxV),
                onValueChange = { seeking = true; seekValue = it },
                onValueChangeFinished = {
                    player.seekTo(seekValue.toLong())
                    seeking = false
                },
                valueRange = 0f..maxV,
                enabled = duration > 0
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtTime(position), style = MaterialTheme.typography.labelSmall)
                Text(fmtTime(duration), style = MaterialTheme.typography.labelSmall)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = {
                    player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                }) { Text("−10s") }
                Button(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                    Text(if (playing) "Pausar" else "Reproduzir")
                }
                OutlinedButton(onClick = { player.seekTo(player.currentPosition + 10_000) }) { Text("+10s") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    speedIdx = (speedIdx + 1) % SPEEDS.size
                    player.setPlaybackSpeed(SPEEDS[speedIdx])
                }) { Text("Velocidade ${SPEEDS[speedIdx]}x") }
                if (isVideo) {
                    TextButton(onClick = onToggleFullscreen) {
                        Text(if (fullscreen) "Sair da tela cheia" else "Tela cheia")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- PDF

class PdfDoc(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()
    val pageCount: Int = renderer.pageCount

    suspend fun render(index: Int, width: Int): Bitmap = mutex.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val h = (width.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    fun close() {
        try {
            renderer.close()
            pfd.close()
        } catch (_: Exception) {
        }
    }
}

@Composable
fun PdfViewer(host: Host, state: SftpState, file: RemoteFile, remote: String) {
    val context = LocalContext.current
    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(remote) {
        try {
            if (file.size > MAX_PDF) {
                errorMsg = "PDF maior que ${humanSize(MAX_PDF)}."
                return@LaunchedEffect
            }
            val dest = File(context.cacheDir, "preview.pdf")
            state.fetchToFile(host, remote, dest, file.size) { progress = it }
            doc = withContext(Dispatchers.IO) { PdfDoc(dest) }
        } catch (e: Exception) {
            errorMsg = e.message ?: "Erro ao abrir o PDF"
        }
    }
    DisposableEffect(Unit) { onDispose { doc?.close() } }

    val d = doc
    when {
        errorMsg != null -> CenteredText(errorMsg!!, isError = true)
        d == null -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Baixando… ${(progress * 100).toInt()}%")
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(d.pageCount) { i ->
                    var bmp by remember(i) { mutableStateOf<ImageBitmap?>(null) }
                    LaunchedEffect(i, widthPx) {
                        bmp = try {
                            d.render(i, widthPx).asImageBitmap()
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val b = bmp
                    if (b != null) {
                        Image(
                            bitmap = b,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().height(300.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- utilitários

@Composable
private fun CenteredText(text: String, isError: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CenteredLoading(text: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
