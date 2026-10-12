package com.vpsmanager

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.jcraft.jsch.ChannelSftp
import java.io.IOException
import java.io.InputStream

/**
 * Lê mídia direto do servidor via SFTP, sem baixar tudo antes.
 * Cada abertura usa um canal SFTP próprio (mesma sessão) e suporta seek.
 */
@UnstableApi
class SftpDataSource(private val newChannel: () -> ChannelSftp) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var channel: ChannelSftp? = null
    private var input: InputStream? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        try {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            val path = dataSpec.uri.path ?: throw IOException("Caminho inválido")
            val ch = newChannel()
            channel = ch
            val size = ch.stat(path).size
            val pos = dataSpec.position
            if (pos > size) throw IOException("Posição além do fim do arquivo")
            input = ch.get(path, null, pos)
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - pos
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: IOException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw IOException(e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(remaining, length.toLong()).toInt()
        val n = try {
            input?.read(buffer, offset, toRead) ?: -1
        } catch (e: Exception) {
            throw IOException(e)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try { input?.close() } catch (_: Exception) {}
        try { channel?.disconnect() } catch (_: Exception) {}
        input = null
        channel = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
