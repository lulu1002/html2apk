package com.example.wgtunnel

import android.app.Application
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Túnel conhecido pelo backend. Precisa ser o MESMO objeto enquanto o túnel está ligado. */
class AppTunnel(private val tunnelName: String, private val onChanged: () -> Unit) : Tunnel {
    @Volatile
    var state: Tunnel.State = Tunnel.State.DOWN

    override fun getName(): String = tunnelName

    override fun onStateChange(newState: Tunnel.State) {
        state = newState
        onChanged()
    }
}

/** Guarda os arquivos .conf em filesDir/tunnels. */
class TunnelStore(context: android.content.Context) {
    private val dir = File(context.filesDir, "tunnels").apply { mkdirs() }

    private fun file(name: String) = File(dir, "$name.conf")

    fun names(): List<String> =
        dir.listFiles { f -> f.isFile && f.extension == "conf" }
            ?.map { it.nameWithoutExtension }
            ?.sortedBy { it.lowercase() }
            ?: emptyList()

    fun exists(name: String) = file(name).exists()
    fun read(name: String): String = file(name).readText()
    fun write(name: String, text: String) = file(name).writeText(text)
    fun delete(name: String) {
        file(name).delete()
    }
}

class WgApp : Application() {
    val backend: GoBackend by lazy { GoBackend(applicationContext) }
    val store: TunnelStore by lazy { TunnelStore(applicationContext) }
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    /** Chamado (em qualquer thread) quando algum túnel muda de estado. */
    @Volatile
    var onChange: (() -> Unit)? = null

    private val tunnels = HashMap<String, AppTunnel>()

    fun tunnel(name: String): AppTunnel = synchronized(tunnels) {
        tunnels.getOrPut(name) { AppTunnel(name) { onChange?.invoke() } }
    }

    fun forget(name: String) {
        synchronized(tunnels) { tunnels.remove(name) }
    }
}
