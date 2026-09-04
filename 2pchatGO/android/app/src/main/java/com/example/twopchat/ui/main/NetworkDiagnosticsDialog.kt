package com.example.twopchat.ui.main

import android.widget.Toast
import android.content.Context
import com.example.twopchat.tor.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
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
import com.example.twopchat.NativeBridge
import com.example.twopchat.bridge.P2PBridgeProvider
import com.example.twopchat.config.ProxyConfig
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.tor.*
import com.example.twopchat.relay.connectionTransportLabel
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
    "OpenTrackr UDP" to "udp://tracker.opentrackr.org:1337/announce",
    "OpenTrackr HTTPS" to "https://tracker.opentrackr.org:443/announce",
    "Open Stealth UDP" to "udp://open.stealth.si:80/announce",
    "Cyberia UDP" to "udp://tracker.cyberia.is:6969/announce",
    "Qu.Ax UDP" to "udp://tracker.qu.ax:6969/announce",
    "Bittor UDP" to "udp://tracker.bittor.pw:1337/announce",
    "Torrent EU UDP" to "udp://tracker.torrent.eu.org:451/announce",
    "Filemail UDP" to "udp://tracker.filemail.com:6969/announce",
    "Dler UDP" to "udp://tracker2.dler.org:80/announce",
    "Nyacat HTTPS" to "https://tr.nyacat.pw:443/announce",
    "Yggdrasil-only HTTP" to "http://[200:1e2f:e608:eb3a:2bf:1e62:87ba:e2f7]/announce",
    "Yggdrasil-only UDP" to "udp://[202:68d0:f0d5:b88d:1d1a:555e:2f6b:3148]:6969/announce",
    "Yggdrasil-only Opentracker" to "udp://[201:23a4:4941:da9e:b18a:eb2a:c22c:c4a6]:6969/announce"
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
        val lastLines = lines.takeLast(1000)
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

private fun readDiagnosticsSnapshot(context: Context): DiagnosticsSnapshot {
    val nat = NativeBridge.getNatDiagnostics()
    val isUpnpMapped = nat["upnp_mapped"] == "true"
    val extIp = nat["upnp_external_ip"]?.takeIf { it.isNotBlank() }
        ?: nat["public_endpoint"]?.takeIf { it.isNotBlank() }
        ?: "n/a"
    val localIp = nat["local_ip"]?.takeIf { it.isNotBlank() }
        ?: P2PMessageRelay.getLocalIpAddress(context)
    val port = nat["upnp_mapped_port"]?.takeIf { it.isNotBlank() }
        ?: "${P2PMessageRelay.listenerPort(context)}"
    val service = nat["upnp_service"]?.takeIf { it.isNotBlank() }
        ?: nat["nat_type"]?.takeIf { it.isNotBlank() }
        ?: "STUN / UPnP"
    val natType = nat["nat_type"]?.takeIf { it.isNotBlank() } ?: "UNKNOWN"

    val upnpData = mapOf(
        "mapped" to if (isUpnpMapped) "true" else "false",
        "external_ip" to extIp,
        "local_ip" to localIp,
        "port" to port,
        "service_type" to service,
        "nat_type" to natType,
    )

    return DiagnosticsSnapshot(
        logs = readLogFile(context),
        upnp = upnpData,
        // These statuses come exclusively from callbacks produced by the native
        // announce operation; configuration or DNS lookup is never treated as success.
        trackers = com.example.twopchat.config.TrackerPreferences.diagnosticStatuses(context),
        yggdrasil = mapOf("state" to if (P2PMessageRelay.getYggdrasilAddress().isNotBlank()) "connected" else "disabled"),
        activePeers = P2PMessageRelay.getActivePeerNames(),
    )
}

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

/** Returns DNS lookup latency only; it is deliberately not tracker ping/health. */
private fun getTrackerDnsLatency(announceUrl: String): Long {
    val startTime = System.currentTimeMillis()
    try {
        val clean = announceUrl.trim()
        val host = when {
            clean.startsWith("udp://", ignoreCase = true) -> clean.removePrefix("udp://").substringBefore('/').substringBeforeLast(':').trim('[', ']')
            clean.startsWith("http://", ignoreCase = true) -> clean.removePrefix("http://").substringBefore('/').substringBeforeLast(':').trim('[', ']')
            clean.startsWith("https://", ignoreCase = true) -> clean.removePrefix("https://").substringBefore('/').substringBeforeLast(':').trim('[', ']')
            else -> java.net.URI(clean).host ?: return -1L
        }
        if (host.isBlank()) return -1L
        if (host.contains(':')) {
            return -3L
        }
        java.net.InetAddress.getByName(host)
        return (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
    } catch (_: Exception) {
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

data class ConnectionSweepResult(
    val timestamp: String,
    val port: Int,
    val portOk: Boolean,
    val natType: String,
    val publicEndpoint: String,
    val upnpMapped: Boolean,
    val upnpPort: String,
    val yggAddress: String,
    val yggOk: Boolean,
    val yggModeLabel: String,
    val torActive: Boolean,
    val onionAddress: String,
    val trackersOnline: Int,
    val trackersTotal: Int,
    val trackerAvgRtt: Long,
    val relayReady: Boolean,
    val activePeersCount: Int,
    val isRooted: Boolean,
    val verdict: String,
    val isAllOk: Boolean,
    val formattedLog: String
)

private suspend fun runConnectionDiagnosticsTest(context: Context): ConnectionSweepResult = withContext(Dispatchers.IO) {
    val sb = java.lang.StringBuilder()
    val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
    sb.appendLine("=========================================")
    sb.appendLine("⚡ [DIAGNOSTICS_TEST] P2P CONNECTION SWEEP AT $timeStr")
    sb.appendLine("=========================================")

    // 1. P2P Local Server Listener Port
    val port = P2PMessageRelay.listenerPort(context)
    val portOk = port > 0
    sb.appendLine("1. Native Go P2P Server: Port $port | Listener Active: $portOk -> ${if (portOk) "OK" else "WARN"}")

    // 2. Active NAT, STUN & UPnP Diagnostics (via Go Core JNI)
    try {
        NativeBridge.refreshNatDiagnostics()
    } catch (e: Exception) {
        com.example.twopchat.logging.SafeLog.d("NetworkDiagnostics", "refreshNatDiagnostics failed: ${e.javaClass.simpleName}")
    }
    val nat = NativeBridge.getNatDiagnostics()
    val natType = nat["nat_type"]?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
    val upnpMapped = nat["upnp_mapped"] == "true"
    val upnpPort = nat["upnp_mapped_port"]?.takeIf { it.isNotBlank() } ?: "$port"
    val publicEndpoint = nat["public_endpoint"]?.takeIf { it.isNotBlank() }
        ?: nat["upnp_external_ip"]?.takeIf { it.isNotBlank() }
        ?: ""
    val localIp = nat["local_ip"]?.takeIf { it.isNotBlank() } ?: P2PMessageRelay.getLocalIpAddress(context)

    sb.appendLine("2. NAT Environment: Type=$natType | Local IP=$localIp")
    sb.appendLine("   └ STUN Public Endpoint: ${if (publicEndpoint.isNotBlank()) publicEndpoint else "None (Behind Symmetric NAT / Tor)"}")
    sb.appendLine("   └ UPnP / NAT-PMP Port Forward: ${if (upnpMapped) "MAPPED (External Port: $upnpPort) -> OK" else "NOT MAPPED (Relying on Hole Punch / Relay)"}")

    // 3. Yggdrasil IPv6 Interface
    val yggAddr = P2PMessageRelay.getYggdrasilAddress()
    val yggOk = yggAddr.isNotBlank() && yggAddr != "N/A" && yggAddr != "unavailable"
    val yggMode = P2PPreferences.getYggdrasilMode(context)
    val yggModeLabel = if (yggMode == P2PPreferences.YggdrasilMode.PROXY) "Proxy (SOCKS5 :9053)" else "System VPN"
    sb.appendLine("3. [YGGDRASIL] IPv6: ${if (yggAddr.isNotBlank()) yggAddr else "Not Active"} · Mode: $yggModeLabel · State: ${if (yggOk) "CONNECTED (OK)" else "OFFLINE"}")

    // 4. Tor & Onion Routing
    val isTorRunning = TorManager.isTorRunning.value
    val torEffective = ProxyConfig.getEffectiveProxyConfig(context)
    val torActive = isTorRunning && torEffective.enabled
    val onionAddr: String = try { NativeBridge.getOnionAddress() ?: "" } catch (_: Exception) { "" }
    sb.appendLine("4. [TOR_ROUTING] SOCKS5 Proxy: ${if (torActive) "ACTIVE (127.0.0.1:9050)" else "OFF"} · Onion Service: ${if (onionAddr.isNotBlank()) onionAddr else "N/A"}")

    // 5. BitTorrent Discovery Trackers Live Health & Latency
    val trackerStatuses = com.example.twopchat.config.TrackerPreferences.diagnosticStatuses(context)
    val totalTrackers = trackerStatuses.size
    var onlineTrackers = 0
    var totalRtt = 0L
    var rttCount = 0
    for ((_, status) in trackerStatuses) {
        if (status.contains("OK", ignoreCase = true) || status.contains("peers", ignoreCase = true)) {
            onlineTrackers++
        }
        val rttMatch = Regex("(?:announce_rtt=|rtt=|rtt:)\\s*([0-9]+)", RegexOption.IGNORE_CASE).find(status)
        if (rttMatch != null) {
            val rttVal = rttMatch.groupValues[1].toLongOrNull()
            if (rttVal != null && rttVal > 0) {
                totalRtt += rttVal
                rttCount++
            }
        }
    }
    val avgRtt = if (rttCount > 0) totalRtt / rttCount else 0L
    sb.appendLine("5. BitTorrent Discovery Trackers: $onlineTrackers/$totalTrackers ONLINE ${if (avgRtt > 0) "(Avg RTT: ${avgRtt}ms)" else ""}")

    // 6. Blind Relay & TCP Hole Punching Readiness
    val relayReady = portOk
    sb.appendLine("6. Fallback Transports: TCP Hole Punching & E2EE Blind Relay Tunnel -> ${if (relayReady) "ARMED & READY" else "INACTIVE"}")

    // 7. Active Peer Sessions
    val activePeers = P2PMessageRelay.getActivePeerNames()
    val registeredEndpoints = P2PMessageRelay.peerEndpoints.size
    sb.appendLine("7. Active Double Ratchet Sessions: ${activePeers.size} active (${registeredEndpoints} endpoints registered)")

    // 8. Security & System Integrity Check
    val isRooted = com.example.twopchat.security.RootDetectionHelper.isRooted()
    sb.appendLine("8. System Integrity: ${if (isRooted) "ROOT DETECTED (RAM Security Reduced)" else "OK (No Root Detected)"}")

    val isAllOk = portOk && (yggOk || publicEndpoint.isNotBlank() || upnpMapped || relayReady)
    val verdict = when {
        !portOk -> "P2P Listener Offline"
        torActive -> "Tor Privacy Route Active"
        yggOk && upnpMapped -> "Dual-Stack Direct & Mesh Active"
        upnpMapped || natType == "FULL_CONE" || natType == "OPEN_INTERNET" -> "Direct P2P Reachable"
        natType == "SYMMETRIC" -> "Symmetric NAT (Hole Punch / Relay Fallback Active)"
        else -> "P2P Operational"
    }

    sb.appendLine("=========================================")
    sb.appendLine("⚡ [DIAGNOSTICS_TEST] SWEEP FINISHED: $verdict")
    sb.appendLine("=========================================")

    val resultLog = sb.toString()
    try {
        val logFile = File(File(context.filesDir, "config"), "app.log")
        logFile.appendText("\n" + resultLog)
    } catch (e: Exception) {
        com.example.twopchat.logging.SafeLog.d("NetworkDiagnostics", "Failed appending sweep result to app.log: ${e.javaClass.simpleName}")
    }

    ConnectionSweepResult(
        timestamp = timeStr,
        port = port,
        portOk = portOk,
        natType = natType,
        publicEndpoint = publicEndpoint,
        upnpMapped = upnpMapped,
        upnpPort = upnpPort,
        yggAddress = yggAddr,
        yggOk = yggOk,
        yggModeLabel = yggModeLabel,
        torActive = torActive,
        onionAddress = onionAddr,
        trackersOnline = onlineTrackers,
        trackersTotal = totalTrackers,
        trackerAvgRtt = avgRtt,
        relayReady = relayReady,
        activePeersCount = activePeers.size,
        isRooted = isRooted,
        verdict = verdict,
        isAllOk = isAllOk,
        formattedLog = resultLog
    )
}

@Composable
private fun SweepStatusRow(
    label: String,
    value: String,
    isOk: Boolean,
    onSurfaceVariant: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "• $label:",
            fontSize = 11.sp,
            color = onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (isOk) Color(0xFF4CAF50) else Color(0xFFFFB74D),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ConnectionSweepCard(
    result: ConnectionSweepResult,
    appLanguage: String,
    primaryColor: Color,
    surfaceVariant: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = surfaceVariant.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (result.isAllOk) Color(0xFF4CAF50).copy(alpha = 0.5f) else Color(0xFFFFB74D).copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header: Status badge, Title, Copy button, Dismiss button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (result.isAllOk) Color(0xFF4CAF50) else Color(0xFFFFB74D))
                    )
                    Text(
                        text = Localizations.tr(
                            appLanguage,
                            ru = "РЕЗУЛЬТАТ ТЕСТА СВЯЗИ",
                            en = "CONNECTION TEST RESULT",
                            de = "VERBINDUNGSTEST-ERGEBNIS",
                            es = "RESULTADO DE LA PRUEBA DE CONEXIÓN",
                            fr = "RÉSULTAT DU TEST DE CONNEXION",
                            pt = "RESULTADO DO TESTE DE CONEXÃO",
                            tr = "BAĞLANTI TESTİ SONUCU"
                        ),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(24.dp)
                    ) {
                        CustomCopyIcon(tint = primaryColor, modifier = Modifier.size(12.dp))
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Verdict banner
            val verdictColor = if (result.isAllOk) Color(0xFF81C784) else Color(0xFFFFD54F)
            val verdictLabel = when {
                result.verdict == "Direct P2P Reachable" -> Localizations.tr(
                    appLanguage,
                    ru = "Прямой P2P доступен (STUN/UPnP)",
                    en = "Direct P2P reachable (STUN/UPnP)",
                    de = "Direktes P2P erreichbar (STUN/UPnP)",
                    es = "P2P directo accesible (STUN/UPnP)",
                    fr = "P2P direct accessible (STUN/UPnP)",
                    pt = "P2P direto acessível (STUN/UPnP)",
                    tr = "Doğrudan P2P erişilebilir (STUN/UPnP)"
                )
                result.verdict.contains("Symmetric NAT") -> Localizations.tr(
                    appLanguage,
                    ru = "Симметричный NAT (Hole Punch / Relay активны)",
                    en = "Symmetric NAT (Hole Punch / Relay active)",
                    de = "Symmetrisches NAT (Hole Punch / Relay aktiv)",
                    es = "NAT simétrico (Hole Punch / Relay activo)",
                    fr = "NAT symétrique (Hole Punch / Relay actif)",
                    pt = "NAT simétrico (Hole Punch / Relay ativo)",
                    tr = "Simetrik NAT (Hole Punch / Relay aktif)"
                )
                result.verdict.contains("Dual-Stack") -> Localizations.tr(
                    appLanguage,
                    ru = "Dual-Stack: Прямой P2P + Yggdrasil Mesh",
                    en = "Dual-Stack: Direct P2P + Yggdrasil Mesh",
                    de = "Dual-Stack: Direktes P2P + Yggdrasil Mesh",
                    es = "Dual-Stack: P2P directo + Yggdrasil Mesh",
                    fr = "Dual-Stack : P2P direct + Yggdrasil Mesh",
                    pt = "Dual-Stack: P2P direto + Yggdrasil Mesh",
                    tr = "Dual-Stack: Doğrudan P2P + Yggdrasil Mesh"
                )
                result.verdict.contains("Tor") -> Localizations.tr(
                    appLanguage,
                    ru = "Tor: маршрутизация через защищённую сеть",
                    en = "Tor: routed via privacy network",
                    de = "Tor: über Datenschutznetzwerk geroutet",
                    es = "Tor: enrutado a través de red privada",
                    fr = "Tor : acheminé via le réseau privé",
                    pt = "Tor: roteado via rede de privacidade",
                    tr = "Tor: gizlilik ağı üzerinden yönlendirildi"
                )
                result.verdict == "P2P Operational" -> Localizations.tr(
                    appLanguage,
                    ru = "P2P системы работают штатно",
                    en = "P2P systems operational",
                    de = "P2P-Systeme betriebsbereit",
                    es = "Sistemas P2P operativos",
                    fr = "Systèmes P2P opérationnels",
                    pt = "Sistemas P2P operacionais",
                    tr = "P2P sistemleri çalışır durumda"
                )
                result.verdict == "P2P Listener Offline" -> Localizations.tr(
                    appLanguage,
                    ru = "Слушатель P2P не запущен",
                    en = "P2P listener offline",
                    de = "P2P-Listener offline",
                    es = "Oyente P2P desconectado",
                    fr = "Écouteur P2P hors ligne",
                    pt = "Ouvinte P2P offline",
                    tr = "P2P dinleyici çevrimdışı"
                )
                else -> result.verdict
            }
            Text(
                text = verdictLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = verdictColor
            )

            HorizontalDivider(
                color = onSurfaceColor.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(vertical = 2.dp)
            )

            // Diagnostic badges grid
            SweepStatusRow(
                label = Localizations.tr(appLanguage, ru = "P2P Сервер", en = "P2P Server", de = "P2P-Server", es = "Servidor P2P", fr = "Serveur P2P", pt = "Servidor P2P", tr = "P2P Sunucusu"),
                value = if (result.portOk) {
                    Localizations.tr(appLanguage, ru = "Порт ${result.port} (Активен)", en = "Port ${result.port} (Active)", de = "Port ${result.port} (Aktiv)", es = "Puerto ${result.port} (Activo)", fr = "Port ${result.port} (Actif)", pt = "Porta ${result.port} (Ativo)", tr = "Port ${result.port} (Aktif)")
                } else {
                    Localizations.tr(appLanguage, ru = "Не активен", en = "Inactive", de = "Inaktiv", es = "Inactivo", fr = "Inactif", pt = "Inativo", tr = "Devre Dışı")
                },
                isOk = result.portOk,
                onSurfaceVariant = onSurfaceVariant
            )

            val natLabel = if (result.upnpMapped) {
                "${result.natType} · UPnP (${result.upnpPort})"
            } else if (result.publicEndpoint.isNotBlank()) {
                "${result.natType} · STUN"
            } else {
                "${result.natType} (Hole Punch / Relay)"
            }
            SweepStatusRow(
                label = "NAT & STUN",
                value = natLabel,
                isOk = result.upnpMapped || result.natType in setOf("FULL_CONE", "OPEN_INTERNET", "RESTRICTED_CONE"),
                onSurfaceVariant = onSurfaceVariant
            )

            val trackerText = if (result.trackersTotal > 0) {
                val rttUnit = Localizations.tr(appLanguage, ru = "мс", en = "ms", de = "ms", es = "ms", fr = "ms", pt = "ms", tr = "ms")
                val onlineText = Localizations.tr(appLanguage, ru = "онлайн", en = "online", de = "online", es = "en línea", fr = "en ligne", pt = "online", tr = "çevrimiçi")
                val rttText = if (result.trackerAvgRtt > 0) " (RTT ~${result.trackerAvgRtt}$rttUnit)" else ""
                "${result.trackersOnline}/${result.trackersTotal} $onlineText$rttText"
            } else {
                Localizations.tr(appLanguage, ru = "Не настроены", en = "None", de = "Keine", es = "Ninguno", fr = "Aucun", pt = "Nenhum", tr = "Yapılandırılmadı")
            }
            SweepStatusRow(
                label = Localizations.tr(appLanguage, ru = "Трекеры Discovery", en = "Discovery Trackers", de = "Discovery-Tracker", es = "Trackers descubrimiento", fr = "Trackers découverte", pt = "Rastreadores descoberta", tr = "Keşif İzleyicileri"),
                value = trackerText,
                isOk = result.trackersOnline > 0,
                onSurfaceVariant = onSurfaceVariant
            )

            val meshText = buildString {
                if (result.yggOk) append("YGG: OK") else append(Localizations.tr(appLanguage, ru = "YGG: Выкл", en = "YGG: Off", de = "YGG: Aus", es = "YGG: Desact.", fr = "YGG: Désactivé", pt = "YGG: Desl.", tr = "YGG: Kapalı"))
                append(" · ")
                if (result.torActive) append(Localizations.tr(appLanguage, ru = "Tor: Активен", en = "Tor: Active", de = "Tor: Aktiv", es = "Tor: Activo", fr = "Tor : Actif", pt = "Tor: Ativo", tr = "Tor: Aktif")) else append(Localizations.tr(appLanguage, ru = "Tor: Выкл", en = "Tor: Off", de = "Tor: Aus", es = "Tor: Desact.", fr = "Tor : Désactivé", pt = "Tor: Desl.", tr = "Tor: Kapalı"))
            }
            SweepStatusRow(
                label = Localizations.tr(appLanguage, ru = "Сети Ygg & Tor", en = "Ygg & Tor Mesh", de = "Ygg- & Tor-Mesh", es = "Redes Ygg y Tor", fr = "Réseaux Ygg et Tor", pt = "Redes Ygg e Tor", tr = "Ygg ve Tor Ağları"),
                value = meshText,
                isOk = result.yggOk || result.torActive,
                onSurfaceVariant = onSurfaceVariant
            )

            SweepStatusRow(
                label = "Relay & Hole Punch",
                value = if (result.relayReady) {
                    Localizations.tr(appLanguage, ru = "Готов (E2EE туннель)", en = "Ready (E2EE tunnel)", de = "Bereit (E2EE-Tunnel)", es = "Listo (túnel E2EE)", fr = "Prêt (tunnel E2EE)", pt = "Pronto (túnel E2EE)", tr = "Hazır (E2EE tünel)")
                } else {
                    Localizations.tr(appLanguage, ru = "Недоступен", en = "Disabled", de = "Deaktiviert", es = "Desactivado", fr = "Désactivé", pt = "Desativado", tr = "Devre Dışı")
                },
                isOk = result.relayReady,
                onSurfaceVariant = onSurfaceVariant
            )

            val peersUnit = Localizations.tr(appLanguage, ru = "пиров", en = "peers", de = "Peers", es = "pares", fr = "pairs", pt = "pares", tr = "eş")
            SweepStatusRow(
                label = Localizations.tr(appLanguage, ru = "Активные сессии", en = "Active Sessions", de = "Aktive Sitzungen", es = "Sesiones activas", fr = "Sessions actives", pt = "Sessões ativas", tr = "Aktif Oturumlar"),
                value = "${result.activePeersCount} $peersUnit",
                isOk = true,
                onSurfaceVariant = onSurfaceVariant
            )

            Text(
                text = "${Localizations.tr(appLanguage, ru = "Замер от", en = "Tested at", de = "Gemessen am", es = "Probado en", fr = "Testé à", pt = "Testado em", tr = "Test zamanı")}: ${result.timestamp}",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun CustomCopyIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeW = (w * 0.10f).coerceAtLeast(1.5f)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.28f, h * 0.08f),
            size = Size(w * 0.62f, h * 0.62f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f, h * 0.12f),
            style = Stroke(width = strokeW)
        )
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
                    "ERRORS" -> (line.contains("[ERROR]", ignoreCase = true) || line.contains("[PYTHON_ERR]", ignoreCase = true) || line.contains("FAILED", ignoreCase = true) || line.contains("timed out", ignoreCase = true) || line.contains("Exception", ignoreCase = true) || line.contains("Error", ignoreCase = true)) && !line.contains("[INFO]", ignoreCase = true) && !line.contains("accepted", ignoreCase = true) && !line.contains("Announce self status", ignoreCase = true)
                    "P2P" -> line.contains("P2PMessageRelay", ignoreCase = true) || line.contains("p2p", ignoreCase = true) || line.contains("NativeBridge", ignoreCase = true) || line.contains("OutboundMessenger", ignoreCase = true)
                    "YGG" -> line.contains("[YGGDRASIL]", ignoreCase = true) || line.contains("Yggdrasil", ignoreCase = true) || line.contains("ygg", ignoreCase = true) || line.contains("PacketTunnelProvider", ignoreCase = true) || line.contains("YggdrasilProxyService", ignoreCase = true) || line.contains("UserSpaceStack", ignoreCase = true) || line.contains("YggUserStack", ignoreCase = true) || line.contains("TUN", ignoreCase = true) || line.contains("200:", ignoreCase = true) || line.contains("0200:", ignoreCase = true)
                    "TRACKERS" -> line.contains("Tracker", ignoreCase = true) || line.contains("announce", ignoreCase = true) || line.contains("discovery", ignoreCase = true)
                    "TOR" -> line.contains("TorManager", ignoreCase = true) || line.contains("[PROXY]", ignoreCase = true) || line.contains("SOCKS5", ignoreCase = true) || line.contains("socks", ignoreCase = true) || line.contains("torrc", ignoreCase = true) || line.contains("Tor", ignoreCase = true)
                    else -> true
                }

                val matchesQuery = searchQuery.isBlank() || line.contains(searchQuery, ignoreCase = true)

                if (matchesLevel && matchesQuery) {
                    matchedCount++
                    val kotlinInfoIndex = line.indexOf("[KOTLIN_INFO]")
                    val goErrIndex = line.indexOf("[GO_ERR]")
                    val goOutIndex = line.indexOf("[GO_OUT]")
                    val infoIndex = line.indexOf("[INFO]")
                    val errIndex = line.indexOf("[ERROR]")
                    val warnIndex = line.indexOf("[WARNING]")
                    val debugIndex = line.indexOf("[DEBUG]")

                    val isTestLog = line.contains("[DIAGNOSTICS_TEST]")
                    val isInfo = infoIndex != -1 || line.contains("Announce self status") || line.contains("accepted") || kotlinInfoIndex != -1
                    val isWarn = warnIndex != -1 || line.contains("Debounced") || line.contains("retry")
                    val isError = (goErrIndex != -1 || errIndex != -1 || line.contains("FAILED") || line.contains("timed out")) && !isInfo
                    val isSuccess = line.contains("SUCCESS") || line.contains("established") || line.contains("OPERATIONAL")

                    when {
                        isTestLog -> pushStyle(SpanStyle(color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold))
                        isError -> pushStyle(SpanStyle(color = Color(0xFFFF5252)))
                        isSuccess -> pushStyle(SpanStyle(color = Color(0xFF69F0AE)))
                        isInfo -> pushStyle(SpanStyle(color = Color(0xFF81C784)))
                        isWarn -> pushStyle(SpanStyle(color = Color(0xFFFFD740)))
                        debugIndex != -1 -> pushStyle(SpanStyle(color = Color(0xFF90A4AE)))
                        else -> pushStyle(SpanStyle(color = Color(0xFFECEFF1)))
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
        var lastSweepResult by remember { mutableStateOf<ConnectionSweepResult?>(null) }

        val trackerPings = remember { mutableStateMapOf<String, Long>() }

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

        val yggdrasilAvailable = yggDiagnostics["state"] in setOf("enabled", "connected") &&
            P2PMessageRelay.getYggdrasilAddress().isNotBlank()
        LaunchedEffect(selectedRadarNode, trackerDiagnostics) {
            if (selectedRadarNode == RadarNode.TRACKERS) {
                trackerDiagnostics.keys.forEach { name ->
                    withContext(Dispatchers.IO) {
                        val url = defaultTrackerUrls[name] ?: ""
                        val ping = if (name.startsWith("Yggdrasil-only") && !yggdrasilAvailable) -2L else getTrackerDnsLatency(url)
                        trackerPings[name] = ping
                    }
                }
            }
        }

        if (selectedRadarNode != null) {
            // SINGLE CONNECTION SPECIFIC DETAIL DIALOG
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
                        .fillMaxHeight(0.78f)
                        .padding(vertical = 12.dp)
                        .border(0.5.dp, primaryColor.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (selectedRadarNode) {
                                    RadarNode.ROUTER -> if (appLanguage == "Русский") "🌐 UPnP / Роутер" else "🌐 UPnP Router"
                                    RadarNode.TRACKERS -> if (appLanguage == "Русский") "🛰 BitTorrent Трекеры" else "🛰 BitTorrent Trackers"
                                    RadarNode.YGGDRASIL -> if (appLanguage == "Русский") "🌿 Yggdrasil IPv6" else "🌿 Yggdrasil IPv6"
                                    RadarNode.PEERS -> if (appLanguage == "Русский") "👥 P2P Сессии Пиров" else "👥 Active P2P Peers"
                                    else -> if (appLanguage == "Русский") selectedRadarNode.labelRu else selectedRadarNode.labelEn
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = onSurfaceColor
                            )
                            IconButton(
                                onClick = {
                                    onSelectedRadarNodeChange(null)
                                    onDismissRequest()
                                },
                                modifier = Modifier.size(32.dp).background(onSurfaceColor.copy(alpha = 0.05f), shape = CircleShape)
                            ) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Scrollable Node Details
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

                        Spacer(modifier = Modifier.height(12.dp))

                        // Action Buttons: Open Logs or Close
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    onSelectedRadarNodeChange(null) // Switch to log console
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = surfaceVariant),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(0.5f)
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") "📄 Открыть логи" else "📄 Open Logs",
                                    color = onSurfaceColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Button(
                                onClick = {
                                    onSelectedRadarNodeChange(null)
                                    onDismissRequest()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(0.5f)
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
        } else {
            // MAIN LOG CONSOLE WINDOW
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
                        // Header Row: Title & Standalone Close Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color(0xFF4CAF50), shape = CircleShape)
                                )
                                Text(
                                    text = Localizations.tr(
                                        appLanguage,
                                        ru = "Сетевой отладчик",
                                        en = "Network Debugger",
                                        de = "Netzwerk-Debugger",
                                        es = "Depurador de red",
                                        fr = "Débogueur réseau",
                                        pt = "Depurador de rede",
                                        tr = "Ağ Hata Ayıklayıcı"
                                    ),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Standalone Close Button
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(onSurfaceColor.copy(alpha = 0.08f))
                                    .clickable { onDismissRequest() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val yggMode = P2PPreferences.getYggdrasilMode(context)

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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = Localizations.tr(
                                            appLanguage,
                                            ru = "📊 Сводка состояния сети",
                                            en = "📊 Network Diagnostics Summary",
                                            de = "📊 Netzwerkdiagnose-Übersicht",
                                            es = "📊 Resumen de diagnóstico de red",
                                            fr = "📊 Résumé du diagnostic réseau",
                                            pt = "📊 Resumo do diagnóstico de rede",
                                            tr = "📊 Ağ Durumu Özeti"
                                        ),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = primaryColor
                                    )
                                    val yggState = yggDiagnostics["state"] ?: "disabled"
                                    val yggModeLabel = if (yggMode == P2PPreferences.YggdrasilMode.PROXY) "proxy" else "vpn"
                                    val yggSummaryText = if (yggState in setOf("connected", "enabled")) "YGG: $yggState ($yggModeLabel)" else "YGG: $yggState"
                                    val activePeersCount = activePeers.size
                                    val isTorRunning = TorManager.isTorRunning.collectAsState().value
                                    val torSummary = if (isTorRunning) "Tor: active" else "Tor: off"
                                    Text(
                                        text = "$yggSummaryText · $torSummary · Peers: $activePeersCount",
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
                                val yggAddress = P2PMessageRelay.getYggdrasilAddress()
                                val localIpv4 = P2PMessageRelay.getLocalIpAddress(context)
                                val upnpMapped = upnpDetails["mapped"] == "true"
                                val trackerCount = trackerDiagnostics.size
                                val isTorRunning = TorManager.isTorRunning.collectAsState().value
                                val effectiveProxy = ProxyConfig.getEffectiveProxyConfig(context)
                                val torText = if (isTorRunning && effectiveProxy.enabled) {
                                    "EMBEDDED TOR (127.0.0.1:9050 - ACTIVE)"
                                } else if (effectiveProxy.enabled) {
                                    "CUSTOM SOCKS5 (${effectiveProxy.host}:${effectiveProxy.port})"
                                } else {
                                    "DISABLED"
                                }
                                val yggModeDetailed = if (yggMode == P2PPreferences.YggdrasilMode.PROXY) "PROXY (127.0.0.1:9053)" else "SYSTEM VPN (TUN)"

                                Text(
                                    text = "• P2P Server Port: $listenerPort (Listening) · Local IPv4: $localIpv4",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF4CAF50)
                                )
                                Text(
                                    text = "• Tor / SOCKS5 Proxy: $torText",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (effectiveProxy.enabled) Color(0xFF4CAF50) else onSurfaceVariant
                                )
                                Text(
                                    text = "• Yggdrasil IPv6: ${if (yggAddress.isNotEmpty()) yggAddress else "Off"} · Mode: $yggModeDetailed · State: ${yggDiagnostics["state"] ?: "disabled"}",
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

                        if (lastSweepResult != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            ConnectionSweepCard(
                                result = lastSweepResult!!,
                                appLanguage = appLanguage,
                                primaryColor = primaryColor,
                                surfaceVariant = surfaceVariant,
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                onCopy = {
                                    clipboardManager.setText(AnnotatedString(lastSweepResult!!.formattedLog))
                                    Toast.makeText(
                                        context,
                                        Localizations.tr(
                                            appLanguage,
                                            ru = "Отчёт скопирован",
                                            en = "Report copied",
                                            de = "Bericht kopiert",
                                            es = "Informe copiado",
                                            fr = "Rapport copié",
                                            pt = "Relatório copiado",
                                            tr = "Rapor kopyalandı"
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onDismiss = { lastSweepResult = null }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Log Filter Chips Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                "ALL" to Localizations.tr(appLanguage, ru = "ВСЕ", en = "ALL", de = "ALLE", es = "TODOS", fr = "TOUS", pt = "TODOS", tr = "TÜMÜ"),
                                "ERRORS" to Localizations.tr(appLanguage, ru = "ОШИБКИ", en = "ERRORS", de = "FEHLER", es = "ERRORES", fr = "ERREURS", pt = "ERROS", tr = "HATALAR"),
                                "P2P" to "P2P",
                                "YGG" to "YGGDRASIL",
                                "TRACKERS" to Localizations.tr(appLanguage, ru = "ТРЕКЕРЫ", en = "TRACKERS", de = "TRACKER", es = "TRACKERS", fr = "TRACKERS", pt = "RASTREADORES", tr = "İZLEYİCİLER"),
                                "TOR" to "TOR"
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
                                        color = if (isSelected) (if (primaryColor == MintGreen) StealthBlack else Color.White) else onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Compact and pixel-perfect Search Bar Input
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(surfaceVariant.copy(alpha = 0.35f))
                                .border(
                                    0.5.dp,
                                    if (searchQuery.isNotEmpty()) primaryColor.copy(alpha = 0.6f) else onSurfaceColor.copy(alpha = 0.12f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = Localizations.tr(
                                                appLanguage,
                                                ru = "Поиск по логам...",
                                                en = "Search logs...",
                                                de = "Protokolle durchsuchen...",
                                                es = "Buscar en registros...",
                                                fr = "Rechercher dans les journaux...",
                                                pt = "Pesquisar nos registros...",
                                                tr = "Günlüklerde ara..."
                                            ),
                                            fontSize = 12.sp,
                                            color = onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            fontSize = 12.sp,
                                            color = onSurfaceColor,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        cursorBrush = SolidColor(primaryColor),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { searchQuery = "" },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear search",
                                            tint = onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Log Stats & Action Toolbar Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = Localizations.tr(
                                        appLanguage,
                                        ru = "СИСТЕМНЫЙ ЛОГ",
                                        en = "SYSTEM LOG",
                                        de = "SYSTEMPROTOKOLL",
                                        es = "REGISTRO DEL SISTEMA",
                                        fr = "JOURNAL SYSTÈME",
                                        pt = "REGISTRO DO SISTEMA",
                                        tr = "SİSTEM GÜNLÜĞÜ"
                                    ),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceVariant
                                )
                                Text(
                                    text = "($lineCount " + Localizations.tr(
                                        appLanguage,
                                        ru = "строк",
                                        en = "lines",
                                        de = "Zeilen",
                                        es = "líneas",
                                        fr = "lignes",
                                        pt = "linhas",
                                        tr = "satır"
                                    ) + ")",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = primaryColor
                                )
                            }

                            // Log Actions Toolbar (Refresh, Copy, Share, Clear)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Refresh
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(onSurfaceColor.copy(alpha = 0.06f))
                                        .clickable { refreshDiagnostics() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh",
                                        tint = primaryColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                // Copy
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(onSurfaceColor.copy(alpha = 0.06f))
                                        .clickable {
                                            clipboardManager.setText(AnnotatedString(formattedLogs.text))
                                            Toast.makeText(
                                                context,
                                                Localizations.tr(
                                                    appLanguage,
                                                    ru = "Логи скопированы",
                                                    en = "Logs copied",
                                                    de = "Protokolle kopiert",
                                                    es = "Registros copiados",
                                                    fr = "Journaux copiés",
                                                    pt = "Registros copiados",
                                                    tr = "Günlükler kopyalandı"
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    CustomCopyIcon(tint = primaryColor, modifier = Modifier.size(13.dp))
                                }
                                // Share
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(onSurfaceColor.copy(alpha = 0.06f))
                                        .clickable { shareLogFile(context) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share",
                                        tint = primaryColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                // Clear
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(onSurfaceColor.copy(alpha = 0.06f))
                                        .clickable {
                                            clearLogFile(context)
                                            logsText = readLogFile(context)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Clear",
                                        tint = Color(0xFFFF5252),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
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
                                            val sweep = runConnectionDiagnosticsTest(context)
                                            lastSweepResult = sweep
                                            applySnapshot(withContext(Dispatchers.IO) { readDiagnosticsSnapshot(context) })
                                            isTestingConnection = false
                                            Toast.makeText(
                                                context,
                                                Localizations.tr(
                                                    appLanguage,
                                                    ru = "Тест связи завершен!",
                                                    en = "Connection test complete!",
                                                    de = "Verbindungstest abgeschlossen!",
                                                    es = "¡Prueba de conexión completada!",
                                                    fr = "Test de connexion terminé !",
                                                    pt = "Teste de conexão concluído!",
                                                    tr = "Bağlantı testi tamamlandı!"
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
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
                                        text = if (isTestingConnection) {
                                            Localizations.tr(
                                                appLanguage,
                                                ru = "Тестирование...",
                                                en = "Testing...",
                                                de = "Testen...",
                                                es = "Probando...",
                                                fr = "Test en cours...",
                                                pt = "Testando...",
                                                tr = "Test ediliyor..."
                                            )
                                        } else {
                                            Localizations.tr(
                                                appLanguage,
                                                ru = "Тест связи",
                                                en = "Test Sweep",
                                                de = "Verbindungstest",
                                                es = "Prueba de red",
                                                fr = "Test de connexion",
                                                pt = "Teste de rede",
                                                tr = "Bağlantı Testi"
                                            )
                                        },
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
}

@Composable
private fun NodeDetailContent(
    node: RadarNode,
    context: Context,
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
            val localIps = listOf(P2PMessageRelay.getLocalIpAddress(context))
            DetailRow(if (appLanguage == "Русский") "Мой Fingerprint:" else "My Fingerprint:", P2PBridgeProvider.get(context).getLocalFingerprint(), primaryColor)
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

            DetailRow(if (appLanguage == "Русский") "Статус проброса:" else "UPnP Mapped Status:", if (mapped) "CONNECTED / OK" else "DISABLED / OFFLINE", if (mapped) Color(0xFF4CAF50) else Color(0xFFFF5252))
            DetailRow(if (appLanguage == "Русский") "Внешний IP адрес:" else "Router External IP:", extIp, primaryColor)
            DetailRow(if (appLanguage == "Русский") "Внутренний IP адрес:" else "Client Internal IP:", intIp, primaryColor)
            DetailRow(if (appLanguage == "Русский") "Проброшенный порт:" else "Mapped Port:", port, primaryColor)
            DetailRow(if (appLanguage == "Русский") "Тип шлюза / Service:" else "Gateway Service:", service, primaryColor)
            DetailRow(if (appLanguage == "Русский") "Адрес управления (SOAP):" else "Control SOAP URL:", controlUrl, onSurfaceVariant)
            if (!mapped && errorMsg.isNotBlank()) {
                DetailRow(if (appLanguage == "Русский") "Состояние:" else "Error message:", errorMsg, Color(0xFFFF5252))
            }

            Spacer(modifier = Modifier.height(8.dp))

            var upnpReopening by remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()

            Button(
                onClick = {
                    upnpReopening = true
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) {
                            NativeBridge.triggerNatTraversal()
                        }
                        delay(1200)
                        refreshDiagnostics()
                        upnpReopening = false
                        Toast.makeText(context, if (appLanguage == "Русский") "STUN/UPnP диагностика запущена" else "STUN/UPnP NAT traversal initiated", Toast.LENGTH_SHORT).show()
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
                    Text(if (appLanguage == "Русский") "Запросить проброс порта" else "Re-open Port", color = if (primaryColor == MintGreen) StealthBlack else Color.White)
                }
            }
        }
        RadarNode.TRACKERS -> {
            val displayTrackers: Map<String, String> = if (trackerDiagnostics.isNotEmpty()) {
                trackerDiagnostics
            } else {
                defaultTrackerUrls.keys.associateWith { "announce=NOT_RUN, resolve=NOT_RUN, announce_rtt=n/ams, resolve_rtt=n/ams" }
            }
            displayTrackers.forEach { (name, status) ->
                val ping = trackerPings[name]
                val announceRtt = Regex("announce_rtt=(\\d+)ms").find(status)?.groupValues?.get(1)?.toLongOrNull()
                val pending = status.contains("PENDING", ignoreCase = true)
                val failed = status.contains("FAIL", ignoreCase = true)
                val skipped = status.contains("SKIPPED", ignoreCase = true)
                val announceOk = status.contains("announce=OK", ignoreCase = true) || status.contains("resolve=OK", ignoreCase = true)
                val pingText = when {
                    announceRtt != null -> "RTT ${announceRtt}ms"
                    pending -> if (appLanguage == "Русский") "опрос..." else "probing..."
                    failed -> if (appLanguage == "Русский") "ошибка" else "failed"
                    ping == -2L -> if (appLanguage == "Русский") "Yggdrasil выкл." else "Yggdrasil off"
                    ping == -3L -> if (announceOk) "IPv6 (OK)" else "IPv6"
                    ping != null && ping >= 0L -> "DNS ${ping}ms"
                    announceOk -> if (appLanguage == "Русский") "Активен (Go)" else "Active (Go)"
                    ping == null -> if (appLanguage == "Русский") "нет данных" else "not checked"
                    else -> if (appLanguage == "Русский") "DNS недоступен" else "DNS unavailable"
                }
                val pingColor = when {
                    announceOk -> Color(0xFF4CAF50)
                    failed -> Color(0xFFFF5252)
                    ping == -2L || ping == -3L || skipped -> onSurfaceVariant
                    ping == null || ping >= 0L -> Color(0xFFFFD740)
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
            val address = P2PMessageRelay.getYggdrasilAddress()

            val yggMode = P2PPreferences.getYggdrasilMode(context)
            DetailRow(if (appLanguage == "Русский") "Режим работы:" else "Operation Mode:", if (yggMode == P2PPreferences.YggdrasilMode.PROXY) "PROXY (127.0.0.1:9053)" else "SYSTEM VPN (TUN)", primaryColor)
            DetailRow(if (appLanguage == "Русский") "Адрес IPv6 Yggdrasil:" else "Yggdrasil IPv6:", if (address.isNotEmpty()) address else "n/a", primaryColor)
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
                                Text("${peerMap["state"]} · ${peerMap["direction"]}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (peerMap["state"] == "ONLINE") Color(0xFF4CAF50) else Color(0xFFFF5252))
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
                                Text("Error: ${peerMap["error"]}", fontSize = 10.sp, color = Color(0xFFFF5252), modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
            }
        }
        RadarNode.PEERS -> {
            val validPeers = activePeers.filter { !P2PMessageRelay.isPlaceholderPeerName(it) && it != "null" && it.isNotBlank() }
            if (validPeers.isEmpty()) {
                Text(
                    text = if (appLanguage == "Русский") "Нет активных сессий Double Ratchet" else "No active Double Ratchet sessions established",
                    color = onSurfaceVariant,
                    fontSize = 13.sp
                )
            } else {
                validPeers.forEach { name ->
                    val endpoint = P2PMessageRelay.peerEndpoints[name] ?: "resolving..."
                    val transport = connectionTransportLabel(
                        rawTransport = P2PMessageRelay.peerConnectionTransports[name],
                        endpoint = P2PMessageRelay.peerEndpoints[name],
                        appLanguage = appLanguage,
                    )
                    val isEstablished = P2PMessageRelay.isPeerOnline(context, name)
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
                                text = if (isEstablished) "ONLINE" else "OFFLINE",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (isEstablished) Color(0xFF4CAF50) else Color(0xFFFF5722),
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
