package com.example.vidgrab

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    var url by mutableStateOf("")
    var loading by mutableStateOf(false)
    var preview by mutableStateOf<Preview?>(null)
    var error by mutableStateOf<String?>(null)
    var updating by mutableStateOf(false)
    var engineVersion by mutableStateOf("")

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val downloads = DownloadRepository.items

    init {
        refreshVersion()
    }

    fun onShared(text: String) {
        url = extractUrl(text) ?: text
        analyze()
    }

    fun analyze() {
        val u = extractUrl(url)
        if (u == null) {
            error = "Cole um link válido (começando com http)"
            return
        }
        url = u
        loading = true
        error = null
        preview = null
        viewModelScope.launch {
            try {
                preview = withContext(Dispatchers.IO) { fetch(u) }
            } catch (e: Exception) {
                error = e.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(250)
                    ?: "Não foi possível analisar o link"
            } finally {
                loading = false
            }
        }
    }

    private suspend fun fetch(u: String): Preview {
        val ok = App.ready.first { it != null }
        if (ok != true) throw IllegalStateException("Motor de download não iniciou: ${App.initError}")
        val req = YoutubeDLRequest(u).apply { addOption("--no-playlist") }
        val info = YoutubeDL.getInstance().getInfo(req)
        return Preview(
            url = u,
            title = info.title ?: u,
            thumb = info.thumbnail,
            duration = (info.duration as Number?)?.toLong() ?: 0L,
            uploader = info.uploader
        )
    }

    fun download(q: Quality) {
        val p = preview ?: return
        DownloadRepository.enqueue(getApplication(), p, q)
        messages.tryEmit("Download adicionado à fila")
    }

    fun updateEngine() {
        if (updating) return
        updating = true
        viewModelScope.launch {
            try {
                val st = withContext(Dispatchers.IO) {
                    App.ready.first { it != null }
                    YoutubeDL.getInstance().updateYoutubeDL(getApplication())
                }
                messages.tryEmit("yt-dlp atualizado: $st")
            } catch (e: Exception) {
                messages.tryEmit("Falha ao atualizar: ${e.message?.take(120)}")
            } finally {
                updating = false
                refreshVersion()
            }
        }
    }

    private fun refreshVersion() {
        viewModelScope.launch {
            engineVersion = try {
                withContext(Dispatchers.IO) {
                    App.ready.first { it != null }
                    YoutubeDL.getInstance().version(getApplication()) ?: "?"
                }
            } catch (_: Exception) {
                "?"
            }
        }
    }

    private fun extractUrl(text: String): String? =
        Regex("https?://\\S+").find(text)?.value
}
