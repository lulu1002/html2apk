package com.example.vidgrab

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        DownloadRepository.init(this)
        val app = this
        Thread {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                ready.value = true
            } catch (t: Throwable) {
                initError = t.message
                ready.value = false
                return@Thread
            }
            // Atualiza o yt-dlp em segundo plano no máximo 1x por dia
            try {
                val prefs = getSharedPreferences("engine", MODE_PRIVATE)
                val last = prefs.getLong("last_update", 0L)
                if (System.currentTimeMillis() - last > 24L * 3600 * 1000) {
                    YoutubeDL.getInstance().updateYoutubeDL(app)
                    prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    companion object {
        /** null = iniciando, true = pronto, false = falhou */
        val ready = MutableStateFlow<Boolean?>(null)
        var initError: String? = null
    }
}
