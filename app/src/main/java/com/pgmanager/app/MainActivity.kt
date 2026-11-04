package com.pgmanager.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val PAGE = 100

sealed interface Screen {
    data object Connections : Screen
    data class Databases(val c: ConnInfo) : Screen
    data class Tables(val c: ConnInfo, val db: String) : Screen
    data class TableView(val c: ConnInfo, val db: String, val schema: String, val table: String, val kind: String) : Screen
    data class Sql(val c: ConnInfo, val db: String, val initial: String = "") : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ConnectionStore(applicationContext)
        val sp = getSharedPreferences("crash", MODE_PRIVATE)
        val lastCrash = sp.getString("trace", null)
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            sp.edit().putString("trace", Log.getStackTraceString(e)).commit()
            oldHandler?.uncaughtException(t, e)
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    App(store)
                    var crash by remember { mutableStateOf(lastCrash) }
                    val clip = LocalClipboardManager.current
                    crash?.let { trace ->
                        AlertDialog(
                            onDismissRequest = { },
                            title = { Text("O app fechou por um erro") },
                            text = {
                                SelectionContainer {
                                    Text(
                                        trace.take(2500),
                                        modifier = Modifier.verticalScroll(rememberScrollState()),
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { clip.setText(AnnotatedString(trace)) }) { Text("Copiar") }
                            },
                            dismissButton = {
                                TextButton(onClick = { sp.edit().remove("trace").apply(); crash = null }) { Text("Fechar") }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun App(store: ConnectionStore) {
    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Connections)) }
    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
    val back: () -> Unit = { stack = stack.dropLast(1) }
    when (val s = stack.last()) {
        is Screen.Connections -> ConnectionsScreen(store) { stack = stack + Screen.Databases(it) }
        is Screen.Databases -> DatabasesScreen(
            s.c, back,
            { db -> stack = stack + Screen.Tables(s.c, db) },
            { db -> stack = stack + Screen.Sql(s.c, db) }
        )
        is Screen.Tables -> TablesScreen(
            s.c, s.db, back,
            { sch, t, k -> stack = stack + Screen.TableView(s.c, s.db, sch, t, k) },
            { sql -> stack = stack + Screen.Sql(s.c, s.db, sql) }
        )
        is Screen.TableView -> TableViewScreen(s.c, s.db, s.schema, s.table, s.kind, back)
        is Screen.Sql -> SqlScreen(s.c, s.db, s.initial, back)
    }
}

// ======================= utilitários =======================

class AsyncState<T> {
    var data by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
}

@Composable
fun <T> rememberAsync(key: Any?, block: () -> T): Pair<AsyncState<T>, () -> Unit> {
    val state = remember { AsyncState<T>() }
    var tick by remember { mutableIntStateOf(0) }
    val latest by rememberUpdatedState(block)
    LaunchedEffect(tick, key) {
        state.loading = true
        state.error = null
        try {
            state.data = withContext(Dispatchers.IO) { latest() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            state.error = e.toString()
        }
        state.loading = false
    }
    val reload: () -> Unit = { tick++ }
    return state to reload
}

fun CoroutineScope.runOp(snack: SnackbarHostState, okMsg: String, after: () -> Unit, op: () -> Unit) {
    launch {
        try {
            withContext(Dispatchers.IO) { op() }
            after()
            snack.showSnackbar(okMsg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            snack.showSnackbar("Erro: ${e}")
        }
    }
}

@Composable
fun <T> AsyncBody(state: AsyncState<T>, pad: PaddingValues, content: @Composable (T) -> Unit) {
    Box(Modifier.padding(pad).fillMaxSize()) {
        val d = state.data
        val err = state.error
        if (err != null) {
            Text("Erro: $err", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        } else if (d != null) {
            content(d)
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar") }
        },
        actions = actions
    )
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, number: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else if (password) KeyboardType.Password else KeyboardType.Text
        )
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Confirmar", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun ResultTable(r: QueryResult, hideFirst: Boolean = false, onRowClick: ((Int) -> Unit)? = null) {
    val start = if (hideFirst) 1 else 0
    val cols = r.columns.drop(start)
    val hScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
        Row(Modifier.background(MaterialTheme.colorScheme.primaryContainer)) {
            cols.forEach { Cell(it, bold = true) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(r.rows) { i, row ->
                val bg = if (i % 2 == 0) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                val mod = if (onRowClick != null) Modifier.background(bg).clickable { onRowClick(i) } else Modifier.background(bg)
                Row(mod) {
                    row.drop(start).forEach { Cell(it ?: "NULL", isNull = it == null) }
                }
            }
        }
    }
}

@Composable
private fun Cell(text: String, bold: Boolean = false, isNull: Boolean = false) {
    Text(
        text,
        modifier = Modifier.width(150.dp).padding(horizontal = 8.dp, vertical = 8.dp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (isNull) FontStyle.Italic else null,
        color = if (isNull) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
    )
}

// ======================= conexões =======================

@Composable
fun ConnectionsScreen(store: ConnectionStore, onOpen: (ConnInfo) -> Unit) {
    var list by remember { mutableStateOf(store.load()) }
    var editing by remember { mutableStateOf<ConnInfo?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ConnInfo?>(null) }

    Scaffold(
        topBar = { Bar("Conexões PostgreSQL") },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Nova conexão") }
        }
    ) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhuma conexão.\nToque em + para adicionar.")
            }
        } else {
            LazyColumn(
                Modifier.padding(pad),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(list, key = { it.id }) { c ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(c) }) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium)
                                Text("${c.user}@${c.host}:${c.port}/${c.database}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { editing = c }) { Icon(Icons.Default.Edit, "Editar") }
                            IconButton(onClick = { deleting = c }) { Icon(Icons.Default.Delete, "Excluir") }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        ConnectionDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { c ->
                list = if (list.any { it.id == c.id }) list.map { if (it.id == c.id) c else it } else list + c
                store.save(list)
                creating = false
                editing = null
            }
        )
    }
    deleting?.let { d ->
        ConfirmDialog(
            "Excluir conexão", "Remover \"${d.name}\" do aplicativo? (O banco não é afetado.)",
            onConfirm = { list = list.filter { it.id != d.id }; store.save(list); deleting = null },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
fun ConnectionDialog(initial: ConnInfo?, onDismiss: () -> Unit, onSave: (ConnInfo) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 5432).toString()) }
    var db by remember { mutableStateOf(initial?.database ?: "postgres") }
    var user by remember { mutableStateOf(initial?.user ?: "postgres") }
    var pass by remember { mutableStateOf(initial?.password ?: "") }
    var ssl by remember { mutableStateOf(initial?.ssl ?: false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun build() = ConnInfo(
        id = initial?.id ?: UUID.randomUUID().toString(),
        name = name.ifBlank { host },
        host = host.trim(),
        port = port.toIntOrNull() ?: 5432,
        database = db.ifBlank { "postgres" },
        user = user.trim(),
        password = pass,
        ssl = ssl
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nova conexão" else "Editar conexão") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Field("Nome (apelido)", name, { name = it })
                Field("Host / IP", host, { host = it })
                Field("Porta", port, { port = it }, number = true)
                Field("Banco padrão", db, { db = it })
                Field("Usuário", user, { user = it })
                Field("Senha", pass, { pass = it }, password = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = ssl, onCheckedChange = { ssl = it })
                    Text("  Usar SSL")
                }
                TextButton(onClick = {
                    status = "Testando..."
                    val c = build()
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            try {
                                Pg.connect(c).use { "✓ Conectado (PostgreSQL ${it.metaData.databaseProductVersion})" }
                            } catch (e: Throwable) {
                                "✗ ${e}"
                            }
                        }
                    }
                }) { Text("Testar conexão") }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(build()) }, enabled = host.isNotBlank() && user.isNotBlank()) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ======================= bancos =======================

@Composable
fun DatabasesScreen(c: ConnInfo, onBack: () -> Unit, onOpen: (String) -> Unit, onSql: (String) -> Unit) {
    val (state, reload) = rememberAsync(null) { Pg.connect(c).use { Pg.listDatabases(it) } }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var dropping by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            Bar(c.name, "${c.host}:${c.port} • bancos", onBack) {
                IconButton(onClick = reload) { Icon(Icons.Default.Refresh, "Atualizar") }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Novo banco") }
        }
    ) { pad ->
        AsyncBody(state, pad) { r ->
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(r.rows) { row ->
                    val name = row[0] ?: ""
                    Card(Modifier.fillMaxWidth().clickable { onOpen(name) }) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text("${row[1]} • dono: ${row[2]}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { onSql(name) }) { Icon(Icons.Default.PlayArrow, "SQL") }
                            IconButton(onClick = { dropping = name }) { Icon(Icons.Default.Delete, "Excluir banco") }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Novo banco de dados") },
            text = { Field("Nome do banco", newName, { newName = it }) },
            confirmButton = {
                TextButton(enabled = newName.isNotBlank(), onClick = {
                    val n = newName.trim()
                    creating = false
                    scope.runOp(snack, "Banco criado", reload) { Pg.connect(c).use { Pg.createDatabase(it, n) } }
                }) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancelar") } }
        )
    }
    dropping?.let { n ->
        ConfirmDialog(
            "Excluir banco", "Apagar definitivamente o banco \"$n\" e todos os dados? Conexões abertas nele serão encerradas.",
            onConfirm = {
                dropping = null
                // conecta no banco padrão (ou em "postgres" se for o próprio banco padrão)
                val admin = if (n == c.database) "postgres" else c.database
                scope.runOp(snack, "Banco excluído", reload) { Pg.connect(c, admin).use { Pg.dropDatabase(it, n) } }
            },
            onDismiss = { dropping = null }
        )
    }
}

// ======================= tabelas =======================

@Composable
fun TablesScreen(
    c: ConnInfo, db: String, onBack: () -> Unit,
    onOpen: (String, String, String) -> Unit, onSql: (String) -> Unit
) {
    val (state, reload) = rememberAsync(null) { Pg.connect(c, db).use { Pg.listTables(it) } }
    var filter by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            Bar(db, "${c.name} • tabelas", onBack) {
                IconButton(onClick = reload) { Icon(Icons.Default.Refresh, "Atualizar") }
                IconButton(onClick = { onSql("") }) { Icon(Icons.Default.PlayArrow, "Editor SQL") }
                IconButton(onClick = {
                    onSql("CREATE TABLE public.nova_tabela (\n  id serial PRIMARY KEY,\n  nome text NOT NULL,\n  criado_em timestamptz DEFAULT now()\n);")
                }) { Icon(Icons.Default.Add, "Nova tabela") }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = filter, onValueChange = { filter = it },
                label = { Text("Filtrar tabelas") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
            Box(Modifier.weight(1f)) {
                AsyncBody(state, PaddingValues()) { r ->
                    val rows = r.rows.filter { filter.isBlank() || "${it[0]}.${it[1]}".contains(filter, ignoreCase = true) }
                    if (rows.isEmpty()) {
                        Text("Nenhuma tabela encontrada.", Modifier.padding(16.dp))
                    } else {
                        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(rows) { row ->
                                Card(Modifier.fillMaxWidth().clickable { onOpen(row[0]!!, row[1]!!, row[2]!!) }) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text("${row[0]}.${row[1]}", style = MaterialTheme.typography.titleSmall)
                                        Text("${row[2]} • ~${row[3]} linhas", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ======================= tabela (dados / estrutura) =======================

@Composable
fun TableViewScreen(c: ConnInfo, db: String, schema: String, table: String, kind: String, onBack: () -> Unit) {
    val editable = kind == "tabela"
    var tab by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    val (dataState, reloadData) = rememberAsync(page) {
        Pg.connect(c, db).use { Pg.tableData(it, schema, table, page, PAGE, editable) }
    }
    val (structState, reloadStruct) = rememberAsync(null) {
        Pg.connect(c, db).use { Pair(Pg.columns(it, schema, table), Pg.indexes(it, schema, table)) }
    }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Int?>(null) }
    var inserting by remember { mutableStateOf(false) }
    var confirmTruncate by remember { mutableStateOf(false) }
    var confirmDrop by remember { mutableStateOf(false) }
    val rowClick: ((Int) -> Unit)? = if (editable) ({ i: Int -> editing = i }) else null

    Scaffold(
        topBar = {
            Bar("$schema.$table", "$db • $kind", onBack) {
                IconButton(onClick = { reloadData(); reloadStruct() }) { Icon(Icons.Default.Refresh, "Atualizar") }
                if (editable) IconButton(onClick = { confirmTruncate = true }) { Icon(Icons.Default.Clear, "Esvaziar tabela") }
                IconButton(onClick = { confirmDrop = true }) { Icon(Icons.Default.Delete, "Excluir") }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            if (editable && tab == 0) {
                FloatingActionButton(onClick = { inserting = true }) { Icon(Icons.Default.Add, "Nova linha") }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Dados") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Estrutura") })
            }
            Box(Modifier.weight(1f)) {
                if (tab == 0) {
                    AsyncBody(dataState, PaddingValues()) { r ->
                        Column {
                            Box(Modifier.weight(1f)) {
                                if (r.rows.isEmpty()) Text("Sem linhas.", Modifier.padding(16.dp))
                                else ResultTable(r, hideFirst = editable, onRowClick = rowClick)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.Default.ArrowBack, "Anterior") }
                                Text(
                                    if (r.rows.isEmpty()) "Página ${page + 1}"
                                    else "Linhas ${page * PAGE + 1}–${page * PAGE + r.rows.size}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                IconButton(onClick = { page++ }, enabled = r.rows.size == PAGE) { Icon(Icons.Default.ArrowForward, "Próxima") }
                            }
                        }
                    }
                } else {
                    AsyncBody(structState, PaddingValues()) { pair ->
                        Column {
                            Text("Colunas", Modifier.padding(8.dp), fontWeight = FontWeight.Bold)
                            Box(Modifier.weight(1f)) { ResultTable(pair.first) }
                            Text("Índices", Modifier.padding(8.dp), fontWeight = FontWeight.Bold)
                            Box(Modifier.weight(1f)) {
                                if (pair.second.rows.isEmpty()) Text("Nenhum índice.", Modifier.padding(8.dp))
                                else ResultTable(pair.second)
                            }
                        }
                    }
                }
            }
        }
    }

    val cur = dataState.data
    val ei = editing
    if (ei != null && cur != null) {
        val row = cur.rows.getOrNull(ei)
        if (row != null) {
            val ctid = row[0] ?: ""
            RowDialog(
                columns = cur.columns.drop(1),
                initial = row.drop(1),
                onDismiss = { editing = null },
                onSave = { changes ->
                    editing = null
                    scope.runOp(snack, "Linha atualizada", reloadData) {
                        Pg.connect(c, db).use { Pg.updateRow(it, schema, table, ctid, changes) }
                    }
                },
                onDelete = {
                    editing = null
                    scope.runOp(snack, "Linha excluída", reloadData) {
                        Pg.connect(c, db).use { Pg.deleteRow(it, schema, table, ctid) }
                    }
                }
            )
        }
    }
    if (inserting && cur != null) {
        RowDialog(
            columns = cur.columns.drop(1),
            initial = null,
            onDismiss = { inserting = false },
            onSave = { values ->
                inserting = false
                scope.runOp(snack, "Linha inserida", reloadData) {
                    Pg.connect(c, db).use { Pg.insertRow(it, schema, table, values) }
                }
            },
            onDelete = null
        )
    }
    if (confirmTruncate) {
        ConfirmDialog(
            "Esvaziar tabela", "Apagar TODAS as linhas de $schema.$table?",
            onConfirm = {
                confirmTruncate = false
                page = 0
                scope.runOp(snack, "Tabela esvaziada", reloadData) { Pg.connect(c, db).use { Pg.truncate(it, schema, table) } }
            },
            onDismiss = { confirmTruncate = false }
        )
    }
    if (confirmDrop) {
        ConfirmDialog(
            "Excluir $kind", "Apagar definitivamente $schema.$table?",
            onConfirm = {
                confirmDrop = false
                scope.runOp(snack, "Excluído", onBack) { Pg.connect(c, db).use { Pg.dropTable(it, schema, table, kind) } }
            },
            onDismiss = { confirmDrop = false }
        )
    }
}

@Composable
fun RowDialog(
    columns: List<String>,
    initial: List<String?>?,
    onDismiss: () -> Unit,
    onSave: (List<Pair<String, String?>>) -> Unit,
    onDelete: (() -> Unit)?
) {
    val isEdit = initial != null
    val texts = remember {
        mutableStateListOf<String>().apply { columns.forEachIndexed { i, _ -> add(initial?.get(i) ?: "") } }
    }
    val nulls = remember {
        mutableStateListOf<Boolean>().apply { columns.forEachIndexed { i, _ -> add(isEdit && initial!![i] == null) } }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Editar linha" else "Nova linha") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!isEdit) Text("Campos vazios usam o valor padrão da coluna.", style = MaterialTheme.typography.bodySmall)
                columns.forEachIndexed { i, col ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = texts[i], onValueChange = { texts[i] = it },
                            label = { Text(col) }, enabled = !nulls[i],
                            modifier = Modifier.weight(1f)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 4.dp)) {
                            Checkbox(checked = nulls[i], onCheckedChange = { nulls[i] = it })
                            Text("NULL", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Excluir esta linha", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val out = ArrayList<Pair<String, String?>>()
                columns.forEachIndexed { i, col ->
                    if (nulls[i]) {
                        if (!isEdit || initial!![i] != null) out.add(col to null)
                    } else if (isEdit) {
                        if (texts[i] != initial!![i]) out.add(col to texts[i])
                    } else if (texts[i].isNotEmpty()) {
                        out.add(col to texts[i])
                    }
                }
                if (isEdit && out.isEmpty()) onDismiss() else onSave(out)
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )

    if (confirmDelete && onDelete != null) {
        ConfirmDialog("Excluir linha", "Apagar esta linha definitivamente?", onConfirm = { confirmDelete = false; onDelete() }, onDismiss = { confirmDelete = false })
    }
}

// ======================= editor SQL =======================

@Composable
fun SqlScreen(c: ConnInfo, db: String, initial: String, onBack: () -> Unit) {
    var sql by remember { mutableStateOf(initial) }
    var result by remember { mutableStateOf<QueryResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun run() {
        if (running || sql.isBlank()) return
        running = true
        error = null
        val q = sql
        scope.launch {
            try {
                result = withContext(Dispatchers.IO) { Pg.connect(c, db).use { Pg.execute(it, q, 500) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.toString()
                result = null
            }
            running = false
        }
    }

    Scaffold(topBar = { Bar("Editor SQL", "${c.name} • $db", onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = sql, onValueChange = { sql = it },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                minLines = 4, maxLines = 8,
                placeholder = { Text("SELECT * FROM tabela;") }
            )
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run() }, enabled = !running) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text("Executar")
                }
                OutlinedButton(onClick = { sql = ""; result = null; error = null }) { Text("Limpar") }
                val r = result
                if (r != null && r.columns.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "resultado.csv")
                            putExtra(Intent.EXTRA_TEXT, Pg.toCsv(r))
                        }
                        ctx.startActivity(Intent.createChooser(i, "Compartilhar CSV"))
                    }) {
                        Icon(Icons.Default.Share, null)
                        Text(" CSV")
                    }
                }
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            result?.let { r ->
                Text(
                    if (r.columns.isNotEmpty())
                        "${r.rows.size} linha(s) • ${r.millis} ms" + if (r.truncated) " (limitado a 500)" else ""
                    else (r.message ?: "") + " • ${r.millis} ms",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                Box(Modifier.weight(1f)) {
                    if (r.columns.isNotEmpty()) ResultTable(r)
                }
            }
        }
    }
}
