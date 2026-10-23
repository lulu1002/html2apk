package com.notas.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Category(val id: String, val name: String)

data class Note(
    val id: String,
    val title: String,
    val content: String,
    val categoryId: String?,
    val pinned: Boolean,
    val updatedAt: Long
)

/** Guarda tudo em um arquivo JSON dentro do armazenamento privado do app. */
class Storage(context: Context) {
    private val file = File(context.filesDir, "notas.json")

    fun load(): Pair<List<Category>, List<Note>> {
        if (!file.exists()) return Pair(emptyList(), emptyList())
        return try {
            val root = JSONObject(file.readText())
            val cats = root.optJSONArray("categories") ?: JSONArray()
            val notes = root.optJSONArray("notes") ?: JSONArray()
            val catList = (0 until cats.length()).map {
                val o = cats.getJSONObject(it)
                Category(o.getString("id"), o.getString("name"))
            }
            val noteList = (0 until notes.length()).map {
                val o = notes.getJSONObject(it)
                Note(
                    id = o.getString("id"),
                    title = o.optString("title", ""),
                    content = o.optString("content", ""),
                    categoryId = if (o.isNull("categoryId")) null else o.getString("categoryId"),
                    pinned = o.optBoolean("pinned", false),
                    updatedAt = o.optLong("updatedAt", 0L)
                )
            }
            Pair(catList, noteList)
        } catch (e: Exception) {
            try {
                file.copyTo(File(file.path + ".bak"), overwrite = true)
            } catch (_: Exception) {
            }
            Pair(emptyList(), emptyList())
        }
    }

    fun save(categories: List<Category>, notes: List<Note>) {
        val catArr = JSONArray()
        categories.forEach { catArr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        val noteArr = JSONArray()
        notes.forEach {
            noteArr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("content", it.content)
                    .put("categoryId", it.categoryId ?: JSONObject.NULL)
                    .put("pinned", it.pinned)
                    .put("updatedAt", it.updatedAt)
            )
        }
        val json = JSONObject().put("categories", catArr).put("notes", noteArr).toString()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }
}
