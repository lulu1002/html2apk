package com.vpsmanager

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Shortcut(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val command: String,
    val enter: Boolean = true
)

/** Preferências do terminal (realce, tamanho da fonte) e atalhos do usuário. */
class TerminalSettings(context: Context) {
    private val prefs = context.getSharedPreferences("terminal_settings", Context.MODE_PRIVATE)

    var highlight by mutableStateOf(prefs.getBoolean("highlight", true))
        private set
    var fontSize by mutableStateOf(prefs.getInt("font_size", 11))
        private set
    val shortcuts = mutableStateListOf<Shortcut>().apply { addAll(loadShortcuts()) }

    fun updateHighlight(v: Boolean) {
        highlight = v
        prefs.edit().putBoolean("highlight", v).apply()
    }

    fun changeFont(delta: Int) {
        fontSize = (fontSize + delta).coerceIn(8, 20)
        prefs.edit().putInt("font_size", fontSize).apply()
    }

    fun addShortcut(s: Shortcut) {
        shortcuts.add(s)
        saveShortcuts()
    }

    fun updateShortcut(s: Shortcut) {
        val i = shortcuts.indexOfFirst { it.id == s.id }
        if (i >= 0) shortcuts[i] = s else shortcuts.add(s)
        saveShortcuts()
    }

    fun removeShortcut(id: String) {
        shortcuts.removeAll { it.id == id }
        saveShortcuts()
    }

    private fun loadShortcuts(): List<Shortcut> {
        val raw = prefs.getString("shortcuts", null) ?: return DEFAULTS
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Shortcut(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    command = o.getString("command"),
                    enter = o.optBoolean("enter", true)
                )
            }
        } catch (_: Exception) {
            DEFAULTS
        }
    }

    private fun saveShortcuts() {
        val arr = JSONArray()
        shortcuts.forEach {
            arr.put(
                JSONObject().apply {
                    put("id", it.id); put("label", it.label)
                    put("command", it.command); put("enter", it.enter)
                }
            )
        }
        prefs.edit().putString("shortcuts", arr.toString()).apply()
    }

    companion object {
        private val DEFAULTS = listOf(
            Shortcut("d1", "ls -la", "ls -la --color=auto"),
            Shortcut("d2", "Disco", "df -h"),
            Shortcut("d3", "Memória", "free -h"),
            Shortcut("d4", "Docker", "docker ps"),
            Shortcut("d5", "Logs ao vivo", "journalctl -f"),
            Shortcut("d6", "Atualizar", "sudo apt update && sudo apt upgrade", enter = false)
        )
    }
}
