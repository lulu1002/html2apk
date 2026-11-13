package com.example.meutuber

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

data class Resolved(
    val e: Entry, val hls: String?, val muxed: String?,
    val v: String?, val a: String?
)

private val yt get() = ServiceList.YouTube

fun toEntry(i: StreamInfoItem, music: Boolean = false) =
    Entry(i.name, i.uploaderName ?: "", i.url, i.thumbnails.firstOrNull()?.url, i.uploaderUrl, music)

/** O YouTube removeu a aba "Em alta", então o Início usa suas inscrições (ou uma busca). */
fun homeFeed(): List<Entry> {
    val subs = Store.list("subs")
    if (subs.isNotEmpty()) {
        val feed = subs.take(10).flatMap { s ->
            try { channelVideos(s.url).take(5) } catch (e: Exception) { emptyList() }
        }
        if (feed.isNotEmpty()) return feed.shuffled()
    }
    val last = Store.list("hist").firstOrNull()?.sub?.takeIf { it.isNotBlank() }
    return search(last ?: "vídeos populares", false)
}

fun search(q: String, music: Boolean): List<Entry> {
    val f = if (music) "music_songs" else "videos"
    val h = yt.searchQHFactory.fromQuery(q, listOf(f), "")
    return SearchInfo.getInfo(yt, h).relatedItems.filterIsInstance<StreamInfoItem>().map { toEntry(it, music) }
}

fun channelVideos(url: String): List<Entry> {
    val ci = ChannelInfo.getInfo(yt, url)
    val tab = ci.tabs.firstOrNull { it.contentFilters.contains("videos") } ?: ci.tabs.first()
    return ChannelTabInfo.getInfo(yt, tab).relatedItems.filterIsInstance<StreamInfoItem>().map { toEntry(it) }
}

fun resolve(e: Entry): Resolved {
    val info = StreamInfo.getInfo(yt, e.url)
    val maxH = Store.maxH
    val video = info.videoOnlyStreams.filter { it.format == MediaFormat.MPEG_4 && it.height <= maxH }
        .maxByOrNull { it.height }?.content
    val audio = info.audioStreams.filter { it.format == MediaFormat.M4A }
        .maxByOrNull { it.averageBitrate }?.content
        ?: info.audioStreams.maxByOrNull { it.averageBitrate }?.content
    val muxed = info.videoStreams.maxByOrNull { it.height }?.content
    val hls = info.hlsUrl.takeIf { it.isNotBlank() }
    if (hls == null && muxed == null && audio == null) throw IllegalStateException("Nenhum stream disponível")
    val ent = e.copy(title = info.name, sub = info.uploaderName ?: e.sub,
        chUrl = info.uploaderUrl ?: e.chUrl)
    return Resolved(ent, hls, muxed, video, audio)
}

fun startPlay(c: Context, r: Resolved, audioOnly: Boolean) {
    ContextCompat.startForegroundService(c, Intent(c, PlaybackService::class.java))
    val p = Player.get(c)
    val meta = MediaMetadata.Builder().setTitle(r.e.title).setArtist(r.e.sub)
        .setArtworkUri(r.e.thumb?.let { Uri.parse(it) }).build()
    fun item(u: String) = MediaItem.Builder().setUri(u).setMediaMetadata(meta).build()
    val f = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory())
    when {
        audioOnly && r.a != null -> p.setMediaSource(f.createMediaSource(item(r.a)))
        r.hls != null -> p.setMediaItem(item(r.hls))
        r.v != null && r.a != null -> p.setMediaSource(
            MergingMediaSource(f.createMediaSource(item(r.v)), f.createMediaSource(item(r.a))))
        else -> p.setMediaItem(item(r.muxed ?: r.a ?: return))
    }
    p.prepare(); p.play()
}

fun download(c: Context, r: Resolved, audioOnly: Boolean) {
    val url = (if (audioOnly) r.a else r.muxed ?: r.a) ?: return
    val ext = if (audioOnly || r.muxed == null) "m4a" else "mp4"
    val name = r.e.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_") + "." + ext
    val req = DownloadManager.Request(Uri.parse(url))
        .setTitle(r.e.title)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
    (c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
}

/** SponsorBlock: busca por prefixo do hash (o ID do vídeo não é enviado). */
private val sbClient = okhttp3.OkHttpClient()

fun sponsorSegments(videoUrl: String): List<Pair<Double, Double>> {
    val id = Regex("(?:v=|youtu\\.be/|shorts/)([\\w-]{11})").find(videoUrl)?.groupValues?.get(1)
        ?: return emptyList()
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(4)
    val cats = "%5B%22sponsor%22,%22selfpromo%22,%22interaction%22,%22intro%22,%22outro%22%5D"
    val req = okhttp3.Request.Builder()
        .url("https://sponsor.ajay.app/api/skipSegments/$hash?categories=$cats").build()
    sbClient.newCall(req).execute().use { r ->
        if (!r.isSuccessful) return emptyList()
        val arr = org.json.JSONArray(r.body?.string() ?: return emptyList())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("videoID") != id) continue
            val segs = o.getJSONArray("segments")
            return (0 until segs.length()).map {
                val s = segs.getJSONObject(it).getJSONArray("segment")
                s.getDouble(0) to s.getDouble(1)
            }
        }
    }
    return emptyList()
}
