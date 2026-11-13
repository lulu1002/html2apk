package com.example.meutuber

import android.Manifest
import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var hasVideo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Root { hasVideo = it } }
            }
        }
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= 26 && hasVideo && Player.get(this).isPlaying)
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
    }
}

@Composable
fun Root(onVideo: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var home by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var found by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var music by remember { mutableStateOf(false) }
    var lib by remember { mutableIntStateOf(0) }
    var channel by remember { mutableStateOf<Pair<String, List<Entry>>?>(null) }
    var current by remember { mutableStateOf<Resolved?>(null) }
    var audioOnly by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) } // força recomposição após mudar favoritos

    fun io(block: suspend () -> Unit) = scope.launch {
        loading = true; err = null
        try { block() } catch (e: Exception) { err = e.message ?: e.toString() }
        loading = false
    }

    fun play(e: Entry) = io {
        val r = withContext(Dispatchers.IO) { resolve(e) }
        audioOnly = e.music
        current = r
        onVideo(!e.music)
        Store.addFront("hist", r.e)
        startPlay(ctx, r, e.music)
    }

    fun openChannel(e: Entry) {
        val u = e.chUrl ?: return
        io { channel = e.sub to withContext(Dispatchers.IO) { channelVideos(u) } }
    }

    fun loadHome() = io { home = withContext(Dispatchers.IO) { homeFeed() } }
    LaunchedEffect(Unit) { loadHome() }
    BackHandler(enabled = channel != null) { channel = null }

    // Pula automaticamente trechos de patrocínio/autopromoção (SponsorBlock)
    LaunchedEffect(current?.e?.url) {
        val url = current?.e?.url ?: return@LaunchedEffect
        val segs = try { withContext(Dispatchers.IO) { sponsorSegments(url) } } catch (e: Exception) { emptyList() }
        if (segs.isEmpty()) return@LaunchedEffect
        val p = Player.get(ctx)
        while (true) {
            delay(500)
            if (!Store.sponsor) continue
            val pos = p.currentPosition / 1000.0
            segs.firstOrNull { pos >= it.first && pos < it.second - 0.7 }
                ?.let { p.seekTo((it.second * 1000).toLong()) }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("🏠 Início", "🔍 Buscar", "📚 Biblioteca").forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { if (i == 0 && tab == 0) loadHome(); tab = i; channel = null },
                        icon = { Text(t.substringBefore(' ')) }, label = { Text(t.substringAfter(' ')) })
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().statusBarsPadding()) {
            current?.let { r ->
                AndroidView(
                    factory = { PlayerView(it).apply { player = Player.get(it) } },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                Text(r.e.title, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    key(tick) {
                        TextButton({ Store.sponsor = !Store.sponsor; tick++ }) {
                            Text(if (Store.sponsor) "🚫 Patrocínio: pular" else "Patrocínio: não pular")
                        }
                        TextButton({ Store.toggle("fav", r.e); tick++ }) {
                            Text(if (Store.has("fav", r.e.url)) "★ Favorito" else "☆ Favoritar")
                        }
                        r.e.chUrl?.let { c ->
                            val ch = r.e.copy(title = r.e.sub, url = c)
                            TextButton({ Store.toggle("subs", ch); tick++ }) {
                                Text(if (Store.has("subs", c)) "✓ Inscrito" else "+ Inscrever")
                            }
                        }
                    }
                    TextButton({ download(ctx, r, audioOnly); Toast.makeText(ctx, "Baixando…", Toast.LENGTH_SHORT).show() }) { Text("⬇ Baixar") }
                    TextButton({
                        Store.maxH = when (Store.maxH) { 360 -> 720; 720 -> 1080; else -> 360 }
                        Toast.makeText(ctx, "Qualidade ${Store.maxH}p (vale no próximo vídeo)", Toast.LENGTH_SHORT).show()
                    }) { Text("⚙ Qualidade") }
                    TextButton({ Player.get(ctx).stop(); current = null; onVideo(false) }) { Text("✕ Fechar") }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            err?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }

            if (tab == 1 && channel == null) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text(if (music) "Pesquisar músicas" else "Pesquisar vídeos") })
                Spacer(Modifier.width(6.dp))
                Button({ io { found = withContext(Dispatchers.IO) { search(query, music) } } }) { Text("Ir") }
            }
            if (tab == 1 && channel == null) FilterChip(music, { music = !music }, { Text("🎵 Modo música") },
                modifier = Modifier.padding(horizontal = 8.dp))

            if (tab == 2 && channel == null) Row(Modifier.padding(8.dp)) {
                listOf("Histórico", "Favoritos", "Inscrições").forEachIndexed { i, t ->
                    FilterChip(lib == i, { lib = i }, { Text(t) }, modifier = Modifier.padding(end = 6.dp))
                }
            }

            val shown: List<Entry> = channel?.second ?: when (tab) {
                0 -> home
                1 -> found
                else -> key(tick, lib) { Store.list(listOf("hist", "fav", "subs")[lib]) }
            }
            val subsList = tab == 2 && lib == 2 && channel == null
            channel?.let { Text("📺 ${it.first}", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }

            LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { e ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if (subsList) openChannel(e.copy(sub = e.title)) else play(e)
                    }.padding(8.dp)) {
                        AsyncImage(e.thumb, null, Modifier.width(140.dp).height(80.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!subsList) Text(e.sub, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.clickable { openChannel(e) })
                        }
                    }
                }
                if (tab == 2 && lib == 0 && channel == null && shown.isNotEmpty())
                    item { TextButton({ Store.clear("hist"); tick++ }) { Text("Limpar histórico") } }
            }
        }
    }
}
