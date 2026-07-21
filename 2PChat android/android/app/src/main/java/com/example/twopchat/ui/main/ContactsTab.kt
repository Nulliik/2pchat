
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.twopchat.PythonBridge
import com.example.twopchat.Chat
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.theme.*
import com.example.twopchat.data.Localizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close

internal data class PeerSearchAddress(
    val nickname: String,
    val discoveryCode: String,
)

internal fun parsePeerSearchAddress(value: String): PeerSearchAddress? {
    val trimmed = value.trim()
    val separator = trimmed.lastIndexOf('#')
    if (separator <= 0 || separator == trimmed.lastIndex) return null

    val nickname = trimmed.substring(0, separator).trim().replace(Regex("\\s+"), "_")
    val discoveryCode = trimmed.substring(separator + 1).trim()
    if (nickname.isEmpty() || discoveryCode.isEmpty()) return null
    return PeerSearchAddress(nickname, discoveryCode)
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
            if (appLanguage == "Русский") "Подтверждён ссылкой приглашения" else "Verified by invite link"
        } else if (verified) {
            if (appLanguage == "Русский") {
                "Узел и ключ активны · владелец ника не подтверждён"
            } else {
                "Live node and key · nickname ownership unverified"
            }
        } else if (appLanguage == "Русский") {
            "Найден на трекере · live-проверка не пройдена"
        } else {
            "Found on tracker · live verification failed"
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
    var inviteLinkState by remember { mutableStateOf("") }
    var showInvitePanel by remember { mutableStateOf(false) }
    var showQrPanel by remember { mutableStateOf(false) }
    var isResolvingInvite by remember { mutableStateOf(false) }
    var resolveInviteStatus by remember { mutableStateOf("") }
    
    val sharedPrefs = remember { com.example.twopchat.P2PPreferences.prefs(context) }
    val rawUsername = remember { sharedPrefs.getString("username_profile", "User Identity") ?: "User Identity" }
    val username = remember(rawUsername) { rawUsername.trim().replace(Regex("\\s+"), "_") }
    val discoveryCode = remember { PythonBridge.getOrCreateDiscoveryCode() }
    val contactAddress = remember(username, discoveryCode) { "$username#$discoveryCode" }
    var fingerprint by remember { mutableStateOf("Loading...") }
    LaunchedEffect(Unit) {
        while (!PythonBridge.isInitialized) {
            kotlinx.coroutines.delay(100)
        }
        fingerprint = withContext(Dispatchers.IO) { PythonBridge.getLocalFingerprint() }
    }

    // Search results must come from an authenticated live peer.  The former
    // hard-coded demo directory made fictional users look searchable/online.
    val filteredContacts = emptyList<ContactItem>()

    // Reusable search execution lambda (used by search button & QR scanner)
    val performSearch = { query: String ->
        if (query.isNotBlank()) {
            val trimmed = query.trim()
            if (trimmed.startsWith("2pchat://connect")) {
                try {
                    val uri = android.net.Uri.parse(trimmed)
                    val parsedName = uri.getQueryParameter("name") ?: "Invited Peer"
                    val token = uri.getQueryParameter("token") ?: uri.getQueryParameter("code") ?: ""
                    val expectedFp = (uri.getQueryParameter("fp")?.trim().orEmpty()).replace(" ", "+")
                    val directIp = uri.getQueryParameter("ip")
                    val yggIp = uri.getQueryParameter("ygg")

                    if (token.isEmpty()) {
                        resolveInviteStatus = if (appLanguage == "Русский") {
                            "Некорректная ссылка/QR: отсутствует код подключения"
                        } else {
                            "Invalid link/QR: missing connection code"
                        }
                    } else {
                        if (!directIp.isNullOrBlank()) {
                            val formattedIp = if (directIp.contains(":")) directIp else "$directIp:50001"
                            com.example.twopchat.P2PMessageRelay.injectLocalDiscoveryCandidate(parsedName, expectedFp, formattedIp)
                        }
                        if (!yggIp.isNullOrBlank()) {
                            val formattedYgg = if (yggIp.contains(":")) yggIp else "[$yggIp]:50001"
                            com.example.twopchat.P2PMessageRelay.injectLocalDiscoveryCandidate(parsedName, expectedFp, formattedYgg)
                        }
                        isResolvingInvite = true
                        resolveInviteStatus = if (appLanguage == "Русский") "Мгновенное подключение к собеседнику..." else "Connecting to peer..."
                        coroutineScope.launch(Dispatchers.IO) {
                            val peers = PythonBridge.searchPeers(token, parsedName, expectedFp.ifEmpty { null })
                            val endpoints = if (peers.isNotEmpty()) peers[0]["endpoints"] as? List<*> else null
                            val endpointStr = if (endpoints != null && endpoints.isNotEmpty()) endpoints.joinToString(",") { it.toString() } else ""
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                isResolvingInvite = false
                                if (endpointStr.isNotEmpty()) {
                                    val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                                    if (!activeSet.contains(parsedName)) {
                                        sharedPrefs.edit()
                                            .putStringSet("active_chats", activeSet + parsedName)
                                            .putString("transport_$parsedName", "DIRECT P2P")
                                            .putString("peer_fingerprint_$parsedName", expectedFp)
                                            .putString("discovery_code_$parsedName", token)
                                            .apply()
                                    }
                                    com.example.twopchat.P2PMessageRelay.rememberAuthenticatedPeerEndpoint(parsedName, endpointStr)
                                    resolveInviteStatus = ""
                                    onItemClick(Chat(parsedName))
                                } else {
                                    resolveInviteStatus = if (appLanguage == "Русский") "Собеседник не найден. Попробуйте снова." else "Peer not found. Please try again."
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Invalid link/QR", Toast.LENGTH_SHORT).show()
                }
            } else {
                val address = parsePeerSearchAddress(trimmed)
                if (address == null) {
                    searchSummary = if (appLanguage == "Русский") {
                        "Введите полный адрес в формате Имя#код. Поиск только по нику отключён."
                    } else {
                        "Enter the full Name#code address. Nickname-only search is disabled."
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
                    coroutineScope.launch(Dispatchers.IO) {
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

                        val results = PythonBridge.searchPeers(
                            address.nickname,
                            expectedLiveName = address.nickname,
                            sharedCode = address.discoveryCode,
                        )
                        progressJob.cancel()

                        withContext(Dispatchers.Main) {
                            isSearching = false
                            searchProgress = ""
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
                    }
                }
            }
        } else {
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
                            text = if (appLanguage == "Русский") "Имя#код или ссылка 2PChat" else "Name#code or 2PChat link",
                            color = onSurfaceVariant.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
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
                            .clickable { searchQuery = "" },
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
                                if (pasted.startsWith("2pchat://connect") || pasted.contains('#')) {
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
                        text = if (appLanguage == "Русский") "Ссылка" else "Link",
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
                        text = if (appLanguage == "Русский") "QR-код" else "QR Code",
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
                        text = if (appLanguage == "Русский") "Найти" else "Search",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSearchActive) (if (primaryColor == MintGreen) StealthBlack else Color.White) else primaryColor
                    )
                }
            }
        }

        if (discoveryCode.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (appLanguage == "Русский") "Ваш адрес для поиска" else "Your search address",
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
                            text = if (appLanguage == "Русский") "Копировать" else "Copy",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        if (searchQuery.trim().startsWith("2pchat://connect")) {
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
                                inviteLinkState = "2pchat://connect?token=$tokenVal&name=$username&fp=$fingerprint"
                                coroutineScope.launch(Dispatchers.IO) {
                                    PythonBridge.announceSelf(
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

                                // Connect Peer — look up an incoming session via the token
                                IconButton(
                                    onClick = {
                                        val guestName = if (appLanguage == "Русский") "Приглашенный гость" else "Guest Peer"
                                        val currentLink = inviteLinkState
                                        if (currentLink.isNotEmpty()) {
                                            val uri = android.net.Uri.parse(currentLink)
                                            val token = uri.getQueryParameter("token") ?: ""
                                            if (token.isNotEmpty()) {
                                                isResolvingInvite = true
                                                resolveInviteStatus = if (appLanguage == "Русский") "Поиск собеседника..." else "Looking for guest peer..."
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val peers = PythonBridge.searchPeers(token)
                                                    val endpoints = if (peers.isNotEmpty()) peers[0]["endpoints"] as? List<*> else null
                                                    val endpointStr = if (endpoints != null && endpoints.isNotEmpty()) endpoints.joinToString(",") { it.toString() } else ""
                                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                        isResolvingInvite = false
                                                        if (endpointStr.isNotEmpty()) {
                                                            val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                                                            if (!activeSet.contains(guestName)) {
                                                                val newSet = activeSet.toMutableSet()
                                                                newSet.add(guestName)
                                                                sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                                                                sharedPrefs.edit().putString("transport_$guestName", "DIRECT P2P").apply()
                                                            }
                                                            com.example.twopchat.P2PMessageRelay.rememberAuthenticatedPeerEndpoint(guestName, endpointStr)
                                                            resolveInviteStatus = ""
                                                            inviteLinkState = ""
                                                            onItemClick(Chat(guestName))
                                                        } else {
                                                            resolveInviteStatus = if (appLanguage == "Русский") "Гость ещё не подсоединился." else "Guest has not connected yet."
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color(0xFF4CAF50), shape = RoundedCornerShape(12.dp))
                                ) {
                                    Icon(
                                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_quick_link),
                                        contentDescription = "Connect",
                                        tint = Color.White,
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
            val localIp = remember { PythonBridge.getLocalIpAddress(false) }
            val yggIp = remember { PythonBridge.getYggdrasilAddress() }
            val qrPayload = remember(username, discoveryCode, fingerprint, localIp, yggIp) {
                val builder = StringBuilder("2pchat://connect?")
                    .append("name=").append(android.net.Uri.encode(username))
                    .append("&code=").append(android.net.Uri.encode(discoveryCode))
                if (fingerprint.isNotBlank() && fingerprint != "Loading..." && fingerprint != "Not Initialized") {
                    builder.append("&fp=").append(android.net.Uri.encode(fingerprint))
                }
                if (localIp.isNotBlank() && localIp != "127.0.0.1") {
                    builder.append("&ip=").append(android.net.Uri.encode(localIp))
                }
                if (yggIp.isNotBlank()) {
                    builder.append("&ygg=").append(android.net.Uri.encode(yggIp))
                }
                builder.toString()
            }

            val qrBitmap = remember(qrPayload) {
                try {
                    val size = 512
                    val writer = com.google.zxing.qrcode.QRCodeWriter()
                    val bitMatrix = writer.encode(qrPayload, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
                    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                    for (x in 0 until size) {
                        for (y in 0 until size) {
                            bmp.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                        }
                    }
                    bmp
                } catch (e: Exception) { null }
            }

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
                        text = if (appLanguage == "Русский") "Личный QR-код" else "Personal QR Code",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (appLanguage == "Русский")
                            "Покажите этот QR другу — пусть отсканирует"
                        else
                            "Show this QR to a friend to connect securely",
                        fontSize = 12.sp,
                        color = onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

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
                            val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                                .enableAutoZoom()
                                .build()
                            val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
                            scanner.startScan()
                                .addOnSuccessListener { barcode: com.google.mlkit.vision.barcode.common.Barcode ->
                                    val rawValue = barcode.rawValue ?: ""
                                    if (rawValue.isNotBlank()) {
                                        searchQuery = rawValue
                                        performSearch(rawValue)
                                        showQrPanel = false
                                    }
                                }
                                .addOnFailureListener { e: Exception ->
                                    Toast.makeText(context, e.message ?: "Scan failed", Toast.LENGTH_SHORT).show()
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
                            text = if (appLanguage == "Русский") "Сканировать QR" else "Scan QR Code",
                            color = if (primaryColor == MintGreen) StealthBlack else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
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
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = contact.verified) {
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
                                    // The live search already authenticated this fingerprint. Seed the
                                    // Python-side name map now so an incoming message cannot briefly be
                                    // filed under Peer(<fingerprint-prefix>) before identity_info arrives.
                                    PythonBridge.rememberPeerName(contact.fingerprint, peerKey)
                                    if (contact.endpoints.isNotBlank() && contact.endpoints != "Unknown") {
                                        P2PMessageRelay.rememberAuthenticatedPeerEndpoint(peerKey, contact.endpoints)
                                    }
                                    onItemClick(Chat(peerKey))
                                }
                                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(14.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Avatar
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(primaryColor.copy(alpha = 0.1f), shape = CircleShape)
                                ) {
                                    Text(
                                        text = contact.initials,
                                        color = primaryColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(12.dp))
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = contact.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = onSurfaceColor
                                    )
                                    
                                    val localizedStatus = when {
                                        contact.status.startsWith("Online") -> contact.status
                                        contact.status == "Offline" -> Localizations.getString("offline", appLanguage)
                                        contact.status.startsWith("Active ") -> {
                                            val timeStr = contact.status.substringAfter("Active ").substringBefore(" ago")
                                            String.format(Localizations.getString("active_m", appLanguage), timeStr)
                                        }
                                        else -> contact.status
                                    }
                                    
                                    Text(
                                        text = localizedStatus,
                                        fontSize = 12.sp,
                                        color = if (contact.verified) primaryColor else Color(0xFFFFB300)
                                    )
                                    if (contact.fingerprint.isNotBlank()) {
                                        Text(
                                            text = "FP: ${contact.fingerprint.take(12)}…${contact.fingerprint.takeLast(6)}",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (contact.ownershipVerified) Color(0xFF4CAF50) else Color(0xFFFFB300)
                                        )
                                    }
                                    if (contact.verified && !contact.ownershipVerified) {
                                        Text(
                                            text = if (appLanguage == "Русский") "Это ключ живого узла, но не доказательство владения ником" else "Live node key; not proof of nickname ownership",
                                            fontSize = 10.sp,
                                            color = Color(0xFFFFB300)
                                        )
                                    }
                                    if (!contact.verified) {
                                        Text(contact.endpoints, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = onSurfaceVariant)
                                        Text(if (appLanguage == "Русский") "Подключение заблокировано до успешной проверки личности" else "Connection is blocked until identity verification succeeds", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                
                                Text(
                                    text = when {
                                        contact.ownershipVerified -> if (appLanguage == "Русский") "ДОВЕРЕН" else "TRUSTED"
                                        contact.verified -> if (appLanguage == "Русский") "ВЫБРАТЬ КЛЮЧ" else "SELECT KEY"
                                        appLanguage == "Русский" -> "НЕ ПРОВЕРЕН"
                                        else -> "UNVERIFIED"
                                    },
                                    fontSize = 11.sp,
                                    color = primaryColor,
                                    fontWeight = FontWeight.Bold
                                )
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
                                if (appLanguage == "Русский") "Пользователь не найден" else "User not found"
                            } else {
                                if (appLanguage == "Русский") "Поиск пиров в сети 2PChat" else "P2P Network Search"
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                if (appLanguage == "Русский") {
                                    "Проверьте правильность написания имени#кода и убедитесь, что собеседник находится в сети."
                                } else {
                                    "Check the name#code format and make sure your peer is online."
                                }
                            } else {
                                if (appLanguage == "Русский") {
                                    "Введите имя собеседника с хэш-кодом (например, user#1234) или вставьте P2P-ссылку приглашения."
                                } else {
                                    "Enter user name with hash code (e.g. user#1234) or paste P2P invite link."
                                }
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

// ================= Settings Tab Screen =================
