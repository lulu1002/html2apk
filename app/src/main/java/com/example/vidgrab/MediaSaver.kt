package com.example.vidgrab

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** path != null => arquivo na pasta privada do app */
data class SavedFile(val uri: String, val mime: String, val path: String? = null)

object AppSettings {
    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Padrão: pasta privada ligada */
    fun isPrivate(ctx: Context): Boolean = prefs(ctx).getBoolean("private_dir", true)

    fun setPrivate(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("private_dir", value).apply()
    }
}

object MediaSaver {
    private const val FOLDER = "VidGrab"

    suspend fun save(ctx: Context, file: File): SavedFile = withContext(Dispatchers.IO) {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

        if (AppSettings.isPrivate(ctx)) savePrivate(ctx, file, mime)
        else if (Build.VERSION.SDK_INT >= 29) saveToMediaStore(ctx, file, mime)
        else saveLegacy(ctx, file, mime)
    }

    /** Pasta do app (Android/data/<pkg>/files/VidGrab) + .nomedia: fora da galeria. */
    private fun savePrivate(ctx: Context, file: File, mime: String): SavedFile {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, FOLDER).apply { mkdirs() }
        try {
            File(dir, ".nomedia").createNewFile()
        } catch (_: Exception) {
        }
        val dest = uniqueFile(dir, file.name)
        file.copyTo(dest, overwrite = false)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", dest)
        return SavedFile(uri.toString(), mime, dest.absolutePath)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = f.nameWithoutExtension
        val ext = f.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }

    private fun saveToMediaStore(ctx: Context, file: File, mime: String): SavedFile {
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
        return SavedFile(uri.toString(), mime)
    }

    private suspend fun saveLegacy(ctx: Context, file: File, mime: String): SavedFile {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        dir.mkdirs()
        val dest = File(dir, file.name)
        file.copyTo(dest, overwrite = true)
        val uri = scan(ctx, dest)
        return SavedFile(uri?.toString() ?: "", mime)
    }

    private suspend fun scan(ctx: Context, f: File): Uri? = suspendCoroutine { c ->
        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null) { _, uri -> c.resume(uri) }
    }
}
