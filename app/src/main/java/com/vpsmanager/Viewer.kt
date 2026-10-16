package com.vpsmanager

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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
private val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
private val TEXT_EXT = setOf(
    "txt", "log", "md", "json", "xml", "yml", "yaml", "toml", "ini", "conf", "cfg", "env",
    "sh", "bash", "zsh", "py", "js", "ts", "html", "htm", "css", "csv", "sql", "java", "kt",
    "c", "h", "cpp", "go", "rs", "php", "rb", "service", "list", "sources", "properties",
    "gradle", "lock", "tsv", "rules", "timer", "socket", "mount"
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

/** Tela de navegação de arquivos: lista ↔ visualizador. */
@Composable
fun FilesTab(
    host: Host,
    state: SftpState,
    onUntrusted: (HostKeyException, (Host) -> Unit) -> Unit,
    onOpenTerminal: (String) -> Unit
) {
    var viewing by remember { mutableStateOf<RemoteFile?>(null) }
    val v = viewing
    if (v != null) {
        FileViewer(host, state, v, joinPath(state.path, v.name)) { viewing = null }
    } else {
        FileBrowser(host, state, onUntrusted, onOpenTerminal) { viewing = it }
    }
}

@Composable
fun FileViewer(host: Host, state: SftpState, file: RemoteFile, remote: String, onClose: () -> Unit) {
    var forceText by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val kind = if (forceText) FileKind.TEXT else kindOf(file.name)

    fun requestClose() {
        if (dirty) confirmDiscard = true else onClose()
    }
    BackHandler(onBack = { requestClose() })

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Descartar alterações?") },
            text = { Text("Você tem alterações não salvas neste arquivo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text("Descartar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Continuar editando") }
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { requestClose() }) { Text("Fechar") }
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(humanSize(file.size), style = MaterialTheme.typography.labelSmall)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (kind) {
                FileKind.IMAGE -> ImageViewer(host, state, file, remote)
                FileKind.VIDEO -> MediaViewer(host, state, remote, isVideo = true)
                FileKind.AUDIO -> MediaViewer(host, state, remote, isVideo = false)
                FileKind.PDF -> PdfViewer(host, state, file, remote)
                FileKind.TEXT -> TextViewer(host, state, file, remote) { dirty = it }
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
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
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

private const val MAX_EDIT = 256 * 1024

/** Decodifica como UTF-8 e devolve null se houver bytes inválidos (evita corromper ao salvar). */
private fun decodeStrict(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

@Composable
fun TextViewer(
    host: Host,
    state: SftpState,
    file: RemoteFile,
    remote: String,
    onDirtyChange: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var original by remember { mutableStateOf("") }
    var canEdit by remember { mutableStateOf(false) }
    var crlf by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf("") }
    var confirmExit by remember { mutableStateOf(false) }

    val dirty = editing && draft != original
    LaunchedEffect(dirty) { onDirtyChange(dirty) }

    LaunchedEffect(remote) {
        try {
            val bytes = state.readHead(host, remote, MAX_TEXT)
            if (bytes.any { it == 0.toByte() }) {
                errorMsg = "Arquivo binário: não dá para exibir como texto."
                return@LaunchedEffect
            }
            val strict = decodeStrict(bytes)
            val text = strict ?: String(bytes, Charsets.UTF_8)
            val truncated = file.size > MAX_TEXT
            crlf = text.contains("\r\n")
            val normalized = text.replace("\r\n", "\n")
            original = normalized
            lines = normalized.replace("\r", "").split('\n')
            canEdit = strict != null && !truncated && file.size <= MAX_EDIT && !normalized.contains('\r')
            note = when {
                truncated -> "Mostrando só os primeiros ${humanSize(MAX_TEXT.toLong())} de ${humanSize(file.size)} (somente leitura)."
                file.size > MAX_EDIT -> "Arquivo maior que ${humanSize(MAX_EDIT.toLong())}: somente leitura."
                strict == null -> "Codificação diferente de UTF-8: somente leitura."
                !canEdit -> "Somente leitura."
                else -> ""
            }
        } catch (e: Exception) {
            errorMsg = e.message ?: "Erro ao ler o arquivo"
        }
    }

    fun save() {
        scope.launch {
            saving = true
            saveMsg = ""
            try {
                val out = if (crlf) draft.replace("\n", "\r\n") else draft
                state.writeBytes(host, remote, out.toByteArray(Charsets.UTF_8))
                original = draft
                lines = draft.replace("\r", "").split('\n')
                saveMsg = "Salvo ✓"
            } catch (e: Exception) {
                saveMsg = "Erro ao salvar: ${e.message}"
            }
            saving = false
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Sair da edição?") },
            text = { Text("As alterações não salvas serão perdidas.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    editing = false
                    saveMsg = ""
                }) { Text("Descartar") }
            },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Continuar") } }
        )
    }

    val l = lines
    when {
        errorMsg != null -> CenteredText(errorMsg!!, isError = true)
        l == null -> CenteredLoading("Carregando…")
        editing -> Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        saveMsg.isNotBlank() -> saveMsg
                        dirty -> "● alterado"
                        else -> "editando"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Button(onClick = { save() }, enabled = dirty && !saving) {
                    Text(if (saving) "Salvando…" else "Salvar")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    if (dirty) confirmExit = true else {
                        editing = false
                        saveMsg = ""
                    }
                }) { Text("Sair") }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    saveMsg = ""
                },
                modifier = Modifier.fillMaxSize().padding(8.dp),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false
                )
            )
        }
        else -> Column(Modifier.fillMaxSize()) {
            if (note.isNotBlank() || canEdit) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (note.isNotBlank()) note else saveMsg,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (canEdit) {
                        Button(onClick = {
                            draft = original
                            saveMsg = ""
                            editing = true
                        }) { Text("Editar") }
                    }
                }
            }
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(l) { line ->
                        Text(
                            if (line.isEmpty()) " " else line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
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

@OptIn(UnstableApi::class)
@Composable
fun MediaViewer(host: Host, state: SftpState, remote: String, isVideo: Boolean) {
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
    val player = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
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
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect)
            )
        } else {
            Text("🎵", fontSize = 64.sp, modifier = Modifier.padding(32.dp))
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                }) { Text("−10s") }
                Button(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                    Text(if (playing) "Pausar" else "Reproduzir")
                }
                OutlinedButton(onClick = { player.seekTo(player.currentPosition + 10_000) }) { Text("+10s") }
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
