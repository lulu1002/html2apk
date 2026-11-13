package com.example.meutuber

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Player único, compartilhado entre a tela e o serviço (toca com a tela apagada). */
object Player {
    private var p: ExoPlayer? = null
    fun get(c: Context): ExoPlayer = p ?: ExoPlayer.Builder(c.applicationContext)
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build().also { p = it }
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, Player.get(this)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Player.get(this).playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        session?.release(); session = null
        super.onDestroy()
    }
}
