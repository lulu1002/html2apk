package com.galeria.privada

import android.content.Intent
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: MediaAdapter
    private lateinit var root: View
    private lateinit var empty: View
    private lateinit var tvTitle: TextView
    private lateinit var tvCount: TextView
    private lateinit var btnDelete: ImageButton

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) importAll(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        empty = findViewById(R.id.empty)
        tvTitle = findViewById(R.id.tvTitle)
        tvCount = findViewById(R.id.tvCount)
        btnDelete = findViewById(R.id.btnDelete)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val fab = findViewById<ExtendedFloatingActionButton>(R.id.fabAdd)

        adapter = MediaAdapter(
            onClick = { i -> if (adapter.selecting) toggle(i) else open(i) },
            onLong = { i -> toggle(i) }
        )
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter

        fab.setOnClickListener { picker.launch(arrayOf("image/*", "video/*")) }
        btnDelete.setOnClickListener { confirmDelete() }
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
        val list = Vault.list(this)
        adapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        updateHeader()
    }

    private fun updateHeader() {
        if (adapter.selecting) {
            tvTitle.text = "${adapter.selectedCount} selecionado(s)"
            tvCount.text = "Toque para selecionar mais"
            btnDelete.visibility = View.VISIBLE
        } else {
            tvTitle.text = "Galeria Privada"
            val photos = adapter.items.count { Vault.isImage(it) }
            val videos = adapter.items.count { Vault.isVideo(it) }
            tvCount.text = "$photos fotos · $videos vídeos"
            btnDelete.visibility = View.GONE
        }
    }

    private fun toggle(i: Int) {
        adapter.toggle(i)
        updateHeader()
    }

    private fun open(i: Int) {
        startActivity(Intent(this, ViewerActivity::class.java).putExtra("index", i))
    }

    private fun importAll(uris: List<android.net.Uri>) {
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

    private fun confirmDelete() {
        val n = adapter.selectedCount
        MaterialAlertDialogBuilder(this)
            .setTitle("Excluir")
            .setMessage("Apagar $n item(ns) da galeria? Isso não pode ser desfeito.")
            .setPositiveButton("Excluir") { _, _ ->
                adapter.selectedFiles().forEach { it.delete() }
                adapter.clearSelection()
                Vault.notifyChanged(applicationContext)
                reload()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Alterar senha")
            menu.add(0, 2, 1, "Bloquear agora")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> changePassword()
                    2 -> {
                        Session.lockAll()
                        finishAffinity()
                    }
                }
                true
            }
            show()
        }
    }

    private fun changePassword() {
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val cur = field("Senha atual")
        val new = field("Nova senha")
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(cur)
            addView(new)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Alterar senha")
            .setView(box)
            .setPositiveButton("Salvar", null)
            .setNegativeButton("Cancelar", null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            when {
                !Vault.check(this, cur.text.toString()) ->
                    Toast.makeText(this, "Senha atual incorreta", Toast.LENGTH_SHORT).show()
                new.text.length < 4 ->
                    Toast.makeText(this, "Use pelo menos 4 caracteres", Toast.LENGTH_SHORT).show()
                else -> {
                    Vault.setPassword(this, new.text.toString())
                    Toast.makeText(this, "Senha alterada", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }
    }
}
