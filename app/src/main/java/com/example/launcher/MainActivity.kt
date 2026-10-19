package com.example.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

data class AppEntry(
    val label: String,
    val pkg: String,
    val cls: String,
    val icon: Drawable,
    val installTime: Long
)

/**
 * Launcher "comum": aparece na gaveta de apps como qualquer outro app e NÃO se registra
 * como tela inicial (sem categoria HOME). Voltar/fechar sai dele e nada fica permanente.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var search: EditText
    private lateinit var gear: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var grid: GridLayoutManager
    private lateinit var dock: LinearLayout
    private lateinit var frame: FrameLayout
    private lateinit var wallpaperView: ImageView
    private lateinit var dimView: View
    private var wallpaperStamp = -1L
    private var textColor = Color.BLACK

    private val adapter = AppAdapter()
    private var all: List<AppEntry> = emptyList()
    private var query = ""
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            loadApps()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildUi()

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(pkgReceiver, f)
        }
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        applyPrefs()
        refreshList()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pkgReceiver)
        } catch (_: Exception) {
        }
        executor.shutdown()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (query.isNotEmpty()) {
            search.setText("")
        } else if (prefs.isHomeAllowed()) {
            // Como tela inicial, fechar a activity faria o sistema reabri-la; só minimiza.
            moveTaskToBack(true)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(4))
        }

        search = EditText(this).apply {
            hint = "Buscar apps"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim() ?: ""
                    refreshList()
                }
            })
            setOnEditorActionListener { _, _, _ ->
                adapter.items.firstOrNull()?.let { launch(it) }
                true
            }
        }

        gear = TextView(this).apply {
            text = "⚙"
            textSize = 26f
            setPadding(dp(14), dp(4), dp(6), dp(4))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        top.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(gear)

        grid = GridLayoutManager(this, 4)
        recycler = RecyclerView(this).apply {
            layoutManager = grid
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
        }

        root.addView(top)
        root.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(dock)

        // Camadas: imagem de fundo -> escurecimento -> conteúdo
        wallpaperView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        dimView = View(this)
        frame = FrameLayout(this)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        frame.addView(wallpaperView, FrameLayout.LayoutParams(match, match))
        frame.addView(dimView, FrameLayout.LayoutParams(match, match))
        frame.addView(root, FrameLayout.LayoutParams(match, match))
        setContentView(frame)
    }

    /** Cor das superfícies (busca/dock): levemente translúcida quando há imagem de fundo. */
    private fun surfaceColor(): Int {
        val s = prefs.surface()
        return if (prefs.hasWallpaper()) {
            Color.argb(210, Color.red(s), Color.green(s), Color.blue(s))
        } else {
            s
        }
    }

    @Suppress("DEPRECATION")
    private fun applyPrefs() {
        val bg = prefs.bg()
        val fg = prefs.fg()
        val wp = prefs.hasWallpaper()
        frame.setBackgroundColor(bg)
        if (wp) {
            val file = prefs.wallpaperFile()
            val stamp = file.lastModified()
            if (stamp != wallpaperStamp) {
                wallpaperView.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                wallpaperStamp = stamp
            }
            wallpaperView.visibility = View.VISIBLE
            dimView.visibility = View.VISIBLE
            dimView.setBackgroundColor(Color.argb(prefs.dim * 255 / 100, 0, 0, 0))
        } else {
            wallpaperView.visibility = View.GONE
            dimView.visibility = View.GONE
            wallpaperView.setImageDrawable(null)
            wallpaperStamp = -1L
        }
        textColor = if (wp) Color.WHITE else fg
        window.statusBarColor = bg
        window.navigationBarColor = bg

        var flags = 0
        if (!prefs.isDark() && !wp) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags

        search.setTextColor(fg)
        search.setHintTextColor(Color.argb(136, Color.red(fg), Color.green(fg), Color.blue(fg)))
        search.background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(surfaceColor())
            setStroke(dp(1), prefs.accentColor())
        }
        search.setPadding(dp(18), dp(10), dp(18), dp(10))
        gear.setTextColor(prefs.accentColor())

        grid.spanCount = prefs.columns
    }

    // ---------------------------------------------------------------- Dados

    private fun loadApps() {
        if (executor.isShutdown) return
        executor.execute {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                if (ai.packageName == packageName) return@mapNotNull null
                val time = try {
                    pm.getPackageInfo(ai.packageName, 0).firstInstallTime
                } catch (e: Exception) {
                    0L
                }
                AppEntry(ri.loadLabel(pm).toString(), ai.packageName, ai.name, ri.loadIcon(pm), time)
            }
            main.post {
                all = list
                refreshList()
            }
        }
    }

    private fun visibleApps(): List<AppEntry> {
        val hidden = prefs.hidden
        var l = all.filter { it.pkg !in hidden }
        l = if (prefs.sort == 1) {
            l.sortedByDescending { it.installTime }
        } else {
            l.sortedBy { it.label.lowercase() }
        }
        if (query.isNotEmpty()) {
            l = l.filter { it.label.contains(query, ignoreCase = true) }
        }
        return l
    }

    @Suppress("NotifyDataSetChanged")
    private fun refreshList() {
        adapter.items = visibleApps()
        adapter.notifyDataSetChanged()
        rebuildDock()
    }

    private fun rebuildDock() {
        dock.removeAllViews()
        val hidden = prefs.hidden
        val apps = prefs.pinned
            .mapNotNull { p -> all.firstOrNull { it.pkg == p && it.pkg !in hidden } }
            .take(5)
        dock.visibility = if (prefs.showDock && apps.isNotEmpty()) View.VISIBLE else View.GONE
        dock.setBackgroundColor(surfaceColor())
        for (a in apps) {
            val iv = ImageView(this).apply {
                setImageDrawable(a.icon)
                setOnClickListener { launch(a) }
                setOnLongClickListener {
                    showMenu(a)
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(0, dp(prefs.iconSize), 1f)
            lp.setMargins(0, dp(10), 0, dp(10))
            dock.addView(iv, lp)
        }
    }

    // ---------------------------------------------------------------- Ações

    private fun launch(a: AppEntry) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(a.pkg, a.cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(i)
            if (prefs.closeAfterLaunch) finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível abrir ${a.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu(a: AppEntry) {
        val isPinned = a.pkg in prefs.pinned
        val items = arrayOf(
            if (isPinned) "Desafixar do dock" else "Fixar no dock",
            "Ocultar da lista",
            "Informações do app",
            "Desinstalar"
        )
        AlertDialog.Builder(this)
            .setTitle(a.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val p = prefs.pinned.toMutableList()
                        if (isPinned) {
                            p.remove(a.pkg)
                        } else if (p.size >= 5) {
                            Toast.makeText(this, "Dock cheio (máximo 5)", Toast.LENGTH_SHORT).show()
                        } else {
                            p.add(a.pkg)
                        }
                        prefs.pinned = p
                        rebuildDock()
                    }
                    1 -> {
                        prefs.hidden = prefs.hidden + a.pkg
                        refreshList()
                        Toast.makeText(this, "Oculto. Veja em Personalizar.", Toast.LENGTH_SHORT).show()
                    }
                    2 -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))
                    )
                    3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- Adapter

    inner class VH(val box: LinearLayout, val iv: ImageView, val tv: TextView) :
        RecyclerView.ViewHolder(box)

    inner class AppAdapter : RecyclerView.Adapter<VH>() {
        var items: List<AppEntry> = emptyList()

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val out = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                box.setBackgroundResource(out.resourceId)
            }
            val iv = ImageView(ctx)
            val tv = TextView(ctx).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                textSize = 12f
                gravity = Gravity.CENTER
            }
            box.addView(iv)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            box.addView(tv, lp)
            return VH(box, iv, tv)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val a = items[position]
            h.iv.layoutParams = LinearLayout.LayoutParams(dp(prefs.iconSize), dp(prefs.iconSize))
            h.iv.setImageDrawable(a.icon)
            h.tv.text = a.label
            h.tv.setTextColor(textColor)
            h.tv.visibility = if (prefs.showLabels) View.VISIBLE else View.GONE
            h.box.setOnClickListener { launch(a) }
            h.box.setOnLongClickListener {
                showMenu(a)
                true
            }
        }
    }
}
