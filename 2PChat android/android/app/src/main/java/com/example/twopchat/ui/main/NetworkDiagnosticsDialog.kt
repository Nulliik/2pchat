package com.example.twopchat.ui.main

import android.widget.Toast
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.PythonBridge
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.connectionTransportLabel
import com.example.twopchat.theme.*
import com.example.twopchat.data.Localizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import org.json.JSONArray
import java.io.File

private val defaultTrackerUrls = mapOf(
    "Torrent.eu.org UDP" to "udp://tracker.torrent.eu.org:451/announce",
    "Open Stealth UDP" to "udp://open.stealth.si:80/announce",
    "Exodus UDP" to "udp://exodus.desync.com:6969/announce",
    "OpenTrackr UDP" to "udp://tracker.opentrackr.org:1337/announce",
    "Dler UDP" to "udp://tracker2.dler.org:80/announce",
    "FileBase UDP" to "udp://tracker.filebase.online:6969/announce",
    "BitSearch UDP" to "udp://tracker.bitsearch.to:6969/announce",
    "OpenTrackr HTTP" to "http://tracker.opentrackr.org:1337/announce",
    "Dler HTTP" to "http://tracker2.dler.org:80/announce",
    "Qu.Ax HTTP" to "http://tracker.qu.ax:6969/announce",
    "OpenTrackr HTTPS" to "https://tracker.opentrackr.org:443/announce",
    "Yemekyedim HTTPS" to "https://tracker.yemekyedim.com:443/announce",
    "Nyacat HTTPS" to "https://tr.nyacat.pw:443/announce",
    "Yggdrasil-only HTTP" to "http://[200:1e2f:e608:eb3a:2bf:1e62:87ba:e2f7]/announce",
    "Yggdrasil-only UDP" to "udp://[202:68d0:f0d5:b88d:1d1a:555e:2f6b:3148]:6969/announce"
)

internal fun isNearLogTail(scrollValue: Int, maxScrollValue: Int, thresholdPx: Int = 32): Boolean {
    require(scrollValue >= 0 && maxScrollValue >= 0 && thresholdPx >= 0)
    return maxScrollValue - scrollValue <= thresholdPx
}

private fun readLogFile(context: Context): String {
    return try {
        val logFile = File(File(context.filesDir, "config"), "app.log")
        if (!logFile.exists()) {
            return "No logs found yet. Connection activities will appear here."
        }
        val lines = logFile.readLines()
        val lastLines = lines.takeLast(400)
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

private fun readDiagnosticsSnapshot(context: Context) = DiagnosticsSnapshot(
    logs = readLogFile(context),
    upnp = runCatching { PythonBridge.getUpnpDetails() }.getOrDefault(emptyMap()),
    trackers = runCatching { PythonBridge.getTrackerDiagnostics() }.getOrDefault(emptyMap()),
    yggdrasil = runCatching { PythonBridge.getYggdrasilNetworkDiagnostics() }.getOrDefault(emptyMap()),
    activePeers = runCatching { PythonBridge.getActivePeers() }.getOrDefault(emptyList()),
)

private fun clearLogFile(context: Context) {
    try {
        val logFile = File(File(context.filesDir, "config"), "app.log")
        if (logFile.exists()) {
            logFile.writeText("")
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun shareLogFile(context: Context) {
    try {
        val logFile = File(File(context.filesDir, "config"), "app.log")
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
        Toast.makeText(context, "Failed to share logs", Toast.LENGTH_SHORT).show()
    }
}

private suspend fun runConnectionDiagnosticsTest(context: Context): String = withContext(Dispatchers.IO) {
    val sb = java.lang.StringBuilder()
    val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
    sb.appendLine("=========================================")
    sb.appendLine("⚡ [DIAGNOSTICS_TEST] P2P CONNECTION SWEEP AT $timeStr")
    sb.appendLine("=========================================")

    // 1. P2P Local Server Listener Port
    val port = P2PMessageRelay.listenerPort(context)
    val isLoopRunning = runCatching { PythonBridge.isLoopRunning() }.getOrDefault(false)
    val portOk = port > 0 && isLoopRunning
    sb.appendLine("1. P2P Server: Port $port | EventLoop Active: $isLoopRunning -> ${if (portOk) "OK" else "WARN"}")

    // 2. Yggdrasil IPv6 Interface
    val yggAddr = PythonBridge.getYggdrasilAddress()
    val yggDiag = runCatching { PythonBridge.getYggdrasilNetworkDiagnostics() }.getOrDefault(emptyMap())
    val yggState = yggDiag["state"] ?: "disabled"
    val yggOk = yggAddr.isNotBlank() && yggState in setOf("enabled", "connected")
    sb.appendLine("2. Yggdrasil IPv6: ${if (yggAddr.isNotBlank()) yggAddr else "Not Active"} | State: $yggState -> ${if (yggOk) "OK" else "OFFLINE"}")

    // 3. UPnP Gateway Status
    val upnp = runCatching { PythonBridge.getUpnpDetails() }.getOrDefault(emptyMap())
    val upnpMapped = upnp["mapped"] == "true"
    val upnpError = upnp["error"] ?: ""
    sb.appendLine("3. UPnP Port Mapping: ${if (upnpMapped) "MAPPED (OK)" else if (upnpError.isNotBlank()) "DISABLED ($upnpError)" else "OFF"}")

    // 4. BitTorrent Trackers Health
    val trackers = runCatching { PythonBridge.getTrackerDiagnostics() }.getOrDefault(emptyMap())
    val okCount = trackers.values.count { it.contains("announce=ok", ignoreCase = true) }
    sb.appendLine("4. BitTorrent Trackers: $okCount / ${trackers.size} Accepted -> ${if (okCount > 0) "ONLINE" else "WAITING"}")

    // 5. Active Peer Sessions
    val activePeers = runCatching { PythonBridge.getActivePeers() }.getOrDefault(emptyList())
    val registeredEndpoints = P2PMessageRelay.peerEndpoints.size
    sb.appendLine("5. Active Double Ratchet Sessions: ${activePeers.size} active (${registeredEndpoints} endpoints registered)")

    sb.appendLine("=========================================")
    sb.appendLine("⚡ [DIAGNOSTICS_TEST] SWEEP FINISHED: ${if (portOk && okCount > 0) "ALL SYSTEMS OPERATIONAL" else "PARTIAL CONNECTIVITY"}")
    sb.appendLine("=========================================")

    val resultLog = sb.toString()
    try {
        val logFile = File(File(context.filesDir, "config"), "app.log")
        logFile.appendText("\n" + resultLog)
    } catch (_: Exception) {}

    return@withContext resultLog
}

@Composable
private fun CustomCopyIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeW = (w * 0.10f).coerceAtLeast(1.5f)
        // Background card
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.28f, h * 0.08f),
            size = Size(w * 0.62f, h * 0.62f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f, h * 0.12f),
            style = Stroke(width = strokeW)
        )
        // Foreground card
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.28f),
            size = Size(w * 0.62f, h * 0.62f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f, h * 0.12f),
            style = Stroke(width = strokeW)
        )
    }
}

@Composable
private fun CustomLightningIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.55f, h * 0.05f)
            lineTo(w * 0.15f, h * 0.55f)
            lineTo(w * 0.48f, h * 0.55f)
            lineTo(w * 0.42f, h * 0.95f)
            lineTo(w * 0.85f, h * 0.45f)
            lineTo(w * 0.52f, h * 0.45f)
            close()
        }
        drawPath(path, color = tint)
    }
}

@Composable
private fun formatLogs(
    logsText: String,
    searchQuery: String = "",
    levelFilter: String = "ALL"
): Pair<AnnotatedString, Int> {
    val lines = logsText.split("\n")
    var matchedCount = 0
    val builder = remember(logsText, searchQuery, levelFilter) {
        AnnotatedString.Builder().apply {
            lines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed

                val matchesLevel = when (levelFilter) {
                    "ERRORS" -> (line.contains("[ERROR]") || line.contains("[PYTHON_ERR]") || line.contains("FAILED") || line.contains("timed out") || line.contains("Exception") || line.contains("Error")) && !line.contains("[INFO]") && !line.contains("accepted") && !line.contains("Announce self status")
                    "P2P" -> line.contains("P2PMessageRelay") || line.contains("p2p") || line.contains("PythonBridge") || line.contains("OutboundMessenger")
                    "YGG" -> line.contains("PacketTunnelProvider") || line.contains("Yggdrasil") || line.contains("GoLog") || line.contains("TUN")
                    "TRACKERS" -> line.contains("Tracker") || line.contains("announce") || line.contains("discovery")
                    else -> true
                }

                val matchesQuery = searchQuery.isBlank() || line.contains(searchQuery, ignoreCase = true)

                if (matchesLevel && matchesQuery) {
                    matchedCount++
                    val kotlinInfoIndex = line.indexOf("[KOTLIN_INFO]")
                    val pythonErrIndex = line.indexOf("[PYTHON_ERR]")
                    val pythonOutIndex = line.indexOf("[PYTHON_OUT]")
                    val infoIndex = line.indexOf("[INFO]")
                    val errIndex = line.indexOf("[ERROR]")
                    val warnIndex = line.indexOf("[WARNING]")
                    val debugIndex = line.indexOf("[DEBUG]")

                    val isTestLog = line.contains("[DIAGNOSTICS_TEST]")
                    val isInfo = infoIndex != -1 || line.contains("Announce self status") || line.contains("accepted") || kotlinInfoIndex != -1
                    val isWarn = warnIndex != -1 || line.contains("Debounced") || line.contains("retry")
                    val isError = (pythonErrIndex != -1 || errIndex != -1 || line.contains("FAILED") || line.contains("timed out")) && !isInfo
                    val isSuccess = line.contains("SUCCESS") || line.contains("established") || line.contains("OPERATIONAL")

                    when {
                        isTestLog -> pushStyle(SpanStyle(color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold)) // Vivid Gold Test Header
                        isError -> pushStyle(SpanStyle(color = Color(0xFFFF5252))) // Coral Red
                        isSuccess -> pushStyle(SpanStyle(color = Color(0xFF69F0AE))) // Bright Mint
                        isInfo -> pushStyle(SpanStyle(color = Color(0xFF81C784))) // Light Green Info
                        isWarn -> pushStyle(SpanStyle(color = Color(0xFFFFD740))) // Amber Yellow
                        debugIndex != -1 -> pushStyle(SpanStyle(color = Color(0xFF90A4AE))) // Slate Gray
                        else -> pushStyle(SpanStyle(color = Color(0xFFECEFF1))) // Warm White
                    }

                    append(line)
                    pop()
                    if (index < lines.size - 1) {
                        append("\n")
                    }
                }
            }
        }.toAnnotatedString()
    }
    return Pair(builder, matchedCount)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDiagnosticsDialog(
    showLogsDialog: Boolean,
    onDismissRequest: () -> Unit,
    showRadarView: Boolean = false,
    onShowRadarViewChange: (Boolean) -> Unit = {},
    selectedRadarNode: RadarNode? = null,
    onSelectedRadarNodeChange: (RadarNode?) -> Unit = {},
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
        val clipboardManager = LocalClipboardManager.current
        val diagnosticsScope = rememberCoroutineScope()

        var logsText by remember { mutableStateOf("") }
        var upnpDetails by remember { mutableStateOf(emptyMap<String, String>()) }
        var trackerDiagnostics by remember { mutableStateOf(emptyMap<String, String>()) }
        var yggDiagnostics by remember { mutableStateOf(emptyMap<String, String>()) }
        var activePeers by remember { mutableStateOf(emptyList<String>()) }
        var showSummaryDetails by remember { mutableStateOf(false) }
        var levelFilter by remember { mutableStateOf("ALL") }
        var searchQuery by remember { mutableStateOf("") }
        var isTestingConnection by remember { mutableStateOf(false) }

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

        val (formattedLogs, lineCount) = formatLogs(logsText, searchQuery, levelFilter)

        androidx.compose.ui.window.Dialog(
            onDismissRequest = { onDismissRequest() },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.90f)
                    .padding(vertical = 8.dp)
                    .border(0.5.dp, primaryColor.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header Row: Title & Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(Color(0xFF4CAF50), shape = CircleShape)
                            )
                            Text(
                                text = if (appLanguage == "Русский") "Сетевой отладчик" else "Network Debugger",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = onSurfaceColor
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Test Connections Button
                            IconButton(
                                onClick = {
                                    if (!isTestingConnection) {
                                        isTestingConnection = true
                                        diagnosticsScope.launch {
                                            runConnectionDiagnosticsTest(context)
                                            applySnapshot(withContext(Dispatchers.IO) { readDiagnosticsSnapshot(context) })
                                            isTestingConnection = false
                                            Toast.makeText(context, if (appLanguage == "Русский") "Тест связи завершен!" else "Connection test complete!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(primaryColor.copy(alpha = 0.15f), shape = CircleShape)
                            ) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = primaryColor, strokeWidth = 2.dp)
                                } else {
                                    CustomLightningIcon(tint = primaryColor, modifier = Modifier.size(16.dp))
                                }
                            }
                            // Refresh
                            IconButton(
                                onClick = { refreshDiagnostics() },
                                modifier = Modifier.size(34.dp).background(onSurfaceColor.copy(alpha = 0.05f), shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = primaryColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            // Copy
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(formattedLogs.text))
                                    Toast.makeText(context, if (appLanguage == "Русский") "Логи скопированы" else "Logs copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(34.dp).background(onSurfaceColor.copy(alpha = 0.05f), shape = CircleShape)
                            ) {
                                CustomCopyIcon(tint = primaryColor, modifier = Modifier.size(15.dp))
                            }
                            // Share
                            IconButton(
                                onClick = { shareLogFile(context) },
                                modifier = Modifier.size(34.dp).background(onSurfaceColor.copy(alpha = 0.05f), shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share",
                                    tint = primaryColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            // Clear
                            IconButton(
                                onClick = {
                                    clearLogFile(context)
                                    logsText = readLogFile(context)
                                },
                                modifier = Modifier.size(34.dp).background(onSurfaceColor.copy(alpha = 0.05f), shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Clear",
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            // Close
                            IconButton(
                                onClick = { onDismissRequest() },
                                modifier = Modifier.size(34.dp).background(onSurfaceColor.copy(alpha = 0.05f), shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Expandable Network Diagnostics Summary Accordion Header
                    Card(
                        colors = CardDefaults.cardColors(containerColor = surfaceVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSummaryDetails = !showSummaryDetails }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") "📊 Сводка состояния сети" else "📊 Network Diagnostics Summary",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor
                                )
                                val yggState = yggDiagnostics["state"] ?: "disabled"
                                val activePeersCount = activePeers.size
                                Text(
                                    text = "YGG: $yggState · Peers: $activePeersCount",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = if (showSummaryDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle Summary",
                                tint = primaryColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (showSummaryDetails) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                                .background(surfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val listenerPort = P2PMessageRelay.listenerPort(context)
                            val yggAddress = PythonBridge.getYggdrasilAddress()
                            val localIpv4 = PythonBridge.getLocalAddresses().filter { !it.contains(':') }.joinToString(", ").ifEmpty { "127.0.0.1" }
                            val upnpMapped = upnpDetails["mapped"] == "true"
                            val trackerCount = trackerDiagnostics.size

                            Text(
                                text = "• P2P Server Port: $listenerPort (Listening) · Local IPv4: $localIpv4",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF4CAF50)
                            )
                            Text(
                                text = "• Yggdrasil IPv6: ${if (yggAddress.isNotEmpty()) yggAddress else "Off"} · State: ${yggDiagnostics["state"] ?: "disabled"}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (yggAddress.isNotEmpty()) primaryColor else onSurfaceVariant
                            )
                            Text(
                                text = "• UPnP Gateway: ${if (upnpMapped) "MAPPED (OK)" else "DISABLED"} · Active Trackers: $trackerCount",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (upnpMapped) Color(0xFF4CAF50) else onSurfaceVariant
                            )
                            if (P2PMessageRelay.peerEndpoints.isNotEmpty()) {
                                Text(
                                    text = "• Peer Endpoints (${P2PMessageRelay.peerEndpoints.size}):",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceColor
                                )
                                P2PMessageRelay.peerEndpoints.forEach { (peer, ep) ->
                                    Text(
                                        text = "   └ $peer -> $ep",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Log Filter Chips Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            "ALL" to (if (appLanguage == "Русский") "ВСЕ" else "ALL"),
                            "ERRORS" to (if (appLanguage == "Русский") "ОШИБКИ" else "ERRORS"),
                            "P2P" to "P2P",
                            "YGG" to "YGGDRASIL",
                            "TRACKERS" to (if (appLanguage == "Русский") "ТРЕКЕРЫ" else "TRACKERS")
                        ).forEach { (key, label) ->
                            val isSelected = levelFilter == key
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) primaryColor else onSurfaceColor.copy(alpha = 0.06f))
                                    .clickable { levelFilter = key }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) (if (primaryColor == MintGreen) StealthBlack else Color.White) else onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Search Bar Input
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = if (appLanguage == "Русский") "Поиск по логам..." else "Search logs...",
                                fontSize = 11.sp,
                                color = onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear search",
                                        tint = onSurfaceVariant,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryColor.copy(alpha = 0.5f),
                            unfocusedBorderColor = onSurfaceColor.copy(alpha = 0.1f),
                            focusedContainerColor = surfaceVariant.copy(alpha = 0.3f),
                            unfocusedContainerColor = surfaceVariant.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Log Stats Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "СИСТЕМНЫЙ ЛОГ (app.log)" else "SYSTEM LOG (app.log)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceVariant
                        )
                        Text(
                            text = if (appLanguage == "Русский") "Строк: $lineCount" else "Lines: $lineCount",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = primaryColor
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Monospace Dark Terminal Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFF0A0D14), shape = RoundedCornerShape(12.dp))
                            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp))
                            .padding(8.dp)
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

                        LaunchedEffect(formattedLogs, followLogTail) {
                            if (followLogTail) {
                                withFrameNanos { }
                                consoleScrollState.scrollTo(consoleScrollState.maxValue)
                            }
                        }

                        SelectionContainer {
                            Text(
                                text = formattedLogs,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(logScrollObserver)
                                    .verticalScroll(consoleScrollState)
                            )
                        }

                        if (!followLogTail) {
                            FilledTonalIconButton(
                                onClick = { followLogTail = true },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = primaryColor.copy(alpha = 0.85f)
                                ),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .size(34.dp)
                            ) {
                                Text(
                                    text = "↓",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Action Buttons Row: Test Connection & Close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (!isTestingConnection) {
                                    isTestingConnection = true
                                    diagnosticsScope.launch {
                                        runConnectionDiagnosticsTest(context)
                                        applySnapshot(withContext(Dispatchers.IO) { readDiagnosticsSnapshot(context) })
                                        isTestingConnection = false
                                        Toast.makeText(context, if (appLanguage == "Русский") "Тест связи завершен!" else "Connection test complete!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = surfaceVariant),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(0.4f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = primaryColor, strokeWidth = 2.dp)
                                } else {
                                    CustomLightningIcon(tint = primaryColor, modifier = Modifier.size(14.dp))
                                }
                                Text(
                                    text = if (appLanguage == "Русский") "Тест связи" else "Test Sweep",
                                    color = onSurfaceColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Button(
                            onClick = { onDismissRequest() },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(0.6f)
                        ) {
                            Text(
                                text = Localizations.getString("close", appLanguage),
                                color = if (primaryColor == MintGreen) StealthBlack else Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
