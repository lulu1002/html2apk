package com.tameczada.sshclient

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.json.JSONArray
import org.json.JSONObject
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

    private fun feed(t: String) {
        val sb = StringBuilder(term)
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

    fun close() {
        thread { runCatching { sh?.disconnect(); f?.disconnect(); s?.disconnect() }; ex.shutdown() }
    }
}
