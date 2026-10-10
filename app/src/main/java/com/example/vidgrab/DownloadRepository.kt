package com.example.vidgrab

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DownloadRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2) // até 2 downloads simultâneos
    private val canceled = ConcurrentHashMap.newKeySet<String>()
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        val raw = prefs?.getString("items", null) ?: return
        try {
            val arr = JSONArray(raw)
            _items.value = (0 until arr.length())
                .map { DownloadItem.fromJson(arr.getJSONObject(it)) }
                .map {
                    if (it.status == Status.RUNNING || it.status == Status.QUEUED)
                        it.copy(status = Status.ERROR, error = "Interrompido") else it
                }
        } catch (_: Exception) {
        }
    }

    private fun persist() {
        val arr = JSONArray()
        _items.value.take(200).forEach { arr.put(it.toJson()) }
        prefs?.edit()?.putString("items", arr.toString())?.apply()
    }

    private fun mutate(id: String, f: (DownloadItem) -> DownloadItem) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    fun enqueue(ctx: Context, p: Preview, q: Quality) {
        val app = ctx.applicationContext
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = p.url,
            title = p.title,
            thumb = p.thumb,
            quality = q.label
        )
        _items.update { listOf(item) + it }
        ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        scope.launch {
            gate.withPermit { run(app, item, q) }
        }
    }

    fun cancel(id: String) {
        canceled.add(id)
        try {
            YoutubeDL.getInstance().destroyProcessById(id)
        } catch (_: Throwable) {
        }
        val it = _items.value.firstOrNull { x -> x.id == id }
        if (it?.status == Status.QUEUED) {
            mutate(id) { x -> x.copy(status = Status.CANCELED) }
            persist()
        }
    }

    fun remove(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun clearFinished() {
        _items.update { list -> list.filter { it.status == Status.RUNNING || it.status == Status.QUEUED } }
        persist()
    }

    private suspend fun run(ctx: Context, item: DownloadItem, q: Quality) {
        if (canceled.remove(item.id)) {
            mutate(item.id) { it.copy(status = Status.CANCELED) }
            persist()
            return
        }
        mutate(item.id) { it.copy(status = Status.RUNNING, progress = 0f) }

        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val tmp = File(base, "tmp/${item.id}").apply { mkdirs() }
        try {
            val ok = App.ready.first { it != null }
            if (ok != true) throw IllegalStateException("Motor de download não iniciou: ${App.initError}")

            val req = YoutubeDLRequest(item.url)
            q.configure(req)
            req.addOption("-o", "${tmp.absolutePath}/%(title).80s.%(ext)s")

            YoutubeDL.getInstance().execute(req, item.id) { progress, eta, _ ->
                if (progress >= 0f) {
                    mutate(item.id) { it.copy(progress = progress / 100f, eta = eta) }
                }
            }

            val file = tmp.listFiles()
                ?.filter {
                    it.isFile && !it.name.endsWith(".part") &&
                        !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp")
                }
                ?.maxByOrNull { it.length() }
                ?: throw IllegalStateException("Arquivo não encontrado após o download")

            val saved = MediaSaver.save(ctx, file)
            mutate(item.id) {
                it.copy(status = Status.DONE, progress = 1f, fileUri = saved.uri, mime = saved.mime)
            }
        } catch (e: Exception) {
            if (canceled.remove(item.id)) {
                mutate(item.id) { it.copy(status = Status.CANCELED) }
            } else {
                val msg = e.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(300) ?: "Erro desconhecido"
                mutate(item.id) { it.copy(status = Status.ERROR, error = msg) }
            }
        } finally {
            tmp.deleteRecursively()
            persist()
        }
    }
}
