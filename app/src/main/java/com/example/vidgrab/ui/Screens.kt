package com.example.vidgrab.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.vidgrab.DownloadItem
import com.example.vidgrab.DownloadRepository
import com.example.vidgrab.MainViewModel
import com.example.vidgrab.Quality
import com.example.vidgrab.Status

@Composable
fun RootScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val active = downloads.count { it.status == Status.RUNNING || it.status == Status.QUEUED }

    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= 28) perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Search, null) },
                    label = { Text("Baixar") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = {
                        BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                            Icon(Icons.AutoMirrored.Filled.List, null)
                        }
                    },
                    label = { Text("Downloads") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Ajustes") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> DownloadTab(vm) { tab = 1 }
                1 -> DownloadsTab(downloads)
                else -> SettingsTab(vm)
            }
        }
    }
}

@Composable
fun DownloadTab(vm: MainViewModel, goToDownloads: () -> Unit) {
    val clip = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("VidGrab", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = vm.url,
            onValueChange = { vm.url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Link do vídeo") },
            singleLine = true,
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (vm.url.isNotEmpty()) {
                        IconButton(onClick = { vm.clearUrl() }) {
                            Icon(Icons.Default.Close, contentDescription = "Limpar link")
                        }
                    }
                    TextButton(onClick = { clip.getText()?.text?.let { vm.url = it } }) { Text("Colar") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.analyze() })
        )

        Button(
            onClick = { vm.analyze() },
            enabled = !vm.loading && vm.url.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (vm.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Analisar link")
        }

        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        vm.preview?.let { p ->
            Card(Modifier.fillMaxWidth()) {
                Column {
                    AsyncImage(
                        model = p.thumb,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        contentScale = ContentScale.Crop
                    )
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(p.uploader, formatDuration(p.duration)).joinToString(" • ")
                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall)
                        Quality.entries.forEach { q ->
                            FilledTonalButton(
                                onClick = { vm.download(q); goToDownloads() },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(q.label) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadsTab(list: List<DownloadItem>) {
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nenhum download ainda")
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = { DownloadRepository.clearFailed() }) { Text("Limpar falhas") }
            }
        }
        items(list, key = { it.id }) { DownloadCard(it) }
    }
}

@Composable
private fun DownloadCard(item: DownloadItem) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = item.thumb,
                contentDescription = null,
                modifier = Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(item.quality, style = MaterialTheme.typography.labelSmall)
                when (item.status) {
                    Status.QUEUED -> Text("Na fila…", style = MaterialTheme.typography.bodySmall)
                    Status.RUNNING -> {
                        if (item.progress <= 0f) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        else LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        val eta = if (item.eta > 0) " • ${item.eta}s restantes" else ""
                        Text("${(item.progress * 100).toInt()}%$eta", style = MaterialTheme.typography.bodySmall)
                    }
                    Status.DONE -> Text(
                        if (item.path != null) "Concluído • pasta privada" else "Concluído • Downloads/VidGrab",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Status.ERROR -> Text(item.error ?: "Erro", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Status.CANCELED -> Text("Cancelado", style = MaterialTheme.typography.bodySmall)
                }
            }
            when (item.status) {
                Status.QUEUED, Status.RUNNING -> IconButton(onClick = { DownloadRepository.cancel(item.id) }) {
                    Icon(Icons.Default.Close, "Cancelar")
                }
                Status.DONE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!item.fileUri.isNullOrBlank()) {
                        TextButton(onClick = { openFile(ctx, item) }) { Text("Abrir") }
                    }
                    IconButton(onClick = { DownloadRepository.remove(item.id) }) {
                        Icon(Icons.Default.Delete, if (item.path != null) "Excluir arquivo" else "Remover da lista")
                    }
                }
                else -> IconButton(onClick = { DownloadRepository.remove(item.id) }) {
                    Icon(Icons.Default.Delete, "Remover")
                }
            }
        }
    }
}

@Composable
fun SettingsTab(vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Versão do yt-dlp: ${vm.engineVersion.ifBlank { "carregando…" }}")
        OutlinedButton(onClick = { vm.updateEngine() }, enabled = !vm.updating, modifier = Modifier.fillMaxWidth()) {
            if (vm.updating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Atualizar yt-dlp")
        }
        Text(
            "Se algum site parar de funcionar, atualize o yt-dlp. O app também tenta atualizar sozinho 1x por dia.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Pasta privada", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (vm.privateDir)
                        "Os arquivos ficam só dentro do app: não aparecem na galeria nem em outros apps. Excluir da lista apaga o arquivo, e desinstalar o app também."
                    else
                        "Os arquivos vão para Downloads/VidGrab e aparecem na galeria.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = vm.privateDir, onCheckedChange = { vm.changePrivateDir(it) })
        }
        Text("Vale para os próximos downloads.", style = MaterialTheme.typography.bodySmall)
        Text(
            "Dica: no navegador ou em outro app, use Compartilhar → VidGrab para analisar o link direto.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Baixe apenas conteúdo que você tem direito de baixar. Respeite os termos de uso dos sites.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun openFile(ctx: android.content.Context, item: DownloadItem) {
    try {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(item.fileUri), item.mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (_: Exception) {
        Toast.makeText(ctx, "Nenhum app para abrir este arquivo", Toast.LENGTH_SHORT).show()
    }
}

private fun formatDuration(sec: Long): String? {
    if (sec <= 0) return null
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
