package com.example.netmonitor

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ConnInfo(
    val proto: String,
    val uid: Int,
    val app: String,
    val dstIp: String,
    val dstPort: Int
) {
    val start = System.currentTimeMillis()

    @Volatile
    var last = start

    @Volatile
    var active = true
    val up = AtomicLong()
    val down = AtomicLong()
}

/** Guarda as conexoes observadas para a tela exibir. */
object Tracker {
    private val conns = ConcurrentHashMap<Long, ConnInfo>()
    private val counter = AtomicLong()
    val dns = ConcurrentHashMap<String, String>()
    val totalUp = AtomicLong()
    val totalDown = AtomicLong()

    fun add(c: ConnInfo) {
        conns[counter.incrementAndGet()] = c
        if (conns.size > 600) {
            // remove as encerradas mais antigas
            conns.entries
                .filter { !it.value.active }
                .sortedBy { it.value.last }
                .take(conns.size - 500)
                .forEach { conns.remove(it.key) }
        }
    }

    fun snapshot(): List<ConnInfo> =
        conns.values.sortedByDescending { it.last }.take(300)

    fun activeCount(): Int = conns.values.count { it.active }

    fun reset() {
        conns.clear()
        totalUp.set(0)
        totalDown.set(0)
    }

    fun hostOf(c: ConnInfo): String = dns[c.dstIp] ?: c.dstIp
}
