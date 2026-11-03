package com.dnsswitch.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * VPN local que só captura o tráfego DNS (rotas /32 para os servidores DNS escolhidos)
 * e repassa cada consulta UDP para o servidor real. O resto da internet não passa pela VPN.
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.dnsswitch.vpn.START"
        const val ACTION_STOP = "com.dnsswitch.vpn.STOP"
        const val EXTRA_SLOT = "slot"
        private const val CHANNEL_ID = "dns_vpn"

        @Volatile
        var activeSlot: Int = -1

        fun start(ctx: Context, slot: Int) {
            val i = Intent(ctx, DnsVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SLOT, slot)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var pool: ExecutorService? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val slot = intent?.getIntExtra(EXTRA_SLOT, 0) ?: 0
        val preset = DnsStore.getPreset(this, slot)
        startForeground(1, buildNotification(preset.name))
        if (!startVpn(slot)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(name: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DNS ativo", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dns)
            .setContentTitle("DNS ativo: $name")
            .setContentText("Toque para abrir")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startVpn(slot: Int): Boolean {
        stopVpn()
        val p = DnsStore.getPreset(this, slot)
        val servers = listOf(p.dns1, p.dns2).filter { it.isNotBlank() && DnsStore.isValidIpv4(it) }
        if (servers.isEmpty()) return false

        val builder = Builder()
            .setSession("DNS Switch")
            .addAddress("10.111.222.1", 32)
        for (s in servers) {
            builder.addDnsServer(s)
            builder.addRoute(s, 32)
        }
        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            null
        } ?: return false

        tun = fd
        running = true
        activeSlot = slot
        pool = Executors.newCachedThreadPool()
        worker = Thread { forwardLoop(fd) }.also { it.start() }
        refreshTiles()
        return true
    }

    private fun stopVpn() {
        running = false
        try { tun?.close() } catch (_: IOException) {}
        tun = null
        pool?.shutdownNow()
        pool = null
        worker = null
        activeSlot = -1
        refreshTiles()
    }

    private fun refreshTiles() {
        val classes = listOf(Tile1::class.java, Tile2::class.java, Tile3::class.java, Tile4::class.java)
        for (c in classes) {
            try {
                TileService.requestListeningState(this, ComponentName(this, c))
            } catch (_: Exception) {}
        }
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ---------- Encaminhamento de pacotes DNS ----------

    private fun u8(b: Byte): Int = b.toInt() and 0xFF

    private fun forwardLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) handlePacket(buf, n, output)
            }
        } catch (_: IOException) {
        }
    }

    private fun handlePacket(pkt: ByteArray, len: Int, output: FileOutputStream) {
        if (len < 28) return
        if ((u8(pkt[0]) shr 4) != 4) return                 // só IPv4
        val ihl = (u8(pkt[0]) and 0x0F) * 4
        if (u8(pkt[9]) != 17 || len < ihl + 8) return        // só UDP
        val dstPort = (u8(pkt[ihl + 2]) shl 8) or u8(pkt[ihl + 3])
        if (dstPort != 53) return
        val srcPort = (u8(pkt[ihl]) shl 8) or u8(pkt[ihl + 1])
        val udpLen = (u8(pkt[ihl + 4]) shl 8) or u8(pkt[ihl + 5])
        val end = minOf(len, ihl + udpLen)
        if (end <= ihl + 8) return

        val payload = pkt.copyOfRange(ihl + 8, end)
        val srcIp = pkt.copyOfRange(12, 16)
        val dstIp = pkt.copyOfRange(16, 20)

        pool?.execute {
            try {
                val socket = DatagramSocket()
                protect(socket)               // sai pela rede real, não volta pra VPN
                socket.soTimeout = 5000
                socket.connect(InetAddress.getByAddress(dstIp), 53)
                socket.send(DatagramPacket(payload, payload.size))
                val rb = ByteArray(4096)
                val rp = DatagramPacket(rb, rb.size)
                socket.receive(rp)
                socket.close()
                val resp = buildUdpPacket(dstIp, srcIp, 53, srcPort, rb, rp.length)
                synchronized(output) { output.write(resp) }
            } catch (_: Exception) {
            }
        }
    }

    private fun buildUdpPacket(
        src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, data: ByteArray, dlen: Int
    ): ByteArray {
        val total = 20 + 8 + dlen
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte()
        p[3] = total.toByte()
        p[6] = 0x40                       // Don't Fragment
        p[8] = 64                         // TTL
        p[9] = 17                         // UDP
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        val cs = checksum(p, 0, 20)
        p[10] = (cs shr 8).toByte()
        p[11] = cs.toByte()
        p[20] = (srcPort shr 8).toByte()
        p[21] = srcPort.toByte()
        p[22] = (dstPort shr 8).toByte()
        p[23] = dstPort.toByte()
        val ul = 8 + dlen
        p[24] = (ul shr 8).toByte()
        p[25] = ul.toByte()
        // checksum UDP = 0 (opcional em IPv4)
        System.arraycopy(data, 0, p, 28, dlen)
        return p
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len) {
            sum += (u8(b[i]) shl 8) or u8(b[i + 1])
            i += 2
        }
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
