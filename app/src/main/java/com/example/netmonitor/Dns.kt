package com.example.netmonitor

/** Le respostas DNS (tipo A) para associar IP -> nome do dominio. */
object Dns {
    fun parseResponse(b: ByteArray, off: Int, len: Int) {
        try {
            if (len < 12) return
            val end = off + len
            val qd = PacketUtil.u16(b, off + 4)
            val an = PacketUtil.u16(b, off + 6)
            var p = off + 12
            var qname: String? = null
            for (i in 0 until qd) {
                val r = readName(b, p, off, end)
                if (i == 0) qname = r.first
                p = r.second + 4
            }
            if (qname == null) return
            for (i in 0 until an) {
                p = readName(b, p, off, end).second
                if (p + 10 > end) return
                val type = PacketUtil.u16(b, p)
                val rdlen = PacketUtil.u16(b, p + 8)
                p += 10
                if (p + rdlen > end) return
                if (type == 1 && rdlen == 4) {
                    Tracker.dns[PacketUtil.ipStr(b.copyOfRange(p, p + 4))] = qname
                }
                p += rdlen
            }
        } catch (e: Exception) {
            // resposta malformada: ignora
        }
    }

    /** Retorna (nome, posicao logo depois do nome no pacote). */
    private fun readName(b: ByteArray, start: Int, base: Int, end: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var p = start
        var next = -1
        var guard = 0
        while (p < end && guard++ < 64) {
            val l = b[p].toInt() and 0xFF
            if (l == 0) {
                p++
                break
            }
            if ((l and 0xC0) == 0xC0) {
                val ptr = ((l and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                if (next < 0) next = p + 2
                p = base + ptr
                continue
            }
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(b, p + 1, l, Charsets.ISO_8859_1))
            p += 1 + l
        }
        return Pair(sb.toString(), if (next >= 0) next else p)
    }
}
