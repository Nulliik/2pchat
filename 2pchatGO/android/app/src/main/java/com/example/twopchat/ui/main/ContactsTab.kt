
package com.example.twopchat.ui.main

import android.widget.Toast
import android.content.Intent
import com.example.twopchat.config.*
import android.net.VpnService
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.GroupConversation
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.twopchat.copyTextToClipboard
import com.example.twopchat.readTextFromClipboard
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.example.twopchat.NativeBridge
import com.example.twopchat.bridge.P2PBridgeProvider
import com.example.twopchat.Chat
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.tor.*
import com.example.twopchat.theme.*
import com.example.twopchat.data.Localizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close

internal data class PeerSearchAddress(
    val nickname: String,
    val discoveryCode: String,
)

internal data class PeerSearchRequest(
    val lookupNickname: String,
    val sharedCode: String,
    val expectedLiveName: String,
    val expectedFingerprint: String?,
)

internal fun invitePeerSearchRequest(
    name: String?,
    code: String?,
    fingerprint: String?,
    onion: String? = null,
    ip: String? = null,
): PeerSearchRequest? {
    val hasExplicitEndpoint = !onion.isNullOrBlank() || !ip.isNullOrBlank() || !fingerprint.isNullOrBlank()
    val rawName = name?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
        ?: if (hasExplicitEndpoint) {
            onion?.trim()?.takeIf { it.isNotBlank() }?.substringBefore(".")?.take(8)
                ?: ip?.trim()?.takeIf { it.isNotBlank() }?.take(12)
                ?: "Peer"
        } else null
    val nickname = rawName?.let(::validatedSearchNickname) ?: rawName ?: return null
    val trimmedCode = code?.trim().takeIf { !it.isNullOrEmpty() }
    val sharedCode = trimmedCode
        ?: fingerprint?.trim()?.takeIf { it.isNotEmpty() }?.take(16)
        ?: onion?.trim()?.takeIf { it.isNotEmpty() }?.substringBefore(".")?.take(16)
        ?: ip?.trim()?.takeIf { it.isNotEmpty() }?.take(16)
        ?: return null
    return PeerSearchRequest(
        lookupNickname = nickname,
        sharedCode = sharedCode,
        expectedLiveName = nickname,
        expectedFingerprint = fingerprint?.trim()?.takeIf(String::isNotEmpty),
    )
}

internal fun formatInviteEndpoint(value: String?, defaultPort: Int = 50001): String? {
    if (value.isNullOrBlank()) return null
    val raw = value.trim()
    if (Regex("^[a-z0-9]{16,56}\\.onion:\\d{1,5}$", RegexOption.IGNORE_CASE).matches(raw)) return raw
    if (Regex("^[a-z0-9]{16,56}\\.onion$", RegexOption.IGNORE_CASE).matches(raw)) return "$raw:$defaultPort"
    if (Regex("^\\[[0-9A-Fa-f:]+\\]:\\d{1,5}$").matches(raw)) return raw
    if (Regex("^[0-9.]+:\\d{1,5}$").matches(raw)) return raw
    if (Regex("^[0-9.]+$").matches(raw)) return "$raw:$defaultPort"
    if (Regex("^[0-9A-Fa-f:]+$").matches(raw) && raw.contains(':')) return "[$raw]:$defaultPort"
    return null
}

internal fun buildContactQrPayload(
    nickname: String,
    discoveryCode: String,
    fingerprint: String,
    localIpv4: String,
    publicIpv4: String,
    ipv6: String,
    onion: String = "",
    listenerPort: Int,
): String {
    val builder = StringBuilder("2pchat://connect?")
        .append("name=").append(android.net.Uri.encode(nickname))
        .append("&code=").append(android.net.Uri.encode(discoveryCode))
    if (fingerprint.isNotBlank() && fingerprint !in setOf("Loading...", "Not Initialized", "Error")) {
        builder.append("&fp=").append(android.net.Uri.encode(fingerprint))
    }
    formatInviteEndpoint(localIpv4, listenerPort)?.let {
        builder.append("&ip=").append(android.net.Uri.encode(it))
    }
    formatInviteEndpoint(publicIpv4, listenerPort)
        ?.takeIf { publicIpv4 != localIpv4 }
        ?.let { builder.append("&public_ip=").append(android.net.Uri.encode(it)) }
    formatInviteEndpoint(ipv6, listenerPort)?.let {
        builder.append("&ygg=").append(android.net.Uri.encode(it))
    }
    formatInviteEndpoint(onion, listenerPort)?.let {
        builder.append("&onion=").append(android.net.Uri.encode(it))
    }
    return builder.toString()
}

internal fun isConnectablePeerSearchResult(
    peer: Map<String, Any>,
    expectedFingerprint: String?,
): Boolean {
    val verified = peer["verified"]?.toString()?.equals("true", ignoreCase = true) == true
    val ownershipVerified = peer["ownership_verified"]?.toString()
        ?.equals("true", ignoreCase = true) == true
    return verified && (expectedFingerprint == null || ownershipVerified)
}

internal fun parsePeerSearchAddress(value: String): PeerSearchAddress? {
    val trimmed = value.trim().removePrefix("@")
    val separator = trimmed.lastIndexOf('#')
    if (separator <= 0 || separator == trimmed.lastIndex) return null

    val nickname = validatedSearchNickname(trimmed.substring(0, separator)) ?: return null
    val discoveryCode = trimmed.substring(separator + 1).trim()
    if (nickname.isEmpty() || discoveryCode.isEmpty()) return null
    return PeerSearchAddress(nickname, discoveryCode)
}

internal fun classicPeerSearchRequest(value: String): PeerSearchRequest? {
    val address = parsePeerSearchAddress(value) ?: return null
    return PeerSearchRequest(
        lookupNickname = address.nickname,
        sharedCode = address.discoveryCode,
        expectedLiveName = address.nickname,
        expectedFingerprint = null,
    )
}

internal data class DirectOnionTarget(
    val nickname: String,
    val onionEndpoint: String,
)

internal fun isDirectOnionAddress(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.contains(".onion", ignoreCase = true)
}

internal fun parseDirectOnionAddress(value: String, defaultPort: Int = 50001): DirectOnionTarget? {
    val trimmed = value.trim()
    if (!trimmed.contains(".onion", ignoreCase = true)) return null

    if (trimmed.startsWith("2pchat://connect", ignoreCase = true)) {
        val query = trimmed.substringAfter("?", "")
        val queryParams = query.split("&").associate {
            val idx = it.indexOf('=')
            if (idx > 0) it.substring(0, idx) to it.substring(idx + 1) else it to ""
        }
        val rawOnion = queryParams["onion"]?.trim() ?: return null
        val onion = try { java.net.URLDecoder.decode(rawOnion, "UTF-8") } catch (_: Exception) { rawOnion }
        val formatted = formatInviteEndpoint(onion, defaultPort) ?: return null
        val rawName = queryParams["name"]?.trim()?.let {
            try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
        }.orEmpty()
        val name = rawName.ifEmpty { "Peer (${onion.take(8)}...)" }
        return DirectOnionTarget(name, formatted)
    }

    val parts = if (trimmed.contains("#")) {
        listOf(trimmed.substringBeforeLast("#").trim(), trimmed.substringAfterLast("#").trim())
    } else if (trimmed.contains(" ") && trimmed.split(Regex("\\s+")).size == 2) {
        trimmed.split(Regex("\\s+"))
    } else {
        listOf("", trimmed)
    }

    val possibleOnion = parts.last()
    val formattedOnion = formatInviteEndpoint(possibleOnion, defaultPort) ?: return null
    val name = parts.first().takeIf { it.isNotBlank() } ?: "Tor Peer (${formattedOnion.take(8)}...)"
    return DirectOnionTarget(name, formattedOnion)
}

internal data class DirectIPTarget(
    val nickname: String,
    val endpoint: String,
)

internal fun isDirectIPAddress(value: String): Boolean {
    val trimmed = value.trim().removePrefix("@")
    if (isDirectOnionAddress(trimmed) || isContactInviteLink(trimmed)) return false
    val separator = trimmed.lastIndexOf('#')
    val possibleEndpoint = if (separator > 0) trimmed.substring(separator + 1).trim() else trimmed
    return formatInviteEndpoint(possibleEndpoint) != null
}

internal fun parseDirectIPAddress(value: String, defaultPort: Int = 50001): DirectIPTarget? {
    val trimmed = value.trim().removePrefix("@")
    if (isDirectOnionAddress(trimmed) || isContactInviteLink(trimmed)) return null
    val separator = trimmed.lastIndexOf('#')
    val possibleEndpoint = if (separator > 0) trimmed.substring(separator + 1).trim() else trimmed
    val rawName = if (separator > 0) trimmed.substring(0, separator).trim() else ""
    val formatted = formatInviteEndpoint(possibleEndpoint, defaultPort) ?: return null
    val name = rawName.ifEmpty { "Peer (${formatted})" }
    return DirectIPTarget(name, formatted)
}

internal fun isContactInviteLink(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.startsWith("2pchat://connect", ignoreCase = true) ||
           trimmed.startsWith("2pchat:connect", ignoreCase = true) ||
           trimmed.startsWith("connect?", ignoreCase = true) ||
           (trimmed.startsWith("?") && (trimmed.contains("name=") || trimmed.contains("code=") || trimmed.contains("token=") || trimmed.contains("onion=") || trimmed.contains("ip="))) ||
           (trimmed.contains("name=") && (trimmed.contains("code=") || trimmed.contains("token=") || trimmed.contains("onion=") || trimmed.contains("ip=")))
}

internal fun contactFromPeerSearchResult(
    peer: Map<String, Any>,
    appLanguage: String,
): ContactItem {
    val name = peer["nickname"]?.toString()?.trim().orEmpty().ifEmpty { "Unknown" }
    val fingerprint = peer["fingerprint"]?.toString().orEmpty()
    val endpoints = (peer["endpoints"] as? List<*>)
        .orEmpty()
        .joinToString(",") { it.toString() }
        .ifEmpty { "Unknown" }
    val verified = peer["verified"]?.toString()?.equals("true", ignoreCase = true) == true
    val ownershipVerified = peer["ownership_verified"]?.toString()
        ?.equals("true", ignoreCase = true) == true
    val reason = peer["verification_reason"]?.toString().orEmpty()
    val displayName = if (name.startsWith("2TFcRb7m") || name.length > 20) {
        "Peer (${name.take(8)}...)"
    } else {
        name
    }

    return ContactItem(
        name = displayName,
        status = if (verified && ownershipVerified) {
            Localizations.tr(
                appLanguage,
                "Подтверждён ссылкой приглашения",
                "Verified by invite link",
                "Durch Einladungslink verifiziert",
                "Verificado por enlace de invitación",
                "Vérifié par lien d'invitation",
                "Verificado por link de convite"
            )
        } else if (verified) {
            Localizations.tr(
                appLanguage,
                "Узел и ключ активны · владелец ника не подтверждён",
                "Live node and key · nickname ownership unverified",
                "Aktiver Knoten & Schlüssel · Nickname-Eigentum nicht verifiziert",
                "Nodo y clave activos · propiedad de apodo no verificada",
                "Nœud et clé actifs · propriété du pseudo non vérifiée",
                "Nó e chave ativos · propriedade do apelido não verificada"
            )
        } else {
            Localizations.tr(
                appLanguage,
                "Найден в сети · Нажмите для подключения",
                "Found on network · Tap to connect",
                "Im Netzwerk gefunden · Tippen zum Verbinden",
                "Encontrado en red · Tocar para conectar",
                "Trouvé sur le réseau · Appuyez pour vous connecter",
                "Encontrado na rede · Toque para conectar"
            )
        },
        initials = displayName.take(2).uppercase(),
        verified = verified,
        endpoints = endpoints,
        verificationDetails = reason,
        fingerprint = fingerprint,
        ownershipVerified = ownershipVerified,
    )
}


@Composable
fun ContactsTab(
    onItemClick: (NavKey) -> Unit,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableStateOf("") }
    var searchSummary by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    var inviteLinkState by remember { mutableStateOf("") }
    var showInvitePanel by remember { mutableStateOf(false) }
    var showQrPanel by remember { mutableStateOf(false) }
    var qrPublicIpv4 by remember { mutableStateOf("") }
    var showCameraScannerDialog by remember { mutableStateOf(false) }
    var isResolvingInvite by remember { mutableStateOf(false) }
    var resolveInviteStatus by remember { mutableStateOf("") }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showCameraScannerDialog = true
        } else {
            Toast.makeText(
                context,
                if (appLanguage == "Русский") "Разрешение на камеру необходимо для сканирования QR" else "Camera permission required for QR scanning",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    val sharedPrefs = remember { com.example.twopchat.config.P2PPreferences.prefs(context) }
    val rawUsername = remember { sharedPrefs.getString("username_profile", "User Identity") ?: "User Identity" }
    val username = remember(rawUsername) { canonicalNickname(rawUsername) }
    val discoveryCode = remember { P2PPreferences.getRendezvousCode(context) }
    val contactAddress = remember(username, discoveryCode) { "$username#$discoveryCode" }
    var fingerprint by remember { mutableStateOf("Loading...") }
    LaunchedEffect(Unit) {
        if (!NativeBridge.isLoaded) {
            NativeBridge.initialize()
        }
        fingerprint = withContext(Dispatchers.IO) { P2PBridgeProvider.get(context).getLocalFingerprint() }
    }
    LaunchedEffect(showQrPanel) {
        if (!showQrPanel) return@LaunchedEffect
        qrPublicIpv4 = withContext(Dispatchers.IO) {
            val localIpv4 = P2PMessageRelay.getLocalIpAddress(context)
            localIpv4.takeUnless { it == "127.0.0.1" }.orEmpty()
        }
    }

    // Search results must come from an authenticated live peer.  The former
    // hard-coded demo directory made fictional users look searchable/online.
    val filteredContacts = emptyList<ContactItem>()

    val updateSearchQuery = { value: String ->
        if (value != searchQuery) {
            searchGeneration++
            searchJob?.cancel()
            isSearching = false
            isResolvingInvite = false
            searchProgress = ""
            searchSummary = ""
            searchResults = emptyList()
        }
        searchQuery = value
    }

    // Reusable search execution lambda (used by search button & QR scanner)
    val performSearch = { query: String ->
        if (query.isNotBlank()) {
            val generation = ++searchGeneration
            searchJob?.cancel()
            val trimmed = query.trim().replace("\\&", "&")
            val decodedLink = if (trimmed.contains("%26") || trimmed.contains("%3D")) {
                try { java.net.URLDecoder.decode(trimmed, "UTF-8") } catch (_: Exception) { trimmed }
            } else trimmed
            if (isContactInviteLink(decodedLink)) {
                try {
                    val normalizedLink = if (!decodedLink.contains("://")) {
                        "2pchat://connect?" + (if (decodedLink.startsWith("?")) decodedLink.substring(1) else decodedLink.substringAfter("?", decodedLink))
                    } else decodedLink
                    val uri = android.net.Uri.parse(normalizedLink)
                    val parsedName = uri.getQueryParameter("name")
                    val token = uri.getQueryParameter("token") ?: uri.getQueryParameter("code")
                    val expectedFp = (uri.getQueryParameter("fp")?.trim().orEmpty()).replace(" ", "+")
                    val directIp = uri.getQueryParameter("ip")
                    val publicIp = uri.getQueryParameter("public_ip")
                    val yggIp = uri.getQueryParameter("ygg")
                    val onionIp = uri.getQueryParameter("onion")
                    val requestedGroupId = uri.getQueryParameter("group")
                    val groupInviteToken = uri.getQueryParameter("group_token")
                    val request = invitePeerSearchRequest(parsedName, token, expectedFp, onionIp, directIp ?: publicIp ?: yggIp)

                    if (request == null) {
                        resolveInviteStatus = if (appLanguage == "Русский") {
                            "Некорректная ссылка/QR: отсутствует код подключения"
                        } else {
                            "Invalid link/QR: missing connection code"
                        }
                    } else {
                        listOf(directIp, publicIp, yggIp, onionIp)
                            .mapNotNull(::formatInviteEndpoint)
                            .distinct()
                            .forEach { endpoint ->
                            com.example.twopchat.relay.P2PMessageRelay.injectLocalDiscoveryCandidate(
                                request.expectedLiveName, request.expectedFingerprint.orEmpty(), endpoint,
                            )
                        }
                        isResolvingInvite = true
                        resolveInviteStatus = if (appLanguage == "Русский") "Мгновенное подключение к собеседнику..." else "Connecting to peer..."
                        searchJob = coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val bridge = P2PBridgeProvider.get(context)
                                val peers = withTimeout(30_000L) {
                                    bridge.searchPeers(
                                        query = request.lookupNickname,
                                        expectedLiveName = request.expectedLiveName,
                                        expectedFingerprint = request.expectedFingerprint,
                                        sharedCode = request.sharedCode,
                                    )
                                }
                                val directEndpoints = listOf(directIp, publicIp, yggIp, onionIp)
                                    .mapNotNull(::formatInviteEndpoint)
                                    .distinct()
                                val resolvedPeer = peers.firstOrNull {
                                    isConnectablePeerSearchResult(it, request.expectedFingerprint)
                                } ?: peers.firstOrNull()
                                val endpoints = resolvedPeer?.get("endpoints") as? List<*>
                                val endpointStr = if (endpoints != null && endpoints.isNotEmpty()) {
                                    endpoints.joinToString(",") { it.toString() }
                                } else if (directEndpoints.isNotEmpty()) {
                                    directEndpoints.joinToString(",")
                                } else {
                                    request.expectedLiveName
                                }
                                withContext(Dispatchers.Main) {
                                    if (generation != searchGeneration) return@withContext
                                if (endpointStr.isNotEmpty() || request.expectedLiveName.isNotBlank()) {
                                    val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                                    if (!activeSet.contains(request.expectedLiveName)) {
                                        sharedPrefs.edit()
                                            .putStringSet("active_chats", activeSet + request.expectedLiveName)
                                            .putString("transport_${request.expectedLiveName}", if (endpointStr.contains(".onion")) "Tor Onion" else "DIRECT P2P")
                                            .putString("peer_fingerprint_${request.expectedLiveName}", request.expectedFingerprint.orEmpty())
                                            .putString("discovery_code_${request.expectedLiveName}", request.sharedCode)
                                            .apply()
                                    }
                                    if (endpointStr.isNotBlank() && endpointStr != request.expectedLiveName) {
                                        sharedPrefs.edit().putString("last_endpoint_${request.expectedLiveName}", endpointStr).apply()
                                        if (endpointStr.contains(".onion")) {
                                            P2PPreferences.setPeerOnionAddress(context, request.expectedLiveName, endpointStr)
                                            ChatDatabaseHelper.getInstance(context).savePeerOnionAddress(
                                                peerName = request.expectedLiveName,
                                                onionAddress = endpointStr,
                                                fingerprint = request.expectedFingerprint?.takeIf { it.isNotBlank() },
                                                endpoint = endpointStr,
                                            )
                                        }
                                        if (!request.expectedFingerprint.isNullOrBlank()) {
                                            P2PBridgeProvider.get(context).updatePeerNameMapping(request.expectedFingerprint, request.expectedLiveName)
                                        }
                                        com.example.twopchat.relay.P2PMessageRelay.rememberAuthenticatedPeerEndpoint(request.expectedLiveName, endpointStr)
                                    }
                                    com.example.twopchat.relay.P2PMessageRelay.triggerImmediateReconnect(context)
                                    if (!requestedGroupId.isNullOrBlank() && !groupInviteToken.isNullOrBlank()) {
                                        com.example.twopchat.group.runtime.GroupChatCoordinator.requestJoinFromInvite(
                                            requestedGroupId,
                                            groupInviteToken,
                                            request.expectedLiveName,
                                        )
                                    }
                                    resolveInviteStatus = ""
                                    onItemClick(Chat(request.expectedLiveName))
                                } else {
                                    resolveInviteStatus = if (appLanguage == "Русский") "Собеседник не найден. Попробуйте снова." else "Peer not found. Please try again."
                                }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                withContext(Dispatchers.Main) {
                                    if (generation == searchGeneration) {
                                        resolveInviteStatus = if (appLanguage == "Русский") {
                                            "Не удалось завершить поиск. Проверьте сеть и повторите попытку."
                                        } else {
                                            "Search could not be completed. Check the network and try again."
                                        }
                                    }
                                }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    if (generation == searchGeneration) isResolvingInvite = false
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Invalid link/QR", Toast.LENGTH_SHORT).show()
                }
            } else if (isDirectOnionAddress(trimmed)) {
                val directOnion = parseDirectOnionAddress(trimmed, P2PMessageRelay.listenerPort(context))
                if (directOnion != null) {
                    if (!P2PPreferences.isTorEnabled(context) && !TorManager.isTorRunning.value) {
                        Toast.makeText(
                            context,
                            if (appLanguage == "Русский") "Для связи по .onion включите Tor в Настройках" else "Enable Tor in Settings to connect via .onion",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    val existingPeerName = P2PPreferences.findPeerNameByEndpoint(context, directOnion.onionEndpoint)
                    val effectiveName = existingPeerName ?: directOnion.nickname

                    isResolvingInvite = true
                    resolveInviteStatus = if (appLanguage == "Русский") "Подключение к скрытому сервису Tor..." else "Connecting to Tor hidden service..."
                    com.example.twopchat.relay.P2PMessageRelay.injectLocalDiscoveryCandidate(
                        effectiveName, "", directOnion.onionEndpoint,
                    )
                    val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                    if (!activeSet.contains(effectiveName)) {
                        sharedPrefs.edit()
                            .putStringSet("active_chats", activeSet + effectiveName)
                            .putString("transport_${effectiveName}", "Tor Onion")
                            .putString("last_endpoint_${effectiveName}", directOnion.onionEndpoint)
                            .apply()
                    } else {
                        sharedPrefs.edit().putString("last_endpoint_${effectiveName}", directOnion.onionEndpoint).apply()
                    }
                    P2PPreferences.setPeerOnionAddress(context, effectiveName, directOnion.onionEndpoint)
                    ChatDatabaseHelper.getInstance(context).savePeerOnionAddress(
                        peerName = effectiveName,
                        onionAddress = directOnion.onionEndpoint,
                        fingerprint = null,
                        endpoint = directOnion.onionEndpoint,
                    )
                    com.example.twopchat.relay.P2PMessageRelay.rememberAuthenticatedPeerEndpoint(effectiveName, directOnion.onionEndpoint)
                    com.example.twopchat.relay.P2PMessageRelay.triggerImmediateReconnect(context)
                    resolveInviteStatus = ""
                    isResolvingInvite = false
                    onItemClick(Chat(effectiveName))
                } else {
                    searchSummary = if (appLanguage == "Русский") {
                        "Некорректный Tor .onion адрес."
                    } else {
                        "Invalid Tor .onion address."
                    }
                    searchResults = emptyList()
                }
            } else if (isDirectIPAddress(trimmed)) {
                val directIP = parseDirectIPAddress(trimmed, P2PMessageRelay.listenerPort(context))
                if (directIP != null) {
                    val existingPeerName = P2PPreferences.findPeerNameByEndpoint(context, directIP.endpoint)
                    val effectiveName = existingPeerName ?: directIP.nickname

                    isResolvingInvite = true
                    resolveInviteStatus = if (appLanguage == "Русский") "Подключение к прямому P2P адресу..." else "Connecting to direct P2P endpoint..."
                    com.example.twopchat.relay.P2PMessageRelay.injectLocalDiscoveryCandidate(
                        effectiveName, "", directIP.endpoint,
                    )
                    val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                    if (!activeSet.contains(effectiveName)) {
                        sharedPrefs.edit()
                            .putStringSet("active_chats", activeSet + effectiveName)
                            .putString("transport_${effectiveName}", "DIRECT P2P")
                            .putString("last_endpoint_${effectiveName}", directIP.endpoint)
                            .apply()
                    } else {
                        sharedPrefs.edit().putString("last_endpoint_${effectiveName}", directIP.endpoint).apply()
                    }
                    com.example.twopchat.relay.P2PMessageRelay.rememberAuthenticatedPeerEndpoint(effectiveName, directIP.endpoint)
                    com.example.twopchat.relay.P2PMessageRelay.triggerImmediateReconnect(context)
                    resolveInviteStatus = ""
                    isResolvingInvite = false
                    onItemClick(Chat(effectiveName))
                } else {
                    searchSummary = if (appLanguage == "Русский") {
                        "Некорректный P2P адрес."
                    } else {
                        "Invalid P2P address."
                    }
                    searchResults = emptyList()
                }
            } else {
                val request = classicPeerSearchRequest(trimmed)
                if (request == null) {
                    searchSummary = if (appLanguage == "Русский") {
                        "Введите адрес (Имя#код), Tor .onion адрес или ссылку."
                    } else {
                        "Enter address (Name#code), Tor .onion address or link."
                    }
                    searchResults = emptyList()
                } else {
                    isSearching = true
                    searchResults = emptyList()
                    searchSummary = ""
                    searchProgress = if (appLanguage == "Русский") {
                        "1/3 · Запрашиваем трекеры и Mainline DHT…"
                    } else {
                        "1/3 · Querying trackers and Mainline DHT…"
                    }
                    searchJob = coroutineScope.launch(Dispatchers.IO) {
                        val progressJob = launch {
                            kotlinx.coroutines.delay(1200)
                            withContext(Dispatchers.Main) {
                                searchProgress = if (appLanguage == "Русский") {
                                    "2/3 · Собираем IPv4 и Yggdrasil endpoint-ы…"
                                } else {
                                    "2/3 · Collecting IPv4 and Yggdrasil endpoints…"
                                }
                            }
                            kotlinx.coroutines.delay(1800)
                            withContext(Dispatchers.Main) {
                                searchProgress = if (appLanguage == "Русский") {
                                    "3/3 · Проверяем живой узел и криптографическую идентичность…"
                                } else {
                                    "3/3 · Verifying live node and cryptographic identity…"
                                }
                            }
                        }

                        try {
                            val bridge = P2PBridgeProvider.get(context)
                            val results = withTimeout(30_000L) {
                                bridge.searchPeers(
                                    query = request.lookupNickname,
                                    expectedLiveName = request.expectedLiveName,
                                    sharedCode = request.sharedCode,
                                )
                            }

                            withContext(Dispatchers.Main) {
                                if (generation != searchGeneration) return@withContext
                            val list = results.map { contactFromPeerSearchResult(it, appLanguage) }
                            val verifiedCount = list.count { it.verified }
                            val unverifiedCount = list.size - verifiedCount

                            searchResults = list
                            searchSummary = if (appLanguage == "Русский") {
                                if (verifiedCount == 0 && unverifiedCount > 0) {
                                    "Найдено: $unverifiedCount без live-подтверждения. Добавление заблокировано."
                                } else {
                                    "Поиск завершён: подтверждено $verifiedCount, найдено без live-подтверждения $unverifiedCount"
                                }
                            } else {
                                if (verifiedCount == 0 && unverifiedCount > 0) {
                                    "Found: $unverifiedCount without live verification. Connection blocked."
                                } else {
                                    "Search complete: $verifiedCount verified, $unverifiedCount found without live verification"
                                }
                            }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            withContext(Dispatchers.Main) {
                                if (generation == searchGeneration) {
                                    searchResults = emptyList()
                                    searchSummary = if (appLanguage == "Русский") {
                                        "Не удалось завершить поиск. Проверьте сеть и повторите попытку."
                                    } else {
                                        "Search could not be completed. Check the network and try again."
                                    }
                                }
                            }
                        } finally {
                            progressJob.cancel()
                            withContext(Dispatchers.Main) {
                                if (generation == searchGeneration) {
                                    isSearching = false
                                    searchProgress = ""
                                }
                            }
                        }
                    }
                }
            }
        } else {
            searchGeneration++
            searchJob?.cancel()
            isSearching = false
            isResolvingInvite = false
            searchResults = emptyList()
            searchProgress = ""
            searchSummary = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Full-Width Search Input Field
        Surface(
            color = surfaceColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 12.dp)
                .height(48.dp)
                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = com.example.twopchat.R.drawable.ic_menu_search),
                    contentDescription = "Search Icon",
                    tint = primaryColor,
                    modifier = Modifier.size(18.dp)
                )
                
                Spacer(modifier = Modifier.width(10.dp))
                
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = Localizations.tr(appLanguage, "Имя#код, .onion или ссылка", "Name#code, .onion or link", "Name#Code, .onion oder Link", "Nombre#código, .onion o enlace", "Nom#code, .onion ou lien", "Nome#código, .onion ou link"),
                            color = onSurfaceVariant.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = updateSearchQuery,
                        singleLine = true,
                        keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                            context = context,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                        ),
                        cursorBrush = SolidColor(primaryColor),
                        textStyle = TextStyle(
                            color = onSurfaceColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                if (searchQuery.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clickable { updateSearchQuery("") },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceVariant
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clickable {
                                val pasted = readTextFromClipboard(context).trim()
                                if (isContactInviteLink(pasted) || pasted.contains('#')) {
                                    searchQuery = pasted
                                    performSearch(pasted)
                                } else {
                                    Toast.makeText(
                                        context,
                                        if (appLanguage == "Русский") "В буфере нет адреса контакта" else "Clipboard doesn't contain a contact address",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_copy_key),
                            contentDescription = if (appLanguage == "Русский") "Вставить из буфера" else "Paste from clipboard",
                            tint = primaryColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Quick Tools Row (Invite Link / QR Code / Direct Search)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Tool 1: Link Settings
            Surface(
                color = if (showInvitePanel) primaryColor.copy(alpha = 0.16f) else surfaceColor.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clickable {
                        showInvitePanel = !showInvitePanel
                        if (showInvitePanel) showQrPanel = false
                    }
                    .border(
                        if (showInvitePanel) 1.5.dp else 0.5.dp,
                        if (showInvitePanel) primaryColor else onSurfaceColor.copy(alpha = 0.08f),
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_quick_link),
                        contentDescription = "Invite Link",
                        tint = primaryColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = Localizations.tr(appLanguage, "Ссылка", "Link", "Link", "Enlace", "Lien", "Link"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = onSurfaceColor
                    )
                }
            }

            // Tool 2: QR Scanner
            Surface(
                color = if (showQrPanel) primaryColor.copy(alpha = 0.16f) else surfaceColor.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clickable {
                        showQrPanel = !showQrPanel
                        if (showQrPanel) showInvitePanel = false
                    }
                    .border(
                        if (showQrPanel) 1.5.dp else 0.5.dp,
                        if (showQrPanel) primaryColor else onSurfaceColor.copy(alpha = 0.08f),
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_qr_code),
                        contentDescription = "QR Code",
                        tint = primaryColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = Localizations.tr(appLanguage, "QR-код", "QR Code", "QR-Code", "Código QR", "Code QR", "Código QR"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = onSurfaceColor
                    )
                }
            }

            // Tool 3: Search Action Button
            val isSearchActive = searchQuery.isNotBlank()
            Surface(
                color = if (isSearchActive) primaryColor else primaryColor.copy(alpha = 0.16f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1.1f)
                    .height(40.dp)
                    .clickable { performSearch(searchQuery) }
                    .border(
                        1.dp,
                        primaryColor,
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_menu_search),
                        contentDescription = "Search",
                        tint = if (isSearchActive) (if (primaryColor == MintGreen) StealthBlack else Color.White) else primaryColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = Localizations.tr(appLanguage, "Найти", "Search", "Suchen", "Buscar", "Rechercher", "Buscar"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSearchActive) (if (primaryColor == MintGreen) StealthBlack else Color.White) else primaryColor
                    )
                }
            }
        }

        val onionAddressCard: String? = remember {
            P2PPreferences.getTorOnionHostname(context) ?: TorManager.onionAddress.value
        }
        val isTorDaemonRunning = TorManager.isTorRunning.value

        if (discoveryCode.isNotEmpty() || onionAddressCard != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    if (discoveryCode.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = Localizations.tr(appLanguage, "Ваш адрес для поиска (Трекеры)", "Your search address (Trackers)", "Deine Suchadresse (Tracker)", "Tu dirección de búsqueda (Trackers)", "Votre adresse de recherche (Trackers)", "Seu endereço de busca (Trackers)"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = contactAddress,
                                    fontSize = 13.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = primaryColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Surface(
                                color = primaryColor.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .clickable {
                                        copyTextToClipboard(context, "2PChat contact", contactAddress)
                                        Toast.makeText(
                                            context,
                                            if (appLanguage == "Русский") "Адрес скопирован" else "Address copied",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    .border(0.5.dp, primaryColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            ) {
                                Text(
                                    text = Localizations.tr(appLanguage, "Копировать", "Copy", "Kopieren", "Copiar", "Copier", "Copiar"),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    if (onionAddressCard != null) {
                        if (discoveryCode.isNotEmpty()) {
                            HorizontalDivider(
                                color = onSurfaceColor.copy(alpha = 0.06f),
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = Localizations.tr(appLanguage, "Ваш Tor .onion адрес", "Your Tor .onion address", "Deine Tor .onion Adresse", "Tu dirección Tor .onion", "Votre adresse Tor .onion", "Seu endereço Tor .onion"),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isTorDaemonRunning) "● Active" else "○ Standby",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isTorDaemonRunning) Color(0xFF4CAF50) else onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = onionAddressCard,
                                    fontSize = 12.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = primaryColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Surface(
                                color = primaryColor.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .clickable {
                                        copyTextToClipboard(context, "2PChat Tor Onion", onionAddressCard)
                                        Toast.makeText(
                                            context,
                                            if (appLanguage == "Русский") "Tor Onion адрес скопирован" else "Tor Onion address copied",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    .border(0.5.dp, primaryColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            ) {
                                Text(
                                    text = Localizations.tr(appLanguage, "Копировать", "Copy", "Kopieren", "Copiar", "Copier", "Copiar"),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isContactInviteLink(searchQuery)) {
            Text(
                text = if (appLanguage == "Русский") {
                    "Ссылка приглашения распознана. Нажмите поиск для защищённого подключения."
                } else {
                    "Invite link recognized. Tap search to connect securely."
                },
                color = primaryColor,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        } else if (isDirectOnionAddress(searchQuery)) {
            Text(
                text = if (appLanguage == "Русский") {
                    "Адрес Tor Onion распознан. Нажмите поиск для прямого подключения через Tor."
                } else {
                    "Tor Onion address recognized. Tap search to connect directly over Tor."
                },
                color = primaryColor,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        // Invite resolving status indicator
        if (isResolvingInvite || resolveInviteStatus.isNotEmpty()) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                if (isResolvingInvite) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = primaryColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = resolveInviteStatus,
                    fontSize = 12.sp,
                    color = if (resolveInviteStatus.contains("not found") || resolveInviteStatus.contains("не найден"))
                        MaterialTheme.colorScheme.error else primaryColor
                )
            }
        }

        // Expanded One-Time Invite Card
        if (showInvitePanel) {
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(20.dp),

                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = Localizations.getString("create_invite", appLanguage),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = Localizations.getString("invite_desc", appLanguage),
                        fontSize = 12.sp,
                        color = onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    if (inviteLinkState.isEmpty()) {
                        Button(
                            onClick = {
                                val tokenBytes = ByteArray(16)
                                java.security.SecureRandom().nextBytes(tokenBytes)
                                val tokenVal = "2pchat_inv_" + tokenBytes.joinToString("") { "%02x".format(it) }
                                val onion = TorManager.getOnionAddress(context).orEmpty()
                                val onionQuery = if (onion.isNotEmpty()) "&onion=${android.net.Uri.encode(onion)}" else ""
                                inviteLinkState = "2pchat://connect?token=$tokenVal&name=$username&fp=$fingerprint$onionQuery"
                                coroutineScope.launch(Dispatchers.IO) {
                                    P2PBridgeProvider.get(context).announceSelf(
                                        tokenVal,
                                        fingerprint,
                                        P2PMessageRelay.listenerPort(context),
                                        rendezvousCode = tokenVal,
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = Localizations.getString("gen_link", appLanguage),
                                color = if (primaryColor == MintGreen) StealthBlack else Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                            ) {
                                Text(
                                    text = inviteLinkState,
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = onSurfaceColor,
                                    modifier = Modifier.padding(10.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            
                            Row(
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                // Copy Link
                                IconButton(
                                    onClick = {
                                        copyTextToClipboard(context, "2PChat invite", inviteLinkState)
                                        Toast.makeText(context, if (appLanguage == "Русский") "Ссылка скопирована!" else "Link copied!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(primaryColor, shape = RoundedCornerShape(12.dp))
                                ) {
                                    Icon(
                                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_copy_key),
                                        contentDescription = "Copy Link",
                                        tint = if (primaryColor == MintGreen) StealthBlack else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Share Link
                                IconButton(
                                    onClick = {
                                        val sendIntent = android.content.Intent().apply {
                                            action = android.content.Intent.ACTION_SEND
                                            putExtra(android.content.Intent.EXTRA_TEXT, inviteLinkState)
                                            type = "text/plain"
                                        }
                                        val shareIntent = android.content.Intent.createChooser(sendIntent, null)
                                        context.startActivity(shareIntent)
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(primaryColor.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp))
                                ) {
                                    Icon(
                                        painter = painterResource(id = android.R.drawable.ic_menu_share),
                                        contentDescription = "Share Link",
                                        tint = primaryColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Reset Link
                                IconButton(
                                    onClick = { inviteLinkState = "" },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp))
                                ) {
                                    Text(
                                        text = "✕",
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // QR Code Connection Panel
        if (showQrPanel) {
            val localIp = remember { P2PMessageRelay.getLocalIpAddress(context) }
            val yggIp = remember { P2PMessageRelay.getYggdrasilAddress() }
            val onionHost: String? = remember {
                TorManager.getOnionAddress(context)
                    ?: com.example.twopchat.config.P2PPreferences.getTorOnionHostname(context)
                    ?: TorManager.onionAddress.value
            }
            val listenerPort = remember { P2PMessageRelay.listenerPort(context) }
            var selectedQrMode by remember { mutableStateOf(if (onionHost != null && P2PPreferences.isTorEnabled(context)) "tor" else "standard") }

            val qrPayload = remember(
                username, discoveryCode, fingerprint, localIp, qrPublicIpv4, yggIp, listenerPort, onionHost, selectedQrMode,
            ) {
                if (selectedQrMode == "tor" && onionHost != null) {
                    "2pchat://connect?name=${android.net.Uri.encode(username)}&onion=${android.net.Uri.encode("$onionHost:$listenerPort")}&fp=${android.net.Uri.encode(fingerprint)}"
                } else {
                    buildContactQrPayload(
                        nickname = username,
                        discoveryCode = discoveryCode,
                        fingerprint = fingerprint,
                        localIpv4 = localIp.takeUnless { it == "127.0.0.1" }.orEmpty(),
                        publicIpv4 = qrPublicIpv4,
                        ipv6 = yggIp,
                        onion = onionHost.orEmpty(),
                        listenerPort = listenerPort,
                    )
                }
            }

            val qrBitmap = com.example.twopchat.ui.common.rememberQrCodeBitmap(qrPayload)

            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = Localizations.tr(appLanguage, "Личный QR-код", "Personal QR Code", "Persönlicher QR-Code", "Código QR personal", "Code QR personnel", "Código QR pessoal"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (selectedQrMode == "tor") {
                            Localizations.tr(appLanguage, "Прямое и анонимное подключение через сеть Tor", "Direct and anonymous connection via Tor network", "Direkte und anonyme Verbindung über das Tor-Netzwerk", "Conexión directa y anónima a través de la red Tor", "Connexion directe et anonyme via le réseau Tor", "Conexão direta e anônima pela rede Tor")
                        } else {
                            Localizations.tr(appLanguage, "Покажите этот QR другу — пусть отсканирует", "Show this QR to a friend to connect securely", "Zeige diesen QR-Code einem Freund für eine sichere Verbindung", "Muestra este QR a un amigo para conectarte de forma segura", "Montrez ce QR à un ami pour vous connecter en toute sécurité", "Mostre este QR para um amigo para se conectar com segurança")
                        },
                        fontSize = 12.sp,
                        color = onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    if (onionHost != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Surface(
                                color = if (selectedQrMode == "standard") primaryColor else primaryColor.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .clickable { selectedQrMode = "standard" }
                                    .border(0.5.dp, primaryColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            ) {
                                Text(
                                    text = Localizations.tr(appLanguage, "🌐 Стандартный", "🌐 Standard", "🌐 Standard", "🌐 Estándar", "🌐 Standard", "🌐 Padrão"),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedQrMode == "standard") (if (primaryColor == MintGreen) StealthBlack else Color.White) else onSurfaceColor,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = if (selectedQrMode == "tor") primaryColor else primaryColor.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .clickable { selectedQrMode = "tor" }
                                    .border(0.5.dp, primaryColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            ) {
                                Text(
                                    text = Localizations.tr(appLanguage, "🧅 Tor Onion", "🧅 Tor Onion", "🧅 Tor Onion", "🧅 Tor Onion", "🧅 Tor Onion", "🧅 Tor Onion"),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedQrMode == "tor") (if (primaryColor == MintGreen) StealthBlack else Color.White) else onSurfaceColor,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // QR Code Display
                    if (qrBitmap != null) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier
                                .size(220.dp)
                                .border(2.dp, primaryColor.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        ) {
                            androidx.compose.foundation.Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("—", color = onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Scan button
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                showCameraScannerDialog = true
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_qr_code),
                            contentDescription = null,
                            tint = if (primaryColor == MintGreen) StealthBlack else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = Localizations.tr(appLanguage, "Сканировать QR", "Scan QR Code", "QR-Code scannen", "Escanear código QR", "Scanner le code QR", "Escanear código QR"),
                            color = if (primaryColor == MintGreen) StealthBlack else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (showCameraScannerDialog) {
            CameraQrScannerOverlay(
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                onDismiss = { showCameraScannerDialog = false },
                onQrScanned = { scannedResult ->
                    showCameraScannerDialog = false
                    showQrPanel = false
                    val trimmed = scannedResult.trim()
                    try {
                        if (trimmed.startsWith("2pchat://group/invite") || trimmed.contains("group/invite") || trimmed.contains("group_id=")) {
                            val uri = android.net.Uri.parse(trimmed)
                            val groupId = uri.getQueryParameter("id") ?: uri.getQueryParameter("groupId") ?: trimmed.substringAfter("id=", "").substringBefore("&")
                            val token = uri.getQueryParameter("token").orEmpty()
                            val peer = uri.getQueryParameter("peer").orEmpty()
                            if (groupId.isNotBlank()) {
                                if (token.isNotBlank()) {
                                    com.example.twopchat.group.runtime.GroupChatCoordinator.requestJoinFromInvite(groupId, token, peer)
                                }
                                onItemClick(GroupConversation(groupId))
                                Toast.makeText(context, if (appLanguage == "Русский") "Вход в группу..." else "Joining group...", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            searchQuery = trimmed
                            performSearch(trimmed)
                        }
                    } catch (e: Throwable) {
                        android.util.Log.e("ContactsTab", "Error handling scanned QR code", e)
                        Toast.makeText(context, if (appLanguage == "Русский") "Ошибка обработки QR-кода" else "Error processing QR code", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val contactsToDisplay = if (searchQuery.isNotBlank()) searchResults else filteredContacts

        if (isSearching) {
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = primaryColor)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(if (appLanguage == "Русский") "Ищем пользователя" else "Searching for user", fontWeight = FontWeight.Bold, color = onSurfaceColor)
                        Text(searchProgress, fontSize = 12.sp, color = onSurfaceVariant)
                        Text(if (appLanguage == "Русский") "Результат появится даже для offline endpoint-а, но будет отмечен как непроверенный." else "Offline endpoints remain visible, but are marked unverified.", fontSize = 10.sp, color = onSurfaceVariant.copy(alpha = 0.75f))
                    }
                }
            }
        } else {
            if (searchSummary.isNotEmpty() && searchQuery.isBlank()) {
                Text(searchSummary, fontSize = 12.sp, color = onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            }

            if (contactsToDisplay.isNotEmpty()) {
                // Contact Directory List
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    contactsToDisplay.forEach { contact ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceColor),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = true) {
                                    val existingFingerprint = sharedPrefs.getString("peer_fingerprint_${contact.name}", null)

                                    val peerKey = if (
                                        existingFingerprint.isNullOrBlank() || existingFingerprint == contact.fingerprint
                                    ) {
                                        contact.name
                                    } else {
                                        // Preserve both contacts only for a real
                                        // same-name/different-key collision.
                                        "${contact.name} · ${contact.fingerprint.take(8)}"
                                    }
                                    val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                                    if (!activeSet.contains(peerKey)) {
                                        val newSet = activeSet.toMutableSet()
                                        newSet.add(peerKey)
                                        sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                                        val isYgg = contact.endpoints.split(',')
                                            .map(String::trim)
                                            .any { it.startsWith('[') }
                                        sharedPrefs.edit()
                                            .putString("transport_$peerKey", if (isYgg) "YGGDRASIL" else "DIRECT P2P")
                                            .putString("peer_fingerprint_$peerKey", contact.fingerprint)
                                            .putString("discovery_code_$peerKey", (if (searchQuery.trim().split('#').size > 1) searchQuery.trim().split('#')[1].trim() else ""))
                                            .apply()
                                    }
                                    // The live search already authenticated this fingerprint.
                                    if (contact.endpoints.isNotBlank() && contact.endpoints != "Unknown") {
                                        sharedPrefs.edit().putString("last_endpoint_$peerKey", contact.endpoints).apply()
                                        if (contact.endpoints.contains(".onion")) {
                                            P2PPreferences.setPeerOnionAddress(context, peerKey, contact.endpoints)
                                            ChatDatabaseHelper.getInstance(context).savePeerOnionAddress(
                                                peerName = peerKey,
                                                onionAddress = contact.endpoints,
                                                fingerprint = contact.fingerprint.takeIf { it.isNotBlank() },
                                                endpoint = contact.endpoints,
                                            )
                                        }
                                        if (contact.fingerprint.isNotBlank()) {
                                            P2PBridgeProvider.get(context).updatePeerNameMapping(contact.fingerprint, peerKey)
                                        }
                                        P2PMessageRelay.rememberAuthenticatedPeerEndpoint(peerKey, contact.endpoints)
                                        P2PMessageRelay.triggerImmediateReconnect(context)
                                    }
                                    onItemClick(Chat(peerKey))
                                }
                                .border(
                                    width = 1.dp,
                                    color = if (contact.verified) primaryColor.copy(alpha = 0.35f) else Color(0xFFF59E0B).copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(18.dp)
                                )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Avatar
                                    val peerAvatarBitmap = P2PMessageRelay.peerAvatars[contact.name]
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(primaryColor.copy(alpha = 0.12f))
                                    ) {
                                        if (peerAvatarBitmap != null) {
                                            Image(
                                                bitmap = peerAvatarBitmap.asImageBitmap(),
                                                contentDescription = contact.name,
                                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                                            )
                                        } else {
                                            Text(
                                                text = contact.initials,
                                                color = primaryColor,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = contact.name,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = onSurfaceColor
                                        )

                                        Spacer(modifier = Modifier.height(3.dp))

                                        val localizedStatus = when {
                                            contact.status.startsWith("Online") -> contact.status
                                            contact.status == "Offline" -> Localizations.getString("offline", appLanguage)
                                            contact.status.startsWith("Active ") -> {
                                                val timeStr = contact.status.substringAfter("Active ").substringBefore(" ago")
                                                String.format(Localizations.getString("active_m", appLanguage), timeStr)
                                            }
                                            else -> contact.status
                                        }

                                        val statusColor = when {
                                            contact.ownershipVerified -> Color(0xFF059669)
                                            contact.verified -> if (onSurfaceColor.luminance() > 0.5f) Color(0xFFFFC107) else Color(0xFFD97706)
                                            else -> Color(0xFFDC2626)
                                        }

                                        Text(
                                            text = localizedStatus,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = statusColor
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    // Action Button / Badge
                                    Surface(
                                        color = primaryColor,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                    ) {
                                        Text(
                                            text = when {
                                                contact.ownershipVerified -> if (appLanguage == "Русский") "Доверен" else "Trusted"
                                                contact.verified -> if (appLanguage == "Русский") "Выбрать" else "Select"
                                                else -> if (appLanguage == "Русский") "Написать" else "Chat"
                                            },
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (primaryColor == MintGreen || primaryColor.luminance() > 0.6f) StealthBlack else Color.White,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                    }
                                }

                                // Cryptographic Fingerprint Chip (Adaptive Light & Dark contrast)
                                if (contact.fingerprint.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Surface(
                                        color = onSurfaceColor.copy(alpha = 0.05f),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(0.5.dp, onSurfaceColor.copy(alpha = 0.1f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "FP: ${contact.fingerprint.take(12)}…${contact.fingerprint.takeLast(6)}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (contact.ownershipVerified) {
                                                Color(0xFF059669)
                                            } else if (onSurfaceColor.luminance() > 0.5f) {
                                                Color(0xFFFFC107)
                                            } else {
                                                Color(0xFFD97706)
                                            },
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                        )
                                    }
                                }



                                if (contact.endpoints.isNotBlank() && contact.endpoints != "Unknown") {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(contact.endpoints, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            if (contactsToDisplay.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.5.dp, onSurfaceColor.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(52.dp)
                                .background(primaryColor.copy(alpha = 0.12f), shape = CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_menu_search),
                                contentDescription = "Search Tip Icon",
                                tint = primaryColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                Localizations.tr(appLanguage, "Пользователь не найден", "User not found", "Benutzer nicht gefunden", "Usuario no encontrado", "Utilisateur non trouvé", "Usuário não encontrado")
                            } else {
                                Localizations.tr(appLanguage, "Поиск пиров в сети 2PChat", "P2P Network Search", "P2P-Netzwerksuche", "Búsqueda en red P2P", "Recherche réseau P2P", "Busca na rede P2P")
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                Localizations.tr(appLanguage, "Проверьте правильность написания имени#кода и убедитесь, что собеседник находится в сети.", "Check the name#code format and make sure your peer is online.", "Überprüfe das Format Name#Code und stelle sicher, dass der Kontakt online ist.", "Comprueba el formato nombre#código y asegúrate de que tu contacto esté en línea.", "Vérifiez le format nom#code et assurez-vous que votre contact est en ligne.", "Verifique o formato nome#código e certifique-se de que o contato esteja online.")
                            } else {
                                Localizations.tr(appLanguage, "Введите имя собеседника с хэш-кодом (например, user#1234) или вставьте P2P-ссылку приглашения.", "Enter user name with hash code (e.g. user#1234) or paste P2P invite link.", "Gib den Benutzernamen mit Hashcode ein (z. B. user#1234) oder füge einen P2P-Einladungslink ein.", "Introduce el nombre de usuario con código hash (ej. usuario#1234) o pega el enlace de invitación P2P.", "Entrez le nom d'utilisateur avec le code de hachage (ex. util#1234) ou collez le lien d'invitation P2P.", "Insira o nome de usuário com o código hash (ex. usuario#1234) ou cole o link de convite P2P.")
                            },
                            fontSize = 12.sp,
                            color = onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun CameraQrScannerOverlay(
    appLanguage: String,
    primaryColor: Color,
    onDismiss: () -> Unit,
    onQrScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val isScanned = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var isTorchEnabled by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    var cameraProviderInstance by remember { mutableStateOf<androidx.camera.lifecycle.ProcessCameraProvider?>(null) }

    val liveScannerOptions = remember {
        com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .build()
    }
    val liveBarcodeScanner = remember {
        com.google.mlkit.vision.barcode.BarcodeScanning.getClient(liveScannerOptions)
    }
    val analysisExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            isScanned.set(true)
            try {
                cameraProviderInstance?.unbindAll()
            } catch (_: Throwable) {}
            try {
                analysisExecutor.shutdown()
            } catch (_: Throwable) {}
            try {
                liveBarcodeScanner.close()
            } catch (_: Throwable) {}
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null && !isScanned.get()) {
            try {
                val inputImage = com.google.mlkit.vision.common.InputImage.fromFilePath(context, uri)
                liveBarcodeScanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        val qrText = barcodes.firstOrNull { it.rawValue?.isNotBlank() == true }?.rawValue
                        if (!qrText.isNullOrBlank() && !isScanned.getAndSet(true)) {
                            try {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            } catch (_: Throwable) {}
                            onQrScanned(qrText)
                        } else {
                            var zxingSuccess = false
                            try {
                                val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                    val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                                    android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                                    }
                                } else {
                                    @Suppress("DEPRECATION")
                                    android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                                }
                                val intArray = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                val source = com.google.zxing.RGBLuminanceSource(bitmap.width, bitmap.height, intArray)
                                val binaryBitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
                                val result = com.google.zxing.MultiFormatReader().decode(binaryBitmap)
                                if (result != null && result.text.isNotBlank() && !isScanned.getAndSet(true)) {
                                    zxingSuccess = true
                                    try {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    } catch (_: Throwable) {}
                                    onQrScanned(result.text)
                                }
                            } catch (_: Throwable) {}
                            if (!zxingSuccess) {
                                Toast.makeText(
                                    context,
                                    if (appLanguage == "Русский") "QR-код не найден на фото" else "No QR code found in photo",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(context, e.message ?: "Failed to read image", Toast.LENGTH_SHORT).show()
                    }
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "laserAnimation")
        val laserOffsetY by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 245f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "laserPos"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    val previewView = androidx.camera.view.PreviewView(ctx).apply {
                        scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
                    }
                    val mainExecutor = ContextCompat.getMainExecutor(ctx)
                    val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            cameraProviderInstance = cameraProvider
                            val preview = androidx.camera.core.Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                                .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                if (isScanned.get()) {
                                    try { imageProxy.close() } catch (_: Throwable) {}
                                    return@setAnalyzer
                                }
                                @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                                val mediaImage = imageProxy.image
                                if (mediaImage != null && !isScanned.get()) {
                                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                    val inputImage = com.google.mlkit.vision.common.InputImage.fromMediaImage(mediaImage, rotationDegrees)
                                    liveBarcodeScanner.process(inputImage)
                                        .addOnSuccessListener { barcodes ->
                                            if (isScanned.get()) return@addOnSuccessListener
                                            for (barcode in barcodes) {
                                                val rawValue = barcode.rawValue ?: barcode.displayValue ?: continue
                                                if (rawValue.isNotBlank() && !isScanned.getAndSet(true)) {
                                                    mainExecutor.execute {
                                                        try {
                                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                        } catch (_: Throwable) {}
                                                        onQrScanned(rawValue)
                                                    }
                                                    break
                                                }
                                            }
                                        }
                                        .addOnCompleteListener {
                                            try {
                                                imageProxy.close()
                                            } catch (_: Throwable) {}
                                        }
                                } else {
                                    try {
                                        imageProxy.close()
                                    } catch (_: Throwable) {}
                                }
                            }

                            val cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
                            cameraProvider.unbindAll()
                            val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                            cameraControl = camera.cameraControl
                        } catch (e: Exception) {
                            android.util.Log.e("CameraQrScanner", "Camera bind failed", e)
                        }
                    }, mainExecutor)
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Scanning Overlay UI
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (appLanguage == "Русский") "Сканирование QR-кода" else "Scan QR Code",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Central Laser Frame Box
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .border(3.dp, primaryColor, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.TopCenter
                ) {
                    // Animated Scanning Laser Beam
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(3.dp)
                            .offset(y = laserOffsetY.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        primaryColor,
                                        Color.White,
                                        primaryColor,
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }

                // Bottom Instruction & Control Buttons
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text(
                        text = if (appLanguage == "Русский") "Наведите камеру на QR-код собеседника" else "Point camera at peer's QR code",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Torch Button
                        IconButton(
                            onClick = {
                                isTorchEnabled = !isTorchEnabled
                                cameraControl?.enableTorch(isTorchEnabled)
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    if (isTorchEnabled) primaryColor else Color.Black.copy(alpha = 0.65f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (isTorchEnabled) com.example.twopchat.R.drawable.ic_torch_on else com.example.twopchat.R.drawable.ic_torch_off
                                ),
                                contentDescription = "Torch",
                                tint = if (isTorchEnabled) StealthBlack else Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Gallery Picker Button
                        IconButton(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_attach_gallery),
                                contentDescription = "Gallery",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ================= Settings Tab Screen =================
