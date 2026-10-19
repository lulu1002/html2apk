package com.exemplo.radio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Station(val name: String, val url: String, var fav: Boolean = false)

object StationStore {
    private const val PREFS = "radio_prefs"
    private const val KEY = "stations"

    // Estações iniciais de exemplo. Links de streaming mudam com o tempo:
    // adicione/remova as suas direto no app (botão "+" e toque longo para apagar).
    private val defaults = listOf(
        Station("BBC World Service", "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service"),
        Station("SomaFM - Groove Salad", "https://ice1.somafm.com/groovesalad-128-mp3"),
        Station("Radio Paradise", "https://stream.radioparadise.com/mp3-128")
    )

    fun load(ctx: Context): MutableList<Station> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return defaults.map { it.copy() }.toMutableList()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) {
                val o = arr.getJSONObject(it)
                Station(o.getString("name"), o.getString("url"), o.optBoolean("fav", false))
            }
        } catch (e: Exception) {
            defaults.map { it.copy() }.toMutableList()
        }
    }

    fun save(ctx: Context, list: List<Station>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("url", it.url).put("fav", it.fav))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
