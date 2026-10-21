package com.tameczada.sshclient

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.Vector
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class Conn(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "", val host: String = "", val port: Int = 22,
    val user: String = "", val pass: String = "", val key: String = ""
)

data class Entry(val name: String, val dir: Boolean, val size: Long, val link: Boolean)

data class Sc(
    val id: String = UUID.randomUUID().toString(),
    val label: String, val cmd: String, val enter: Boolean = true
)

object Store {
    fun load(c: Context): List<Conn> = try {
        val a = JSONArray(c.getSharedPreferences("s", 0).getString("c", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Conn(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"),
                o.getString("user"), o.getString("pass"), o.getString("key"))
        }
    } catch (e: Exception) { emptyList() }

    fun save(c: Context, l: List<Conn>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host)
                .put("port", it.port).put("user", it.user).put("pass", it.pass).put("key", it.key))
        }
        c.getSharedPreferences("s", 0).edit().putString("c", a.toString()).apply()
    }
}

fun join(p: String, n: String) = if (p.endsWith("/")) p + n else "$p/$n"

private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\u001B[()][A-Za-z0-9]|\u001B[=>]")

class Ssh(val c: Conn) {
    var term by mutableStateOf("")
    var cwd = ""
    private var s: Session? = null
    private var f: ChannelSftp? = null
    private var sh: ChannelShell? = null
    private var out: OutputStream? = null
    private val ex = Executors.newSingleThreadExecutor()

    fun connect() {
        val j = JSch()
        if (c.key.isNotBlank())
            j.addIdentity("k", (c.key.trim() + "\n").toByteArray(), null,
                c.pass.takeIf { it.isNotEmpty() }?.toByteArray())
        val x = j.getSession(c.user, c.host, c.port)
        if (c.key.isBlank()) x.setPassword(c.pass)
        x.setConfig("StrictHostKeyChecking", "no")
        x.connect(15000)
        s = x
        f = (x.openChannel("sftp") as ChannelSftp).also { it.connect(15000) }
        val ch = x.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm", 80, 24, 0, 0)
        val i = ch.inputStream
        out = ch.outputStream
        ch.connect(15000)
        sh = ch
        thread {
            try {
                val r = i.reader(); val b = CharArray(4096)
                while (true) { val n = r.read(b); if (n < 0) break; feed(String(b, 0, n)) }
            } catch (_: Exception) {}
            feed("\n[sessão encerrada]\n")
        }
    }

    private fun feed(t0: String) {
        var t = t0
        var base = term
        val k = maxOf(t.lastIndexOf("\u001B[2J"), t.lastIndexOf("\u001B[3J"))
        if (k >= 0) { t = t.substring(k + 4); base = "" }
        val sb = StringBuilder(base)
        for (ch in ansi.replace(t, "").replace("\r\n", "\n")) when (ch) {
            '\b' -> if (sb.isNotEmpty() && sb.last() != '\n') sb.setLength(sb.length - 1)
            '\r', '\u0007' -> {}
            else -> sb.append(ch)
        }
        term = if (sb.length > 30000) sb.substring(sb.length - 30000) else sb.toString()
    }

    fun send(t: String) = ex.execute {
        try { out?.write(t.toByteArray()); out?.flush() } catch (_: Exception) {}
    }

    @Synchronized fun home(): String = f!!.pwd()
    @Synchronized fun real(p: String): String = f!!.realpath(p)

    @Synchronized fun list(p: String): List<Entry> =
        (f!!.ls(p) as Vector<*>).map { it as ChannelSftp.LsEntry }
            .filter { it.filename != "." && it.filename != ".." }
            .map { e ->
                val lnk = e.attrs.isLink
                val d = e.attrs.isDir ||
                    (lnk && runCatching { f!!.stat(join(p, e.filename)).isDir }.getOrDefault(false))
                Entry(e.filename, d, e.attrs.size, lnk)
            }
            .sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))

    @Synchronized fun read(p: String): String = f!!.get(p).use { String(it.readBytes()) }
    @Synchronized fun write(p: String, t: String) = f!!.put(t.toByteArray().inputStream(), p)
    @Synchronized fun mkdir(p: String) = f!!.mkdir(p)
    @Synchronized fun move(a: String, b: String) = f!!.rename(a, b)

    @Synchronized fun delete(p: String, dir: Boolean) {
        if (dir) {
            list(p).forEach { delete(join(p, it.name), it.dir && !it.link) }
            f!!.rmdir(p)
        } else f!!.rm(p)
    }

    fun download(p: String, dest: File, prog: (Long, Long) -> Unit) {
        synchronized(this) {
            val total = f!!.stat(p).size
            FileOutputStream(dest).use { o ->
                f!!.get(p, o, object : SftpProgressMonitor {
                    var d = 0L
                    var last = -1L
                    override fun init(op: Int, src: String?, dst: String?, max: Long) {}
                    override fun count(c: Long): Boolean {
                        d += c
                        val pc = if (total > 0) d * 100 / total else 0
                        if (pc != last) { last = pc; prog(d, total) }
                        return true
                    }
                    override fun end() {}
                })
            }
        }
    }

    fun close() {
        thread { runCatching { sh?.disconnect(); f?.disconnect(); s?.disconnect() }; ex.shutdown() }
    }
}

fun expand(s: String): String {
    val r = s.replace("<ESC>", "\u001B").replace("<TAB>", "\t").replace("<ENTER>", "\n")
        .replace("<UP>", "\u001B[A").replace("<DOWN>", "\u001B[B")
        .replace("<RIGHT>", "\u001B[C").replace("<LEFT>", "\u001B[D")
    return Regex("<C-([a-zA-Z])>").replace(r) { m ->
        (m.groupValues[1][0].uppercaseChar().code - 64).toChar().toString()
    }
}

fun defaultScs() = listOf(
    Sc(label = "ls -la", cmd = "ls -la"),
    Sc(label = "cd ..", cmd = "cd .."),
    Sc(label = "pwd", cmd = "pwd"),
    Sc(label = "clear", cmd = "clear"),
    Sc(label = "Ctrl+L", cmd = "<C-l>", enter = false),
    Sc(label = "Ctrl+A", cmd = "<C-a>", enter = false),
    Sc(label = "Ctrl+E", cmd = "<C-e>", enter = false),
    Sc(label = "Ctrl+R", cmd = "<C-r>", enter = false),
    Sc(label = "q", cmd = "q", enter = false),
    Sc(label = "vim :q!", cmd = "<ESC>:q!<ENTER>", enter = false),
    Sc(label = "htop", cmd = "htop"),
    Sc(label = "top", cmd = "top"),
    Sc(label = "df -h", cmd = "df -h"),
    Sc(label = "free -h", cmd = "free -h"),
    Sc(label = "du -sh *", cmd = "du -sh *"),
    Sc(label = "ps aux", cmd = "ps aux"),
    Sc(label = "ip a", cmd = "ip a"),
    Sc(label = "ping", cmd = "ping -c 4 8.8.8.8"),
    Sc(label = "uname", cmd = "uname -a"),
    Sc(label = "history", cmd = "history | tail -30"),
    Sc(label = "git status", cmd = "git status"),
    Sc(label = "git pull", cmd = "git pull"),
    Sc(label = "docker ps", cmd = "docker ps"),
    Sc(label = "sudo", cmd = "sudo ", enter = false),
    Sc(label = "nano", cmd = "nano ", enter = false),
    Sc(label = "tail -f", cmd = "tail -f ", enter = false),
    Sc(label = "systemctl", cmd = "systemctl status ", enter = false),
    Sc(label = "~/", cmd = "cd ~", enter = true)
)

object ScStore {
    fun load(c: Context): List<Sc> {
        val raw = c.getSharedPreferences("s", 0).getString("sc", null) ?: return defaultScs()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Sc(o.getString("id"), o.getString("label"), o.getString("cmd"), o.getBoolean("enter"))
            }
        } catch (e: Exception) { defaultScs() }
    }

    fun save(c: Context, l: List<Sc>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("label", it.label).put("cmd", it.cmd).put("enter", it.enter)) }
        c.getSharedPreferences("s", 0).edit().putString("sc", a.toString()).apply()
    }
}
