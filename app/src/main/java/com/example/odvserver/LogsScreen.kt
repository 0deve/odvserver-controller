package com.example.odvserver

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val id: String,
    val message: String,
    val service: String,
    val pid: String,
    val timestamp: String,
    val priority: Int,
    val rawTime: Long
)

enum class SortOption {
    TimeNewest,
    Priority
}

// log levels
enum class LogLevel(val label: String, val value: Int) {
    Emergency("Only emergency", 0),
    Alert("Alert and above", 1),
    Critical("Critical and above", 2),
    Error("Error and above", 3),
    Warning("Warning and above", 4),
    Notice("Notice and above", 5),
    Info("Info and above", 6),
    Debug("Debug and above", 7)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    sshManager: SshManager,
    connectionDetails: ConnectionDetails,
    modifier: Modifier = Modifier
) {
    // data state
    var logsList by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // filters state
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(SortOption.TimeNewest) }
    var filterLogLevel by remember { mutableStateOf(LogLevel.Warning) }

    // date state
    var selectedDateMillis by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    // interaction state
    var selectedLog by remember { mutableStateOf<LogEntry?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showLevelMenu by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val dateState = rememberDatePickerState()

    fun formatDateForCmd(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date(millis))
    }

    fun fetchLogs() {
        if (connectionDetails.ip.isBlank()) {
            errorMsg = "Not connected (missing IP)"
            return
        }

        isLoading = true
        errorMsg = null
        logsList = emptyList()

        scope.launch {
            try {
                val safePass = connectionDetails.password.replace("'", "'\\''")

                // command
                val cmdBuilder = StringBuilder("echo '$safePass' | sudo -S -p '' journalctl -o json --no-pager")

                // priority filter
                cmdBuilder.append(" -p ${filterLogLevel.value}")

                // filter data
                if (selectedDateMillis != null) {
                    val dateStr = formatDateForCmd(selectedDateMillis!!)
                    cmdBuilder.append(" --since '$dateStr 00:00:00' --until '$dateStr 23:59:59'")
                } else {
                    cmdBuilder.append(" -n 100")
                }

                // text filter
                if (searchQuery.isNotBlank()) {
                    cmdBuilder.append(" --grep '(?i)$searchQuery'")
                }

                // imp sort
                if (selectedDateMillis == null) {
                    cmdBuilder.append(" --reverse")
                }

                // for spam
                cmdBuilder.append(" | grep -v 'sudo:session' | grep -v 'journalctl'")

                val fullCmd = cmdBuilder.toString()

                val result = sshManager.sendCommand(
                    connectionDetails.ip,
                    connectionDetails.user,
                    connectionDetails.password,
                    fullCmd
                )

                if (result.startsWith("Err")) {
                    errorMsg = result
                } else if (result.contains("incorrect password", ignoreCase = true)) {
                    errorMsg = "Sudo Error: Incorrect password."
                } else {
                    val parsedLogs = mutableListOf<LogEntry>()
                    val lines = result.lines()
                    var successCount = 0

                    for (line in lines) {
                        if (line.isBlank()) continue

                        try {
                            val json = JSONObject(line)
                            val msg = json.optString("MESSAGE", "-")
                            val srv = json.optString("SYSLOG_IDENTIFIER", "system")
                            val pid = json.optString("_PID", "N/A")
                            val prio = json.optInt("PRIORITY", 6)
                            val timeMicro = json.optLong("__REALTIME_TIMESTAMP", System.currentTimeMillis() * 1000)

                            val dateObj = Date(timeMicro / 1000)
                            // formatul cerut dd/MM/yyyy + ora
                            val sdfDisplay = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)
                            val timeStr = sdfDisplay.format(dateObj)

                            parsedLogs.add(LogEntry(
                                id = timeMicro.toString(),
                                message = msg,
                                service = srv,
                                pid = pid,
                                timestamp = timeStr,
                                priority = prio,
                                rawTime = timeMicro
                            ))
                            successCount++
                        } catch (e: Exception) {
                            // ignore
                        }
                    }

                    if (successCount > 0) {
                        logsList = when(sortOption) {
                            SortOption.TimeNewest -> parsedLogs.sortedByDescending { it.rawTime }
                            SortOption.Priority -> parsedLogs.sortedBy { it.priority }
                        }
                    } else {
                        if (result.isBlank()) {
                            errorMsg = "No logs found."
                        } else {
                            val rawPreview = if (result.length > 300) result.take(300) + "..." else result
                            errorMsg = "Server output:\n$rawPreview"
                        }
                    }
                }
            } catch (e: Exception) {
                errorMsg = "Critical Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // effects
    LaunchedEffect(sortOption) {
        if (logsList.isNotEmpty()) {
            logsList = when(sortOption) {
                SortOption.TimeNewest -> logsList.sortedByDescending { it.rawTime }
                SortOption.Priority -> logsList.sortedBy { it.priority }
            }
        }
    }

    LaunchedEffect(filterLogLevel) {
        fetchLogs()
    }

    LaunchedEffect(Unit) {
        fetchLogs()
    }

    // dialogs
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    selectedDateMillis = dateState.selectedDateMillis
                    showDatePicker = false
                    fetchLogs()
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    selectedDateMillis = null
                    showDatePicker = false
                    fetchLogs()
                }) { Text("Clear Date") }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    // detail view dialog
    if (selectedLog != null) {
        AlertDialog(
            onDismissRequest = { selectedLog = null },
            title = { Text("Log Details") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    DetailRow("Timestamp", selectedLog!!.timestamp)
                    DetailRow("Service", selectedLog!!.service)
                    DetailRow("PID", selectedLog!!.pid)
                    DetailRow("Priority", "${selectedLog!!.priority} (${getPriorityLabel(selectedLog!!.priority)})")

                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Message:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = selectedLog!!.message,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedLog = null }) {
                    Text("Close")
                }
            }
        )
    }

    // UI structure
    Box(modifier = modifier.fillMaxSize()) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
            ) {
                // search
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search logs") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "clear")
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { fetchLogs() },
                        modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small)
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "search")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // filters
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // date
                    AssistChip(
                        onClick = { showDatePicker = true },
                        label = {
                            Text(if (selectedDateMillis != null) formatDateForCmd(selectedDateMillis!!) else "Recent")
                        },
                        leadingIcon = { Icon(Icons.Filled.CalendarToday, null) },
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                    )

                    // level
                    Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        AssistChip(
                            onClick = { showLevelMenu = true },
                            label = {
                                Text(filterLogLevel.label.split(" ")[0] + "+")
                            },
                            leadingIcon = { Icon(Icons.Filled.FilterList, null) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        DropdownMenu(
                            expanded = showLevelMenu,
                            onDismissRequest = { showLevelMenu = false }
                        ) {
                            LogLevel.values().forEach { level ->
                                DropdownMenuItem(
                                    text = { Text(level.label) },
                                    onClick = {
                                        filterLogLevel = level
                                        showLevelMenu = false
                                    },
                                    leadingIcon = {
                                        if (level == filterLogLevel) {
                                            Icon(Icons.Filled.Sort, null)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // sort
                    Box(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        AssistChip(
                            onClick = { showSortMenu = true },
                            label = { Text(if (sortOption == SortOption.TimeNewest) "Time" else "Prio") },
                            leadingIcon = { Icon(Icons.Filled.Sort, null) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Newest (Time)") },
                                onClick = {
                                    sortOption = SortOption.TimeNewest
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Most Severe (Priority)") },
                                onClick = {
                                    sortOption = SortOption.Priority
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Divider()

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (errorMsg != null) {
                Text(
                    text = errorMsg!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(logsList) { log ->
                    LogItem(
                        log = log,
                        onClick = { selectedLog = it }
                    )
                }
            }
        }
    }
}

@Composable
fun LogItem(log: LogEntry, onClick: (LogEntry) -> Unit) {
    // 0-3 error, 4 warning, rest info
    val (icon, color) = when {
        log.priority <= 3 -> Pair(Icons.Filled.Warning, Color(0xFFFF5252))
        log.priority == 4 -> Pair(Icons.Filled.Warning, Color(0xFFFFAB40))
        else -> Pair(Icons.Filled.Info, Color(0xFF448AFF))
    }

    Card(
        onClick = { onClick(log) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp).padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = log.service,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = log.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = log.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(text = value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
    }
}

fun getPriorityLabel(prio: Int): String {
    return LogLevel.values().find { it.value == prio }?.label ?: "Unknown"
}