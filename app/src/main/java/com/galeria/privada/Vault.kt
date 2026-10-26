package com.galeria.privada

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Estado de desbloqueio em memória (some quando o processo morre). */
object Session {
    private const val WINDOW_MS = 5 * 60 * 1000L

    @Volatile var appUnlocked = false
    @Volatile private var providerUntil = 0L

    fun unlock() {
        appUnlocked = true
        touch()
    }

    fun touch() {
        providerUntil = SystemClock.elapsedRealtime() + WINDOW_MS
    }

    fun providerOpen() = SystemClock.elapsedRealtime() < providerUntil

    fun lockAll() {
        appUnlocked = false
        providerUntil = 0L
    }
}

object Vault {
    private const val PREFS = "vault_prefs"
    private const val ITER = 120_000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dir(ctx: Context): File = File(ctx.filesDir, "vault").also { it.mkdirs() }

    fun hasPassword(ctx: Context) = prefs(ctx).contains("hash")

    private fun derive(pw: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pw.toCharArray(), salt, ITER, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun setPassword(ctx: Context, pw: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(derive(pw, salt), Base64.NO_WRAP))
            .apply()
    }

    fun check(ctx: Context, pw: String): Boolean {
        val p = prefs(ctx)
        val salt = Base64.decode(p.getString("salt", null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(p.getString("hash", null) ?: return false, Base64.NO_WRAP)
        return MessageDigest.isEqual(expected, derive(pw, salt))
    }

    fun lockRemainingMs(ctx: Context): Long =
        (prefs(ctx).getLong("locked_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun registerFail(ctx: Context) {
        val p = prefs(ctx)
        val fails = p.getInt("fails", 0) + 1
        val e = p.edit().putInt("fails", fails)
        if (fails >= 5) e.putLong("locked_until", System.currentTimeMillis() + 30_000L)
        e.apply()
    }

    fun resetFails(ctx: Context) {
        prefs(ctx).edit().putInt("fails", 0).putLong("locked_until", 0L).apply()
    }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun mime(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

    fun isVideo(file: File) = mime(file).startsWith("video/")
    fun isImage(file: File) = mime(file).startsWith("image/")

    fun listByType(ctx: Context, video: Boolean): List<File> =
        list(ctx).filter { isVideo(it) == video }

    /** Copia um arquivo do cofre para a memória do celular (Imagens/Vídeos/Download > GaleriaPrivada). */
    fun exportToPhone(ctx: Context, file: File): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportQ(ctx, file) else exportLegacy(ctx, file)
    } catch (e: Exception) {
        false
    }

    private fun exportQ(ctx: Context, file: File): Boolean {
        val cr = ctx.contentResolver
        val vol = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val (collection, folder) = when {
            isVideo(file) -> MediaStore.Video.Media.getContentUri(vol) to Environment.DIRECTORY_MOVIES
            isImage(file) -> MediaStore.Images.Media.getContentUri(vol) to Environment.DIRECTORY_PICTURES
            else -> MediaStore.Downloads.getContentUri(vol) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(file))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/GaleriaPrivada")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(collection, values) ?: return false
        try {
            val out = cr.openOutputStream(uri) ?: throw java.io.IOException("sem saída")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            return false
        }
        cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return true
    }

    @Suppress("DEPRECATION")
    private fun exportLegacy(ctx: Context, file: File): Boolean {
        val type = when {
            isVideo(file) -> Environment.DIRECTORY_MOVIES
            isImage(file) -> Environment.DIRECTORY_PICTURES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(type), "GaleriaPrivada")
        dir.mkdirs()
        var target = File(dir, file.name)
        var n = 1
        while (target.exists()) {
            val ext = file.extension
            target = File(dir, if (ext.isEmpty()) "${file.nameWithoutExtension}_$n" else "${file.nameWithoutExtension}_$n.$ext")
            n++
        }
        file.copyTo(target)
        MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), null, null)
        return true
    }

    fun notifyChanged(ctx: Context) {
        val uri = DocumentsContract.buildChildDocumentsUri(ctx.packageName + ".vault", "root")
        ctx.contentResolver.notifyChange(uri, null)
    }

    /** Copia o arquivo escolhido para dentro do cofre (pasta privada do app). */
    fun import(ctx: Context, uri: Uri): Boolean {
        return try {
            val cr = ctx.contentResolver
            var name: String? = null
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
            var base = (name ?: "midia_${System.currentTimeMillis()}")
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trimStart('.')
            if (base.isEmpty()) base = "midia_${System.currentTimeMillis()}"
            if (!base.contains('.')) {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(cr.getType(uri))?.let { base += ".$it" }
            }
            val stem = base.substringBeforeLast('.', base)
            val ext = base.substringAfterLast('.', "")
            var target = File(dir(ctx), base)
            var n = 1
            while (target.exists()) {
                target = File(dir(ctx), if (ext.isEmpty()) "${stem}_$n" else "${stem}_$n.$ext")
                n++
            }
            Albums.forget(ctx, listOf(target.name))
            val input = cr.openInputStream(uri) ?: return false
            input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            true
        } catch (e: Exception) {
            false
        }
    }
}
