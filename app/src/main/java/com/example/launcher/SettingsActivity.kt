package com.example.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.FileOutputStream

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        build()
    }

    private fun label(t: String, size: Float = 16f, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = size
        tv.setTextColor(prefs.fg())
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setPadding(0, dp(14), 0, dp(4))
        return tv
    }

    private fun build() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        val scroll = ScrollView(this)
        scroll.addView(content)
        scroll.setBackgroundColor(prefs.bg())
        window.statusBarColor = prefs.bg()
        setContentView(scroll)

        content.addView(label("Personalizar", 24f, true))

        // Aparência
        content.addView(label("Aparência", 18f, true))
        choiceButton("Tema", Prefs.THEME_NAMES, prefs.theme) {
            prefs.theme = it
            recreate()
        }
        content.addView(label("Cor de destaque"))
        accentRow()

        // Imagem de fundo
        content.addView(label("Imagem de fundo", 18f, true))
        button(if (prefs.hasWallpaper()) "Trocar imagem" else "Escolher imagem") { pickImage() }
        if (prefs.hasWallpaper()) {
            slider("Escurecer imagem", 0, 80, prefs.dim, "%") { prefs.dim = it }
            button("Remover imagem") {
                prefs.clearWallpaper()
                recreate()
            }
        }

        // Grade
        content.addView(label("Grade de apps", 18f, true))
        slider("Colunas", 3, 6, prefs.columns, "") { prefs.columns = it }
        slider("Tamanho do ícone", 40, 96, prefs.iconSize, " dp") { prefs.iconSize = it }
        toggle("Mostrar nomes dos apps", prefs.showLabels) { prefs.showLabels = it }
        choiceButton("Ordenar por", Prefs.SORT_NAMES, prefs.sort) { prefs.sort = it }

        // Comportamento
        content.addView(label("Comportamento", 18f, true))
        toggle("Mostrar dock (apps fixados)", prefs.showDock) { prefs.showDock = it }
        toggle("Fechar o launcher ao abrir um app", prefs.closeAfterLaunch) {
            prefs.closeAfterLaunch = it
        }
        // Launcher padrão (opcional)
        content.addView(label("Launcher padrão (opcional)", 18f, true))
        toggle("Permitir usar como tela inicial", prefs.isHomeAllowed()) {
            prefs.setHomeAllowed(it)
            recreate()
        }
        if (prefs.isHomeAllowed()) {
            button("Escolher launcher padrão do sistema") { openHomeChooser() }
            content.addView(label("Para voltar ao normal, escolha outro launcher ou desligue o interruptor acima.", 13f))
        } else {
            content.addView(label("Desligado: o app funciona só como app comum e nunca vira a tela inicial.", 13f))
        }

        content.addView(label("Dica: segure um app para fixar no dock, ocultar, ver informações ou desinstalar.", 13f))

        // Ocultos / reset
        content.addView(label("Dados", 18f, true))
        button("Gerenciar apps ocultos") { manageHidden() }
        button("Restaurar padrões") {
            prefs.reset()
            recreate()
        }
    }

    private fun button(text: String, onClick: () -> Unit) {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        content.addView(b, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun choiceButton(title: String, names: Array<String>, current: Int, onPick: (Int) -> Unit) {
        val b = Button(this)
        b.isAllCaps = false
        b.text = "$title: ${names[current.coerceIn(0, names.size - 1)]}"
        b.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(names, current) { d, which ->
                    d.dismiss()
                    onPick(which)
                    b.text = "$title: ${names[which]}"
                }
                .show()
        }
        content.addView(b, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun slider(title: String, lo: Int, hi: Int, value: Int, unit: String, onChange: (Int) -> Unit) {
        val tv = label("$title: $value$unit")
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = value - lo
        sb.progressTintList = ColorStateList.valueOf(prefs.accentColor())
        sb.thumbTintList = ColorStateList.valueOf(prefs.accentColor())
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + lo
                tv.text = "$title: $v$unit"
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        content.addView(tv)
        content.addView(sb)
    }

    private fun toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = Switch(this)
        sw.text = title
        sw.isChecked = checked
        sw.setTextColor(prefs.fg())
        sw.setPadding(0, dp(10), 0, dp(10))
        sw.setOnCheckedChangeListener { _, b -> onChange(b) }
        content.addView(sw)
    }

    private fun accentRow() {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(4), 0, dp(4))
        Prefs.ACCENTS.forEachIndexed { i, c ->
            val v = View(this)
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(c)
            if (i == prefs.accent) d.setStroke(dp(3), prefs.fg())
            v.background = d
            v.setOnClickListener {
                prefs.accent = i
                recreate()
            }
            val lp = LinearLayout.LayoutParams(dp(40), dp(40))
            lp.rightMargin = dp(10)
            row.addView(v, lp)
        }
        content.addView(row)
    }

    private val pickRequest = 42

    private fun pickImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        try {
            startActivityForResult(i, pickRequest)
        } catch (e: Exception) {
            Toast.makeText(this, "Nenhum seletor de imagens disponível", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val ok = try {
                saveWallpaper(uri)
                true
            } catch (e: Throwable) {
                false
            }
            runOnUiThread {
                if (ok) {
                    recreate()
                } else {
                    Toast.makeText(this, "Não foi possível usar essa imagem", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Copia a imagem para o armazenamento do app, reduzida ao tamanho da tela. */
    private fun saveWallpaper(uri: Uri) {
        val dm = resources.displayMetrics
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= dm.widthPixels &&
            bounds.outHeight / (sample * 2) >= dm.heightPixels
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("decode")

        val orientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val m = Matrix()
            m.postRotate(deg)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        FileOutputStream(prefs.wallpaperFile()).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private fun openHomeChooser() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            try {
                // Fallback: o toque no botão Início abre o seletor de launcher.
                startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                Toast.makeText(this, "Abra Ajustes > Apps > Apps padrão > Tela inicial", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun manageHidden() {
        val hidden = prefs.hidden.toList()
        if (hidden.isEmpty()) {
            Toast.makeText(this, "Nenhum app oculto", Toast.LENGTH_SHORT).show()
            return
        }
        val names = hidden.map { labelOf(it) }.toTypedArray()
        val checked = BooleanArray(hidden.size) { true }
        AlertDialog.Builder(this)
            .setTitle("Desmarque para mostrar de novo")
            .setMultiChoiceItems(names, checked) { _, i, b -> checked[i] = b }
            .setPositiveButton("OK") { _, _ ->
                prefs.hidden = hidden.filterIndexed { i, _ -> checked[i] }.toSet()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
