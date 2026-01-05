package com.example.odvserver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ServerControlScreen(
    sshManager: SshManager,
    connectionDetails: ConnectionDetails,
    modifier: Modifier = Modifier
) {
    // using local states for ui, but saving to connectionDetails
    // when user types
    var ip by remember { mutableStateOf(connectionDetails.ip) }
    var user by remember { mutableStateOf(connectionDetails.user) }
    var password by remember { mutableStateOf(connectionDetails.password) }

    // ui
    var logs by remember { mutableStateOf("Enter details.") }
    var isLoading by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // check connection on load
    LaunchedEffect(Unit) {
        if (sshManager.isConnected()) {
            isConnected = true
            logs = "Session active."
            // update local text fields to match active connection
            // if needed but connectionDetails acts as global state anyway
        }
    }

    // update global object when text changes
    fun updateCredentials() {
        connectionDetails.ip = ip
        connectionDetails.user = user
        connectionDetails.password = password
    }

    val isFormValid = ip.isNotBlank() && user.isNotBlank() && password.isNotBlank()

    fun runCmd(cmd: String, isTestConnection: Boolean = false) {
        if (isLoading) return
        isLoading = true
        updateCredentials()

        if (isTestConnection) logs = "Testing connection"
        else logs = "Executing: $cmd "

        scope.launch {
            val result = sshManager.sendCommand(ip, user, password, cmd)

            if (isTestConnection) {
                if (!result.startsWith("err")) {
                    isConnected = true
                    logs = "Success\nOutput: $result"
                } else {
                    logs = "Connection Err:\n$result"
                }
            } else {
                logs = "Result:\n$result"
            }
            isLoading = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // ip
        OutlinedTextField(
            value = ip,
            onValueChange = {
                ip = it
                updateCredentials()
            },
            label = { Text("IP") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // user
        OutlinedTextField(
            value = user,
            onValueChange = {
                user = it
                updateCredentials()
            },
            label = { Text("User") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        //pass
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                updateCredentials()
            },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                val image = if (passwordVisible)
                    Icons.Filled.Visibility
                else
                    Icons.Filled.VisibilityOff

                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(imageVector = image, contentDescription = "Toggle pass")
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // connect
        Button(
            onClick = { runCmd("echo 'Connection OK'", isTestConnection = true) },
            enabled = isFormValid && !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            if (isLoading && !isConnected) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            } else {
                Text(if (isConnected) "RECONNECT" else "CONNECT")
            }
        }

        // clear hosts button (security reset)
        TextButton(
            onClick = {
                val deleted = sshManager.clearKnownHosts()
                if (deleted) logs = "Known Hosts cleared. Next connection will be treated as new."
                else logs = "No Known Hosts file found to clear."
                isConnected = false
                sshManager.disconnect()
            },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear Known Hosts (Reset Security)")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // commands
        if (isConnected) {
            Text(text = "Default commands:", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = { runCmd("docker ps") }, enabled = !isLoading) {
                    Text("Docker Status")
                }

                Button(onClick = { runCmd("uptime") }, enabled = !isLoading) {
                    Text("Uptime")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = { runCmd("df -h") }, enabled = !isLoading) {
                    Text("Disk Usage")
                }

                Button(
                    onClick = { runCmd("echo $password | sudo -S reboot") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    enabled = !isLoading
                ) {
                    Text("REBOOT", color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Output / Log:",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Start)
        )

        // terminal
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .background(Color.Black)
                .padding(8.dp)
        ) {
            Text(
                text = logs,
                color = if (logs.startsWith("Errr")) Color.Red else Color.Green,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
    }
}