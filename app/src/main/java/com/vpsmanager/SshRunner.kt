package com.vpsmanager

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object SshRunner {

    /** Executa um comando e devolve stdout + stderr. */
    suspend fun exec(h: Host, command: String): String = withContext(Dispatchers.IO) {
        val jsch = JSch()
        if (h.privateKey.isNotBlank()) {
            jsch.addIdentity("key-${h.id}", h.privateKey.toByteArray(), null, null)
        }
        val session = jsch.getSession(h.user, h.host, h.port)
        if (h.password.isNotBlank()) session.setPassword(h.password)
        // TODO: trocar por verificação de fingerprint (trust on first use)
        session.setConfig("StrictHostKeyChecking", "no")
        try {
            session.connect(10_000)
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
