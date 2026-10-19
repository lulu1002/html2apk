package com.exemplo.devtools

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object Http {

    class Resp(
        val code: Int,
        val headers: Map<String, List<String>>,
        val body: String,
        val ms: Long,
        val bytes: Int
    )

    private val base = OkHttpClient()

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Int = 15_000,
        followRedirects: Boolean = true
    ): Resp {
        val m = method.uppercase()
        val client = base.newBuilder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(60_000, TimeUnit.MILLISECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .build()

        val contentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: "application/json; charset=utf-8"

        val reqBody: RequestBody? = when (m) {
            "POST", "PUT", "PATCH" -> (body ?: "").toRequestBody(contentType.toMediaTypeOrNull())
            "DELETE" -> body?.toRequestBody(contentType.toMediaTypeOrNull())
            else -> null
        }

        val req = Request.Builder().url(url).method(m, reqBody).apply {
            for ((k, v) in headers) if (!k.equals("Content-Type", ignoreCase = true)) header(k, v)
        }.build()

        val t0 = System.currentTimeMillis()
        return client.newCall(req).execute().use { resp ->
            val bytes = resp.peekBody(2_000_000).bytes()
            Resp(
                resp.code,
                resp.headers.toMultimap(),
                String(bytes, Charsets.UTF_8),
                System.currentTimeMillis() - t0,
                bytes.size
            )
        }
    }
}
