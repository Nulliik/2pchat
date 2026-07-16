
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
    
    val sharedPrefs = remember { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    val username = remember { sharedPrefs.getString("username_profile", "User Identity") ?: "User Identity" }
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
                    val token = uri.getQueryParameter("token") ?: ""
                    val expectedFp = (uri.getQueryParameter("fp")?.trim().orEmpty()).replace(" ", "+")
                    val validFingerprint = try {
                        val decoded = android.util.Base64.decode(expectedFp, android.util.Base64.NO_WRAP)
                        decoded.size == 32 && android.util.Base64.encodeToString(decoded, android.util.Base64.NO_WRAP) == expectedFp
                    } catch (_: IllegalArgumentException) {
                        false
                    }

                    if (token.isEmpty() || !validFingerprint) {
                        resolveInviteStatus = if (appLanguage == "Русский") {
                            "Некорректная ссылка: отсутствует token или 32-байтный fingerprint"
                        } else {
                            "Invalid invite: missing token or 32-byte fingerprint"
                        }
                    } else {
                        isResolvingInvite = true
                        resolveInviteStatus = if (appLanguage == "Русский") "Поиск собеседника..." else "Finding peer..."
                        coroutineScope.launch(Dispatchers.IO) {
                            val peers = PythonBridge.searchPeers(token, parsedName, expectedFp)
                            val verified = peers.firstOrNull()?.get("verified")?.toString()?.equals("true", ignoreCase = true) == true
                            val ownershipVerified = peers.firstOrNull()?.get("ownership_verified")?.toString()?.equals("true", ignoreCase = true) == true
                            val endpoints = if (peers.isNotEmpty()) peers[0]["endpoints"] as? List<*> else null
                            val endpointStr = if (endpoints != null && endpoints.isNotEmpty()) endpoints.joinToString(",") { it.toString() } else ""
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                isResolvingInvite = false
                                if (endpointStr.isNotEmpty() && verified && ownershipVerified) {
                                    val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                                    if (!activeSet.contains(parsedName)) {
                                        sharedPrefs.edit()
                                            .putStringSet("active_chats", activeSet + parsedName)
                                            .putString("transport_$parsedName", "DIRECT P2P")
                                            .putString("peer_fingerprint_$parsedName", expectedFp)
                                            .putString("discovery_code_$parsedName", token)
                                            .apply()
                                    }
                                    com.example.twopchat.P2PMessageRelay.peerEndpoints[parsedName] = endpointStr
                                    resolveInviteStatus = ""
                                    onItemClick(Chat(parsedName))
                                } else {
                                    resolveInviteStatus = if (appLanguage == "Русский") "Собеседник не найден. Попробуйте позже." else "Peer not found. They may be offline."
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Invalid link", Toast.LENGTH_SHORT).show()
                }
            } else {
                val separator = trimmed.lastIndexOf('#')
                if (separator <= 0 || separator == trimmed.lastIndex) {
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

                        val results = PythonBridge.searchPeers(trimmed)
                        progressJob.cancel()

                        withContext(Dispatchers.Main) {
                            isSearching = false
                            searchProgress = ""
                            val list = mutableListOf<ContactItem>()
                            var verifiedCount = 0
                            var unverifiedCount = 0

                            for (peer in results) {
                                val pName = peer["name"]?.toString() ?: "Unnamed"
                                val pFp = peer["fingerprint"]?.toString() ?: ""
                                val pCode = peer["discovery_code"]?.toString() ?: ""
                                val pVerified = peer["verified"]?.toString()?.equals("true", ignoreCase = true) == true
                                val pOwnership = peer["ownership_verified"]?.toString()?.equals("true", ignoreCase = true) == true
                                val pEndpoints = peer["endpoints"] as? List<*>
                                val endpointStr = if (pEndpoints != null && pEndpoints.isNotEmpty()) pEndpoints.joinToString(",") { it.toString() } else ""

                                if (pVerified && pOwnership) {
                                    verifiedCount++
                                    list.add(ContactItem(pName, pFp, pCode, pVerified, endpointStr))
                                } else {
                                    unverifiedCount++
                                }
                            }

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
        // Search Header Row with Quick Action Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        if (appLanguage == "Русский") "Имя#код или ссылка 2PChat" else "Name#code or 2PChat link",
                        color = onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                trailingIcon = {
                    IconButton(onClick = {
                        val pasted = readTextFromClipboard(context).trim()
                        if (pasted.startsWith("2pchat://connect") || pasted.contains('#')) {
                            searchQuery = pasted
                        } else {
                            Toast.makeText(
                                context,
                                if (appLanguage == "Русский") "В буфере нет адреса контакта" else "Clipboard doesn't contain a contact address",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) {
                        Icon(
                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_copy_key),
                            contentDescription = if (appLanguage == "Русский") "Вставить ссылку приглашения" else "Paste invite link",
                            tint = primaryColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = surfaceColor,
                    unfocusedContainerColor = surfaceColor,
                    focusedTextColor = onSurfaceColor,
                    unfocusedTextColor = onSurfaceColor,
                    focusedIndicatorColor = primaryColor,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            )
            
            // Link Settings Panel Toggle Button
            IconButton(
                onClick = {
                    showInvitePanel = !showInvitePanel
                    if (showInvitePanel) showQrPanel = false
                },
                modifier = Modifier
                    .size(52.dp)
                    .background(if (showInvitePanel) primaryColor else surfaceColor, shape = RoundedCornerShape(14.dp))
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            ) {
                Icon(
                    painter = painterResource(id = com.example.twopchat.R.drawable.ic_quick_link),
                    contentDescription = "Invite Link Settings",
                    tint = if (showInvitePanel) StealthBlack else primaryColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            // QR Code Connection Panel Toggle Button
            IconButton(
                onClick = {
                    showQrPanel = !showQrPanel
                    if (showQrPanel) showInvitePanel = false
                },
                modifier = Modifier
                    .size(52.dp)
                    .background(if (showQrPanel) primaryColor else surfaceColor, shape = RoundedCornerShape(14.dp))
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            ) {
                Icon(
                    painter = painterResource(id = com.example.twopchat.R.drawable.ic_qr_code),
                    contentDescription = "QR Code Connection",
                    tint = if (showQrPanel) StealthBlack else primaryColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            
            // Search Execute Button
            IconButton(
                onClick = { performSearch(searchQuery) },
                modifier = Modifier
                    .size(52.dp)
                    .background(primaryColor, shape = RoundedCornerShape(14.dp))
            ) {
                Icon(
                    painter = painterResource(id = com.example.twopchat.R.drawable.ic_menu_search),
                    contentDescription = "Search",
                    tint = if (primaryColor == MintGreen) StealthBlack else Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        if (discoveryCode.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (appLanguage == "Русский") "Ваш адрес для поиска" else "Your search address",
                        fontSize = 11.sp,
                        color = onSurfaceVariant,
                    )
                    Text(
                        text = contactAddress,
                        fontSize = 13.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = onSurfaceColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = {
                    copyTextToClipboard(context, "2PChat contact", contactAddress)
                    Toast.makeText(
                        context,
                        if (appLanguage == "Русский") "Адрес скопирован" else "Address copied",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) {
                    Text(if (appLanguage == "Русский") "Копировать" else "Copy", color = primaryColor)
                }
            }
            Text(
                text = if (appLanguage == "Русский") {
                    "Отправьте этот адрес собеседнику. Код и SHA-1 приложение обработает само."
                } else {
                    "Send this address to your contact. The app handles the code and SHA-1 automatically."
                },
                fontSize = 11.sp,
                color = onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
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
                                                            com.example.twopchat.P2PMessageRelay.peerEndpoints[guestName] = endpointStr
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
            val qrBitmap = remember(contactAddress) {
                try {
                    val size = 512
                    val writer = com.google.zxing.qrcode.QRCodeWriter()
                    val bitMatrix = writer.encode(contactAddress, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
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

        // Peer Directory Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = Localizations.getString("secure_directory", appLanguage),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor
            )
            val count = if (searchQuery.isNotBlank()) searchResults.size else filteredContacts.size
            Text(
                text = String.format(Localizations.getString("peers_count", appLanguage), count),
                fontSize = 12.sp,
                color = onSurfaceVariant
            )
        }

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
            if (searchSummary.isNotEmpty()) {
                Text(searchSummary, fontSize = 12.sp, color = onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            }
            val contactsToDisplay = if (searchQuery.isNotBlank()) {
                searchResults
            } else {
                filteredContacts
            }

            if (searchQuery.isNotBlank() && contactsToDisplay.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (appLanguage == "Русский") "Пользователь не найден или не подтвердил имя при live-проверке" else "User not found or did not confirm their name during live verification",
                        color = onSurfaceVariant,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
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
                                        P2PMessageRelay.peerEndpoints[peerKey] = contact.endpoints
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
        }
    }
}

// ================= Settings Tab Screen =================
