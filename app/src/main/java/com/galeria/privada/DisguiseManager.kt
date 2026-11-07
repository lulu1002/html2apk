package com.galeria.privada

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Controla o disfarce de calculadora: troca qual ícone/launcher aparece na tela de apps. */
object DisguiseManager {
    private const val PREFS = "disguise_prefs"
    private const val DEFAULT_CODE = "1961"
    private const val MAIN_ALIAS = "com.galeria.privada.MainLauncher"
    private const val CALC_ALIAS = "com.galeria.privada.CalcLauncher"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)

    fun code(ctx: Context): String = prefs(ctx).getString("code", null) ?: DEFAULT_CODE

    fun setCode(ctx: Context, code: String) {
        prefs(ctx).edit().putString("code", code).apply()
    }

    private fun setComponent(ctx: Context, name: String, enabled: Boolean) {
        val pm = ctx.packageManager
        val state = if (enabled)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        pm.setComponentEnabledSetting(ComponentName(ctx.packageName, name), state, PackageManager.DONT_KILL_APP)
    }

    /** Liga/desliga o disfarce: ativa o ícone de Calculadora e esconde o ícone real. */
    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", enabled).apply()
        setComponent(ctx, MAIN_ALIAS, !enabled)
        setComponent(ctx, CALC_ALIAS, enabled)
    }
}
