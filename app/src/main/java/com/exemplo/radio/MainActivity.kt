package com.exemplo.radio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : Activity() {

    private lateinit var stations: MutableList<Station>
    private lateinit var adapter: StationAdapter
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var sleepBtn: Button

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var currentUrl: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    private val green = Color.parseColor("#1DB954")

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateUi()
        override fun onPlaybackStateChanged(playbackState: Int) = updateUi()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateUi()
        override fun onPlayerError(error: PlaybackException) {
            statusView.text = "Erro ao reproduzir (${error.errorCodeName})"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stations = StationStore.load(this)
        buildUi()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(playerListener)
                updateUi()
            } catch (e: Exception) {
                statusView.text = "Não foi possível conectar ao player"
            }
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(dp(16), dp(24), dp(16), dp(8))
        }

        root.addView(TextView(this).apply {
            text = "Rádio"
            textSize = 28f
            setTextColor(Color.WHITE)
        })

        nameView = TextView(this).apply {
            text = "Selecione uma estação"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, dp(24), 0, 0)
        }
        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(nameView)
        root.addView(statusView)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = iconButton(android.R.drawable.ic_media_previous) { step(-1) }
        playBtn = iconButton(android.R.drawable.ic_media_play) { togglePlay() }
        val next = iconButton(android.R.drawable.ic_media_next) { step(1) }
        controls.addView(prev, LinearLayout.LayoutParams(dp(72), dp(72)))
        controls.addView(playBtn, LinearLayout.LayoutParams(dp(88), dp(88)))
        controls.addView(next, LinearLayout.LayoutParams(dp(72), dp(72)))
        root.addView(controls)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        sleepBtn = Button(this).apply {
            text = "Timer: desligado"
            setOnClickListener { showSleepDialog() }
        }
        val addBtn = Button(this).apply {
            text = "+ Estação"
            setOnClickListener { showAddDialog() }
        }
        actions.addView(sleepBtn)
        actions.addView(addBtn)
        root.addView(actions)

        adapter = StationAdapter()
        val listView = ListView(this).apply {
            this.adapter = this@MainActivity.adapter
            divider = null
            setOnItemClickListener { _, _, pos, _ -> play(stations[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(pos); true }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun iconButton(icon: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setOnClickListener { onClick() }
    }

    private fun updateUi() {
        val c = controller
        val item = c?.currentMediaItem
        currentUrl = item?.mediaId?.takeIf { it.isNotEmpty() }
        val station = stations.firstOrNull { it.url == currentUrl }

        nameView.text = station?.name ?: "Selecione uma estação"
        val playing = c?.isPlaying == true
        playBtn.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )

        var status = when {
            c == null || station == null -> ""
            c.playbackState == Player.STATE_BUFFERING -> "Carregando…"
            playing -> "Tocando"
            else -> "Pausado"
        }
        val song = c?.mediaMetadata?.title?.toString()
        if (playing && !song.isNullOrBlank() && song != station?.name) status += "  •  $song"
        statusView.text = status

        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- Player

    private fun play(station: Station) {
        val c = controller ?: run {
            Toast.makeText(this, "Player ainda conectando…", Toast.LENGTH_SHORT).show()
            return
        }
        val item = MediaItem.Builder()
            .setMediaId(station.url)
            .setUri(station.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setArtist("Rádio")
                    .build()
            )
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    private fun togglePlay() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            c.mediaItemCount > 0 -> c.play()
            stations.isNotEmpty() -> play(stations[0])
        }
    }

    private fun step(delta: Int) {
        if (stations.isEmpty()) return
        val idx = stations.indexOfFirst { it.url == currentUrl }
        val target = when {
            idx < 0 -> if (delta > 0) 0 else stations.size - 1
            else -> (idx + delta + stations.size) % stations.size
        }
        play(stations[target])
    }

    // ---------------------------------------------------------------- Dialogs

    private fun showSleepDialog() {
        val labels = arrayOf("Desligado", "15 minutos", "30 minutos", "60 minutos")
        val minutes = intArrayOf(0, 15, 30, 60)
        AlertDialog.Builder(this)
            .setTitle("Desligar automaticamente")
            .setItems(labels) { _, which ->
                sleepRunnable?.let { handler.removeCallbacks(it) }
                sleepRunnable = null
                if (minutes[which] == 0) {
                    sleepBtn.text = "Timer: desligado"
                } else {
                    sleepBtn.text = "Timer: ${minutes[which]} min"
                    val r = Runnable {
                        controller?.pause()
                        sleepBtn.text = "Timer: desligado"
                    }
                    sleepRunnable = r
                    handler.postDelayed(r, minutes[which] * 60_000L)
                }
            }
            .show()
    }

    private fun showAddDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Nome da estação"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val urlInput = EditText(this).apply {
            hint = "URL do stream (http/https)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(nameInput)
        box.addView(urlInput)
        AlertDialog.Builder(this)
            .setTitle("Adicionar estação")
            .setView(box)
            .setPositiveButton("Adicionar") { _, _ ->
                val n = nameInput.text.toString().trim()
                val u = urlInput.text.toString().trim()
                if (n.isEmpty() || !(u.startsWith("http://") || u.startsWith("https://"))) {
                    Toast.makeText(this, "Nome e URL válidos são obrigatórios", Toast.LENGTH_SHORT).show()
                } else {
                    stations.add(Station(n, u))
                    persist()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete(pos: Int) {
        val s = stations[pos]
        AlertDialog.Builder(this)
            .setTitle("Remover estação?")
            .setMessage(s.name)
            .setPositiveButton("Remover") { _, _ ->
                stations.removeAt(pos)
                persist()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun persist() {
        StationStore.save(this, stations)
        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- Adapter

    private inner class StationAdapter : BaseAdapter() {
        override fun getCount() = stations.size
        override fun getItem(position: Int) = stations[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val s = stations[position]
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
            }
            val name = TextView(this@MainActivity).apply {
                text = s.name
                textSize = 17f
                setTextColor(if (s.url == currentUrl) green else Color.WHITE)
            }
            val star = ImageButton(this@MainActivity).apply {
                setImageResource(
                    if (s.fav) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
                )
                setBackgroundColor(Color.TRANSPARENT)
                isFocusable = false
                setOnClickListener {
                    s.fav = !s.fav
                    stations.sortByDescending { it.fav } // favoritas no topo (ordenação estável)
                    persist()
                }
            }
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(star, LinearLayout.LayoutParams(dp(44), dp(44)))
            return row
        }
    }
}
