package com.vpsmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val keyButtons = listOf(
    "Ctrl+C" to "\u0003",
    "Ctrl+D" to "\u0004",
    "Ctrl+L" to "\u000C",
    "Ctrl+Z" to "\u001A",
    "Tab" to "\t",
    "Esc" to "\u001B",
    "↑" to "\u001B[A",
    "↓" to "\u001B[B"
)

/** Várias abas de terminal (cada uma é um shell separado). */
@Composable
fun TerminalsTab(
    host: Host,
    terminals: SnapshotStateList<TerminalState>,
    active: Int,
    onActiveChange: (Int) -> Unit,
    onUntrusted: (HostKeyException, (Host) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val settings = remember { TerminalSettings(context) }
    val idx = active.coerceIn(0, terminals.lastIndex)

    fun closeTab(i: Int) {
        if (terminals.size <= 1) return
        val t = terminals.removeAt(i)
        t.close()
        onActiveChange((if (i <= idx) idx - 1 else idx).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = idx, edgePadding = 0.dp) {
            terminals.forEachIndexed { i, t ->
                Tab(
                    selected = i == idx,
                    onClick = { onActiveChange(i) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                buildString {
                                    append("Terminal ${t.number}")
                                    t.dirName?.let { append(" · $it") }
                                },
                                maxLines = 1
                            )
                            if (terminals.size > 1) {
                                Spacer(Modifier.width(8.dp))
                                Text("✕", modifier = Modifier.clickable { closeTab(i) })
                            }
                        }
                    }
                )
            }
            Tab(
                selected = false,
                onClick = {
                    val next = (terminals.maxOfOrNull { it.number } ?: 0) + 1
                    terminals.add(TerminalState(next))
                    onActiveChange(terminals.lastIndex)
                },
                text = { Text("＋ Novo") }
            )
        }
        key(terminals[idx]) {
            TerminalTab(host, terminals[idx], settings, onUntrusted)
        }
    }
}

@Composable
fun TerminalTab(
    host: Host,
    state: TerminalState,
    settings: TerminalSettings,
    onUntrusted: (HostKeyException, (Host) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var showShortcuts by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
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
    LaunchedEffect(settings.highlight, settings.themeIndex) { state.applyStyle(settings.highlight, settings.theme) }
    LaunchedEffect(state.text.length) { scroll.scrollTo(scroll.maxValue) }

    fun sendLine() {
        state.send(input + "\r")
        input = ""
    }

    if (showShortcuts) ShortcutsDialog(settings) { showShortcuts = false }
    if (showThemes) ThemeDialog(settings) { showThemes = false }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    state.connected -> "● conectado"
                    state.connecting -> "Conectando…"
                    else -> state.error ?: "Desconectado"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!state.connected) {
                TextButton(onClick = { connect(host) }, enabled = !state.connecting) { Text("Conectar") }
            }
            Box {
                IconButton(onClick = { menu = true }) { Text("⋮", fontSize = 22.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Realce de sintaxe") },
                        trailingIcon = { Switch(checked = settings.highlight, onCheckedChange = null) },
                        onClick = { settings.updateHighlight(!settings.highlight) }
                    )
                    DropdownMenuItem(
                        text = { Text("Fonte maior (${settings.fontSize})") },
                        onClick = { settings.changeFont(1) }
                    )
                    DropdownMenuItem(
                        text = { Text("Fonte menor (${settings.fontSize})") },
                        onClick = { settings.changeFont(-1) }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Tema: ${settings.theme.name}…") },
                        onClick = { menu = false; showThemes = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Atalhos personalizados…") },
                        onClick = { menu = false; showShortcuts = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Limpar tela") },
                        onClick = { menu = false; state.send("\u000C") }
                    )
                    DropdownMenuItem(
                        text = { Text("Reconectar") },
                        enabled = !state.connected && !state.connecting,
                        onClick = { menu = false; connect(host) }
                    )
                }
            }
        }
        Surface(color = Color(settings.theme.bg), modifier = Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                Text(
                    state.text,
                    color = Color(settings.theme.fg),
                    fontFamily = FontFamily.Monospace,
                    fontSize = settings.fontSize.sp,
                    modifier = Modifier.verticalScroll(scroll).padding(8.dp)
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(keyButtons) { (label, seq) ->
                AssistChip(onClick = { state.send(seq) }, label = { Text(label) })
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(settings.shortcuts, key = { it.id }) { sc ->
                SuggestionChip(
                    onClick = {
                        state.send(sc.command.replace("\n", "\r") + if (sc.enter) "\r" else "")
                    },
                    label = { Text(sc.label) }
                )
            }
            item {
                AssistChip(onClick = { showShortcuts = true }, label = { Text("⚙ Atalhos") })
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

@Composable
fun ShortcutsDialog(settings: TerminalSettings, onDismiss: () -> Unit) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var nameText by remember { mutableStateOf("") }
    var cmdText by remember { mutableStateOf("") }
    var enter by remember { mutableStateOf(true) }

    fun reset() {
        editingId = null
        nameText = ""
        cmdText = ""
        enter = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Atalhos personalizados") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                settings.shortcuts.forEach { sc ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sc.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                sc.command,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = {
                            editingId = sc.id
                            nameText = sc.label
                            cmdText = sc.command
                            enter = sc.enter
                        }) { Text("Editar") }
                        TextButton(onClick = {
                            settings.removeShortcut(sc.id)
                            if (editingId == sc.id) reset()
                        }) { Text("Excluir") }
                    }
                }
                HorizontalDivider()
                Text(
                    if (editingId == null) "Novo atalho" else "Editar atalho",
                    style = MaterialTheme.typography.titleSmall
                )
                OutlinedTextField(
                    nameText, { nameText = it },
                    singleLine = true,
                    label = { Text("Nome do botão") }
                )
                OutlinedTextField(
                    cmdText, { cmdText = it },
                    minLines = 1,
                    maxLines = 3,
                    label = { Text("Comando") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enter, onCheckedChange = { enter = it })
                    Text("Enviar com Enter")
                }
                Button(onClick = {
                    if (nameText.isNotBlank() && cmdText.isNotBlank()) {
                        val id = editingId
                        if (id == null) {
                            settings.addShortcut(Shortcut(label = nameText.trim(), command = cmdText, enter = enter))
                        } else {
                            settings.updateShortcut(Shortcut(id, nameText.trim(), cmdText, enter))
                        }
                        reset()
                    }
                }) { Text(if (editingId == null) "Adicionar" else "Salvar") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}

@Composable
fun ThemeDialog(settings: TerminalSettings, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tema do terminal") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TerminalThemes.all.forEachIndexed { i, t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { settings.selectTheme(i) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = settings.themeIndex == i, onClick = { settings.selectTheme(i) })
                        Column(Modifier.weight(1f).background(Color(t.bg)).padding(8.dp)) {
                            Text(
                                t.name,
                                color = Color(t.fg),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            Text(
                                buildAnnotatedString {
                                    for (k in 1..6) {
                                        withStyle(SpanStyle(color = t.ansi(k))) { append("● ") }
                                    }
                                },
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }
    )
}
