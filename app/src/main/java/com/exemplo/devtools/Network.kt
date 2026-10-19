package com.exemplo.devtools

import android.app.AlertDialog
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

val COMMON_PORTS = linkedMapOf(
    21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS", 80 to "HTTP",
    110 to "POP3", 139 to "NetBIOS", 143 to "IMAP", 443 to "HTTPS", 445 to "SMB",
    554 to "RTSP", 631 to "IPP", 993 to "IMAPS", 1883 to "MQTT", 3306 to "MySQL",
    3389 to "RDP", 5432 to "PostgreSQL", 5900 to "VNC", 8000 to "HTTP-alt",
    8080 to "HTTP-proxy", 8443 to "HTTPS-alt", 9100 to "Impressora"
)

class LocalNet(val ip: String, val prefix: Int, val iface: String)
class HostInfo(val ip: String, val host: String, val ms: Long, val ports: List<Int>, var mac: String = "")

private val hostRegex = Regex("^[A-Za-z0-9._:-]+$")
private fun validHost(h: String) = h.isNotEmpty() && !h.startsWith("-") && hostRegex.matches(h)

fun localNet(): LocalNet? {
    val ifaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
    for (ni in ifaces) {
        if (!ni.isUp || ni.isLoopback) continue
        for (ia in ni.interfaceAddresses) {
            val a = ia.address
            if (a is Inet4Address && a.isSiteLocalAddress) {
                return LocalNet(a.hostAddress ?: continue, ia.networkPrefixLength.toInt(), ni.name)
            }
        }
    }
    return null
}

fun tcpOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
} catch (e: Exception) {
    false
}

/** Usa o binário `ping` do Android (não precisa de root). Retorna latência em ms ou null. */
fun pingOnce(host: String, timeoutSec: Int = 1): Long? = try {
    val t0 = System.nanoTime()
    val p = ProcessBuilder("ping", "-c", "1", "-W", timeoutSec.toString(), host)
        .redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    val ok = p.waitFor() == 0
    if (!ok) null else {
        Regex("time=([0-9.]+)").find(out)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()
            ?: ((System.nanoTime() - t0) / 1_000_000)
    }
} catch (e: Exception) {
    null
}

fun probeHost(ip: String): HostInfo? {
    val t0 = System.nanoTime()
    var ms = pingOnce(ip)
    val ports = ArrayList<Int>()
    if (ms == null) {
        for (p in intArrayOf(80, 443, 22, 445, 139, 8080, 53)) {
            if (tcpOpen(ip, p, 250)) {
                ports.add(p)
                ms = (System.nanoTime() - t0) / 1_000_000
                break
            }
        }
    }
    if (ms == null) return null
    val name = try { InetAddress.getByName(ip).hostName } catch (e: Exception) { ip }
    return HostInfo(ip, if (name == ip) "" else name, ms, ports)
}

fun scanPorts(host: String): List<Int> {
    val pool = Executors.newFixedThreadPool(24)
    try {
        val futures = COMMON_PORTS.keys.map { p ->
            pool.submit(Callable { if (tcpOpen(host, p, 700)) p else -1 })
        }
        return futures.map { it.get() }.filter { it > 0 }
    } finally {
        pool.shutdown()
    }
}

fun readArp(): Map<String, String> = try {
    File("/proc/net/arp").readLines().drop(1).mapNotNull {
        val p = it.trim().split(Regex("\\s+"))
        if (p.size >= 4 && p[3] != "00:00:00:00:00:00") p[0] to p[3] else null
    }.toMap()
} catch (e: Exception) {
    emptyMap()
}

// ---------------------------------------------------------------- telas

fun MainActivity.networkHome(): View {
    val box = vbox()
    box.add(tv("Use apenas em redes que você administra ou tem autorização para testar.", 12f, YELLOW), 0)
    box.add(btn("📡  Scanner de rede local") { push("Scanner de rede", scannerView()) }, 10)
    box.add(btn("📶  Ping") { push("Ping", pingView()) })
    box.add(btn("🌐  DNS (A, AAAA, MX, TXT…)") { push("DNS", dnsView()) })
    box.add(btn("🔌  Teste de portas") { push("Teste de portas", portsView()) })
    box.add(btn("ℹ️  Meu IP e interfaces") { push("Meu IP", ipInfoView()) })
    return scroll(box)
}

private fun MainActivity.showPortScan(ip: String) {
    toast("Escaneando portas comuns de $ip…")
    async({ scanPorts(ip) }) { res ->
        val msg = res.fold(
            onSuccess = { open ->
                if (open.isEmpty()) "Nenhuma das ${COMMON_PORTS.size} portas comuns está aberta."
                else open.joinToString("\n") { "$it  ${COMMON_PORTS[it] ?: ""}" }
            },
            onFailure = { "Erro: ${it.message}" }
        )
        AlertDialog.Builder(this).setTitle("Portas abertas • $ip").setMessage(msg)
            .setPositiveButton("OK", null).show()
    }
}

fun MainActivity.scannerView(): View {
    val box = vbox()
    val net = localNet()
    box.add(
        tv(
            if (net == null) "Nenhuma rede local detectada. Conecte-se a um Wi-Fi."
            else "Seu IP: ${net.ip}/${net.prefix}  (${net.iface})", 14f
        ), 0
    )
    val progress = box.add(tv("", 13f, GRAY))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    val cancel = AtomicBoolean(false)
    val done = AtomicInteger(0)
    val found = ArrayList<HostInfo>()
    var executor: ExecutorService? = null
    var running = false
    lateinit var startBtn: android.widget.Button

    fun rowFor(h: HostInfo): View {
        val mine = h.ip == net?.ip
        val row = vbox(10).apply {
            setBackgroundColor(CARD)
            setOnClickListener { showPortScan(h.ip) }
        }
        row.add(tv(h.ip + if (mine) "  (este aparelho)" else "", 15f, if (mine) GREEN else Color.WHITE), 0)
        val extra = listOf(
            h.host,
            "${h.ms} ms",
            if (h.ports.isNotEmpty()) "portas: ${h.ports.joinToString(",")}" else "",
            h.mac
        ).filter { it.isNotBlank() }.joinToString(" • ")
        row.add(tv(extra, 12f, GRAY), 2)
        return row
    }

    fun finishScan() {
        running = false
        executor?.shutdownNow()
        startBtn.text = "Iniciar varredura"
        val arp = readArp()
        found.sortBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
        found.forEach { it.mac = arp[it.ip] ?: "" }
        list.removeAllViews()
        found.forEach { list.add(rowFor(it), 4) }
        progress.text = "Concluído • ${found.size} dispositivos • toque em um para ver portas abertas"
    }

    fun start() {
        val n = localNet()
        if (n == null) { toast("Sem rede local"); return }
        val base = n.ip.substringBeforeLast('.')
        found.clear()
        list.removeAllViews()
        cancel.set(false)
        done.set(0)
        running = true
        startBtn.text = "Parar"
        val ex = Executors.newFixedThreadPool(48)
        executor = ex
        for (i in 1..254) {
            val ip = "$base.$i"
            ex.execute {
                if (cancel.get()) return@execute
                val res = probeHost(ip)
                val d = done.incrementAndGet()
                runOnUiThread {
                    if (cancel.get() || !running) return@runOnUiThread
                    progress.text = "Varrendo… $d/254 • ${found.size + if (res != null) 1 else 0} dispositivos"
                    if (res != null) { found.add(res); list.add(rowFor(res), 4) }
                    if (d >= 254) finishScan()
                }
            }
        }
    }

    startBtn = box.add(btn("Iniciar varredura") {
        if (running) { cancel.set(true); finishScan() } else start()
    }, 8)
    box.add(
        tv("Detecta dispositivos que respondem a ping ou têm portas comuns abertas. O MAC só aparece se o Android permitir ler a tabela ARP.", 11f, GRAY), 4
    )
    box.add(list, 8)
    return scroll(box)
}

fun MainActivity.pingView(): View {
    val box = vbox()
    val host = box.add(ed("Host ou IP (ex.: google.com)").apply { setText("google.com") }, 0)
    val out = outputView()
    box.add(btn("Executar ping (4 pacotes)") {
        val h = host.text.toString().trim()
        if (!validHost(h)) { toast("Host inválido"); return@btn }
        out.text = ""
        Thread {
            try {
                val p = ProcessBuilder("ping", "-c", "4", "-W", "2", h).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().forEachLine { l -> runOnUiThread { out.append(l + "\n") } }
                p.waitFor()
            } catch (e: Exception) {
                runOnUiThread { out.append("Erro: ${e.message}") }
            }
        }.start()
    })
    box.add(out, 8)
    return scroll(box)
}

fun MainActivity.dnsView(): View {
    val box = vbox()
    val host = box.add(ed("Domínio (ex.: github.com)").apply { setText("github.com") }, 0)
    val type = box.add(spinner(listOf("A", "AAAA", "MX", "TXT", "NS", "CNAME", "SOA")))
    val out = outputView()

    box.add(btn("Consultar (DNS over HTTPS • Cloudflare)") {
        val h = host.text.toString().trim()
        if (!validHost(h)) { toast("Domínio inválido"); return@btn }
        val t = type.selectedItem.toString()
        out.text = "Consultando…"
        async({
            Http.request(
                "GET", "https://cloudflare-dns.com/dns-query?name=$h&type=$t",
                mapOf("Accept" to "application/dns-json")
            )
        }) { res ->
            res.fold(onSuccess = { r ->
                val ans = try { JSONObject(r.body).optJSONArray("Answer") } catch (e: Exception) { null }
                out.text = if (ans == null || ans.length() == 0) "Sem resposta para $t em $h"
                else (0 until ans.length()).joinToString("\n") {
                    val a = ans.getJSONObject(it)
                    "${a.optString("name")}  TTL ${a.optInt("TTL")}\n  ${a.optString("data")}"
                }
            }, onFailure = { out.text = "Erro: ${it.message}" })
        }
    })
    box.add(btn("Resolver pelo sistema (IPs)") {
        val h = host.text.toString().trim()
        if (!validHost(h)) { toast("Domínio inválido"); return@btn }
        out.text = "Resolvendo…"
        async({ InetAddress.getAllByName(h).joinToString("\n") { it.hostAddress ?: "" } }) { res ->
            res.fold(onSuccess = { out.text = it }, onFailure = { out.text = "Erro: ${it.message}" })
        }
    })
    box.add(out, 8)
    return scroll(box)
}

fun MainActivity.portsView(): View {
    val box = vbox()
    val host = box.add(ed("Host ou IP"), 0)
    val port = box.add(ed("Porta (vazio = portas comuns)").apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
    })
    val out = outputView()
    box.add(btn("Testar") {
        val h = host.text.toString().trim()
        if (!validHost(h)) { toast("Host inválido"); return@btn }
        val p = port.text.toString().trim().toIntOrNull()
        out.text = "Testando…"
        if (p != null) {
            async({
                val t0 = System.currentTimeMillis()
                val open = tcpOpen(h, p, 3000)
                open to (System.currentTimeMillis() - t0)
            }) { res ->
                res.fold(onSuccess = { (open, ms) ->
                    out.text = if (open) "✅ $h:$p ABERTA (${ms} ms)" else "❌ $h:$p fechada ou filtrada"
                }, onFailure = { out.text = "Erro: ${it.message}" })
            }
        } else {
            async({ scanPorts(h) }) { res ->
                res.fold(onSuccess = { open ->
                    out.text = if (open.isEmpty()) "Nenhuma porta comum aberta em $h"
                    else open.joinToString("\n") { "✅ $it  ${COMMON_PORTS[it] ?: ""}" }
                }, onFailure = { out.text = "Erro: ${it.message}" })
            }
        }
    })
    box.add(out, 8)
    return scroll(box)
}

fun MainActivity.ipInfoView(): View {
    val box = vbox()
    val out = outputView().apply { text = "Carregando…" }
    box.add(out, 0)
    async({
        val sb = StringBuilder()
        val pub = try { Http.request("GET", "https://api.ipify.org").body.trim() } catch (e: Exception) { "indisponível" }
        sb.appendLine("IP público: $pub").appendLine()
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (ni in ifaces) {
            if (!ni.isUp) continue
            sb.appendLine("${ni.name}${if (ni.isLoopback) " (loopback)" else ""}")
            for (ia in ni.interfaceAddresses) {
                sb.appendLine("   ${ia.address.hostAddress?.substringBefore('%')}/${ia.networkPrefixLength}")
            }
        }
        sb.toString()
    }) { res ->
        res.fold(onSuccess = { out.text = it }, onFailure = { out.text = "Erro: ${it.message}" })
    }
    return scroll(box)
}
