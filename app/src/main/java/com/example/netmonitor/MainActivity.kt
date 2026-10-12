package com.example.netmonitor

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var toggle: Button
    private lateinit var summary: TextView
    private val adapter = ConnAdapter()

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(8))
        }

        val title = TextView(this).apply {
            text = "Monitor de Rede"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        summary = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(summary)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggle = Button(this).apply {
            setOnClickListener { onToggle() }
        }
        val clear = Button(this).apply {
            text = "Limpar lista"
            setOnClickListener {
                Tracker.reset()
                refresh()
            }
        }
        buttons.addView(toggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(clear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            emptyView = null
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun onToggle() {
        if (MonitorVpnService.running) {
            startService(Intent(this, MonitorVpnService::class.java).setAction(MonitorVpnService.ACTION_STOP))
            handler.postDelayed({ refresh() }, 300)
        } else {
            val prepare = VpnService.prepare(this)
            if (prepare != null) {
                startActivityForResult(prepare, 1)
            } else {
                startVpn()
            }
        }
    }

    private fun startVpn() {
        startService(Intent(this, MonitorVpnService::class.java))
        handler.postDelayed({ refresh() }, 300)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) startVpn()
    }

    private fun refresh() {
        val on = MonitorVpnService.running
        toggle.text = if (on) "Parar monitor" else "Iniciar monitor"
        summary.text = (if (on) "Monitorando" else "Parado") +
            " · conexões ativas: ${Tracker.activeCount()}" +
            "\nTotal ↑ ${fmt(Tracker.totalUp.get())}   ↓ ${fmt(Tracker.totalDown.get())}"
        adapter.items = Tracker.snapshot()
        adapter.notifyDataSetChanged()
    }

    private fun fmt(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format("%.1f MB", b / (1024.0 * 1024))
        else -> String.format("%.2f GB", b / (1024.0 * 1024 * 1024))
    }

    private fun ago(ms: Long): String {
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 2 -> "agora"
            s < 60 -> "há ${s}s"
            s < 3600 -> "há ${s / 60} min"
            else -> "há ${s / 3600} h"
        }
    }

    private class Row(val title: TextView, val detail: TextView)

    private inner class ConnAdapter : BaseAdapter() {
        var items: List<ConnInfo> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View
            val row: Row
            if (convertView == null) {
                val box = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                val t = TextView(this@MainActivity).apply { textSize = 15f }
                val d = TextView(this@MainActivity).apply {
                    textSize = 12f
                    setTextColor(Color.DKGRAY)
                }
                box.addView(t)
                box.addView(d)
                row = Row(t, d)
                box.tag = row
                view = box
            } else {
                view = convertView
                row = convertView.tag as Row
            }

            val c = items[position]
            row.title.text = (if (c.active) "● " else "○ ") +
                c.app + "  →  " + Tracker.hostOf(c) + ":" + c.dstPort
            row.detail.text = c.proto + " · ↑ " + fmt(c.up.get()) + "  ↓ " + fmt(c.down.get()) +
                " · " + (if (c.active) "ativa" else "encerrada") + " · " + ago(c.last)
            view.alpha = if (c.active) 1f else 0.55f
            return view
        }
    }
}
