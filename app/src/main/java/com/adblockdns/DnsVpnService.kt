package com.adblockdns

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * VPN local: só o tráfego para o DNS "falso" (10.111.222.2) entra no túnel.
 * Cada consulta é bloqueada (resposta 0.0.0.0) ou repassada ao DNS real.
 */
class DnsVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var thread: Thread? = null
    private var pool: ExecutorService? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        val fd = try {
            Builder()
                .setSession("Bloqueador DNS")
                .addAddress(VPN_ADDRESS, 24)
                .addDnsServer(DNS_ADDRESS)
                .addRoute(DNS_ADDRESS, 32)
                .setMtu(1500)
                .setBlocking(true)
                .establish()
        } catch (e: Exception) {
            null
        }
        if (fd == null) {
            stopSelf()
            return
        }
        tun = fd
        running = true
        DnsStats.running.value = true
        val list = BlockList.get(this)
        pool = Executors.newFixedThreadPool(8)
        thread = Thread({ loop(fd, list) }, "dns-vpn").also { it.start() }
    }

    private fun loop(fd: ParcelFileDescriptor, list: BlockList) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) {
                    Thread.sleep(10)
                    continue
                }
                val pkt = UdpPacket.parse(buf, n) ?: continue
                pool?.execute { handle(pkt, list, output) }
            }
        } catch (_: Exception) {
        } finally {
            stopVpn()
        }
    }

    private fun handle(pkt: UdpPacket, list: BlockList, out: FileOutputStream) {
        val q = DnsMessage.parseQuestion(pkt.payload)
        val response: ByteArray? =
            if (q != null && list.isBlocked(q.name)) {
                if (q.type == 1) DnsStats.record(q.name, true)
                DnsMessage.blockedResponse(pkt.payload, q)
            } else {
                if (q != null && q.type == 1) DnsStats.record(q.name, false)
                forward(pkt.payload)
            }
        if (response != null) {
            val bytes = pkt.reply(response)
            synchronized(out) {
                try {
                    out.write(bytes)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Repassa a consulta ao DNS real (socket protegido: não volta para o túnel). */
    private fun forward(payload: ByteArray): ByteArray? {
        for (server in UPSTREAMS) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 3000
                    s.send(DatagramPacket(payload, payload.size, InetAddress.getByAddress(server), 53))
                    val buf = ByteArray(4096)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    return buf.copyOf(p.length)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun stopVpn() {
        running = false
        DnsStats.running.value = false
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        pool?.shutdownNow()
        pool = null
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.adblockdns.STOP"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val DNS_ADDRESS = "10.111.222.2"
        private val UPSTREAMS = listOf(
            byteArrayOf(1, 1, 1, 1),
            byteArrayOf(8, 8, 8, 8)
        )
    }
}
