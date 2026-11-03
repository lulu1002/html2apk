package com.dnsswitch.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val nameFields = ArrayList<EditText>()
    private val dns1Fields = ArrayList<EditText>()
    private val dns2Fields = ArrayList<EditText>()
    private var pendingSlot = -1

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 14f, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 6.dp(), 0, 6.dp())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 32.dp())
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(text("DNS Switch VPN", 24f, true))
        status = text("", 16f, true)
        root.addView(status)
        root.addView(text("Digite o IP do DNS (IPv4). O segundo DNS é opcional."))

        for (slot in 0..3) {
            val p = DnsStore.getPreset(this, slot)
            root.addView(text("Atalho ${slot + 1}", 15f, true))

            val name = EditText(this).apply {
                hint = "Nome"
                setText(p.name)
                inputType = InputType.TYPE_CLASS_TEXT
            }
            val d1 = EditText(this).apply {
                hint = "DNS principal (ex: 8.8.8.8)"
                setText(p.dns1)
                inputType = InputType.TYPE_CLASS_TEXT
            }
            val d2 = EditText(this).apply {
                hint = "DNS secundário (opcional)"
                setText(p.dns2)
                inputType = InputType.TYPE_CLASS_TEXT
            }
            nameFields.add(name); dns1Fields.add(d1); dns2Fields.add(d2)
            root.addView(name); root.addView(d1); root.addView(d2)
            root.addView(Button(this).apply {
                text = "Ativar este DNS"
                setOnClickListener { activate(slot) }
            })
        }

        root.addView(Button(this).apply {
            text = "Salvar atalhos"
            setOnClickListener { saveAll(); toast("Atalhos salvos") }
        })
        root.addView(Button(this).apply {
            text = "Desligar DNS (voltar ao normal)"
            setOnClickListener { DnsVpnService.stop(this@MainActivity); toast("DNS desligado"); status.postDelayed({ refresh() }, 500) }
        })

        root.addView(text("Como adicionar ao painel", 18f, true))
        root.addView(text("Puxe a barra de notificações, toque no lápis (editar) e arraste \"DNS 1\" a \"DNS 4\" para o painel. Toque para ligar, toque de novo para desligar."))
        root.addView(text("Na primeira vez o Android pede para autorizar a VPN. É só aceitar: nenhum dado seu passa por servidor nenhum, a VPN é local e só encaminha consultas de DNS."))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun saveAll() {
        for (i in 0..3) {
            DnsStore.setPreset(
                this, i,
                nameFields[i].text.toString(),
                dns1Fields[i].text.toString(),
                dns2Fields[i].text.toString()
            )
        }
    }

    private fun activate(slot: Int) {
        saveAll()
        val d1 = dns1Fields[slot].text.toString()
        val d2 = dns2Fields[slot].text.toString()
        if (!DnsStore.isValidIpv4(d1) || (d2.isNotBlank() && !DnsStore.isValidIpv4(d2))) {
            toast("IP inválido")
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingSlot = slot
            startActivityForResult(intent, 1)
        } else {
            startDns(slot)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && pendingSlot >= 0) {
            startDns(pendingSlot)
        } else if (requestCode == 1) {
            toast("VPN não autorizada")
        }
        pendingSlot = -1
    }

    private fun startDns(slot: Int) {
        DnsVpnService.start(this, slot)
        toast("DNS ativado")
        status.postDelayed({ refresh() }, 500)
    }

    private fun refresh() {
        val s = DnsVpnService.activeSlot
        status.text = if (s >= 0) {
            val p = DnsStore.getPreset(this, s)
            "Ativo: ${p.name} (${p.dns1})"
        } else {
            "DNS: padrão do sistema (desligado)"
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
