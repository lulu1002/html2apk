package com.adblockdns

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Estado compartilhado entre o serviço VPN e a interface. */
object DnsStats {
    data class Entry(val domain: String, val blocked: Boolean)

    val running = MutableStateFlow(false)
    val totalCount = MutableStateFlow(0)
    val blockedCount = MutableStateFlow(0)
    val recent = MutableStateFlow<List<Entry>>(emptyList())

    fun record(domain: String, blocked: Boolean) {
        totalCount.update { it + 1 }
        if (blocked) blockedCount.update { it + 1 }
        recent.update { cur ->
            if (cur.firstOrNull()?.domain == domain) cur
            else (listOf(Entry(domain, blocked)) + cur).take(100)
        }
    }
}
