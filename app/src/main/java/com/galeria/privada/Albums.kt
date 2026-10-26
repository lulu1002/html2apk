package com.galeria.privada

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Álbuns (sub-abas) criados pelo usuário. Os arquivos ficam na mesma pasta; só guardamos a associação. */
object Albums {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("albums", Context.MODE_PRIVATE)
    private fun key(video: Boolean) = if (video) "list_video" else "list_photo"

    fun list(ctx: Context, video: Boolean): List<String> {
        val arr = JSONArray(prefs(ctx).getString(key(video), "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun saveList(ctx: Context, video: Boolean, l: List<String>) {
        prefs(ctx).edit().putString(key(video), JSONArray(l).toString()).apply()
    }

    fun assignments(ctx: Context): Map<String, String> {
        val o = JSONObject(prefs(ctx).getString("assign", "{}"))
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    private fun saveAssign(ctx: Context, m: Map<String, String>) {
        prefs(ctx).edit().putString("assign", JSONObject(m).toString()).apply()
    }

    /** Retorna mensagem de erro, ou null se criou. */
    fun add(ctx: Context, video: Boolean, raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return "Digite um nome"
        if (name.length > 30) return "Nome muito longo (máx. 30)"
        val cur = list(ctx, video)
        if (name.equals("Todos", true) || cur.any { it.equals(name, true) })
            return "Já existe um álbum com esse nome"
        saveList(ctx, video, cur + name)
        return null
    }

    fun rename(ctx: Context, video: Boolean, old: String, raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return "Digite um nome"
        if (name.length > 30) return "Nome muito longo (máx. 30)"
        val cur = list(ctx, video)
        if (name.equals("Todos", true) || cur.any { it != old && it.equals(name, true) })
            return "Já existe um álbum com esse nome"
        saveList(ctx, video, cur.map { if (it == old) name else it })
        saveAssign(ctx, assignments(ctx).mapValues { if (it.value == old) name else it.value })
        return null
    }

    /** Apaga só o álbum; os arquivos voltam para "Todos". */
    fun delete(ctx: Context, video: Boolean, name: String) {
        saveList(ctx, video, list(ctx, video).filter { it != name })
        saveAssign(ctx, assignments(ctx).filterValues { it != name })
    }

    fun move(ctx: Context, files: List<File>, album: String?) {
        val m = assignments(ctx).toMutableMap()
        files.forEach { if (album == null) m.remove(it.name) else m[it.name] = album }
        saveAssign(ctx, m)
    }

    fun forget(ctx: Context, names: List<String>) {
        val m = assignments(ctx).toMutableMap()
        if (names.any { m.remove(it) != null }) saveAssign(ctx, m)
    }

    fun files(ctx: Context, video: Boolean, album: String?): List<File> {
        val base = Vault.listByType(ctx, video)
        if (album == null) return base
        val a = assignments(ctx)
        return base.filter { a[it.name] == album }
    }
}
