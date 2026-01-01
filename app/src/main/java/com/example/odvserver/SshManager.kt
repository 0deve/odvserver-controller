package com.example.odvserver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.util.concurrent.TimeUnit

class SshManager {

    // thread
    suspend fun sendCommand(
        host: String,
        user: String,
        pass: String,
        command: String
    ): String = withContext(Dispatchers.IO) {
        val ssh = SSHClient()
        // fara cheie
        // ATENTIE este nesigur pentru retele publice
        // de preferat sa se foloseasca doar in retele locale
        ssh.addHostKeyVerifier(PromiscuousVerifier())

        try {
            // connect
            ssh.connect(host)

            // auth
            ssh.authPassword(user, pass)

            // sesiune
            val session = ssh.startSession()
            return@withContext try {
                // comanda
                val cmd = session.exec(command)

                // rezultat
                val output = cmd.inputStream.reader().readText()
                val error = cmd.errorStream.reader().readText()

                // delay
                cmd.join(5, TimeUnit.SECONDS)

                if (output.isNotEmpty()) output else error
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            return@withContext "Err: ${e.message}"
        } finally {
            if (ssh.isConnected) {
                ssh.disconnect()
            }
        }
    }
}