package com.example.netmonitor

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** Descobre qual app e dono de uma conexao. */
class AppResolver(ctx: Context) {
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val pm = ctx.packageManager
    private val cache = ConcurrentHashMap<Int, String>()

    /** proto: 6 = TCP, 17 = UDP. Retorna -1 se nao for possivel descobrir. */
    fun uidOf(proto: Int, local: InetSocketAddress, remote: InetSocketAddress): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        return try {
            cm.getConnectionOwnerUid(proto, local, remote)
        } catch (e: Exception) {
            -1
        }
    }

    fun nameOf(uid: Int): String {
        if (uid < 0) return "App desconhecido"
        cache[uid]?.let { return it }
        val name = when (uid) {
            0 -> "root"
            1000 -> "Sistema Android"
            else -> try {
                val pkg = pm.getPackagesForUid(uid)?.firstOrNull()
                if (pkg == null) "uid $uid" else {
                    try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (e: Exception) {
                        pkg
                    }
                }
            } catch (e: Exception) {
                "uid $uid"
            }
        }
        cache[uid] = name
        return name
    }

    fun addr(ip: ByteArray, port: Int): InetSocketAddress =
        InetSocketAddress(InetAddress.getByAddress(ip), port)
}
