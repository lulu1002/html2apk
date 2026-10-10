package com.example.gerenciador

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Editor de texto: realce de sintaxe, localizar/substituir, salvar no mesmo arquivo (SAF). */
class EditorActivity : Activity() {

    private lateinit var editor: EditText
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var replInput: EditText
    private lateinit var caseBox: CheckBox
    private lateinit var countView: TextView

    private var dirty = false
    private var loading = true
    private var wrap = true
    private var textSize = 14f
    private var dark = false
    private var fileName = ""

    private val handler = Handler(Looper.getMainLooper())
    private val highlightTask = Runnable { highlight() }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        dark = AppTheme.isDark(this)

        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        fileName = intent.getStringExtra(EXTRA_NAME) ?: uri.lastPathSegment ?: "Editor"
        title = fileName

        val wrapH = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (loading) return
                    if (!dirty) {
                        dirty = true
                        updateTitle()
                    }
                    handler.removeCallbacks(highlightTask)
                    handler.postDelayed(highlightTask, 300)
                }
            })
        }

        buildFindBar()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(findBar, match, wrapH)
        root.addView(editor, LinearLayout.LayoutParams(match, 0, 1f))
        setContentView(root)

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
        highlight()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------ localizar / substituir

    private fun buildFindBar() {
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onClick() }
        }
        val wrapH = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        findInput = EditText(this).apply {
            hint = "Localizar"
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = updateCount()
            })
        }
        replInput = EditText(this).apply {
            hint = "Substituir por"
            setSingleLine()
        }
        caseBox = CheckBox(this).apply {
            text = "Aa (maiúsc./minúsc.)"
            setOnCheckedChangeListener { _, _ -> updateCount() }
        }
        countView = TextView(this).apply { setPadding(dp(12), 0, 0, 0) }

        val row1 = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(findInput, LinearLayout.LayoutParams(0, wrapH, 1f))
            addView(btn("◀") { findNext(false) })
            addView(btn("▶") { findNext(true) })
            addView(btn("✕") { toggleFindBar(false) })
        }
        val row2 = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(replInput, LinearLayout.LayoutParams(0, wrapH, 1f))
            addView(btn("Subst.") { replaceCurrent() })
            addView(btn("Todas") { replaceAll() })
        }
        val row3 = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(caseBox)
            addView(countView)
        }
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
            addView(row1, match, wrapH)
            addView(row2, match, wrapH)
            addView(row3, match, wrapH)
        }
    }

    private fun toggleFindBar(show: Boolean) {
        findBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            findInput.requestFocus()
            updateCount()
        } else {
            editor.requestFocus()
        }
    }

    private fun ignoreCase() = !caseBox.isChecked

    private fun updateCount() {
        val q = findInput.text.toString()
        if (q.isEmpty()) {
            countView.text = ""
            return
        }
        val t = editor.text.toString()
        var n = 0
        var i = t.indexOf(q, 0, ignoreCase())
        while (i >= 0) {
            n++
            i = t.indexOf(q, i + q.length, ignoreCase())
        }
        countView.text = "$n resultado(s)"
    }

    private fun findNext(forward: Boolean) {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        val t = editor.text.toString()
        val ic = ignoreCase()
        var idx = if (forward) {
            t.indexOf(q, editor.selectionEnd.coerceAtLeast(0), ic)
        } else {
            val from = editor.selectionStart - 1
            if (from < 0) -1 else t.lastIndexOf(q, from, ic)
        }
        if (idx < 0) idx = if (forward) t.indexOf(q, 0, ic) else t.lastIndexOf(q, t.length, ic)
        if (idx < 0) {
            Toast.makeText(this, "Não encontrado", Toast.LENGTH_SHORT).show()
            return
        }
        editor.requestFocus()
        editor.setSelection(idx, idx + q.length)
    }

    private fun replaceCurrent() {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        val s = editor.selectionStart
        val e = editor.selectionEnd
        if (s in 0 until e && editor.text.substring(s, e).equals(q, ignoreCase())) {
            editor.text.replace(s, e, replInput.text.toString())
        }
        findNext(true)
    }

    private fun replaceAll() {
        val q = findInput.text.toString()
        if (q.isEmpty()) return
        val opts = if (ignoreCase()) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = Regex(Regex.escape(q), opts)
        val t = editor.text.toString()
        val n = regex.findAll(t).count()
        if (n == 0) {
            Toast.makeText(this, "Não encontrado", Toast.LENGTH_SHORT).show()
            return
        }
        editor.setText(regex.replace(t, Regex.escapeReplacement(replInput.text.toString())))
        Toast.makeText(this, "$n substituição(ões)", Toast.LENGTH_SHORT).show()
        updateCount()
    }

    // ------------------------------------------------------------ realce / salvar

    private fun highlight() {
        if (!::editor.isInitialized) return
        Highlighter.apply(editor.text, fileName, dark)
    }

    private fun updateTitle() {
        title = if (dirty) "* $fileName" else fileName
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

    // ----------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_SAVE, 0, "Salvar").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, M_FIND, 1, "Localizar e substituir").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, M_WRAP, 2, "Quebra de linha: ligada")
        menu.add(0, M_BIGGER, 3, "Fonte maior")
        menu.add(0, M_SMALLER, 4, "Fonte menor")
        menu.add(0, M_TOP, 5, "Ir ao início")
        menu.add(0, M_END, 6, "Ir ao fim")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            M_SAVE -> save()
            M_FIND -> toggleFindBar(findBar.visibility != View.VISIBLE)
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_NAME = "name"
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val M_SAVE = 1
        private const val M_FIND = 2
        private const val M_WRAP = 3
        private const val M_BIGGER = 4
        private const val M_SMALLER = 5
        private const val M_TOP = 6
        private const val M_END = 7
    }
}
