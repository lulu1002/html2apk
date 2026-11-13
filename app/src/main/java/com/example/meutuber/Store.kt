package com.example.meutuber

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Entry(
    val title: String, val sub: String, val url: String,
    val thumb: String?, val chUrl: String?, val music: Boolean = false
)

/** Histórico, favoritos e inscrições ficam só no aparelho (SharedPreferences). */
object Store {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("meutuber", Context.MODE_PRIVATE) }

    fun list(k: String): List<Entry> {
        val a = JSONArray(sp.getString(k, "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Entry(
                o.getString("t"), o.getString("s"), o.getString("u"),
                o.optString("i").ifBlank { null }, o.optString("c").ifBlank { null }, o.optBoolean("m")
            )
        }
    }

    private fun save(k: String, l: List<Entry>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("t", it.title).put("s", it.sub).put("u", it.url)
                .put("i", it.thumb ?: "").put("c", it.chUrl ?: "").put("m", it.music))
        }
        sp.edit().putString(k, a.toString()).apply()
    }

    fun has(k: String, url: String) = list(k).any { it.url == url }

    fun toggle(k: String, e: Entry) {
        val l = list(k)
        save(k, if (l.any { it.url == e.url }) l.filter { it.url != e.url } else listOf(e) + l)
    }

    fun addFront(k: String, e: Entry, max: Int = 200) =
        save(k, (listOf(e) + list(k).filter { it.url != e.url }).take(max))

    fun clear(k: String) = save(k, emptyList())

    var sponsor: Boolean
        get() = sp.getBoolean("sponsor", true)
        set(v) = sp.edit().putBoolean("sponsor", v).apply()

    var maxH: Int
        get() = sp.getInt("maxh", 720)
        set(v) = sp.edit().putInt("maxh", v).apply()
}
