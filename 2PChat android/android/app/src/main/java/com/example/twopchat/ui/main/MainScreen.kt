package com.example.twopchat.ui.main

import android.widget.Toast
import android.content.Intent
import android.net.VpnService
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import org.json.JSONArray
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
import com.example.twopchat.P2PMessageRelay
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
    var showLogsDialog by remember { mutableStateOf(false) }
    var showRadarView by remember { mutableStateOf(true) }
    var selectedRadarNode by remember { mutableStateOf<RadarNode?>(null) }
    LaunchedEffect(Unit) {
        while (!PythonBridge.isInitialized) {
            kotlinx.coroutines.delay(100)
        }
        localFingerprint = withContext(Dispatchers.IO) { PythonBridge.getLocalFingerprint() }
    }
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    var activeIconAlias by remember { mutableStateOf(sharedPrefs.getString("active_icon_alias", "MainActivityAliasDefault") ?: "MainActivityAliasDefault") }

    var mainActiveChatsSet by remember {
        mutableStateOf(sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet())
    }
    var totalUnreadCount by remember {
        mutableStateOf(mainActiveChatsSet.sumOf { sharedPrefs.getInt("unread_count_$it", 0) })
    }

    DisposableEffect(sharedPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "active_chats" || key?.startsWith("unread_count_") == true) {
                val chats = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                mainActiveChatsSet = chats
                totalUnreadCount = chats.sumOf { sharedPrefs.getInt("unread_count_$it", 0) }
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

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
                    0 -> ChatsTab(
                        onItemClick = onItemClick,
                        localFingerprint = localFingerprint,
                        appLanguage = appLanguage,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor,
                        onSurfaceVariant = onSurfaceVariant,
                        onStatusPillClick = { node ->
                            selectedRadarNode = node
                            showRadarView = true
                            showLogsDialog = true
                        }
                    )
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
                        onDeleteAccount = onDeleteAccount,
                        onShowLogs = { showLogsDialog = true }
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
            onSurfaceColor = onSurfaceColor,
            unreadCount = totalUnreadCount
        )
    }

    NetworkDiagnosticsDialog(
        showLogsDialog = showLogsDialog,
        onDismissRequest = { showLogsDialog = false },
        showRadarView = showRadarView,
        onShowRadarViewChange = { showRadarView = it },
        selectedRadarNode = selectedRadarNode,
        onSelectedRadarNodeChange = { selectedRadarNode = it },
        appLanguage = appLanguage,
        primaryColor = primaryColor,
        surfaceColor = surfaceColor,
        onSurfaceColor = onSurfaceColor,
        onSurfaceVariant = onSurfaceVariant,
        surfaceVariant = surfaceVariant,
        sharedPrefs = sharedPrefs
    )
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
    onSurfaceVariant: Color,
    onStatusPillClick: (RadarNode) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    
    val sharedPrefs = remember(context) { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    var activeChatsSet by remember {
        mutableStateOf(sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet())
    }
    
    androidx.compose.runtime.DisposableEffect(sharedPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "active_chats" || key?.startsWith("last_msg_") == true || key?.startsWith("transport_") == true || key?.startsWith("unread_count_") == true) {
                activeChatsSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val mockPeers = remember(activeChatsSet) {
        activeChatsSet.map { name ->
            val lastMsg = com.example.twopchat.SecureStorage.decrypt(sharedPrefs.getString("last_msg_$name", null)) ?: when(name) {
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
                initials = if (name.length >= 2) name.substring(0, 2).uppercase() else name.uppercase(),
                unreadCount = sharedPrefs.getInt("unread_count_$name", 0)
            )
        }
    }

    // Hero Card live state
    val currentUsername = remember { sharedPrefs.getString("username_profile", "Anonymous") ?: "Anonymous" }
    var heroActivePeers by remember { mutableStateOf(0) }
    var heroUpnpOk by remember { mutableStateOf<Boolean?>(null) }
    var heroTrackersOk by remember { mutableStateOf<Boolean?>(null) }
    var heroYggOk by remember { mutableStateOf<Boolean?>(null) }
    var heroInviteLink by remember { mutableStateOf("") }
    var heroInviteGenerating by remember { mutableStateOf(false) }
    val heroScope = rememberCoroutineScope()

    // Pulsing animations
    val infiniteTransition = rememberInfiniteTransition(label = "heroRing")
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ringAlpha"
    )

    val warningTransition = rememberInfiniteTransition(label = "warningPulse")
    val warningAlpha by warningTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "warningAlpha"
    )

    LaunchedEffect(Unit) {
        while (true) {
            if (PythonBridge.isInitialized) {
                heroActivePeers = P2PMessageRelay.peerSessionStates.count { it.value == true }
                heroUpnpOk = PythonBridge.isUpnpMapped()
                val trackers = PythonBridge.getTrackerDiagnostics()
                heroTrackersOk = trackers.isNotEmpty() && trackers.values.any {
                    it.contains("announce=ok", ignoreCase = true)
                }
                val yggAddr = PythonBridge.getYggdrasilAddress()
                heroYggOk = yggAddr.isNotBlank() && yggAddr != "N/A" && yggAddr != "unavailable"
            }
            kotlinx.coroutines.delay(4000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ─── Hero Identity Card ────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.10f),
                            surfaceColor.copy(alpha = 0.90f),
                            primaryColor.copy(alpha = 0.04f)
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .border(0.5.dp, primaryColor.copy(alpha = 0.20f), RoundedCornerShape(24.dp))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {

                // Top row: avatar + name + share button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Pulsing avatar
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                    ) {
                        Box(modifier = Modifier.size(56.dp).background(primaryColor.copy(alpha = ringAlpha * 0.25f), CircleShape))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(primaryColor.copy(alpha = 0.85f), primaryColor.copy(alpha = 0.40f))
                                    ),
                                    shape = CircleShape
                                )
                                .border(1.5.dp, primaryColor.copy(alpha = 0.55f), CircleShape)
                        ) {
                            Text(
                                text = currentUsername.take(2).uppercase(),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (appLanguage == "Русский") "МОЙ ПРОФИЛЬ" else "MY PROFILE",
                                fontSize = 9.sp, color = onSurfaceVariant,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
                            )
                        }
                        Text(
                            text = currentUsername, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            color = onSurfaceColor, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Share invite button
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(primaryColor.copy(alpha = 0.15f), shape = RoundedCornerShape(14.dp))
                            .border(0.5.dp, primaryColor.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                            .clickable(enabled = !heroInviteGenerating) {
                                if (heroInviteLink.isNotEmpty()) {
                                    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        putExtra(android.content.Intent.EXTRA_TEXT, heroInviteLink)
                                        type = "text/plain"
                                    }
                                    context.startActivity(android.content.Intent.createChooser(sendIntent, null))
                                } else {
                                    heroInviteGenerating = true
                                    heroScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        if (!PythonBridge.isInitialized) { heroInviteGenerating = false; return@launch }
                                        val fp = PythonBridge.getLocalFingerprint()
                                        val tokenBytes = ByteArray(16)
                                        java.security.SecureRandom().nextBytes(tokenBytes)
                                        val token = "2pchat_inv_" + tokenBytes.joinToString("") { "%02x".format(it) }
                                        val link = "2pchat://connect?token=$token&name=$currentUsername&fp=$fp"
                                        PythonBridge.announceSelf(token, fp, P2PMessageRelay.listenerPort(context))
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            heroInviteLink = link
                                            heroInviteGenerating = false
                                            val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                putExtra(android.content.Intent.EXTRA_TEXT, link)
                                                type = "text/plain"
                                            }
                                            context.startActivity(android.content.Intent.createChooser(sendIntent, null))
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (heroInviteGenerating) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = primaryColor
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_quick_link),
                                contentDescription = "Share invite",
                                tint = primaryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Status pills
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    @Composable
                    fun StatusPill(
                        label: String,
                        value: String,
                        ok: Boolean?,
                        node: RadarNode
                    ) {
                        val pillColor = when (ok) {
                            true  -> Color(0xFF00C853)
                            false -> Color(0xFFFF5252)
                            null  -> onSurfaceVariant.copy(alpha = 0.45f)
                        }
                        
                        // Slowly blink the dot if there is a warning/error (ok == false)
                        val dotAlpha = if (ok == false) warningAlpha else 1.0f

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onStatusPillClick(node) }
                                .background(pillColor.copy(alpha = 0.08f))
                                .border(0.5.dp, pillColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                .padding(vertical = 8.dp, horizontal = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .graphicsLayer { alpha = dotAlpha }
                                    .background(pillColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pillColor, maxLines = 1)
                            Text(text = label, fontSize = 9.sp, color = onSurfaceVariant, letterSpacing = 0.3.sp, maxLines = 1)
                        }
                    }

                    StatusPill(
                        label = "UPnP",
                        value = when (heroUpnpOk) { true -> "✓ OK"; false -> "✗ Нет"; else -> "…" },
                        ok = heroUpnpOk,
                        node = RadarNode.ROUTER
                    )
                    StatusPill(
                        label = if (appLanguage == "Русский") "Трекеры" else "Trackers",
                        value = when (heroTrackersOk) { true -> "✓ OK"; false -> "✗ Нет"; else -> "…" },
                        ok = heroTrackersOk,
                        node = RadarNode.TRACKERS
                    )
                    StatusPill(
                        label = "Yggdrasil",
                        value = when (heroYggOk) { true -> "✓ OK"; false -> "✗ Нет"; else -> "…" },
                        ok = heroYggOk,
                        node = RadarNode.YGGDRASIL
                    )
                    StatusPill(
                        label = if (appLanguage == "Русский") "Пиры" else "Peers",
                        value = if (heroActivePeers > 0) "$heroActivePeers 🟢" else "0",
                        ok = if (heroActivePeers > 0) true else null,
                        node = RadarNode.PEERS
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Chats Header
        Text(
            text = Localizations.getString("active_handshakes", appLanguage),
            fontSize = 18.sp, fontWeight = FontWeight.Bold, color = onSurfaceColor,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Peers List
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val savedMessagesName = Localizations.getString("saved_messages_title", appLanguage)
            val savedMessagesDesc = Localizations.getString("saved_messages_desc", appLanguage)
            PeerRow(
                peer = PeerItem(
                    name = savedMessagesName, lastMsg = savedMessagesDesc,
                    transport = "LOCAL RAM", isDirect = true, initials = "🔖"
                ),
                appLanguage = appLanguage, primaryColor = primaryColor, surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor, onSurfaceVariant = onSurfaceVariant,
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
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableStateOf("") }
    var searchSummary by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var inviteLinkState by remember { mutableStateOf("") }
    var directIpVal by remember { mutableStateOf("") }
    var directPortVal by remember { mutableStateOf(P2PMessageRelay.listenerPort(context).toString()) }
    var directNameVal by remember { mutableStateOf("") }
    var showInvitePanel by remember { mutableStateOf(false) }
    var showDirectIpPanel by remember { mutableStateOf(false) }
    var isResolvingInvite by remember { mutableStateOf(false) }
    var resolveInviteStatus by remember { mutableStateOf("") }
    
    val sharedPrefs = remember { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    val username = remember { sharedPrefs.getString("username_profile", "User Identity") ?: "User Identity" }
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
                        if (appLanguage == "Русский") "Ник или 2pchat:// ссылка" else "Nickname or 2pchat:// link",
                        color = onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                trailingIcon = {
                    IconButton(onClick = {
                        val pasted = clipboardManager.getText()?.text?.trim().orEmpty()
                        if (pasted.startsWith("2pchat://connect")) {
                            searchQuery = pasted
                        } else {
                            Toast.makeText(
                                context,
                                if (appLanguage == "Русский") "В буфере нет ссылки 2PChat" else "Clipboard doesn't contain a 2PChat link",
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
                                val expectedFp = uri.getQueryParameter("fp")?.trim().orEmpty()
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
                                    return@IconButton
                                }

                                if (token.isNotEmpty()) {
                                    isResolvingInvite = true
                                    resolveInviteStatus = if (appLanguage == "Русский") "Поиск собеседника..." else "Finding peer..."
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val peers = PythonBridge.searchPeers(token, parsedName, expectedFp)
                                        val verified = peers.firstOrNull()?.get("verified")?.toString()?.equals("true", ignoreCase = true) == true
                                        val ownershipVerified = peers.firstOrNull()?.get("ownership_verified")?.toString()?.equals("true", ignoreCase = true) == true
                                        val endpoints = if (peers.isNotEmpty()) peers[0]["endpoints"] as? List<*> else null
                                        // Pass ALL endpoints comma-separated so Python can try each (IPv4 first, Yggdrasil IPv6 as fallback)
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
                        searchResults = emptyList()
                        searchSummary = ""
                        searchProgress = if (appLanguage == "Русский") {
                            "1/3 · Запрашиваем HTTP и UDP-трекеры…"
                        } else {
                            "1/3 · Querying HTTP and UDP trackers…"
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
                            val peers = PythonBridge.searchPeers(searchQuery)
                            progressJob.cancel()
                            val items = peers.map { peer ->
                                val name = peer["nickname"] as? String ?: "Unknown"
                                val fp = peer["fingerprint"] as? String ?: ""
                                val endpoints = peer["endpoints"] as? List<*> ?: emptyList<Any>()
                                val endpointStr = if (endpoints.isNotEmpty()) {
                                    endpoints.joinToString(",") { it.toString() }
                                } else {
                                    "Unknown"
                                }
                                val verified = peer["verified"]?.toString()?.equals("true", ignoreCase = true) == true
                                val ownershipVerified = peer["ownership_verified"]?.toString()?.equals("true", ignoreCase = true) == true
                                val reason = peer["verification_reason"]?.toString().orEmpty()
                                val displayName = if (name.startsWith("2TFcRb7m") || name.length > 20) {
                                    "Peer (" + name.take(8) + "...)"
                                } else {
                                    name
                                }
                                ContactItem(
                                    name = displayName,
                                    status = if (verified && ownershipVerified) {
                                        if (appLanguage == "Русский") "Подтверждён ссылкой приглашения" else "Verified by invite link"
                                    } else if (verified) {
                                        if (appLanguage == "Русский") "Узел и ключ активны · владелец ника не подтверждён" else "Live node and key · nickname ownership unverified"
                                    } else if (appLanguage == "Русский") "Найден на трекере · live-проверка не пройдена" else "Found on tracker · live verification failed",
                                    initials = if (displayName.length >= 2) displayName.substring(0, 2).uppercase() else displayName.uppercase(),
                                    verified = verified,
                                    endpoints = endpointStr,
                                    verificationDetails = reason,
                                    fingerprint = fp,
                                    ownershipVerified = ownershipVerified,
                                )
                            }
                            withContext(Dispatchers.Main) {
                                searchResults = items
                                isSearching = false
                                searchProgress = ""
                                val verifiedCount = items.count { it.verified }
                                val unverifiedCount = items.size - verifiedCount
                                searchSummary = if (appLanguage == "Русский") {
                                    "Поиск завершён: подтверждено $verifiedCount, найдено без live-подтверждения $unverifiedCount"
                                } else {
                                    "Search complete: $verifiedCount verified, $unverifiedCount found without live verification"
                                }
                            }
                        }
                    } else {
                        searchResults = emptyList()
                        searchProgress = ""
                        searchSummary = ""
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
                                    PythonBridge.announceSelf(tokenVal, fingerprint, P2PMessageRelay.listenerPort(context))
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
                            placeholder = { Text(P2PMessageRelay.listenerPort(context).toString(), fontSize = 12.sp, color = onSurfaceVariant.copy(alpha = 0.4f)) },
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
                            val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
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
                                    val peerKey = if (contact.ownershipVerified) contact.name else "${contact.name} · ${contact.fingerprint.take(8)}"
                                    val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                                    if (!activeSet.contains(peerKey)) {
                                        val newSet = activeSet.toMutableSet()
                                        newSet.add(peerKey)
                                        sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                                        val isYgg = contact.status.contains("Yggdrasil", ignoreCase = true)
                                        sharedPrefs.edit()
                                            .putString("transport_$peerKey", if (isYgg) "YGGDRASIL" else "DIRECT P2P")
                                            .putString("peer_fingerprint_$peerKey", contact.fingerprint)
                                            .apply()
                                    }
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
    onDeleteAccount: () -> Unit,
    onShowLogs: () -> Unit
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
                com.example.twopchat.P2PMessageRelay.shareAvatarWithConnectedPeers(context)
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
    var yggdrasilRouting by remember { mutableStateOf(sharedPrefs.getBoolean("settings_yggdrasil", true)) }
    var ipv4Routing by remember { mutableStateOf(sharedPrefs.getBoolean("settings_ipv4", true)) }
    var persistChatHistory by remember { mutableStateOf(sharedPrefs.getBoolean("persist_chat_history", true)) }
    var stealthDisguise by remember { mutableStateOf(sharedPrefs.getBoolean("settings_stealth_disguise", false)) }
    var showDisguiseInstructionDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        yggdrasilRouting = sharedPrefs.getBoolean("settings_yggdrasil", true)
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
    
    // Passcode dialog flow states
    var showSetPasscodeDialog by remember { mutableStateOf(false) }
    var showDisablePasscodeDialog by remember { mutableStateOf(false) }
    var showAutolockDialog by remember { mutableStateOf(false) }
    var autolockMinutes by remember { mutableStateOf(sharedPrefs.getInt("passcode_autolock_minutes", 1)) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showSetDuressDialog by remember { mutableStateOf(false) }
    var showLauncherIconsPicker by remember { mutableStateOf(false) }
    var showRegenerateYggdrasilKeysDialog by remember { mutableStateOf(false) }

    if (showRegenerateYggdrasilKeysDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateYggdrasilKeysDialog = false },
            title = {
                Text(if (appLanguage == "Русский") "Сгенерировать новый ключ Yggdrasil?" else "Generate a new Yggdrasil key?")
            },
            text = {
                Text(
                    if (appLanguage == "Русский") {
                        "Текущий Yggdrasil IPv6 изменится. Сохранённые у контактов старые адреса перестанут работать."
                    } else {
                        "Your Yggdrasil IPv6 address will change. Contacts with the old address will no longer be able to reach you."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startService(Intent(context, PacketTunnelProvider::class.java).apply {
                        action = PacketTunnelProvider.ACTION_REGENERATE_KEYS
                    })
                    showRegenerateYggdrasilKeysDialog = false
                    Toast.makeText(
                        context,
                        if (appLanguage == "Русский") "Yggdrasil-ключ обновлён" else "Yggdrasil key regenerated",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) {
                    Text(if (appLanguage == "Русский") "Сгенерировать" else "Generate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateYggdrasilKeysDialog = false }) {
                    Text(if (appLanguage == "Русский") "Отмена" else "Cancel")
                }
            },
        )
    }

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

                // IPv4 transport
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (appLanguage == "Русский") "Подключение по IPv4" else "IPv4 connections",
                            fontWeight = FontWeight.Medium,
                            color = onSurfaceColor
                        )
                        Text(
                            if (appLanguage == "Русский") {
                                "Анонсировать и использовать прямые IPv4-подключения"
                            } else {
                                "Announce and use direct IPv4 connections"
                            },
                            fontSize = 12.sp,
                            color = onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = ipv4Routing,
                        onCheckedChange = { enabled ->
                            ipv4Routing = enabled
                            sharedPrefs.edit().putBoolean("settings_ipv4", enabled).apply()
                            com.example.twopchat.PythonBridge.setIpv4Enabled(enabled)
                            com.example.twopchat.P2PMessageRelay.refreshAnnouncement(context)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = primaryColor,
                            checkedTrackColor = primaryColor.copy(alpha = 0.3f)
                        )
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

                TextButton(
                    onClick = { showRegenerateYggdrasilKeysDialog = true },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        if (appLanguage == "Русский") "Сгенерировать новый ключ Yggdrasil" else "Generate new Yggdrasil key"
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
                        .clickable { onShowLogs() }
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
    onSurfaceColor: Color,
    unreadCount: Int = 0
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
                    Box {
                        Icon(
                            painter = painterResource(id = tab.iconRes),
                            contentDescription = tab.label,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer(scaleX = iconScale, scaleY = iconScale)
                        )
                        if (index == 0 && unreadCount > 0) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .align(Alignment.TopEnd)
                                    .offset(x = 6.dp, y = (-4).dp)
                                    .background(Color(0xFFE53935), shape = CircleShape)
                            )
                        }
                    }
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
    val initials: String,
    val unreadCount: Int = 0
)

data class ContactItem(
    val name: String,
    val status: String,
    val initials: String,
    val verified: Boolean = true,
    val endpoints: String = "",
    val verificationDetails: String = "",
    val fingerprint: String = "",
    val ownershipVerified: Boolean = false,
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
                // Avatar representation with Online Status Dot
                Box(modifier = Modifier.size(46.dp)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(primaryColor.copy(alpha = 0.1f), shape = CircleShape)
                    ) {
                        val avatarBitmap = com.example.twopchat.P2PMessageRelay.peerAvatars[peer.name]
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap.asImageBitmap(),
                                contentDescription = "Avatar",
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else if (peer.initials == "🔖") {
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
                    if (peer.name != "Saved Messages") {
                        val isOnline = com.example.twopchat.P2PMessageRelay.peerEndpoints[peer.name] != null
                        if (isOnline) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .align(Alignment.BottomEnd)
                                    .background(surfaceColor, shape = CircleShape)
                                    .padding(2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(primaryColor, shape = CircleShape)
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = peer.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor,
                            modifier = Modifier.weight(1f)
                        )
                        if (peer.unreadCount > 0) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .background(primaryColor, shape = CircleShape)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = peer.unreadCount.toString(),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = peer.lastMsg,
                        fontSize = 12.sp,
                        color = if (peer.unreadCount > 0) onSurfaceColor else onSurfaceVariant,
                        fontWeight = if (peer.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
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
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = valueColor, modifier = Modifier.padding(top = 2.dp))
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
        
        val refreshDiagnostics = {
            logsText = readLogFile(context)
            upnpDetails = PythonBridge.getUpnpDetails()
            trackerDiagnostics = PythonBridge.getTrackerDiagnostics()
            yggDiagnostics = PythonBridge.getYggdrasilNetworkDiagnostics()
        }
        
        LaunchedEffect(Unit) {
            refreshDiagnostics()
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

        val trackerUrls = remember {
            mapOf(
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
        }

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

        androidx.compose.ui.window.Dialog(
            onDismissRequest = { onDismissRequest() }
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
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
                                    modifier = Modifier.size(36.dp).background(onSurfaceColor.copy(alpha = 0.04f), shape = CircleShape)
                                ) {
                                    Text("↻", fontSize = 16.sp, color = primaryColor, fontWeight = FontWeight.Bold)
                                }
                                // Clear (only logs)
                                IconButton(
                                    onClick = {
                                        clearLogFile(context)
                                        logsText = readLogFile(context)
                                    },
                                    modifier = Modifier.size(36.dp).background(onSurfaceColor.copy(alpha = 0.04f), shape = CircleShape)
                                ) {
                                    Text("🗑", fontSize = 16.sp, color = Color.Red)
                                }
                                // Share (only logs)
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

                        Spacer(modifier = Modifier.height(12.dp))

                        // Toggle Buttons (Radar vs Logs)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(onSurfaceColor.copy(alpha = 0.04f), shape = RoundedCornerShape(12.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Button(
                                onClick = { onShowRadarViewChange(true) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (showRadarView) primaryColor else Color.Transparent,
                                    contentColor = if (showRadarView) Color.White else onSurfaceColor
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") "Радар связей" else "Radar View",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Button(
                                onClick = { onShowRadarViewChange(false) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!showRadarView) primaryColor else Color.Transparent,
                                    contentColor = if (!showRadarView) Color.White else onSurfaceColor
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") "Консоль логов" else "Logs Console",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (showRadarView) {
                            // RADAR VIEW MODE
                            Column(
                                modifier = Modifier.weight(1f),
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
                                        .background(Color(0xFF070809), shape = RoundedCornerShape(12.dp))
                                        .border(0.5.dp, Color(0xFF39FF14).copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp))
                                        .padding(10.dp)
                                ) {
                                    val consoleScrollState = rememberScrollState()
                                    LaunchedEffect(logsText) {
                                        consoleScrollState.scrollTo(consoleScrollState.maxValue)
                                    }
                                    
                                    SelectionContainer {
                                        Text(
                                            text = logsText,
                                            color = Color(0xFF39FF14),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(consoleScrollState)
                                        )
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
                                        .padding(16.dp)
                                ) {
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
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                                trackerDiagnostics.forEach { (name, status) ->
                                                    val ping = trackerPings[name]
                                                    val announceRtt = Regex("announce_rtt=(\\d+)ms").find(status)?.groupValues?.get(1)?.toLongOrNull()
                                                    val announceOk = status.contains("announce=OK", ignoreCase = true)
                                                    val pingText = if (announceRtt != null && announceOk) {
                                                        "RTT ${announceRtt}ms"
                                                    } else if (announceRtt != null) {
                                                        if (appLanguage == "Русский") "ошибка через ${announceRtt}ms" else "failed after ${announceRtt}ms"
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
                                                    val statusColor = when {
                                                        skipped || ping == -2L -> onSurfaceVariant
                                                        announceOk -> Color(0xFF4CAF50)
                                                        else -> Color.Red
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
                                                                Text(pingText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = statusColor)
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
                                                                // The embedded Go API uses capitalized admin
                                                                // field names (URI, Up, RXBytes...). The previous
                                                                // lowercase-only parser therefore displayed every
                                                                // real public peer as "unknown".
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
                                                val activePeers = PythonBridge.getActivePeers()
                                                if (activePeers.isEmpty()) {
                                                    Text(
                                                        text = if (appLanguage == "Русский") "Нет активных сессий Double Ratchet" else "No active Double Ratchet sessions established",
                                                        color = onSurfaceVariant,
                                                        fontSize = 13.sp
                                                    )
                                                } else {
                                                    activePeers.forEach { name ->
                                                        val endpoint = P2PMessageRelay.peerEndpoints[name] ?: "resolving..."
                                                        val transport = P2PMessageRelay.peerConnectionTransports[name] ?: "direct"
                                                        val isEstablished = P2PMessageRelay.peerSessionStates[name] ?: true
                                                        Card(
                                                            colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.3f)),
                                                            modifier = Modifier.fillMaxWidth().border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Column {
                                                                    Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = onSurfaceColor)
                                                                    Text("EP: $endpoint", fontSize = 11.sp, color = onSurfaceVariant)
                                                                    Text("Transport: $transport", fontSize = 11.sp, color = primaryColor)
                                                                }
                                                                Text(
                                                                    text = if (isEstablished) "ONLINE" else "WAITING",
                                                                    fontWeight = FontWeight.Bold,
                                                                    fontSize = 11.sp,
                                                                    color = if (isEstablished) Color(0xFF4CAF50) else Color(0xFFFFC107)
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
                }
            }
        }
    }
}
