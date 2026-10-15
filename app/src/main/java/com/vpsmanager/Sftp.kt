package com.vpsmanager

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RemoteFile(val name: String, val isDir: Boolean, val size: Long)

fun joinPath(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

fun humanSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

private fun displayName(cr: ContentResolver, uri: Uri): String {
    cr.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) return c.getString(i)
    }
    return uri.lastPathSegment ?: "arquivo"
}

class SftpState {
    var path by mutableStateOf("")
    var entries by mutableStateOf<List<RemoteFile>>(emptyList())
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var started = false

    private var session: Session? = null
    private var sftp: ChannelSftp? = null

    private suspend fun ensure(h: Host): ChannelSftp = withContext(Dispatchers.IO) {
        val current = sftp
        if (current != null && current.isConnected && session?.isConnected == true) {
            return@withContext current
        }
        session?.disconnect()
        val s = SshRunner.openSession(h)
        val ch = s.openChannel("sftp") as ChannelSftp
        ch.connect(10_000)
        session = s
        sftp = ch
        ch
    }

    /** Lista [dir] (ou a pasta atual / home, se nulo). */
    suspend fun open(h: Host, dir: String? = null) {
        busy = true
        try {
            val result = withContext(Dispatchers.IO) {
                val ch = ensure(h)
                val target = dir ?: path.ifBlank { ch.pwd() }
                ch.cd(target)
                val resolved = ch.pwd()
                val list = ch.ls(".")
                    .filterIsInstance<ChannelSftp.LsEntry>()
                    .filter { it.filename != "." && it.filename != ".." }
                    .map { RemoteFile(it.filename, it.attrs.isDir, it.attrs.size) }
                    .sortedWith(compareByDescending<RemoteFile> { it.isDir }.thenBy { it.name.lowercase() })
                resolved to list
            }
            path = result.first
            entries = result.second
            message = ""
        } finally {
            busy = false
        }
    }

    suspend fun download(h: Host, name: String, uri: Uri, cr: ContentResolver) {
        busy = true
        try {
            withContext(Dispatchers.IO) {
                val ch = ensure(h)
                val out = cr.openOutputStream(uri) ?: error("Não foi possível abrir o destino")
                out.use { ch.get(joinPath(path, name), it) }
            }
            message = "Baixado: $name"
        } finally {
            busy = false
        }
    }

    suspend fun upload(h: Host, uri: Uri, cr: ContentResolver) {
        busy = true
        try {
            val name = displayName(cr, uri)
            withContext(Dispatchers.IO) {
                val ch = ensure(h)
                val input = cr.openInputStream(uri) ?: error("Não foi possível ler o arquivo")
                input.use { ch.put(it, joinPath(path, name)) }
            }
            message = "Enviado: $name"
        } finally {
            busy = false
        }
        open(h, path)
    }

    suspend fun delete(h: Host, f: RemoteFile) {
        busy = true
        try {
            withContext(Dispatchers.IO) {
                val ch = ensure(h)
                val p = joinPath(path, f.name)
                if (f.isDir) ch.rmdir(p) else ch.rm(p)
            }
            message = "Excluído: ${f.name}"
        } finally {
            busy = false
        }
        open(h, path)
    }

    suspend fun mkdir(h: Host, name: String) {
        busy = true
        try {
            withContext(Dispatchers.IO) { ensure(h).mkdir(joinPath(path, name)) }
            message = "Pasta criada: $name"
        } finally {
            busy = false
        }
        open(h, path)
    }

    /** Garante que existe uma sessão SFTP conectada. */
    suspend fun connect(h: Host) {
        ensure(h)
    }

    /** Canal SFTP extra na mesma sessão (usado no streaming de mídia). Bloqueante. */
    fun newChannel(): ChannelSftp {
        val s = session ?: error("Sem sessão SFTP")
        val ch = s.openChannel("sftp") as ChannelSftp
        ch.connect(10_000)
        return ch
    }

    /** Lê até [max] bytes do início do arquivo remoto. */
    suspend fun readHead(h: Host, remote: String, max: Int): ByteArray = withContext(Dispatchers.IO) {
        ensure(h)
        val ch = newChannel()
        try {
            ch.get(remote).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < max) {
                    val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            ch.disconnect()
        }
    }

    /** Baixa o arquivo remoto inteiro para [dest], reportando o progresso (0..1). */
    suspend fun fetchToFile(
        h: Host,
        remote: String,
        dest: File,
        total: Long,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        ensure(h)
        val ch = newChannel()
        try {
            ch.get(remote).use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            ch.disconnect()
        }
    }

    fun close() {
        val ch = sftp
        val s = session
        Thread {
            try { ch?.disconnect(); s?.disconnect() } catch (_: Exception) {}
        }.start()
    }
}

@Composable
fun FileBrowser(
    host: Host,
    state: SftpState,
    onUntrusted: (HostKeyException, (Host) -> Unit) -> Unit,
    onOpenFile: (RemoteFile) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var toDelete by remember { mutableStateOf<RemoteFile?>(null) }
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf("") }

    fun guarded(h: Host, block: suspend (Host) -> Unit) {
        scope.launch {
            try {
                block(h)
            } catch (e: HostKeyException) {
                onUntrusted(e) { trusted -> guarded(trusted, block) }
            } catch (e: Exception) {
                state.message = "Erro: ${e.message}"
            }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) guarded(host) { state.upload(it, uri, context.contentResolver) }
    }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val f = pendingDownload
        if (uri != null && f != null) guarded(host) { state.download(it, f.name, uri, context.contentResolver) }
        pendingDownload = null
    }

    LaunchedEffect(Unit) {
        if (!state.started) {
            state.started = true
            guarded(host) { state.open(it) }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Excluir ${f.name}?") },
            text = { Text(if (f.isDir) "A pasta precisa estar vazia." else "Isso não pode ser desfeito.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    guarded(host) { state.delete(it, f) }
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } }
        )
    }

    if (showMkdir) {
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            title = { Text("Nova pasta") },
            text = {
                OutlinedTextField(newFolder, { newFolder = it }, singleLine = true, label = { Text("Nome") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newFolder.trim()
                    showMkdir = false
                    newFolder = ""
                    if (name.isNotEmpty()) guarded(host) { state.mkdir(it, name) }
                }) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { showMkdir = false }) { Text("Cancelar") } }
        )
    }

    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(state.path.ifBlank { "…" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { uploadLauncher.launch("*/*") }, enabled = !state.busy) { Text("Subir") }
            OutlinedButton(onClick = { showMkdir = true }, enabled = !state.busy) { Text("Nova pasta") }
            OutlinedButton(
                onClick = { guarded(host) { state.open(it, state.path.ifBlank { null }) } },
                enabled = !state.busy
            ) { Text("Atualizar") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodySmall)

        LazyColumn(Modifier.weight(1f)) {
            if (state.path.isNotBlank() && state.path != "/") {
                item {
                    Text(
                        "📁 ..",
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !state.busy) {
                                val parent = state.path.substringBeforeLast('/').ifEmpty { "/" }
                                guarded(host) { state.open(it, parent) }
                            }
                            .padding(vertical = 12.dp)
                    )
                }
            }
            items(state.entries, key = { it.name }) { f ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = !state.busy) {
                            if (f.isDir) guarded(host) { state.open(it, joinPath(state.path, f.name)) }
                            else onOpenFile(f)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (f.isDir) "📁 " else "📄 ") + f.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!f.isDir) Text(humanSize(f.size), style = MaterialTheme.typography.labelSmall)
                    }
                    if (!f.isDir) {
                        TextButton(onClick = {
                            pendingDownload = f
                            downloadLauncher.launch(f.name)
                        }) { Text("Baixar") }
                    }
                    TextButton(onClick = { toDelete = f }) { Text("Excluir") }
                }
            }
        }
    }
}
