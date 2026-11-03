package com.dnsswitch.vpn

import android.content.Context

object DnsStore {

    data class Preset(val name: String, val dns1: String, val dns2: String)

    private const val PREFS = "dns_presets"

    private val defaults = listOf(
        Preset("Google", "8.8.8.8", "8.8.4.4"),
        Preset("Cloudflare", "1.1.1.1", "1.0.0.1"),
        Preset("AdGuard", "94.140.14.14", "94.140.15.15"),
        Preset("Quad9", "9.9.9.9", "149.112.112.112")
    )

    fun getPreset(ctx: Context, slot: Int): Preset {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = defaults[slot]
        return Preset(
            p.getString("name_$slot", d.name) ?: d.name,
            p.getString("dns1_$slot", d.dns1) ?: d.dns1,
            p.getString("dns2_$slot", d.dns2) ?: d.dns2
        )
    }

    fun setPreset(ctx: Context, slot: Int, name: String, dns1: String, dns2: String) {
        val d = defaults[slot]
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name_$slot", name.trim().ifEmpty { d.name })
            .putString("dns1_$slot", dns1.trim().ifEmpty { d.dns1 })
            .putString("dns2_$slot", dns2.trim())
            .apply()
    }

    fun isValidIpv4(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        return parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255 }
    }
}
