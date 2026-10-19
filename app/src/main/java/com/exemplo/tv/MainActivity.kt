package com.exemplo.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    companion object {
        const val DEFAULT_SOURCE = "https://iptv-org.github.io/iptv/countries/br.m3u"
        const val ALT_SOURCE = "https://github.com/iptv-com/iptv/raw/refs/heads/main/lists/brazil.m3u"
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"
    }

    // dados
    private var all: List<Channel> = emptyList()
    private val shown = mutableListOf<Channel>()
    private var favs = HashSet<String>()
    private var group: String? = null
    private var favOnly = false
    private var query = ""
    private var currentUrl: String? = null

    // player
    private lateinit var player: ExoPlayer
    private lateinit var httpFactory: DefaultHttpDataSource.Factory

    // views
    private lateinit var videoFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private lateinit var titleView: TextView
    private lateinit var controls: LinearLayout
    private lateinit var listArea: LinearLayout
    private lateinit var infoView: TextView
    private lateinit var groupBtn: Button
    private lateinit var favBtn: Button
    private lateinit var adapter: ChannelAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { controls.visibility = View.GONE; titleView.visibility = View.GONE }
    private val blue = Color.parseColor("#42A5F5")

    private val prefs by lazy { getSharedPreferences("tv_prefs", Context.MODE_PRIVATE) }
    private val cacheFile by lazy { File(filesDir, "playlist.m3u") }

    // ------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        favs = HashSet(prefs.getStringSet("favs", emptySet()) ?: emptySet())

        httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(10_000)
            .setUserAgent(BROWSER_UA)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> showStatus("Carregando…")
                    Player.STATE_READY -> statusView.visibility = View.GONE
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                showStatus("Canal indisponível (${error.errorCodeName}).\nTente outro canal.")
            }
        })

        buildUi()
        applyLayout()
        loadPlaylist()
    }

    override fun onStop() {
        player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isLandscape()) toggleFullscreen() else super.onBackPressed()
    }

    // ------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // --- vídeo
        videoFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            this.player = this@MainActivity.player
            setOnClickListener { toggleControls() }
        }
        videoFrame.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            text = "Escolha um canal"
        }
        videoFrame.addView(statusView, FrameLayout.LayoutParams(-1, -1))

        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(Color.parseColor("#66000000"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        videoFrame.addView(titleView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#66000000"))
            visibility = View.GONE
        }
        controls.addView(iconButton(android.R.drawable.ic_media_previous) { step(-1) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        controls.addView(iconButton(android.R.drawable.ic_menu_rotate) { toggleFullscreen() }, LinearLayout.LayoutParams(dp(56), dp(56)))
        controls.addView(iconButton(android.R.drawable.ic_media_next) { step(1) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        videoFrame.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        root.addView(videoFrame, LinearLayout.LayoutParams(-1, dp(220)))

        // --- área da lista
        listArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        groupBtn = Button(this).apply { text = "Categoria"; setOnClickListener { showGroupDialog() } }
        favBtn = Button(this).apply {
            text = "☆ Favoritos"
            setOnClickListener {
                favOnly = !favOnly
                text = if (favOnly) "★ Favoritos" else "☆ Favoritos"
                filter()
            }
        }
        val srcBtn = Button(this).apply { text = "Fonte"; setOnClickListener { showSourceDialog() } }
        for (b in listOf(groupBtn, favBtn, srcBtn)) {
            buttons.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        listArea.addView(buttons)

        val search = EditText(this).apply {
            hint = "Buscar canal"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    filter()
                }
            })
        }
        listArea.addView(search)

        infoView = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(4))
        }
        listArea.addView(infoView)

        adapter = ChannelAdapter()
        val listView = ListView(this).apply {
            this.adapter = this@MainActivity.adapter
            divider = null
            setOnItemClickListener { _, _, pos, _ -> play(shown[pos]) }
        }
        listArea.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(listArea, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun iconButton(icon: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setOnClickListener { onClick(); showControlsTemporarily() }
    }

    private fun applyLayout() {
        val lp = videoFrame.layoutParams as LinearLayout.LayoutParams
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        if (isLandscape()) {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.weight = 1f
            listArea.visibility = View.GONE
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            val m = resources.displayMetrics
            lp.height = minOf(m.widthPixels, m.heightPixels) * 9 / 16
            lp.weight = 0f
            listArea.visibility = View.VISIBLE
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
        videoFrame.layoutParams = lp
    }

    private fun toggleFullscreen() {
        if (isLandscape()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            handler.postDelayed({ requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }, 1500)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun toggleControls() {
        if (controls.visibility == View.VISIBLE) {
            handler.removeCallbacks(hideControls)
            hideControls.run()
        } else {
            showControlsTemporarily()
        }
    }

    private fun showControlsTemporarily() {
        controls.visibility = View.VISIBLE
        titleView.visibility = if (titleView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 3500)
    }

    private fun showStatus(msg: String) {
        statusView.text = msg
        statusView.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------ player

    private fun play(ch: Channel) {
        currentUrl = ch.url
        titleView.text = ch.name
        showStatus("Carregando…")

        httpFactory.setUserAgent(ch.userAgent ?: BROWSER_UA)
        httpFactory.setDefaultRequestProperties(
            if (ch.referrer != null) mapOf("Referer" to ch.referrer) else emptyMap()
        )

        val item = MediaItem.Builder().setUri(ch.url).apply {
            if (ch.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true

        adapter.notifyDataSetChanged()
        showControlsTemporarily()
    }

    private fun step(delta: Int) {
        if (shown.isEmpty()) return
        val idx = shown.indexOfFirst { it.url == currentUrl }
        val target = if (idx < 0) 0 else (idx + delta + shown.size) % shown.size
        play(shown[target])
    }

    // ------------------------------------------------------------ lista de canais

    private fun source() = prefs.getString("source", DEFAULT_SOURCE) ?: DEFAULT_SOURCE

    private fun loadPlaylist() {
        infoView.text = "Carregando lista de canais…"
        val src = source()
        Thread {
            val text: String? = try {
                val conn = URL(src).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("User-Agent", "TVAberta/1.0")
                try {
                    if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
                    conn.inputStream.bufferedReader().use { it.readText() }.also { cacheFile.writeText(it) }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                if (cacheFile.exists()) cacheFile.readText() else null // usa a última lista salva
            }
            val list = text?.let { M3uParser.parse(it) }
            runOnUiThread {
                if (list.isNullOrEmpty()) {
                    infoView.text = "Não foi possível carregar a lista. Verifique a internet ou troque a Fonte."
                } else {
                    all = list
                    group = null
                    groupBtn.text = "Categoria"
                    filter()
                }
            }
        }.start()
    }

    private fun filter() {
        shown.clear()
        shown.addAll(all.filter { ch ->
            (group == null || ch.groups.contains(group)) &&
                (!favOnly || ch.url in favs) &&
                (query.isBlank() || ch.name.contains(query, ignoreCase = true))
        })
        adapter.notifyDataSetChanged()
        infoView.text = "${shown.size} canais" + (group?.let { " • $it" } ?: "")
    }

    private fun showGroupDialog() {
        val groups = all.flatMap { it.groups }.distinct().sorted()
        val labels = (listOf("Todas") + groups).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Categoria")
            .setItems(labels) { _, which ->
                group = if (which == 0) null else groups[which - 1]
                groupBtn.text = group ?: "Categoria"
                filter()
            }
            .show()
    }

    private fun showSourceDialog() {
        val labels = arrayOf(
            "Brasil — iptv-org (padrão)",
            "Brasil — iptv-com (canais abertos, PT-BR)",
            "Outro link M3U…"
        )
        AlertDialog.Builder(this)
            .setTitle("Fonte da lista de canais")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> setSource(DEFAULT_SOURCE)
                    1 -> setSource(ALT_SOURCE)
                    else -> askCustomSource()
                }
            }
            .show()
    }

    private fun askCustomSource() {
        val input = EditText(this).apply {
            hint = "https://…/lista.m3u"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Link da lista M3U")
            .setView(input)
            .setPositiveButton("Carregar") { _, _ ->
                val u = input.text.toString().trim()
                if (u.startsWith("http://") || u.startsWith("https://")) setSource(u)
                else Toast.makeText(this, "Link inválido", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setSource(url: String) {
        prefs.edit().putString("source", url).apply()
        cacheFile.delete()
        loadPlaylist()
    }

    // ------------------------------------------------------------ adapter

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ch = shown[position]
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }
            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this@MainActivity).apply {
                text = ch.name
                textSize = 16f
                setTextColor(if (ch.url == currentUrl) blue else Color.WHITE)
            })
            if (ch.groups.isNotEmpty()) {
                texts.addView(TextView(this@MainActivity).apply {
                    text = ch.groups.joinToString(" • ")
                    textSize = 12f
                    setTextColor(Color.GRAY)
                })
            }
            val star = ImageButton(this@MainActivity).apply {
                setImageResource(
                    if (ch.url in favs) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
                )
                setBackgroundColor(Color.TRANSPARENT)
                isFocusable = false
                setOnClickListener {
                    if (!favs.add(ch.url)) favs.remove(ch.url)
                    prefs.edit().putStringSet("favs", HashSet(favs)).apply()
                    if (favOnly) filter() else adapter.notifyDataSetChanged()
                }
            }
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(star, LinearLayout.LayoutParams(dp(44), dp(44)))
            return row
        }
    }
}
