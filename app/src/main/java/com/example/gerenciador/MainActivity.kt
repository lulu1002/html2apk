package com.example.gerenciador

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.EditText
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
 * Escolha uma pasta e navegue; toque longo num item abre o menu de ações.
 */
class MainActivity : Activity() {

    private enum class Sort(val label: String) { NOME("Nome"), TAMANHO("Tamanho"), DATA("Data") }

    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }
    private val stack = ArrayList<DocumentFile>() // raiz ... pasta atual
    private var all: List<DocumentFile> = emptyList()
    private var shown: List<DocumentFile> = emptyList()
    private var sort = Sort.NOME
    private var filter = ""

    // "área de transferência" interna para copiar/mover
    private var clip: DocumentFile? = null
    private var clipParent: DocumentFile? = null
    private var clipCut = false

    private lateinit var searchBox: EditText
    private lateinit var pathView: TextView
    private lateinit var list: ListView
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        pathView = TextView(this).apply { setPadding(32, 8, 32, 16) }
        emptyView = TextView(this).apply {
            setPadding(32, 48, 32, 48)
            visibility = View.GONE
        }
        list = ListView(this)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(searchBox, match, wrap)
        root.addView(pathView, match, wrap)
        root.addView(emptyView, match, wrap)
        root.addView(list, match, match)
        setContentView(root)

        list.setOnItemClickListener { _, _, pos, _ ->
            val item = shown[pos]
            if (item.isDirectory) {
                stack.add(item)
                clearSearch()
                refresh()
            } else {
                openFile(item)
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            showItemMenu(shown[pos])
            true
        }

        prefs.getString(KEY_TREE, null)?.let { saved ->
            DocumentFile.fromTreeUri(this, Uri.parse(saved))?.takeIf { it.canRead() }?.let {
                stack.add(it)
            }
        }
        refresh()
    }

    // ---------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_PICK, 0, "Escolher pasta").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_NEW_DIR, 1, "Nova pasta")
        menu.add(0, M_NEW_FILE, 2, "Novo arquivo")
        menu.add(0, M_PASTE, 3, "Colar").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_SORT, 4, "Ordenar")
        menu.add(0, M_REFRESH, 5, "Atualizar")
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(M_PASTE)?.isVisible = clip != null
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
            M_SORT -> {
                val labels = Sort.values().map { it.label }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Ordenar por")
                    .setSingleChoiceItems(labels, sort.ordinal) { d, which ->
                        sort = Sort.values()[which]
                        d.dismiss()
                        applyFilter()
                    }.show()
            }
            M_REFRESH -> refresh()
        }
        return true
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
        if (stack.isNotEmpty()) refresh() // pode ter sido editado
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

    private fun applyFilter() {
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
        list.adapter = object : ArrayAdapter<DocumentFile>(
            this, android.R.layout.simple_list_item_2, shown
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(
                    android.R.layout.simple_list_item_2, parent, false
                )
                val f = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text =
                    (if (f.isDirectory) "📁 " else "📄 ") + (f.name ?: "?")
                val date = f.lastModified().takeIf { it > 0 }?.let { fmt.format(Date(it)) } ?: ""
                val size = if (f.isDirectory) "" else Formatter.formatShortFileSize(context, f.length())
                v.findViewById<TextView>(android.R.id.text2).text =
                    listOf(size, date).filter { it.isNotEmpty() }.joinToString("  ·  ")
                return v
            }
        }
    }

    // ---------------------------------------------------------------- ações

    private fun showItemMenu(f: DocumentFile) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (!f.isDirectory) {
            actions += "Abrir" to { openFile(f) }
            actions += "Editar como texto" to { openEditor(f) }
            actions += "Compartilhar" to { share(f) }
        }
        actions += "Renomear" to { rename(f) }
        actions += "Copiar" to { setClip(f, false) }
        actions += "Mover" to { setClip(f, true) }
        actions += "Excluir" to { delete(f) }
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
            toast("Nenhum app para abrir este arquivo. Use \"Editar como texto\" se for texto.")
        }
    }

    private fun openEditor(file: DocumentFile) {
        startActivity(
            Intent(this, EditorActivity::class.java)
                .setData(file.uri)
                .putExtra(EditorActivity.EXTRA_NAME, file.name)
        )
    }

    private fun share(file: DocumentFile) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(file.type ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, file.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Compartilhar"))
    }

    private fun rename(f: DocumentFile) {
        prompt("Renomear", f.name ?: "") { name ->
            toast(if (f.renameTo(name)) "Renomeado" else "Falha ao renomear")
            refresh()
        }
    }

    private fun delete(f: DocumentFile) {
        AlertDialog.Builder(this)
            .setTitle("Excluir")
            .setMessage("Excluir \"${f.name}\"" + if (f.isDirectory) " e todo o conteúdo?" else "?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Excluir") { _, _ ->
                toast(if (f.delete()) "Excluído" else "Falha ao excluir")
                refresh()
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

    private fun setClip(f: DocumentFile, cut: Boolean) {
        clip = f
        clipParent = stack.lastOrNull()
        clipCut = cut
        invalidateOptionsMenu()
        toast(if (cut) "Pronto para mover. Abra a pasta destino e use Colar." else "Copiado. Abra a pasta destino e use Colar.")
    }

    private fun paste() {
        val src = clip ?: return
        val dest = stack.lastOrNull() ?: return
        val cut = clipCut
        val parent = clipParent
        toast("Trabalhando…")
        Thread {
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
            runOnUiThread {
                if (ok && cut) { clip = null; clipParent = null }
                toast(if (ok) "Concluído" else "Falha na operação")
                refresh()
            }
        }.start()
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
        const val M_PICK = 1
        const val M_NEW_DIR = 2
        const val M_NEW_FILE = 3
        const val M_PASTE = 4
        const val M_SORT = 5
        const val M_REFRESH = 6
    }
}
