
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.example.twopchat.data.ChatDatabaseHelper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.example.twopchat.PythonBridge
import com.example.twopchat.Chat
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.canonicalConnectionTransport
import com.example.twopchat.theme.*
import com.example.twopchat.data.Localizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh


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
    val context = LocalContext.current
    
    val sharedPrefs = remember(context) { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    var activeChatsSet by remember {
        mutableStateOf(sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet())
    }
    var chatListRevision by remember { mutableIntStateOf(0) }
    var profilePhotoUri by remember { mutableStateOf(sharedPrefs.getString("profile_photo_uri", null)) }
    val profileBitmap = remember(profilePhotoUri) {
        com.example.twopchat.ui.onboarding.loadBitmapFromUri(context, profilePhotoUri)
    }
    var currentUsername by remember { mutableStateOf(sharedPrefs.getString("username_profile", "Anonymous") ?: "Anonymous") }
    var activeMenuPeer by remember { mutableStateOf<PeerItem?>(null) }
    
    androidx.compose.runtime.DisposableEffect(sharedPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "active_chats") {
                activeChatsSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
            }
            if (key == "active_chats" || key?.startsWith("last_msg_") == true || key?.startsWith("transport_") == true || key?.startsWith("last_endpoint_") == true || key?.startsWith("unread_count_") == true) {
                // The chat set itself usually stays equal when a message
                // arrives. Keep a separate revision so Compose refreshes the
                // preview and unread badge on the main screen.
                chatListRevision++
            }
            if (key == "profile_photo_uri") {
                profilePhotoUri = sharedPrefs.getString("profile_photo_uri", null)
            }
            if (key == "username_profile") {
                currentUsername = sharedPrefs.getString("username_profile", "Anonymous") ?: "Anonymous"
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    // Read relay SnapshotState maps during composition so route changes are
    // visible immediately even when SharedPreferences hasn't changed.
    val peerNames = remember(activeChatsSet, chatListRevision) { activeChatsSet.toList() }
    val peers = peerNames.map { name ->
            val lastMsg = com.example.twopchat.SecureStorage.decrypt(
                sharedPrefs.getString("last_msg_$name", null)
            ) ?: "No messages yet"
            val transport = canonicalConnectionTransport(
                rawTransport = P2PMessageRelay.peerConnectionTransports[name]
                    ?: sharedPrefs.getString("transport_$name", null),
                endpoint = P2PMessageRelay.peerEndpoints[name]
                    ?: sharedPrefs.getString("last_endpoint_$name", null),
            ) ?: "UNKNOWN"
            val isPinned = sharedPrefs.getBoolean("pinned_chat_$name", false)
            val isBlocked = sharedPrefs.getBoolean("blocked_peer_$name", false)
            PeerItem(
                name = name,
                lastMsg = lastMsg,
                transport = transport,
                isDirect = isDirectP2pTransport(transport),
                initials = if (name.length >= 2) name.substring(0, 2).uppercase() else name.uppercase(),
                unreadCount = sharedPrefs.getInt("unread_count_$name", 0),
                isPinned = isPinned,
                isBlocked = isBlocked
            )
    }.sortedWith(
        compareByDescending<PeerItem> { it.isPinned }
            .thenBy { it.name }
    )

    // Hero Card live state
    var heroActivePeers by remember { mutableStateOf(0) }
    var heroUpnpOk by remember { mutableStateOf<Boolean?>(null) }
    var heroTrackersOk by remember { mutableStateOf<Boolean?>(null) }
    var heroYggOk by remember { mutableStateOf<Boolean?>(null) }
    var isRefreshingAll by remember { mutableStateOf(false) }
    val heroScope = rememberCoroutineScope()

    // Pulsing animations
    val animationsEnabled = com.example.twopchat.LocalAppAnimationsEnabled.current
    val infiniteTransition = if (animationsEnabled) rememberInfiniteTransition(label = "heroRing") else null
    val ringAlpha = infiniteTransition?.animateFloat(
        initialValue = 0.2f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ringAlpha"
    )?.value ?: 0.2f

    val warningTransition = if (animationsEnabled) rememberInfiniteTransition(label = "warningPulse") else null
    val warningAlpha = warningTransition?.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "warningAlpha"
    )?.value ?: 1.0f

    LaunchedEffect(Unit) {
        while (true) {
            if (PythonBridge.isInitialized) {
                heroActivePeers = PythonBridge.getActivePeers().distinct().size
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
                            if (profileBitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = profileBitmap.asImageBitmap(),
                                    contentDescription = "My Profile Avatar",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                )
                            } else {
                                Text(
                                    text = currentUsername.take(2).uppercase(),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
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

                    // Refresh connections button
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(primaryColor.copy(alpha = 0.15f), shape = RoundedCornerShape(14.dp))
                            .border(0.5.dp, primaryColor.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                            .clickable(enabled = !isRefreshingAll) {
                                isRefreshingAll = true
                                val startMsg = if (appLanguage == "Русский") "Обновление всех подключений..." else "Refreshing all connections..."
                                val endMsg = if (appLanguage == "Русский") "Подключения успешно обновлены!" else "Connections successfully refreshed!"
                                Toast.makeText(context, startMsg, Toast.LENGTH_SHORT).show()
                                heroScope.launch {
                                    // 1. UPnP Reopen
                                    withContext(Dispatchers.IO) {
                                        PythonBridge.triggerUpnpReopen()
                                    }
                                    // 2. Trackers Refresh
                                    P2PMessageRelay.refreshAnnouncement(context)
                                    // 3. Yggdrasil Restart
                                    val stopIntent = Intent(context, PacketTunnelProvider::class.java).apply {
                                        action = PacketTunnelProvider.ACTION_STOP
                                    }
                                    context.startService(stopIntent)
                                    delay(1000)
                                    val startIntent = Intent(context, PacketTunnelProvider::class.java).apply {
                                        action = PacketTunnelProvider.ACTION_START
                                    }
                                    context.startService(startIntent)
                                    
                                    isRefreshingAll = false
                                    Toast.makeText(context, endMsg, Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isRefreshingAll) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = primaryColor
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh connections",
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
                            null  -> onSurfaceVariant
                        }
                        
                        val pulseTransition = if (animationsEnabled) rememberInfiniteTransition(label = "pillPulse") else null
                        val pulseScale = pulseTransition?.animateFloat(
                            initialValue = 1.0f,
                            targetValue = 2.4f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1600, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "pulseScale"
                        )?.value ?: 1.0f
                        val pulseAlpha = pulseTransition?.animateFloat(
                            initialValue = 0.6f,
                            targetValue = 0.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1600, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "pulseAlpha"
                        )?.value ?: 0.0f

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
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(14.dp)
                            ) {
                                if (ok != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .graphicsLayer {
                                                scaleX = pulseScale
                                                scaleY = pulseScale
                                                alpha = pulseAlpha
                                            }
                                            .border(1.dp, pillColor, CircleShape)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .graphicsLayer { alpha = dotAlpha }
                                        .background(pillColor, CircleShape)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            ) {
                                if (ok == true && (node == RadarNode.ROUTER || node == RadarNode.TRACKERS || node == RadarNode.YGGDRASIL)) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "OK",
                                        tint = pillColor,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                } else if (ok == false && (node == RadarNode.ROUTER || node == RadarNode.TRACKERS || node == RadarNode.YGGDRASIL)) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Error",
                                        tint = pillColor,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                }
                                Text(
                                    text = if (ok == true && (node == RadarNode.ROUTER || node == RadarNode.TRACKERS || node == RadarNode.YGGDRASIL)) {
                                        "OK"
                                    } else if (ok == false && (node == RadarNode.ROUTER || node == RadarNode.TRACKERS || node == RadarNode.YGGDRASIL)) {
                                        if (appLanguage == "Русский") "Нет" else "No"
                                    } else {
                                        value
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = pillColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(text = label, fontSize = 9.sp, color = onSurfaceVariant, letterSpacing = 0.3.sp, maxLines = 1)
                        }
                    }

                    StatusPill(
                        label = "UPnP",
                        value = "…",
                        ok = heroUpnpOk,
                        node = RadarNode.ROUTER
                    )
                    StatusPill(
                        label = if (appLanguage == "Русский") "Трекеры" else "Trackers",
                        value = "…",
                        ok = heroTrackersOk,
                        node = RadarNode.TRACKERS
                    )
                    StatusPill(
                        label = "Yggdrasil",
                        value = "…",
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
        Column(
            modifier = Modifier.animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
            peers.forEach { peer ->
                PeerRow(
                    peer = peer,
                    appLanguage = appLanguage,
                    primaryColor = primaryColor,
                    surfaceColor = surfaceColor,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onClick = { onItemClick(Chat(peer.name)) },
                    onLongClick = {
                        activeMenuPeer = peer
                    }
                )
            }
        }

        if (activeMenuPeer != null) {
            val peer = activeMenuPeer!!
            val isPinned = peer.isPinned
            val isMuted = sharedPrefs.getBoolean("mute_notifications_${peer.name}", false)
            val isBlocked = sharedPrefs.getBoolean("blocked_peer_${peer.name}", false)

            Dialog(
                onDismissRequest = { activeMenuPeer = null },
                properties = DialogProperties(usePlatformDefaultWidth = true)
            ) {
                var animateIn by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    animateIn = true
                }

                val scale by animateFloatAsState(
                    targetValue = if (animateIn) 1f else 0.85f,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                )
                val opacity by animateFloatAsState(
                    targetValue = if (animateIn) 1f else 0f,
                    animationSpec = tween(200)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = surfaceColor),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            alpha = opacity
                        }
                        .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(40.dp)) {
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
                                                modifier = Modifier.size(20.dp)
                                            )
                                        } else {
                                            Text(
                                                text = peer.initials,
                                                color = primaryColor,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = peer.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = onSurfaceColor
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val statusText = if (peer.name == Localizations.getString("saved_messages_title", appLanguage)) {
                                        if (appLanguage == "Русский") "Личное облако" else "Personal storage"
                                    } else {
                                        val isOnline = com.example.twopchat.P2PMessageRelay.peerSessionStates[peer.name] == true
                                        if (isOnline) {
                                            if (appLanguage == "Русский") "В сети" else "Online"
                                        } else {
                                            if (appLanguage == "Русский") "Не в сети" else "Offline"
                                        }
                                    }
                                    Text(
                                        text = statusText,
                                        fontSize = 11.sp,
                                        color = if (peer.name != Localizations.getString("saved_messages_title", appLanguage) && com.example.twopchat.P2PMessageRelay.peerSessionStates[peer.name] == true) primaryColor else onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = { activeMenuPeer = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = onSurfaceVariant)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.08f), thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(8.dp))

                        // Menu Options
                        DialogOptionRow(
                            iconRes = com.example.twopchat.R.drawable.ic_pin,
                            label = if (isPinned) {
                                if (appLanguage == "Русский") "Открепить чат" else "Unpin Chat"
                            } else {
                                if (appLanguage == "Русский") "Закрепить чат" else "Pin Chat"
                            },
                            textColor = onSurfaceColor,
                            iconTint = primaryColor,
                            onClick = {
                                sharedPrefs.edit().putBoolean("pinned_chat_${peer.name}", !isPinned).apply()
                                chatListRevision++
                                activeMenuPeer = null
                            }
                        )

                        if (peer.name != Localizations.getString("saved_messages_title", appLanguage)) {
                            DialogOptionRow(
                                label = if (isMuted) {
                                    if (appLanguage == "Русский") "Включить уведомления" else "Unmute Notifications"
                                } else {
                                    if (appLanguage == "Русский") "Выключить уведомления" else "Mute Notifications"
                                },
                                textColor = onSurfaceColor,
                                iconTint = primaryColor,
                                iconRes = if (isMuted) com.example.twopchat.R.drawable.ic_notifications else com.example.twopchat.R.drawable.ic_notifications_off,
                                onClick = {
                                    sharedPrefs.edit().putBoolean("mute_notifications_${peer.name}", !isMuted).apply()
                                    chatListRevision++
                                    activeMenuPeer = null
                                }
                            )
                        }

                        DialogOptionRow(
                            label = if (appLanguage == "Русский") "Очистить историю" else "Clear History",
                            textColor = Color.Red,
                            iconTint = Color.Red,
                            iconRes = com.example.twopchat.R.drawable.ic_broom,
                            onClick = {
                                val db = ChatDatabaseHelper.getInstance(context)
                                db.clearMessagesForPeer(peer.name)
                                sharedPrefs.edit().remove("last_msg_${peer.name}").apply()
                                chatListRevision++
                                activeMenuPeer = null
                                Toast.makeText(context, if (appLanguage == "Русский") "История очищена" else "History cleared", Toast.LENGTH_SHORT).show()
                            }
                        )

                        if (peer.name != Localizations.getString("saved_messages_title", appLanguage)) {
                            DialogOptionRow(
                                label = if (isBlocked) {
                                    if (appLanguage == "Русский") "Разблокировать" else "Unblock"
                                } else {
                                    if (appLanguage == "Русский") "Заблокировать" else "Block"
                                },
                                textColor = if (isBlocked) primaryColor else Color.Red,
                                iconTint = if (isBlocked) primaryColor else Color.Red,
                                iconRes = com.example.twopchat.R.drawable.ic_block,
                                onClick = {
                                    sharedPrefs.edit().putBoolean("blocked_peer_${peer.name}", !isBlocked).apply()
                                    chatListRevision++
                                    activeMenuPeer = null
                                    val toastMsg = if (isBlocked) {
                                        if (appLanguage == "Русский") "Пользователь разблокирован" else "User unblocked"
                                    } else {
                                        if (appLanguage == "Русский") "Пользователь заблокирован" else "User blocked"
                                    }
                                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        if (peer.name != Localizations.getString("saved_messages_title", appLanguage)) {
                            DialogOptionRow(
                                iconRes = com.example.twopchat.R.drawable.ic_delete,
                                label = if (appLanguage == "Русский") "Удалить чат" else "Delete Chat",
                                textColor = Color.Red,
                                iconTint = Color.Red,
                                onClick = {
                                    com.example.twopchat.P2PMessageRelay.deleteChat(context, peer.name)
                                    chatListRevision++
                                    activeMenuPeer = null
                                    Toast.makeText(context, if (appLanguage == "Русский") "Чат удален" else "Chat deleted", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}


@Composable
fun DialogOptionRow(
    label: String,
    textColor: Color,
    iconTint: Color,
    onClick: () -> Unit,
    iconRes: Int? = null,
    emoji: String? = null,
    iconSize: Dp = 18.dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(iconSize)
                )
            } else if (emoji != null) {
                Text(text = emoji, fontSize = 16.sp)
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

// ================= Contacts Tab Screen =================
