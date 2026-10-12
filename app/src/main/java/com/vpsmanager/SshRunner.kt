package com.vpsmanager

import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Lançada quando o servidor ainda não é confiável (primeira vez) ou mudou de chave. */
class HostKeyException(
    val fingerprint: String,
    val keyType: String,
    val changed: Boolean
) : Exception(if (changed) "Fingerprint do servidor mudou" else "Servidor ainda não confiável")

/** Mesmo formato do OpenSSH: SHA256:base64-sem-padding. */
fun fingerprintOf(key: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key)
    return "SHA256:" + Base64.encodeToString(digest, Base64.NO_PADDING or Base64.NO_WRAP)
}

private fun keyTypeOf(key: ByteArray): String = try {
    val len = ByteBuffer.wrap(key).int
    String(key, 4, len, Charsets.US_ASCII)
} catch (e: Exception) {
    "desconhecido"
}

/**
 * Repositório que só aceita a chave cujo fingerprint já foi confiado.
 * Com StrictHostKeyChecking=yes, o JSch aborta ANTES da autenticação,
 * então a senha nunca é enviada a um servidor não verificado.
 */
private class PinnedHostKeyRepository(private val expected: String) : HostKeyRepository {
    var seenFingerprint: String? = null
    var seenType: String = ""

    override fun check(host: String?, key: ByteArray): Int {
        val fp = fingerprintOf(key)
        seenFingerprint = fp
        seenType = keyTypeOf(key)
        return when {
            expected.isBlank() -> HostKeyRepository.NOT_INCLUDED
            expected == fp -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID(): String = "pinned-fingerprint"
    override fun getHostKey(): Array<HostKey> = arrayOf()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = arrayOf()
}

object SshRunner {

    /** Abre uma sessão SSH já autenticada (bloqueante: chame em Dispatchers.IO). */
    fun openSession(h: Host): Session {
        val jsch = JSch()
        if (h.privateKey.isNotBlank()) {
            jsch.addIdentity("key-${h.id}", h.privateKey.toByteArray(), null, null)
        }
        val repo = PinnedHostKeyRepository(h.fingerprint)
        val session = jsch.getSession(h.user, h.host, h.port)
        if (h.password.isNotBlank()) session.setPassword(h.password)
        session.setHostKeyRepository(repo)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setServerAliveInterval(30_000)
        try {
            session.connect(10_000)
        } catch (e: JSchException) {
            session.disconnect()
            val seen = repo.seenFingerprint
            if (seen != null && seen != h.fingerprint) {
                throw HostKeyException(seen, repo.seenType, changed = h.fingerprint.isNotBlank())
            }
            throw e
        }
        return session
    }

    /** Executa um comando e devolve stdout + stderr. */
    suspend fun exec(h: Host, command: String): String = withContext(Dispatchers.IO) {
        val session = openSession(h)
        try {
            val channel = session.openChannel("exec") as ChannelExec
            val out = ByteArrayOutputStream()
            channel.setCommand(command)
            channel.setOutputStream(out)
            channel.setErrStream(out)
            channel.connect()
            var waited = 0
            while (!channel.isClosed && waited < 60_000) {
                delay(100)
                waited += 100
            }
            channel.disconnect()
            out.toString("UTF-8").ifBlank { "(sem saída)" }
        } finally {
            session.disconnect()
        }
    }
}
