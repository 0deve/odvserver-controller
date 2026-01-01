package com.example.odvserver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupSecurityProvider()

        setContent {
            MaterialTheme {
                ServerControlScreen()
            }
        }
    }

    private fun setupSecurityProvider() {
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }
}

@Composable
fun ServerControlScreen() {
    // date
    var ip by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // ui
    var logs by remember { mutableStateOf("Introdu datele.") }
    var isLoading by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val sshManager = remember { SshManager() }

    val isFormValid = ip.isNotBlank() && user.isNotBlank() && password.isNotBlank()

    fun runCmd(cmd: String, isTestConnection: Boolean = false) {
        if (isLoading) return
        isLoading = true

        if (isTestConnection) logs = "Se testeaza conexiunea"
        else logs = "Se executa: $cmd ..."

        scope.launch {
            val result = sshManager.sendCommand(ip, user, password, cmd)

            if (isTestConnection) {
                if (!result.startsWith("err")) {
                    isConnected = true
                    logs = "Succes\nOutput: $result"
                } else {
                    logs = "Err conectare:\n$result"
                }
            } else {
                logs = "Rezultat:\n$result"
            }
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Control Server", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(16.dp))

        // ip
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("IP") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // user
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("User") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        //pass
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
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
            onClick = { runCmd("echo 'Conexiune OK'", isTestConnection = true) },
            enabled = isFormValid && !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            if (isLoading && !isConnected) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            } else {
                Text(if (isConnected) "RE-CONECTARE" else "CONNECT")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // comenzi
        if (isConnected) {
            Text(text = "Comenzi implicite:", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = { runCmd("docker ps") }, enabled = !isLoading) {
                    Text("Status Docker")
                }

                Button(onClick = { runCmd("uptime") }, enabled = !isLoading) {
                    Text("Uptime")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = { runCmd("docker restart jellyfin") }, enabled = !isLoading) {
                    Text("Restart Jellyfin")
                }

                Button(
                    onClick = { runCmd("echo $password | sudo -S reboot") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    enabled = !isLoading
                ) {
                    Text("REBOOT")
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