package com.dnsswitch.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

abstract class BaseDnsTile(private val slot: Int) : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (DnsVpnService.activeSlot == slot) {
            DnsVpnService.stop(this)
            return
        }
        if (VpnService.prepare(this) != null) {
            openApp()      // precisa autorizar a VPN uma vez dentro do app
            return
        }
        DnsVpnService.start(this, slot)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val preset = DnsStore.getPreset(this, slot)
        tile.label = preset.name
        tile.state = if (DnsVpnService.activeSlot == slot) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = preset.dns1
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}

class Tile1 : BaseDnsTile(0)
class Tile2 : BaseDnsTile(1)
class Tile3 : BaseDnsTile(2)
class Tile4 : BaseDnsTile(3)
