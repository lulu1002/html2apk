package com.example.wgtunnel

import android.content.Context
import android.net.VpnService
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config

/** Liga/desliga túneis fora da tela principal (tile e widget). */
object TunnelToggler {
    enum class Result { OK, NEEDS_PERMISSION, NO_TUNNEL }

    private const val PREFS = "prefs"
    private const val KEY_LAST = "last_tunnel"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun remember(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_LAST, name).apply()
    }

    /** Último túnel usado (ou o primeiro da lista). */
    fun lastName(ctx: Context): String? {
        val app = ctx.applicationContext as WgApp
        val names = app.store.names()
        val saved = prefs(ctx).getString(KEY_LAST, null)
        return if (saved != null && saved in names) saved else names.firstOrNull()
    }

    /** Túnel que está ligado agora, se houver. */
    fun activeName(ctx: Context): String? {
        val app = ctx.applicationContext as WgApp
        return app.store.names().firstOrNull { app.tunnel(it).state == Tunnel.State.UP }
    }

    /**
     * Se há túnel ligado, desliga; senão liga o último usado.
     * [done] é chamado numa thread de segundo plano quando termina.
     */
    fun toggle(ctx: Context, done: () -> Unit): Result {
        val app = ctx.applicationContext as WgApp
        val active = activeName(ctx)
        if (active != null) {
            run(app, active, false, done)
            return Result.OK
        }
        val name = lastName(ctx) ?: return Result.NO_TUNNEL
        if (VpnService.prepare(ctx) != null) return Result.NEEDS_PERMISSION
        run(app, name, true, done)
        return Result.OK
    }

    private fun run(app: WgApp, name: String, up: Boolean, done: () -> Unit) {
        val tunnel = app.tunnel(name)
        app.io.execute {
            try {
                val config = if (up) Config.parse(app.store.read(name).byteInputStream()) else null
                app.backend.setState(
                    tunnel,
                    if (up) Tunnel.State.UP else Tunnel.State.DOWN,
                    config
                )
            } catch (_: Exception) {
            }
            done()
        }
    }
}
