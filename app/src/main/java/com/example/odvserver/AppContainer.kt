package com.example.odvserver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code // icon for terminal
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// update enum
enum class Screen {
    Control,
    Logs,
    Terminal // new screen
}

// data class to hold common data between screens
data class ConnectionDetails(
    var ip: String = "",
    var user: String = "",
    var password: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContainer(
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit
) {
    // drawer state
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // current screen
    var currentScreen by remember { mutableStateOf(Screen.Control) }

    // connection details
    val connectionDetails = remember { ConnectionDetails() }
    val sshManager = remember { SshManager() }

    var terminalHistory by remember { mutableStateOf(listOf<TerminalItem>()) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                // menu header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Menu",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    // theme toggle button
                    IconButton(onClick = onThemeToggle) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = "toggle theme",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // menu items
                NavigationDrawerItem(
                    label = { Text("Server Control") },
                    icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                    selected = currentScreen == Screen.Control,
                    onClick = {
                        currentScreen = Screen.Control
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                //logs
                NavigationDrawerItem(
                    label = { Text("Logs") },
                    icon = { Icon(Icons.Filled.Description, contentDescription = null) },
                    selected = currentScreen == Screen.Logs,
                    onClick = {
                        currentScreen = Screen.Logs
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                // terminal
                NavigationDrawerItem(
                    label = { Text("Terminal / Shell") },
                    icon = { Icon(Icons.Filled.Code, contentDescription = null) },
                    selected = currentScreen == Screen.Terminal,
                    onClick = {
                        currentScreen = Screen.Terminal
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(when(currentScreen) {
                            Screen.Control -> "Server Control"
                            Screen.Logs -> "Server Logs"
                            Screen.Terminal -> "Terminal" // title
                        })
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { contentPadding ->
            Box(modifier = Modifier.padding(contentPadding)) {
                when (currentScreen) {
                    Screen.Control -> ServerControlScreen(
                        sshManager = sshManager,
                        connectionDetails = connectionDetails
                    )
                    Screen.Logs -> LogsScreen(
                        sshManager = sshManager,
                        connectionDetails = connectionDetails
                    )
                    Screen.Terminal -> TerminalScreen(
                        sshManager = sshManager,
                        connectionDetails = connectionDetails,
                        // pass the history of the terminal
                        history = terminalHistory,
                        onHistoryUpdate = { terminalHistory = it }
                    )
                }
            }
        }
    }
}