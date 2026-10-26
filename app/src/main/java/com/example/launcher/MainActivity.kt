package com.example.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

data class AppEntry(
    val label: String,
    val pkg: String,
    val cls: String,
    val icon: Drawable,
    val installTime: Long
)

sealed class Item
class AppItem(val app: AppEntry) : Item()
class FolderItem(val name: String, val members: List<AppEntry>, val secret: Boolean) : Item()

/**
 * Launcher "comum": aparece na gaveta de apps como qualquer outro app. Só vira tela
 * inicial se o usuário ligar essa opção em Personalizar.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var search: EditText
    private lateinit var gear: TextView
    private lateinit var quickBox: LinearLayout
    private lateinit var quickTitle: TextView
    private lateinit var quickRow: LinearLayout
    private lateinit var recycler: RecyclerView
    private lateinit var grid: GridLayoutManager
    private lateinit var dock: LinearLayout
    private lateinit var frame: FrameLayout
    private lateinit var homeLayer: LinearLayout
    private lateinit var homeSpace: View
    private lateinit var page0: LinearLayout
    private lateinit var pager: PagerView
    private lateinit var pageDots: LinearLayout
    private lateinit var clockView: TextClock
    private lateinit var dateView: TextClock
    private lateinit var profilePill: TextView
    private lateinit var homeGear: TextView
    private lateinit var appsButton: TextView
    private var drawerOpen = false
    private lateinit var wallpaperView: ImageView
    private lateinit var dimView: View
    private var wallpaperStamp = -1L
    private var textColor = Color.BLACK

    private val adapter = AppAdapter(false)
    private var all: List<AppEntry> = emptyList()
    private var query = ""
    private var revealed = false
    private var lockedSet: Set<String> = emptySet()
    private var secretPkgs: Set<String> = emptySet()
    private var profileAllowed: Set<String>? = null
    private var folderDialog: AlertDialog? = null
    private lateinit var lock: LockHelper
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // formato dos ícones / vidro fosco
    private var iconSig = ""
    private var glassBitmap: Bitmap? = null
    private var glassKey = ""

    // gestos
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var downInRecycler = false
    private var canScrollUpAtDown = false
    private var canScrollDownAtDown = false
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            loadApps()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        lock = LockHelper(this, prefs)
        buildUi()
        showDrawer(prefs.startInDrawer, false)

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

    override fun onStop() {
        super.onStop()
        // Ao sair do launcher, os apps ocultos voltam a ficar escondidos.
        revealed = false
    }

    override fun onDestroy() {
        super.onDestroy()
        lock.dismiss()
        folderDialog?.dismiss()
        try {
            unregisterReceiver(pkgReceiver)
        } catch (_: Exception) {
        }
        executor.shutdown()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Botão Início (quando é o launcher padrão): volta para a tela inicial, página 1.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            folderDialog?.dismiss()
            showDrawer(prefs.startInDrawer, false)
            pager.goTo(0, false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerOpen && query.isNotEmpty()) {
            search.setText("")
            return
        }
        if (drawerOpen && !prefs.startInDrawer) {
            showDrawer(false)
            return
        }
        if (!drawerOpen && pager.page != 0) {
            pager.goTo(0, true)
            return
        }
        if (prefs.isHomeAllowed()) {
            // Como tela inicial, fechar a activity faria o sistema reabri-la; só minimiza.
            moveTaskToBack(true)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        lock.handleResult(requestCode, resultCode)
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
                    val raw = s?.toString() ?: ""
                    // Código secreto: mostra/esconde os apps ocultos.
                    if (prefs.hasSecretCode() && raw.length == prefs.secretCodeLength &&
                        prefs.checkSecretCode(raw)
                    ) {
                        revealed = !revealed
                        toast(if (revealed) "Apps ocultos visíveis" else "Apps ocultos escondidos")
                        s?.clear()
                        refreshList()
                        return
                    }
                    query = raw.trim()
                    refreshDrawer()
                }
            })
            setOnEditorActionListener { _, _, _ ->
                (adapter.items.firstOrNull() as? AppItem)?.let { launch(it.app) }
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

        quickTitle = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(6), dp(16), 0)
        }
        quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        quickBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(quickTitle)
            addView(quickRow)
        }

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

        // Gaveta de apps: busca + grade
        root.addView(top)
        root.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Tela inicial: relógio, páginas, dock e botão Apps
        buildHome()

        // Camadas: imagem de fundo -> escurecimento -> conteúdo
        wallpaperView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        dimView = View(this)
        frame = FrameLayout(this)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        frame.addView(wallpaperView, FrameLayout.LayoutParams(match, match))
        frame.addView(dimView, FrameLayout.LayoutParams(match, match))
        frame.addView(homeLayer, FrameLayout.LayoutParams(match, match))
        frame.addView(root, FrameLayout.LayoutParams(match, match))
        setContentView(frame)
    }

    private fun buildHome() {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val matchP = ViewGroup.LayoutParams.MATCH_PARENT
        homeLayer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(24), dp(32), dp(16), dp(8))
        }
        clockView = TextClock(this).apply {
            textSize = 56f
            setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL))
            setIncludeFontPadding(false)
        }
        dateView = TextClock(this).apply { textSize = 16f }
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        dateView.format12Hour = pattern
        dateView.format24Hour = pattern

        profilePill = TextView(this).apply {
            textSize = 13f
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setOnClickListener { showProfilePicker() }
        }
        val pillLp = LinearLayout.LayoutParams(wrap, wrap)
        pillLp.topMargin = dp(8)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(clockView)
            addView(dateView)
            addView(profilePill, pillLp)
        }
        homeGear = TextView(this).apply {
            text = "⚙"
            textSize = 26f
            setPadding(dp(14), dp(4), dp(6), dp(4))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        header.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
        header.addView(homeGear)

        // Página 1: espaço livre + linha rápida. As demais (atalhos) são criadas em rebuildPages().
        homeSpace = View(this)
        page0 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(homeSpace, LinearLayout.LayoutParams(matchP, 0, 1f))
            addView(quickBox)
        }
        pager = PagerView(this)
        pager.onPageChanged = { p -> rebuildDots(pager.count, p) }

        pageDots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        }

        appsButton = TextView(this).apply {
            text = "▦  Apps"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(14), dp(36), dp(14))
            setOnClickListener { showDrawer(true) }
        }

        homeLayer.addView(header)
        homeLayer.addView(pager, LinearLayout.LayoutParams(matchP, 0, 1f))
        homeLayer.addView(pageDots)
        val dockLp = LinearLayout.LayoutParams(matchP, wrap)
        dockLp.setMargins(dp(12), dp(8), dp(12), 0)
        homeLayer.addView(dock, dockLp)
        val btnLp = LinearLayout.LayoutParams(wrap, wrap)
        btnLp.gravity = Gravity.CENTER_HORIZONTAL
        btnLp.setMargins(0, dp(12), 0, dp(20))
        homeLayer.addView(appsButton, btnLp)
    }

    /** Alterna entre a tela inicial e a lista de apps. */
    private fun showDrawer(open: Boolean, animate: Boolean = true) {
        drawerOpen = open
        root.animate().cancel()
        homeLayer.animate().cancel()
        if (open) {
            homeLayer.visibility = View.GONE
            root.visibility = View.VISIBLE
            recycler.scrollToPosition(0)
            if (animate) {
                root.alpha = 0f
                root.animate().alpha(1f).setDuration(160).start()
            } else {
                root.alpha = 1f
            }
        } else {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(search.windowToken, 0)
            if (query.isNotEmpty()) search.setText("")
            search.clearFocus()
            root.visibility = View.GONE
            homeLayer.visibility = View.VISIBLE
            if (animate) {
                homeLayer.alpha = 0f
                homeLayer.animate().alpha(1f).setDuration(160).start()
            } else {
                homeLayer.alpha = 1f
            }
        }
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

    /** Fundo arredondado da busca/dock: vidro fosco (se ligado e pronto) ou cor sólida. */
    private fun surfaceBackground(radiusDp: Int, accentStroke: Boolean): Drawable {
        val r = dp(radiusDp).toFloat()
        val gb = glassBitmap
        val base: Drawable = if (prefs.glass && prefs.hasWallpaper() && gb != null) {
            val s = prefs.surface()
            val tint = Color.argb(115, Color.red(s), Color.green(s), Color.blue(s))
            GlassDrawable(frame, gb, r, prefs.dim * 255 / 100, tint)
        } else {
            GradientDrawable().apply {
                cornerRadius = r
                setColor(surfaceColor())
            }
        }
        if (!accentStroke) return base
        val stroke = GradientDrawable().apply {
            cornerRadius = r
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), prefs.accentColor())
        }
        return LayerDrawable(arrayOf(base, stroke))
    }

    private fun applySurfaces() {
        search.background = surfaceBackground(24, true)
        search.setPadding(dp(18), dp(10), dp(18), dp(10))
        dock.background = surfaceBackground(24, false)
        val ac = prefs.accentColor()
        profilePill.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.argb(60, Color.red(ac), Color.green(ac), Color.blue(ac)))
        }
    }

    /** Prepara (em segundo plano) a versão desfocada da imagem de fundo para o vidro fosco. */
    private fun ensureGlass() {
        val key = "${prefs.wallpaperFile().lastModified()}|${prefs.glassBlur}"
        if (key == glassKey) return
        glassKey = key
        glassBitmap = null
        if (executor.isShutdown) return
        val file = prefs.wallpaperFile()
        val radius = prefs.glassBlur
        executor.execute {
            val bmp = try {
                BitmapFactory.decodeFile(file.absolutePath)?.let { Glass.blur(it, radius) }
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (glassKey == key && bmp != null) {
                    glassBitmap = bmp
                    applySurfaces()
                }
            }
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

        // Ícones recortados: refaz o cache quando muda o formato ou o tamanho.
        val sig = "${prefs.iconShape}/${prefs.iconSize}"
        if (sig != iconSig) {
            iconSig = sig
            IconShaper.clear()
        }

        // Vidro fosco: só com imagem de fundo.
        if (prefs.glass && wp) {
            ensureGlass()
        } else {
            glassBitmap = null
            glassKey = ""
        }

        search.setTextColor(fg)
        search.setHintTextColor(Color.argb(136, Color.red(fg), Color.green(fg), Color.blue(fg)))
        applySurfaces()
        gear.setTextColor(prefs.accentColor())
        quickTitle.setTextColor(textColor)

        clockView.setTextColor(textColor)
        dateView.setTextColor(textColor)
        profilePill.setTextColor(textColor)
        val clockVis = if (prefs.showClock) View.VISIBLE else View.GONE
        clockView.visibility = clockVis
        dateView.visibility = clockVis
        homeGear.setTextColor(prefs.accentColor())
        appsButton.setTextColor(Color.WHITE)
        appsButton.background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(prefs.accentColor())
        }

        val horiz = prefs.gridHorizontal
        adapter.horizontal = horiz
        grid.orientation = if (horiz) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL
        grid.spanCount = if (horiz) prefs.gridRows else prefs.columns
    }

    // ---------------------------------------------------------------- Ícones

    /** Ícone do app já no formato escolhido (círculo, squircle…). */
    private fun iconOf(a: AppEntry, sizeDp: Int = prefs.iconSize): Drawable =
        IconShaper.shaped(this, a.icon, a.pkg + "/" + a.cls, prefs.iconShape, dp(sizeDp))

    // ---------------------------------------------------------------- Gestos

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        trackGesture(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun insideView(v: View, x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + v.width &&
            y >= loc[1] && y <= loc[1] + v.height
    }

    /** Espaço vazio: na lista, fora de qualquer item; na tela inicial, a área livre do meio. */
    private fun emptySpot(x: Float, y: Float): Boolean {
        if (!drawerOpen) return insideView(homeSpace, x, y)
        val loc = IntArray(2)
        recycler.getLocationOnScreen(loc)
        return recycler.findChildViewUnder(x - loc[0], y - loc[1]) == null
    }

    private fun trackGesture(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                downTime = ev.eventTime
                // Na lista, só vale dentro da grade; na tela inicial, vale em toda a tela.
                downInRecycler = if (drawerOpen) insideView(recycler, ev.rawX, ev.rawY) else true
                canScrollUpAtDown = drawerOpen && recycler.canScrollVertically(-1)
                canScrollDownAtDown = drawerOpen && recycler.canScrollVertically(1)
            }
            MotionEvent.ACTION_UP -> {
                if (!downInRecycler) return
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                val dt = ev.eventTime - downTime
                if (abs(dy) > dp(90) && abs(dy) > abs(dx) * 2 && dt < 600) {
                    lastTapTime = 0L
                    if (dy > 0 && !canScrollUpAtDown) {
                        runAction(prefs.gestureDown)
                    } else if (dy < 0 && !canScrollDownAtDown) {
                        // Na tela inicial, deslizar para cima sempre abre a lista de apps.
                        if (drawerOpen) runAction(prefs.gestureUp) else showDrawer(true)
                    }
                } else if (abs(dx) < dp(12) && abs(dy) < dp(12) && dt < 250) {
                    val now = ev.eventTime
                    if (now - lastTapTime < 350 &&
                        abs(ev.rawX - lastTapX) < dp(40) && abs(ev.rawY - lastTapY) < dp(40)
                    ) {
                        lastTapTime = 0L
                        if (emptySpot(ev.rawX, ev.rawY)) runAction(prefs.gestureDouble)
                    } else {
                        lastTapTime = now
                        lastTapX = ev.rawX
                        lastTapY = ev.rawY
                    }
                }
            }
        }
    }

    private fun runAction(a: Int) {
        when (a) {
            1 -> focusSearch()
            2 -> lockScreen()
            3 -> startActivity(Intent(this, SettingsActivity::class.java))
            4 -> expandNotifications()
        }
    }

    private fun focusSearch() {
        if (!drawerOpen) showDrawer(true)
        search.post { Keyboard.show(search) }
    }

    private fun lockScreen() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, LockAdmin::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Bloquear a tela")
                .setMessage(
                    "Para bloquear a tela com um gesto, o Android exige ativar este app como " +
                        "administrador do dispositivo (só a permissão de bloquear a tela). " +
                        "Você pode remover depois em Personalizar > Gestos."
                )
                .setPositiveButton("Ativar") { _, _ ->
                    startActivity(
                        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                            .putExtra(
                                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "Usado apenas para bloquear a tela."
                            )
                    )
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel")
                .invoke(sb)
        } catch (e: Throwable) {
            toast("Não suportado neste Android")
        }
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

    /** Apps que podem aparecer em algum lugar: sem os ocultos e só os do perfil ativo. */
    private fun visibleApps(): List<AppEntry> {
        val hidden = prefs.hidden
        val allowed = profileAllowed
        val sel = prefs.secretReveal
        return all.filter {
            (it.pkg !in hidden || (revealed && (sel.isEmpty() || it.pkg in sel))) &&
                (allowed == null || it.pkg in allowed)
        }
    }

    /** Apps "à vista": também sem os que estão dentro de pastas secretas. */
    private fun openApps(): List<AppEntry> = visibleApps().filter { it.pkg !in secretPkgs }

    private fun sortApps(l: List<AppEntry>): List<AppEntry> = when (prefs.sort) {
        1 -> l.sortedByDescending { it.installTime }
        2 -> l.sortedWith(
            compareByDescending<AppEntry> { prefs.useCount(it.pkg) }.thenBy { it.label.lowercase() }
        )
        else -> l.sortedBy { it.label.lowercase() }
    }

    private fun buildItems(): List<Item> {
        val open = openApps()
        if (query.isNotEmpty()) {
            return sortApps(open.filter { it.label.contains(query, ignoreCase = true) })
                .map { AppItem(it) }
        }
        val vis = visibleApps()
        val folders = prefs.folders()
        val secretNames = prefs.secretFolders
        val inFolder = HashSet<String>()
        folders.values.forEach { inFolder.addAll(it) }

        val items = ArrayList<Item>()
        for (name in folders.keys.sortedBy { it.lowercase() }) {
            val members = (folders[name] ?: emptyList<String>())
                .flatMap { p -> vis.filter { it.pkg == p } }
            items.add(FolderItem(name, members, name in secretNames))
        }
        sortApps(open.filter { it.pkg !in inFolder }).forEach { items.add(AppItem(it)) }
        return items
    }

    /** Recalcula tudo (lista de apps + tela inicial). */
    private fun refreshList() {
        lockedSet = prefs.locked
        val secretNames = prefs.secretFolders
        secretPkgs = prefs.folders().filterKeys { it in secretNames }.values.flatten().toSet()
        val prof = prefs.activeProfile
        profileAllowed = if (prof.isEmpty()) null else prefs.profiles()[prof]?.toSet()
        refreshDrawer()
        refreshHome()
    }

    @Suppress("NotifyDataSetChanged")
    private fun refreshDrawer() {
        adapter.items = buildItems()
        adapter.notifyDataSetChanged()
    }

    private fun refreshHome() {
        rebuildQuick()
        rebuildDock()
        rebuildPages()
        updateProfilePill()
    }

    private fun rebuildQuick() {
        quickRow.removeAllViews()
        val mode = prefs.topRow.coerceIn(0, 2)
        val open = openApps()
        val list: List<AppEntry> = when (mode) {
            1 -> open.filter { prefs.lastUsed(it.pkg) > 0 }.sortedByDescending { prefs.lastUsed(it.pkg) }
            2 -> open.filter { prefs.useCount(it.pkg) > 0 }.sortedByDescending { prefs.useCount(it.pkg) }
            else -> emptyList()
        }.take(5)
        quickBox.visibility = if (mode != 0 && list.isNotEmpty()) View.VISIBLE else View.GONE
        quickTitle.text = Prefs.TOPROW_NAMES[mode]
        for (a in list) {
            val iv = ImageView(this).apply {
                setImageDrawable(iconOf(a, 44))
                setOnClickListener { launch(a) }
                setOnLongClickListener {
                    showMenu(a)
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
            lp.setMargins(0, dp(4), 0, dp(4))
            quickRow.addView(iv, lp)
        }
    }

    private fun rebuildDock() {
        dock.removeAllViews()
        val open = openApps()
        val apps = prefs.pinned
            .mapNotNull { p -> open.firstOrNull { it.pkg == p } }
            .take(5)
        dock.visibility = if (prefs.showDock && apps.isNotEmpty()) View.VISIBLE else View.GONE
        dock.background = surfaceBackground(24, false)
        for (a in apps) {
            val iv = ImageView(this).apply {
                setImageDrawable(iconOf(a))
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

    // ---------------------------------------------------------------- Páginas da tela inicial

    private fun rebuildPages() {
        val pages = prefs.pages()
        val open = openApps()
        val views = ArrayList<View>()
        views.add(page0)
        for (pkgs in pages) {
            val items = pkgs.mapNotNull { p -> open.firstOrNull { it.pkg == p } }.map { AppItem(it) }
            val pageAdapter = AppAdapter(false)
            pageAdapter.items = items
            val rv = RecyclerView(this).apply {
                layoutManager = GridLayoutManager(this@MainActivity, prefs.columns)
                adapter = pageAdapter
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setPadding(dp(8), dp(4), dp(8), dp(8))
            }
            val hint = TextView(this).apply {
                text = "Página vazia\nSegure um app e escolha \"Adicionar à página\""
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(textColor)
                setPadding(dp(32), 0, dp(32), 0)
                visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
            val box = FrameLayout(this)
            box.addView(
                rv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            box.addView(
                hint,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
            views.add(box)
        }
        pager.setPages(views, pager.page)
        rebuildDots(views.size, pager.page)
    }

    private fun rebuildDots(count: Int, current: Int) {
        pageDots.removeAllViews()
        pageDots.visibility = if (count > 1) View.VISIBLE else View.GONE
        for (i in 0 until count) {
            val size = dp(if (i == current) 9 else 7)
            val v = View(this)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == current) prefs.accentColor() else Color.argb(120, 160, 160, 160))
            }
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(dp(4), 0, dp(4), 0)
            pageDots.addView(v, lp)
        }
    }

    private fun pageIndexOf(a: AppEntry): Int = prefs.pages().indexOfFirst { a.pkg in it }

    private fun choosePage(a: AppEntry) {
        val pages = prefs.pages()
        val labels = ArrayList<String>()
        pages.forEachIndexed { i, p -> labels.add("Página ${i + 2} (${p.size} apps)") }
        labels.add("➕ Nova página")
        AlertDialog.Builder(this)
            .setTitle("Adicionar à página")
            .setItems(labels.toTypedArray()) { _, which -> addToPage(a, which) }
            .show()
    }

    private fun addToPage(a: AppEntry, idx: Int) {
        val pages = prefs.pages()
        if (idx >= pages.size) pages.add(ArrayList())
        val target = idx.coerceAtMost(pages.size - 1)
        pages.forEach { it.remove(a.pkg) }
        pages[target].add(a.pkg)
        prefs.savePages(pages)
        folderDialog?.dismiss()
        refreshList()
        toast("Adicionado à página ${target + 2}")
    }

    private fun removeFromPages(a: AppEntry) {
        val pages = prefs.pages()
        pages.forEach { it.remove(a.pkg) }
        prefs.savePages(pages)
        refreshList()
    }

    // ---------------------------------------------------------------- Perfis

    private fun updateProfilePill() {
        val profiles = prefs.profiles()
        val active = prefs.activeProfile
        if (profiles.isEmpty() && active.isEmpty()) {
            profilePill.visibility = View.GONE
            return
        }
        profilePill.visibility = View.VISIBLE
        profilePill.text = "👤 " + (if (active.isEmpty() || !profiles.containsKey(active)) "Todos os apps" else active)
    }

    private fun showProfilePicker() {
        val names = prefs.profiles().keys.toList()
        val labels = (listOf("Todos os apps") + names).toTypedArray()
        val cur = prefs.activeProfile
        val checked = if (cur.isEmpty()) 0 else (names.indexOf(cur) + 1).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Perfil")
            .setSingleChoiceItems(labels, checked) { d, which ->
                d.dismiss()
                prefs.activeProfile = if (which == 0) "" else names[which - 1]
                refreshList()
            }
            .show()
    }

    // ---------------------------------------------------------------- Bloqueio / abrir apps

    /** Pede a senha (própria ou do celular, conforme Personalizar) e então executa [action]. */
    private fun authThen(title: String, action: () -> Unit) {
        lock.authenticate(title, null, action)
    }

    private fun launch(a: AppEntry) {
        folderDialog?.dismiss()
        folderDialog = null
        if (a.pkg in lockedSet) {
            authThen("Abrir ${a.label}") { doLaunch(a) }
        } else {
            doLaunch(a)
        }
    }

    private fun doLaunch(a: AppEntry) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(a.pkg, a.cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(i)
            prefs.recordUse(a.pkg)
            if (prefs.closeAfterLaunch) finish()
        } catch (e: Exception) {
            toast("Não foi possível abrir ${a.label}")
        }
    }

    private fun toggleLock(a: AppEntry) {
        if (!lock.canProtect()) {
            Toast.makeText(
                this,
                "Defina uma senha própria em Personalizar > Bloqueio e privacidade, ou um bloqueio de tela no Android",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (a.pkg in prefs.locked) {
            authThen("Remover bloqueio de ${a.label}") {
                prefs.locked = prefs.locked - a.pkg
                refreshList()
                toast("Bloqueio removido")
            }
        } else {
            prefs.locked = prefs.locked + a.pkg
            refreshList()
            toast("${a.label} agora pede senha para abrir por aqui")
        }
    }

    // ---------------------------------------------------------------- Atalhos do app

    /** Atalhos do app (ex.: "Nova mensagem"). Só existem quando este launcher é o padrão. */
    private fun loadShortcuts(pkg: String): List<ShortcutInfo> {
        try {
            val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            if (!la.hasShortcutHostPermission()) return emptyList()
            val q = LauncherApps.ShortcutQuery()
                .setPackage(pkg)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
            val list = la.getShortcuts(q, Process.myUserHandle()) ?: return emptyList()
            return list.filter { it.isEnabled }
        } catch (e: Throwable) {
            return emptyList()
        }
    }

    private fun startShortcut(s: ShortcutInfo, a: AppEntry) {
        val run = {
            try {
                val la = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                la.startShortcut(s, null, null)
                prefs.recordUse(a.pkg)
            } catch (e: Throwable) {
                toast("Não foi possível abrir o atalho")
            }
        }
        if (a.pkg in lockedSet) authThen("Abrir ${a.label}", run) else run()
    }

    // ---------------------------------------------------------------- Menus

    private fun showMenu(a: AppEntry) {
        val isPinned = a.pkg in prefs.pinned
        val folders = prefs.folders()
        val currentFolder = folders.entries.firstOrNull { a.pkg in it.value }?.key
        val isLocked = a.pkg in prefs.locked
        val pageIdx = pageIndexOf(a)

        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        // Atalhos do próprio app (quando disponíveis)
        for (s in loadShortcuts(a.pkg).take(4)) {
            val name = s.shortLabel ?: s.longLabel ?: s.id
            labels.add("⚡ $name")
            actions.add { startShortcut(s, a) }
        }

        labels.add(if (isPinned) "Desafixar do dock" else "Fixar no dock")
        actions.add {
            val p = prefs.pinned.toMutableList()
            if (isPinned) {
                p.remove(a.pkg)
            } else if (p.size >= 5) {
                toast("Dock cheio (máximo 5)")
            } else {
                p.add(a.pkg)
            }
            prefs.pinned = p
            rebuildDock()
        }

        if (pageIdx >= 0) {
            labels.add("Tirar da página ${pageIdx + 2}")
            actions.add { removeFromPages(a) }
        }
        labels.add(if (pageIdx >= 0) "Mover para outra página…" else "Adicionar à página…")
        actions.add { choosePage(a) }

        if (currentFolder != null) {
            labels.add("Tirar da pasta \"$currentFolder\"")
            actions.add { removeFromFolder(a) }
        }
        labels.add(if (currentFolder != null) "Mover para outra pasta…" else "Mover para pasta…")
        actions.add { chooseFolder(a) }

        labels.add(if (isLocked) "Remover bloqueio" else "Bloquear com senha")
        actions.add { toggleLock(a) }

        if (a.pkg in prefs.hidden) {
            labels.add("Deixar de ocultar")
            actions.add {
                prefs.hidden = prefs.hidden - a.pkg
                folderDialog?.dismiss()
                refreshList()
            }
        } else {
            labels.add("Ocultar da lista")
            actions.add {
                prefs.hidden = prefs.hidden + a.pkg
                folderDialog?.dismiss()
                refreshList()
                toast("Oculto. Veja em Personalizar.")
            }
        }

        labels.add("Informações do app")
        actions.add {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))
            )
        }

        labels.add("Desinstalar")
        actions.add { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))) }

        AlertDialog.Builder(this)
            .setTitle(a.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showFolderMenu(f: FolderItem) {
        val items = arrayOf(
            "Renomear",
            "Excluir pasta (apps voltam para a lista)",
            if (f.secret) "Deixar de ser secreta" else "Tornar secreta (pede senha)"
        )
        AlertDialog.Builder(this)
            .setTitle(if (f.secret) "🔒 ${f.name}" else f.name)
            .setItems(items) { _, which ->
                val run = {
                    when (which) {
                        0 -> renameFolder(f)
                        1 -> deleteFolder(f)
                        else -> toggleSecret(f)
                    }
                }
                // Mexer numa pasta secreta exige a senha.
                if (f.secret) authThen("Pasta \"${f.name}\"", run) else run()
            }
            .show()
    }

    private fun renameFolder(f: FolderItem) {
        askFolderName("Renomear pasta", f.name) { n ->
            val m = prefs.folders()
            if (n != f.name && m.containsKey(n)) {
                toast("Já existe uma pasta com esse nome")
            } else {
                val v = m.remove(f.name)
                if (v != null) m[n] = v
                prefs.saveFolders(m)
                if (f.secret) prefs.secretFolders = prefs.secretFolders - f.name + n
                refreshList()
            }
        }
    }

    private fun deleteFolder(f: FolderItem) {
        val m = prefs.folders()
        m.remove(f.name)
        prefs.saveFolders(m)
        prefs.secretFolders = prefs.secretFolders - f.name
        refreshList()
    }

    private fun toggleSecret(f: FolderItem) {
        if (f.secret) {
            prefs.secretFolders = prefs.secretFolders - f.name
            toast("A pasta deixou de ser secreta")
        } else {
            if (!lock.canProtect()) {
                Toast.makeText(
                    this,
                    "Defina uma senha própria em Personalizar > Bloqueio e privacidade, ou um bloqueio de tela no Android",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            prefs.secretFolders = prefs.secretFolders + f.name
            toast("Pasta secreta: os apps dela somem da busca e dos recentes")
        }
        refreshList()
    }

    // ---------------------------------------------------------------- Pastas

    private fun askFolderName(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(initial)
            setSingleLine()
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(et)
        }
        val d = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) onOk(n)
            }
            .setNegativeButton("Cancelar", null)
            .create()
        d.setOnShowListener { Keyboard.show(et) }
        d.show()
    }

    private fun chooseFolder(a: AppEntry) {
        val names = prefs.folders().keys.toList()
        val labels = (names + "➕ Nova pasta…").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Mover para pasta")
            .setItems(labels) { _, which ->
                if (which == names.size) {
                    askFolderName("Nova pasta", "") { n -> addToFolder(n, a) }
                } else {
                    addToFolder(names[which], a)
                }
            }
            .show()
    }

    private fun addToFolder(name: String, a: AppEntry) {
        val m = prefs.folders()
        m.values.forEach { it.remove(a.pkg) }
        m.getOrPut(name) { ArrayList() }.add(a.pkg)
        m.values.removeAll { it.isEmpty() }
        prefs.saveFolders(m)
        folderDialog?.dismiss()
        refreshList()
    }

    private fun removeFromFolder(a: AppEntry) {
        val m = prefs.folders()
        m.values.forEach { it.remove(a.pkg) }
        m.values.removeAll { it.isEmpty() }
        prefs.saveFolders(m)
        folderDialog?.dismiss()
        refreshList()
    }

    private fun openFolder(f: FolderItem) {
        if (f.secret) {
            authThen("Abrir pasta \"${f.name}\"") { showFolder(f) }
        } else {
            showFolder(f)
        }
    }

    private fun showFolder(f: FolderItem) {
        if (f.members.isEmpty()) {
            toast("Pasta vazia — segure para renomear ou excluir")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(12))
            minimumWidth = dp(280)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(prefs.surface())
            }
        }
        val title = TextView(this).apply {
            text = f.name
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(prefs.fg())
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        val folderAdapter = AppAdapter(true)
        folderAdapter.items = f.members.map { AppItem(it) }
        val rv = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = folderAdapter
        }
        box.addView(title)
        box.addView(rv)

        val d = AlertDialog.Builder(this).setView(box).create()
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        folderDialog = d
        d.show()
    }

    /** Ícone de pasta: quadrado arredondado com até 4 miniaturas (ou um cadeado, se for secreta). */
    private fun folderIcon(members: List<AppEntry>, secret: Boolean): Drawable {
        val size = dp(prefs.iconSize)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val ac = prefs.accentColor()
        p.color = Color.argb(90, Color.red(ac), Color.green(ac), Color.blue(ac))
        val r = size * 0.28f
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, p)

        if (secret) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG)
            tp.textSize = size * 0.5f
            tp.textAlign = Paint.Align.CENTER
            c.drawText("🔒", size / 2f, size / 2f + tp.textSize * 0.35f, tp)
            return BitmapDrawable(resources, bmp)
        }

        val pad = size * 0.12f
        val cell = (size - 2 * pad) / 2f
        val mini = cell * 0.9f
        for ((i, a) in members.take(4).withIndex()) {
            val d = iconOf(a)
            val old = d.copyBounds()
            val l = (pad + (i % 2) * cell + (cell - mini) / 2f).toInt()
            val t = (pad + (i / 2) * cell + (cell - mini) / 2f).toInt()
            d.setBounds(l, t, l + mini.toInt(), t + mini.toInt())
            d.draw(c)
            d.setBounds(old.left, old.top, old.right, old.bottom)
        }
        return BitmapDrawable(resources, bmp)
    }

    // ---------------------------------------------------------------- Adapter

    inner class VH(val box: LinearLayout, val iv: ImageView, val tv: TextView) :
        RecyclerView.ViewHolder(box)

    /** [dialogStyle]: usado dentro do diálogo de pasta (texto na cor do tema, não do fundo). */
    inner class AppAdapter(private val dialogStyle: Boolean) : RecyclerView.Adapter<VH>() {
        var items: List<Item> = emptyList()
        var horizontal = false

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
            val item = items[position]
            val size = dp(prefs.iconSize)
            h.box.layoutParams = if (horizontal) {
                RecyclerView.LayoutParams(
                    (resources.displayMetrics.widthPixels - dp(16)) / prefs.columns,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            } else {
                RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            h.iv.layoutParams = LinearLayout.LayoutParams(size, size)
            h.tv.setTextColor(if (dialogStyle) prefs.fg() else textColor)
            h.tv.visibility = if (prefs.showLabels) View.VISIBLE else View.GONE

            when (item) {
                is AppItem -> {
                    val a = item.app
                    h.iv.setImageDrawable(iconOf(a))
                    h.tv.text = if (a.pkg in lockedSet) "🔒 ${a.label}" else a.label
                    h.box.setOnClickListener { launch(a) }
                    h.box.setOnLongClickListener {
                        showMenu(a)
                        true
                    }
                }
                is FolderItem -> {
                    h.iv.setImageDrawable(folderIcon(item.members, item.secret))
                    h.tv.text = item.name
                    h.box.setOnClickListener { openFolder(item) }
                    h.box.setOnLongClickListener {
                        showFolderMenu(item)
                        true
                    }
                }
            }
        }
    }
}
