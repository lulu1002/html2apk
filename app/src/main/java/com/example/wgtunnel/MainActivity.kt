package com.example.wgtunnel

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val app get() = application as WgApp
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView

    private var rendering = false
    private var pendingVpnName: String? = null
    private var pendingExportName: String? = null

    private val vpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val name = pendingVpnName
            pendingVpnName = null
            if (name != null && result.resultCode == RESULT_OK) {
                setTunnelState(name, true)
            } else {
                toast(getString(R.string.vpn_denied))
                render()
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFrom(uri)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val name = pendingExportName
            pendingExportName = null
            if (uri != null && name != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(app.store.read(name).toByteArray())
                    }
                    toast(getString(R.string.exported))
                } catch (e: Exception) {
                    toast(e.message ?: e.toString())
                }
            }
        }

    private val statsTick = object : Runnable {
        override fun run() {
            refreshStats()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        listContainer = findViewById(R.id.list)
        emptyView = findViewById(R.id.empty)
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { showAddMenu() }
        render()
    }

    override fun onStart() {
        super.onStart()
        app.onChange = { ui.post { render() } }
        render()
        ui.post(statsTick)
    }

    override fun onStop() {
        super.onStop()
        app.onChange = null
        ui.removeCallbacks(statsTick)
    }

    // ---------------------------------------------------------------- lista

    private fun render() {
        rendering = true
        listContainer.removeAllViews()
        val names = app.store.names()
        emptyView.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
        for (name in names) {
            val tunnel = app.tunnel(name)
            val up = tunnel.state == Tunnel.State.UP
            val row = layoutInflater.inflate(R.layout.item_tunnel, listContainer, false)
            row.tag = name
            row.findViewById<TextView>(R.id.name).text = name
            row.findViewById<TextView>(R.id.status).text =
                getString(if (up) R.string.connected else R.string.disconnected)
            val toggle = row.findViewById<MaterialSwitch>(R.id.toggle)
            toggle.isChecked = up
            toggle.setOnCheckedChangeListener { _, checked ->
                if (!rendering) setTunnelState(name, checked)
            }
            row.setOnClickListener { openEditor(name) }
            row.setOnLongClickListener { showTunnelMenu(name); true }
            listContainer.addView(row)
        }
        rendering = false
    }

    private fun refreshStats() {
        for (name in app.store.names()) {
            val tunnel = app.tunnel(name)
            if (tunnel.state != Tunnel.State.UP) continue
            app.io.execute {
                try {
                    val stats = app.backend.getStatistics(tunnel)
                    val text = getString(
                        R.string.connected_stats,
                        formatBytes(stats.totalRx()),
                        formatBytes(stats.totalTx())
                    )
                    ui.post { setStatus(name, text) }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun setStatus(name: String, text: String) {
        for (i in 0 until listContainer.childCount) {
            val row = listContainer.getChildAt(i)
            if (row.tag == name) row.findViewById<TextView>(R.id.status).text = text
        }
    }

    private fun formatBytes(b: Long): String {
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        var d = b.toDouble()
        var i = 0
        while (d >= 1024 && i < units.lastIndex) {
            d /= 1024
            i++
        }
        return if (i == 0) "$b B" else String.format(Locale.US, "%.2f %s", d, units[i])
    }

    // ---------------------------------------------------------------- ligar / desligar

    private fun setTunnelState(name: String, up: Boolean) {
        if (up) {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                pendingVpnName = name
                vpnLauncher.launch(intent)
                return
            }
        }
        val tunnel = app.tunnel(name)
        app.io.execute {
            try {
                val config = if (up) Config.parse(app.store.read(name).byteInputStream()) else null
                val newState = if (up) Tunnel.State.UP else Tunnel.State.DOWN
                app.backend.setState(tunnel, newState, config)
            } catch (e: Exception) {
                ui.post { toast(describe(e)) }
            }
            ui.post { render() }
        }
    }

    // ---------------------------------------------------------------- menus

    private fun showAddMenu() {
        val items = arrayOf(getString(R.string.import_file), getString(R.string.create_new))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_tunnel)
            .setItems(items) { _, which ->
                if (which == 0) importLauncher.launch(arrayOf("*/*")) else openEditor(null)
            }
            .show()
    }

    private fun showTunnelMenu(name: String) {
        val items = arrayOf(
            getString(R.string.edit),
            getString(R.string.export_conf),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openEditor(name)
                    1 -> {
                        pendingExportName = name
                        exportLauncher.launch("$name.conf")
                    }
                    2 -> confirmDelete(name)
                }
            }
            .show()
    }

    private fun confirmDelete(name: String) {
        if (app.tunnel(name).state == Tunnel.State.UP) {
            toast(getString(R.string.turn_off_first))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.confirm_delete, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                app.store.delete(name)
                app.forget(name)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- importar

    private fun importFrom(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            Config.parse(text.byteInputStream())
            var display = "wg0"
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) display = c.getString(idx) ?: display
            }
            val base = display.removeSuffix(".conf")
                .replace(Regex("[^a-zA-Z0-9_=+.-]"), "_")
                .take(12)
                .ifEmpty { "wg0" }
            var name = base
            var i = 1
            while (app.store.exists(name)) name = base + "-" + (i++)
            app.store.write(name, text)
            render()
            toast(getString(R.string.imported, name))
        } catch (e: Exception) {
            toast(describe(e))
        }
    }

    // ---------------------------------------------------------------- editor

    private fun openEditor(existing: String?) {
        if (existing != null && app.tunnel(existing).state == Tunnel.State.UP) {
            toast(getString(R.string.turn_off_first))
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_edit, null)
        val nameIn = view.findViewById<EditText>(R.id.edit_name)
        val confIn = view.findViewById<EditText>(R.id.edit_config)
        val pubView = view.findViewById<TextView>(R.id.public_key)

        val privRe = Regex("(?im)^\\s*PrivateKey\\s*=\\s*(\\S+)")

        fun updatePublicKey() {
            val m = privRe.find(confIn.text.toString())
            val pub = try {
                if (m == null) null else KeyPair(Key.fromBase64(m.groupValues[1])).publicKey.toBase64()
            } catch (_: Exception) {
                null
            }
            pubView.text = if (pub != null) getString(R.string.public_key_fmt, pub)
            else getString(R.string.public_key_none)
            pubView.setOnClickListener {
                if (pub != null) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("public key", pub))
                    toast(getString(R.string.copied))
                }
            }
        }

        if (existing != null) {
            nameIn.setText(existing)
            confIn.setText(app.store.read(existing))
        } else {
            confIn.setText(template())
        }
        updatePublicKey()

        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.new_tunnel else R.string.edit)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.gen_key, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val line = "PrivateKey = " + KeyPair().privateKey.toBase64()
                val txt = confIn.text.toString()
                confIn.setText(
                    if (privRe.containsMatchIn(txt)) {
                        Regex("(?im)^\\s*PrivateKey\\s*=.*$")
                            .replaceFirst(txt, Regex.escapeReplacement(line))
                    } else {
                        txt.replaceFirst("[Interface]", "[Interface]\n$line")
                    }
                )
                updatePublicKey()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameIn.text.toString().trim()
                if (!NAME_RE.matches(name)) {
                    nameIn.error = getString(R.string.invalid_name)
                    return@setOnClickListener
                }
                if (name != existing && app.store.exists(name)) {
                    nameIn.error = getString(R.string.name_exists)
                    return@setOnClickListener
                }
                val text = confIn.text.toString()
                try {
                    Config.parse(text.byteInputStream())
                } catch (e: Exception) {
                    toast(describe(e))
                    return@setOnClickListener
                }
                app.store.write(name, text)
                if (existing != null && existing != name) {
                    app.store.delete(existing)
                    app.forget(existing)
                }
                render()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun template(): String {
        val kp = KeyPair()
        return """
            [Interface]
            PrivateKey = ${kp.privateKey.toBase64()}
            Address = 10.0.0.2/32
            DNS = 1.1.1.1

            [Peer]
            PublicKey =
            Endpoint = servidor.exemplo.com:51820
            AllowedIPs = 0.0.0.0/0, ::/0
            PersistentKeepalive = 25
        """.trimIndent() + "\n"
    }

    // ---------------------------------------------------------------- util

    private fun describe(e: Exception): String =
        if (e is BadConfigException) {
            "${e.section} ${e.location}: ${e.reason}"
        } else {
            e.message ?: e.toString()
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private val NAME_RE = Regex("^[a-zA-Z0-9_=+.-]{1,15}$")
    }
}
