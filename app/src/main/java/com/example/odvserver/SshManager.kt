package com.example.odvserver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import java.util.concurrent.TimeoutException

class SshManager {

    private var activeClient: SSHClient? = null
    private var currentHost: String = ""
    private var currentUser: String = ""
    private var currentPass: String = ""

    // mutex to prevent concurrent connection attempts
    private val mutex = Mutex()

    // helper to get or create connection
    private suspend fun getConnectedClient(host: String, user: String, pass: String): SSHClient {
        mutex.withLock {
            // check if we can reuse
            if (activeClient != null && activeClient!!.isConnected &&
                currentHost == host && currentUser == user && currentPass == pass) {
                return activeClient!!
            }

            // clean up old if exists
            try {
                activeClient?.disconnect()
            } catch (e: Exception) {
                // ignore close err
            }

            // new connection
            val ssh = SSHClient()


            //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
            // warning: promiscuous verifier is insecure used only for local testing
            // for prod use consoleknownhostsverifier or similar
            //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!


            ssh.addHostKeyVerifier(PromiscuousVerifier())

            // connect
            ssh.connect(host)
            ssh.authPassword(user, pass)

            // save state
            activeClient = ssh
            currentHost = host
            currentUser = user
            currentPass = pass

            return ssh
        }
    }

    suspend fun sendCommand(
        host: String,
        user: String,
        pass: String,
        command: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val ssh = getConnectedClient(host, user, pass)

            // open channel on existing connection
            val session = ssh.startSession()
            try {
                val cmd = session.exec(command)

                val outputDeferred = async { cmd.inputStream.reader().readText() }
                val errorDeferred = async { cmd.errorStream.reader().readText() }

                cmd.join(10, TimeUnit.SECONDS)

                if (cmd.exitStatus == null) {
                    throw TimeoutException("Command timed out (server took too long)")
                }

                val output = outputDeferred.await()
                val error = errorDeferred.await()

                if (output.isNotEmpty()) output else error
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            // force reset on error
            disconnect()
            // handle null message
            "Err: ${e.message ?: e.toString()}"
        }
    }

    // editor
    suspend fun saveFileContent(
        host: String,
        user: String,
        pass: String,
        filePath: String,
        content: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val ssh = getConnectedClient(host, user, pass)
            val session = ssh.startSession()
            try {
                // using cat insted of echo for file stream
                val cmd = session.exec("cat > \"$filePath\"")

                val outputStream = cmd.outputStream
                // content
                outputStream.write(content.toByteArray())
                outputStream.flush()
                outputStream.close() // end of file

                cmd.join(5, TimeUnit.SECONDS)

                val error = cmd.errorStream.reader().readText()
                if (error.isBlank()) "File saved successfully" else "Err saving: $error"
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            disconnect()
            "Err: ${e.message ?: e.toString()}"
        }
    }

    // cleanup
    fun disconnect() {
        try {
            activeClient?.disconnect()
        } catch (e: Exception) {
            // ignore
        } finally {
            activeClient = null
            currentHost = ""
        }
    }

    // for the connect bug so you dont need to hit connect every time
    fun isConnected(): Boolean {
        return activeClient?.isConnected == true
    }
}