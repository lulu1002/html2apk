package com.example.vidgrab

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class SavedFile(val uri: String, val mime: String)

object MediaSaver {
    private const val FOLDER = "VidGrab"

    suspend fun save(ctx: Context, file: File): SavedFile = withContext(Dispatchers.IO) {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Não consegui criar o arquivo em Downloads")
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            SavedFile(uri.toString(), mime)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            dir.mkdirs()
            val dest = File(dir, file.name)
            file.copyTo(dest, overwrite = true)
            val uri = scan(ctx, dest)
            SavedFile(uri?.toString() ?: "", mime)
        }
    }

    private suspend fun scan(ctx: Context, f: File): Uri? = suspendCoroutine { c ->
        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null) { _, uri -> c.resume(uri) }
    }
}
