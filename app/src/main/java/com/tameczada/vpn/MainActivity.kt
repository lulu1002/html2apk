package com.tameczada.vpn

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var backend: GoBackend
    private lateinit var statusView: TextView
    private lateinit var configEdit: EditText
    private lateinit var toggleBtn: Button

    private val tunnel = object : Tunnel {
        override fun getName(): String = "wg0"
        override fun onStateChange(newState: Tunnel.State) {
            runOnUiThread { render(newState == Tunnel.State.UP) }
        }
    }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (GoBackend.VpnService.prepare(this) == null) setTunnel(true)
            else toast("Permissão de VPN negada")
        }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                try {
                    val text = contentResolver.openInputStream(it)!!.bufferedReader().readText()
                    configEdit.setText(text)
                } catch (e: Exception) {
                    toast("Erro ao ler arquivo: ${e.message}")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        backend = GoBackend(applicationContext)
        statusView = findViewById(R.id.status)
        configEdit = findViewById(R.id.configEdit)
        toggleBtn = findViewById(R.id.toggleBtn)

        val prefs = getSharedPreferences("wg", MODE_PRIVATE)
        configEdit.setText(prefs.getString("config", ""))

        findViewById<Button>(R.id.importBtn).setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }

        toggleBtn.setOnClickListener {
            val isUp = backend.getState(tunnel) == Tunnel.State.UP
            if (isUp) {
                setTunnel(false)
            } else {
                if (configEdit.text.isBlank()) {
                    toast("Cole ou importe uma configuração")
                    return@setOnClickListener
                }
                prefs.edit().putString("config", configEdit.text.toString()).apply()
                val intent = GoBackend.VpnService.prepare(this)
                if (intent != null) vpnPermission.launch(intent) else setTunnel(true)
            }
        }

        render(backend.getState(tunnel) == Tunnel.State.UP)
    }

    private fun setTunnel(up: Boolean) {
        val text = configEdit.text.toString()
        thread {
            try {
                val cfg = if (up) Config.parse(ByteArrayInputStream(text.toByteArray())) else null
                backend.setState(tunnel, if (up) Tunnel.State.UP else Tunnel.State.DOWN, cfg)
                runOnUiThread { render(up) }
            } catch (e: Exception) {
                runOnUiThread {
                    statusView.text = "Erro: ${e.message}"
                    render(backend.getState(tunnel) == Tunnel.State.UP, keepStatus = true)
                }
            }
        }
    }

    private fun render(up: Boolean, keepStatus: Boolean = false) {
        if (!keepStatus) statusView.text = if (up) "Conectado" else "Desconectado"
        toggleBtn.text = if (up) "Desconectar" else "Conectar"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
