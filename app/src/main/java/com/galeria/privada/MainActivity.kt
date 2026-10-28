package com.galeria.privada

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: MediaAdapter
    private lateinit var root: View
    private lateinit var empty: View
    private lateinit var tabs: TabLayout
    private lateinit var chips: ChipGroup
    private lateinit var tvTitle: TextView
    private lateinit var tvCount: TextView
    private lateinit var btnDelete: ImageButton
    private lateinit var btnExport: ImageButton
    private lateinit var btnMove: ImageButton

    private var tabVideo = false
    private var selectedAlbum: String? = null
    private var totalCount = 0
    private var pendingExport: (() -> Unit)? = null

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) importAll(uris) }

    private val changeProtectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> if (result.resultCode == RESULT_OK) Toast.makeText(this, "Proteção atualizada", Toast.LENGTH_SHORT).show() }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingExport?.invoke()
        else Toast.makeText(this, "Sem permissão para salvar no celular", Toast.LENGTH_SHORT).show()
        pendingExport = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        empty = findViewById(R.id.empty)
        tabs = findViewById(R.id.tabs)
        chips = findViewById(R.id.chips)
        tvTitle = findViewById(R.id.tvTitle)
        tvCount = findViewById(R.id.tvCount)
        btnDelete = findViewById(R.id.btnDelete)
        btnExport = findViewById(R.id.btnExport)
        btnMove = findViewById(R.id.btnMove)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val fab = findViewById<ExtendedFloatingActionButton>(R.id.fabAdd)

        adapter = MediaAdapter(
            onClick = { i -> if (adapter.selecting) toggle(i) else open(i) },
            onLong = { i -> toggle(i) }
        )
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter

        tabs.addTab(tabs.newTab().setText("Fotos"))
        tabs.addTab(tabs.newTab().setText("Vídeos"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tabVideo = tab?.position == 1
                selectedAlbum = null
                adapter.clearSelection()
                reload()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        fab.setOnClickListener { picker.launch(arrayOf("image/*", "video/*")) }
        btnDelete.setOnClickListener { confirmDelete() }
        btnExport.setOnClickListener { askExport() }
        btnMove.setOnClickListener { askMove() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        onBackPressedDispatcher.addCallback(this) {
            if (adapter.selecting) {
                adapter.clearSelection()
                updateHeader()
            } else {
                finish()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!Session.appUnlocked) {
            root.visibility = View.INVISIBLE
            startActivity(
                Intent(this, UnlockActivity::class.java).putExtra(UnlockActivity.EXTRA_APP_MODE, true)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (Session.appUnlocked) {
            root.visibility = View.VISIBLE
            reload()
        }
    }

    private fun reload() {
        val all = Vault.list(this)
        val videos = all.filter { Vault.isVideo(it) }
        val photos = all.filter { !Vault.isVideo(it) }
        totalCount = all.size
        tabs.getTabAt(0)?.text = "Fotos (${photos.size})"
        tabs.getTabAt(1)?.text = "Vídeos (${videos.size})"

        val base = if (tabVideo) videos else photos
        val albums = Albums.list(this, tabVideo)
        val assign = Albums.assignments(this)
        if (selectedAlbum != null && selectedAlbum !in albums) selectedAlbum = null
        val album = selectedAlbum
        val current = if (album == null) base else base.filter { assign[it.name] == album }

        buildChips(albums, base, assign)
        adapter.submit(current)
        empty.visibility = if (current.isEmpty()) View.VISIBLE else View.GONE
        updateHeader()
    }

    private fun buildChips(albums: List<String>, base: List<File>, assign: Map<String, String>) {
        chips.removeAllViews()
        fun chip(label: String, checked: Boolean, checkable: Boolean): Chip {
            val c = layoutInflater.inflate(R.layout.item_chip, chips, false) as Chip
            c.text = label
            c.isCheckable = checkable
            c.isChecked = checked
            chips.addView(c)
            return c
        }
        chip("Todos (${base.size})", selectedAlbum == null, true).setOnClickListener {
            selectedAlbum = null
            adapter.clearSelection()
            reload()
        }
        albums.forEach { name ->
            val n = base.count { assign[it.name] == name }
            val c = chip("$name ($n)", selectedAlbum == name, true)
            c.setOnClickListener {
                selectedAlbum = name
                adapter.clearSelection()
                reload()
            }
            c.setOnLongClickListener {
                manageAlbum(name)
                true
            }
        }
        chip("+ Novo álbum", false, false).setOnClickListener { askNewAlbum(null) }
    }

    private fun updateHeader() {
        if (adapter.selecting) {
            tvTitle.text = "${adapter.selectedCount} selecionado(s)"
            tvCount.text = "Toque para selecionar mais"
            btnDelete.visibility = View.VISIBLE
            btnExport.visibility = View.VISIBLE
            btnMove.visibility = View.VISIBLE
        } else {
            tvTitle.text = "Galeria Privada"
            tvCount.text = "$totalCount itens protegidos"
            btnDelete.visibility = View.GONE
            btnExport.visibility = View.GONE
            btnMove.visibility = View.GONE
        }
    }

    private fun toggle(i: Int) {
        adapter.toggle(i)
        updateHeader()
    }

    private fun open(i: Int) {
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .putExtra("index", i)
                .putExtra("video", tabVideo)
                .putExtra("album", selectedAlbum)
        )
    }

    // ---------- Álbuns ----------

    private fun nameDialog(title: String, initial: String, positive: String, onOk: (String) -> String?) {
        val input = EditText(this).apply {
            hint = "Nome do álbum"
            setText(initial)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(positive, null)
            .setNegativeButton("Cancelar", null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val err = onOk(input.text.toString().trim())
            if (err != null) Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            else dialog.dismiss()
        }
    }

    private fun askNewAlbum(onCreated: ((String) -> Unit)?) {
        nameDialog("Novo álbum", "", "Criar") { name ->
            val err = Albums.add(this, tabVideo, name)
            if (err == null) {
                if (onCreated != null) {
                    onCreated(name)
                } else {
                    selectedAlbum = name
                    reload()
                }
            }
            err
        }
    }

    private fun manageAlbum(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setItems(arrayOf("Renomear", "Excluir álbum")) { _, which ->
                if (which == 0) {
                    nameDialog("Renomear álbum", name, "Salvar") { novo ->
                        val err = Albums.rename(this, tabVideo, name, novo)
                        if (err == null) {
                            if (selectedAlbum == name) selectedAlbum = novo
                            reload()
                        }
                        err
                    }
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Excluir álbum")
                        .setMessage("Os arquivos não são apagados: voltam para \"Todos\".")
                        .setPositiveButton("Excluir") { _, _ ->
                            Albums.delete(this, tabVideo, name)
                            if (selectedAlbum == name) selectedAlbum = null
                            reload()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
            .show()
    }

    private fun askMove() {
        val albums = Albums.list(this, tabVideo)
        val labels = mutableListOf("Todos (sem álbum)")
        labels.addAll(albums)
        labels.add("+ Novo álbum…")
        MaterialAlertDialogBuilder(this)
            .setTitle("Mover para álbum")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> doMove(null)
                    which == labels.size - 1 -> askNewAlbum { doMove(it) }
                    else -> doMove(albums[which - 1])
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doMove(album: String?) {
        val files = adapter.selectedFiles()
        Albums.move(this, files, album)
        adapter.clearSelection()
        reload()
        Toast.makeText(
            this,
            "${files.size} item(ns) movido(s) para ${album ?: "Todos"}",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ---------- Importar / exportar / excluir ----------

    private fun importAll(uris: List<Uri>) {
        Toast.makeText(this, "Importando…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread {
            var ok = 0
            uris.forEach { if (Vault.import(app, it)) ok++ }
            Vault.notifyChanged(app)
            runOnUiThread {
                reload()
                Toast.makeText(
                    this,
                    "$ok item(ns) protegido(s). Os originais continuam na galeria normal — apague-os se quiser.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun askExportZip() {
        if (totalCount == 0) {
            Toast.makeText(this, "Não há nada para exportar", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Exportar tudo em .zip")
            .setMessage(
                "$totalCount item(ns) vão para um único arquivo .zip em Download > GaleriaPrivada. " +
                    "Os arquivos continuam protegidos aqui dentro."
            )
            .setPositiveButton("Exportar") { _, _ -> exportZip() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun exportZip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExport = { exportZip() }
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val app = applicationContext
        Toast.makeText(this, "Gerando .zip…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = Vault.exportAllZip(app)
            runOnUiThread {
                when (result) {
                    is Vault.ZipResult.Ok ->
                        Toast.makeText(this, "Salvo em Download/GaleriaPrivada/${result.name}", Toast.LENGTH_LONG).show()
                    is Vault.ZipResult.Error ->
                        Toast.makeText(this, "Falha ao exportar: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun askExport() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Devolver ao celular")
            .setMessage(
                "${adapter.selectedCount} item(ns) vão para Imagens/Vídeos > GaleriaPrivada.\n\n" +
                    "Baixar: mantém uma cópia na galeria privada.\n" +
                    "Mover: tira da galeria privada."
            )
            .setPositiveButton("Mover") { _, _ -> export(true) }
            .setNeutralButton("Baixar") { _, _ -> export(false) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun export(move: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExport = { export(move) }
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val files = adapter.selectedFiles()
        val app = applicationContext
        Toast.makeText(this, "Exportando…", Toast.LENGTH_SHORT).show()
        Thread {
            var ok = 0
            val removed = mutableListOf<String>()
            files.forEach {
                if (Vault.exportToPhone(app, it)) {
                    ok++
                    if (move && it.delete()) removed.add(it.name)
                }
            }
            Albums.forget(app, removed)
            Vault.notifyChanged(app)
            runOnUiThread {
                adapter.clearSelection()
                reload()
                val verbo = if (move) "movido(s)" else "baixado(s)"
                val falhas = files.size - ok
                val extra = if (falhas > 0) " ($falhas falharam)" else ""
                Toast.makeText(this, "$ok item(ns) $verbo para o celular$extra", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun confirmDelete() {
        val n = adapter.selectedCount
        MaterialAlertDialogBuilder(this)
            .setTitle("Excluir")
            .setMessage("Apagar $n item(ns) da galeria? Isso não pode ser desfeito.")
            .setPositiveButton("Excluir") { _, _ ->
                val files = adapter.selectedFiles()
                files.forEach { it.delete() }
                Albums.forget(applicationContext, files.map { it.name })
                adapter.clearSelection()
                Vault.notifyChanged(applicationContext)
                reload()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Menu / senha ----------

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 3, 0, "Selecionar tudo")
            menu.add(0, 5, 1, "Exportar tudo em .zip")
            menu.add(0, 1, 2, "Alterar proteção")
            menu.add(0, 4, 3, "Disfarce de calculadora")
            menu.add(0, 2, 4, "Bloquear agora")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> changeProtectionLauncher.launch(
                        Intent(this@MainActivity, UnlockActivity::class.java).putExtra(UnlockActivity.EXTRA_CHANGE_MODE, true)
                    )
                    2 -> {
                        Session.lockAll()
                        finishAffinity()
                    }
                    3 -> {
                        adapter.selectAll()
                        updateHeader()
                    }
                    4 -> disguiseSettings()
                    5 -> askExportZip()
                }
                true
            }
            show()
        }
    }

    private fun disguiseSettings() {
        val enabled = DisguiseManager.isEnabled(this)
        val switchView = com.google.android.material.materialswitch.MaterialSwitch(this).apply {
            text = "Mostrar ícone de Calculadora em vez de Galeria Privada"
            isChecked = enabled
        }
        val codeField = EditText(this).apply {
            hint = "Código secreto (números)"
            setText(DisguiseManager.code(this@MainActivity))
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val info = TextView(this).apply {
            text = "No app de Calculadora, digite esse código e toque em \"=\" para abrir a galeria."
            setTextColor(getColor(R.color.muted))
            textSize = 13f
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(switchView)
            addView(codeField, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad })
            addView(info, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 })
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Disfarce de calculadora")
            .setView(box)
            .setPositiveButton("Salvar", null)
            .setNegativeButton("Cancelar", null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val code = codeField.text.toString().trim()
            if (switchView.isChecked && code.length < 4) {
                Toast.makeText(this, "Use um código com pelo menos 4 números", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (code.isNotEmpty()) DisguiseManager.setCode(this, code)
            val wasEnabled = DisguiseManager.isEnabled(this)
            DisguiseManager.setEnabled(this, switchView.isChecked)
            dialog.dismiss()
            if (switchView.isChecked && !wasEnabled) {
                Toast.makeText(
                    this,
                    "Pronto. O ícone agora é \"Calculadora\". Para voltar, abra a Calculadora, digite o código e toque em =.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

}
