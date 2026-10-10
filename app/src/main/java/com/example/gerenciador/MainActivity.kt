package com.example.gerenciador

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gerenciador de arquivos baseado no Storage Access Framework.
 * Toque longo seleciona (vários itens); toque num arquivo abre; tema e visualização no menu.
 */
class MainActivity : Activity() {

    private enum class Sort(val label: String) { NOME("Nome"), TAMANHO("Tamanho"), DATA("Data") }
    private enum class Mode(val label: String) {
        LISTA("Lista detalhada"), COMPACTA("Lista compacta"), GRADE("Grade")
    }

    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }
    private val stack = ArrayList<DocumentFile>() // raiz ... pasta atual
    private var all: List<DocumentFile> = emptyList()
    private var shown: List<DocumentFile> = emptyList()
    private var sort = Sort.NOME
    private var mode = Mode.LISTA
    private var filter = ""

    // "área de transferência" interna para copiar/mover
    private var clip: List<DocumentFile> = emptyList()
    private var clipParent: DocumentFile? = null
    private var clipCut = false

    private var actionMode: ActionMode? = null

    private lateinit var searchBox: EditText
    private lateinit var pathView: TextView
    private lateinit var container: FrameLayout
    private lateinit var list: AbsListView
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)

        mode = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, "") ?: "") }.getOrDefault(Mode.LISTA)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchBox = EditText(this).apply {
            hint = "Buscar nesta pasta"
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filter = s?.toString().orEmpty()
                    applyFilter()
                }
            })
        }
        pathView = TextView(this).apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }
        emptyView = TextView(this).apply {
            setPadding(dp(16), dp(24), dp(16), dp(24))
            visibility = View.GONE
        }
        container = FrameLayout(this)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(searchBox, match, wrap)
        root.addView(pathView, match, wrap)
        root.addView(emptyView, match, wrap)
        root.addView(container, match, match)
        setContentView(root)

        prefs.getString(KEY_TREE, null)?.let { saved ->
            DocumentFile.fromTreeUri(this, Uri.parse(saved))?.takeIf { it.canRead() }?.let {
                stack.add(it)
            }
        }
        buildList()
        refresh()
    }

    // ------------------------------------------------------- lista / grade

    private fun buildList() {
        actionMode?.finish()
        container.removeAllViews()
        val v: AbsListView = if (mode == Mode.GRADE) {
            GridView(this).apply {
                numColumns = GridView.AUTO_FIT
                columnWidth = dp(104)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                verticalSpacing = dp(4)
                horizontalSpacing = dp(4)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
        } else ListView(this)

        v.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE_MODAL
        v.setMultiChoiceModeListener(selectionListener)
        v.setOnItemClickListener { _, _, pos, _ ->
            val item = shown[pos]
            if (item.isDirectory) {
                stack.add(item)
                clearSearch()
                refresh()
            } else {
                openFile(item)
            }
        }
        container.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        list = v
        applyFilter()
    }

    private class Holder(val icon: TextView, val name: TextView, val sub: TextView?)

    private fun selectedBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_activated), ColorDrawable(0x553F8CFF))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    private fun newItemView(): View {
        fun text(sp: Float) = TextView(this).apply {
            textSize = sp
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val view: View
        val holder: Holder
        when (mode) {
            Mode.LISTA -> {
                val icon = text(24f).apply { gravity = Gravity.CENTER; setWidth(dp(40)) }
                val name = text(16f)
                val sub = text(12f).apply { alpha = 0.65f }
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(name)
                    addView(sub)
                }
                view = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(icon)
                    addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                holder = Holder(icon, name, sub)
            }
            Mode.COMPACTA -> {
                val icon = text(18f).apply { gravity = Gravity.CENTER; setWidth(dp(32)) }
                val name = text(15f)
                val sub = text(12f).apply { alpha = 0.65f; setPadding(dp(8), 0, 0, 0) }
                view = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    addView(icon)
                    addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(sub)
                }
                holder = Holder(icon, name, sub)
            }
            Mode.GRADE -> {
                val icon = text(40f).apply { gravity = Gravity.CENTER }
                val name = text(12f).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                }
                view = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    minimumHeight = dp(100)
                    addView(icon, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(name, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                holder = Holder(icon, name, null)
            }
        }
        view.background = selectedBackground()
        view.tag = holder
        return view
    }

    private fun iconFor(f: DocumentFile): String {
        if (f.isDirectory) return "📁"
        val ext = (f.name ?: "").substringAfterLast('.', "").lowercase()
        val mime = f.type ?: ""
        return when {
            mime.startsWith("image/") -> "🖼️"
            mime.startsWith("video/") -> "🎬"
            mime.startsWith("audio/") -> "🎵"
            ext == "pdf" -> "📕"
            ext in setOf("zip", "rar", "7z", "tar", "gz") -> "🗜️"
            ext in setOf("apk") -> "📦"
            mime.startsWith("text/") || Highlighter.supports(f.name ?: "") || ext in setOf("txt", "log", "csv") -> "📝"
            else -> "📄"
        }
    }

    private fun applyFilter() {
        actionMode?.finish()
        val q = filter.trim().lowercase()
        val filtered = if (q.isEmpty()) all else all.filter { (it.name ?: "").lowercase().contains(q) }
        val cmp: Comparator<DocumentFile> = when (sort) {
            Sort.NOME -> compareBy<DocumentFile> { it.name?.lowercase() ?: "" }
            Sort.TAMANHO -> compareByDescending<DocumentFile> { it.length() }
            Sort.DATA -> compareByDescending<DocumentFile> { it.lastModified() }
        }
        shown = filtered.sortedWith(compareBy<DocumentFile> { !it.isDirectory }.then(cmp))

        emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = when {
            stack.isEmpty() -> "Use o menu para escolher uma pasta."
            all.isEmpty() -> "Pasta vazia."
            else -> "Nada encontrado."
        }

        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val items = shown
        val ctx = this
        list.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: newItemView()
                val h = v.tag as Holder
                val f = items[position]
                h.icon.text = iconFor(f)
                h.name.text = f.name ?: "?"
                val size = if (f.isDirectory) "" else Formatter.formatShortFileSize(ctx, f.length())
                val date = f.lastModified().takeIf { it > 0 }?.let { fmt.format(Date(it)) } ?: ""
                h.sub?.text = if (mode == Mode.COMPACTA) size
                else listOf(size, date).filter { it.isNotEmpty() }.joinToString("  ·  ")
                return v
            }
        }
    }

    // ------------------------------------------------------------ seleção

    private val selectionListener = object : AbsListView.MultiChoiceModeListener {
        override fun onItemCheckedStateChanged(mode: ActionMode, position: Int, id: Long, checked: Boolean) {
            mode.title = "${list.checkedItemCount} selecionado(s)"
            mode.invalidate()
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            menu.add(0, A_COPY, 0, "Copiar").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, A_CUT, 1, "Mover").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, A_DELETE, 2, "Excluir").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, A_SHARE, 3, "Compartilhar")
            menu.add(0, A_ALL, 4, "Selecionar tudo")
            menu.add(0, A_MORE, 5, "Mais ações…")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(A_MORE)?.isVisible = list.checkedItemCount == 1
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == A_ALL) {
                for (i in shown.indices) list.setItemChecked(i, true)
                return true
            }
            val sel = selected()
            if (sel.isEmpty()) return true
            when (item.itemId) {
                A_COPY -> setClip(sel, false)
                A_CUT -> setClip(sel, true)
                A_DELETE -> deleteAll(sel)
                A_SHARE -> share(sel)
                A_MORE -> showItemMenu(sel[0])
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
        }
    }

    private fun selected(): List<DocumentFile> {
        val arr = list.checkedItemPositions ?: return emptyList()
        val res = ArrayList<DocumentFile>()
        for (i in 0 until arr.size()) {
            if (arr.valueAt(i)) {
                val p = arr.keyAt(i)
                if (p in shown.indices) res += shown[p]
            }
        }
        return res
    }

    // ---------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_PICK, 0, "Escolher pasta").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_NEW_DIR, 1, "Nova pasta")
        menu.add(0, M_NEW_FILE, 2, "Novo arquivo")
        menu.add(0, M_PASTE, 3, "Colar").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_SORT, 4, "Ordenar")
        menu.add(0, M_VIEW, 5, "Modo de exibição")
        menu.add(0, M_THEME, 6, "Tema")
        menu.add(0, M_REFRESH, 7, "Atualizar")
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(M_PASTE)?.isVisible = clip.isNotEmpty()
        val hasDir = stack.isNotEmpty()
        menu.findItem(M_NEW_DIR)?.isEnabled = hasDir
        menu.findItem(M_NEW_FILE)?.isEnabled = hasDir
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            M_PICK -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
            M_NEW_DIR -> prompt("Nova pasta", "") { name ->
                val dir = stack.lastOrNull()?.createDirectory(name)
                toast(if (dir != null) "Pasta criada" else "Falha ao criar pasta")
                refresh()
            }
            M_NEW_FILE -> prompt("Novo arquivo (ex.: notas.txt)", "") { name ->
                val f = stack.lastOrNull()?.createFile(guessMime(name), name)
                if (f != null) {
                    refresh()
                    openEditor(f)
                } else toast("Falha ao criar arquivo")
            }
            M_PASTE -> paste()
            M_SORT -> choose("Ordenar por", Sort.values().map { it.label }, sort.ordinal) {
                sort = Sort.values()[it]
                applyFilter()
            }
            M_VIEW -> choose("Modo de exibição", Mode.values().map { it.label }, mode.ordinal) {
                mode = Mode.values()[it]
                prefs.edit().putString(KEY_MODE, mode.name).apply()
                buildList()
            }
            M_THEME -> {
                val modes = listOf("system", "light", "dark")
                choose("Tema", listOf("Seguir o sistema", "Claro", "Escuro"), modes.indexOf(AppTheme.mode(this))) {
                    AppTheme.setMode(this, modes[it])
                    recreate()
                }
            }
            M_REFRESH -> refresh()
        }
        return true
    }

    private fun choose(title: String, labels: List<String>, checked: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                d.dismiss()
                onPick(which)
            }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TREE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString(KEY_TREE, uri.toString()).apply()
            stack.clear()
            DocumentFile.fromTreeUri(this, uri)?.let { stack.add(it) }
            refresh()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            clearSearch()
            refresh()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (stack.isNotEmpty() && ::list.isInitialized) refresh() // pode ter sido editado
    }

    // ------------------------------------------------------------ listagem

    private fun clearSearch() {
        filter = ""
        searchBox.setText("")
    }

    private fun refresh() {
        val current = stack.lastOrNull()
        if (current == null) {
            pathView.text = "Nenhuma pasta selecionada"
            all = emptyList()
        } else {
            pathView.text = stack.joinToString(" / ") { it.name ?: "?" }
            all = current.listFiles().toList()
        }
        applyFilter()
        invalidateOptionsMenu()
    }

    // ---------------------------------------------------------------- ações

    private fun showItemMenu(f: DocumentFile) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (!f.isDirectory) {
            actions += "Abrir" to { openFile(f) }
            actions += "Editar como texto" to { openEditor(f) }
            actions += "Compartilhar" to { share(listOf(f)) }
        }
        actions += "Renomear" to { rename(f) }
        actions += "Detalhes" to { details(f) }
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun openFile(file: DocumentFile) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(file.uri, file.type ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // sem app para o tipo: tenta abrir no editor de texto
            openEditor(file)
        }
    }

    private fun openEditor(file: DocumentFile) {
        startActivity(
            Intent(this, EditorActivity::class.java)
                .setData(file.uri)
                .putExtra(EditorActivity.EXTRA_NAME, file.name)
        )
    }

    private fun share(files: List<DocumentFile>) {
        val only = files.filter { !it.isDirectory }
        if (only.isEmpty()) {
            toast("Pastas não podem ser compartilhadas")
            return
        }
        val send = if (only.size == 1) {
            Intent(Intent.ACTION_SEND)
                .setType(only[0].type ?: "*/*")
                .putExtra(Intent.EXTRA_STREAM, only[0].uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(only.map { it.uri }))
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Compartilhar"))
    }

    private fun rename(f: DocumentFile) {
        prompt("Renomear", f.name ?: "") { name ->
            toast(if (f.renameTo(name)) "Renomeado" else "Falha ao renomear")
            refresh()
        }
    }

    private fun deleteAll(files: List<DocumentFile>) {
        val msg = if (files.size == 1) "Excluir \"${files[0].name}\"" + if (files[0].isDirectory) " e todo o conteúdo?" else "?"
        else "Excluir ${files.size} itens (pastas incluem todo o conteúdo)?"
        AlertDialog.Builder(this)
            .setTitle("Excluir")
            .setMessage(msg)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir") { _, _ ->
                background { files.map { it.delete() }.all { it } }
            }.show()
    }

    private fun details(f: DocumentFile) {
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val msg = buildString {
            appendLine("Nome: ${f.name}")
            appendLine("Tipo: ${if (f.isDirectory) "pasta" else (f.type ?: "desconhecido")}")
            if (!f.isDirectory) appendLine("Tamanho: ${Formatter.formatFileSize(this@MainActivity, f.length())} (${f.length()} bytes)")
            if (f.lastModified() > 0) appendLine("Modificado: ${fmt.format(Date(f.lastModified()))}")
            appendLine("Gravável: ${if (f.canWrite()) "sim" else "não"}")
            append("URI: ${f.uri}")
        }
        AlertDialog.Builder(this).setTitle("Detalhes").setMessage(msg)
            .setPositiveButton("OK", null).show()
    }

    // ----------------------------------------------------- copiar/mover/colar

    private fun setClip(files: List<DocumentFile>, cut: Boolean) {
        clip = files
        clipParent = stack.lastOrNull()
        clipCut = cut
        invalidateOptionsMenu()
        toast("${files.size} item(ns) " + (if (cut) "para mover" else "copiado(s)") + ". Abra a pasta destino e use Colar.")
    }

    private fun paste() {
        val items = clip
        val dest = stack.lastOrNull() ?: return
        val cut = clipCut
        val parent = clipParent
        background {
            var allOk = true
            for (src in items) {
                var ok = false
                try {
                    if (cut && parent != null) {
                        ok = try {
                            DocumentsContract.moveDocument(contentResolver, src.uri, parent.uri, dest.uri) != null
                        } catch (e: Exception) {
                            false
                        }
                    }
                    if (!ok) {
                        ok = copyRecursive(src, dest)
                        if (ok && cut) src.delete()
                    }
                } catch (e: Exception) {
                    ok = false
                }
                allOk = allOk && ok
            }
            if (allOk && cut) runOnUiThread { clip = emptyList(); clipParent = null }
            allOk
        }
    }

    private fun copyRecursive(src: DocumentFile, destDir: DocumentFile): Boolean {
        val name = uniqueName(destDir, src.name ?: "sem_nome")
        if (src.isDirectory) {
            val nd = destDir.createDirectory(name) ?: return false
            var ok = true
            for (child in src.listFiles()) ok = copyRecursive(child, nd) && ok
            return ok
        }
        val nf = destDir.createFile(src.type ?: guessMime(name), name) ?: return false
        contentResolver.openInputStream(src.uri).use { input ->
            contentResolver.openOutputStream(nf.uri, "w").use { output ->
                if (input == null || output == null) return false
                input.copyTo(output)
            }
        }
        return true
    }

    private fun uniqueName(dir: DocumentFile, name: String): String {
        if (dir.findFile(name) == null) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base (cópia${if (i > 1) " $i" else ""})$ext"
            if (dir.findFile(candidate) == null) return candidate
            i++
        }
    }

    // ----------------------------------------------------------------- util

    /** Roda [work] fora da thread de UI e mostra o resultado + atualiza a lista. */
    private fun background(work: () -> Boolean) {
        toast("Trabalhando…")
        Thread {
            val ok = try { work() } catch (e: Exception) { false }
            runOnUiThread {
                toast(if (ok) "Concluído" else "Falha em parte da operação")
                refresh()
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun prompt(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSingleLine()
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("OK") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) onOk(v)
            }.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQ_TREE = 1
        const val KEY_TREE = "tree_uri"
        const val KEY_MODE = "view_mode"
        const val M_PICK = 1
        const val M_NEW_DIR = 2
        const val M_NEW_FILE = 3
        const val M_PASTE = 4
        const val M_SORT = 5
        const val M_VIEW = 6
        const val M_THEME = 7
        const val M_REFRESH = 8
        const val A_COPY = 101
        const val A_CUT = 102
        const val A_DELETE = 103
        const val A_SHARE = 104
        const val A_ALL = 105
        const val A_MORE = 106
    }
}
