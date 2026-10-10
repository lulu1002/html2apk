package com.example.gerenciador

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

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
 * Acesso rápido ao SAF: UMA consulta traz todos os filhos de uma pasta com nome, tipo,
 * tamanho e data. (DocumentFile faz uma consulta por propriedade por arquivo — muito lento.)
 */
object Fs {
    private val COLS = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED
    )

    private fun read(c: Cursor, uri: Uri): Entry = Entry(
        uri = uri,
        name = c.getString(1) ?: c.getString(0) ?: "?",
        mime = c.getString(2) ?: "",
        size = if (c.isNull(3)) -1L else c.getLong(3),
        modified = if (c.isNull(4)) 0L else c.getLong(4)
    )

    /** Lista os filhos de [dir] (URI de documento dentro de uma árvore). Null em caso de erro. */
    fun list(ctx: Context, dir: Uri): List<Entry>? {
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

    fun stat(ctx: Context, docUri: Uri): Entry? = try {
        ctx.contentResolver.query(docUri, COLS, null, null, null)?.use { c ->
            if (c.moveToFirst()) read(c, docUri) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Pasta raiz de uma árvore autorizada pelo usuário. */
    fun root(ctx: Context, tree: Uri): Entry? = try {
        stat(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
    } catch (e: Exception) {
        null
    }

    fun createDir(ctx: Context, parent: Uri, name: String): Uri? = try {
        DocumentsContract.createDocument(ctx.contentResolver, parent, Document.MIME_TYPE_DIR, name)
    } catch (e: Exception) {
        null
    }

    fun createFile(ctx: Context, parent: Uri, mime: String, name: String): Uri? = try {
        DocumentsContract.createDocument(ctx.contentResolver, parent, mime, name)
    } catch (e: Exception) {
        null
    }

    fun delete(ctx: Context, uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(ctx.contentResolver, uri)
    } catch (e: Exception) {
        false
    }

    fun rename(ctx: Context, uri: Uri, name: String): Boolean = try {
        DocumentsContract.renameDocument(ctx.contentResolver, uri, name) != null
    } catch (e: Exception) {
        false
    }

    fun move(ctx: Context, src: Uri, srcParent: Uri, dest: Uri): Boolean = try {
        DocumentsContract.moveDocument(ctx.contentResolver, src, srcParent, dest) != null
    } catch (e: Exception) {
        false
    }
}
