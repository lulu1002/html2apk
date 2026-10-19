package com.example.wgtunnel

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Atalho no painel de configurações rápidas. */
class VpnTileService : TileService() {

    override fun onStartListening() {
        update()
    }

    override fun onClick() {
        val app = application as WgApp
        when (TunnelToggler.toggle(this) { app.refreshSurfaces() }) {
            TunnelToggler.Result.OK -> update()
            TunnelToggler.Result.NEEDS_PERMISSION, TunnelToggler.Result.NO_TUNNEL -> openApp()
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val active = TunnelToggler.activeName(this)
        val name = active ?: TunnelToggler.lastName(this)
        tile.label = name ?: getString(R.string.app_name)
        tile.state = if (active != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(if (active != null) R.string.connected else R.string.disconnected)
        }
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        Toast.makeText(this, R.string.open_app_first, Toast.LENGTH_LONG).show()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
