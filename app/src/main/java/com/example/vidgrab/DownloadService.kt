package com.example.vidgrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Mantém o app vivo (com notificação) enquanto há downloads em andamento. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, build("Preparando download…", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (job == null) {
            job = scope.launch {
                DownloadRepository.items.collect { list ->
                    val active = list.filter { it.status == Status.QUEUED || it.status == Status.RUNNING }
                    if (active.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val avg = (active.map { it.progress }.average() * 100).toInt()
                        val text = "${active.size} download(s) • $avg%"
                        try {
                            NotificationManagerCompat.from(this@DownloadService)
                                .notify(NOTIF_ID, build(text, avg, avg == 0))
                        } catch (_: SecurityException) {
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(text: String, progress: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("VidGrab")
            .setContentText(text)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1001
    }
}
