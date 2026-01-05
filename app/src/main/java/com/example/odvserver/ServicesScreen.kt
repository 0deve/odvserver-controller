package com.example.odvserver

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// data
data class ServiceItem(
    val name: String,
    val description: String,
    val loadState: String, // loaded/not-found
    val activeState: String, // active/inactive
    val subState: String // running/exited/dead
)

data class ServiceDetails(
    val id: String = "-",
    val description: String = "-",
    val activeState: String = "-",
    val subState: String = "-",
    val unitFileState: String = "-", // enabled/disabled
    val activeEnterTimestamp: String = "-",
    val fragmentPath: String = "-",
    val requires: String = "-",
    val wantedBy: String = "-",
    val before: String = "-",
    val after: String = "-"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(
    sshManager: SshManager,
    connectionDetails: ConnectionDetails,
    modifier: Modifier = Modifier
) {
    // states
    var servicesList by remember { mutableStateOf<List<ServiceItem>>(emptyList()) }
    var filteredList by remember { mutableStateOf<List<ServiceItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // detail state
    var selectedServiceId by remember { mutableStateOf<String?>(null) }
    var serviceDetails by remember { mutableStateOf<ServiceDetails?>(null) }
    var isLoadingDetails by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // fetch main list
    fun fetchServices() {
        if (connectionDetails.ip.isBlank()) return
        isLoading = true
        scope.launch {
            try {
                // systemctl list-units provides runtime info
                // --no-legend removes headers
                // tr -s ' ' squeezes spaces for easier parsing
                val cmd = "systemctl list-units --type=service --all --no-pager --no-legend | tr -s ' '"
                val result = sshManager.sendCommand(
                    connectionDetails.ip,
                    connectionDetails.user,
                    connectionDetails.password,
                    cmd
                )

                if (!result.startsWith("Err")) {
                    val parsed = result.lines().mapNotNull { line ->
                        if (line.isBlank()) null
                        else {
                            val parts = line.trim().split(" ")
                            if (parts.size >= 4) {
                                val name = parts[0]
                                val load = parts[1]
                                val active = parts[2]
                                val sub = parts[3]
                                val desc = parts.drop(4).joinToString(" ")
                                ServiceItem(name, desc, load, active, sub)
                            } else null
                        }
                    }
                    servicesList = parsed
                    filteredList = parsed
                }
            } catch (e: Exception) {
                // ignore
            } finally {
                isLoading = false
            }
        }
    }

    // fetch details for one service
    fun fetchDetails(serviceName: String) {
        isLoadingDetails = true
        selectedServiceId = serviceName
        scope.launch {
            try {
                // systemctl show gives key=value pairs
                val props = "Id,Description,ActiveState,SubState,UnitFileState,ActiveEnterTimestamp,FragmentPath,Requires,WantedBy,Before,After"
                val cmd = "systemctl show $serviceName --property=$props --no-pager"

                val result = sshManager.sendCommand(
                    connectionDetails.ip,
                    connectionDetails.user,
                    connectionDetails.password,
                    cmd
                )

                if (!result.startsWith("Err")) {
                    val map = mutableMapOf<String, String>()
                    result.lines().forEach { line ->
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2) {
                            map[parts[0]] = parts[1]
                        }
                    }

                    serviceDetails = ServiceDetails(
                        id = map["Id"] ?: serviceName,
                        description = map["Description"] ?: "",
                        activeState = map["ActiveState"] ?: "-",
                        subState = map["SubState"] ?: "-",
                        unitFileState = map["UnitFileState"] ?: "static",
                        activeEnterTimestamp = map["ActiveEnterTimestamp"] ?: "-",
                        fragmentPath = map["FragmentPath"] ?: "-",
                        requires = map["Requires"] ?: "-",
                        wantedBy = map["WantedBy"] ?: "-",
                        before = map["Before"] ?: "-",
                        after = map["After"] ?: "-"
                    )
                }
            } catch (e: Exception) {
                // ignore
            } finally {
                isLoadingDetails = false
            }
        }
    }

    // filter logic
    LaunchedEffect(searchQuery, servicesList) {
        if (searchQuery.isBlank()) {
            filteredList = servicesList
        } else {
            filteredList = servicesList.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.description.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    LaunchedEffect(Unit) {
        fetchServices()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column {
            // header & search
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search services") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, "clear")
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { fetchServices() },
                        modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small)
                    ) {
                        Icon(Icons.Filled.Refresh, "refresh")
                    }
                }
            }

            if (isLoading && servicesList.isEmpty()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // list
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filteredList) { item ->
                    ServiceRow(
                        item = item,
                        onClick = { fetchDetails(item.name) }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }

        // detail dialog/sheet
        if (selectedServiceId != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedServiceId = null; serviceDetails = null },
                modifier = Modifier.fillMaxHeight(0.85f)
            ) {
                if (isLoadingDetails || serviceDetails == null) {
                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    ServiceDetailContent(serviceDetails!!)
                }
            }
        }
    }
}

@Composable
fun ServiceRow(item: ServiceItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // left info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // status chips
        Column(horizontalAlignment = Alignment.End) {
            // running state
            val isActive = item.activeState == "active"
            val activeColor = if (isActive) Color(0xFF4CAF50) else Color.Gray

            Surface(
                color = activeColor.copy(alpha = 0.2f),
                shape = MaterialTheme.shapes.extraSmall,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Text(
                    text = item.subState.uppercase(),
                    color = activeColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun ServiceDetailContent(details: ServiceDetails) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Settings, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(details.id, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(details.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // status pills row
        Row(modifier = Modifier.fillMaxWidth()) {
            StatusPill(
                label = details.activeState.uppercase(),
                isActive = details.activeState == "active"
            )
            Spacer(modifier = Modifier.width(8.dp))
            StatusPill(
                label = details.unitFileState.uppercase(),
                isActive = details.unitFileState == "enabled"
            )
        }

        Divider(modifier = Modifier.padding(vertical = 16.dp))

        // key value list
        DetailItem("Active since", details.activeEnterTimestamp)
        DetailItem("Automatically starts", details.unitFileState)
        DetailItem("Path", details.fragmentPath)

        Spacer(modifier = Modifier.height(16.dp))
        Text("Dependencies", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        DetailItem("Requires", details.requires)
        DetailItem("Wanted by", details.wantedBy)
        DetailItem("Before", details.before)
        DetailItem("After", details.after)

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun StatusPill(label: String, isActive: Boolean) {
    val color = if (isActive) Color(0xFF2196F3) else Color.Gray
    Surface(
        color = color,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun DetailItem(label: String, value: String) {
    // split comma separated lists for better view
    val cleanValue = if (value.contains(" ")) value.replace(" ", "\n") else value

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (value.isBlank() || value == "-") "None" else value.replace(" ", "  "), // add spacing for lists
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}