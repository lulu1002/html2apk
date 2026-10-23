package com.galeria.privada

import android.content.Context
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
            val input = cr.openInputStream(uri) ?: return false
            input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            true
        } catch (e: Exception) {
            false
        }
    }
}
