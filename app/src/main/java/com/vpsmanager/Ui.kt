package com.vpsmanager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun App(store: HostStore) {
    var hosts by remember { mutableStateOf(store.load()) }
    var selected by remember { mutableStateOf<Host?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    val current = selected
    Surface(Modifier.fillMaxSize()) {
        if (current != null) {
            HostScreen(current) { selected = null }
        } else {
            HostList(
                hosts = hosts,
                onOpen = { selected = it },
                onDelete = { h -> hosts = hosts - h; store.save(hosts) },
                onAdd = { showDialog = true }
            )
        }
        if (showDialog) {
            HostDialog(
                onDismiss = { showDialog = false },
                onSave = { h -> hosts = hosts + h; store.save(hosts); showDialog = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostList(
    hosts: List<Host>,
    onOpen: (Host) -> Unit,
    onDelete: (Host) -> Unit,
    onAdd: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Meus servidores") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd) { Text("Novo servidor") }
        }
    ) { pad ->
        if (hosts.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhum servidor ainda")
            }
        } else {
            LazyColumn(
                Modifier.padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(hosts, key = { it.id }) { h ->
                    Card(onClick = { onOpen(h) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${h.user}@${h.host}:${h.port}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = { onDelete(h) }) { Text("Excluir") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HostDialog(onDismiss: () -> Unit, onSave: (Host) -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo servidor") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text("IP ou domínio") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Porta") }, singleLine = true)
                OutlinedTextField(user, { user = it }, label = { Text("Usuário") }, singleLine = true)
                OutlinedTextField(
                    password, { password = it }, label = { Text("Senha (opcional)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    key, { key = it }, label = { Text("Chave privada PEM (opcional)") },
                    minLines = 3, maxLines = 5
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && host.isNotBlank() && user.isNotBlank()) {
                    onSave(
                        Host(
                            name = name.trim(), host = host.trim(),
                            port = port.toIntOrNull() ?: 22, user = user.trim(),
                            password = password, privateKey = key.trim()
                        )
                    )
                }
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private val quickCommands = listOf(
    "Uptime" to "uptime",
    "Disco" to "df -h",
    "Memória" to "free -h",
    "Top" to "top -bn1 | head -15",
    "Docker" to "docker ps",
    "Logs" to "journalctl -n 30 --no-pager",
    "Portas" to "ss -tulpn"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(host: Host, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("Escolha um comando.") }
    var running by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }

    fun run(cmd: String) {
        if (running || cmd.isBlank()) return
        running = true
        output = "$ $cmd\n"
        scope.launch {
            val result = try {
                SshRunner.exec(host, cmd)
            } catch (e: Exception) {
                "Erro: ${e.message}"
            }
            output += result
            running = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(host.name) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(12.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(quickCommands) { (label, cmd) ->
                    AssistChip(onClick = { run(cmd) }, label = { Text(label) })
                }
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        output,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    custom, { custom = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Comando") }
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { run(custom) }, enabled = !running) { Text("Executar") }
            }
        }
    }
}
