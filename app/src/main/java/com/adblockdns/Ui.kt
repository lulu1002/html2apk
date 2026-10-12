package com.adblockdns

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun startVpn(context: Context) {
    context.startService(Intent(context, DnsVpnService::class.java))
}

private fun stopVpn(context: Context) {
    context.startService(Intent(context, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
}

/** DNS privado em modo "nome do host" ignora o DNS da VPN e anula o bloqueio. */
private fun privateDnsStrict(context: Context): Boolean = try {
    Settings.Global.getString(context.contentResolver, "private_dns_mode") == "hostname"
} catch (_: Exception) {
    false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(list: BlockList) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Bloqueador DNS") })
                TabRow(selectedTabIndex = tab) {
                    listOf("Início", "Meus domínios").forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (tab == 0) HomeTab(list) else DomainsTab(list)
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun HomeTab(list: BlockList) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by DnsStats.running.collectAsState()
    val total by DnsStats.totalCount.collectAsState()
    val blockedCount by DnsStats.blockedCount.collectAsState()
    val recent by DnsStats.recent.collectAsState()
    val loaded by list.loadedCount.collectAsState()
    val lastUpdate by list.lastUpdate.collectAsState()
    val strictDns = remember(running) { privateDnsStrict(context) }
    var updating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpn(context)
    }

    fun toggle() {
        if (running) {
            stopVpn(context)
        } else {
            val intent = VpnService.prepare(context)
            if (intent != null) vpnLauncher.launch(intent) else startVpn(context)
        }
    }

    val percent = if (total > 0) blockedCount * 100 / total else 0
    val updatedText = if (lastUpdate == 0L) "nunca"
    else SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(lastUpdate))

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(onClick = { toggle() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (running) "Desativar proteção" else "Ativar proteção")
        }
        Text(
            if (running) "🛡 Protegendo: consultas DNS estão sendo filtradas" else "Proteção desligada",
            style = MaterialTheme.typography.bodyMedium
        )

        if (strictDns) {
            Text(
                "⚠ O “DNS privado” do Android está em modo nome do host: ele ignora este app. " +
                    "Vá em Configurações > Rede > DNS privado e escolha Desativado ou Automático.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Consultas", total.toString(), Modifier.weight(1f))
            StatCard("Bloqueadas", blockedCount.toString(), Modifier.weight(1f))
            StatCard("% bloqueado", "$percent%", Modifier.weight(1f))
        }

        Text("$loaded domínios na lista • atualizada: $updatedText", style = MaterialTheme.typography.bodySmall)
        if (loaded < 1000) {
            Text(
                "Dica: toque em “Atualizar listas” para baixar as listas completas.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    updating = true
                    message = try {
                        "Listas atualizadas: ${list.updateFromNetwork()} domínios"
                    } catch (e: Exception) {
                        "Falha ao atualizar: ${e.message}"
                    }
                    updating = false
                }
            },
            enabled = !updating,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (updating) "Baixando…" else "Atualizar listas") }
        if (updating) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)

        Text("Consultas recentes", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f)) {
            items(recent) { e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (e.blocked) "🚫 " else "✅ ") + e.domain,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = {
                        if (e.blocked) list.addAllow(e.domain) else list.addBlock(e.domain)
                    }) { Text(if (e.blocked) "Liberar" else "Bloquear") }
                }
            }
        }
        Text(
            "Se algum anúncio passar, desative o “DNS privado” do Android e o “DNS seguro” do navegador.",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun DomainsTab(list: BlockList) {
    var blocked by remember { mutableStateOf(list.customBlocked()) }
    var allowed by remember { mutableStateOf(list.customAllowed()) }
    fun refresh() {
        blocked = list.customBlocked()
        allowed = list.customAllowed()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            DomainAdder("Bloquear domínio") { list.addBlock(it); refresh() }
        }
        items(blocked) { d -> DomainRow(d) { list.removeBlock(d); refresh() } }
        item {
            Spacer(Modifier.height(16.dp))
            DomainAdder("Liberar domínio (vale também para subdomínios)") { list.addAllow(it); refresh() }
        }
        items(allowed) { d -> DomainRow(d) { list.removeAllow(d); refresh() } }
    }
}

@Composable
fun DomainAdder(title: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("exemplo.com") }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (text.isNotBlank()) {
                    onAdd(text)
                    text = ""
                }
            }) { Text("Adicionar") }
        }
    }
}

@Composable
fun DomainRow(domain: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(domain, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onRemove) { Text("Remover") }
    }
}
