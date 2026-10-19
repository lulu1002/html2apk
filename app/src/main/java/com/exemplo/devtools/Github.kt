package com.exemplo.devtools

import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.util.Base64
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URLEncoder

class Item(val title: String, val sub: String = "", val color: Int = Color.WHITE, val onClick: () -> Unit)

fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)
fun String.shortDate() = replace('T', ' ').removeSuffix("Z").take(16)

object GH {
    fun call(act: MainActivity, method: String, path: String, body: String? = null): Http.Resp {
        val token = act.prefs.getString("gh_token", "") ?: ""
        val h = mutableMapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to "DevToolbox"
        )
        if (token.isNotBlank()) h["Authorization"] = "Bearer $token"
        val url = if (path.startsWith("http")) path else "https://api.github.com$path"
        return Http.request(method, url, h, body)
    }

    fun json(act: MainActivity, path: String): Any {
        val r = call(act, "GET", path)
        if (r.code !in 200..299) throw IOException(errorMsg(r))
        return JSONTokener(r.body).nextValue()
    }

    fun errorMsg(r: Http.Resp): String {
        val msg = try { JSONObject(r.body).optString("message") } catch (e: Exception) { "" }
        val hint = when {
            r.code == 401 -> " (token inválido ou expirado)"
            r.code == 403 && msg.contains("rate limit", true) -> " (limite de requisições: salve um token para aumentar)"
            r.code == 403 -> " (sem permissão: confira os escopos do token)"
            r.code == 404 -> " (não encontrado — repo privado exige token)"
            else -> ""
        }
        return "HTTP ${r.code}: $msg$hint"
    }
}

// ---------------------------------------------------------------- telas genéricas

fun MainActivity.listScreen(title: String, loader: () -> List<Item>) {
    val box = vbox(8)
    val status = tv("Carregando…", 14f, GRAY)
    box.add(status)
    push(title, scroll(box))
    async(loader) { res ->
        res.fold(
            onSuccess = { items ->
                if (items.isEmpty()) status.text = "Nada encontrado"
                else {
                    box.removeView(status)
                    for (item in items) box.add(itemRow(item), 4)
                }
            },
            onFailure = {
                status.text = "Erro: ${it.message}"
                status.setTextColor(RED)
            }
        )
    }
}

private fun MainActivity.itemRow(item: Item): View {
    val row = vbox(10).apply {
        setBackgroundColor(CARD)
        setOnClickListener { item.onClick() }
    }
    row.add(tv(item.title, 15f, item.color), 0)
    if (item.sub.isNotBlank()) row.add(tv(item.sub, 12f, GRAY), 2)
    return row
}

fun MainActivity.textScreen(title: String, url: String? = null, mono: Boolean = false, loader: () -> String) {
    val box = vbox()
    val out = outputView(mono).apply { text = "Carregando…" }
    val actions = hbox()
    if (url != null) actions.add(btn("Abrir no navegador") { openUrl(url) }, 0, 1f, 0)
    actions.add(btn("Copiar") { copyText(out.text.toString()) }, 0, 1f, 0)
    box.add(actions, 0)
    box.add(out, 6)
    push(title, scroll(box))
    async(loader) { res ->
        res.fold(
            onSuccess = { out.text = it },
            onFailure = { out.text = "Erro: ${it.message}"; out.setTextColor(RED) }
        )
    }
}

// ---------------------------------------------------------------- tela inicial do GitHub

fun MainActivity.githubHome(): View {
    val box = vbox()

    box.add(tv("Token de acesso (opcional)", 15f), 0)
    box.add(
        tv(
            "Necessário para repositórios privados, re-executar builds e aumentar o limite de requisições. " +
                "Fica salvo só neste aparelho. Prefira um token fine-grained com permissões mínimas.",
            12f, GRAY
        ), 2
    )
    val tokenIn = box.add(ed("github_pat_… ou ghp_…").apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setText(prefs.getString("gh_token", ""))
    })
    val tokenInfo = box.add(tv("", 13f, GRAY))
    box.add(btn("Salvar e testar token") {
        prefs.edit().putString("gh_token", tokenIn.text.toString().trim()).apply()
        tokenInfo.text = "Testando…"
        async({ GH.json(this, "/user") as JSONObject }) { res ->
            res.fold(
                onSuccess = { tokenInfo.text = "✅ Logado como ${it.str("login")}"; tokenInfo.setTextColor(GREEN) },
                onFailure = { tokenInfo.text = "❌ ${it.message}"; tokenInfo.setTextColor(RED) }
            )
        }
    })

    box.add(tv("Repositório ou usuário", 15f), 18)
    val repoIn = box.add(ed("usuario/repositorio  ou  usuario").apply {
        setText(prefs.getString("gh_last", ""))
    })

    box.add(btn("Abrir repositório") {
        val r = repoIn.text.toString().trim().removePrefix("https://github.com/").trim('/')
        if (r.count { it == '/' } == 1) repoScreen(r) else toast("Use o formato usuario/repositorio")
    })
    box.add(btn("Repositórios do usuário") {
        val u = repoIn.text.toString().trim().substringBefore('/')
        val token = prefs.getString("gh_token", "") ?: ""
        when {
            u.isNotEmpty() -> reposList("Repos de $u", "/users/$u/repos?sort=updated&per_page=60")
            token.isNotBlank() -> reposList("Meus repositórios", "/user/repos?sort=updated&per_page=60")
            else -> toast("Digite um usuário ou salve um token")
        }
    })
    box.add(btn("Buscar repositórios no GitHub") {
        val q = repoIn.text.toString().trim()
        if (q.isEmpty()) toast("Digite o termo de busca")
        else reposList("Busca: $q", "/search/repositories?q=${URLEncoder.encode(q, "UTF-8")}&per_page=30")
    })
    return scroll(box)
}

private fun MainActivity.reposList(title: String, path: String) = listScreen(title) {
    val v = GH.json(this, path)
    val arr = if (v is JSONObject) v.getJSONArray("items") else v as JSONArray
    List(arr.length()) { i ->
        val r = arr.getJSONObject(i)
        val full = r.str("full_name")
        val lang = r.str("language")
        Item(
            "$full   ⭐${r.optInt("stargazers_count")}",
            listOf(r.str("description"), lang).filter { it.isNotBlank() }.joinToString(" • "),
            BLUE
        ) { repoScreen(full) }
    }
}

// ---------------------------------------------------------------- repositório

fun MainActivity.repoScreen(repo: String) {
    prefs.edit().putString("gh_last", repo).apply()
    val box = vbox()
    box.add(btn("🛠  Actions (builds)") { actionsScreen(repo) })
    box.add(btn("🐞  Issues") { issuesScreen(repo, "open", false) })
    box.add(btn("🔀  Pull requests") { issuesScreen(repo, "open", true) })
    box.add(btn("📁  Código") { codeScreen(repo, "") })
    box.add(btn("📦  Releases") { releasesScreen(repo) })
    box.add(btn("ℹ️  Informações") {
        textScreen("Info • $repo", "https://github.com/$repo") {
            val o = GH.json(this, "/repos/$repo") as JSONObject
            buildString {
                appendLine(o.str("full_name"))
                appendLine(o.str("description"))
                appendLine()
                appendLine("Linguagem: ${o.str("language")}")
                appendLine("⭐ ${o.optInt("stargazers_count")}   🍴 ${o.optInt("forks_count")}   👁 ${o.optInt("watchers_count")}")
                appendLine("Issues abertas: ${o.optInt("open_issues_count")}")
                appendLine("Branch padrão: ${o.str("default_branch")}")
                appendLine("Criado: ${o.str("created_at").shortDate()}")
                appendLine("Atualizado: ${o.str("pushed_at").shortDate()}")
                appendLine("Privado: ${o.optBoolean("private")}")
                appendLine("Clone: ${o.str("clone_url")}")
            }
        }
    })
    box.add(btn("🌐  Abrir no navegador") { openUrl("https://github.com/$repo") })
    push(repo, scroll(box))
}

// ---------------------------------------------------------------- Actions

fun MainActivity.actionsScreen(repo: String) = listScreen("Actions • $repo") {
    val o = GH.json(this, "/repos/$repo/actions/runs?per_page=30") as JSONObject
    val arr = o.getJSONArray("workflow_runs")
    List(arr.length()) { i ->
        val r = arr.getJSONObject(i)
        val status = r.str("status")
        val concl = r.str("conclusion")
        val state = if (status == "completed") concl else status
        val (icon, color) = when (state) {
            "success" -> "✅" to GREEN
            "failure", "timed_out" -> "❌" to RED
            "cancelled", "skipped" -> "⏹" to GRAY
            "in_progress", "queued", "waiting", "pending" -> "⏳" to YELLOW
            else -> "•" to Color.WHITE
        }
        val id = r.getLong("id")
        val name = "${r.str("name")} #${r.optInt("run_number")}"
        Item(
            "$icon $name",
            "${r.str("head_branch")} • ${r.str("event")} • $state • ${r.str("updated_at").shortDate()}\n${r.str("display_title")}",
            color
        ) { runMenu(repo, id, name, r.str("html_url")) }
    }
}

private fun MainActivity.runMenu(repo: String, id: Long, name: String, url: String) {
    val labels = arrayOf(
        "Ver jobs e etapas",
        "Abrir no navegador (logs e artefatos)",
        "Re-executar tudo",
        "Re-executar só os que falharam",
        "Cancelar execução"
    )
    AlertDialog.Builder(this).setTitle(name).setItems(labels) { _, which ->
        when (which) {
            0 -> jobsScreen(repo, id, name)
            1 -> openUrl(url)
            2 -> postAction(repo, "/actions/runs/$id/rerun", "Re-execução iniciada")
            3 -> postAction(repo, "/actions/runs/$id/rerun-failed-jobs", "Re-execução dos jobs com falha iniciada")
            4 -> postAction(repo, "/actions/runs/$id/cancel", "Cancelamento solicitado")
        }
    }.show()
}

private fun MainActivity.postAction(repo: String, path: String, okMsg: String) {
    async({ GH.call(this, "POST", "/repos/$repo$path", "") }) { res ->
        res.fold(
            onSuccess = { toast(if (it.code in 200..299) okMsg else GH.errorMsg(it)) },
            onFailure = { toast("Erro: ${it.message}") }
        )
    }
}

private fun MainActivity.jobsScreen(repo: String, runId: Long, name: String) = listScreen("Jobs • $name") {
    val o = GH.json(this, "/repos/$repo/actions/runs/$runId/jobs?per_page=50") as JSONObject
    val arr = o.getJSONArray("jobs")
    List(arr.length()) { i ->
        val j = arr.getJSONObject(i)
        val state = if (j.str("status") == "completed") j.str("conclusion") else j.str("status")
        val color = when (state) {
            "success" -> GREEN
            "failure" -> RED
            "in_progress", "queued" -> YELLOW
            else -> GRAY
        }
        val steps = j.optJSONArray("steps") ?: JSONArray()
        val failed = (0 until steps.length()).map { steps.getJSONObject(it) }
            .filter { it.str("conclusion") == "failure" }
            .joinToString("") { "\n   ✗ falhou na etapa: ${it.str("name")}" }
        Item(j.str("name"), "$state$failed", color) { openUrl(j.str("html_url")) }
    }
}

// ---------------------------------------------------------------- Issues / PRs

fun MainActivity.issuesScreen(repo: String, state: String, pulls: Boolean) {
    val kind = if (pulls) "PRs" else "Issues"
    listScreen("$kind ($state) • $repo") {
        val arr = GH.json(this, "/repos/$repo/${if (pulls) "pulls" else "issues"}?state=$state&per_page=40") as JSONArray
        val items = ArrayList<Item>()
        val other = if (state == "open") "closed" else "open"
        items += Item("↻ Ver ${if (other == "open") "abertas" else "fechadas"}", "", BLUE) {
            issuesScreen(repo, other, pulls)
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!pulls && o.has("pull_request")) continue
            val num = o.optInt("number")
            val user = o.optJSONObject("user")?.str("login").orEmpty()
            items += Item(
                "#$num ${o.str("title")}",
                "$user • 💬 ${o.optInt("comments")} • ${o.str("updated_at").shortDate()}"
            ) { issueDetail(repo, num, o.str("title"), o.str("body"), o.str("html_url")) }
        }
        items
    }
}

private fun MainActivity.issueDetail(repo: String, num: Int, title: String, body: String, url: String) {
    textScreen("#$num", url) {
        val sb = StringBuilder()
        sb.appendLine(title).appendLine().appendLine(body.ifBlank { "(sem descrição)" })
        val comments = GH.json(this, "/repos/$repo/issues/$num/comments?per_page=50") as JSONArray
        if (comments.length() > 0) sb.appendLine().appendLine("──── Comentários ────")
        for (i in 0 until comments.length()) {
            val c = comments.getJSONObject(i)
            sb.appendLine().appendLine("${c.optJSONObject("user")?.str("login")} • ${c.str("created_at").shortDate()}")
            sb.appendLine(c.str("body"))
        }
        sb.toString()
    }
}

// ---------------------------------------------------------------- Código

fun MainActivity.codeScreen(repo: String, path: String) {
    listScreen("/$path • $repo") {
        val v = GH.json(this, "/repos/$repo/contents${encPath(path)}")
        val arr = v as? JSONArray ?: throw IOException("Não é uma pasta")
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            .sortedWith(compareBy({ it.str("type") != "dir" }, { it.str("name").lowercase() }))
        list.map { o ->
            val p = o.str("path")
            if (o.str("type") == "dir") Item("📁 ${o.str("name")}", "", BLUE) { codeScreen(repo, p) }
            else Item("📄 ${o.str("name")}", fmtSize(o.optLong("size"))) {
                fileScreen(repo, p, o.optLong("size"), o.str("html_url"))
            }
        }
    }
}

private fun encPath(path: String): String {
    val parts = path.split('/').filter { it.isNotEmpty() }
    return if (parts.isEmpty()) "" else "/" + parts.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}

private fun MainActivity.fileScreen(repo: String, path: String, size: Long, htmlUrl: String) {
    textScreen(path.substringAfterLast('/'), htmlUrl, mono = true) {
        if (size > 400_000) return@textScreen "Arquivo grande demais para exibir (${fmtSize(size)}). Abra no navegador."
        val o = GH.json(this, "/repos/$repo/contents${encPath(path)}") as JSONObject
        val bytes = Base64.decode(o.str("content").replace("\n", ""), Base64.DEFAULT)
        val binary = bytes.take(1000).count { it.toInt() in 0..8 || it.toInt() in 14..31 } > 5
        if (binary) "Arquivo binário (${fmtSize(size)}). Abra no navegador."
        else String(bytes, Charsets.UTF_8).take(100_000)
    }
}

// ---------------------------------------------------------------- Releases

private fun MainActivity.releasesScreen(repo: String) = listScreen("Releases • $repo") {
    val arr = GH.json(this, "/repos/$repo/releases?per_page=20") as JSONArray
    List(arr.length()) { i ->
        val r = arr.getJSONObject(i)
        val assets = r.optJSONArray("assets") ?: JSONArray()
        val tag = r.str("tag_name")
        val name = r.str("name").ifBlank { tag }
        Item(
            name,
            "$tag • ${r.str("published_at").shortDate()} • ${assets.length()} arquivo(s)" +
                if (r.optBoolean("prerelease")) " • pré-lançamento" else "",
            BLUE
        ) { assetsScreen(name, assets, r.str("html_url")) }
    }
}

private fun MainActivity.assetsScreen(name: String, assets: JSONArray, pageUrl: String) =
    listScreen("Arquivos • $name") {
        val items = ArrayList<Item>()
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            items += Item(
                a.str("name"),
                "${fmtSize(a.optLong("size"))} • ⬇ ${a.optInt("download_count")}"
            ) { openUrl(a.str("browser_download_url")) }
        }
        items += Item("Abrir página da release", "", BLUE) { openUrl(pageUrl) }
        items
    }
