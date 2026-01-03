package com.example.odvserver

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    val timestamp: String,
    val priority: Int, // 0-3=err, 4=warn, 6=info
    val rawTime: Long
)

// enum for sorting
enum class SortOption {
    TimeNewest, // default
    Priority    // most severe first
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

    // filter state
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(SortOption.TimeNewest) }

    // date state (null = today/recent, long = chosen timestamp)
    var selectedDateMillis by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    // sort dropdown menu
    var showSortMenu by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val dateState = rememberDatePickerState()

    // date formatting
    fun formatDateForCmd(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(Date(millis))
    }

    // fetch function
    fun fetchLogs() {
        if (connectionDetails.ip.isBlank()) {
            errorMsg = "not connected (missing ip)"
            return
        }

        isLoading = true
        errorMsg = null

        scope.launch {
            // build command dynamically
            // json output, no pager
            val cmdBuilder = StringBuilder("journalctl -o json --no-pager")

            // date filter
            if (selectedDateMillis != null) {
                val dateStr = formatDateForCmd(selectedDateMillis!!)
                // search interal for hour
                cmdBuilder.append(" --since '$dateStr 00:00:00' --until '$dateStr 23:59:59'")
            } else {
                // if date not selected take last 100
                cmdBuilder.append(" -n 100")
            }

            // text filter
            // for case insensitive
            if (searchQuery.isNotBlank()) {
                cmdBuilder.append(" --grep '$searchQuery' -i")
            }

            // reverse at the end for newest
            // --until
            if (selectedDateMillis == null) {
                cmdBuilder.append(" --reverse")
            }

            val cmd = cmdBuilder.toString()

            val result = sshManager.sendCommand(
                connectionDetails.ip,
                connectionDetails.user,
                connectionDetails.password,
                cmd
            )

            if (result.startsWith("Err")) {
                errorMsg = result
            } else {
                val parsedLogs = mutableListOf<LogEntry>()
                val lines = result.lines()

                for (line in lines) {
                    if (line.isNotBlank()) {
                        try {
                            val json = JSONObject(line)

                            val msg = json.optString("MESSAGE", "-")
                            val srv = json.optString("SYSLOG_IDENTIFIER", "sys")
                            val prio = json.optInt("PRIORITY", 6)
                            val timeMicro = json.optLong("__REALTIME_TIMESTAMP", System.currentTimeMillis() * 1000)

                            val dateObj = Date(timeMicro / 1000)
                            val sdfDisplay = SimpleDateFormat("HH:mm:ss", Locale.US)
                            val timeStr = sdfDisplay.format(dateObj)

                            parsedLogs.add(LogEntry(
                                id = timeMicro.toString(),
                                message = msg,
                                service = srv,
                                timestamp = timeStr,
                                priority = prio,
                                rawTime = timeMicro
                            ))
                        } catch (e: Exception) {
                            // ignore bad json
                        }
                    }
                }

                // apply selected sort
                logsList = when(sortOption) {
                    SortOption.TimeNewest -> parsedLogs.sortedByDescending { it.rawTime }
                    SortOption.Priority -> parsedLogs.sortedBy { it.priority } // 0 is most severe
                }
            }
            isLoading = false
        }
    }

    // reload when local sort changes
    LaunchedEffect(sortOption) {
        if (logsList.isNotEmpty()) {
            logsList = when(sortOption) {
                SortOption.TimeNewest -> logsList.sortedByDescending { it.rawTime }
                SortOption.Priority -> logsList.sortedBy { it.priority }
            }
        }
    }

    // load on startup
    LaunchedEffect(Unit) {
        fetchLogs()
    }

    // calendar dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    selectedDateMillis = dateState.selectedDateMillis
                    showDatePicker = false
                    fetchLogs() // auto search when date chosen
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

    Box(modifier = modifier.fillMaxSize()) {
        Column {
            // control area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
            ) {
                // search and refresh
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search (ex: error, docker)") },
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

                // date and sort
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // date button
                    AssistChip(
                        onClick = { showDatePicker = true },
                        label = {
                            Text(if (selectedDateMillis != null)
                                formatDateForCmd(selectedDateMillis!!)
                            else "Recent")
                        },
                        leadingIcon = { Icon(Icons.Filled.CalendarToday, null) }
                    )

                    // sort button
                    Box {
                        AssistChip(
                            onClick = { showSortMenu = true },
                            label = {
                                Text(if (sortOption == SortOption.TimeNewest) "Time" else "Severity")
                            },
                            leadingIcon = { Icon(Icons.Filled.Sort, null) }
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

            // log list
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
                    LogItem(log)
                }
            }
        }
    }
}

@Composable
fun LogItem(log: LogEntry) {
    // 0-3 error, 4 warning, rest info
    val (icon, color) = when {
        log.priority <= 3 -> Pair(Icons.Filled.Warning, Color(0xFFFF5252))
        log.priority == 4 -> Pair(Icons.Filled.Warning, Color(0xFFFFAB40))
        else -> Pair(Icons.Filled.Info, Color(0xFF448AFF))
    }

    Card(
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
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}