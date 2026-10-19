package com.exemplo.devtools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private class Tool(val emoji: String, val title: String, val sub: String, val open: () -> View)

    private val stack = ArrayList<Pair<String, View>>()
    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var backBtn: TextView

    val prefs by lazy { getSharedPreferences("devtoolbox", Context.MODE_PRIVATE) }

    /** Callback do seletor de arquivos (ACTION_OPEN_DOCUMENT). */
    var resultCallback: ((Int, Intent?) -> Unit)? = null

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4242) {
            val cb = resultCallback
            resultCallback = null
            cb?.invoke(resultCode, data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }
        val header = hbox().apply { setBackgroundColor(Color.parseColor("#1A1A1A")) }
        backBtn = tv("←", 26f).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { pop() }
        }
        titleView = tv("", 19f)
        header.addView(backBtn)
        header.addView(titleView)
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        push("Dev Toolbox", home())
    }

    fun push(title: String, view: View) {
        stack.add(title to view)
        show()
    }

    fun pop() {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            show()
        } else finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() = pop()

    private fun show() {
        val (title, view) = stack.last()
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(-1, -1))
        titleView.text = title
        backBtn.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        if (stack.size == 1) titleView.setPadding(dp(16), dp(14), 0, dp(14)) else titleView.setPadding(0, 0, 0, 0)
    }

    fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Não foi possível abrir o link")
        }
    }

    private fun home(): View {
        val tools = listOf(
            Tool("🐙", "GitHub", "Repos, Actions (builds), issues, PRs, código e releases") { githubHome() },
            Tool("📡", "Rede", "Scanner de rede local, ping, DNS, portas, IP") { networkHome() },
            Tool("🔌", "Cliente de API", "Requisições HTTP estilo Postman, com histórico") { apiClientView() },
            Tool("🧾", "JSON / XML", "Formatar, minificar e validar") { formatterView() },
            Tool("🔁", "Conversores", "Base64, URL, hex, hashes, timestamp, JWT, UUID") { convertView() },
            Tool("🔎", "Regex", "Testador de expressões regulares") { regexView() },
            Tool("🎨", "Cores", "HEX ↔ RGB ↔ HSL com pré-visualização") { colorView() },
            Tool("🩺", "Monitor de sites", "Verifica se sites e APIs estão no ar") { monitorView() },
            Tool("📚", "Cheat sheets", "Git, Linux, Docker, SQL, ADB, GitHub Actions") { cheatsView() }
        )
        val box = vbox(12)
        for (t in tools) {
            val card = vbox(14).apply {
                setBackgroundColor(CARD)
                setOnClickListener { push(t.title, t.open()) }
            }
            card.add(tv("${t.emoji}  ${t.title}", 18f), 0)
            card.add(tv(t.sub, 13f, GRAY), 2)
            box.add(card, 8)
        }
        return scroll(box)
    }
}
