package com.luyan.wifiscout

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0F172A")
    private val card = Color.parseColor("#1E293B")
    private val accent = Color.parseColor("#38BDF8")
    private val muted = Color.parseColor("#94A3B8")

    private lateinit var wifi: WifiManager
    private val handler = Handler(Looper.getMainLooper())

    // Aba Sinal
    private lateinit var dbmTv: TextView
    private lateinit var qualityTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var meter: ProgressBar
    private lateinit var graph: SignalGraphView

    // Aba Melhor local
    private lateinit var measureBtn: Button
    private lateinit var measureStatus: TextView
    private lateinit var resultsBox: LinearLayout

    // Aba Speedtest
    private lateinit var speedBtn: Button
    private lateinit var stageTv: TextView
    private lateinit var pingTv: TextView
    private lateinit var downTv: TextView
    private lateinit var upTv: TextView

    private val panels = ArrayList<View>()
    private val tabs = ArrayList<Button>()

    private var measuring = false
    private var measureStart = 0L
    private val samples = ArrayList<Int>()
    private val points = ArrayList<Pair<String, Int>>()
    private var tester: SpeedTester? = null

    private val poll = object : Runnable {
        override fun run() {
            updateSignal()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = bg
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        loadPoints()
        setContentView(buildUi())
        renderResults()
        requestLocationIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    override fun onDestroy() {
        tester?.cancel()
        super.onDestroy()
    }

    private fun requestLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.parseColor("#0F172A"))
            setBackgroundColor(accent)
            setOnClickListener { onClick() }
        }

    private fun scrollPanel(content: LinearLayout): ScrollView =
        ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun column(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        root.addView(tv("WiFi Scout", 22f, bold = true))

        panels.add(buildSignalPanel())
        panels.add(buildMapPanel())
        panels.add(buildSpeedPanel())

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        listOf("Sinal", "Melhor local", "Speedtest").forEachIndexed { i, name ->
            val b = Button(this).apply {
                text = name
                isAllCaps = false
                setOnClickListener { showTab(i) }
            }
            tabs.add(b)
            tabRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabRow)

        val content = FrameLayout(this)
        panels.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        showTab(0)
        return root
    }

    private fun showTab(index: Int) {
        panels.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, b ->
            b.setBackgroundColor(if (i == index) accent else card)
            b.setTextColor(if (i == index) Color.parseColor("#0F172A") else Color.WHITE)
        }
    }

    private fun buildSignalPanel(): View {
        val col = column()

        dbmTv = tv("--", 72f, bold = true).apply { gravity = Gravity.CENTER }
        col.addView(dbmTv, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(tv("dBm", 16f, muted).apply { gravity = Gravity.CENTER })

        qualityTv = tv("Procurando sinal…", 20f, bold = true).apply { gravity = Gravity.CENTER }
        col.addView(qualityTv, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))

        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        col.addView(
            meter,
            LinearLayout.LayoutParams(MATCH, dp(12)).apply { setMargins(0, dp(12), 0, dp(12)) }
        )

        infoTv = tv("", 14f, muted).apply { gravity = Gravity.CENTER }
        col.addView(infoTv, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))

        graph = SignalGraphView(this).apply { setBackgroundColor(card) }
        col.addView(
            graph,
            LinearLayout.LayoutParams(MATCH, dp(200)).apply { setMargins(0, dp(16), 0, dp(12)) }
        )

        col.addView(
            tv(
                "Caminhe pelo cômodo segurando o celular e observe o número: quanto mais perto " +
                    "de 0 (ex.: -45 dBm), melhor o sinal. Use a aba \"Melhor local\" para registrar os pontos.",
                13f, muted
            )
        )
        return scrollPanel(col)
    }

    private fun buildMapPanel(): View {
        val col = column()
        col.addView(
            tv(
                "Vá até um ponto do cômodo, toque em medir e fique parado por 5 segundos. " +
                    "O app faz a média do sinal e dá um nome ao ponto. Repita em vários lugares " +
                    "e o ranking mostra o melhor.",
                14f, muted
            )
        )

        measureBtn = button("Medir este ponto (5 s)") { startMeasure() }
        col.addView(
            measureBtn,
            LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(12), 0, dp(4)) }
        )

        measureStatus = tv("", 14f, accent)
        col.addView(measureStatus)

        col.addView(tv("Ranking", 18f, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) })

        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(resultsBox)

        val clear = Button(this).apply {
            text = "Limpar pontos"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(card)
            setOnClickListener {
                points.clear()
                savePoints()
                renderResults()
            }
        }
        col.addView(
            clear,
            LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(16), 0, 0) }
        )
        return scrollPanel(col)
    }

    private fun buildSpeedPanel(): View {
        val col = column()

        speedBtn = button("Iniciar teste") { toggleSpeed() }
        col.addView(speedBtn, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))

        stageTv = tv("Pronto para testar", 14f, accent).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        col.addView(stageTv, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))

        pingTv = tv("--", 40f, bold = true)
        downTv = tv("--", 40f, bold = true)
        upTv = tv("--", 40f, bold = true)

        listOf(
            Triple("Latência (ms)", pingTv, 0),
            Triple("Download (Mbps)", downTv, 1),
            Triple("Upload (Mbps)", upTv, 2)
        ).forEach { (label, value, _) ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(card)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            box.addView(tv(label, 13f, muted))
            box.addView(value)
            col.addView(
                box,
                LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(10)) }
            )
        }

        col.addView(
            tv(
                "O teste usa os servidores da Cloudflare por cerca de 8 s no download e 8 s no upload, " +
                    "e pode consumir dezenas de MB. Prefira rodar no Wi-Fi.",
                12f, muted
            )
        )
        return scrollPanel(col)
    }

    // ------------------------------------------------------- Sinal em tempo real

    @Suppress("DEPRECATION")
    private fun updateSignal() {
        if (!wifi.isWifiEnabled) {
            showNoSignal("Wi-Fi desligado")
            return
        }
        val info = wifi.connectionInfo
        val rssi = info?.rssi ?: INVALID_RSSI
        if (info == null || rssi == INVALID_RSSI || rssi > 0) {
            showNoSignal("Sem conexão Wi-Fi")
            return
        }

        dbmTv.text = rssi.toString()
        dbmTv.setTextColor(qualityColor(rssi))
        qualityTv.text = qualityLabel(rssi)
        qualityTv.setTextColor(qualityColor(rssi))
        meter.progress = signalPercent(rssi)
        meter.progressTintList = ColorStateList.valueOf(qualityColor(rssi))

        val ssid = info.ssid?.trim('"').orEmpty()
        val name = if (ssid.isEmpty() || ssid == "<unknown ssid>") "Rede conectada" else ssid
        infoTv.text = "$name  •  ${bandLabel(info.frequency)}  •  ${info.linkSpeed} Mbps  •  ${signalPercent(rssi)}%"

        graph.add(rssi)

        if (measuring) collectSample(rssi)
    }

    private fun showNoSignal(msg: String) {
        dbmTv.text = "--"
        dbmTv.setTextColor(muted)
        qualityTv.text = msg
        qualityTv.setTextColor(muted)
        meter.progress = 0
        infoTv.text = ""
    }

    // ------------------------------------------------------- Melhor local

    private fun startMeasure() {
        if (measuring) return
        measuring = true
        samples.clear()
        measureStart = SystemClock.elapsedRealtime()
        measureBtn.isEnabled = false
        measureStatus.text = "Medindo… fique parado (5 s)"
    }

    private fun collectSample(rssi: Int) {
        samples.add(rssi)
        val elapsed = SystemClock.elapsedRealtime() - measureStart
        val left = ((MEASURE_MS - elapsed) / 1000 + 1).coerceAtLeast(0)
        measureStatus.text = "Medindo… fique parado (${left} s)"
        if (elapsed >= MEASURE_MS) finishMeasure()
    }

    private fun finishMeasure() {
        measuring = false
        measureBtn.isEnabled = true
        measureStatus.text = ""
        if (samples.isEmpty()) {
            Toast.makeText(this, "Sem sinal Wi-Fi para medir", Toast.LENGTH_SHORT).show()
            return
        }
        val avg = Math.round(samples.average()).toInt()

        val input = EditText(this).apply {
            setText("Ponto ${points.size + 1}")
            inputType = InputType.TYPE_CLASS_TEXT
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Média: $avg dBm (${qualityLabel(avg)})")
            .setMessage("Dê um nome para este local:")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val name = input.text.toString().trim().replace("|", " ").replace(";", " ")
                points.add(Pair(if (name.isEmpty()) "Ponto ${points.size + 1}" else name, avg))
                savePoints()
                renderResults()
            }
            .setNegativeButton("Descartar", null)
            .show()
    }

    private fun renderResults() {
        resultsBox.removeAllViews()
        if (points.isEmpty()) {
            resultsBox.addView(tv("Nenhum ponto medido ainda.", 14f, muted))
            return
        }
        points.sortedByDescending { it.second }.forEachIndexed { i, p ->
            val label = "${i + 1}. ${p.first}  —  ${p.second} dBm  (${qualityLabel(p.second)})" +
                if (i == 0) "  ★ MELHOR" else ""
            val row = tv(label, 16f, qualityColor(p.second), bold = i == 0)
            row.setPadding(0, dp(8), 0, dp(8))
            resultsBox.addView(row)
        }
    }

    private fun savePoints() {
        val raw = points.joinToString(";") { "${it.first}|${it.second}" }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("points", raw).apply()
    }

    private fun loadPoints() {
        points.clear()
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("points", "").orEmpty()
        if (raw.isEmpty()) return
        raw.split(";").forEach { entry ->
            val parts = entry.split("|")
            val value = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size == 2 && value != null) points.add(Pair(parts[0], value))
        }
    }

    // ------------------------------------------------------- Speedtest

    private fun toggleSpeed() {
        val running = tester
        if (running != null) {
            running.cancel()
            speedBtn.isEnabled = false
            return
        }

        pingTv.text = "--"
        downTv.text = "--"
        upTv.text = "--"
        speedBtn.text = "Cancelar"

        val st = SpeedTester(object : SpeedTester.Callback {
            override fun onStage(stage: String) {
                handler.post { stageTv.text = stage }
            }

            override fun onPing(ms: Double) {
                handler.post { pingTv.text = String.format("%.0f", ms) }
            }

            override fun onDownload(mbps: Double, isFinal: Boolean) {
                handler.post { downTv.text = String.format("%.1f", mbps) }
            }

            override fun onUpload(mbps: Double, isFinal: Boolean) {
                handler.post { upTv.text = String.format("%.1f", mbps) }
            }

            override fun onFinished(message: String) {
                handler.post {
                    tester = null
                    stageTv.text = message
                    speedBtn.text = "Iniciar teste"
                    speedBtn.isEnabled = true
                }
            }
        })
        tester = st
        st.start()
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val POLL_MS = 300L
        private const val MEASURE_MS = 5000L
        private const val INVALID_RSSI = -127
        private const val PREFS = "wifiscout"
    }
}
