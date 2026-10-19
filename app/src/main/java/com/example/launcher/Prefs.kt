package com.example.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Prefs(private val ctx: Context) {

    private val sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    companion object {
        val ACCENTS = intArrayOf(
            0xFF1E88E5.toInt(), // azul
            0xFF43A047.toInt(), // verde
            0xFFE53935.toInt(), // vermelho
            0xFF8E24AA.toInt(), // roxo
            0xFFFB8C00.toInt(), // laranja
            0xFF00ACC1.toInt()  // ciano
        )
        val THEME_NAMES = arrayOf("Seguir o sistema", "Claro", "Escuro", "AMOLED (preto)")
        val SORT_NAMES = arrayOf("Nome (A-Z)", "Instalados recentemente", "Mais usados")
        val TOPROW_NAMES = arrayOf("Desligada", "Recentes", "Mais usados")
        val ACTION_NAMES = arrayOf(
            "Nada", "Buscar apps", "Bloquear a tela", "Abrir Personalizar", "Abrir notificações"
        )
    }

    // ---- Linha rápida (recentes / mais usados) e gestos

    var topRow: Int
        get() = sp.getInt("topRow", 1)
        set(v) { sp.edit().putInt("topRow", v).apply() }

    var showClock: Boolean
        get() = sp.getBoolean("showClock", true)
        set(v) { sp.edit().putBoolean("showClock", v).apply() }

    /** Se ligado, abre direto na lista de apps (sem a tela inicial). */
    var startInDrawer: Boolean
        get() = sp.getBoolean("startInDrawer", false)
        set(v) { sp.edit().putBoolean("startInDrawer", v).apply() }

    var gestureDown: Int
        get() = sp.getInt("gestDown", 1)
        set(v) { sp.edit().putInt("gestDown", v).apply() }

    var gestureUp: Int
        get() = sp.getInt("gestUp", 0)
        set(v) { sp.edit().putInt("gestUp", v).apply() }

    var gestureDouble: Int
        get() = sp.getInt("gestDouble", 0)
        set(v) { sp.edit().putInt("gestDouble", v).apply() }

    fun recordUse(pkg: String) {
        sp.edit()
            .putInt("use_$pkg", useCount(pkg) + 1)
            .putLong("last_$pkg", System.currentTimeMillis())
            .apply()
    }

    fun useCount(pkg: String): Int = sp.getInt("use_$pkg", 0)

    fun lastUsed(pkg: String): Long = sp.getLong("last_$pkg", 0L)

    // ---- Bloqueio por app (usa a senha/PIN/padrão/digital do próprio celular)

    var locked: Set<String>
        get() = HashSet(sp.getStringSet("locked", emptySet()) ?: emptySet())
        set(v) { sp.edit().putStringSet("locked", HashSet(v)).apply() }

    // ---- Pastas

    fun folders(): LinkedHashMap<String, MutableList<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        try {
            val o = JSONObject(sp.getString("folders", "{}") ?: "{}")
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val a = o.getJSONArray(k)
                val l = ArrayList<String>()
                for (i in 0 until a.length()) l.add(a.getString(i))
                out[k] = l
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveFolders(m: Map<String, List<String>>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONArray(v))
        sp.edit().putString("folders", o.toString()).apply()
    }

    // ---- Backup (exportar / importar tudo em JSON)

    fun exportJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        val p = JSONObject()
        for ((k, v) in sp.all) {
            val e = JSONObject()
            when (v) {
                is Int -> { e.put("t", "i"); e.put("v", v) }
                is Long -> { e.put("t", "l"); e.put("v", v) }
                is Boolean -> { e.put("t", "b"); e.put("v", v) }
                is String -> { e.put("t", "s"); e.put("v", v) }
                is Set<*> -> { e.put("t", "ss"); e.put("v", JSONArray(v.map { it.toString() })) }
                else -> continue
            }
            p.put(k, e)
        }
        root.put("prefs", p)
        val f = wallpaperFile()
        if (f.exists()) root.put("wallpaper", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        return root.toString()
    }

    /** Lança exceção (sem alterar nada) se o arquivo for inválido. */
    fun importJson(text: String) {
        val root = JSONObject(text)
        val p = root.getJSONObject("prefs")
        val ed = sp.edit().clear()
        val keys = p.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val e = p.getJSONObject(k)
            when (e.getString("t")) {
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "s" -> ed.putString(k, e.getString("v"))
                "ss" -> {
                    val a = e.getJSONArray("v")
                    val s = HashSet<String>()
                    for (i in 0 until a.length()) s.add(a.getString(i))
                    ed.putStringSet(k, s)
                }
            }
        }
        val wp = root.optString("wallpaper", "")
        val bytes = if (wp.isNotEmpty()) Base64.decode(wp, Base64.DEFAULT) else null
        ed.apply()
        if (bytes != null) wallpaperFile().writeBytes(bytes) else clearWallpaper()
    }

    var columns: Int
        get() = sp.getInt("columns", 4)
        set(v) { sp.edit().putInt("columns", v).apply() }

    var iconSize: Int
        get() = sp.getInt("iconSize", 56)
        set(v) { sp.edit().putInt("iconSize", v).apply() }

    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) { sp.edit().putInt("theme", v).apply() }

    var accent: Int
        get() = sp.getInt("accent", 0)
        set(v) { sp.edit().putInt("accent", v).apply() }

    var sort: Int
        get() = sp.getInt("sort", 0)
        set(v) { sp.edit().putInt("sort", v).apply() }

    var showLabels: Boolean
        get() = sp.getBoolean("showLabels", true)
        set(v) { sp.edit().putBoolean("showLabels", v).apply() }

    var showDock: Boolean
        get() = sp.getBoolean("showDock", true)
        set(v) { sp.edit().putBoolean("showDock", v).apply() }

    var closeAfterLaunch: Boolean
        get() = sp.getBoolean("closeAfterLaunch", false)
        set(v) { sp.edit().putBoolean("closeAfterLaunch", v).apply() }

    var hidden: Set<String>
        get() = HashSet(sp.getStringSet("hidden", emptySet()) ?: emptySet())
        set(v) { sp.edit().putStringSet("hidden", HashSet(v)).apply() }

    var pinned: List<String>
        get() = (sp.getString("pinned", "") ?: "").split("|").filter { it.isNotEmpty() }
        set(v) { sp.edit().putString("pinned", v.joinToString("|")).apply() }

    fun accentColor(): Int = ACCENTS[accent.coerceIn(0, ACCENTS.size - 1)]

    fun isDark(): Boolean = when (theme) {
        1 -> false
        2, 3 -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun bg(): Int = when {
        theme == 3 -> Color.BLACK
        isDark() -> 0xFF121212.toInt()
        else -> 0xFFF5F5F5.toInt()
    }

    fun fg(): Int = if (isDark()) 0xFFEEEEEE.toInt() else 0xFF212121.toInt()

    fun surface(): Int = if (isDark()) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt()

    private val homeAlias = ComponentName(ctx, "com.example.launcher.HomeAlias")

    /** True quando o launcher está habilitado para aparecer como opção de tela inicial. */
    fun isHomeAllowed(): Boolean =
        ctx.packageManager.getComponentEnabledSetting(homeAlias) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setHomeAllowed(on: Boolean) {
        ctx.packageManager.setComponentEnabledSetting(
            homeAlias,
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
    }

    var dim: Int
        get() = sp.getInt("dim", 30)
        set(v) { sp.edit().putInt("dim", v).apply() }

    fun wallpaperFile(): File = File(ctx.filesDir, "wallpaper.jpg")

    fun hasWallpaper(): Boolean = wallpaperFile().exists()

    fun clearWallpaper() {
        wallpaperFile().delete()
    }

    fun reset() {
        clearWallpaper()
        sp.edit().clear().apply()
        setHomeAllowed(false)
    }
}
