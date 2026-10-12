package com.example.gerenciador

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Atalho para uma pasta: pasta raiz (árvore autorizada) + caminho de nomes até ela. */
data class Shortcut(val name: String, val root: String, val path: List<String>)

object ShortcutStore {
    private const val KEY = "shortcuts"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Shortcut> {
        val out = ArrayList<Shortcut>()
        try {
            val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.getJSONArray("path")
                out += Shortcut(
                    o.getString("name"),
                    o.getString("root"),
                    List(p.length()) { p.getString(it) }
                )
            }
        } catch (e: Exception) {
            // dados corrompidos: começa vazio
        }
        return out
    }

    fun save(ctx: Context, list: List<Shortcut>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("root", s.root)
                    .put("path", JSONArray(s.path))
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
