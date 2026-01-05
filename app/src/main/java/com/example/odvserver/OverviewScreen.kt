package com.example.odvserver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

enum class OverviewView {
    Dashboard,
    UsageDetails,
    HardwareDetails
}

// data classes
data class ServerStats(
    val hostname: String = "-",
    val osName: String = "-",
    val serverTime: String = "-",
    val model: String = "-",
    val machineId: String = "-",
    val uptime: String = "-",
    val cpuLoad: String = "-",
    val ramUsed: String = "0",
    val ramTotal: String = "1",
    val diskUsed: String = "0",
    val diskTotal: String = "1",
    val diskPercent: String = "0%",
    val updatesCount: Int = 0,
    val updatesMessage: String = "Checking..."
)

// data classes detailed
data class ProcessInfo(val name: String, val usage: String)
data class DiskMount(val mount: String, val size: String, val used: String, val avail: String, val pcent: String)
data class NetInterface(val name: String, val rx: String, val tx: String)

data class DetailedUsageStats(
    val cpuLoad1: String = "-",
    val cpuLoad5: String = "-",
    val cpuLoad15: String = "-",
    val cpuProcs: List<ProcessInfo> = emptyList(),
    val ramUsed: String = "-",
    val ramFree: String = "-",
    val ramTotal: String = "-",
    val ramProcs: List<ProcessInfo> = emptyList(),
    val disks: List<DiskMount> = emptyList(),
    val networks: List<NetInterface> = emptyList()
)

// data classes hardware
data class HardwareStats(
    val cpuModel: String = "-",
    val cpuCores: String = "-",
    val architecture: String = "-",
    val blockDevices: String = "-" // raw lsblk output
)

@Composable
fun OverviewScreen(
    sshManager: SshManager,
    connectionDetails: ConnectionDetails,
    modifier: Modifier = Modifier
) {
    // for subscreen
    var currentView by remember { mutableStateOf(OverviewView.Dashboard) }

    Box(modifier = modifier.fillMaxSize()) {
        when (currentView) {
            OverviewView.Dashboard -> DashboardContent(
                sshManager, connectionDetails,
                onNavToUsage = { currentView = OverviewView.UsageDetails },
                onNavToHardware = { currentView = OverviewView.HardwareDetails }
            )
            OverviewView.UsageDetails -> UsageDetailsContent(
                sshManager, connectionDetails,
                onBack = { currentView = OverviewView.Dashboard }
            )
            OverviewView.HardwareDetails -> HardwareDetailsContent(
                sshManager, connectionDetails,
                onBack = { currentView = OverviewView.Dashboard }
            )
        }
    }
}


//dashboard view
@Composable
fun DashboardContent(
    sshManager: SshManager,
    connectionDetails: ConnectionDetails,
    onNavToUsage: () -> Unit,
    onNavToHardware: () -> Unit
) {
    var stats by remember { mutableStateOf(ServerStats()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun fetchData() {
        if (connectionDetails.ip.isBlank()) {
            errorMsg = "not connected"
            return
        }
        isLoading = true
        errorMsg = null
        scope.launch {
            val sep = "|||"
            val cmd = "hostname && echo '$sep' && " +
                    "uptime -p && echo '$sep' && " +
                    "grep -m1 'PRETTY_NAME' /etc/os-release && echo '$sep' && " +
                    "cat /proc/loadavg && echo '$sep' && " +
                    "free -m | grep Mem && echo '$sep' && " +
                    "df -h / | tail -1 && echo '$sep' && " +
                    "date +'%Y-%m-%d %H:%M:%S' && echo '$sep' && " +
                    "(cat /sys/class/dmi/id/product_name 2>/dev/null || cat /sys/firmware/devicetree/base/model 2>/dev/null || uname -m) && echo '$sep' && " +
                    "cat /etc/machine-id && echo '$sep' && " +
                    "apt-get -s upgrade | grep 'upgraded,'"

            val result = sshManager.sendCommand(connectionDetails.ip, connectionDetails.user, connectionDetails.password, cmd)

            if (result.startsWith("Err")) {
                errorMsg = result
            } else {
                try {
                    val parts = result.split(sep).map { it.trim() }
                    if (parts.size >= 10) {
                        val host = parts[0]
                        val up = parts[1]
                        val osRaw = parts[2].replace("PRETTY_NAME=", "").replace("\"", "")
                        val loadRaw = parts[3].split(" ").firstOrNull() ?: "-"

                        val ramParts = parts[4].split("\\s+".toRegex())
                        val rTotal = ramParts.getOrNull(1) ?: "1"
                        val rUsed = ramParts.getOrNull(2) ?: "0"

                        val diskParts = parts[5].split("\\s+".toRegex())
                        val dTotal = diskParts.getOrNull(1) ?: "1"
                        val dUsed = diskParts.getOrNull(2) ?: "0"
                        val dPerc = diskParts.getOrNull(4) ?: "0%"

                        val sTime = parts[6]
                        val sModel = parts[7]
                        val mId = parts[8]

                        val updatesRaw = parts[9]
                        val updatesNum = updatesRaw.split(" ").firstOrNull()?.toIntOrNull() ?: 0

                        stats = ServerStats(host, osRaw, sTime, sModel, mId, up, loadRaw, rUsed, rTotal, dUsed, dTotal, dPerc, updatesNum, updatesRaw)
                    }
                } catch (e: Exception) {
                    errorMsg = "parse err: ${e.message}"
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { fetchData() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        // header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("System Dashboard", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = { fetchData() }, enabled = !isLoading) {
                if(isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                else Icon(Icons.Filled.Refresh, "refresh")
            }
        }

        if (errorMsg != null) Text(errorMsg!!, color = MaterialTheme.colorScheme.error)

        Spacer(modifier = Modifier.height(16.dp))

        // updates
        val updatesColor = if (stats.updatesCount > 0) MaterialTheme.colorScheme.primaryContainer else Color(0xFFE8F5E9)
        Card(colors = CardDefaults.cardColors(containerColor = updatesColor), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (stats.updatesCount > 0) Icons.Filled.Update else Icons.Filled.CheckCircle, null, tint = if (stats.updatesCount > 0) MaterialTheme.colorScheme.primary else Color(0xFF2E7D32))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(if (stats.updatesCount > 0) "${stats.updatesCount} Updates Available" else "System Up to Date", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // info card
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Computer, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("System Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    // button hardware
                    TextButton(onClick = onNavToHardware) {
                        Text("Hardware")
                    }
                }
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                KeyValueRow("Hostname", stats.hostname)
                KeyValueRow("Model", stats.model)
                KeyValueRow("OS", stats.osName)
                KeyValueRow("System Time", stats.serverTime)
                KeyValueRow("Uptime", stats.uptime)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // usage header
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Usage & Health", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onNavToUsage) {
                Text("View More")
            }
        }

        // cpu simple
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Analytics, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("CPU Load (1m)", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(stats.cpuLoad, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // ram simple
        UsageCard("Memory (RAM)", Icons.Filled.Memory, stats.ramUsed, stats.ramTotal, "MB")
        Spacer(modifier = Modifier.height(8.dp))
        // disk simple
        UsageCard("Disk Storage (Root)", Icons.Filled.Storage, stats.diskUsed, stats.diskTotal, "", "${stats.diskPercent} Full")
    }
}

//usage details
//inspired by https://github.com/cockpit-project/cockpit
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageDetailsContent(
    sshManager: SshManager,
    connectionDetails: ConnectionDetails,
    onBack: () -> Unit
) {
    var dStats by remember { mutableStateOf(DetailedUsageStats()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun fetchDetails() {
        scope.launch {
            isLoading = true
            val sep = "|||"
            // loadavg
            // top cpu procs (ps)
            // memory free -h
            // top mem procs
            // df -h
            // net dev
            val cmd = "cat /proc/loadavg && echo '$sep' && " +
                    "ps -eo comm,pcpu --sort=-pcpu | head -6 && echo '$sep' && " +
                    "free -m && echo '$sep' && " +
                    "ps -eo comm,pmem --sort=-pmem | head -6 && echo '$sep' && " +
                    "df -h --output=source,size,used,avail,pcent,target -x tmpfs -x devtmpfs && echo '$sep' && " +
                    "cat /proc/net/dev"

            val res = sshManager.sendCommand(connectionDetails.ip, connectionDetails.user, connectionDetails.password, cmd)

            if (!res.startsWith("Err")) {
                try {
                    val sections = res.split(sep).map { it.trim() }

                    // cpu
                    val loadParts = sections[0].split(" ")
                    val l1 = loadParts.getOrNull(0) ?: "-"
                    val l5 = loadParts.getOrNull(1) ?: "-"
                    val l15 = loadParts.getOrNull(2) ?: "-"

                    val cpuProcs = sections[1].lines().drop(1).mapNotNull {
                        val p = it.trim().split("\\s+".toRegex())
                        if(p.size >= 2) ProcessInfo(p[0], p[1] + "%") else null
                    }

                    // ram
                    val ramLines = sections[2].lines()
                    var rUsed="-"; var rFree="-"; var rTot="-"
                    ramLines.forEach { line ->
                        if (line.startsWith("Mem:")) {
                            val p = line.split("\\s+".toRegex())
                            rTot = p.getOrNull(1) ?: "-"
                            rUsed = p.getOrNull(2) ?: "-"
                            rFree = p.getOrNull(3) ?: "-"
                        }
                    }
                    val ramProcs = sections[3].lines().drop(1).mapNotNull {
                        val p = it.trim().split("\\s+".toRegex())
                        if(p.size >= 2) ProcessInfo(p[0], p[1] + "%") else null
                    }

                    // disk
                    val diskLines = sections[4].lines().drop(1) // skip header
                    val disks = diskLines.mapNotNull {
                        val p = it.trim().split("\\s+".toRegex())
                        // source size used avail pcent target
                        if (p.size >= 6) DiskMount(p[5], p[1], p[2], p[3], p[4]) else null
                    }

                    // net
                    val netLines = sections[5].lines().drop(2) // skip headers
                    val nets = netLines.mapNotNull {
                        val p = it.trim().split("\\s+".toRegex())
                        val name = p.getOrNull(0)?.replace(":","") ?: ""
                        // bytes in is index 1, bytes out is index 9 usually
                        val rx = p.getOrNull(1) ?: "0"
                        val tx = p.getOrNull(9) ?: "0"
                        if (name.isNotBlank()) NetInterface(name, formatBytes(rx), formatBytes(tx)) else null
                    }

                    dStats = DetailedUsageStats(l1, l5, l15, cpuProcs, rUsed, rFree, rTot, ramProcs, disks, nets)

                } catch (e: Exception) {
                    // ignore
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { fetchDetails() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // top bar
        TopAppBar(
            title = { Text("Detailed Usage") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "back") }
            },
            actions = {
                IconButton(onClick = { fetchDetails() }) { Icon(Icons.Filled.Refresh, "refresh") }
            }
        )

        if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
            // CPU
            DetailedCard("CPU", Icons.Filled.Analytics) {
                Text("Load Average", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("1 min: ${dStats.cpuLoad1}, 5 min: ${dStats.cpuLoad5}, 15 min: ${dStats.cpuLoad15}")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Top Processes (by CPU)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                dStats.cpuProcs.forEach {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(it.name, style = MaterialTheme.typography.bodySmall)
                        Text(it.usage, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // memory
            DetailedCard("Memory", Icons.Filled.Memory) {
                Text("Usage", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("${dStats.ramUsed} MB Used / ${dStats.ramTotal} MB Total")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Top Processes (by RAM)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                dStats.ramProcs.forEach {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(it.name, style = MaterialTheme.typography.bodySmall)
                        Text(it.usage, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // disks
            DetailedCard("Disks", Icons.Filled.Storage) {
                Text("Mount Points", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                dStats.disks.forEach {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(it.mount, fontWeight = FontWeight.Bold)
                            Text("${it.avail} Free")
                        }
                        // progress bar
                        val cleanPerc = it.pcent.replace("%","").toFloatOrNull() ?: 0f
                        LinearProgressIndicator(
                            progress = { cleanPerc / 100f },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = if (cleanPerc > 90) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // network
            DetailedCard("Network (Total Traffic)", Icons.Filled.Computer) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Interface", fontWeight = FontWeight.Bold)
                    Text("In / Out", fontWeight = FontWeight.Bold)
                }
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                dStats.networks.forEach {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(it.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Text("${it.rx} / ${it.tx}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

//hardware details
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareDetailsContent(
    sshManager: SshManager,
    connectionDetails: ConnectionDetails,
    onBack: () -> Unit
) {
    var hStats by remember { mutableStateOf(HardwareStats()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun fetchHw() {
        scope.launch {
            isLoading = true
            // simple lscpu and lsblk
            val sep = "|||"
            val cmd = "lscpu | grep 'Model name' && echo '$sep' && " +
                    "lscpu | grep 'CPU(s):' | head -1 && echo '$sep' && " +
                    "lscpu | grep 'Architecture' && echo '$sep' && " +
                    "lsblk -o NAME,SIZE,TYPE,MODEL"

            val res = sshManager.sendCommand(connectionDetails.ip, connectionDetails.user, connectionDetails.password, cmd)
            if (!res.startsWith("Err")) {
                val parts = res.split(sep)
                val cpuM = parts.getOrNull(0)?.replace("Model name:", "")?.trim() ?: "-"
                val cpuC = parts.getOrNull(1)?.replace("CPU(s):", "")?.trim() ?: "-"
                val arch = parts.getOrNull(2)?.replace("Architecture:", "")?.trim() ?: "-"
                val blk = parts.getOrNull(3) ?: "-"

                hStats = HardwareStats(cpuM, cpuC, arch, blk)
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { fetchHw() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Hardware Info") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "back") }
            }
        )

        if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("CPU", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    KeyValueRow("Model", hStats.cpuModel)
                    KeyValueRow("Cores (Logical)", hStats.cpuCores)
                    KeyValueRow("Architecture", hStats.architecture)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Storage Devices (lsblk)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = hStats.blockDevices,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

//helpers

@Composable
fun DetailedCard(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            content()
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = key, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun UsageCard(
    title: String,
    icon: ImageVector,
    used: String,
    total: String,
    unit: String,
    subtitle: String? = null
) {
    val usedFloat = used.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
    val totalFloat = total.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 1f
    val progress = if (totalFloat > 0) usedFloat / totalFloat else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "$used / $total $unit",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (progress > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// format bytes
fun formatBytes(bytesStr: String): String {
    val b = bytesStr.toLongOrNull() ?: 0L
    if (b < 1024) return "$b B"
    val k = b / 1024
    if (k < 1024) return "$k KB"
    val m = k / 1024
    if (m < 1024) return "$m MB"
    val g = m / 1024
    return "$g GB"
}