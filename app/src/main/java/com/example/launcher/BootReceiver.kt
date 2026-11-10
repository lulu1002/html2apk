package com.example.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Religa o controle remoto depois de reiniciar o celular ou atualizar o app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Prefs(c).remoteOn) RemoteService.start(c)
    }
}
