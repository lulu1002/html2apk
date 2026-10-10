package com.example.gerenciador

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile

/**
 * Gerenciador de arquivos simples baseado no Storage Access Framework.
 * O usuário escolhe uma pasta e navega nela; tocar num arquivo abre no app padrão.
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }
    private val stack = ArrayList<DocumentFile>() // pilha de pastas (raiz ... atual)
    private var items: List<DocumentFile> = emptyList()

    private lateinit var pathView: TextView
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pick = Button(this).apply {
            text = "Escolher pasta"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
            }
        }
        pathView = TextView(this).apply { setPadding(32, 16, 32, 16) }
        list = ListView(this)
        root.addView(pick, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(pathView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(list, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        list.setOnItemClickListener { _, _, pos, _ ->
            val item = items[pos]
            if (item.isDirectory) {
                stack.add(item)
                refresh()
            } else {
                openFile(item)
            }
        }

        prefs.getString(KEY_TREE, null)?.let { saved ->
            DocumentFile.fromTreeUri(this, Uri.parse(saved))?.takeIf { it.canRead() }?.let {
                stack.add(it)
            }
        }
        refresh()
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
            refresh()
        } else {
            super.onBackPressed()
        }
    }

    private fun refresh() {
        val current = stack.lastOrNull()
        if (current == null) {
            pathView.text = "Nenhuma pasta selecionada"
            items = emptyList()
        } else {
            pathView.text = stack.joinToString(" / ") { it.name ?: "?" }
            items = current.listFiles()
                .sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))
        }
        val labels = items.map { (if (it.isDirectory) "📁 " else "📄 ") + (it.name ?: "?") }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun openFile(file: DocumentFile) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(file.uri, file.type ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nenhum app para abrir este arquivo", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val REQ_TREE = 1
        const val KEY_TREE = "tree_uri"
    }
}
