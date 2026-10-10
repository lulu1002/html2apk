package com.example.gerenciador

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast

/** Editor de texto simples: abre via URI (SAF), salva de volta no mesmo arquivo. */
class EditorActivity : Activity() {

    private lateinit var editor: EditText
    private var dirty = false
    private var loading = true
    private var wrap = true
    private var textSize = 14f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        title = intent.getStringExtra(EXTRA_NAME) ?: uri.lastPathSegment ?: "Editor"

        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            setPadding(24, 16, 24, 16)
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!loading && !dirty) {
                        dirty = true
                        updateTitle()
                    }
                }
            })
        }
        setContentView(editor)

        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            if (bytes.size > MAX_BYTES) {
                Toast.makeText(this, "Arquivo grande demais para o editor (máx. 2 MB)", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            if (bytes.take(8000).any { it == 0.toByte() }) {
                Toast.makeText(this, "Parece um arquivo binário; não dá para editar como texto", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            editor.setText(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao abrir: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        loading = false
    }

    private fun updateTitle() {
        val base = intent.getStringExtra(EXTRA_NAME) ?: "Editor"
        title = if (dirty) "* $base" else base
    }

    private fun save(): Boolean {
        val uri = intent.data ?: return false
        return try {
            // "wt" = escreve e trunca (evita sobras de conteúdo antigo)
            contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(editor.text.toString().toByteArray(Charsets.UTF_8))
            } ?: error("sem acesso de escrita")
            dirty = false
            updateTitle()
            Toast.makeText(this, "Salvo", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao salvar: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_SAVE, 0, "Salvar").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, M_WRAP, 1, "Quebra de linha: ligada")
        menu.add(0, M_BIGGER, 2, "Fonte maior")
        menu.add(0, M_SMALLER, 3, "Fonte menor")
        menu.add(0, M_TOP, 4, "Ir ao início")
        menu.add(0, M_END, 5, "Ir ao fim")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            M_SAVE -> save()
            M_WRAP -> {
                wrap = !wrap
                editor.setHorizontallyScrolling(!wrap)
                item.title = "Quebra de linha: " + if (wrap) "ligada" else "desligada"
            }
            M_BIGGER -> setSize(textSize + 2)
            M_SMALLER -> setSize(textSize - 2)
            M_TOP -> editor.setSelection(0)
            M_END -> editor.setSelection(editor.text.length)
        }
        return true
    }

    private fun setSize(sp: Float) {
        textSize = sp.coerceIn(8f, 32f)
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!dirty) {
            super.onBackPressed()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Alterações não salvas")
            .setMessage("Salvar antes de sair?")
            .setPositiveButton("Salvar") { _, _ -> if (save()) finish() }
            .setNegativeButton("Descartar") { _, _ -> dirty = false; finish() }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    companion object {
        const val EXTRA_NAME = "name"
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val M_SAVE = 1
        private const val M_WRAP = 2
        private const val M_BIGGER = 3
        private const val M_SMALLER = 4
        private const val M_TOP = 5
        private const val M_END = 6
    }
}
