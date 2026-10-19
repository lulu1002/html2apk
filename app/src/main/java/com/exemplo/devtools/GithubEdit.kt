package com.exemplo.devtools

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.CheckBox
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

fun encPath(path: String): String {
    val parts = path.split('/').filter { it.isNotEmpty() }
    return if (parts.isEmpty()) "" else "/" + parts.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}

private fun validRepoPath(p: String) =
    p.isNotEmpty() && !p.startsWith("/") && !p.endsWith("/") && p.split('/').none { it.isEmpty() || it == "." || it == ".." }

// ---------------------------------------------------------------- API Git Data (commits com vários arquivos)

object GitData {

    /** bytes == null significa "apagar este caminho". */
    class Change(val path: String, val bytes: ByteArray?)

    fun branch(act: MainActivity, repo: String): String {
        val saved = act.prefs.getString("gh_branch:$repo", null)
        if (!saved.isNullOrBlank()) return saved
        val o = GH.json(act, "/repos/$repo") as JSONObject
        val b = o.str("default_branch").ifBlank { "main" }
        setBranch(act, repo, b)
        return b
    }

    fun setBranch(act: MainActivity, repo: String, branch: String) {
        act.prefs.edit().putString("gh_branch:$repo", branch).apply()
    }

    private fun ok(r: Http.Resp): Http.Resp {
        if (r.code !in 200..299) throw IOException(GH.errorMsg(r))
        return r
    }

    private fun post(act: MainActivity, path: String, json: JSONObject) = ok(GH.call(act, "POST", path, json.toString()))

    private fun modeFor(path: String): String {
        val n = path.substringAfterLast('/')
        return if (n == "gradlew" || n.endsWith(".sh")) "100755" else "100644"
    }

    fun createBranch(act: MainActivity, repo: String, name: String, from: String) {
        val ref = ok(GH.call(act, "GET", "/repos/$repo/git/ref/heads/$from"))
        val sha = JSONObject(ref.body).getJSONObject("object").getString("sha")
        post(act, "/repos/$repo/git/refs", JSONObject().put("ref", "refs/heads/$name").put("sha", sha))
    }

    fun folderDeletes(act: MainActivity, repo: String, branch: String, dir: String): List<Change> {
        val r = ok(GH.call(act, "GET", "/repos/$repo/git/trees/$branch?recursive=1"))
        val arr = JSONObject(r.body).getJSONArray("tree")
        val out = ArrayList<Change>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.str("type") == "blob" && o.str("path").startsWith("$dir/")) out.add(Change(o.str("path"), null))
        }
        if (out.isEmpty()) throw IOException("Nenhum arquivo encontrado em /$dir")
        return out
    }

    /** Cria UM commit com todas as mudanças. Retorna a URL do commit. */
    fun commit(
        act: MainActivity, repo: String, branch: String, message: String,
        changes: List<Change>, progress: (String) -> Unit
    ): String {
        require(changes.isNotEmpty()) { "Nada para enviar" }
        var todo = changes.associateBy { it.path }.values.toList() // último vence em caminhos repetidos
        val refPath = "/repos/$repo/git/ref/heads/$branch"
        var refResp = GH.call(act, "GET", refPath)

        if (refResp.code != 200) {
            // Repositório vazio (ou branch inexistente): o 1º arquivo cria o commit inicial pela API de conteúdo.
            val first = todo.firstOrNull { it.bytes != null } ?: throw IOException("Branch '$branch' não existe")
            progress("Criando o primeiro commit…")
            val body = JSONObject()
                .put("message", message)
                .put("content", Base64.encodeToString(first.bytes!!, Base64.NO_WRAP))
                .put("branch", branch)
            ok(GH.call(act, "PUT", "/repos/$repo/contents${encPath(first.path)}", body.toString()))
            todo = todo.filter { it !== first }
            if (todo.isEmpty()) return "https://github.com/$repo/tree/$branch"
            refResp = ok(GH.call(act, "GET", refPath))
        }

        val commitSha = JSONObject(refResp.body).getJSONObject("object").getString("sha")
        val baseTree = JSONObject(ok(GH.call(act, "GET", "/repos/$repo/git/commits/$commitSha")).body)
            .getJSONObject("tree").getString("sha")

        val toUpload = todo.count { it.bytes != null }
        val counter = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(6)
        val treeArr = JSONArray()
        try {
            val futures = todo.map { c ->
                pool.submit(Callable<JSONObject> {
                    val e = JSONObject().put("path", c.path).put("mode", modeFor(c.path)).put("type", "blob")
                    val data = c.bytes
                    if (data == null) {
                        e.put("sha", JSONObject.NULL)
                    } else {
                        val blob = post(
                            act, "/repos/$repo/git/blobs",
                            JSONObject().put("content", Base64.encodeToString(data, Base64.NO_WRAP)).put("encoding", "base64")
                        )
                        e.put("sha", JSONObject(blob.body).getString("sha"))
                        progress("Enviando arquivos… ${counter.incrementAndGet()}/$toUpload")
                    }
                    e
                })
            }
            for (f in futures) treeArr.put(f.get())
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        } finally {
            pool.shutdownNow()
        }

        progress("Criando commit…")
        val tree = post(act, "/repos/$repo/git/trees", JSONObject().put("base_tree", baseTree).put("tree", treeArr))
        val treeSha = JSONObject(tree.body).getString("sha")
        val nc = post(
            act, "/repos/$repo/git/commits",
            JSONObject().put("message", message).put("tree", treeSha).put("parents", JSONArray().put(commitSha))
        )
        val newSha = JSONObject(nc.body).getString("sha")
        ok(GH.call(act, "PATCH", "/repos/$repo/git/refs/heads/$branch", JSONObject().put("sha", newSha).toString()))
        return "https://github.com/$repo/commit/$newSha"
    }
}

// ---------------------------------------------------------------- helpers de UI

fun <T> MainActivity.withProgress(title: String, work: ((String) -> Unit) -> T, done: (Result<T>) -> Unit) {
    val msg = tv("Aguarde…", 14f)
    val box = vbox(20).apply { addView(msg) }
    val dlg = AlertDialog.Builder(this).setTitle(title).setView(box).setCancelable(false).create()
    dlg.show()
    async({ work { m -> runOnUiThread { msg.text = m } } }) { res ->
        dlg.dismiss()
        done(res)
    }
}

fun MainActivity.promptText(title: String, initial: String, onOk: (String) -> Unit) {
    val input = ed("").apply { setText(initial) }
    val box = vbox(16).apply { addView(input) }
    AlertDialog.Builder(this).setTitle(title).setView(box)
        .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
        .setNegativeButton("Cancelar", null).show()
}

fun MainActivity.askCommitMessage(summary: String, default: String, onOk: (String) -> Unit) {
    val box = vbox(16)
    box.add(tv(summary, 13f, GRAY), 0)
    val input = box.add(ed("Mensagem do commit").apply { setText(default) })
    AlertDialog.Builder(this).setTitle("Commit").setView(box)
        .setPositiveButton("Commit") { _, _ -> onOk(input.text.toString().ifBlank { default }) }
        .setNegativeButton("Cancelar", null).show()
}

/** Executa [build] em segundo plano (já sabendo a branch) e cria um único commit com o resultado. */
fun MainActivity.commitChanges(
    repo: String, message: String, onDone: () -> Unit = {}, build: (String) -> List<GitData.Change>
) {
    withProgress("Enviando para o GitHub", { progress ->
        val branch = GitData.branch(this, repo)
        progress("Preparando…")
        val changes = build(branch)
        branch to GitData.commit(this, repo, branch, message, changes, progress)
    }) { res ->
        res.fold(onSuccess = { (branch, url) ->
            AlertDialog.Builder(this).setTitle("✅ Commit enviado")
                .setMessage("Branch: $branch\n$url")
                .setPositiveButton("Abrir no GitHub") { _, _ -> openUrl(url) }
                .setNegativeButton("OK", null).show()
            onDone()
        }, onFailure = {
            AlertDialog.Builder(this).setTitle("❌ Não foi possível enviar")
                .setMessage(it.message ?: it.toString())
                .setPositiveButton("OK", null).show()
        })
    }
}

fun MainActivity.pickFile(multiple: Boolean, mime: String, cb: (List<Uri>) -> Unit) {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mime
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
    }
    resultCallback = { code, data ->
        if (code == Activity.RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
            } else {
                data.data?.let { uris.add(it) }
            }
            if (uris.isNotEmpty()) cb(uris)
        }
    }
    startActivityForResult(i, 4242)
}

private fun MainActivity.displayName(u: Uri): String {
    val name: String? = contentResolver.query(u, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
    return name ?: u.lastPathSegment?.substringAfterLast('/') ?: "arquivo"
}

private fun MainActivity.readUri(u: Uri): ByteArray =
    contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw IOException("Não foi possível ler o arquivo")

// ---------------------------------------------------------------- navegador de código

fun MainActivity.codeScreen(repo: String, path: String) {
    listScreen("/$path • $repo") {
        val branch = GitData.branch(this, repo)
        val items = ArrayList<Item>()
        items += Item("🌿 Branch: $branch", "toque para trocar ou criar", BLUE) { branchDialog(repo) }
        items += Item("➕ Novo arquivo aqui", "", GREEN) { newFileDialog(repo, path) }
        items += Item("⬆ Enviar arquivos do celular", "", GREEN) { uploadFiles(repo, path) }
        items += Item("⬆ Enviar projeto (.zip)", "extrai e envia tudo em um único commit", GREEN) { uploadZip(repo, path) }
        if (path.isNotEmpty()) items += Item("🗑 Excluir esta pasta", "", RED) { deleteFolder(repo, path) }

        val r = GH.call(this, "GET", "/repos/$repo/contents${encPath(path)}?ref=${URLEncoder.encode(branch, "UTF-8")}")
        if (r.code == 404 && path.isEmpty()) {
            items += Item("Repositório vazio", "envie arquivos acima para criar o primeiro commit", GRAY) {}
            return@listScreen items
        }
        if (r.code !in 200..299) throw IOException(GH.errorMsg(r))
        val arr = JSONTokener(r.body).nextValue() as? JSONArray ?: throw IOException("Não é uma pasta")

        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            .sortedWith(compareBy({ it.str("type") != "dir" }, { it.str("name").lowercase() }))
        for (o in list) {
            val p = o.str("path")
            if (o.str("type") == "dir") {
                items += Item("📁 ${o.str("name")}", "", Color.WHITE) { codeScreen(repo, p) }
            } else {
                items += Item("📄 ${o.str("name")}", fmtSize(o.optLong("size"))) {
                    fileScreen(repo, p, o.optLong("size"), o.str("html_url"))
                }
            }
        }
        items
    }
}

fun MainActivity.fileScreen(repo: String, path: String, size: Long, htmlUrl: String) {
    val name = path.substringAfterLast('/')
    val box = vbox()
    val out = outputView().apply { text = "Carregando…" }
    var bytes: ByteArray? = null
    var text: String? = null

    val row1 = hbox()
    row1.add(btn("✏️ Editar") {
        val t = text
        if (t == null) toast("Arquivo não carregado ou não editável") else editorScreen(repo, path, t)
    }, 0, 1f, 0)
    row1.add(btn("↪ Mover") {
        val b = bytes
        if (b == null) { toast("Arquivo ainda não carregado"); return@btn }
        promptText("Novo caminho", path) { newPath ->
            if (!validRepoPath(newPath) || newPath == path) { toast("Caminho inválido"); return@promptText }
            askCommitMessage("$path → $newPath", "Move $name") { msg ->
                commitChanges(repo, msg, { pop() }) { _ -> listOf(GitData.Change(path, null), GitData.Change(newPath, b)) }
            }
        }
    }, 0, 1f, 0)
    row1.add(btn("🗑 Excluir") {
        AlertDialog.Builder(this).setTitle("Excluir arquivo?").setMessage(path)
            .setPositiveButton("Excluir") { _, _ ->
                askCommitMessage("Excluir $path", "Remove $name") { msg ->
                    commitChanges(repo, msg, { pop() }) { _ -> listOf(GitData.Change(path, null)) }
                }
            }.setNegativeButton("Cancelar", null).show()
    }, 0, 1f, 0)
    box.add(row1, 0)

    val row2 = hbox()
    row2.add(btn("Abrir no navegador") { openUrl(htmlUrl) }, 0, 1f, 0)
    row2.add(btn("Copiar") { copyText(text ?: out.text.toString()) }, 0, 1f, 0)
    box.add(row2, 0)
    box.add(out, 6)
    push(name, scroll(box))

    async({
        if (size > 400_000) throw IOException("Arquivo grande demais para abrir aqui (${fmtSize(size)}). Use o navegador.")
        val branch = GitData.branch(this, repo)
        val o = GH.json(this, "/repos/$repo/contents${encPath(path)}?ref=${URLEncoder.encode(branch, "UTF-8")}") as JSONObject
        Base64.decode(o.str("content").replace("\n", ""), Base64.DEFAULT)
    }) { res ->
        res.fold(onSuccess = { b ->
            bytes = b
            val binary = b.take(1000).count { it.toInt() in 0..8 || it.toInt() in 14..31 } > 5
            val t = String(b, Charsets.UTF_8)
            when {
                binary -> out.text = "Arquivo binário (${fmtSize(size)}). Não dá para editar aqui."
                !t.toByteArray(Charsets.UTF_8).contentEquals(b) ->
                    out.text = "Codificação que não é UTF-8: edição desativada para não corromper o arquivo.\n\n" + t.take(50_000)
                else -> {
                    text = t
                    out.text = t.take(100_000) + if (t.length > 100_000) "\n… (cortado na tela; a edição usa o arquivo inteiro)" else ""
                }
            }
            Unit
        }, onFailure = {
            out.text = "Erro: ${it.message}"
            out.setTextColor(RED)
        })
    }
}

fun MainActivity.editorScreen(repo: String, path: String, initial: String) {
    val box = vbox(8)
    box.add(tv(path, 13f, GRAY), 0)
    val editor = box.add(ed("", 18, true).apply {
        setText(initial)
        textSize = 13f
        maxLines = Int.MAX_VALUE
    })
    box.add(btn("💾  Fazer commit…") {
        val name = path.substringAfterLast('/')
        askCommitMessage("Alterações em $path", "Atualiza $name") { msg ->
            val data = editor.text.toString().toByteArray(Charsets.UTF_8)
            commitChanges(repo, msg, { pop() }) { _ -> listOf(GitData.Change(path, data)) }
        }
    }, 8)
    push("Editar • ${path.substringAfterLast('/')}", scroll(box))
}

fun MainActivity.newFileDialog(repo: String, dir: String) {
    promptText("Nome do novo arquivo (pode usar pastas: a/b.txt)", "") { name ->
        val full = if (dir.isEmpty()) name else "$dir/$name"
        if (!validRepoPath(full)) toast("Nome inválido") else editorScreen(repo, full, "")
    }
}

fun MainActivity.deleteFolder(repo: String, dir: String) {
    AlertDialog.Builder(this).setTitle("Excluir pasta?").setMessage("/$dir e tudo que há dentro (em um commit).")
        .setPositiveButton("Excluir") { _, _ ->
            askCommitMessage("Excluir /$dir", "Remove $dir") { msg ->
                commitChanges(repo, msg, { pop() }) { branch -> GitData.folderDeletes(this, repo, branch, dir) }
            }
        }.setNegativeButton("Cancelar", null).show()
}

// ---------------------------------------------------------------- envio de arquivos e projetos

fun MainActivity.uploadFiles(repo: String, dir: String) {
    pickFile(true, "*/*") { uris ->
        withProgress("Lendo arquivos…", { _ ->
            var total = 0L
            uris.map { u ->
                val n = displayName(u)
                val b = readUri(u)
                total += b.size
                require(total <= 60_000_000) { "Total acima de 60 MB. Envie em partes." }
                n to b
            }
        }) { res ->
            res.fold(onSuccess = { files ->
                val summary = "${files.size} arquivo(s) → /$dir\n" +
                    files.joinToString("\n") { "• ${it.first} (${fmtSize(it.second.size.toLong())})" }.take(600)
                askCommitMessage(summary, "Adiciona ${files.size} arquivo(s)") { msg ->
                    commitChanges(repo, msg) { _ ->
                        files.map { (n, b) -> GitData.Change(if (dir.isEmpty()) n else "$dir/$n", b) }
                    }
                }
            }, onFailure = { toast("Erro: ${it.message}") })
        }
    }
}

private fun MainActivity.readZip(uri: Uri): List<Pair<String, ByteArray>> {
    val out = ArrayList<Pair<String, ByteArray>>()
    var total = 0L
    val stream = contentResolver.openInputStream(uri) ?: throw IOException("Não foi possível abrir o ZIP")
    ZipInputStream(stream.buffered()).use { zis ->
        var e = zis.nextEntry
        while (e != null) {
            val name = e.name.replace('\\', '/')
            val segs = name.split('/')
            val skip = e.isDirectory || name.startsWith("__MACOSX/") || name.endsWith(".DS_Store") ||
                segs.contains(".git")
            if (!skip) {
                require(segs.none { it == ".." }) { "ZIP com caminho inseguro: $name" }
                val data = zis.readBytes()
                total += data.size
                require(total <= 80_000_000) { "ZIP descompactado passa de 80 MB" }
                require(out.size < 3000) { "ZIP com mais de 3000 arquivos" }
                out.add(name to data)
            }
            zis.closeEntry()
            e = zis.nextEntry
        }
    }
    return out
}

fun MainActivity.uploadZip(repo: String, dir: String) {
    pickFile(false, "application/zip") { uris ->
        val uri = uris.first()
        withProgress("Lendo ZIP…", { _ -> readZip(uri) }) { res ->
            res.fold(onSuccess = { files -> confirmZip(repo, dir, files) }, onFailure = { toast("Erro: ${it.message}") })
        }
    }
}

private fun MainActivity.confirmZip(repo: String, dir: String, files: List<Pair<String, ByteArray>>) {
    if (files.isEmpty()) { toast("ZIP vazio"); return }
    val roots = files.map { it.first.substringBefore('/') }.distinct()
    val hasRoot = roots.size == 1 && files.all { it.first.contains('/') }

    val box = vbox(16)
    box.add(tv("${files.size} arquivo(s) • ${fmtSize(files.sumOf { it.second.size.toLong() })}\nDestino: /$dir", 13f, GRAY), 0)
    val strip = CheckBox(this).apply {
        text = "Remover a pasta raiz \"${roots[0]}\" do ZIP"
        isChecked = true
        setTextColor(Color.WHITE)
    }
    if (hasRoot) box.addView(strip)
    val input = box.add(ed("Mensagem do commit").apply { setText("Envia projeto (${files.size} arquivos)") })

    AlertDialog.Builder(this).setTitle("Enviar projeto").setView(box)
        .setPositiveButton("Enviar") { _, _ ->
            val msg = input.text.toString().ifBlank { "Envia projeto" }
            val doStrip = hasRoot && strip.isChecked
            commitChanges(repo, msg) { _ ->
                files.map { (p, b) ->
                    val rel = if (doStrip) p.substringAfter('/') else p
                    GitData.Change(if (dir.isEmpty()) rel else "$dir/$rel", b)
                }
            }
        }.setNegativeButton("Cancelar", null).show()
}

// ---------------------------------------------------------------- branches e repositórios

fun MainActivity.branchDialog(repo: String) {
    async({
        val a = GH.json(this, "/repos/$repo/branches?per_page=100") as JSONArray
        List(a.length()) { a.getJSONObject(it).str("name") }
    }) { res ->
        res.fold(onSuccess = { names ->
            val current = prefs.getString("gh_branch:$repo", "") ?: ""
            val labels = (names.map { if (it == current) "✓ $it" else it } + "➕ Criar nova branch…").toTypedArray()
            AlertDialog.Builder(this).setTitle("Branch ativa").setItems(labels) { _, i ->
                if (i == names.size) newBranchDialog(repo)
                else { GitData.setBranch(this, repo, names[i]); toast("Branch: ${names[i]} (reabra o Código)") }
            }.show()
        }, onFailure = { toast("Erro: ${it.message}") })
    }
}

private fun MainActivity.newBranchDialog(repo: String) {
    promptText("Nome da nova branch", "") { name ->
        if (name.isEmpty() || name.contains(' ')) { toast("Nome inválido"); return@promptText }
        async({
            val from = GitData.branch(this, repo)
            GitData.createBranch(this, repo, name, from)
            GitData.setBranch(this, repo, name)
            from
        }) { res ->
            res.fold(
                onSuccess = { toast("Branch '$name' criada a partir de '$it' e selecionada") },
                onFailure = { toast("Erro: ${it.message}") }
            )
        }
    }
}

fun MainActivity.createRepoDialog() {
    val box = vbox(16)
    val name = box.add(ed("nome-do-repositorio"), 0)
    val desc = box.add(ed("Descrição (opcional)"))
    val priv = CheckBox(this).apply { text = "Privado"; isChecked = true; setTextColor(Color.WHITE) }
    val readme = CheckBox(this).apply { text = "Criar com README"; isChecked = true; setTextColor(Color.WHITE) }
    box.addView(priv)
    box.addView(readme)
    AlertDialog.Builder(this).setTitle("Novo repositório").setView(box)
        .setPositiveButton("Criar") { _, _ ->
            val n = name.text.toString().trim()
            if (n.isEmpty() || n.contains(' ')) { toast("Nome inválido"); return@setPositiveButton }
            val body = JSONObject().put("name", n).put("description", desc.text.toString().trim())
                .put("private", priv.isChecked).put("auto_init", readme.isChecked).toString()
            async({
                val r = GH.call(this, "POST", "/user/repos", body)
                if (r.code !in 200..299) throw IOException(GH.errorMsg(r))
                JSONObject(r.body).str("full_name")
            }) { res ->
                res.fold(onSuccess = { toast("Criado: $it"); repoScreen(it) }, onFailure = {
                    AlertDialog.Builder(this).setTitle("❌ Não foi possível criar")
                        .setMessage("${it.message}\n\nSe der 403/404, crie o repositório pelo site do GitHub ou use um token clássico com o escopo \"repo\".")
                        .setPositiveButton("OK", null).show()
                })
            }
        }.setNegativeButton("Cancelar", null).show()
}
