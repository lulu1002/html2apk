package com.example.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Fica conectado ao servidor de controle remoto (long-poll) e executa os comandos. */
class RemoteService : Service() {

    companion object {
        const val ACTION_UPDATE = "com.example.launcher.REMOTE_UPDATE"
        private const val CH_STATUS = "remote_status"
        private const val CH_MSG = "remote_msg"

        fun start(ctx: Context) {
            val i = Intent(ctx, RemoteService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RemoteService::class.java))
        }
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private lateinit var prefs: Prefs
    private val done = HashSet<Int>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, "Controle remoto", NotificationManager.IMPORTANCE_MIN)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_MSG, "Mensagens do dono", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notification.Builder(this, CH_STATUS)
            .setContentTitle("Controle remoto ativo")
            .setContentText("Conectado ao seu servidor")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (!prefs.remoteOn || prefs.remoteUrl.isBlank() || prefs.remoteToken.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            worker = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    private fun baseUrl(): String {
        var u = prefs.remoteUrl.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u
    }

    private fun battery(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun adminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, LockAdmin::class.java))
    }

    private fun loop() {
        var wait = 5000L
        while (running) {
            try {
                val q = "bat=${battery()}&ld=${if (prefs.isLockdown()) 1 else 0}&adm=${if (adminActive()) 1 else 0}"
                val c = URL("${baseUrl()}/api/device/poll?$q").openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 40000
                c.setRequestProperty("x-device-token", prefs.remoteToken)
                if (c.responseCode != 200) {
                    c.disconnect()
                    throw IllegalStateException("http ${c.responseCode}")
                }
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val arr = JSONObject(body).getJSONArray("commands")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getInt("id")
                    val result = if (id in done) "ok" else run(o.getString("type"), o.optString("text", ""))
                    done.add(id)
                    ack(id, result)
                }
                wait = 1000L
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                // Sem internet ou servidor fora do ar: tenta de novo com espera crescente.
                wait = (wait * 2).coerceAtMost(60000L)
            }
            try {
                Thread.sleep(wait)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun ack(id: Int, result: String) {
        try {
            val c = URL("${baseUrl()}/api/device/ack").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.setRequestProperty("x-device-token", prefs.remoteToken)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use {
                it.write(JSONObject().put("id", id).put("result", result).toString().toByteArray())
            }
            c.responseCode
            c.disconnect()
        } catch (_: Exception) {
        }
    }

    private fun run(type: String, text: String): String {
        return when (type) {
            "lock" -> if (lockScreen()) "ok" else "sem permissão de bloquear a tela"
            "message" -> {
                prefs.pendingMessage = text.ifBlank { "Mensagem do dono do aparelho." }
                notifyMessage(prefs.pendingMessage)
                tell()
                "ok"
            }
            "lockdown" -> {
                prefs.setLockdown(text.ifBlank { "Este aparelho foi travado pelo dono." })
                val locked = lockScreen()
                tell()
                if (locked) "ok" else "launcher travado (tela sem permissão)"
            }
            "unlock" -> {
                prefs.clearLockdown()
                tell()
                "ok"
            }
            else -> "desconhecido"
        }
    }

    private fun lockScreen(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isAdminActive(ComponentName(this, LockAdmin::class.java))) return false
        dpm.lockNow()
        return true
    }

    private fun notifyMessage(text: String) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH_MSG)
            .setContentTitle("Mensagem do dono")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2, n)
    }

    /** Avisa o launcher (se aberto) e, com a permissão "sobre outros apps", o traz para a frente. */
    private fun tell() {
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName))
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } catch (_: Exception) {
            }
        }
    }
}
