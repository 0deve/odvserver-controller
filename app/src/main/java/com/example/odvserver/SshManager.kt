package com.example.odvserver

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.PublicKey
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import java.util.concurrent.TimeoutException

class SshManager(private val context: Context) {

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

            val hostsFile = File(context.filesDir, "known_hosts")
            if (!hostsFile.exists()) {
                hostsFile.createNewFile()
            }
            ssh.addHostKeyVerifier(TrustOnFirstUseVerifier(hostsFile))

            ssh.connect(host)
            ssh.authPassword(user, pass)

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

    // helper function to clear saved hosts
    fun clearKnownHosts(): Boolean {
        val hostsFile = File(context.filesDir, "known_hosts")
        if (hostsFile.exists()) {
            return hostsFile.delete()
        }
        return false
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


//  TOFU verification for security
//  1 checks if host is in file
//  2 if NO -> saves it and allows connection
//  3 if YES -> checks if keys match.
//  4 if CHANGED -> rejects connection

class TrustOnFirstUseVerifier(private val knownHostsFile: File) : HostKeyVerifier {

    // required by interface to help client choose key algorithm
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        val lines = if (knownHostsFile.exists()) knownHostsFile.readLines() else emptyList()
        val existingEntry = lines.find { it.startsWith("$hostname ") }

        if (existingEntry != null) {
            val parts = existingEntry.split(" ")
            // e.g. ssh-rsa
            if (parts.size >= 2) return listOf(parts[1])
        }
        return emptyList()
    }

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val keyType = KeyType.fromKey(key)

        val keyString = Base64.encodeToString(key.encoded, Base64.NO_WRAP)

        // hostname keyType keyString
        val entryLine = "$hostname $keyType $keyString"

        // read file
        val lines = if (knownHostsFile.exists()) knownHostsFile.readLines() else emptyList()

        // look for host
        val existingEntry = lines.find { it.startsWith("$hostname ") }

        if (existingEntry == null) {
            // trust and save
            knownHostsFile.appendText("$entryLine\n")
            return true
        } else {
            // verify
            // check if the stored key matches the current key
            val parts = existingEntry.split(" ")
            if (parts.size >= 3) {
                val storedKey = parts[2]
                if (storedKey == keyString) {
                    return true // Match!
                }
            }

            // KEY MISMATCH
            return false
        }
    }
}