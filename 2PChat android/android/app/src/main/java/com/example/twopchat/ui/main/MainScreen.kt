package com.example.twopchat.ui.main

import android.widget.Toast
import android.content.Intent
import android.net.VpnService
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalClipboardManager
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
import com.example.twopchat.theme.*
import com.example.twopchat.data.Localizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.*
import androidx.compose.animation.core.*

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    useCerulean: Boolean,
    onAccentChanged: (Boolean) -> Unit,
    appLanguage: String,
    onLanguageChanged: (String) -> Unit,
    onIconChanged: (String) -> Unit,
    onDeleteAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(0) }
    var localFingerprint by remember { mutableStateOf("Loading...") }
    LaunchedEffect(Unit) {
        while (!PythonBridge.isInitialized) {
            kotlinx.coroutines.delay(100)
        }
        localFingerprint = PythonBridge.getLocalFingerprint()
    }
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    var activeIconAlias by remember { mutableStateOf(sharedPrefs.getString("active_icon_alias", "MainActivityAliasDefault") ?: "MainActivityAliasDefault") }

    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    var activePeers by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            if (PythonBridge.isInitialized) {
                activePeers = PythonBridge.getActivePeers()
            }
            kotlinx.coroutines.delay(2500)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .safeDrawingPadding()
    ) {
        // App Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "2PChat",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = primaryColor,
                letterSpacing = (-0.5).sp
            )

            // P2P Active Status Chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(surfaceColor, shape = RoundedCornerShape(12.dp))
                    .border(
                        0.5.dp, 
                        if (activePeers.isNotEmpty()) Color(0xFF4CAF50).copy(alpha = 0.5f) else primaryColor.copy(alpha = 0.3f), 
                        RoundedCornerShape(12.dp)
                    )
                    .clickable(enabled = activePeers.isNotEmpty()) {
                        Toast.makeText(
                            context,
                            (if (appLanguage == "Русский") "Активные сессии: " else "Active sessions: ") + activePeers.joinToString(", "),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (activePeers.isNotEmpty()) Color(0xFF4CAF50) else onSurfaceColor.copy(alpha = 0.3f), shape = CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (activePeers.isNotEmpty()) {
                        if (activePeers.size == 1) {
                            activePeers.first()
                        } else {
                            "${activePeers.size} " + (if (appLanguage == "Русский") "актив." else "active")
                        }
                    } else {
                        if (appLanguage == "Русский") "нет сессий" else "no sessions"
                    },
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (activePeers.isNotEmpty()) Color(0xFF4CAF50) else onSurfaceColor.copy(alpha = 0.5f),
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 85.dp)
                )
            }
        }

        // Tab Content Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)).togetherWith(fadeOut(animationSpec = tween(220)))
                },
                label = "tab_transition"
            ) { targetTab ->
                when (targetTab) {
                    0 -> ChatsTab(onItemClick, localFingerprint, appLanguage, primaryColor, surfaceColor, onSurfaceColor, onSurfaceVariant)
                    1 -> ContactsTab(onItemClick, appLanguage, primaryColor, surfaceColor, onSurfaceColor, onSurfaceVariant)
                    2 -> SettingsTab(
                        isDarkTheme = isDarkTheme,
                        onThemeChanged = onThemeChanged,
                        useCerulean = useCerulean,
                        onAccentChanged = onAccentChanged,
                        activeIconAlias = activeIconAlias,
                        onIconChanged = { alias ->
                            activeIconAlias = alias
                            sharedPrefs.edit().putString("active_icon_alias", alias).apply()
                            val isDisguised = sharedPrefs.getBoolean("settings_stealth_disguise", false)
                            if (!isDisguised) {
                                onIconChanged(alias)
                            }
                        },
                        appLanguage = appLanguage,
                        onLanguageChanged = onLanguageChanged,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor,
                        onSurfaceVariant = onSurfaceVariant,
                        surfaceVariant = surfaceVariant,
                        onDeleteAccount = onDeleteAccount
                    )
                }
            }
        }

        // Bottom Navigation Bar
        TabNavigationRow(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor
        )
    }
}

// ================= Chats Tab Screen =================
@Composable
fun ChatsTab(
    onItemClick: (NavKey) -> Unit,
    localFingerprint: String,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    
    val sharedPrefs = remember(context) { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    var activeChatsSet by remember {
        mutableStateOf(sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")) ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen"))
    }
    
    androidx.compose.runtime.DisposableEffect(sharedPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "active_chats" || key?.startsWith("last_msg_") == true || key?.startsWith("transport_") == true) {
                activeChatsSet = sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")) ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val mockPeers = remember(activeChatsSet) {
        activeChatsSet.map { name ->
            val lastMsg = sharedPrefs.getString("last_msg_$name", null) ?: when(name) {
                "Eleanor Vance" -> "You: The designs look fantastic!"
                "Liam O'Connor" -> "Thanks for the feedback."
                "Sarah Chen" -> "Last message, your work is great!"
                else -> "No messages yet"
            }
            val transport = sharedPrefs.getString("transport_$name", null) ?: when(name) {
                "Liam O'Connor" -> "YGGDRASIL"
                else -> "DIRECT P2P"
            }
            PeerItem(
                name = name,
                lastMsg = lastMsg,
                transport = transport,
                isDirect = transport == "DIRECT P2P",
                initials = if (name.length >= 2) name.substring(0, 2).uppercase() else name.uppercase()
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Identity Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.08f),
                            surfaceColor.copy(alpha = 0.85f)
                        )
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .border(0.5.dp, primaryColor.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = Localizations.getString("my_fingerprint", appLanguage),
                    fontSize = 10.sp,
                    color = onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = localFingerprint,
                        fontSize = 14.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = onSurfaceColor,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(localFingerprint))
                            Toast.makeText(context, "Fingerprint copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(primaryColor.copy(alpha = 0.1f), shape = CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_copy_key),
                            contentDescription = "Copy Fingerprint",
                            tint = primaryColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "🛡️", fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = Localizations.getString("ram_info", appLanguage),
                        fontSize = 11.sp,
                        color = onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Chats Header
        Text(
            text = Localizations.getString("active_handshakes", appLanguage),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceColor,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Peers List
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Saved Messages / Notes Chat
            val savedMessagesName = Localizations.getString("saved_messages_title", appLanguage)
            val savedMessagesDesc = Localizations.getString("saved_messages_desc", appLanguage)
            PeerRow(
                peer = PeerItem(
                    name = savedMessagesName,
                    lastMsg = savedMessagesDesc,
                    transport = "LOCAL RAM",
                    isDirect = true,
                    initials = "🔖"
                ),
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onClick = { onItemClick(Chat("Saved Messages")) }
            )

            mockPeers.forEach { peer ->
                PeerRow(peer, appLanguage, primaryColor, surfaceColor, onSurfaceColor, onSurfaceVariant, onClick = { onItemClick(Chat(peer.name)) })
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ================= Contacts Tab Screen =================
@Composable
fun ContactsTab(
    onItemClick: (NavKey) -> Unit,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color
) {
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var inviteLinkState by remember { mutableStateOf("") }
    var directIpVal by remember { mutableStateOf("") }
    var directPortVal by remember { mutableStateOf("50001") }
    var directNameVal by remember { mutableStateOf("") }
    var showInvitePanel by remember { mutableStateOf(false) }
    var showDirectIpPanel by remember { mutableStateOf(false) }
    var isResolvingInvite by remember { mutableStateOf(false) }
    var resolveInviteStatus by remember { mutableStateOf("") }
    
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    val username = remember { sharedPrefs.getString("username_profile", "User Identity") ?: "User Identity" }
    var fingerprint by remember { mutableStateOf("Loading...") }
    LaunchedEffect(Unit) {
        while (!PythonBridge.isInitialized) {
            kotlinx.coroutines.delay(100)
        }
        fingerprint = PythonBridge.getLocalFingerprint()
    }

    LaunchedEffect(username, fingerprint) {
        if (username.isNotBlank() && username != "User Identity" && fingerprint != "Loading..." && fingerprint != "Not Initialized") {
            coroutineScope.launch(Dispatchers.IO) {
                PythonBridge.announceSelf(username, fingerprint, 50001)
            }
        }
    }
    
    val directoryContacts = listOf(
        ContactItem("Alina GE", "Active 3m ago", "AG"),
        ContactItem("Amanda Pri", "Offline", "AP"),
        ContactItem("Eleanor Vance", "Online", "EV"),
        ContactItem("Krinal GE", "Offline", "KG"),
        ContactItem("Liam O'Connor", "Online", "LO"),
        ContactItem("Mennako GE", "Online", "MG"),
        ContactItem("Pitto GE", "Active 2h ago", "PG"),
        ContactItem("Sarah Chen", "Online", "SC")
    )

    val filteredContacts = directoryContacts.filter {
        it.name.contains(searchQuery, ignoreCase = true)
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
                placeholder = { Text(Localizations.getString("search_placeholder", appLanguage), color = onSurfaceVariant.copy(alpha = 0.5f)) },
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
                    if (showInvitePanel) showDirectIpPanel = false
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

            // Direct IP Connection Panel Toggle Button
            IconButton(
                onClick = {
                    showDirectIpPanel = !showDirectIpPanel
                    if (showDirectIpPanel) showInvitePanel = false
                },
                modifier = Modifier
                    .size(52.dp)
                    .background(if (showDirectIpPanel) primaryColor else surfaceColor, shape = RoundedCornerShape(14.dp))
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            ) {
                Icon(
                    painter = painterResource(id = com.example.twopchat.R.drawable.ic_quick_ip),
                    contentDescription = "Direct IP Connection",
                    tint = if (showDirectIpPanel) StealthBlack else primaryColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            
            // Search Execute Button
            IconButton(
                onClick = {
                    if (searchQuery.isNotBlank()) {
                        val trimmed = searchQuery.trim()
                        if (trimmed.startsWith("2pchat://connect")) {
                            try {
                                val uri = android.net.Uri.parse(trimmed)
                                val parsedName = uri.getQueryParameter("name") ?: "Invited Peer"
                                val token = uri.getQueryParameter("token") ?: ""

                                val activeSet = sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")) ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")
                                if (!activeSet.contains(parsedName)) {
                                    val newSet = activeSet.toMutableSet()
                                    newSet.add(parsedName)
                                    sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                                    sharedPrefs.edit().putString("transport_$parsedName", "DIRECT P2P").apply()
                                }

                                if (token.isNotEmpty()) {
                                    isResolvingInvite = true
                                    resolveInviteStatus = if (appLanguage == "Русский") "Поиск собеседника..." else "Finding peer..."
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val peers = PythonBridge.searchPeers(token)
                                        val endpoints = if (peers.isNotEmpty()) peers[0]["endpoints"] as? List<*> else null
                                        // Pass ALL endpoints comma-separated so Python can try each (IPv4 first, Yggdrasil IPv6 as fallback)
                                        val endpointStr = if (endpoints != null && endpoints.isNotEmpty()) endpoints.joinToString(",") { it.toString() } else ""
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            isResolvingInvite = false
                                            if (endpointStr.isNotEmpty()) {
                                                com.example.twopchat.P2PMessageRelay.peerEndpoints[parsedName] = endpointStr
                                                resolveInviteStatus = ""
                                                onItemClick(Chat(parsedName))
                                            } else {
                                                resolveInviteStatus = if (appLanguage == "Русский") "Собеседник не найден. Попробуйте позже." else "Peer not found. They may be offline."
                                            }
                                        }
                                    }
                                } else {
                                    // No token — navigate directly if the link had no token (manual)
                                    onItemClick(Chat(parsedName))
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Invalid link", Toast.LENGTH_SHORT).show()
                            }
                            return@IconButton
                        }
                        isSearching = true
                        coroutineScope.launch(Dispatchers.IO) {
                            val peers = PythonBridge.searchPeers(searchQuery)
                            val items = peers.map { peer ->
                                val name = peer["nickname"] as? String ?: "Unknown"
                                val fp = peer["fingerprint"] as? String ?: ""
                                val endpoints = peer["endpoints"] as? List<*> ?: emptyList<Any>()
                                val endpointStr = if (endpoints.isNotEmpty()) {
                                    endpoints.joinToString(",") { it.toString() }
                                } else {
                                    "Unknown"
                                }
                                val displayName = if (name.startsWith("2TFcRb7m") || name.length > 20) {
                                    "Peer (" + name.take(8) + "...)"
                                } else {
                                    name
                                }
                                if (endpointStr != "Unknown") {
                                    com.example.twopchat.P2PMessageRelay.peerEndpoints[displayName] = endpointStr
                                }
                                ContactItem(
                                    name = displayName,
                                    status = "Online ($endpointStr)",
                                    initials = if (displayName.length >= 2) displayName.substring(0, 2).uppercase() else displayName.uppercase()
                                )
                            }
                            withContext(Dispatchers.Main) {
                                searchResults = items
                                isSearching = false
                            }
                        }
                    } else {
                        searchResults = emptyList()
                    }
                },
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
                                    PythonBridge.announceSelf(tokenVal, fingerprint, 50001)
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
                                val clipManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                
                                // Copy Link
                                IconButton(
                                    onClick = {
                                        clipManager.setText(androidx.compose.ui.text.AnnotatedString(inviteLinkState))
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
                                                            val activeSet = sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")) ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")
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

        // Expanded Direct IP Connection Card
        if (showDirectIpPanel) {
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
                        text = if (appLanguage == "Русский") "Прямое IP-подключение" else "Direct IP Connection",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (appLanguage == "Русский") "Подключение по локальному IP или Yggdrasil адресу без трекеров" else "Connect to a peer directly using their network address",
                        fontSize = 12.sp,
                        color = onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Nickname input (weight 1f)
                        OutlinedTextField(
                            value = directNameVal,
                            onValueChange = { directNameVal = it },
                            placeholder = { Text("Bob", fontSize = 12.sp, color = onSurfaceVariant.copy(alpha = 0.4f)) },
                            label = { Text(if (appLanguage == "Русский") "Имя" else "Name", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = onSurfaceColor.copy(alpha = 0.1f),
                                focusedLabelColor = primaryColor,
                                unfocusedLabelColor = onSurfaceVariant
                            )
                        )

                        // IP Address input (weight 1.5f)
                        OutlinedTextField(
                            value = directIpVal,
                            onValueChange = { directIpVal = it },
                            placeholder = { Text("192.168.1.100", fontSize = 12.sp, color = onSurfaceVariant.copy(alpha = 0.4f)) },
                            label = { Text("IP", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1.5f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = onSurfaceColor.copy(alpha = 0.1f),
                                focusedLabelColor = primaryColor,
                                unfocusedLabelColor = onSurfaceVariant
                            )
                        )

                        // Port input (weight 0.8f)
                        OutlinedTextField(
                            value = directPortVal,
                            onValueChange = { directPortVal = it },
                            placeholder = { Text("50001", fontSize = 12.sp, color = onSurfaceVariant.copy(alpha = 0.4f)) },
                            label = { Text(if (appLanguage == "Русский") "Порт" else "Port", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(0.8f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = onSurfaceColor.copy(alpha = 0.1f),
                                focusedLabelColor = primaryColor,
                                unfocusedLabelColor = onSurfaceVariant
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val ip = directIpVal.trim()
                            val port = directPortVal.trim()
                            val rawName = if (directNameVal.isNotBlank()) directNameVal.trim() else "Direct Peer"

                            if (ip.isEmpty() || port.isEmpty()) {
                                Toast.makeText(context, if (appLanguage == "Русский") "Введите IP и Порт" else "Please enter IP and Port", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            val portInt = port.toIntOrNull()
                            if (portInt == null || portInt !in 1..65535) {
                                Toast.makeText(context, if (appLanguage == "Русский") "Неверный порт (1-65535)" else "Invalid port (1-65535)", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            val name = rawName.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
                            if (name.isEmpty()) {
                                Toast.makeText(context, if (appLanguage == "Русский") "Неверное имя" else "Invalid name", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            val endpointStr = "$ip:$port"
                            com.example.twopchat.P2PMessageRelay.peerEndpoints[name] = endpointStr

                            // Add to active chats set
                            val activeSet = sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")) ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")
                            if (!activeSet.contains(name)) {
                                val newSet = activeSet.toMutableSet()
                                newSet.add(name)
                                sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                                sharedPrefs.edit().putString("transport_$name", "DIRECT P2P").apply()
                            }

                            // Send handshake ping to register ourselves on Bob's side
                            coroutineScope.launch(Dispatchers.IO) {
                                com.example.twopchat.P2PMessageRelay.sendMessage(context, endpointStr, username, "")
                            }

                            Toast.makeText(context, if (appLanguage == "Русский") "Подключение по IP..." else "Connecting via IP...", Toast.LENGTH_SHORT).show()
                            onItemClick(Chat(name))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Подключить по IP" else "Connect via IP",
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = primaryColor)
            }
        } else {
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
                        text = if (appLanguage == "ru") "Пользователи не найдены на DHT/трекерах" else "No peers found on DHT/trackers",
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
                                .clickable {
                                    val activeSet = sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")) ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")
                                    if (!activeSet.contains(contact.name)) {
                                        val newSet = activeSet.toMutableSet()
                                        newSet.add(contact.name)
                                        sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                                        val isYgg = contact.status.contains("Yggdrasil", ignoreCase = true)
                                        sharedPrefs.edit().putString("transport_${contact.name}", if (isYgg) "YGGDRASIL" else "DIRECT P2P").apply()
                                    }
                                    onItemClick(Chat(contact.name))
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
                                        color = if (contact.status.startsWith("Online")) primaryColor else onSurfaceVariant
                                    )
                                }
                                
                                Text(
                                    text = Localizations.getString("connect_action", appLanguage),
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
@Composable
fun SettingsTab(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    useCerulean: Boolean,
    onAccentChanged: (Boolean) -> Unit,
    activeIconAlias: String,
    onIconChanged: (String) -> Unit,
    appLanguage: String,
    onLanguageChanged: (String) -> Unit,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    surfaceVariant: Color,
    onDeleteAccount: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val sharedPrefs = remember { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    
    // Profile photo states
    var profilePhotoUri by remember { mutableStateOf(sharedPrefs.getString("profile_photo_uri", null)) }
    var profileBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(com.example.twopchat.ui.onboarding.loadBitmapFromUri(context, profilePhotoUri)) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            val localPath = com.example.twopchat.ui.onboarding.saveImageToInternalStorage(context, it)
            if (localPath != null) {
                profilePhotoUri = localPath
                sharedPrefs.edit().putString("profile_photo_uri", localPath).apply()
                profileBitmap = com.example.twopchat.ui.onboarding.loadBitmapFromUri(context, localPath)
                Toast.makeText(context, "Profile photo updated", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Dynamic settings states
    val username = remember { sharedPrefs.getString("username_profile", "User Identity") ?: "User Identity" }
    var notificationsEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("settings_notifications", true)) }
    var previewsEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("settings_previews", true)) }
    var blockScreenshots by remember { mutableStateOf(sharedPrefs.getBoolean("settings_screenshots", true)) }
    var passcodeLock by remember { mutableStateOf(sharedPrefs.getBoolean("settings_passcode", false)) }
    var wifiDiscovery by remember { mutableStateOf(sharedPrefs.getBoolean("settings_wifi", true)) }
    var yggdrasilRouting by remember { mutableStateOf(sharedPrefs.getBoolean("settings_yggdrasil", false)) }
    var persistChatHistory by remember { mutableStateOf(sharedPrefs.getBoolean("persist_chat_history", true)) }
    var stealthDisguise by remember { mutableStateOf(sharedPrefs.getBoolean("settings_stealth_disguise", false)) }
    var showDisguiseInstructionDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        yggdrasilRouting = sharedPrefs.getBoolean("settings_yggdrasil", false)
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val intent = Intent(context, PacketTunnelProvider::class.java).apply {
                    action = PacketTunnelProvider.ACTION_START
                }
                context.startService(intent)
                yggdrasilRouting = true
                sharedPrefs.edit().putBoolean("settings_yggdrasil", true).apply()
            } else {
                yggdrasilRouting = false
                sharedPrefs.edit().putBoolean("settings_yggdrasil", false).apply()
            }
        }
    )
    
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    
    // Passcode dialog flow states
    var showSetPasscodeDialog by remember { mutableStateOf(false) }
    var showDisablePasscodeDialog by remember { mutableStateOf(false) }
    var showAutolockDialog by remember { mutableStateOf(false) }
    var autolockMinutes by remember { mutableStateOf(sharedPrefs.getInt("passcode_autolock_minutes", 1)) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showSetDuressDialog by remember { mutableStateOf(false) }
    var showLauncherIconsPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(scrollState)
    ) {
        // Visual Profile Card with interactive photo selector
        Card(
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Profile Photo container (clickable)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(primaryColor.copy(alpha = 0.15f), shape = CircleShape)
                        .border(1.dp, primaryColor, CircleShape)
                        .clickable { imagePickerLauncher.launch("image/*") }
                ) {
                    if (profileBitmap != null) {
                        Image(
                            bitmap = profileBitmap!!.asImageBitmap(),
                            contentDescription = "Profile Photo",
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = com.example.twopchat.R.drawable.ic_add_photo_smiley),
                            contentDescription = "Edit Photo",
                            tint = primaryColor,
                            modifier = Modifier
                                .size(28.dp)
                                .align(Alignment.Center)
                        )
                        Text(
                            text = Localizations.getString("edit_photo", appLanguage),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 4.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column {
                    Text(
                        text = Localizations.getString("username_profile", appLanguage),
                        fontSize = 13.sp,
                        color = onSurfaceVariant
                    )
                    Text(
                        text = username,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Appearance Settings Card
        Text(
            text = Localizations.getString("appearance", appLanguage),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceColor,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Light Mode Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Localizations.getString("light_theme", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                        Text(Localizations.getString("light_theme_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = !isDarkTheme,
                        onCheckedChange = { light -> onThemeChanged(!light) },
                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                    )
                }
                
                Divider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                // Cerulean Accent Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Localizations.getString("cerulean_blue", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                        Text(Localizations.getString("cerulean_blue_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = useCerulean,
                        onCheckedChange = onAccentChanged,
                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                // Expandable Launcher Icons Picker Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLauncherIconsPicker = !showLauncherIconsPicker }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Localizations.getString("premium_icons", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                        Text(Localizations.getString("select_icons_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val activeIconLabel = when (activeIconAlias) {
                            "MainActivityAliasBlue" -> "Cerulean Blue"
                            "MainActivityAliasNoir" -> "Noir Luxury"
                            "MainActivityAliasNeon" -> "Neon Bright"
                            else -> "Mint Classic"
                        }
                        Text(text = activeIconLabel, color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = if (showLauncherIconsPicker) "▼" else "❯", fontSize = 12.sp, color = onSurfaceVariant)
                    }
                }

                androidx.compose.animation.AnimatedVisibility(visible = showLauncherIconsPicker) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        val iconOptions = listOf(
                            AppIconOption("MainActivityAliasDefault", "Mint Classic", StealthBlack, MintGreen, "Dark/Mint", com.example.twopchat.R.drawable.ic_logo_default_fg),
                            AppIconOption("MainActivityAliasBlue", "Cerulean Blue", CeruleanBlue, Color.White, "Cerulean", com.example.twopchat.R.drawable.ic_logo_blue_fg),
                            AppIconOption("MainActivityAliasNoir", "Noir Luxury", Onyx, ChampagneGold, "Charcoal/Gold", com.example.twopchat.R.drawable.ic_logo_noir_fg),
                            AppIconOption("MainActivityAliasNeon", "Neon Bright", Color.White, NeonPurple, "Light/Violet", com.example.twopchat.R.drawable.ic_logo_neon_fg)
                        )

                        iconOptions.forEach { option ->
                            val isSelected = activeIconAlias == option.alias
                            Card(
                                colors = CardDefaults.cardColors(containerColor = if (isSelected) primaryColor.copy(alpha = 0.08f) else surfaceColor),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (activeIconAlias != option.alias) {
                                            onIconChanged(option.alias)
                                            Toast.makeText(context, "${option.name} Launcher Icon Selected! Launchers rotate on next restart.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.5.dp,
                                        color = if (isSelected) primaryColor else onSurfaceColor.copy(alpha = 0.05f),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Icon Preview box
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(46.dp)
                                                .background(option.bg, shape = RoundedCornerShape(10.dp))
                                                .border(1.dp, option.fg.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        ) {
                                            Image(
                                                painter = painterResource(id = option.fgRes),
                                                contentDescription = option.name,
                                                modifier = Modifier.size(30.dp),
                                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(option.fg)
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.width(14.dp))
                                        
                                        Column {
                                            Text(
                                                text = option.name,
                                                fontWeight = FontWeight.SemiBold,
                                                color = onSurfaceColor
                                            )
                                            Text(
                                                text = option.styleDesc,
                                                fontSize = 11.sp,
                                                color = onSurfaceVariant
                                            )
                                        }
                                    }
                                    
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            if (activeIconAlias != option.alias) {
                                                onIconChanged(option.alias)
                                                Toast.makeText(context, "${option.name} Launcher Icon Selected! Launchers rotate on next restart.", Toast.LENGTH_LONG).show()
                                            }
                                        },
                                        colors = RadioButtonDefaults.colors(selectedColor = primaryColor)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Notifications Settings Card
        Text(
            text = Localizations.getString("notifications", appLanguage),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceColor,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Localizations.getString("push_notifications", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                        Text(Localizations.getString("push_notifications_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = {
                            notificationsEnabled = it
                            sharedPrefs.edit().putBoolean("settings_notifications", it).apply()
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Localizations.getString("message_previews", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                        Text(Localizations.getString("message_previews_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = previewsEnabled,
                        onCheckedChange = {
                            previewsEnabled = it
                            sharedPrefs.edit().putBoolean("settings_previews", it).apply()
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Security & Network Settings Card
        Text(
            text = Localizations.getString("security_network", appLanguage),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceColor,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Stealth Disguise Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Localizations.getString("stealth_disguise", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                        Text(Localizations.getString("stealth_disguise_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = stealthDisguise,
                        onCheckedChange = { checked ->
                            stealthDisguise = checked
                            sharedPrefs.edit().putBoolean("settings_stealth_disguise", checked).apply()
                            if (checked) {
                                onIconChanged("MainActivityAliasCurrency")
                                showDisguiseInstructionDialog = true
                            } else {
                                onIconChanged(activeIconAlias)
                                Toast.makeText(context, if (appLanguage == "Русский") "Маскировка выключена." else "Disguise inactive.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                // Screenshot blocking
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Localizations.getString("block_screenshots", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                        Text(Localizations.getString("block_screenshots_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = blockScreenshots,
                        onCheckedChange = {
                            blockScreenshots = it
                            sharedPrefs.edit().putBoolean("settings_screenshots", it).apply()
                            val activity = context as? android.app.Activity
                            activity?.let { act ->
                                if (it) {
                                    act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                                } else {
                                    act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                // Passcode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Localizations.getString("passcode_lock", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                        Text(Localizations.getString("passcode_lock_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = passcodeLock,
                        onCheckedChange = { checked ->
                            if (checked) {
                                showSetPasscodeDialog = true
                            } else {
                                showDisablePasscodeDialog = true
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                    )
                }

                if (passcodeLock) {
                    Divider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAutolockDialog = true }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(Localizations.getString("autolock_title", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                            Text(Localizations.getString("autolock_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        val autolockLabel = when (autolockMinutes) {
                            1 -> Localizations.getString("minutes_1", appLanguage)
                            5 -> Localizations.getString("minutes_5", appLanguage)
                            10 -> Localizations.getString("minutes_10", appLanguage)
                            30 -> Localizations.getString("minutes_30", appLanguage)
                            else -> "${autolockMinutes}m"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = autolockLabel, color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "❯", fontSize = 12.sp, color = onSurfaceVariant)
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSetDuressDialog = true }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(Localizations.getString("duress_pin_title", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                            Text(Localizations.getString("duress_pin_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        val duressPinValue = sharedPrefs.getString("passcode_duress_value", "") ?: ""
                        val duressSet = duressPinValue.isNotEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (duressSet) Localizations.getString("enabled", appLanguage) else Localizations.getString("disabled", appLanguage),
                                color = if (duressSet) primaryColor else onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "❯", fontSize = 12.sp, color = onSurfaceVariant)
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                // Direct WiFi discovery
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Localizations.getString("wifi_discovery", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                        Text(Localizations.getString("wifi_discovery_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = wifiDiscovery,
                        onCheckedChange = {
                            wifiDiscovery = it
                            sharedPrefs.edit().putBoolean("settings_wifi", it).apply()
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                // Yggdrasil
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Localizations.getString("yggdrasil_routing", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                        Text(Localizations.getString("yggdrasil_routing_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = yggdrasilRouting,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                val vpnIntent = VpnService.prepare(context)
                                if (vpnIntent != null) {
                                    vpnLauncher.launch(vpnIntent)
                                } else {
                                    val intent = Intent(context, PacketTunnelProvider::class.java).apply {
                                        action = PacketTunnelProvider.ACTION_START
                                    }
                                    context.startService(intent)
                                    yggdrasilRouting = true
                                    sharedPrefs.edit().putBoolean("settings_yggdrasil", true).apply()
                                }
                            } else {
                                val intent = Intent(context, PacketTunnelProvider::class.java).apply {
                                    action = PacketTunnelProvider.ACTION_STOP
                                }
                                context.startService(intent)
                                yggdrasilRouting = false
                                sharedPrefs.edit().putBoolean("settings_yggdrasil", false).apply()
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = primaryColor, checkedTrackColor = primaryColor.copy(alpha = 0.3f))
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Message History / RAM Mode Settings Card
        Card(
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (appLanguage == "Русский") "Сохранять историю переписок" else "Persist Chat History",
                            fontWeight = FontWeight.Medium,
                            color = onSurfaceColor
                        )
                        Text(
                            text = if (appLanguage == "Русский") {
                                "Если выключено, сообщения будут находиться только в ОЗУ (стираться при выходе из диалога)"
                            } else {
                                "If disabled, messages reside strictly in RAM and clear when exiting the chat"
                            },
                            fontSize = 12.sp,
                            color = onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = persistChatHistory,
                        onCheckedChange = {
                            persistChatHistory = it
                            sharedPrefs.edit().putBoolean("persist_chat_history", it).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = primaryColor,
                            checkedTrackColor = primaryColor.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Language Settings Card
        Text(
            text = Localizations.getString("language", appLanguage),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceColor,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showLanguageDialog = true }
                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(Localizations.getString("app_language", appLanguage), fontWeight = FontWeight.Medium, color = onSurfaceColor)
                    Text(Localizations.getString("app_language_desc", appLanguage), fontSize = 12.sp, color = onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = appLanguage, color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "❯", fontSize = 12.sp, color = onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        // Delete Account Warning Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDeleteAccountDialog = true }
                .border(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = Localizations.getString("delete_account", appLanguage),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Developer Options Section (grouped)
        Text(
            text = if (appLanguage == "Русский") "Настройки разработчика" else "Developer Options",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = onSurfaceColor,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Network Diagnostics & Logs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLogsDialog = true }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (appLanguage == "Русский") "Сетевой отладчик и Логи" else "Network Diagnostics & Logs",
                            fontWeight = FontWeight.Medium,
                            color = onSurfaceColor
                        )
                        Text(
                            text = if (appLanguage == "Русский") {
                                "Просмотр системного лога работы P2P и сетевого статуса"
                            } else {
                                "View system P2P logs and network connection diagnostic status"
                            },
                            fontSize = 12.sp,
                            color = onSurfaceVariant
                        )
                    }
                    Text(text = "❯", fontSize = 12.sp, color = onSurfaceVariant)
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp), color = onSurfaceColor.copy(alpha = 0.05f))

                // Export/Share App Logs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // Export/Share log file
                            val logFile = java.io.File(java.io.File(context.filesDir, "config"), "app.log")
                            if (logFile.exists() && logFile.length() > 0) {
                                try {
                                    val authority = "${context.packageName}.fileprovider"
                                    val fileUri: android.net.Uri = androidx.core.content.FileProvider.getUriForFile(context, authority, logFile)
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(shareIntent, if (appLanguage == "Русский") "Поделиться логами" else "Share Logs"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error sharing logs: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, if (appLanguage == "Русский") "Лог-файл пуст или еще не создан" else "Log file is empty or not created yet", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (appLanguage == "Русский") "Экспорт логов приложения" else "Export App Logs", fontWeight = FontWeight.Medium, color = onSurfaceColor)
                        Text(if (appLanguage == "Русский") "Поделиться файлом app.log" else "Share the app.log file", fontSize = 12.sp, color = onSurfaceVariant)
                    }
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_share),
                        contentDescription = "Share",
                        tint = primaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
    }

    // Language Selector dialog
    if (showLanguageDialog) {
        val languages = listOf("English", "Русский")
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(Localizations.getString("close", appLanguage), color = primaryColor)
                }
            },
            title = { Text(Localizations.getString("app_language", appLanguage), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = onSurfaceColor) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    languages.forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onLanguageChanged(lang)
                                    showLanguageDialog = false
                                    Toast.makeText(context, "Language changed to $lang", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = lang, fontSize = 15.sp, color = onSurfaceColor)
                            if (lang == appLanguage) {
                                Text(text = "✓", color = primaryColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showLogsDialog) {
        var logsText by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            logsText = readLogFile(context)
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showLogsDialog = false }
        ) {
            val dialogScrollState = rememberScrollState()
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
                    .border(0.5.dp, primaryColor.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(dialogScrollState)
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
                                onClick = { logsText = readLogFile(context) },
                                modifier = Modifier.size(36.dp).background(onSurfaceColor.copy(alpha = 0.04f), shape = CircleShape)
                            ) {
                                Text("↻", fontSize = 16.sp, color = primaryColor, fontWeight = FontWeight.Bold)
                            }
                            // Clear
                            IconButton(
                                onClick = {
                                    clearLogFile(context)
                                    logsText = readLogFile(context)
                                },
                                modifier = Modifier.size(36.dp).background(onSurfaceColor.copy(alpha = 0.04f), shape = CircleShape)
                            ) {
                                Text("🗑", fontSize = 16.sp, color = Color.Red)
                            }
                            // Share
                            IconButton(
                                onClick = { shareLogFile(context) },
                                modifier = Modifier.size(36.dp).background(onSurfaceColor.copy(alpha = 0.04f), shape = CircleShape)
                            ) {
                                Icon(
                                    painter = painterResource(id = android.R.drawable.ic_menu_share),
                                    contentDescription = "Share",
                                    tint = primaryColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

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
                                    text = "50001 (listening)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            val announcedEndpoints = com.example.twopchat.PythonBridge.getLocalAddresses().map { host ->
                                when {
                                    host.contains(':') -> "[$host]:50001"
                                    host == "10.0.2.16" -> "$host:50001 (emulator local)"
                                    else -> "$host:50001"
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
                            val publicTrackerIpv4 = com.example.twopchat.P2PMessageRelay.peerEndpoints
                                .values
                                .flatMap { endpointCsv -> endpointCsv.split(",") }
                                .map { it.trim() }
                                .filter { endpoint -> endpoint.isNotEmpty() && !endpoint.startsWith("[") }
                                .mapNotNull { endpoint ->
                                    val host = endpoint.substringBeforeLast(":", "")
                                    if (host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) && host != "10.0.2.16") host else null
                                }
                                .distinct()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") "Публичный IPv4 по данным трекеров:" else "Public IPv4 seen by trackers:",
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
                            val yggTrackerDiagnostics = com.example.twopchat.PythonBridge
                                .getTrackerDiagnostics()
                                .filterKeys { it.contains("Yggdrasil", ignoreCase = true) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") "Ygg tracker status:" else "Ygg tracker status:",
                                    fontSize = 13.sp,
                                    color = onSurfaceColor
                                )
                                Text(
                                    text = "${yggTrackerDiagnostics.size}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor
                                )
                            }
                            if (yggTrackerDiagnostics.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(onSurfaceColor.copy(alpha = 0.02f), shape = RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    yggTrackerDiagnostics.forEach { (trackerName, status) ->
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
                                val ipv4List = com.example.twopchat.PythonBridge.getLocalAddresses().filter { !it.contains(':') }
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
                                val yggList = com.example.twopchat.PythonBridge.getLocalAddresses().filter { it.contains(':') }
                                Text(
                                    text = if (yggList.isNotEmpty()) yggList.joinToString(", ") else (if (appLanguage == "Русский") "Не обнаружен" else "Not detected"),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (yggList.isNotEmpty()) Color(0xFF4CAF50) else Color.Red
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
                            .background(Color(0xFF070809), shape = RoundedCornerShape(12.dp))
                            .border(0.5.dp, Color(0xFF39FF14).copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        val scrollState = rememberScrollState()
                        LaunchedEffect(logsText) {
                            scrollState.scrollTo(scrollState.maxValue)
                        }
                        
                        SelectionContainer {
                            Text(
                                text = logsText,
                                color = Color(0xFF39FF14),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Close Button
                    Button(
                        onClick = { showLogsDialog = false },
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
            }
        }
    }

    if (showDisguiseInstructionDialog) {
        AlertDialog(
            onDismissRequest = { showDisguiseInstructionDialog = false },
            title = {
                Text(
                    text = if (appLanguage == "Русский") "Режим маскировки включен" else "Stealth Disguise Activated",
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Text(
                    text = if (appLanguage == "Русский") {
                        "Чтобы войти в 2PChat в будущем:\n\n" +
                        "1. Введите в поле ввода суммы конвертера ровно 777 или 2002.\n\n" +
                        "2. Либо быстро нажмите 3 раза на заголовок «Курсы валют» вверху экрана."
                    } else {
                        "To enter 2PChat in the future:\n\n" +
                        "1. Enter exactly 777 or 2002 in the converter amount field.\n\n" +
                        "2. Or tap the top title \"Currency Rates\" 3 times quickly."
                    },
                    fontSize = 14.sp,
                    color = onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showDisguiseInstructionDialog = false }) {
                    Text(text = if (appLanguage == "Русский") "Понятно" else "Understood", color = primaryColor)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Passcode Setup Dialog Flow
    if (showSetPasscodeDialog) {
        var pin1 by remember { mutableStateOf("") }
        var pin2 by remember { mutableStateOf("") }
        var isConfirming by remember { mutableStateOf(false) }
        var pinError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showSetPasscodeDialog = false },
            title = {
                Text(
                    text = Localizations.getString("set_passcode_title", appLanguage),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isConfirming) {
                            Localizations.getString("confirm_passcode", appLanguage)
                        } else {
                            Localizations.getString("enter_passcode", appLanguage)
                        },
                        fontSize = 14.sp,
                        color = onSurfaceVariant
                    )
                    
                    TextField(
                        value = if (isConfirming) pin2 else pin1,
                        onValueChange = { input ->
                            if (input.length <= 4 && input.all { it.isDigit() }) {
                                if (isConfirming) pin2 = input else pin1 = input
                                pinError = false
                            }
                        },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = surfaceVariant,
                            unfocusedContainerColor = surfaceVariant,
                            focusedTextColor = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor,
                            focusedIndicatorColor = primaryColor
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (pinError) {
                        Text(
                            text = Localizations.getString("passcodes_dont_match", appLanguage),
                            color = Color.Red,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!isConfirming) {
                            if (pin1.length == 4) {
                                isConfirming = true
                            }
                        } else {
                            if (pin1 == pin2) {
                                sharedPrefs.edit()
                                    .putString("passcode_value", com.example.twopchat.SecurityUtils.hashPasscode(pin1))
                                    .putBoolean("settings_passcode", true)
                                    .apply()
                                passcodeLock = true
                                showSetPasscodeDialog = false
                                Toast.makeText(context, Localizations.getString("passcode_enabled", appLanguage), Toast.LENGTH_SHORT).show()
                            } else {
                                pinError = true
                                pin2 = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = if (primaryColor == MintGreen) StealthBlack else Color.White
                    ),
                    enabled = if (isConfirming) pin2.length == 4 else pin1.length == 4
                ) {
                    Text(Localizations.getString("continue", appLanguage))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSetPasscodeDialog = false }) {
                    Text(Localizations.getString("close", appLanguage), color = primaryColor)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Passcode Disable Dialog Flow (requires security verification)
    if (showDisablePasscodeDialog) {
        var enteredPin by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showDisablePasscodeDialog = false },
            title = {
                Text(
                    text = Localizations.getString("disable_passcode_title", appLanguage),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = Localizations.getString("enter_current_passcode", appLanguage),
                        fontSize = 14.sp,
                        color = onSurfaceVariant
                    )
                    
                    TextField(
                        value = enteredPin,
                        onValueChange = { input ->
                            if (input.length <= 4 && input.all { it.isDigit() }) {
                                enteredPin = input
                                pinError = false
                            }
                        },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = surfaceVariant,
                            unfocusedContainerColor = surfaceVariant,
                            focusedTextColor = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor,
                            focusedIndicatorColor = primaryColor
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (pinError) {
                        Text(
                            text = Localizations.getString("invalid_passcode", appLanguage),
                            color = Color.Red,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val correctPin = sharedPrefs.getString("passcode_value", "") ?: ""
                        if (com.example.twopchat.SecurityUtils.verifyAndMigratePasscode(enteredPin, correctPin, sharedPrefs, "passcode_value")) {
                            sharedPrefs.edit()
                                .putBoolean("settings_passcode", false)
                                .remove("passcode_value")
                                .apply()
                            passcodeLock = false
                            showDisablePasscodeDialog = false
                            Toast.makeText(context, Localizations.getString("passcode_disabled", appLanguage), Toast.LENGTH_SHORT).show()
                        } else {
                            pinError = true
                            enteredPin = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = if (primaryColor == MintGreen) StealthBlack else Color.White
                    ),
                    enabled = enteredPin.length == 4
                ) {
                    Text(Localizations.getString("enter", appLanguage))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisablePasscodeDialog = false }) {
                    Text(Localizations.getString("close", appLanguage), color = primaryColor)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Inactivity Auto-lock Selector Dialog
    if (showAutolockDialog) {
        val options = listOf(1, 5, 10, 30)
        AlertDialog(
            onDismissRequest = { showAutolockDialog = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAutolockDialog = false }) {
                    Text(Localizations.getString("close", appLanguage), color = primaryColor)
                }
            },
            title = {
                Text(
                    text = Localizations.getString("autolock_title", appLanguage),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { minutes ->
                        val label = when (minutes) {
                            1 -> Localizations.getString("minutes_1", appLanguage)
                            5 -> Localizations.getString("minutes_5", appLanguage)
                            10 -> Localizations.getString("minutes_10", appLanguage)
                            30 -> Localizations.getString("minutes_30", appLanguage)
                            else -> "$minutes m"
                        }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    autolockMinutes = minutes
                                    sharedPrefs.edit().putInt("passcode_autolock_minutes", minutes).apply()
                                    showAutolockDialog = false
                                    Toast.makeText(context, "Auto-lock timeout set to $label", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = label, fontSize = 15.sp, color = onSurfaceColor)
                            if (minutes == autolockMinutes) {
                                Text(text = "✓", color = primaryColor, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Delete Account Confirmation Dialog
    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            title = {
                Text(
                    text = Localizations.getString("delete_account_title", appLanguage),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Text(
                    text = Localizations.getString("delete_account_desc", appLanguage),
                    fontSize = 14.sp,
                    color = onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAccountDialog = false
                        onDeleteAccount()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Text(Localizations.getString("delete", appLanguage))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) {
                    Text(Localizations.getString("cancel", appLanguage), color = primaryColor)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Set Duress PIN Dialog Flow
    if (showSetDuressDialog) {
        var duressPin1 by remember { mutableStateOf("") }
        var duressPin2 by remember { mutableStateOf("") }
        var isDuressConfirming by remember { mutableStateOf(false) }
        var duressPinError by remember { mutableStateOf(false) }
        var duressMatchesMainError by remember { mutableStateOf(false) }

        val mainPinVal = sharedPrefs.getString("passcode_value", "") ?: ""

        AlertDialog(
            onDismissRequest = { showSetDuressDialog = false },
            title = {
                Text(
                    text = Localizations.getString("set_duress_title", appLanguage),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isDuressConfirming) {
                            Localizations.getString("confirm_duress_pin", appLanguage)
                        } else {
                            Localizations.getString("enter_duress_pin", appLanguage)
                        },
                        fontSize = 14.sp,
                        color = onSurfaceVariant
                    )
                    
                    TextField(
                        value = if (isDuressConfirming) duressPin2 else duressPin1,
                        onValueChange = { input ->
                            if (input.length <= 4 && input.all { it.isDigit() }) {
                                if (isDuressConfirming) duressPin2 = input else duressPin1 = input
                                duressPinError = false
                                duressMatchesMainError = false
                            }
                        },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = surfaceVariant,
                            unfocusedContainerColor = surfaceVariant,
                            focusedTextColor = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor,
                            focusedIndicatorColor = primaryColor
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (duressPinError) {
                        Text(
                            text = Localizations.getString("passcodes_dont_match", appLanguage),
                            color = Color.Red,
                            fontSize = 12.sp
                        )
                    }
                    if (duressMatchesMainError) {
                        Text(
                            text = Localizations.getString("duress_matches_main_error", appLanguage),
                            color = Color.Red,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!isDuressConfirming) {
                            if (duressPin1.length == 4) {
                                if (com.example.twopchat.SecurityUtils.hashPasscode(duressPin1) == mainPinVal) {
                                    duressMatchesMainError = true
                                    duressPin1 = ""
                                } else {
                                    isDuressConfirming = true
                                }
                            }
                        } else {
                            if (duressPin1 == duressPin2) {
                                sharedPrefs.edit()
                                    .putString("passcode_duress_value", com.example.twopchat.SecurityUtils.hashPasscode(duressPin1))
                                    .apply()
                                showSetDuressDialog = false
                                Toast.makeText(context, Localizations.getString("duress_enabled", appLanguage), Toast.LENGTH_SHORT).show()
                            } else {
                                duressPinError = true
                                duressPin2 = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = if (primaryColor == MintGreen) StealthBlack else Color.White
                    ),
                    enabled = if (isDuressConfirming) duressPin2.length == 4 else duressPin1.length == 4
                ) {
                    Text(Localizations.getString("continue", appLanguage))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    sharedPrefs.edit().remove("passcode_duress_value").apply()
                    showSetDuressDialog = false
                    Toast.makeText(context, Localizations.getString("duress_disabled", appLanguage), Toast.LENGTH_SHORT).show()
                }) {
                    Text(Localizations.getString("disable", appLanguage), color = Color.Red)
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

// Bottom Tab Navigation Bar Helper Composable
@Composable
fun TabNavigationRow(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color
) {
    NavigationBar(
        containerColor = surfaceColor,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .border(width = 0.5.dp, color = onSurfaceColor.copy(alpha = 0.05f))
    ) {
        val tabs = listOf(
            NavigationTabItem(Localizations.getString("tab_chats", appLanguage), com.example.twopchat.R.drawable.ic_menu_chats),
            NavigationTabItem(Localizations.getString("tab_contacts", appLanguage), com.example.twopchat.R.drawable.ic_menu_search),
            NavigationTabItem(Localizations.getString("tab_settings", appLanguage), com.example.twopchat.R.drawable.ic_menu_settings)
        )

        tabs.forEachIndexed { index, tab ->
            val isSelected = selectedTab == index
            val iconScale by animateFloatAsState(
                targetValue = if (isSelected) 1.18f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "scale"
            )
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(index) },
                icon = {
                    Icon(
                        painter = painterResource(id = tab.iconRes),
                        contentDescription = tab.label,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer(scaleX = iconScale, scaleY = iconScale)
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) primaryColor else Color.Gray
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = primaryColor.copy(alpha = 0.12f),
                    selectedIconColor = primaryColor,
                    unselectedIconColor = onSurfaceColor.copy(alpha = 0.4f)
                )
            )
        }
    }
}

// Data models
data class PeerItem(
    val name: String,
    val lastMsg: String,
    val transport: String,
    val isDirect: Boolean,
    val initials: String
)

data class ContactItem(
    val name: String,
    val status: String,
    val initials: String
)

data class AppIconOption(
    val alias: String,
    val name: String,
    val bg: Color,
    val fg: Color,
    val styleDesc: String,
    val fgRes: Int
)

data class NavigationTabItem(
    val label: String,
    val iconRes: Int
)

@Composable
fun PeerRow(
    peer: PeerItem,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    val isVerified = remember(peer.name) { sharedPrefs.getBoolean("verified_peer_${peer.name}", false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.04f), RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // Avatar representation
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .background(primaryColor.copy(alpha = 0.1f), shape = CircleShape)
                ) {
                    if (peer.initials == "🔖") {
                        Icon(
                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_saved_messages),
                            contentDescription = "Saved Messages",
                            tint = primaryColor,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Text(
                            text = peer.initials,
                            color = primaryColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = peer.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = peer.lastMsg,
                        fontSize = 12.sp,
                        color = onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))

            // Transport Badge (Quiet Luxury design)
            val badgeBg = if (peer.isDirect) primaryColor.copy(alpha = 0.1f) else onSurfaceColor.copy(alpha = 0.05f)
            val badgeFg = if (peer.isDirect) primaryColor else onSurfaceVariant
            
            val localizedTransport = if (peer.transport == "LOCAL RAM") {
                Localizations.getString("local_storage", appLanguage)
            } else if (peer.isDirect) {
                Localizations.getString("direct_p2p", appLanguage)
            } else {
                Localizations.getString("yggdrasil", appLanguage)
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (peer.name != "Saved Messages") {
                    val isMismatch = sharedPrefs.getBoolean("fingerprint_mismatch_${peer.name}", false)
                    val shieldColor = when {
                        isMismatch -> Color(0xFFF44336) // Red
                        isVerified -> Color(0xFF4CAF50) // Green
                        else -> Color(0xFFFFC107) // Yellow
                    }
                    Icon(
                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_shield_status),
                        contentDescription = "Security Status",
                        tint = shieldColor,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(13.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .background(badgeBg, shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = localizedTransport,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeFg,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
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
