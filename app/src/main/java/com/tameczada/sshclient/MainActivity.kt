package com.tameczada.sshclient

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread

fun bg(err: (String) -> Unit, f: () -> Unit) =
    thread { try { f() } catch (e: Exception) { err(e.message ?: e.toString()) } }

class Ask(val title: String, val init: String, val ok: (String) -> Unit)

val Red = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App(this) } } }
    }
}

@Composable
fun App(ctx: Context) {
    var conns by remember { mutableStateOf(Store.load(ctx)) }
    var editing by remember { mutableStateOf<Conn?>(null) }
    var ssh by remember { mutableStateOf<Ssh?>(null) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val cur = ssh
    val ed = editing
    when {
        cur != null -> SessionScreen(cur) { cur.close(); ssh = null }
        ed != null -> EditConn(ed, { c ->
            conns = if (conns.any { it.id == c.id }) conns.map { if (it.id == c.id) c else it } else conns + c
            Store.save(ctx, conns); editing = null
        }, { editing = null })
        else -> Column(Modifier.padding(12.dp)) {
            Text("Conexões SSH", fontSize = 22.sp)
            Button({ editing = Conn() }) { Text("+ Nova conexão") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (msg.isNotEmpty()) Text(msg, color = Red)
            if (conns.isEmpty()) Text("Nenhuma conexão. Termux: host 127.0.0.1, porta 8022.")
            LazyColumn {
                items(conns, key = { it.id }) { c ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(c.name, fontSize = 18.sp)
                            Text("${c.user}@${c.host}:${c.port}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({
                                    msg = ""; busy = true
                                    val s = Ssh(c)
                                    bg({ msg = it; busy = false }) { s.connect(); ssh = s; busy = false }
                                }, enabled = !busy) { Text("Conectar") }
                                OutlinedButton({ editing = c }) { Text("Editar") }
                                OutlinedButton({ conns = conns.filter { it.id != c.id }; Store.save(ctx, conns) }) { Text("Apagar") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun F(l: String, v: String, o: (String) -> Unit) =
    OutlinedTextField(v, o, label = { Text(l) }, singleLine = true, modifier = Modifier.fillMaxWidth())

@Composable
fun EditConn(c: Conn, onSave: (Conn) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(c.name) }
    var host by remember { mutableStateOf(c.host) }
    var port by remember { mutableStateOf(c.port.toString()) }
    var user by remember { mutableStateOf(c.user) }
    var pass by remember { mutableStateOf(c.pass) }
    var key by remember { mutableStateOf(c.key) }
    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Conexão", fontSize = 22.sp)
        OutlinedButton({ host = "127.0.0.1"; port = "8022"; if (name.isEmpty()) name = "Termux" }) { Text("Modelo Termux") }
        F("Nome", name) { name = it }
        F("Host / IP", host) { host = it }
        F("Porta", port) { port = it }
        F("Usuário", user) { user = it }
        OutlinedTextField(pass, { pass = it }, label = { Text("Senha (ou senha da chave)") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text("Chave privada PEM (opcional)") },
            minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                onSave(c.copy(name = name.ifBlank { host }, host = host.trim(), port = port.toIntOrNull() ?: 22,
                    user = user.trim(), pass = pass, key = key))
            }) { Text("Salvar") }
            OutlinedButton(onCancel) { Text("Cancelar") }
        }
    }
}

@Composable
fun SessionScreen(ssh: Ssh, onExit: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tab == 0) Button({}) { Text("Terminal") } else OutlinedButton({ tab = 0 }) { Text("Terminal") }
            if (tab == 1) Button({}) { Text("Arquivos") } else OutlinedButton({ tab = 1 }) { Text("Arquivos") }
            OutlinedButton(onExit) { Text("Sair") }
        }
        if (tab == 0) Term(ssh) else Files(ssh)
    }
}

@Composable
fun Term(ssh: Ssh) {
    val ctx = LocalContext.current
    var inp by remember { mutableStateOf("") }
    var scs by remember { mutableStateOf(ScStore.load(ctx)) }
    var manage by remember { mutableStateOf(false) }
    var editSc by remember { mutableStateOf<Sc?>(null) }
    val sc = rememberScrollState()
    fun fire(s: Sc) = ssh.send(expand(s.cmd) + if (s.enter) "\n" else "")
    fun saveAll(l: List<Sc>) { scs = l; ScStore.save(ctx, l) }
    LaunchedEffect(ssh.term) { sc.scrollTo(sc.maxValue) }
    Column(Modifier.fillMaxSize().padding(4.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).verticalScroll(sc).padding(4.dp)) {
            SelectionContainer {
                Text(ssh.term, color = Color(0xFF33FF66), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Esc" to "\u001B", "Tab" to "\t", "Ctrl+C" to "\u0003", "Ctrl+D" to "\u0004", "Ctrl+Z" to "\u001A",
                "↑" to "\u001B[A", "↓" to "\u001B[B", "←" to "\u001B[D", "→" to "\u001B[C").forEach { (l, k) ->
                OutlinedButton({ ssh.send(k) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(l) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton({ manage = true }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("⚙ Atalhos") }
            scs.forEach { s ->
                Button({ fire(s) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(s.label) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(inp, { inp = it }, Modifier.weight(1f), singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrect = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { ssh.send(inp + "\n"); inp = "" }))
            Button({ ssh.send(inp + "\n"); inp = "" }) { Text("Enviar") }
        }
    }
    if (manage) {
        AlertDialog(onDismissRequest = { manage = false }, title = { Text("Atalhos") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    scs.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.label, Modifier.weight(1f))
                            TextButton({ editSc = s }) { Text("Editar") }
                            TextButton({ saveAll(scs.filter { it.id != s.id }) }) { Text("Apagar") }
                        }
                    }
                    Row {
                        TextButton({ editSc = Sc(label = "", cmd = "") }) { Text("+ Novo") }
                        TextButton({ saveAll(defaultScs()) }) { Text("Restaurar padrão") }
                    }
                }
            },
            confirmButton = { TextButton({ manage = false }) { Text("Fechar") } })
    }
    editSc?.let { s ->
        var l by remember(s) { mutableStateOf(s.label) }
        var c by remember(s) { mutableStateOf(s.cmd) }
        var en by remember(s) { mutableStateOf(s.enter) }
        AlertDialog(onDismissRequest = { editSc = null }, title = { Text("Atalho") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    F("Nome do botão", l) { l = it }
                    F("Comando", c) { c = it }
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(en, { en = it }); Text(" Enter no final") }
                    Text("Teclas: <ESC> <TAB> <UP> <DOWN> <LEFT> <RIGHT> <ENTER> <C-c> (= Ctrl+C)", fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton({
                    if (l.isNotBlank() && c.isNotEmpty()) {
                        val n = s.copy(label = l.trim(), cmd = c, enter = en)
                        saveAll(if (scs.any { it.id == s.id }) scs.map { if (it.id == s.id) n else it } else scs + n)
                    }
                    editSc = null
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton({ editSc = null }) { Text("Cancelar") } })
    }
}

@Composable
fun Files(ssh: Ssh) {
    var path by remember { mutableStateOf(ssh.cwd) }
    var files by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var err by remember { mutableStateOf("") }
    var openF by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf<Ask?>(null) }
    var del by remember { mutableStateOf<Entry?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    var mediaKind by remember { mutableStateOf(0) }
    var mediaFile by remember { mutableStateOf<File?>(null) }
    var mediaName by remember { mutableStateOf("") }
    var loadPct by remember { mutableStateOf(-1) }
    val e = { m: String -> err = m }
    fun go(p: String) = bg(e) { val r = ssh.real(p); files = ssh.list(r); path = r; ssh.cwd = r; err = "" }
    fun act(f: () -> Unit) = bg(e) { f(); go(path) }
    fun open(f: Entry) {
        val p = join(path, f.name)
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val kind = when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> 1
            "mp4", "mkv", "webm", "3gp", "avi", "mov", "mp3", "m4a", "ogg", "wav", "flac", "aac" -> 2
            else -> 0
        }
        if (f.dir) go(p)
        else if (kind > 0) {
            if (f.size > 500_000_000L) { err = "Arquivo muito grande (>500 MB)" }
            else {
                mediaName = f.name; loadPct = 0; err = ""
                bg({ err = it; loadPct = -1 }) {
                    val dest = File(ctx.cacheDir, "m_" + System.nanoTime() + "." + ext)
                    ssh.download(p, dest) { d, t -> loadPct = if (t > 0) (d * 100 / t).toInt() else 0 }
                    mediaFile = dest; mediaKind = kind; loadPct = -1
                }
            }
        }
        else if (f.size > 300_000) err = "Arquivo grande demais para editar (>300 KB)"
        else bg(e) { text = ssh.read(p); openF = p; err = "" }
    }
    LaunchedEffect(Unit) {
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("m_") }?.forEach { it.delete() }
        bg(e) { go(if (path.isEmpty()) ssh.home() else path) }
    }

    val of = openF
    val mf = mediaFile
    if (mf != null && mediaKind > 0) {
        MediaView(mediaName, mf, mediaKind) { mf.delete(); mediaFile = null; mediaKind = 0 }
    } else if (of != null) {
        Column(Modifier.fillMaxSize().padding(4.dp)) {
            Text(of, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ bg(e) { ssh.write(of, text); err = "Salvo ✓" } }) { Text("Salvar") }
                OutlinedButton({ openF = null; err = "" }) { Text("Fechar") }
            }
            if (err.isNotEmpty()) Text(err, color = if (err.startsWith("Salvo")) Color.Green else Red)
            OutlinedTextField(text, { text = it }, Modifier.weight(1f).fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
        }
    } else {
        Column(Modifier.fillMaxSize().padding(4.dp)) {
            Text(path, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton({ go(path.substringBeforeLast('/').ifEmpty { "/" }) }) { Text("↑ Subir") }
                OutlinedButton({ go(path) }) { Text("Atualizar") }
                OutlinedButton({ prompt = Ask("Nova pasta", "") { n -> act { ssh.mkdir(join(path, n)) } } }) { Text("+ Pasta") }
                OutlinedButton({ prompt = Ask("Novo arquivo", "") { n -> act { ssh.write(join(path, n), "") } } }) { Text("+ Arquivo") }
            }
            if (err.isNotEmpty()) Text(err, color = Red)
            if (loadPct >= 0) {
                Text("Baixando $mediaName: $loadPct%", fontSize = 12.sp)
                LinearProgressIndicator(progress = { loadPct / 100f }, modifier = Modifier.fillMaxWidth())
            }
            LazyColumn {
                items(files, key = { it.name }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { open(f) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (f.dir) "📁 " else "📄 ")
                        Text(f.name, Modifier.weight(1f))
                        if (!f.dir) Text("${f.size / 1024}K", fontSize = 11.sp)
                        Box {
                            TextButton({ menu = f.name }) { Text("⋮") }
                            DropdownMenu(menu == f.name, { menu = null }) {
                                if (!f.dir) DropdownMenuItem({ Text("Editar / ver") }, { menu = null; open(f) })
                                DropdownMenuItem({ Text("Mover / renomear") }, {
                                    menu = null
                                    val p = join(path, f.name)
                                    prompt = Ask("Novo caminho ou nome", p) { n ->
                                        act { ssh.move(p, if (n.startsWith("/")) n else join(path, n)) }
                                    }
                                })
                                DropdownMenuItem({ Text("Apagar") }, { menu = null; del = f })
                            }
                        }
                    }
                }
            }
        }
    }

    prompt?.let { a ->
        var v by remember(a) { mutableStateOf(a.init) }
        AlertDialog(onDismissRequest = { prompt = null }, title = { Text(a.title) },
            text = { OutlinedTextField(v, { v = it }, singleLine = true) },
            confirmButton = { TextButton({ prompt = null; if (v.isNotBlank()) a.ok(v.trim()) }) { Text("OK") } },
            dismissButton = { TextButton({ prompt = null }) { Text("Cancelar") } })
    }
    del?.let { f ->
        AlertDialog(onDismissRequest = { del = null }, title = { Text("Apagar ${f.name}?") },
            text = { Text(if (f.dir && !f.link) "A pasta e tudo dentro dela serão apagados." else "Não dá para desfazer.") },
            confirmButton = {
                TextButton({ del = null; act { ssh.delete(join(path, f.name), f.dir && !f.link) } }) { Text("Apagar") }
            },
            dismissButton = { TextButton({ del = null }) { Text("Cancelar") } })
    }
}

@Composable
fun MediaView(name: String, file: File, kind: Int, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClose) { Text("Fechar") }
            Text(name, Modifier.padding(start = 8.dp), fontSize = 13.sp)
        }
        if (kind == 1) ImageView(file) else VideoPlayer(file)
    }
}

@Composable
fun ImageView(file: File) {
    val bmp = remember(file) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var s = 1
        while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
    }
    var sc by remember { mutableStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    if (bmp == null) Text("Não foi possível abrir a imagem", color = Red)
    else Box(Modifier.fillMaxSize().clipToBounds().pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
            sc = (sc * zoom).coerceIn(1f, 8f)
            off = if (sc == 1f) Offset.Zero else off + pan
        }
    }) {
        Image(bmp.asImageBitmap(), null,
            Modifier.fillMaxSize().graphicsLayer(scaleX = sc, scaleY = sc, translationX = off.x, translationY = off.y),
            contentScale = ContentScale.Fit)
    }
}

@Composable
fun VideoPlayer(file: File) {
    var vv by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(file) { onDispose { vv?.stopPlayback() } }
    AndroidView(factory = { c ->
        VideoView(c).apply {
            val mc = MediaController(c)
            mc.setAnchorView(this)
            setMediaController(mc)
            setVideoPath(file.path)
            setOnPreparedListener { start() }
            vv = this
        }
    }, modifier = Modifier.fillMaxSize())
}
