package com.example.gerenciador

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import java.util.concurrent.Executors
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
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var chipBar: LinearLayout

    private val thumbPool = Executors.newFixedThreadPool(3)
    private val thumbCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

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
        chipBar = LinearLayout(this).apply { setPadding(dp(8), 0, dp(8), 0) }
        chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipBar)
            visibility = View.GONE
        }
        root.addView(chipScroll, match, wrap)
        root.addView(emptyView, match, wrap)
        root.addView(container, match, match)
        setContentView(root)

        prefs.getString(KEY_TREE, null)?.let { saved ->
            DocumentFile.fromTreeUri(this, Uri.parse(saved))?.takeIf { it.canRead() }?.let {
                stack.add(it)
            }
        }
        renderShortcuts()
        buildList()
        refresh()
    }

    override fun onDestroy() {
        thumbPool.shutdownNow()
        super.onDestroy()
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

    private class Holder(val icon: TextView, val name: TextView, val sub: TextView?, val thumb: ImageView?)

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
        // caixa com emoji e, por cima, a miniatura (quando houver)
        fun box(icon: TextView): Pair<FrameLayout, ImageView> {
            val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val fl = FrameLayout(this).apply {
                addView(icon, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addView(thumb, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            return fl to thumb
        }
        val view: View
        val holder: Holder
        when (mode) {
            Mode.LISTA -> {
                val icon = text(24f).apply { gravity = Gravity.CENTER }
                val (fl, thumb) = box(icon)
                val name = text(16f)
                val sub = text(12f).apply { alpha = 0.65f }
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(name)
                    addView(sub)
                }
                view = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    addView(fl, LinearLayout.LayoutParams(dp(44), dp(44)))
                    addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                holder = Holder(icon, name, sub, thumb)
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
                holder = Holder(icon, name, sub, null)
            }
            Mode.GRADE -> {
                val icon = text(40f).apply { gravity = Gravity.CENTER }
                val (fl, thumb) = box(icon)
                val name = text(12f).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                }
                view = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    minimumHeight = dp(110)
                    addView(fl, LinearLayout.LayoutParams(dp(72), dp(72)))
                    addView(name, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                holder = Holder(icon, name, null, thumb)
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
                val t = h.thumb
                if (t != null) {
                    val mime = f.type ?: ""
                    if (!f.isDirectory && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                        loadThumb(f, t, mime.startsWith("video/"))
                    } else {
                        t.tag = null
                        t.setImageDrawable(null)
                    }
                }
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
        menu.add(0, M_ADD_SC, 7, "Adicionar atalho para esta pasta")
        menu.add(0, M_REFRESH, 8, "Atualizar")
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(M_PASTE)?.isVisible = clip.isNotEmpty()
        val hasDir = stack.isNotEmpty()
        menu.findItem(M_NEW_DIR)?.isEnabled = hasDir
        menu.findItem(M_NEW_FILE)?.isEnabled = hasDir
        menu.findItem(M_ADD_SC)?.isEnabled = hasDir
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
            M_ADD_SC -> addShortcut()
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

    // -------------------------------------------------------------- atalhos

    private fun renderShortcuts() {
        chipBar.removeAllViews()
        val list = ShortcutStore.load(this)
        chipScroll.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        for (sc in list) {
            val b = Button(this).apply {
                text = "⭐ " + sc.name
                textSize = 12f
                setAllCaps(false)
                minWidth = 0
                minimumWidth = 0
                setOnClickListener { openShortcut(sc) }
                setOnLongClickListener { shortcutMenu(sc); true }
            }
            chipBar.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        }
    }

    private fun addShortcut() {
        val cur = stack.lastOrNull()
        val rootUri = prefs.getString(KEY_TREE, null)
        if (cur == null || rootUri == null) {
            toast("Escolha uma pasta primeiro")
            return
        }
        val path = stack.drop(1).map { it.name ?: "" }
        prompt("Nome do atalho", cur.name ?: "Pasta") { name ->
            val list = ShortcutStore.load(this)
            if (list.any { it.root == rootUri && it.path == path }) {
                toast("Já existe um atalho para esta pasta")
            } else {
                list += Shortcut(name, rootUri, path)
                ShortcutStore.save(this, list)
                renderShortcuts()
                toast("Atalho salvo")
            }
        }
    }

    private fun openShortcut(sc: Shortcut) {
        val root = DocumentFile.fromTreeUri(this, Uri.parse(sc.root))
        if (root == null || !root.canRead()) {
            toast("Sem acesso a essa pasta. Escolha-a de novo no menu.")
            return
        }
        Thread {
            val newStack = arrayListOf(root)
            var cur = root
            var missing: String? = null
            for (n in sc.path) {
                val c = cur.findFile(n)
                if (c == null || !c.isDirectory) {
                    missing = n
                    break
                }
                newStack.add(c)
                cur = c
            }
            runOnUiThread {
                prefs.edit().putString(KEY_TREE, sc.root).apply()
                stack.clear()
                stack.addAll(newStack)
                clearSearch()
                refresh()
                if (missing != null) toast("Pasta \"$missing\" não encontrada; mostrando o mais próximo")
            }
        }.start()
    }

    private fun shortcutMenu(sc: Shortcut) {
        AlertDialog.Builder(this)
            .setTitle(sc.name)
            .setItems(arrayOf("Renomear atalho", "Remover atalho")) { _, i ->
                if (i == 0) {
                    prompt("Renomear atalho", sc.name) { name ->
                        val list = ShortcutStore.load(this)
                        val idx = list.indexOfFirst { it.root == sc.root && it.path == sc.path }
                        if (idx >= 0) {
                            list[idx] = sc.copy(name = name)
                            ShortcutStore.save(this, list)
                            renderShortcuts()
                        }
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("Remover o atalho \"${sc.name}\"? A pasta em si não é apagada.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Remover") { _, _ ->
                            val list = ShortcutStore.load(this)
                            list.removeAll { it.root == sc.root && it.path == sc.path }
                            ShortcutStore.save(this, list)
                            renderShortcuts()
                            toast("Atalho removido")
                        }.show()
                }
            }.show()
    }

    // ---------------------------------------------------------- miniaturas

    private fun loadThumb(f: DocumentFile, iv: ImageView, video: Boolean) {
        val key = f.uri.toString()
        iv.tag = key
        val cached = thumbCache.get(key)
        if (cached != null) {
            iv.setImageBitmap(cached)
            return
        }
        iv.setImageDrawable(null)
        val uri = f.uri
        try {
            thumbPool.execute {
                val bmp = decodeThumb(uri, video)
                if (bmp != null) {
                    thumbCache.put(key, bmp)
                    runOnUiThread { if (iv.tag == key) iv.setImageBitmap(bmp) }
                }
            }
        } catch (e: Exception) {
            // pool encerrado (activity fechando)
        }
    }

    private fun decodeThumb(uri: Uri, video: Boolean): Bitmap? {
        val size = THUMB_PX
        return try {
            if (video) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(this, uri)
                    val b = r.frameAtTime ?: return null
                    val scale = size.toFloat() / maxOf(b.width, b.height)
                    if (scale >= 1f) b
                    else Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true)
                } finally {
                    r.release()
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
        } catch (e: Exception) {
            null
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
        const val THUMB_PX = 192
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
        const val M_ADD_SC = 9
        const val A_COPY = 101
        const val A_CUT = 102
        const val A_DELETE = 103
        const val A_SHARE = 104
        const val A_ALL = 105
        const val A_MORE = 106
    }
}
