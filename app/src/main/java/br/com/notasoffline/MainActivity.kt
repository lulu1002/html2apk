package br.com.notasoffline

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Note(val id: Long, var title: String, var body: String, var category: String, var updated: Long)

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("notas_locais", Context.MODE_PRIVATE) }
    private val notes = mutableListOf<Note>()
    private val categories = mutableListOf<String>()
    private var selectedCategory = "Todas"
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var categoryLabel: TextView

    private val navy = Color.rgb(28, 39, 61)
    private val blue = Color.rgb(53, 104, 204)
    private val pale = Color.rgb(242, 245, 250)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadData()
        if (categories.isEmpty()) { categories.add("Pessoal"); categories.add("Trabalho"); saveData() }
        showHome()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int = navy, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply { text = label; setOnClickListener { action() } }
    private fun pad(view: View, all: Int = 12) { view.setPadding(dp(all), dp(all), dp(all), dp(all)) }

    private fun setup(title: String) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pale)
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val heading = text(title, 25f, navy, true)
        root.addView(heading)
        setContentView(root)
    }

    private fun showHome() {
        setup("Bloco de Notas")
        root.addView(text("Suas ideias, organizadas no aparelho.", 14f, Color.DKGRAY))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(button("+ Nova nota") { editNote(null) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Categorias") { manageCategories() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        categoryLabel = text("Categoria: $selectedCategory", 15f, navy, true)
        root.addView(categoryLabel)
        val spinner = Spinner(this)
        val options = listOf("Todas") + categories
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinner.setSelection(options.indexOf(selectedCategory).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCategory = options[position]
                categoryLabel.text = "Categoria: $selectedCategory"
                renderNotes()
            }
        }
        root.addView(spinner)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        renderNotes()
    }

    private fun renderNotes() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val filtered = notes.filter { selectedCategory == "Todas" || it.category == selectedCategory }
            .sortedByDescending { it.updated }
        if (filtered.isEmpty()) {
            val empty = text("Nenhuma nota por aqui. Toque em “+ Nova nota” para começar.", 15f, Color.GRAY)
            pad(empty, 22); list.addView(empty); return
        }
        filtered.forEach { note ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            val title = text(note.title.ifBlank { "Sem título" }, 18f, navy, true)
            card.addView(title)
            card.addView(text(note.category + "  •  " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(note.updated)), 12f, blue))
            val preview = note.body.replace("\n", " ").take(140)
            card.addView(text(if (preview.isBlank()) "Nota vazia" else preview, 14f, Color.DKGRAY))
            val row = LinearLayout(this).apply { gravity = Gravity.END }
            row.addView(button("Editar") { editNote(note) })
            row.addView(button("Apagar") { confirmDeleteNote(note) })
            card.addView(row)
            val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(10)
            list.addView(card, lp)
        }
    }

    private fun editNote(existing: Note?) {
        if (categories.isEmpty()) {
            Toast.makeText(this, "Crie uma categoria antes de adicionar notas.", Toast.LENGTH_LONG).show()
            manageCategories(); return
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val titleInput = EditText(this).apply { hint = "Título da nota"; setText(existing?.title ?: "") }
        val bodyInput = EditText(this).apply {
            hint = "Escreva sua nota aqui…"; setText(existing?.body ?: "")
            minLines = 6; gravity = Gravity.TOP; inputType =  android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val categorySpinner = Spinner(this)
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        categorySpinner.setSelection(categories.indexOf(existing?.category ?: selectedCategory).let { if (it < 0) 0 else it })
        form.addView(titleInput); form.addView(categorySpinner); form.addView(bodyInput)
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "Nova nota" else "Editar nota")
            .setView(form).setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text.toString().trim()
                val body = bodyInput.text.toString().trim()
                if (title.isBlank() && body.isBlank()) {
                    Toast.makeText(this, "Escreva um título ou conteúdo.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val category = categorySpinner.selectedItem.toString()
                if (existing == null) notes.add(Note(System.currentTimeMillis(), title, body, category, System.currentTimeMillis()))
                else { existing.title = title; existing.body = body; existing.category = category; existing.updated = System.currentTimeMillis() }
                saveData(); dialog.dismiss(); showHome()
            }
        }
        dialog.show()
    }

    private fun manageCategories() {
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows); choices.addView(scroll)
        fun refreshRows() {
            rows.removeAllViews()
            categories.forEach { category ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(text(category, 16f), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(button("Renomear") { renameCategory(category) })
                row.addView(button("Apagar") { deleteCategory(category) })
                rows.addView(row)
            }
            if (categories.isEmpty()) rows.addView(text("Nenhuma categoria criada.", 14f, Color.GRAY))
        }
        refreshRows()
        val dialog = AlertDialog.Builder(this).setTitle("Categorias").setView(choices)
            .setNegativeButton("Fechar") { _, _ -> showHome() }
            .setPositiveButton("+ Criar", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val input = EditText(this).apply { hint = "Nome da categoria" }
                AlertDialog.Builder(this).setTitle("Nova categoria").setView(input)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Criar") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotBlank() && categories.none { it.equals(name, true) }) {
                            categories.add(name); saveData()
                            dialog.dismiss(); manageCategories()
                        } else Toast.makeText(this, "Nome vazio ou já existente.", Toast.LENGTH_SHORT).show()
                    }.show()
            }
        }
        dialog.show()
    }

    private fun renameCategory(old: String) {
        val input = EditText(this).apply { setText(old); hint = "Nome da categoria" }
        AlertDialog.Builder(this).setTitle("Renomear categoria").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Salvar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank() && categories.none { it.equals(name, true) && it != old }) {
                    categories[categories.indexOf(old)] = name
                    notes.filter { it.category == old }.forEach { it.category = name }
                    if (selectedCategory == old) selectedCategory = name
                    saveData(); manageCategories()
                } else Toast.makeText(this, "Nome inválido ou já utilizado.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun deleteCategory(category: String) {
        AlertDialog.Builder(this).setTitle("Apagar categoria?")
            .setMessage("As notas desta categoria também serão apagadas. Esta ação não pode ser desfeita.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Apagar") { _, _ ->
                categories.remove(category)
                notes.removeAll { it.category == category }
                if (selectedCategory == category) selectedCategory = "Todas"
                saveData(); manageCategories()
            }.show()
    }

    private fun confirmDeleteNote(note: Note) {
        AlertDialog.Builder(this).setTitle("Apagar nota?")
            .setMessage("A nota “${note.title.ifBlank { "Sem título" }}” será removida.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Apagar") { _, _ ->
                notes.remove(note); saveData(); renderNotes()
            }.show()
    }

    private fun saveData() {
        val noteArray = JSONArray()
        notes.forEach { note ->
            noteArray.put(JSONObject().put("id", note.id).put("title", note.title).put("body", note.body)
                .put("category", note.category).put("updated", note.updated))
        }
        val categoryArray = JSONArray()
        categories.forEach { categoryArray.put(category) }
        prefs.edit().putString("notes", noteArray.toString()).putString("categories", categoryArray.toString()).apply()
    }

    private fun loadData() {
        try {
            val categoryArray = JSONArray(prefs.getString("categories", "[]"))
            for (i in 0 until categoryArray.length()) categories.add(categoryArray.getString(i))
            val noteArray = JSONArray(prefs.getString("notes", "[]"))
            for (i in 0 until noteArray.length()) {
                val o = noteArray.getJSONObject(i)
                notes.add(Note(o.optLong("id"), o.optString("title"), o.optString("body"),
                    o.optString("category", "Pessoal"), o.optLong("updated")))
            }
        } catch (_: Exception) { notes.clear(); categories.clear() }
    }
}
