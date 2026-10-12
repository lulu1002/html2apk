package com.example.netmonitor

/** Funcoes para ler e montar pacotes IPv4 / TCP / UDP. */
object PacketUtil {
    const val M = 0xFFFFFFFFL

    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or
            ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or
            (b[o + 3].toLong() and 0xFF)

    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte()
        b[o + 1] = v.toByte()
    }

    fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v shr 24).toByte()
        b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte()
        b[o + 3] = v.toByte()
    }

    fun ipStr(b: ByteArray): String =
        "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"

    private fun sum(b: ByteArray, off: Int, len: Int, init: Long): Long {
        var s = init
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) s += (b[i].toInt() and 0xFF) shl 8
        return s
    }

    private fun fold(s0: Long): Int {
        var s = s0
        while ((s ushr 16) != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    private fun ipHeader(p: ByteArray, total: Int, proto: Int, src: ByteArray, dst: ByteArray) {
        p[0] = 0x45
        put16(p, 2, total)
        p[6] = 0x40 // DF
        p[8] = 64
        p[9] = proto.toByte()
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        put16(p, 10, fold(sum(p, 0, 20, 0L)))
    }

    fun tcp(
        src: ByteArray, dst: ByteArray, sp: Int, dp: Int,
        seq: Long, ack: Long, flags: Int, win: Int,
        data: ByteArray?, len: Int, mss: Boolean
    ): ByteArray {
        val thl = if (mss) 24 else 20
        val total = 20 + thl + len
        val p = ByteArray(total)
        ipHeader(p, total, 6, src, dst)
        val t = 20
        put16(p, t, sp)
        put16(p, t + 2, dp)
        put32(p, t + 4, seq and M)
        put32(p, t + 8, ack and M)
        p[t + 12] = ((thl / 4) shl 4).toByte()
        p[t + 13] = flags.toByte()
        put16(p, t + 14, win)
        if (mss) {
            p[t + 20] = 2
            p[t + 21] = 4
            put16(p, t + 22, 1400)
        }
        if (data != null && len > 0) System.arraycopy(data, 0, p, t + thl, len)
        var s = sum(src, 0, 4, 0L) + sum(dst, 0, 4, 0L) + 6 + (thl + len)
        s = sum(p, t, thl + len, s)
        put16(p, t + 16, fold(s))
        return p
    }

    fun udp(
        src: ByteArray, dst: ByteArray, sp: Int, dp: Int,
        data: ByteArray, off: Int, len: Int
    ): ByteArray {
        val ulen = 8 + len
        val total = 20 + ulen
        val p = ByteArray(total)
        ipHeader(p, total, 17, src, dst)
        put16(p, 20, sp)
        put16(p, 22, dp)
        put16(p, 24, ulen)
        System.arraycopy(data, off, p, 28, len)
        var s = sum(src, 0, 4, 0L) + sum(dst, 0, 4, 0L) + 17 + ulen
        s = sum(p, 20, ulen, s)
        var c = fold(s)
        if (c == 0) c = 0xFFFF
        put16(p, 26, c)
        return p
    }
}
