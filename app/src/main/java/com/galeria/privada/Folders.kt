package com.galeria.privada

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * Pastas reais dentro do cofre (um nível, sem sub-pastas dentro de pastas).
 * Mover um arquivo para uma pasta o move de verdade no armazenamento, não é só uma marcação.
 */
object Folders {

    private fun sanitize(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || name.length > 40) return null
        if (name == "." || name == "..") return null
        if (name.equals("root", ignoreCase = true)) return null
        if (name.any { it == '/' || it == '\\' }) return null
        return name
    }

    fun list(ctx: Context): List<File> =
        Vault.dir(ctx).listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun byName(ctx: Context, name: String): File? = list(ctx).firstOrNull { it.name == name }

    /** Retorna mensagem de erro, ou null se criou com sucesso. */
    fun create(ctx: Context, raw: String): String? {
        val name = sanitize(raw) ?: return "Nome inválido"
        if (list(ctx).any { it.name.equals(name, true) }) return "Já existe uma pasta com esse nome"
        return if (File(Vault.dir(ctx), name).mkdir()) null else "Não foi possível criar a pasta"
    }

    fun rename(ctx: Context, folder: File, raw: String): String? {
        val name = sanitize(raw) ?: return "Nome inválido"
        if (list(ctx).any { it.name.equals(name, true) && it.name != folder.name })
            return "Já existe uma pasta com esse nome"
        val dest = File(Vault.dir(ctx), name)
        return if (folder.renameTo(dest)) null else "Não foi possível renomear"
    }

    private fun uniqueName(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (target.exists()) {
            target = File(dir, "${stem}_$n$ext")
            n++
        }
        return target
    }

    /** Move os arquivos da pasta de volta para "Sem pasta" (raiz) e apaga a pasta vazia. */
    fun delete(ctx: Context, folder: File) {
        folder.listFiles()?.forEach { f ->
            val dest = uniqueName(Vault.dir(ctx), f.name)
            f.renameTo(dest)
        }
        folder.delete()
    }

    /** Move arquivos para uma pasta (null = "Sem pasta"/raiz). Retorna quantos foram movidos. */
    fun move(ctx: Context, files: List<File>, target: File?): Int {
        val destDir = target ?: Vault.dir(ctx)
        val destCanon = destDir.canonicalPath
        var ok = 0
        files.forEach { f ->
            if (f.parentFile?.canonicalPath == destCanon) {
                ok++
                return@forEach
            }
            val dest = uniqueName(destDir, f.name)
            if (f.renameTo(dest)) ok++
        }
        return ok
    }

    /** Arquivos soltos na raiz (não estão em nenhuma pasta). */
    fun rootFiles(ctx: Context): List<File> = Vault.list(ctx)

    /** Todos os arquivos do cofre: raiz + todas as pastas. */
    fun allFiles(ctx: Context): List<File> {
        val result = mutableListOf<File>()
        result.addAll(rootFiles(ctx))
        list(ctx).forEach { folder ->
            folder.listFiles()?.filter { it.isFile }?.let { result.addAll(it) }
        }
        return result
    }

    /**
     * Arquivos de um escopo, filtrados por tipo.
     * scope: null = tudo (raiz + pastas), "" = só "Sem pasta" (raiz), outro = nome da pasta.
     */
    fun filesFor(ctx: Context, scope: String?, video: Boolean): List<File> {
        val list = when (scope) {
            null -> allFiles(ctx)
            "" -> rootFiles(ctx)
            else -> byName(ctx, scope)?.listFiles()?.filter { it.isFile } ?: emptyList()
        }
        return list.filter { Vault.isVideo(it) == video }
    }

    /** Migra o antigo sistema de álbuns (tags) para pastas reais. Roda uma vez só. */
    fun migrateOldAlbumsIfNeeded(ctx: Context) {
        val old = ctx.getSharedPreferences("albums", Context.MODE_PRIVATE)
        if (old.getBoolean("migrated_v2", false)) return
        val assignRaw = old.getString("assign", null)
        if (!assignRaw.isNullOrEmpty() && assignRaw != "{}") {
            try {
                val o = JSONObject(assignRaw)
                val keys = o.keys()
                while (keys.hasNext()) {
                    val fileName = keys.next()
                    val albumName = o.getString(fileName)
                    val safe = sanitize(albumName) ?: continue
                    if (byName(ctx, safe) == null) create(ctx, safe)
                    val folder = byName(ctx, safe) ?: continue
                    val f = File(Vault.dir(ctx), fileName)
                    if (f.exists() && f.isFile) {
                        val dest = uniqueName(folder, fileName)
                        f.renameTo(dest)
                    }
                }
            } catch (e: Exception) {
            }
        }
        old.edit()
            .putBoolean("migrated_v2", true)
            .remove("list_video").remove("list_photo").remove("assign")
            .apply()
    }
}
