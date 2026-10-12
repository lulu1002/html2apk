package com.example.netmonitor

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.example.netmonitor.PacketUtil.ACK
import com.example.netmonitor.PacketUtil.FIN
import com.example.netmonitor.PacketUtil.M
import com.example.netmonitor.PacketUtil.PSH
import com.example.netmonitor.PacketUtil.RST
import com.example.netmonitor.PacketUtil.SYN
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock
import kotlin.random.Random
import java.util.concurrent.locks.ReentrantLock

/**
 * VPN local: todo o trafego IPv4 do celular passa por aqui, e encaminhado
 * para a rede real por sockets "protegidos" (que nao voltam para a VPN).
 * Cada conexao observada e registrada no Tracker.
 */
class MonitorVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.example.netmonitor.STOP"
        private val VPN_ADDR = byteArrayOf(10, 0, 0, 2)

        @Volatile
        var running = false
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var sweeper: ScheduledExecutorService? = null
    private val pool = Executors.newCachedThreadPool()
    private val tcpFlows = ConcurrentHashMap<String, TcpFlow>()
    private val udpFlows = ConcurrentHashMap<String, UdpFlow>()
    private val outLock = Any()
    private var out: FileOutputStream? = null
    private lateinit var resolver: AppResolver

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) startVpn()
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun startVpn() {
        resolver = AppResolver(this)
        val pfd = Builder()
            .setSession("Monitor de Rede")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setMtu(1500)
            .setBlocking(true)
            .establish() ?: return

        tun = pfd
        out = FileOutputStream(pfd.fileDescriptor)
        running = true

        val input = FileInputStream(pfd.fileDescriptor)
        reader = Thread({ readLoop(input) }, "tun-reader").also {
            it.isDaemon = true
            it.start()
        }

        sweeper = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ sweep() }, 30, 30, TimeUnit.SECONDS)
        }
    }

    private fun stopVpn() {
        if (!running && tun == null) return
        running = false
        try {
            tun?.close()
        } catch (e: Exception) {
        }
        tun = null
        reader?.interrupt()
        sweeper?.shutdownNow()
        tcpFlows.values.toList().forEach { it.close() }
        udpFlows.values.toList().forEach { it.close() }
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        tcpFlows.values.toList().forEach {
            if (now - it.lastActive > 10 * 60 * 1000L) it.timeout()
        }
    }

    private fun writeTun(p: ByteArray) {
        try {
            synchronized(outLock) { out?.write(p) }
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Leitura dos pacotes vindos dos apps
    // ------------------------------------------------------------------

    private fun readLoop(input: FileInputStream) {
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                try {
                    handlePacket(buf, n)
                } catch (e: Exception) {
                    // pacote problematico: ignora e segue
                }
            }
        } catch (e: Exception) {
            // interface fechada
        }
    }

    private fun handlePacket(buf: ByteArray, n: Int) {
        if (((buf[0].toInt() shr 4) and 0xF) != 4) return // so IPv4
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (n < ihl + 8) return
        val len = minOf(PacketUtil.u16(buf, 2), n)
        val proto = buf[9].toInt() and 0xFF
        val dst = buf.copyOfRange(16, 20)
        when (proto) {
            6 -> handleTcp(buf, ihl, len, dst)
            17 -> handleUdp(buf, ihl, len, dst)
        }
    }

    // ------------------------------------------------------------------
    // TCP
    // ------------------------------------------------------------------

    private fun handleTcp(buf: ByteArray, t: Int, len: Int, dst: ByteArray) {
        if (len < t + 20) return
        val sp = PacketUtil.u16(buf, t)
        val dp = PacketUtil.u16(buf, t + 2)
        val seq = PacketUtil.u32(buf, t + 4)
        val ack = PacketUtil.u32(buf, t + 8)
        val thl = ((buf[t + 12].toInt() shr 4) and 0xF) * 4
        val flags = buf[t + 13].toInt() and 0x3F
        val win = PacketUtil.u16(buf, t + 14)
        val dataOff = t + thl
        if (thl < 20 || dataOff > len) return
        val dLen = len - dataOff
        val key = "$sp>${PacketUtil.ipStr(dst)}:$dp"
        val flow = tcpFlows[key]

        if ((flags and SYN) != 0 && (flags and ACK) == 0) {
            if (flow != null && flow.isSameSyn(seq)) return // SYN repetido
            flow?.close()
            var ws = 0
            var i = t + 20
            while (i < dataOff) {
                val kind = buf[i].toInt() and 0xFF
                if (kind == 0) break
                if (kind == 1) {
                    i++
                    continue
                }
                if (i + 1 >= dataOff) break
                val l = buf[i + 1].toInt() and 0xFF
                if (l < 2) break
                if (kind == 3 && l == 3 && i + 2 < dataOff) ws = minOf(buf[i + 2].toInt() and 0xFF, 14)
                i += l
            }
            val uid = resolver.uidOf(6, resolver.addr(VPN_ADDR, sp), resolver.addr(dst, dp))
            val info = ConnInfo("TCP", uid, resolver.nameOf(uid), PacketUtil.ipStr(dst), dp)
            Tracker.add(info)
            val f = TcpFlow(key, sp, dst, dp, seq, ws, win, info)
            tcpFlows[key] = f
            f.start()
            return
        }

        if (flow == null) {
            // Pacote de uma conexao que nao conhecemos
            if ((flags and RST) != 0) return
            if (dLen == 0 && (flags and (FIN or SYN)) == 0) return // ACK solto: ignora
            val rst = if ((flags and ACK) != 0) {
                PacketUtil.tcp(dst, VPN_ADDR, dp, sp, ack, 0, RST, 0, null, 0, false)
            } else {
                val a = (seq + dLen + (if ((flags and FIN) != 0) 1 else 0)) and M
                PacketUtil.tcp(dst, VPN_ADDR, dp, sp, 0, a, RST or ACK, 0, null, 0, false)
            }
            writeTun(rst)
            return
        }

        flow.onPacket(flags, seq, ack, win, buf, dataOff, dLen)
    }

    private inner class TcpFlow(
        private val key: String,
        private val sp: Int,
        private val dstIp: ByteArray,
        private val dp: Int,
        private val clientIsn: Long,
        private val wscale: Int,
        initialWin: Int,
        private val info: ConnInfo
    ) {
        @Volatile
        var state = 0 // 0 conectando, 1 aberta, 2 fechada

        @Volatile
        var lastActive = System.currentTimeMillis()

        private val lock = ReentrantLock()
        private val cond = lock.newCondition()
        private var sock: Socket? = null
        private var serverSeq = 0L
        private var clientNext = (clientIsn + 1) and M
        private var clientAck = 0L
        private var clientWin = initialWin.toLong()
        private var clientFin = false
        private var serverFin = false

        @Volatile
        private var writerDone = false
        private val wq = LinkedBlockingQueue<ByteArray>()
        private val poison = ByteArray(0)
        private val queued = AtomicInteger()

        @Volatile
        private var throttled = false
        private val closed = AtomicBoolean(false)

        fun isSameSyn(seq: Long) = seq == clientIsn

        private fun send(seq: Long, flags: Int, data: ByteArray? = null, len: Int = 0, mss: Boolean = false) {
            val ack = lock.withLock { clientNext }
            val win = (65535 - queued.get()).coerceIn(0, 65535)
            writeTun(PacketUtil.tcp(dstIp, VPN_ADDR, dp, sp, seq, ack, flags, win, data, len, mss))
        }

        private fun sendAck() = send(lock.withLock { serverSeq }, ACK)

        private fun sendRstAndClose() {
            if (state != 2) send(lock.withLock { serverSeq }, RST or ACK)
            close()
        }

        fun timeout() = sendRstAndClose()

        fun start() {
            pool.execute {
                try {
                    val s = Socket()
                    s.bind(null) // cria o descritor de arquivo antes de proteger
                    if (!protect(s)) throw java.io.IOException("protect falhou")
                    s.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dp), 8000)
                    s.tcpNoDelay = true
                    if (state == 2) {
                        s.close()
                        return@execute
                    }
                    sock = s
                    val isn = Random.nextLong(0, M)
                    lock.withLock {
                        serverSeq = (isn + 1) and M
                        clientAck = serverSeq
                    }
                    state = 1
                    send(isn, SYN or ACK, mss = true)
                    pool.execute { writerLoop(s) }
                    readerLoop(s)
                } catch (e: Exception) {
                    if (state != 2) {
                        send(0, RST or ACK)
                        close()
                    }
                }
            }
        }

        /** Dados que vem do servidor remoto -> app. */
        private fun readerLoop(s: Socket) {
            val inp = s.getInputStream()
            val b = ByteArray(1360)
            try {
                while (state == 1) {
                    val n = inp.read(b)
                    if (n < 0) break
                    if (n == 0) continue
                    if (!waitWindow(n)) return
                    val seq = lock.withLock {
                        val q = serverSeq
                        serverSeq = (q + n) and M
                        q
                    }
                    send(seq, PSH or ACK, b, n)
                    info.down.addAndGet(n.toLong())
                    Tracker.totalDown.addAndGet(n.toLong())
                    val now = System.currentTimeMillis()
                    info.last = now
                    lastActive = now
                }
                if (state != 1) return
                lock.withLock {
                    val q = serverSeq
                    serverSeq = (q + 1) and M
                    serverFin = true
                    send(q, FIN or ACK)
                }
                checkDone()
            } catch (e: Exception) {
                if (state == 1) sendRstAndClose()
            }
        }

        /** Espera o app ter espaco na janela de recepcao dele. */
        private fun waitWindow(n: Int): Boolean {
            lock.withLock {
                while (state == 1) {
                    val inflight = (serverSeq - clientAck) and M
                    if (clientWin > 0 && (inflight == 0L || inflight + n <= clientWin)) return true
                    cond.await(500, TimeUnit.MILLISECONDS)
                }
            }
            return false
        }

        /** Dados que vem do app -> servidor remoto. */
        private fun writerLoop(s: Socket) {
            try {
                val o = s.getOutputStream()
                while (true) {
                    val d = wq.take()
                    if (d === poison) {
                        if (state == 1) {
                            try {
                                s.shutdownOutput()
                            } catch (e: Exception) {
                            }
                        }
                        break
                    }
                    o.write(d)
                    o.flush()
                    queued.addAndGet(-d.size)
                    if (throttled && queued.get() < 16384) {
                        throttled = false
                        sendAck()
                    }
                }
                writerDone = true
                checkDone()
            } catch (e: Exception) {
                if (state == 1) sendRstAndClose()
            }
        }

        fun onPacket(flags: Int, seq: Long, ack: Long, win: Int, buf: ByteArray, off: Int, dLen: Int) {
            lastActive = System.currentTimeMillis()
            if ((flags and RST) != 0) {
                close()
                return
            }
            if (state != 1) return

            if ((flags and ACK) != 0) {
                lock.withLock {
                    clientAck = ack
                    clientWin = win.toLong() shl wscale
                    cond.signalAll()
                }
            }

            if (dLen > 0) {
                val accept = lock.withLock {
                    if (seq == clientNext) {
                        clientNext = (clientNext + dLen) and M
                        true
                    } else false
                }
                if (accept) {
                    wq.put(buf.copyOfRange(off, off + dLen))
                    if (queued.addAndGet(dLen) > 49152) throttled = true
                    info.up.addAndGet(dLen.toLong())
                    Tracker.totalUp.addAndGet(dLen.toLong())
                    info.last = System.currentTimeMillis()
                }
                sendAck() // ACK mesmo se for retransmissao, para o app se resincronizar
            }

            if ((flags and FIN) != 0) {
                val first = lock.withLock {
                    if (!clientFin && ((seq + dLen) and M) == clientNext) {
                        clientFin = true
                        clientNext = (clientNext + 1) and M
                        true
                    } else false
                }
                sendAck()
                if (first) wq.put(poison)
                checkDone()
            }
        }

        private fun checkDone() {
            if (clientFin && serverFin && writerDone) close()
        }

        fun close() {
            if (closed.getAndSet(true)) return
            state = 2
            try {
                sock?.close()
            } catch (e: Exception) {
            }
            wq.offer(poison)
            lock.withLock { cond.signalAll() }
            tcpFlows.remove(key, this)
            info.active = false
            info.last = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------------
    // UDP
    // ------------------------------------------------------------------

    private fun handleUdp(buf: ByteArray, t: Int, len: Int, dst: ByteArray) {
        if (len < t + 8) return
        val sp = PacketUtil.u16(buf, t)
        val dp = PacketUtil.u16(buf, t + 2)
        val ulen = PacketUtil.u16(buf, t + 4)
        val dLen = minOf(ulen - 8, len - t - 8)
        if (dLen < 0) return
        val key = "$sp>${PacketUtil.ipStr(dst)}:$dp"
        var flow = udpFlows[key]
        if (flow == null) {
            val uid = resolver.uidOf(17, resolver.addr(VPN_ADDR, sp), resolver.addr(dst, dp))
            val info = ConnInfo("UDP", uid, resolver.nameOf(uid), PacketUtil.ipStr(dst), dp)
            if (dp != 53) Tracker.add(info) // consultas DNS nao poluem a lista
            flow = try {
                UdpFlow(key, sp, dst, dp, info)
            } catch (e: Exception) {
                return
            }
            udpFlows[key] = flow
            flow.startReader()
        }
        flow.send(buf, t + 8, dLen)
    }

    private inner class UdpFlow(
        private val key: String,
        private val sp: Int,
        private val dstIp: ByteArray,
        private val dp: Int,
        private val info: ConnInfo
    ) {
        private val sock = DatagramSocket()
        private val dest = InetSocketAddress(InetAddress.getByAddress(dstIp), dp)
        private val closed = AtomicBoolean(false)

        init {
            protect(sock)
            sock.soTimeout = if (dp == 53) 5000 else 30000
        }

        fun send(buf: ByteArray, off: Int, len: Int) {
            try {
                sock.send(DatagramPacket(buf, off, len, dest))
                info.up.addAndGet(len.toLong())
                Tracker.totalUp.addAndGet(len.toLong())
                info.last = System.currentTimeMillis()
            } catch (e: Exception) {
                close()
            }
        }

        fun startReader() {
            pool.execute {
                val rb = ByteArray(65535)
                val pkt = DatagramPacket(rb, rb.size)
                try {
                    while (!closed.get()) {
                        try {
                            sock.receive(pkt)
                        } catch (e: SocketTimeoutException) {
                            break
                        }
                        val n = pkt.length
                        writeTun(PacketUtil.udp(dstIp, VPN_ADDR, dp, sp, rb, 0, n))
                        info.down.addAndGet(n.toLong())
                        Tracker.totalDown.addAndGet(n.toLong())
                        info.last = System.currentTimeMillis()
                        if (dp == 53) Dns.parseResponse(rb, 0, n)
                    }
                } catch (e: Exception) {
                }
                close()
            }
        }

        fun close() {
            if (closed.getAndSet(true)) return
            try {
                sock.close()
            } catch (e: Exception) {
            }
            udpFlows.remove(key, this)
            info.active = false
        }
    }
}
