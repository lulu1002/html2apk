package com.example.wgtunnel

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast

/** Widget da tela inicial: um botão que liga/desliga o último túnel usado. */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, build(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        val pending = goAsync()
        val result = TunnelToggler.toggle(context) {
            updateAll(context)
            pending.finish()
        }
        if (result != TunnelToggler.Result.OK) {
            val msg = if (result == TunnelToggler.Result.NO_TUNNEL) R.string.no_tunnel
            else R.string.open_app_first
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
            pending.finish()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.wgtunnel.TOGGLE"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, build(context))
        }

        private fun build(context: Context): RemoteViews {
            val active = TunnelToggler.activeName(context)
            val name = active ?: TunnelToggler.lastName(context)
            val views = RemoteViews(context.packageName, R.layout.widget_toggle)
            views.setTextViewText(R.id.w_name, name ?: context.getString(R.string.app_name))
            views.setTextViewText(
                R.id.w_status,
                context.getString(if (active != null) R.string.connected else R.string.disconnected)
            )
            views.setInt(
                R.id.w_root, "setBackgroundResource",
                if (active != null) R.drawable.bg_widget_on else R.drawable.bg_widget_off
            )
            val intent = Intent(context, WidgetProvider::class.java).setAction(ACTION_TOGGLE)
            val pi = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.w_root, pi)
            return views
        }
    }
}
