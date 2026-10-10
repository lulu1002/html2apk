package com.example.gerenciador

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/** Tema do app: "system" (segue o Android), "light" ou "dark". */
object AppTheme {
    const val KEY = "theme"

    fun mode(ctx: Context): String =
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString(KEY, "system") ?: "system"

    fun setMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
    }

    fun isDark(ctx: Context): Boolean = when (mode(ctx)) {
        "light" -> false
        "dark" -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /** Chamar ANTES de super.onCreate(). */
    fun apply(activity: Activity) {
        activity.setTheme(
            if (isDark(activity)) android.R.style.Theme_Material
            else android.R.style.Theme_Material_Light_DarkActionBar
        )
    }
}
