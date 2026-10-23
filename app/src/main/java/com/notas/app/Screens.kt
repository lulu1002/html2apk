package com.notas.app

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))

private fun formatDate(ms: Long): String = dateFormat.format(Date(ms))

@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent
)

// =====================================================================
// Lista de notas
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(vm: NotesViewModel) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var showNewCategory by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDeleteCategory by remember { mutableStateOf(false) }

    val selected = vm.selectedCategory
    val selectedCat = vm.categories.firstOrNull { it.id == selected }
    val q = vm.query.trim()
    val hasUncategorized = vm.categories.isNotEmpty() && vm.notes.any { it.categoryId == null }

    val visible = vm.notes
        .filter { n ->
            val catOk = when (selected) {
                null -> true
                NO_CATEGORY -> n.categoryId == null
                else -> n.categoryId == selected
            }
            val qOk = q.isEmpty() ||
                n.title.contains(q, ignoreCase = true) ||
                n.content.contains(q, ignoreCase = true)
            catOk && qOk
        }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = vm.query,
                            onValueChange = { vm.query = it },
                            placeholder = { Text("Buscar notas") },
                            singleLine = true,
                            colors = transparentFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("Notas")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) vm.query = ""
                    }) {
                        Icon(
                            if (searching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Buscar"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.openNew() }) {
                Icon(Icons.Default.Add, contentDescription = "Nova nota")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selected == null,
                        onClick = { vm.selectedCategory = null },
                        label = { Text("Todas") }
                    )
                }
                if (hasUncategorized) {
                    item {
                        FilterChip(
                            selected = selected == NO_CATEGORY,
                            onClick = { vm.selectedCategory = NO_CATEGORY },
                            label = { Text("Sem categoria") }
                        )
                    }
                }
                items(vm.categories, key = { it.id }) { c ->
                    FilterChip(
                        selected = selected == c.id,
                        onClick = { vm.selectedCategory = c.id },
                        label = { Text(c.name) }
                    )
                }
                item {
                    AssistChip(
                        onClick = { showNewCategory = true },
                        label = { Text("+ Categoria") }
                    )
                }
            }

            if (selectedCat != null) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { showRename = true }) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Renomear")
                    }
                    TextButton(onClick = { showDeleteCategory = true }) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Excluir categoria")
                    }
                }
            }

            if (visible.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (vm.notes.isEmpty()) "Nenhuma nota ainda.\nToque em + para criar a primeira."
                        else "Nenhuma nota encontrada.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visible, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            categoryName = vm.categories.firstOrNull { it.id == note.categoryId }?.name,
                            onClick = { vm.openNote(note) }
                        )
                    }
                }
            }
        }
    }

    if (showNewCategory) {
        NameDialog(
            title = "Nova categoria",
            initial = "",
            confirmLabel = "Criar",
            onConfirm = { name ->
                vm.addCategory(name)?.let { vm.selectedCategory = it.id }
                showNewCategory = false
            },
            onDismiss = { showNewCategory = false }
        )
    }
    if (showRename && selectedCat != null) {
        NameDialog(
            title = "Renomear categoria",
            initial = selectedCat.name,
            confirmLabel = "Salvar",
            onConfirm = { name ->
                vm.renameCategory(selectedCat.id, name)
                showRename = false
            },
            onDismiss = { showRename = false }
        )
    }
    if (showDeleteCategory && selectedCat != null) {
        AlertDialog(
            onDismissRequest = { showDeleteCategory = false },
            title = { Text("Excluir categoria?") },
            text = { Text("As notas dessa categoria não serão apagadas, só ficarão sem categoria.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCategory(selectedCat.id)
                    showDeleteCategory = false
                }) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCategory = false }) { Text("Cancelar") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteCard(note: Note, categoryName: String?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    note.title.ifBlank { "Sem título" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (note.pinned) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Fixada",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (note.content.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    note.content,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                listOfNotNull(categoryName, formatDate(note.updatedAt)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Nome") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// =====================================================================
// Editor de nota
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: NotesViewModel, initial: Note) {
    var title by rememberSaveable(initial.id) { mutableStateOf(initial.title) }
    var content by rememberSaveable(initial.id) { mutableStateOf(initial.content) }
    var categoryId by rememberSaveable(initial.id) { mutableStateOf<String?>(initial.categoryId) }
    var pinned by rememberSaveable(initial.id) { mutableStateOf(initial.pinned) }

    var confirmDelete by remember { mutableStateOf(false) }
    var catMenu by remember { mutableStateOf(false) }
    var newCat by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun current() = initial.copy(
        title = title,
        content = content,
        categoryId = categoryId,
        pinned = pinned
    )

    // Salvamento automático enquanto digita
    LaunchedEffect(title, content, categoryId, pinned) {
        delay(600)
        vm.saveDraft(current())
    }

    BackHandler { vm.closeEditor(current()) }

    val catName = vm.categories.firstOrNull { it.id == categoryId }?.name ?: "Sem categoria"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { vm.closeEditor(current()) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { pinned = !pinned }) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Fixar",
                            tint = if (pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "$title\n\n$content".trim())
                        }
                        context.startActivity(Intent.createChooser(send, "Compartilhar nota"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Compartilhar")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Apagar")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = { catMenu = true }) {
                    Text(catName)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Sem categoria") },
                        onClick = { categoryId = null; catMenu = false }
                    )
                    vm.categories.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c.name) },
                            onClick = { categoryId = c.id; catMenu = false }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("+ Nova categoria") },
                        onClick = { catMenu = false; newCat = true }
                    )
                }
            }

            TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Título", style = MaterialTheme.typography.titleLarge) },
                textStyle = MaterialTheme.typography.titleLarge,
                singleLine = true,
                colors = transparentFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            TextField(
                value = content,
                onValueChange = { content = it },
                placeholder = { Text("Escreva sua nota...") },
                colors = transparentFieldColors(),
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }

    if (newCat) {
        NameDialog(
            title = "Nova categoria",
            initial = "",
            confirmLabel = "Criar",
            onConfirm = { name ->
                vm.addCategory(name)?.let { categoryId = it.id }
                newCat = false
            },
            onDismiss = { newCat = false }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Apagar nota?") },
            text = { Text("Essa ação não pode ser desfeita.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteNote(initial.id)
                }) { Text("Apagar") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            }
        )
    }
}
