package com.luyan.wifiscout

import java.net.HttpURLConnection
import java.net.URL
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Speedtest simples usando os endpoints públicos da Cloudflare.
 * Mede latência, download e upload. Os callbacks são chamados na thread do teste,
 * quem usa deve repassar para a thread principal.
 */
class SpeedTester(private val cb: Callback) {

    interface Callback {
        fun onStage(stage: String)
        fun onPing(ms: Double)
        fun onDownload(mbps: Double, isFinal: Boolean)
        fun onUpload(mbps: Double, isFinal: Boolean)
        /** Sempre chamado no fim. message = "Concluído", "Cancelado" ou o motivo do erro. */
        fun onFinished(message: String)
    }

    @Volatile
    private var cancelled = false

    @Volatile
    private var lastError: String? = null

    fun cancel() {
        cancelled = true
    }

    fun start() {
        Thread { runTest() }.start()
    }

    private fun runTest() {
        var message = "Concluído"
        try {
            cb.onStage("Medindo latência…")
            val ping = measurePing()
            if (ping < 0) throw IllegalStateException("Sem conexão com o servidor de teste")
            cb.onPing(ping)

            if (!cancelled) {
                cb.onStage("Testando download…")
                val down = transfer(download = true) { cb.onDownload(it, false) }
                cb.onDownload(down, true)
            }

            if (!cancelled) {
                cb.onStage("Testando upload…")
                val up = transfer(download = false) { cb.onUpload(it, false) }
                cb.onUpload(up, true)
            }

            if (cancelled) message = "Cancelado"
        } catch (e: Exception) {
            message = if (cancelled) "Cancelado" else "Erro: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            cb.onFinished(message)
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.useCaches = false
        c.setRequestProperty("Cache-Control", "no-cache")
        return c
    }

    /** Média de 5 requisições mínimas (a primeira, com handshake TLS, é descartada). */
    private fun measurePing(): Double {
        val times = ArrayList<Double>()
        for (i in 0 until 6) {
            if (cancelled) break
            try {
                val c = open("$DOWN_URL?bytes=0&r=${System.nanoTime()}")
                val t0 = System.nanoTime()
                c.responseCode
                c.inputStream.use { it.readBytes() }
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                if (i > 0) times.add(ms)
            } catch (e: Exception) {
                lastError = e.message
            }
        }
        return if (times.isEmpty()) -1.0 else times.average()
    }

    /** Transferência paralela por [DURATION_MS]; reporta Mbps a cada 250 ms e devolve a média final. */
    private fun transfer(download: Boolean, onProgress: (Double) -> Unit): Double {
        val total = AtomicLong(0)
        val startNs = System.nanoTime()
        val endNs = startNs + DURATION_MS * 1_000_000L
        val pool = Executors.newFixedThreadPool(THREADS)
        lastError = null

        repeat(THREADS) {
            pool.execute {
                if (download) downloadWorker(total, endNs) else uploadWorker(total, endNs)
            }
        }

        while (!cancelled && System.nanoTime() < endNs) {
            Thread.sleep(250)
            val secs = (System.nanoTime() - startNs) / 1e9
            onProgress(total.get() * 8 / secs / 1e6)
        }
        pool.shutdownNow()

        val secs = (System.nanoTime() - startNs) / 1e9
        if (total.get() == 0L && !cancelled) {
            throw IllegalStateException(lastError ?: "Sem resposta do servidor")
        }
        return total.get() * 8 / secs / 1e6
    }

    private fun downloadWorker(total: AtomicLong, endNs: Long) {
        val buf = ByteArray(64 * 1024)
        while (!cancelled && System.nanoTime() < endNs) {
            try {
                val c = open("$DOWN_URL?bytes=25000000&r=${System.nanoTime()}")
                c.inputStream.use { input ->
                    while (!cancelled && System.nanoTime() < endNs) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total.addAndGet(n.toLong())
                    }
                }
                c.disconnect()
            } catch (e: Exception) {
                if (System.nanoTime() < endNs) lastError = e.message
                return
            }
        }
    }

    private fun uploadWorker(total: AtomicLong, endNs: Long) {
        val chunk = ByteArray(64 * 1024).also { Random().nextBytes(it) }
        while (!cancelled && System.nanoTime() < endNs) {
            try {
                val c = open(UP_URL)
                c.requestMethod = "POST"
                c.doOutput = true
                c.setFixedLengthStreamingMode(UP_BODY_SIZE)
                c.outputStream.use { out ->
                    var sent = 0
                    while (sent < UP_BODY_SIZE && !cancelled && System.nanoTime() < endNs) {
                        out.write(chunk)
                        sent += chunk.size
                        total.addAndGet(chunk.size.toLong())
                    }
                }
                c.responseCode
                c.disconnect()
            } catch (e: Exception) {
                if (System.nanoTime() < endNs) lastError = e.message
                return
            }
        }
    }

    companion object {
        private const val DOWN_URL = "https://speed.cloudflare.com/__down"
        private const val UP_URL = "https://speed.cloudflare.com/__up"
        private const val UP_BODY_SIZE = 64 * 1024 * 32 // 2 MB por requisição
        private const val DURATION_MS = 8000L
        private const val THREADS = 4
    }
}
