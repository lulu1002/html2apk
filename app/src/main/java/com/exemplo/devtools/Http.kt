package com.exemplo.devtools

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object Http {

    class Resp(
        val code: Int,
        val headers: Map<String, List<String>>,
        val body: String,
        val ms: Long,
        val bytes: Int
    )

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 15_000,
        followRedirects: Boolean = true
    ): Resp {
        val t0 = System.currentTimeMillis()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = followRedirects

            var m = method.uppercase()
            if (m == "PATCH") { // HttpURLConnection não suporta PATCH: usa override
                m = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
            conn.requestMethod = m
            for ((k, v) in headers) conn.setRequestProperty(k, v)

            if (body != null && (m == "POST" || m == "PUT")) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }

            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { readLimited(it) } ?: ByteArray(0)

            val hf = HashMap<String, List<String>>()
            for ((k, v) in conn.headerFields) if (k != null) hf[k] = v

            return Resp(code, hf, String(bytes, Charsets.UTF_8), System.currentTimeMillis() - t0, bytes.size)
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(s: InputStream, max: Int = 2_000_000): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() >= max) break
        }
        return out.toByteArray()
    }
}
