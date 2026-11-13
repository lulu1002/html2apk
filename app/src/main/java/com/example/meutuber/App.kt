package com.example.meutuber

import android.app.Application
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        NewPipe.init(HttpDownloader())
    }
}

class HttpDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        // Bloqueio de anúncios/rastreadores: nem chega a abrir a conexão.
        val u = request.url()
        val host = android.net.Uri.parse(u).host ?: ""
        if (BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") } ||
            BLOCKED_PATHS.any { u.contains(it) })
            return Response(204, "Blocked", emptyMap(), "", u)
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody()
            method == "POST" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val builder = okhttp3.Request.Builder()
            .method(method, body)
            .url(request.url())
            .header("User-Agent", UA)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCAPTCHA", request.url())
            return Response(
                r.code, r.message, r.headers.toMultimap(),
                r.body?.string(), r.request.url.toString()
            )
        }
    }

    companion object {
        val BLOCKED_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "adservice.google.com",
            "2mdn.net", "moatads.com", "scorecardresearch.com"
        )
        val BLOCKED_PATHS = listOf("/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_info")
        const val UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
