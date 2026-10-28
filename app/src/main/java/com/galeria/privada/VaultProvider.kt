package com.galeria.privada

import android.app.AuthenticationRequiredException
import android.app.PendingIntent
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Faz a galeria aparecer no seletor de arquivos do Android (anexar em outros apps).
 * Sem senha liberada, o seletor abre a tela de senha antes de mostrar qualquer coisa.
 */
class VaultProvider : DocumentsProvider() {

    companion object {
        private const val ROOT_ID = "vault"
        private const val ROOT_DOC = "root"
        private val ROOT_COLS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_MIME_TYPES
        )
        private val DOC_COLS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
        )
    }

    private fun cols(p: Array<out String>?, d: Array<String>): Array<String> =
        if (p != null) arrayOf(*p) else d

    private fun appName(): String =
        context!!.applicationInfo.loadLabel(context!!.packageManager).toString()

    private fun authority() = context!!.packageName + ".vault"

    override fun onCreate(): Boolean = true

    private fun requireAuth() {
        if (!Session.providerOpen()) {
            val ctx = context!!
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, UnlockActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            throw AuthenticationRequiredException(SecurityException("Senha necessária"), pi)
        }
        Session.touch()
    }

    private fun fileFor(id: String): File {
        val base = Vault.dir(context!!).canonicalFile
        val f = File(base, id).canonicalFile
        if (f.parentFile != base || !f.exists()) throw FileNotFoundException("Não encontrado: $id")
        return f
    }

    private fun addRoot(c: MatrixCursor) {
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC)
            .add(Document.COLUMN_DISPLAY_NAME, "Galeria Privada")
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            .add(Document.COLUMN_SIZE, null)
            .add(Document.COLUMN_LAST_MODIFIED, null)
            .add(Document.COLUMN_FLAGS, 0)
    }

    private fun addFile(c: MatrixCursor, f: File) {
        val thumb = if (Vault.isImage(f) || Vault.isVideo(f)) Document.FLAG_SUPPORTS_THUMBNAIL else 0
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, f.name)
            .add(Document.COLUMN_DISPLAY_NAME, f.name)
            .add(Document.COLUMN_MIME_TYPE, Vault.mime(f))
            .add(Document.COLUMN_SIZE, f.length())
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, thumb)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(cols(projection, ROOT_COLS))
        c.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_ICON, R.drawable.ic_vault)
            .add(Root.COLUMN_TITLE, appName())
            .add(Root.COLUMN_SUMMARY, "Protegida por senha")
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
            .add(Root.COLUMN_MIME_TYPES, "*/*")
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(cols(projection, DOC_COLS))
        if (documentId == ROOT_DOC) {
            addRoot(c)
        } else {
            requireAuth()
            addFile(c, fileFor(documentId))
        }
        return c
    }

    override fun queryChildDocuments(
        parentDocumentId: String, projection: Array<out String>?, sortOrder: String?
    ): Cursor {
        requireAuth()
        val c = MatrixCursor(cols(projection, DOC_COLS))
        c.setNotificationUri(
            context!!.contentResolver,
            DocumentsContract.buildChildDocumentsUri(authority(), parentDocumentId)
        )
        Vault.list(context!!).forEach { addFile(c, it) }
        return c
    }

    override fun openDocument(
        documentId: String, mode: String, signal: CancellationSignal?
    ): ParcelFileDescriptor {
        requireAuth()
        return ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openDocumentThumbnail(
        documentId: String, sizeHint: Point, signal: CancellationSignal?
    ): AssetFileDescriptor {
        requireAuth()
        val f = fileFor(documentId)
        val px = maxOf(sizeHint.x, sizeHint.y).coerceIn(128, 1024)
        val bmp = Thumbs.decode(f, px) ?: throw FileNotFoundException("Sem miniatura")
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, it)
                }
            } catch (e: Exception) {
            }
        }.start()
        return AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }
}
