package com.example.gerenciador

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/** Tema do app = modo (sistema/claro/escuro) + paleta de cores. */
object AppTheme {
    private const val KEY_MODE = "theme"
    private const val KEY_PALETTE = "palette"

    /** chave -> nome exibido */
    val PALETTES = listOf(
        "classic" to "⚪ Padrão",
        "blue" to "🔵 Azul",
        "green" to "🟢 Verde",
        "orange" to "🟠 Laranja",
        "pink" to "🌸 Rosa",
        "purple" to "🟣 Roxo",
        "red" to "🔴 Vermelho",
        "teal" to "🩵 Turquesa",
        "graphite" to "⚫ Grafite",
        "amoled" to "🖤 AMOLED (preto puro)"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun mode(ctx: Context): String = prefs(ctx).getString(KEY_MODE, "system") ?: "system"

    fun setMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY_MODE, mode).apply()
    }

    fun palette(ctx: Context): String {
        val p = prefs(ctx).getString(KEY_PALETTE, "classic") ?: "classic"
        return if (PALETTES.any { it.first == p }) p else "classic"
    }

    fun setPalette(ctx: Context, palette: String) {
        prefs(ctx).edit().putString(KEY_PALETTE, palette).apply()
    }

    fun isDark(ctx: Context): Boolean {
        if (palette(ctx) == "amoled") return true // preto puro só existe no escuro
        return when (mode(ctx)) {
            "light" -> false
            "dark" -> true
            else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    /** Chamar ANTES de super.onCreate(). */
    fun apply(activity: Activity) {
        val name = "Gm_" + palette(activity).replaceFirstChar { it.uppercase() } +
            if (isDark(activity)) "_Dark" else "_Light"
        val id = activity.resources.getIdentifier(name, "style", activity.packageName)
        activity.setTheme(
            if (id != 0) id
            else if (isDark(activity)) android.R.style.Theme_Material
            else android.R.style.Theme_Material_Light_DarkActionBar
        )
    }
}
