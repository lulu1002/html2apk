package com.example.minicut

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.*
import androidx.media3.ui.PlayerView
import com.google.common.collect.ImmutableList
import java.io.File

data class Clip(val uri: Uri, val startMs: Long, val endMs: Long)

class MainActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var startBar: SeekBar
    private lateinit var endBar: SeekBar
    private lateinit var info: TextView
    private lateinit var timelineLabel: TextView
    private lateinit var muteCheck: CheckBox
    private lateinit var grayCheck: CheckBox
    private lateinit var exportBtn: Button
    private lateinit var speedSpinner: Spinner
    private lateinit var rotateSpinner: Spinner
    private lateinit var ratioSpinner: Spinner
    private lateinit var transitionSpinner: Spinner
    private lateinit var brightnessBar: SeekBar
    private lateinit var contrastBar: SeekBar
    private lateinit var videoVolBar: SeekBar
    private lateinit var musicVolBar: SeekBar
    private lateinit var textInput: EditText
    private lateinit var textPosSpinner: Spinner
    private lateinit var textColorSpinner: Spinner
    private lateinit var stickerLabel: TextView
    private lateinit var stickerPosSpinner: Spinner
    private lateinit var stickerSizeSpinner: Spinner
    private lateinit var musicLabel: TextView

    private var videoUri: Uri? = null
    private var musicUri: Uri? = null
    private var stickerBitmap: Bitmap? = null
    private var durationMs = 0L
    private val timeline = mutableListOf<Clip>()
    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val speeds = listOf("0.25x" to 0.25f, "0.5x" to 0.5f, "1x" to 1f, "1.5x" to 1.5f, "2x" to 2f, "3x" to 3f)
    private val rotations = listOf("0°" to 0f, "90°" to 90f, "180°" to 180f, "270°" to 270f)
    private val ratios = listOf("Original" to 0f, "1:1 (Instagram)" to 1f, "9:16 (Stories/TikTok)" to 9f / 16f,
        "16:9 (YouTube)" to 16f / 9f, "4:5" to 0.8f)
    private val textPositions = listOf("Embaixo" to -0.8f, "Centro" to 0f, "Em cima" to 0.8f)
    private val textColors = listOf("Branco" to Color.WHITE, "Amarelo" to Color.YELLOW, "Vermelho" to Color.RED,
        "Verde" to Color.GREEN, "Azul" to Color.CYAN)
    private val transitions = listOf("Nenhuma (corte seco)", "Zoom", "Deslizar")
    private val stickerPositions = listOf(
        "Canto superior esquerdo" to (-0.7f to 0.7f), "Canto superior direito" to (0.7f to 0.7f),
        "Centro" to (0f to 0f),
        "Canto inferior esquerdo" to (-0.7f to -0.7f), "Canto inferior direito" to (0.7f to -0.7f)
    )
    private val stickerSizes = listOf("Pequeno" to 0.2f, "Médio" to 0.35f, "Grande" to 0.5f)

    private val videoPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadVideo(uri)
    }
    private val musicPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            musicUri = uri
            musicLabel.text = "Música: selecionada ✓"
        }
    }
    private val stickerPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            stickerBitmap = loadBitmap(uri)
            stickerLabel.text = if (stickerBitmap != null) "Sticker: selecionado ✓" else "Sticker: falha ao abrir imagem"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && player.duration > 0) {
                    durationMs = player.duration
                    updateInfo()
                }
            }
        })
        buildUi()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(24))
        }
        playerView = PlayerView(this).apply {
            this.player = this@MainActivity.player
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260))
        }
        info = TextView(this).apply { text = "Nenhum vídeo selecionado"; gravity = Gravity.CENTER }

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    if (startBar.progress >= endBar.progress) {
                        if (s === startBar) startBar.progress = (endBar.progress - 10).coerceAtLeast(0)
                        else endBar.progress = (startBar.progress + 10).coerceAtMost(1000)
                    }
                    updateInfo()
                    if (s === startBar) player.seekTo(startMs())
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        startBar = SeekBar(this).apply { max = 1000; progress = 0; setOnSeekBarChangeListener(seekListener) }
        endBar = SeekBar(this).apply { max = 1000; progress = 1000; setOnSeekBarChangeListener(seekListener) }

        timelineLabel = TextView(this).apply { text = "Timeline: vazia (exporta só o vídeo atual)" }
        transitionSpinner = spinner(transitions)
        speedSpinner = spinner(speeds.map { it.first }).apply { setSelection(2) }
        rotateSpinner = spinner(rotations.map { it.first })
        ratioSpinner = spinner(ratios.map { it.first })
        brightnessBar = SeekBar(this).apply { max = 200; progress = 100 }
        contrastBar = SeekBar(this).apply { max = 200; progress = 100 }
        muteCheck = CheckBox(this).apply { text = "Remover áudio original" }
        grayCheck = CheckBox(this).apply { text = "Filtro preto e branco" }
        videoVolBar = SeekBar(this).apply { max = 200; progress = 100 }
        musicVolBar = SeekBar(this).apply { max = 200; progress = 100 }

        textInput = EditText(this).apply { hint = "Texto / emoji sobre o vídeo (opcional)" }
        textPosSpinner = spinner(textPositions.map { it.first })
        textColorSpinner = spinner(textColors.map { it.first })

        stickerLabel = TextView(this).apply { text = "Sticker: nenhum" }
        stickerPosSpinner = spinner(stickerPositions.map { it.first })
        stickerSizeSpinner = spinner(stickerSizes.map { it.first }).apply { setSelection(1) }
        musicLabel = TextView(this).apply { text = "Música: nenhuma" }

        exportBtn = button("Exportar") { export() }

        root.addView(button("Escolher vídeo") { videoPicker.launch("video/*") })
        root.addView(playerView)
        root.addView(info)
        root.addView(label("Início do corte")); root.addView(startBar)
        root.addView(label("Fim do corte")); root.addView(endBar)

        root.addView(label("Vários vídeos"))
        root.addView(timelineLabel)
        root.addView(button("Adicionar este corte à timeline") { addToTimeline() })
        root.addView(button("Limpar timeline") { timeline.clear(); updateTimelineLabel() })
        root.addView(label("Transição entre clipes")); root.addView(transitionSpinner)

        root.addView(label("Velocidade")); root.addView(speedSpinner)
        root.addView(label("Girar")); root.addView(rotateSpinner)
        root.addView(label("Proporção (corta as bordas)")); root.addView(ratioSpinner)
        root.addView(label("Brilho")); root.addView(brightnessBar)
        root.addView(label("Contraste")); root.addView(contrastBar)
        root.addView(grayCheck)

        root.addView(label("Texto"))
        root.addView(textInput)
        root.addView(textPosSpinner)
        root.addView(textColorSpinner)

        root.addView(label("Sticker (imagem)"))
        root.addView(stickerLabel)
        root.addView(button("Escolher imagem") { stickerPicker.launch("image/*") })
        root.addView(button("Remover sticker") { stickerBitmap = null; stickerLabel.text = "Sticker: nenhum" })
        root.addView(stickerPosSpinner)
        root.addView(stickerSizeSpinner)

        root.addView(label("Áudio"))
        root.addView(muteCheck)
        root.addView(label("Volume do vídeo (0–200%)")); root.addView(videoVolBar)
        root.addView(musicLabel)
        root.addView(button("Adicionar música") { musicPicker.launch("audio/*") })
        root.addView(button("Remover música") { musicUri = null; musicLabel.text = "Música: nenhuma" })
        root.addView(label("Volume da música (0–200%)")); root.addView(musicVolBar)

        root.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        root.addView(exportBtn)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun loadVideo(uri: Uri) {
        videoUri = uri
        startBar.progress = 0
        endBar.progress = 1000
        durationMs = 0
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    private fun startMs() = durationMs * startBar.progress / 1000
    private fun endMs() = durationMs * endBar.progress / 1000

    private fun updateInfo() {
        info.text = "Corte: %.1fs → %.1fs (total %.1fs)".format(
            startMs() / 1000f, endMs() / 1000f, durationMs / 1000f
        )
    }

    private fun addToTimeline() {
        val uri = videoUri
        if (uri == null || durationMs <= 0) {
            Toast.makeText(this, "Escolha um vídeo primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        timeline.add(Clip(uri, startMs(), endMs()))
        updateTimelineLabel()
    }

    private fun updateTimelineLabel() {
        timelineLabel.text = if (timeline.isEmpty()) "Timeline: vazia (exporta só o vídeo atual)"
        else "Timeline: ${timeline.size} clipe(s), %.1fs no total".format(
            timeline.sumOf { it.endMs - it.startMs } / 1000f
        )
    }

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }

    private fun volumeProcessor(volume: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(volume))
        putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(volume))
    }

    /** Efeito de entrada nos primeiros 0,5 s de cada clipe (a partir do segundo). */
    private fun transitionEffect(kind: Int): Effect {
        val durationUs = 500_000f
        return MatrixTransformation { timeUs ->
            val p = (timeUs / durationUs).coerceIn(0f, 1f)
            Matrix().apply {
                if (kind == 1) {
                    val s = p.coerceAtLeast(0.02f)
                    postScale(s, s)
                } else {
                    postTranslate((1f - p) * 2f, 0f)
                }
            }
        }
    }

    private fun export() {
        val clips: List<Clip> = if (timeline.isNotEmpty()) timeline.toList() else {
            val uri = videoUri
            if (uri == null || durationMs <= 0) {
                Toast.makeText(this, "Escolha um vídeo primeiro", Toast.LENGTH_SHORT).show()
                return
            }
            listOf(Clip(uri, startMs(), endMs()))
        }
        player.pause()
        exportBtn.isEnabled = false
        exportBtn.text = "Exportando..."

        val speed = speeds[speedSpinner.selectedItemPosition].second
        val rotation = rotations[rotateSpinner.selectedItemPosition].second
        val ratio = ratios[ratioSpinner.selectedItemPosition].second
        val brightness = (brightnessBar.progress - 100) / 100f
        val contrast = (contrastBar.progress - 100) / 100f
        val videoVol = videoVolBar.progress / 100f
        val musicVol = musicVolBar.progress / 100f
        val transition = transitionSpinner.selectedItemPosition
        val text = textInput.text.toString().trim()

        val items = clips.mapIndexed { index, c ->
            val clipCfg = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(c.startMs)
                .setEndPositionMs(c.endMs)
                .build()
            val mediaItem = MediaItem.Builder().setUri(c.uri).setClippingConfiguration(clipCfg).build()

            val audioProcessors = mutableListOf<AudioProcessor>()
            val videoEffects = mutableListOf<Effect>()

            if (speed != 1f) {
                videoEffects.add(SpeedChangeEffect(speed))
                audioProcessors.add(SonicAudioProcessor().apply { setSpeed(speed) })
            }
            if (videoVol != 1f) audioProcessors.add(volumeProcessor(videoVol))
            if (brightness != 0f) videoEffects.add(Brightness(brightness))
            if (contrast != 0f) videoEffects.add(Contrast(contrast))
            if (grayCheck.isChecked) videoEffects.add(RgbFilter.createGrayscaleFilter())
            if (rotation != 0f) {
                videoEffects.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(rotation).build())
            }
            if (index > 0 && transition != 0) videoEffects.add(transitionEffect(transition))

            EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(muteCheck.isChecked)
                .setEffects(Effects(audioProcessors, videoEffects))
                .build()
        }

        val sequences = mutableListOf(EditedMediaItemSequence(items))

        val totalOutputMs = (clips.sumOf { it.endMs - it.startMs } / speed).toLong()
        val music = musicUri
        if (music != null) {
            val musicClip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(0)
                .setEndPositionMs(totalOutputMs)
                .build()
            val musicProcessors = mutableListOf<AudioProcessor>()
            if (musicVol != 1f) musicProcessors.add(volumeProcessor(musicVol))
            val musicItem = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(music).setClippingConfiguration(musicClip).build()
            ).setEffects(Effects(musicProcessors, emptyList())).build()
            sequences.add(EditedMediaItemSequence(listOf(musicItem)))
        }

        // Efeitos aplicados no vídeo final (depois de juntar os clipes)
        val compositionEffects = mutableListOf<Effect>()
        if (ratio != 0f) {
            compositionEffects.add(
                Presentation.createForAspectRatio(ratio, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            )
        }
        val overlays = mutableListOf<TextureOverlay>()
        val sticker = stickerBitmap
        if (sticker != null) {
            val (x, y) = stickerPositions[stickerPosSpinner.selectedItemPosition].second
            val size = stickerSizes[stickerSizeSpinner.selectedItemPosition].second
            val settings = OverlaySettings.Builder()
                .setBackgroundFrameAnchor(x, y)
                .setScale(size, size)
                .build()
            overlays.add(BitmapOverlay.createStaticBitmapOverlay(sticker, settings))
        }
        if (text.isNotEmpty()) {
            val color = textColors[textColorSpinner.selectedItemPosition].second
            val y = textPositions[textPosSpinner.selectedItemPosition].second
            val span = SpannableString(text).apply {
                setSpan(ForegroundColorSpan(color), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(AbsoluteSizeSpan(72), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val settings = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, y).build()
            overlays.add(TextOverlay.createStaticTextOverlay(span, settings))
        }
        if (overlays.isNotEmpty()) compositionEffects.add(OverlayEffect(ImmutableList.copyOf(overlays)))

        val composition = Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), compositionEffects))
            .build()
        val out = File(cacheDir, "export_${System.currentTimeMillis()}.mp4")

        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    saveToGallery(out)
                    finishExport("Vídeo salvo em Filmes/MiniCut")
                }
                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    finishExport("Erro: ${exportException.message}")
                }
            })
            .build()
        transformer = t
        t.start(composition, out.absolutePath)
        pollProgress()
    }

    private fun pollProgress() {
        val t = transformer ?: return
        val holder = ProgressHolder()
        val state = t.getProgress(holder)
        if (state == Transformer.PROGRESS_STATE_NOT_STARTED) return
        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
            exportBtn.text = "Exportando... ${holder.progress}%"
        }
        handler.postDelayed({ pollProgress() }, 500)
    }

    private fun saveToGallery(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "MiniCut_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MiniCut")
        }
        val dest = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(dest)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        file.delete()
    }

    private fun finishExport(msg: String) {
        transformer = null
        handler.removeCallbacksAndMessages(null)
        exportBtn.isEnabled = true
        exportBtn.text = "Exportar"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        transformer?.cancel()
        player.release()
        super.onDestroy()
    }
}
