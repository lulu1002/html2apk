package com.notas.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.UUID

const val NO_CATEGORY = "__none__"

class NotesViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = Storage(app)

    val categories = mutableStateListOf<Category>()
    val notes = mutableStateListOf<Note>()

    /** null = todas, NO_CATEGORY = sem categoria, senão o id da categoria */
    var selectedCategory by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var editing by mutableStateOf<Note?>(null)

    init {
        val (cats, ns) = storage.load()
        categories.addAll(cats)
        notes.addAll(ns)
    }

    private fun persist() = storage.save(categories.toList(), notes.toList())

    // ---------- Notas ----------

    fun openNew() {
        val cat = selectedCategory?.takeIf { id -> categories.any { it.id == id } }
        editing = Note(UUID.randomUUID().toString(), "", "", cat, false, System.currentTimeMillis())
    }

    fun openNote(note: Note) {
        editing = note
    }

    /** Salva automaticamente enquanto edita. Nota nova e vazia não é salva. */
    fun saveDraft(note: Note) {
        val i = notes.indexOfFirst { it.id == note.id }
        if (i < 0) {
            if (note.title.isBlank() && note.content.isBlank()) return
            notes.add(0, note.copy(updatedAt = System.currentTimeMillis()))
        } else {
            val old = notes[i]
            if (old.title == note.title && old.content == note.content &&
                old.categoryId == note.categoryId && old.pinned == note.pinned
            ) return
            notes[i] = note.copy(updatedAt = System.currentTimeMillis())
        }
        persist()
    }

    fun closeEditor(note: Note) {
        if (note.title.isBlank() && note.content.isBlank()) {
            notes.removeAll { it.id == note.id }
            persist()
        } else {
            saveDraft(note)
        }
        editing = null
    }

    fun deleteNote(id: String) {
        notes.removeAll { it.id == id }
        persist()
        editing = null
    }

    // ---------- Categorias ----------

    fun addCategory(name: String): Category? {
        val n = name.trim()
        if (n.isEmpty()) return null
        categories.firstOrNull { it.name.equals(n, ignoreCase = true) }?.let { return it }
        val c = Category(UUID.randomUUID().toString(), n)
        categories.add(c)
        persist()
        return c
    }

    fun renameCategory(id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val i = categories.indexOfFirst { it.id == id }
        if (i >= 0) {
            categories[i] = categories[i].copy(name = n)
            persist()
        }
    }

    fun deleteCategory(id: String) {
        categories.removeAll { it.id == id }
        for (i in notes.indices) {
            if (notes[i].categoryId == id) notes[i] = notes[i].copy(categoryId = null)
        }
        if (selectedCategory == id) selectedCategory = null
        persist()
    }
}
