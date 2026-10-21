package com.tameczada.pclauncher

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    private class AppItem(val label: String, val pkg: String, val intent: Intent, val icon: Drawable)
    private class Win(val view: View, val button: TextView)

    private val apps = mutableListOf<AppItem>()
    private val shown = mutableListOf<AppItem>()
    private val openWindows = mutableMapOf<String, Win>()

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var desktop: FrameLayout
    private lateinit var windowLayer: FrameLayout
    private lateinit var taskbarButtons: LinearLayout
    private lateinit var startMenu: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var appAdapter: BaseAdapter

    private val taskH by lazy { dp(52) }

    private val wallpapers = arrayOf(
        intArrayOf(0xFF0B3D91.toInt(), 0xFF1E88E5.toInt(), 0xFF00ACC1.toInt()),
        intArrayOf(0xFF1A0033.toInt(), 0xFF6A1B9A.toInt(), 0xFFE91E63.toInt()),
        intArrayOf(0xFF0F2027.toInt(), 0xFF203A43.toInt(), 0xFF2C5364.toInt()),
        intArrayOf(0xFF134E5E.toInt(), 0xFF2E7D32.toInt(), 0xFFA8E063.toInt())
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("pc", MODE_PRIVATE)
        window.statusBarColor = 0xFF111114.toInt()
        window.navigationBarColor = 0xFF1B1B1F.toInt()

        root = FrameLayout(this)

        desktop = FrameLayout(this)
        root.addView(desktop, FrameLayout.LayoutParams(MATCH, MATCH).apply { bottomMargin = taskH })
        desktop.setOnClickListener { hideStart() }
        desktop.setOnLongClickListener { cycleWallpaper(); true }
        desktop.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if (b - t != ob - ot) refreshDesktop()
        }

        windowLayer = FrameLayout(this)
        root.addView(windowLayer, FrameLayout.LayoutParams(MATCH, MATCH).apply { bottomMargin = taskH })

        buildStartMenu()
        buildTaskbar()
        setContentView(root)
        applyWallpaper()
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyStartSize() }
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        filterApps()
        desktop.post { refreshDesktop() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        hideStart()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (startMenu.visibility == View.VISIBLE) hideStart()
        // launcher: nunca fecha
    }

    // ---------------------------------------------------------------- apps

    private fun loadApps() {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps.clear()
        pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { ri ->
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                AppItem(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, launch, ri.loadIcon(pm))
            }
            .sortedBy { it.label.lowercase() }
            .forEach { apps.add(it) }
    }

    private fun launch(item: AppItem) {
        hideStart()
        try {
            startActivity(item.intent)
        } catch (e: Exception) {
            toast("Não foi possível abrir ${item.label}")
        }
    }

    private fun pinned(): MutableList<String> =
        (prefs.getString("pins", "") ?: "").split("|").filter { it.isNotEmpty() }.toMutableList()

    private fun savePins(l: List<String>) {
        prefs.edit().putString("pins", l.joinToString("|")).apply()
    }

    private fun pin(pkg: String) {
        val l = pinned()
        if (pkg !in l) {
            l.add(pkg)
            savePins(l)
        }
        refreshDesktop()
        toast("Fixado na área de trabalho")
    }

    private fun unpin(pkg: String) {
        val l = pinned()
        l.remove(pkg)
        savePins(l)
        refreshDesktop()
    }

    // ---------------------------------------------------------------- área de trabalho

    private fun applyWallpaper() {
        val i = prefs.getInt("wall", 0) % wallpapers.size
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, wallpapers[i])
    }

    private fun cycleWallpaper() {
        prefs.edit().putInt("wall", (prefs.getInt("wall", 0) + 1) % wallpapers.size).apply()
        applyWallpaper()
    }

    private fun refreshDesktop() {
        desktop.removeAllViews()
        val entries = mutableListOf<View>()
        entries += icon("Este Computador", null, "💻", { openSystemWindow() }, null)
        entries += icon("Bloco de Notas", null, "📝", { openNotepad() }, null)
        entries += icon("Lixeira", null, "🗑️", { toast("A lixeira está vazia") }, null)
        for (pkg in pinned()) {
            val a = apps.find { it.pkg == pkg } ?: continue
            entries += icon(a.label, a.icon, null, { launch(a) }) { v ->
                PopupMenu(this, v).apply {
                    menu.add(0, 1, 0, "Remover da área de trabalho")
                    setOnMenuItemClickListener { unpin(a.pkg); true }
                    show()
                }
            }
        }
        val cellW = dp(84)
        val cellH = dp(100)
        val perCol = maxOf(1, (desktop.height - dp(8)) / cellH)
        entries.forEachIndexed { i, v ->
            val col = i / perCol
            val row = i % perCol
            desktop.addView(v, FrameLayout.LayoutParams(cellW, cellH).apply {
                leftMargin = dp(8) + col * cellW
                topMargin = dp(8) + row * cellH
            })
        }
    }

    private fun icon(
        label: String, d: Drawable?, emoji: String?,
        click: () -> Unit, long: ((View) -> Unit)?
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                    setColor(0x55FFFFFF); cornerRadius = dp(6).toFloat()
                })
                addState(intArrayOf(), ColorDrawable(0))
            }
            setOnClickListener { click() }
            if (long != null) setOnLongClickListener { long(it); true }
        }
        if (d != null) {
            box.addView(ImageView(this).apply { setImageDrawable(d) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        } else {
            box.addView(TextView(this).apply {
                text = emoji; textSize = 32f; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        box.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        return box
    }

    // ---------------------------------------------------------------- barra de tarefas

    private fun buildTaskbar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xEE1B1B1F.toInt())
            setPadding(dp(4), 0, dp(10), 0)
            isClickable = true
        }
        val start = WinLogo(this).apply { setOnClickListener { toggleStart() } }
        bar.addView(start, LinearLayout.LayoutParams(dp(52), MATCH))

        taskbarButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(taskbarButtons, LinearLayout.LayoutParams(0, MATCH, 1f))

        val clock = TextClock(this).apply {
            format24Hour = "HH:mm\ndd/MM/yyyy"
            format12Hour = "HH:mm\ndd/MM/yyyy"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        bar.addView(clock, LinearLayout.LayoutParams(WRAP, MATCH))

        root.addView(bar, FrameLayout.LayoutParams(MATCH, taskH, Gravity.BOTTOM))
    }

    // ---------------------------------------------------------------- menu iniciar

    private fun buildStartMenu() {
        startMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            isClickable = true
            setPadding(dp(12), dp(12), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(0xF2202024.toInt()); cornerRadius = dp(14).toFloat()
            }
        }

        search = EditText(this).apply {
            hint = "Pesquisar apps"
            setHintTextColor(0xFF9E9E9E.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            isSingleLine = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF33333A.toInt()); cornerRadius = dp(10).toFloat()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { filterApps() }
            })
        }
        startMenu.addView(search, LinearLayout.LayoutParams(MATCH, WRAP))

        appAdapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = shown[position]
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                }
                row.addView(ImageView(this@MainActivity).apply { setImageDrawable(item.icon) },
                    LinearLayout.LayoutParams(dp(36), dp(36)))
                row.addView(TextView(this@MainActivity).apply {
                    text = item.label
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setSingleLine()
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
                return row
            }
        }

        list = ListView(this).apply {
            adapter = appAdapter
            divider = null
            selector = ColorDrawable(0x33FFFFFF)
            setOnItemClickListener { _, _, pos, _ -> launch(shown[pos]) }
            setOnItemLongClickListener { _, view, pos, _ ->
                val item = shown[pos]
                PopupMenu(this@MainActivity, view).apply {
                    menu.add(0, 1, 0, "Fixar na área de trabalho")
                    menu.add(0, 2, 1, "Informações do app")
                    menu.add(0, 3, 2, "Desinstalar")
                    setOnMenuItemClickListener { m ->
                        when (m.itemId) {
                            1 -> pin(item.pkg)
                            2 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${item.pkg}")))
                            3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${item.pkg}")))
                        }
                        true
                    }
                    show()
                }
                true
            }
        }
        startMenu.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            topMargin = dp(8); bottomMargin = dp(4)
        })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(flatButton("⚙  Configurações") {
            hideStart()
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bottom.addView(flatButton("🖼  Papel de parede") { cycleWallpaper() },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        startMenu.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(startMenu, FrameLayout.LayoutParams(dp(360), dp(520), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(8); bottomMargin = taskH + dp(8)
        })
    }

    private fun flatButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(10), dp(8), dp(10))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun applyStartSize() {
        val m = resources.displayMetrics
        val lp = startMenu.layoutParams as FrameLayout.LayoutParams
        val w = minOf(dp(360), m.widthPixels - dp(16))
        val h = minOf(dp(520), (m.heightPixels * 0.72f).toInt())
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h
            startMenu.layoutParams = lp
        }
    }

    private fun filterApps() {
        val q = search.text.toString().trim().lowercase()
        shown.clear()
        shown.addAll(if (q.isEmpty()) apps else apps.filter { it.label.lowercase().contains(q) })
        appAdapter.notifyDataSetChanged()
    }

    private fun toggleStart() {
        if (startMenu.visibility == View.VISIBLE) hideStart() else {
            search.setText("")
            filterApps()
            startMenu.visibility = View.VISIBLE
        }
    }

    private fun hideStart() {
        startMenu.visibility = View.GONE
    }

    // ---------------------------------------------------------------- janelas

    private fun openWindow(key: String, title: String, content: View, wDp: Int, hDp: Int) {
        openWindows[key]?.let { w ->
            w.view.visibility = View.VISIBLE
            w.view.bringToFront()
            return
        }
        hideStart()

        val radius = dp(10).toFloat()
        val win = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            elevation = dp(12).toFloat()
            background = GradientDrawable().apply {
                setColor(0xFF2B2B31.toInt()); cornerRadius = radius; setStroke(dp(1), 0x44FFFFFF)
            }
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF1F1F24.toInt())
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
        }
        titleBar.addView(TextView(this).apply {
            text = title; setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))

        val btn = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isClickable = true
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF); cornerRadius = dp(6).toFloat()
            }
        }

        val minimize = flatButton("—") { win.visibility = View.GONE }
        val close = flatButton("✕") {
            windowLayer.removeView(win)
            taskbarButtons.removeView(btn)
            openWindows.remove(key)
        }
        titleBar.addView(minimize, LinearLayout.LayoutParams(dp(44), WRAP))
        titleBar.addView(close, LinearLayout.LayoutParams(dp(44), WRAP))

        var dx = 0f
        var dy = 0f
        titleBar.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    win.bringToFront()
                    dx = win.x - e.rawX
                    dy = win.y - e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    win.x = e.rawX + dx
                    win.y = maxOf(0f, e.rawY + dy)
                }
            }
            true
        }
        win.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) win.bringToFront()
            false
        }

        win.addView(titleBar, LinearLayout.LayoutParams(MATCH, WRAP))
        win.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val maxW = maxOf(dp(200), windowLayer.width - dp(16))
        val maxH = maxOf(dp(200), windowLayer.height - dp(16))
        val offset = openWindows.size * dp(28)
        windowLayer.addView(win, FrameLayout.LayoutParams(minOf(dp(wDp), maxW), minOf(dp(hDp), maxH)).apply {
            leftMargin = dp(24) + offset
            topMargin = dp(24) + offset
        })

        btn.setOnClickListener {
            if (win.visibility == View.VISIBLE) win.visibility = View.GONE
            else { win.visibility = View.VISIBLE; win.bringToFront() }
        }
        taskbarButtons.addView(btn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(4) })
        openWindows[key] = Win(win, btn)
    }

    private fun openSystemWindow() {
        val tv = TextView(this).apply {
            text = systemInfo()
            setTextColor(Color.WHITE)
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val sv = ScrollView(this).apply { addView(tv) }
        openWindow("sys", "Este Computador", sv, 320, 330)
    }

    private fun systemInfo(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val st = StatFs(Environment.getDataDirectory().path)
        fun gb(b: Long) = String.format(Locale.getDefault(), "%.1f GB", b / 1073741824.0)
        return "Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Sistema: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Processador: ${Build.HARDWARE} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}\n" +
            "Núcleos: ${Runtime.getRuntime().availableProcessors()}\n" +
            "Memória: ${gb(mi.totalMem - mi.availMem)} / ${gb(mi.totalMem)}\n" +
            "Armazenamento: ${gb(st.totalBytes - st.availableBytes)} / ${gb(st.totalBytes)}"
    }

    private fun openNotepad() {
        val et = EditText(this).apply {
            setText(prefs.getString("note", ""))
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
            background = null
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString("note", s.toString()).apply()
                }
            })
        }
        openWindow("note", "Bloco de Notas", et, 320, 360)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

/** Logo de quatro quadrados do botão Iniciar. */
private class WinLogo(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FC3F7.toInt() }
    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) * 0.5f
        val g = s * 0.08f
        val h = (s - g) / 2f
        val x0 = (width - s) / 2f
        val y0 = (height - s) / 2f
        canvas.drawRect(x0, y0, x0 + h, y0 + h, paint)
        canvas.drawRect(x0 + h + g, y0, x0 + s, y0 + h, paint)
        canvas.drawRect(x0, y0 + h + g, x0 + h, y0 + s, paint)
        canvas.drawRect(x0 + h + g, y0 + h + g, x0 + s, y0 + s, paint)
    }
}
