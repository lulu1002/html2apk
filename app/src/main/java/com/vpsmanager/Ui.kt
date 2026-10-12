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
            HostScreen(
                host = current,
                onBack = { selected = null },
                onTrust = { updated ->
                    hosts = hosts.map { if (it.id == updated.id) updated else it }
                    store.save(hosts)
                }
            )
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

class CommandsState {
    var output by mutableStateOf("Escolha um comando.")
    var running by mutableStateOf(false)
    var custom by mutableStateOf("")
}

@Composable
fun TrustDialog(host: Host, e: HostKeyException, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (e.changed) "⚠ Fingerprint mudou!" else "Confiar neste servidor?") },
        text = {
            SelectionContainer {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (e.changed) {
                        Text(
                            "A chave de ${host.host} é diferente da que você confiou antes. " +
                                "Pode ser um ataque (man-in-the-middle) ou o servidor ter sido reinstalado."
                        )
                        Text("Salvo:", style = MaterialTheme.typography.labelMedium)
                        Text(host.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("Recebido:", style = MaterialTheme.typography.labelMedium)
                    } else {
                        Text("Primeira conexão com ${host.host}. Tipo da chave: ${e.keyType}")
                    }
                    Text(e.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text(
                        "Confira no servidor com:\nssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(if (e.changed) "Confiar mesmo assim" else "Confiar") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } }
    )
}

@Composable
fun CommandsTab(
    host: Host,
    st: CommandsState,
    onUntrusted: (HostKeyException, (Host) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()

    fun run(cmd: String, target: Host) {
        if (st.running || cmd.isBlank()) return
        st.running = true
        st.output = "$ $cmd\n"
        scope.launch {
            try {
                st.output += SshRunner.exec(target, cmd)
            } catch (e: HostKeyException) {
                st.output += "Conexão bloqueada: confirme o fingerprint do servidor."
                onUntrusted(e) { trusted -> run(cmd, trusted) }
            } catch (e: Exception) {
                st.output += "Erro: ${e.message}"
            }
            st.running = false
        }
    }

    Column(
        Modifier.padding(12.dp).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (host.fingerprint.isNotBlank()) {
            Text("🔒 ${host.fingerprint}", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quickCommands) { (label, cmd) ->
                AssistChip(onClick = { run(cmd, host) }, label = { Text(label) })
            }
        }
        if (st.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        Surface(tonalElevation = 3.dp, modifier = Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                Text(
                    st.output,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                st.custom, { st.custom = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Comando") }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { run(st.custom, host) }, enabled = !st.running) { Text("Executar") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(host: Host, onBack: () -> Unit, onTrust: (Host) -> Unit) {
    BackHandler(onBack = onBack)
    var current by remember { mutableStateOf(host) }
    var tab by remember { mutableStateOf(0) }
    var untrusted by remember { mutableStateOf<HostKeyException?>(null) }
    var retry by remember { mutableStateOf<((Host) -> Unit)?>(null) }
    val commands = remember { CommandsState() }
    val terminal = remember { TerminalState() }
    val files = remember { SftpState() }

    DisposableEffect(Unit) {
        onDispose {
            terminal.close()
            files.close()
        }
    }

    val report: (HostKeyException, (Host) -> Unit) -> Unit = { e, r ->
        untrusted = e
        retry = r
    }

    untrusted?.let { e ->
        TrustDialog(
            host = current,
            e = e,
            onConfirm = {
                val updated = current.copy(fingerprint = e.fingerprint)
                current = updated
                onTrust(updated)
                val r = retry
                untrusted = null
                retry = null
                r?.invoke(updated)
            },
            onCancel = {
                untrusted = null
                retry = null
            }
        )
    }

    val hideChrome = tab == 2 && files.fullscreen
    Scaffold(
        topBar = {
            if (!hideChrome) {
                Column {
                    TopAppBar(
                        title = { Text(current.name) },
                        navigationIcon = { TextButton(onClick = onBack) { Text("Voltar") } }
                    )
                    TabRow(selectedTabIndex = tab) {
                        listOf("Comandos", "Terminal", "Arquivos").forEachIndexed { i, title ->
                            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                        }
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> CommandsTab(current, commands, report)
                1 -> TerminalTab(current, terminal, report)
                else -> FilesTab(current, files, report) { path ->
                    tab = 1
                    terminal.cdTo(path)
                }
            }
        }
    }
}
