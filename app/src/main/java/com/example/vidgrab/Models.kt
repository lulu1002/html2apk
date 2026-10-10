package com.example.vidgrab

import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject

enum class Status { QUEUED, RUNNING, DONE, ERROR, CANCELED }

data class Preview(
    val url: String,
    val title: String,
    val thumb: String?,
    val duration: Long,
    val uploader: String?
)

enum class Quality(val label: String) {
    BEST("Vídeo – melhor qualidade"),
    P1080("Vídeo – até 1080p"),
    P720("Vídeo – até 720p"),
    P480("Vídeo – até 480p"),
    P360("Vídeo – até 360p"),
    MP3("Áudio – MP3"),
    M4A("Áudio – M4A");

    fun configure(req: YoutubeDLRequest) {
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        when (this) {
            MP3 -> {
                req.addOption("-f", "bestaudio/best")
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "0")
            }
            M4A -> {
                req.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                req.addOption("-x")
                req.addOption("--audio-format", "m4a")
            }
            else -> {
                val h = when (this) {
                    P1080 -> 1080
                    P720 -> 720
                    P480 -> 480
                    P360 -> 360
                    else -> null
                }
                val f = if (h == null) {
                    "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b"
                } else {
                    "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/b[height<=$h][ext=mp4]/" +
                        "bv*[height<=$h]+ba/b[height<=$h]/b"
                }
                req.addOption("-f", f)
                req.addOption("--merge-output-format", "mp4")
            }
        }
    }
}

data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val thumb: String?,
    val quality: String,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val eta: Long = 0,
    val fileUri: String? = null,
    val mime: String? = null,
    val error: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("title", title)
        put("thumb", thumb ?: JSONObject.NULL)
        put("quality", quality)
        put("status", status.name)
        put("fileUri", fileUri ?: JSONObject.NULL)
        put("mime", mime ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
    }

    companion object {
        private fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)

        fun fromJson(o: JSONObject) = DownloadItem(
            id = o.getString("id"),
            url = o.getString("url"),
            title = o.getString("title"),
            thumb = o.str("thumb"),
            quality = o.getString("quality"),
            status = Status.valueOf(o.getString("status")),
            fileUri = o.str("fileUri"),
            mime = o.str("mime"),
            error = o.str("error")
        )
    }
}
