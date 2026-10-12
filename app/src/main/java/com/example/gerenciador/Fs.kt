package com.example.gerenciador

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import java.io.File

/** Item de uma pasta, com todos os metadados já carregados (sem consultas extras). */
data class Entry(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long
) {
    val isDir: Boolean get() = mime == Document.MIME_TYPE_DIR
}

/**
 * Acesso a arquivos com dois "backends", escolhidos pelo esquema da URI:
 *  - content://  → Storage Access Framework (pasta escolhida pelo usuário). UMA consulta traz
 *    todos os filhos (DocumentFile faz uma consulta por propriedade — muito lento).
 *  - file://     → acesso direto ao armazenamento (permissão "Acesso a todos os arquivos"),
 *    que permite abrir Downloads, a raiz do armazenamento e cartão SD.
 */
object Fs {
    private val COLS = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED
    )

    private fun isFile(u: Uri) = u.scheme == "file"
    private fun fileOf(u: Uri) = File(u.path ?: "/")

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun fileEntry(f: File): Entry {
        val dir = f.isDirectory
        val internal = Environment.getExternalStorageDirectory().absolutePath
        val name = when {
            f.absolutePath == internal -> "Armazenamento interno"
            f.name.isEmpty() -> f.absolutePath
            else -> f.name
        }
        return Entry(
            uri = Uri.fromFile(f),
            name = name,
            mime = if (dir) Document.MIME_TYPE_DIR else mimeOf(f.name),
            size = if (dir) -1L else f.length(),
            modified = f.lastModified()
        )
    }

    private fun read(c: Cursor, uri: Uri): Entry = Entry(
        uri = uri,
        name = c.getString(1) ?: c.getString(0) ?: "?",
        mime = c.getString(2) ?: "",
        size = if (c.isNull(3)) -1L else c.getLong(3),
        modified = if (c.isNull(4)) 0L else c.getLong(4)
    )

    /** Lista os filhos de [dir]. Null em caso de erro (ex.: sem permissão). */
    fun list(ctx: Context, dir: Uri): List<Entry>? {
        if (isFile(dir)) {
            val files = fileOf(dir).listFiles() ?: return null
            return files.map { fileEntry(it) }
        }
        return try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                dir, DocumentsContract.getDocumentId(dir)
            )
            val out = ArrayList<Entry>()
            val cursor = ctx.contentResolver.query(children, COLS, null, null, null) ?: return null
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    out += read(c, DocumentsContract.buildDocumentUriUsingTree(dir, id))
                }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    fun stat(ctx: Context, docUri: Uri): Entry? {
        if (isFile(docUri)) {
            val f = fileOf(docUri)
            return if (f.exists()) fileEntry(f) else null
        }
        return try {
            ctx.contentResolver.query(docUri, COLS, null, null, null)?.use { c ->
                if (c.moveToFirst()) read(c, docUri) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Pasta raiz: árvore autorizada (SAF) ou pasta do armazenamento (file://). */
    fun root(ctx: Context, tree: Uri): Entry? {
        if (isFile(tree)) return stat(ctx, tree)
        return try {
            stat(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
        } catch (e: Exception) {
            null
        }
    }

    fun createDir(ctx: Context, parent: Uri, name: String): Uri? {
        if (isFile(parent)) {
            val f = File(fileOf(parent), name)
            return if (f.mkdir()) Uri.fromFile(f) else null
        }
        return try {
            DocumentsContract.createDocument(ctx.contentResolver, parent, Document.MIME_TYPE_DIR, name)
        } catch (e: Exception) {
            null
        }
    }

    fun createFile(ctx: Context, parent: Uri, mime: String, name: String): Uri? {
        if (isFile(parent)) {
            val f = File(fileOf(parent), name)
            return try {
                if (f.createNewFile()) Uri.fromFile(f) else null
            } catch (e: Exception) {
                null
            }
        }
        return try {
            DocumentsContract.createDocument(ctx.contentResolver, parent, mime, name)
        } catch (e: Exception) {
            null
        }
    }

    fun delete(ctx: Context, uri: Uri): Boolean {
        if (isFile(uri)) return try { fileOf(uri).deleteRecursively() } catch (e: Exception) { false }
        return try {
            DocumentsContract.deleteDocument(ctx.contentResolver, uri)
        } catch (e: Exception) {
            false
        }
    }

    fun rename(ctx: Context, uri: Uri, name: String): Boolean {
        if (isFile(uri)) {
            val f = fileOf(uri)
            val target = File(f.parentFile, name)
            return !target.exists() && f.renameTo(target)
        }
        return try {
            DocumentsContract.renameDocument(ctx.contentResolver, uri, name) != null
        } catch (e: Exception) {
            false
        }
    }

    fun move(ctx: Context, src: Uri, srcParent: Uri, dest: Uri): Boolean {
        if (isFile(src) && isFile(dest)) {
            val f = fileOf(src)
            val target = File(fileOf(dest), f.name)
            return !target.exists() && f.renameTo(target)
        }
        if (isFile(src) != isFile(dest)) return false // entre backends diferentes: cai em copiar+apagar
        return try {
            DocumentsContract.moveDocument(ctx.contentResolver, src, srcParent, dest) != null
        } catch (e: Exception) {
            false
        }
    }
}
