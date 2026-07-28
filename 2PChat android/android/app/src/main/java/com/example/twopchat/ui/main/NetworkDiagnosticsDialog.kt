
package com.example.twopchat.ui.main

import android.widget.Toast
import android.content.Intent
import android.net.VpnService
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import org.json.JSONArray
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.example.twopchat.PythonBridge
import com.example.twopchat.Chat
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.connectionTransportLabel
import com.example.twopchat.theme.*
import com.example.twopchat.data.Localizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share


private val defaultTrackerUrls = mapOf(
    "Torrent.eu.org UDP" to "udp://tracker.torrent.eu.org:451/announce",
    "Open Stealth UDP" to "udp://open.stealth.si:80/announce",
    "Exodus UDP" to "udp://exodus.desync.com:6969/announce",
    "OpenTrackr HTTP" to "http://tracker.opentrackr.org:1337/announce",
    "Dler HTTP" to "http://tracker2.dler.org:80/announce",
    "Qu.Ax HTTP" to "http://tracker.qu.ax:6969/announce",
    "Yemekyedim HTTPS" to "https://tracker.yemekyedim.com:443/announce",
    "Nyacat HTTPS" to "https://tr.nyacat.pw:443/announce",
    "Yggdrasil-only HTTP" to "http://[200:1e2f:e608:eb3a:2bf:1e62:87ba:e2f7]/announce",
    "Yggdrasil-only UDP" to "udp://[202:68d0:f0d5:b88d:1d1a:555e:2f6b:3148]:6969/announce"
)

internal fun isNearLogTail(scrollValue: Int, maxScrollValue: Int, thresholdPx: Int = 32): Boolean {
    require(scrollValue >= 0 && maxScrollValue >= 0 && thresholdPx >= 0)
    return maxScrollValue - scrollValue <= thresholdPx
}

private fun readLogFile(context: android.content.Context): String {
    return try {
        val logFile = java.io.File(java.io.File(context.filesDir, "config"), "app.log")
        if (!logFile.exists()) {
            return "No logs found yet. Connection activities will appear here."
        }
        val lines = logFile.readLines()
        val lastLines = lines.takeLast(150)
        lastLines.joinToString("\n")
    } catch (e: Exception) {
        "Error reading log file: ${e.message}"
    }
}

private data class DiagnosticsSnapshot(
    val logs: String,
    val upnp: Map<String, String>,
    val trackers: Map<String, String>,
    val yggdrasil: Map<String, String>,
    val activePeers: List<String>,
)

private fun readDiagnosticsSnapshot(context: android.content.Context) = DiagnosticsSnapshot(
    logs = readLogFile(context),
    upnp = runCatching { PythonBridge.getUpnpDetails() }.getOrDefault(emptyMap()),
    trackers = runCatching { PythonBridge.getTrackerDiagnostics() }.getOrDefault(emptyMap()),
    yggdrasil = runCatching { PythonBridge.getYggdrasilNetworkDiagnostics() }.getOrDefault(emptyMap()),
    activePeers = runCatching { PythonBridge.getActivePeers() }.getOrDefault(emptyList()),
)

private fun getTrackerPing(announceUrl: String): Long {
    val startTime = System.currentTimeMillis()
    try {
        val host = java.net.URI(announceUrl).host ?: return -1L
        // Numeric IPv4/IPv6 literals don't require DNS. Reporting the local
        // parse time as "DNS 0ms" made it look like a network measurement.
        if (host.contains(':') || host.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}"))) {
            java.net.InetAddress.getByName(host)
            return -3L
        }
        java.net.InetAddress.getByName(host)
        return (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
    } catch (e: Exception) {
        return -1L
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color) {
    val isMultiLine = value.contains("\n")
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        if (isMultiLine) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = value,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = valueColor,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = valueColor,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 16.dp)
                )
            }
        }
    }
}

private fun clearLogFile(context: android.content.Context) {
    try {
        val logFile = java.io.File(java.io.File(context.filesDir, "config"), "app.log")
        if (logFile.exists()) {
            logFile.writeText("")
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun shareLogFile(context: android.content.Context) {
    try {
        val logFile = java.io.File(java.io.File(context.filesDir, "config"), "app.log")
        if (logFile.exists()) {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Share Logs"))
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Failed to share logs", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun formatLogs(logsText: String): AnnotatedString {
    val builder = remember(logsText) {
        AnnotatedString.Builder().apply {
            val lines = logsText.split("\n")
            lines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                
                val kotlinInfoIndex = line.indexOf("[KOTLIN_INFO]")
                val pythonErrIndex = line.indexOf("[PYTHON_ERR]")
                val pythonOutIndex = line.indexOf("[PYTHON_OUT]")
                val infoIndex = line.indexOf("[INFO]")
                val errIndex = line.indexOf("[ERROR]")
                val warnIndex = line.indexOf("[WARNING]")
                val debugIndex = line.indexOf("[DEBUG]")
                
                when {
                    pythonErrIndex != -1 || errIndex != -1 -> {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFFFF5252))) // Coral Red
                    }
                    kotlinInfoIndex != -1 -> {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFF40C4FF))) // Electric Blue
                    }
                    pythonOutIndex != -1 || infoIndex != -1 -> {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFF69F0AE))) // Mint Green
                    }
                    warnIndex != -1 -> {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFFFFD740))) // Amber Yellow
                    }
                    debugIndex != -1 -> {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFF90A4AE))) // Slate Gray
                    }
                    else -> {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFFECEFF1))) // Warm White
                    }
                }
                
                append(line)
                pop()
                if (index < lines.size - 1) {
                    append("\n")
                }
            }
        }.toAnnotatedString()
    }
    return builder
}

@Composable
fun NetworkDiagnosticsDialog(
    showLogsDialog: Boolean,
    onDismissRequest: () -> Unit,
    showRadarView: Boolean,
    onShowRadarViewChange: (Boolean) -> Unit,
    selectedRadarNode: RadarNode?,
    onSelectedRadarNodeChange: (RadarNode?) -> Unit,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    surfaceVariant: Color,
    sharedPrefs: android.content.SharedPreferences
) {
    if (showLogsDialog) {
        val context = LocalContext.current
        var logsText by remember { mutableStateOf("") }
        
        var upnpDetails by remember { mutableStateOf(emptyMap<String, String>()) }
        var trackerDiagnostics by remember { mutableStateOf(emptyMap<String, String>()) }
        var yggDiagnostics by remember { mutableStateOf(emptyMap<String, String>()) }
        var activePeers by remember { mutableStateOf(emptyList<String>()) }
        val diagnosticsScope = rememberCoroutineScope()

        val applySnapshot: (DiagnosticsSnapshot) -> Unit = { snapshot ->
            logsText = snapshot.logs
            upnpDetails = snapshot.upnp
            trackerDiagnostics = snapshot.trackers
            yggDiagnostics = snapshot.yggdrasil
            activePeers = snapshot.activePeers
        }
        
        val refreshDiagnostics: () -> Unit = {
            diagnosticsScope.launch {
                applySnapshot(withContext(Dispatchers.IO) { readDiagnosticsSnapshot(context) })
            }
        }
        
        LaunchedEffect(Unit) {
            while (isActive) {
                applySnapshot(withContext(Dispatchers.IO) { readDiagnosticsSnapshot(context) })
                delay(2_000)
            }
        }

        val upnpStatus = remember(upnpDetails) {
            val mapped = upnpDetails["mapped"] == "true"
            val err = upnpDetails["error"] ?: ""
            when {
                mapped -> NetworkNodeState.OK
                err.contains("progress", ignoreCase = true) -> NetworkNodeState.WARNING
                err.contains("Discovery", ignoreCase = true) || err.contains("SOAP", ignoreCase = true) -> NetworkNodeState.ERROR
                else -> NetworkNodeState.DISABLED
            }
        }

        val trackerStatus = remember(trackerDiagnostics) {
            if (trackerDiagnostics.isEmpty()) {
                NetworkNodeState.DISABLED
            } else {
                val okCount = trackerDiagnostics.values.count { it.contains("announce=ok", ignoreCase = true) }
                if (okCount == trackerDiagnostics.size) {
                    NetworkNodeState.OK
                } else if (okCount > 0) {
                    NetworkNodeState.WARNING
                } else {
                    NetworkNodeState.ERROR
                }
            }
        }

        val yggStatus = remember(yggDiagnostics) {
            val state = yggDiagnostics["state"] ?: "disabled"
            val peers = yggDiagnostics["peers"]?.toIntOrNull() ?: 0
            when {
                state == "connected" || (state == "enabled" && peers > 0) -> NetworkNodeState.OK
                state == "enabled" && peers == 0 -> NetworkNodeState.WARNING
                state == "disabled" -> NetworkNodeState.DISABLED
                else -> NetworkNodeState.ERROR
            }
        }

        val peersCount = P2PMessageRelay.peerEndpoints.size

        val trackerUrls = defaultTrackerUrls

        val trackerPings = remember { mutableStateMapOf<String, Long>() }
        val yggdrasilAvailable = yggDiagnostics["state"] in setOf("enabled", "connected") &&
            PythonBridge.getYggdrasilAddress().isNotBlank()
        LaunchedEffect(selectedRadarNode, trackerDiagnostics) {
            if (selectedRadarNode == RadarNode.TRACKERS) {
                trackerDiagnostics.keys.forEach { name ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val url = trackerUrls[name] ?: ""
                        val ping = if (name.startsWith("Yggdrasil-only") && !yggdrasilAvailable) -2L else getTrackerPing(url)
                        trackerPings[name] = ping
                    }
                }
            }
        }

        if (selectedRadarNode != null && !showRadarView) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = {
                    onSelectedRadarNodeChange(null)
                    onDismissRequest()
                },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = surfaceColor),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .fillMaxHeight(0.75f)
                        .padding(vertical = 12.dp)
                        .border(0.5.dp, primaryColor.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp)
                    ) {
                        // Header Row (Title + Close Button)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (appLanguage == "Русский") selectedRadarNode.labelRu else selectedRadarNode.labelEn,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = onSurfaceColor
                            )
                            IconButton(
                                onClick = {
                                    onSelectedRadarNodeChange(null)
                                    onDismissRequest()
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(onSurfaceColor.copy(alpha = 0.05f), shape = CircleShape)
                            ) {
                                Text("✕", fontSize = 13.sp, color = onSurfaceVariant, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Node Details List
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NodeDetailContent(
                                node = selectedRadarNode,
                                context = context,
                                appLanguage = appLanguage,
                                primaryColor = primaryColor,
                                surfaceColor = surfaceColor,
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                surfaceVariant = surfaceVariant,
                                sharedPrefs = sharedPrefs,
                                upnpDetails = upnpDetails,
                                trackerDiagnostics = trackerDiagnostics,
                                trackerPings = trackerPings,
                                yggDiagnostics = yggDiagnostics,
                                activePeers = activePeers,
                                refreshDiagnostics = refreshDiagnostics
                            )
                        }
                    }
                }
            }
        } else {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { onDismissRequest() },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.85f)
                    .padding(vertical = 12.dp)
                    .border(0.5.dp, primaryColor.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        // Header Row: Title & Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (appLanguage == "Русский") "Сетевой отладчик" else "Network Debugger",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = onSurfaceColor
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Refresh
                                IconButton(
                                    onClick = { refreshDiagnostics() },
                                    modifier = Modifier.size(36.dp).background(onSurfaceColor.copy(alpha = 0.05f), shape = CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh",
                                        tint = primaryColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                // Clear (only logs)
                                IconButton(
                                    onClick = {
                                        clearLogFile(context)
                                        logsText = readLogFile(context)
                                    },
                                    modifier = Modifier.size(36.dp).background(onSurfaceColor.copy(alpha = 0.05f), shape = CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Clear",
                                        tint = Color.Red,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                // Share (only logs)
                                IconButton(
                                    onClick = { shareLogFile(context) },
                                    modifier = Modifier.size(36.dp).background(onSurfaceColor.copy(alpha = 0.05f), shape = CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share",
                                        tint = primaryColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Segmented Control (Radar vs Logs)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .background(onSurfaceColor.copy(alpha = 0.05f), shape = RoundedCornerShape(12.dp))
                                .padding(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (showRadarView) primaryColor else Color.Transparent)
                                    .clickable { onShowRadarViewChange(true) }
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") "Радар связей" else "Radar View",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (showRadarView) (if (primaryColor == MintGreen) StealthBlack else Color.White) else onSurfaceColor
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (!showRadarView) primaryColor else Color.Transparent)
                                    .clickable { onShowRadarViewChange(false) }
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") "Консоль логов" else "Logs Console",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (!showRadarView) (if (primaryColor == MintGreen) StealthBlack else Color.White) else onSurfaceColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (showRadarView) {
                            // RADAR VIEW MODE
                            Column(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                NetworkRadarWidget(
                                    upnpStatus = upnpStatus,
                                    trackerStatus = trackerStatus,
                                    yggStatus = yggStatus,
                                    peersCount = peersCount,
                                    onNodeClicked = { node ->
                                        onSelectedRadarNodeChange(node)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                
                                Text(
                                    text = if (appLanguage == "Русский") "Нажмите на узел радара для подробностей" else "Tap a radar node for connection details",
                                    fontSize = 11.sp,
                                    color = onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        } else {
                            // RAW LOGS CONSOLE MODE
                            val dialogScrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(dialogScrollState)
                            ) {
                                // Connection Info Cards
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = surfaceVariant.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = if (appLanguage == "Русский") "СТАТУС ПОДКЛЮЧЕНИЙ" else "CONNECTION DIAGNOSTICS",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryColor,
                                            letterSpacing = 0.5.sp,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = if (appLanguage == "Русский") "Порт сервера:" else "P2P Server Port:",
                                                fontSize = 13.sp,
                                                color = onSurfaceColor
                                            )
                                            Text(
                                                text = "${P2PMessageRelay.listenerPort(context)} (listening)",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF4CAF50)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val listenerPort = P2PMessageRelay.listenerPort(context)
                                        val announcedEndpoints = PythonBridge.getLocalAddresses().map { host ->
                                            when {
                                                host.contains(':') -> "[$host]:$listenerPort"
                                                host == "10.0.2.16" -> "$host:$listenerPort (emulator local)"
                                                else -> "$host:$listenerPort"
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = if (appLanguage == "Русский") "Анонсируемые endpoint-ы:" else "Announced endpoints:",
                                                fontSize = 13.sp,
                                                color = onSurfaceColor
                                            )
                                            Text(
                                                text = "${announcedEndpoints.size}",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = primaryColor
                                            )
                                        }
                                        if (announcedEndpoints.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(onSurfaceColor.copy(alpha = 0.02f), shape = RoundedCornerShape(8.dp))
                                                    .padding(8.dp)
                                            ) {
                                                announcedEndpoints.forEach { endpoint ->
                                                    Text(
                                                        text = "• $endpoint",
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val publicTrackerIpv4 = PythonBridge.getObservedPublicAddresses()
                                            .filter { address ->
                                                address.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) &&
                                                    !address.startsWith("10.") &&

                                                    !address.startsWith("127.") &&
                                                    !address.startsWith("192.168.") &&
                                                    !address.startsWith("172.16.")
                                            }
                                            .distinct()
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = if (appLanguage == "Русский") "Публичный IPv4 по данным discovery:" else "Public IPv4 seen by discovery:",
                                                fontSize = 13.sp,
                                                color = onSurfaceColor
                                            )
                                            Text(
                                                text = if (publicTrackerIpv4.isNotEmpty()) publicTrackerIpv4.joinToString(", ") else "n/a",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (publicTrackerIpv4.isNotEmpty()) Color(0xFF4CAF50) else onSurfaceVariant
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val yggTrackerDiagnosticsMap = trackerDiagnostics
                                            .filterKeys { it.contains("Yggdrasil", ignoreCase = true) }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Ygg tracker status:",
                                                fontSize = 13.sp,
                                                color = onSurfaceColor
                                            )
                                            Text(
                                                text = "${yggTrackerDiagnosticsMap.size}",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = primaryColor
                                            )
                                        }
                                        if (yggTrackerDiagnosticsMap.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(onSurfaceColor.copy(alpha = 0.02f), shape = RoundedCornerShape(8.dp))
                                                    .padding(8.dp)
                                            ) {
                                                yggTrackerDiagnosticsMap.forEach { (trackerName, status) ->
                                                    Text(
                                                        text = "• $trackerName -> $status",
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = if (appLanguage == "Русский") "Локальный IPv4 адрес:" else "Local IPv4 Address:",
                                                fontSize = 13.sp,
                                                color = onSurfaceColor
                                            )
                                            val ipv4List = PythonBridge.getLocalAddresses().filter { !it.contains(':') }
                                            Text(
                                                text = if (ipv4List.isNotEmpty()) ipv4List.joinToString(", ") else "127.0.0.1",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = primaryColor
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = if (appLanguage == "Русский") "Мой Yggdrasil IPv6:" else "My Yggdrasil IPv6:",
                                                fontSize = 13.sp,
                                                color = onSurfaceColor
                                            )
                                            val yggAddress = PythonBridge.getYggdrasilAddress()
                                            Text(
                                                text = if (yggAddress.isNotEmpty()) yggAddress else (if (appLanguage == "Русский") "Не обнаружен" else "Not detected"),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (yggAddress.isNotEmpty()) Color(0xFF4CAF50) else Color.Red
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = if (appLanguage == "Русский") "Проверка сети Yggdrasil:" else "Yggdrasil network check:",
                                                fontSize = 13.sp,
                                                color = onSurfaceColor
                                            )
                                            val state = yggDiagnostics["state"] ?: "disabled"
                                            val peers = yggDiagnostics["peers"] ?: "0"
                                            val routes = yggDiagnostics["routes"] ?: "0"
                                            Text(
                                                text = "$state · peers=$peers · routes=$routes",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (state == "connected") Color(0xFF4CAF50) else Color.Red
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = if (appLanguage == "Русский") "Активных пиров:" else "Resolved Peer IPs:",
                                                fontSize = 13.sp,
                                                color = onSurfaceColor
                                            )
                                            Text(
                                                text = "${com.example.twopchat.P2PMessageRelay.peerEndpoints.size}",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = primaryColor
                                            )
                                        }
                                        if (com.example.twopchat.P2PMessageRelay.peerEndpoints.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(onSurfaceColor.copy(alpha = 0.02f), shape = RoundedCornerShape(8.dp))
                                                    .padding(8.dp)
                                            ) {
                                                com.example.twopchat.P2PMessageRelay.peerEndpoints.forEach { (peer, ip) ->
                                                    Text(
                                                        text = "• $peer -> $ip",
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = if (appLanguage == "Русский") "СИСТЕМНЫЙ ЛОГ (app.log)" else "SYSTEM LOG (app.log)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )

                                // Terminal Console Box
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(280.dp)
                                        .background(Color(0xFF0F1115), shape = RoundedCornerShape(12.dp))
                                        .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), shape = RoundedCornerShape(12.dp))
                                        .padding(10.dp)
                                ) {
                                    val consoleScrollState = rememberScrollState()
                                    var followLogTail by remember { mutableStateOf(true) }
                                    val logScrollObserver = remember(consoleScrollState) {
                                        object : NestedScrollConnection {
                                            override fun onPostScroll(
                                                consumed: Offset,
                                                available: Offset,
                                                source: NestedScrollSource,
                                            ): Offset {
                                                if (source == NestedScrollSource.UserInput) {
                                                    followLogTail = isNearLogTail(
                                                        consoleScrollState.value,
                                                        consoleScrollState.maxValue,
                                                    )
                                                }
                                                return Offset.Zero
                                            }
                                        }
                                    }
                                    LaunchedEffect(logsText, followLogTail) {
                                        if (followLogTail) {
                                            withFrameNanos { }
                                            consoleScrollState.scrollTo(consoleScrollState.maxValue)
                                        }
                                    }

                                    SelectionContainer {
                                        Text(
                                            text = formatLogs(logsText),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .nestedScroll(logScrollObserver)
                                                .verticalScroll(consoleScrollState)
                                        )
                                    }
                                    if (!followLogTail) {
                                        FilledTonalIconButton(
                                            onClick = {
                                                followLogTail = true
                                            },
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .size(36.dp),
                                        ) {
                                            Text(
                                                "↓",
                                                color = primaryColor,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Close Button
                        Button(
                            onClick = { onDismissRequest() },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {

                            Text(
                                text = Localizations.getString("close", appLanguage),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Sliding detail BottomSheet-like drawer
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showRadarView && selectedRadarNode != null,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it }),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        selectedRadarNode?.let { node ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = surfaceVariant),
                                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.65f)
                                    .border(0.5.dp, primaryColor.copy(alpha = 0.2f), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    // Drag Handle
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterHorizontally)
                                            .padding(bottom = 12.dp)
                                            .width(36.dp)
                                            .height(4.dp)
                                            .background(onSurfaceColor.copy(alpha = 0.15f), shape = CircleShape)
                                    )
                                    // Drawer Header
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (appLanguage == "Русский") node.labelRu else node.labelEn,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = onSurfaceColor
                                        )
                                        IconButton(
                                            onClick = { onSelectedRadarNodeChange(null) },
                                            modifier = Modifier.size(30.dp).background(onSurfaceColor.copy(alpha = 0.05f), shape = CircleShape)
                                        ) {
                                            Text("✕", fontSize = 12.sp, color = onSurfaceVariant, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(10.dp))
                                    
                                    // Drawer Content (Scrollable list of details)
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        NodeDetailContent(
                                            node = node,
                                            context = context,
                                            appLanguage = appLanguage,
                                            primaryColor = primaryColor,
                                            surfaceColor = surfaceColor,
                                            onSurfaceColor = onSurfaceColor,
                                            onSurfaceVariant = onSurfaceVariant,
                                            surfaceVariant = surfaceVariant,
                                            sharedPrefs = sharedPrefs,
                                            upnpDetails = upnpDetails,
                                            trackerDiagnostics = trackerDiagnostics,
                                            trackerPings = trackerPings,
                                            yggDiagnostics = yggDiagnostics,
                                            activePeers = activePeers,
                                            refreshDiagnostics = refreshDiagnostics
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun NodeDetailContent(
    node: RadarNode,
    context: android.content.Context,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    surfaceVariant: Color,
    sharedPrefs: android.content.SharedPreferences,
    upnpDetails: Map<String, String>,
    trackerDiagnostics: Map<String, String>,
    trackerPings: Map<String, Long>,
    yggDiagnostics: Map<String, String>,
    activePeers: List<String>,
    refreshDiagnostics: () -> Unit,
) {
    when (node) {
        RadarNode.SELF -> {
            val localIps = PythonBridge.getLocalAddresses()
            DetailRow(if (appLanguage == "Русский") "Мой Fingerprint:" else "My Fingerprint:", PythonBridge.getLocalFingerprint(), primaryColor)
            DetailRow(if (appLanguage == "Русский") "Порт P2P Сервера:" else "P2P Server Port:", "${P2PMessageRelay.listenerPort(context)} (listening)", Color(0xFF4CAF50))
            DetailRow(if (appLanguage == "Русский") "Локальные IP адреса:" else "Local IP Addresses:", localIps.joinToString("\n"), primaryColor)
        }
        RadarNode.ROUTER -> {
            val mapped = upnpDetails["mapped"] == "true"
            val extIp = upnpDetails["external_ip"] ?: "n/a"
            val intIp = upnpDetails["local_ip"] ?: "n/a"
            val port = upnpDetails["port"] ?: "n/a"
            val service = upnpDetails["service_type"] ?: "n/a"
            val controlUrl = upnpDetails["control_url"] ?: "n/a"
            val errorMsg = upnpDetails["error"] ?: "n/a"
            
            DetailRow(if (appLanguage == "Русский") "Статус проброса:" else "UPnP Mapped Status:", if (mapped) "CONNECTED / OK" else "FAILED / OFFLINE", if (mapped) Color(0xFF4CAF50) else Color.Red)
            DetailRow(if (appLanguage == "Русский") "Внешний IP адрес:" else "Router External IP:", extIp, primaryColor)
            DetailRow(if (appLanguage == "Русский") "Внутренний IP адрес:" else "Client Internal IP:", intIp, primaryColor)
            DetailRow(if (appLanguage == "Русский") "Проброшенный порт:" else "Mapped Port:", port, primaryColor)
            DetailRow(if (appLanguage == "Русский") "Тип шлюза / Service:" else "Gateway Service:", service, primaryColor)
            DetailRow(if (appLanguage == "Русский") "Адрес управления (SOAP):" else "Control SOAP URL:", controlUrl, onSurfaceVariant)
            if (!mapped) {
                DetailRow(if (appLanguage == "Русский") "Код ошибки:" else "Error message:", errorMsg, Color.Red)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            var upnpReopening by remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()
            
            Button(
                onClick = {
                    upnpReopening = true
                    coroutineScope.launch {
                        val success = PythonBridge.triggerUpnpReopen()
                        kotlinx.coroutines.delay(2000)
                        refreshDiagnostics()
                        upnpReopening = false
                        Toast.makeText(context, if (success) "UPnP reopen triggered!" else "Failed to trigger UPnP reopen", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !upnpReopening,
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (upnpReopening) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(if (appLanguage == "Русский") "Переоткрыть порт" else "Re-open Port", color = Color.White)
                }
            }
        }
        RadarNode.TRACKERS -> {
            val displayTrackers: Map<String, String> = if (trackerDiagnostics.isNotEmpty()) {
                trackerDiagnostics
            } else {
                defaultTrackerUrls.keys.associateWith { "announce=PENDING, resolve=PENDING, announce_rtt=n/ams, resolve_rtt=n/ams" }
            }
            displayTrackers.forEach { (name, status) ->
                val ping = trackerPings[name]
                val announceRtt = Regex("announce_rtt=(\\d+)ms").find(status)?.groupValues?.get(1)?.toLongOrNull()
                val pingText = if (announceRtt != null) {
                     "RTT ${announceRtt}ms"
                } else if (ping == null) {
                    if (appLanguage == "Русский") "опрос..." else "probing..."
                } else if (ping == -2L) {
                    if (appLanguage == "Русский") "Yggdrasil выкл." else "Yggdrasil off"
                } else if (ping == -3L) {
                    "IPv6 literal"
                } else if (ping < 0) {
                    if (appLanguage == "Русский") "DNS недоступен" else "DNS unavailable"
                } else if (ping == 0L) {
                    "DNS <1ms"
                } else {
                    "DNS ${ping}ms"
                }
                val skipped = status.contains("SKIPPED", ignoreCase = true)
                val announceOk = status.contains("announce=OK", ignoreCase = true) || status.contains("resolve=OK", ignoreCase = true)
                val pingColor = when {
                    ping != null && ping >= 0L -> Color(0xFF4CAF50)
                    ping == -2L || ping == -3L || skipped -> onSurfaceVariant
                    ping == null -> Color(0xFFFFD740)
                    else -> Color(0xFFFF5252)
                }
                val detailStatusColor = when {
                    announceOk -> Color(0xFF4CAF50)
                    status.contains("PENDING", ignoreCase = true) -> Color(0xFFFFD740)
                    skipped -> onSurfaceVariant
                    else -> Color(0xFFFF5252)
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = onSurfaceColor)
                            Text(pingText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pingColor)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = status,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = onSurfaceVariant
                        )
                    }
                }
            }
        }
        RadarNode.YGGDRASIL -> {
            val state = yggDiagnostics["state"] ?: "disabled"
            val peers = yggDiagnostics["peers"] ?: "0"
            val routes = yggDiagnostics["routes"] ?: "0"
            val treeNodes = yggDiagnostics["tree_nodes"] ?: "0"
            val address = PythonBridge.getYggdrasilAddress()
            
            DetailRow(if (appLanguage == "Русский") "Статус Go-демона:" else "Daemon Status:", state.uppercase(), if (state == "connected") Color(0xFF4CAF50) else Color.Red)
            DetailRow(if (appLanguage == "Русский") "Адрес IPv6 Yggdrasil:" else "Yggdrasil IPv6:", if (address.isNotEmpty()) address else "n/a", primaryColor)
            DetailRow(if (appLanguage == "Русский") "Количество пиров (mesh):" else "Mesh Peers Count:", peers, primaryColor)
            DetailRow(if (appLanguage == "Русский") "Количество маршрутов:" else "Routing table size:", routes, primaryColor)
            DetailRow(if (appLanguage == "Русский") "Узлов в дереве (DHT):" else "DHT tree nodes count:", treeNodes, primaryColor)
            
            val yggPeersJsonStr = sharedPrefs.getString("yggdrasil_runtime_peers_json", "") ?: ""
            if (yggPeersJsonStr.isNotEmpty() && yggPeersJsonStr != "null") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(if (appLanguage == "Русский") "ПУБЛИЧНЫЕ ПИРЫ:" else "PUBLIC MESH PEERS:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = primaryColor)
                
                val peersList = remember(yggPeersJsonStr) {
                    val list = mutableListOf<Map<String, String>>()
                    try {
                        val arr = JSONArray(yggPeersJsonStr)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            fun firstString(vararg keys: String): String = keys
                                .asSequence()
                                .map { obj.optString(it, "").trim() }
                                .firstOrNull { it.isNotEmpty() && it != "null" }
                                .orEmpty()
                            fun firstLong(vararg keys: String): Long = keys
                                .asSequence()
                                .filter { obj.has(it) }
                                .map { obj.optLong(it, 0L) }
                                .firstOrNull() ?: 0L

                            val uri = firstString("URI", "uri", "endpoint", "address")
                            val remote = firstString("Remote", "remote", "Address")
                            val key = firstString("Key", "key")
                            val up = if (obj.has("Up")) obj.optBoolean("Up") else obj.optBoolean("up", true)
                            val inbound = if (obj.has("Inbound")) obj.optBoolean("Inbound") else obj.optBoolean("inbound", false)
                            val uptime = firstLong("Uptime", "uptime")
                            val uptimeSeconds = if (uptime > 86_400_000_000L) uptime / 1_000_000_000L else uptime
                            val uptimeText = if (uptimeSeconds > 0) {
                                "${uptimeSeconds / 3600}h ${(uptimeSeconds % 3600) / 60}m ${uptimeSeconds % 60}s"
                            } else if (up) "connected" else "offline"
                            val tx = firstLong("TXBytes", "bytes_sent", "tx")
                            val rx = firstLong("RXBytes", "bytes_recv", "rx")
                            val latency = firstLong("Latency", "latency")
                            val cost = firstLong("Cost", "cost")
                            val lastError = firstString("LastError", "last_error", "error")
                            list.add(mapOf(
                                "address" to (uri.ifEmpty { remote.ifEmpty { key.take(16).ifEmpty { "peer #${i + 1}" } } }),
                                "remote" to remote,
                                "key" to key,
                                "state" to (if (up) "ONLINE" else "OFFLINE"),
                                "direction" to (if (inbound) "INBOUND" else "OUTBOUND"),
                                "uptime" to uptimeText,
                                "traffic" to "TX: ${tx / 1024} KB / RX: ${rx / 1024} KB",
                                "route" to "Cost: $cost · Latency: ${latency / 1_000_000} ms",
                                "error" to lastError
                            ))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    list
                }
                
                peersList.forEach { peerMap ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth().border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(peerMap["address"] ?: "", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = onSurfaceColor)
                            if (!peerMap["remote"].isNullOrEmpty()) {
                                Text("Remote: ${peerMap["remote"]}", fontSize = 11.sp, color = onSurfaceVariant)
                            }
                            if (!peerMap["key"].isNullOrEmpty()) {
                                Text("Key: ${peerMap["key"]}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = onSurfaceVariant)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${peerMap["state"]} · ${peerMap["direction"]}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (peerMap["state"] == "ONLINE") Color(0xFF4CAF50) else Color.Red)
                                Text("Uptime: ${peerMap["uptime"]}", fontSize = 11.sp, color = onSurfaceVariant)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(peerMap["traffic"] ?: "", fontSize = 11.sp, color = onSurfaceVariant)
                                Text(peerMap["route"] ?: "", fontSize = 11.sp, color = onSurfaceVariant)
                            }
                            if (!peerMap["error"].isNullOrEmpty()) {
                                Text("Error: ${peerMap["error"]}", fontSize = 10.sp, color = Color.Red, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
            }
        }
        RadarNode.PEERS -> {
            if (activePeers.isEmpty()) {
                Text(
                    text = if (appLanguage == "Русский") "Нет активных сессий Double Ratchet" else "No active Double Ratchet sessions established",
                    color = onSurfaceVariant,
                    fontSize = 13.sp
                )
            } else {
                activePeers.forEach { name ->
                    val endpoint = P2PMessageRelay.peerEndpoints[name] ?: "resolving..."
                    val transport = connectionTransportLabel(
                        rawTransport = P2PMessageRelay.peerConnectionTransports[name],
                        endpoint = P2PMessageRelay.peerEndpoints[name],
                        appLanguage = appLanguage,
                    )
                    val isEstablished = name in activePeers
                    Card(
                        colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth().border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = onSurfaceColor)
                                Text("EP: $endpoint", fontSize = 11.sp, color = onSurfaceVariant)
                                Text("Transport: $transport", fontSize = 11.sp, color = primaryColor)
                            }
                            Text(
                                text = if (isEstablished) "ONLINE" else "WAITING",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (isEstablished) Color(0xFF4CAF50) else Color(0xFFFFC107),
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.widthIn(min = 58.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

