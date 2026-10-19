package com.example.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
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
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.FileOutputStream

class SettingsActivity : Activity() {

    private val reqPick = 42
    private val reqExport = 43
    private val reqImport = 44
    private lateinit var prefs: Prefs
    private lateinit var lock: LockHelper
    private lateinit var content: LinearLayout
    private var authed = false
    private var adminShown = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        lock = LockHelper(this, prefs)
        authed = savedInstanceState?.getBoolean("authed") ?: false

        // Com apps bloqueados, só abre Personalizar depois de confirmar a senha.
        if (prefs.locked.isNotEmpty() && !authed) {
            lock.authenticate("Personalizar", onCancel = { finish() }) {
                authed = true
                build()
            }
            return
        }
        build()
    }

    override fun onDestroy() {
        super.onDestroy()
        lock.dismiss()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("authed", authed)
    }

    override fun onResume() {
        super.onResume()
        // Depois de ativar/remover o administrador de bloqueio de tela, atualiza o botão.
        if (::content.isInitialized && isAdminActive() != adminShown) recreate()
    }

    private fun adminComponent() = ComponentName(this, LockAdmin::class.java)

    private fun isAdminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(adminComponent())
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
        choiceButton("Linha rápida no topo", Prefs.TOPROW_NAMES, prefs.topRow) { prefs.topRow = it }

        // Gestos
        content.addView(label("Gestos", 18f, true))
        choiceButton("Deslizar para baixo", Prefs.ACTION_NAMES, prefs.gestureDown) { prefs.gestureDown = it }
        choiceButton("Deslizar para cima", Prefs.ACTION_NAMES, prefs.gestureUp) { prefs.gestureUp = it }
        choiceButton("Toque duplo", Prefs.ACTION_NAMES, prefs.gestureDouble) { prefs.gestureDouble = it }
        content.addView(
            label(
                "Deslizar funciona quando a lista está no topo/fim. O toque duplo vale em um " +
                    "espaço vazio da lista (abaixo dos apps).",
                13f
            )
        )
        adminShown = isAdminActive()
        if (adminShown) {
            button("Remover permissão de bloquear a tela") {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.removeActiveAdmin(adminComponent())
                recreate()
            }
        } else {
            button("Ativar permissão de bloquear a tela") {
                startActivity(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
                        .putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Usado apenas para bloquear a tela."
                        )
                )
            }
        }
        content.addView(
            label(
                "Necessária só para a ação \"Bloquear a tela\". Para desinstalar o app, remova " +
                    "esta permissão antes.",
                13f
            )
        )

        // Comportamento
        content.addView(label("Tela inicial", 18f, true))
        toggle("Mostrar relógio e data", prefs.showClock) { prefs.showClock = it }
        toggle("Abrir direto na lista de apps (sem tela inicial)", prefs.startInDrawer) {
            prefs.startInDrawer = it
        }
        content.addView(
            label(
                "Na tela inicial, toque em \"Apps\" ou deslize para cima para abrir a lista.",
                13f
            )
        )

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

        content.addView(
            label(
                "Dica: segure um app para fixar no dock, mover para pasta, bloquear com senha/digital, " +
                    "ocultar, ver informações ou desinstalar. O bloqueio vale para abrir o app por este " +
                    "launcher (não impede abrir por outro launcher ou pelas notificações).",
                13f
            )
        )

        // Bloqueio de apps
        content.addView(label("Bloqueio de apps", 18f, true))
        val ownActive = prefs.lockMode == 1 && prefs.hasPin()
        choiceButton(
            "Senha usada",
            arrayOf("Senha/digital do celular", "Senha própria deste launcher"),
            if (ownActive) 1 else 0
        ) { which ->
            if (which == 1 && !prefs.hasPin()) {
                askNewPin(onDone = {
                    prefs.lockMode = 1
                    recreate()
                }, onCancel = { recreate() })
            } else {
                prefs.lockMode = which
                recreate()
            }
        }
        if (prefs.hasPin()) {
            button("Alterar senha própria") {
                askNewPin(onDone = { toast("Senha alterada") }, onCancel = {})
            }
            button("Remover senha própria") {
                prefs.clearPin()
                prefs.lockMode = 0
                recreate()
            }
        }
        content.addView(
            label(
                "A senha própria é diferente da do celular e fica salva só neste app (apenas o " +
                    "código de verificação, nunca a senha em si). Ela não entra no backup. Se esquecer, " +
                    "só limpando os dados do app em Ajustes do Android (isso apaga as configurações). " +
                    "Após 5 erros, espera 30 segundos.",
                13f
            )
        )

        // Dados
        content.addView(label("Dados", 18f, true))
        button("Gerenciar apps ocultos") { manageHidden() }
        button("Exportar configurações (backup)") { exportBackup() }
        button("Importar configurações") { importBackup() }
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

    // ---------------------------------------------------------------- Imagem de fundo

    private fun pickImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
        try {
            startActivityForResult(i, reqPick)
        } catch (e: Exception) {
            toast("Nenhum seletor de imagens disponível")
        }
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

    // ---------------------------------------------------------------- Backup

    private fun exportBackup() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "meulauncher-backup.json")
        try {
            startActivityForResult(i, reqExport)
        } catch (e: Exception) {
            toast("Não foi possível abrir o seletor de arquivos")
        }
    }

    private fun importBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        try {
            startActivityForResult(i, reqImport)
        } catch (e: Exception) {
            toast("Não foi possível abrir o seletor de arquivos")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (lock.handleResult(requestCode, resultCode)) return
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            reqPick -> Thread {
                val ok = try {
                    saveWallpaper(uri)
                    true
                } catch (e: Throwable) {
                    false
                }
                runOnUiThread {
                    if (ok) recreate() else toast("Não foi possível usar essa imagem")
                }
            }.start()

            reqExport -> Thread {
                val ok = try {
                    contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(prefs.exportJson().toByteArray(Charsets.UTF_8))
                    } != null
                } catch (e: Throwable) {
                    false
                }
                runOnUiThread {
                    toast(if (ok) "Backup salvo" else "Não foi possível salvar o backup")
                }
            }.start()

            reqImport -> Thread {
                val ok = try {
                    val text = contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("vazio")
                    prefs.importJson(text)
                    true
                } catch (e: Throwable) {
                    false
                }
                runOnUiThread {
                    if (ok) {
                        toast("Configurações importadas")
                        recreate()
                    } else {
                        toast("Arquivo de backup inválido")
                    }
                }
            }.start()
        }
    }

    // ---------------------------------------------------------------- Outros

    private fun askNewPin(onDone: () -> Unit, onCancel: () -> Unit) {
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val a = EditText(this).apply {
            hint = "Nova senha (mín. 4 caracteres)"
            inputType = type
            setSingleLine()
        }
        val b = EditText(this).apply {
            hint = "Repita a senha"
            inputType = type
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(a)
            addView(b)
        }
        var saved = false
        val d = AlertDialog.Builder(this)
            .setTitle("Senha própria")
            .setView(box)
            .setPositiveButton("Salvar", null)
            .setNegativeButton("Cancelar", null)
            .setOnDismissListener {
                if (!saved && !isDestroyed) onCancel()
            }
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p1 = a.text.toString()
                val p2 = b.text.toString()
                if (p1.length < 4) {
                    a.error = "Mínimo de 4 caracteres"
                } else if (p1 != p2) {
                    b.error = "As senhas não são iguais"
                } else {
                    prefs.setPin(p1)
                    saved = true
                    d.dismiss()
                    onDone()
                }
            }
        }
        d.show()
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
                toast("Abra Ajustes > Apps > Apps padrão > Tela inicial")
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
            toast("Nenhum app oculto")
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
