package com.adblockdns

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

private fun u16(b: ByteArray, off: Int): Int =
    ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

/** Pacote IPv4/UDP com destino à porta 53, lido da interface TUN. */
class UdpPacket(
    private val srcIp: ByteArray,
    private val dstIp: ByteArray,
    private val srcPort: Int,
    private val dstPort: Int,
    val payload: ByteArray
) {
    /** Monta o pacote IPv4/UDP de resposta (origem e destino invertidos). */
    fun reply(data: ByteArray): ByteArray {
        val total = 28 + data.size
        val b = ByteBuffer.allocate(total)
        b.put(0x45.toByte())               // versão 4, IHL 5
        b.put(0.toByte())                  // TOS
        b.putShort(total.toShort())
        b.putShort(0.toShort())            // identificação
        b.putShort(0x4000.toShort())       // DF
        b.put(64.toByte())                 // TTL
        b.put(17.toByte())                 // UDP
        b.putShort(0.toShort())            // checksum (calculado abaixo)
        b.put(dstIp)
        b.put(srcIp)
        b.putShort(dstPort.toShort())
        b.putShort(srcPort.toShort())
        b.putShort((8 + data.size).toShort())
        b.putShort(0.toShort())            // checksum UDP opcional em IPv4
        b.put(data)
        val arr = b.array()
        val ck = checksum(arr, 0, 20)
        arr[10] = (ck shr 8).toByte()
        arr[11] = ck.toByte()
        return arr
    }

    companion object {
        fun parse(buf: ByteArray, len: Int): UdpPacket? {
            if (len < 28) return null
            if (((buf[0].toInt() ushr 4) and 0xF) != 4) return null
            val ihl = (buf[0].toInt() and 0xF) * 4
            if (ihl < 20 || len < ihl + 8) return null
            if ((buf[9].toInt() and 0xFF) != 17) return null
            val dstPort = u16(buf, ihl + 2)
            if (dstPort != 53) return null
            val udpLen = u16(buf, ihl + 4)
            val plen = minOf(udpLen - 8, len - ihl - 8)
            if (plen <= 0) return null
            return UdpPacket(
                buf.copyOfRange(12, 16),
                buf.copyOfRange(16, 20),
                u16(buf, ihl),
                dstPort,
                buf.copyOfRange(ihl + 8, ihl + 8 + plen)
            )
        }

        private fun checksum(b: ByteArray, off: Int, len: Int): Int {
            var sum = 0L
            var i = off
            while (i < off + len) {
                sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                i += 2
            }
            while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
            return (sum.inv() and 0xFFFF).toInt()
        }
    }
}

object DnsMessage {
    class Question(val name: String, val type: Int, val endOffset: Int)

    /** Lê a pergunta de uma consulta DNS simples (1 pergunta, sem compressão). */
    fun parseQuestion(d: ByteArray): Question? {
        if (d.size < 17) return null
        val flags = u16(d, 2)
        if ((flags and 0x8000) != 0) return null        // já é uma resposta
        if (((flags shr 11) and 0xF) != 0) return null  // só consultas padrão
        if (u16(d, 4) != 1) return null
        var pos = 12
        val sb = StringBuilder()
        while (true) {
            if (pos >= d.size) return null
            val l = d[pos].toInt() and 0xFF
            if (l == 0) {
                pos++
                break
            }
            if ((l and 0xC0) != 0) return null
            if (pos + 1 + l > d.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, pos + 1, l, Charsets.US_ASCII))
            pos += 1 + l
        }
        if (pos + 4 > d.size) return null
        return Question(sb.toString().lowercase(), u16(d, pos), pos + 4)
    }

    /**
     * Resposta para domínio bloqueado: A → 0.0.0.0, AAAA → ::,
     * demais tipos → resposta vazia (NOERROR).
     */
    fun blockedResponse(req: ByteArray, q: Question): ByteArray {
        val withAnswer = q.type == 1 || q.type == 28
        val rdLen = if (q.type == 28) 16 else 4
        val flags = 0x8000 or (u16(req, 2) and 0x0100) or 0x0080
        val out = ByteArrayOutputStream()
        out.write(req, 0, 2)                               // ID
        out.write(flags shr 8); out.write(flags and 0xFF)
        out.write(0); out.write(1)                         // QDCOUNT
        out.write(0); out.write(if (withAnswer) 1 else 0)  // ANCOUNT
        out.write(0); out.write(0)                         // NSCOUNT
        out.write(0); out.write(0)                         // ARCOUNT
        out.write(req, 12, q.endOffset - 12)               // pergunta original
        if (withAnswer) {
            out.write(0xC0); out.write(0x0C)               // ponteiro para o nome
            out.write(0); out.write(q.type)
            out.write(0); out.write(1)                     // classe IN
            out.write(0); out.write(0); out.write(0); out.write(60) // TTL
            out.write(0); out.write(rdLen)
            out.write(ByteArray(rdLen))
        }
        return out.toByteArray()
    }
}
