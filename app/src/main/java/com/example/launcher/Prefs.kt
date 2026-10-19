package com.example.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
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
        val SORT_NAMES = arrayOf("Nome (A-Z)", "Mais recentes primeiro")
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
