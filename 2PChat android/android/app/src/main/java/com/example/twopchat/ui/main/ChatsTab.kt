
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
    var chatToDelete by remember { mutableStateOf<String?>(null) }
    
    androidx.compose.runtime.DisposableEffect(sharedPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "active_chats") {
                activeChatsSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
            }
            if (key == "active_chats" || key?.startsWith("last_msg_") == true || key?.startsWith("transport_") == true || key?.startsWith("unread_count_") == true) {
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

    val peers = remember(activeChatsSet, chatListRevision) {
        activeChatsSet.map { name ->
            val lastMsg = com.example.twopchat.SecureStorage.decrypt(
                sharedPrefs.getString("last_msg_$name", null)
            ) ?: "No messages yet"
            val transport = sharedPrefs.getString("transport_$name", null) ?: "UNKNOWN"
            PeerItem(
                name = name,
                lastMsg = lastMsg,
                transport = transport,
                isDirect = isDirectP2pTransport(transport),
                initials = if (name.length >= 2) name.substring(0, 2).uppercase() else name.uppercase(),
                unreadCount = sharedPrefs.getInt("unread_count_$name", 0)
            )
        }
    }

    // Hero Card live state
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
                                        PythonBridge.announceSelf(
                                            token,
                                            fp,
                                            P2PMessageRelay.listenerPort(context),
                                            rendezvousCode = token,
                                        )
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
                        
                        val pulseTransition = rememberInfiniteTransition(label = "pillPulse")
                        val pulseScale by pulseTransition.animateFloat(
                            initialValue = 1.0f,
                            targetValue = 2.4f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1600, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "pulseScale"
                        )
                        val pulseAlpha by pulseTransition.animateFloat(
                            initialValue = 0.6f,
                            targetValue = 0.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1600, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "pulseAlpha"
                        )

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
                        chatToDelete = peer.name
                    }
                )
            }
        }

        if (chatToDelete != null) {
            AlertDialog(
                onDismissRequest = { chatToDelete = null },
                title = {
                    Text(if (appLanguage == "Русский") "Удалить чат?" else "Delete chat?")
                },
                text = {
                    Text(
                        if (appLanguage == "Русский") {
                            "Вы уверены, что хотите удалить чат с пользователем \"${chatToDelete}\"? Это действие сотрет всю историю переписки."
                        } else {
                            "Are you sure you want to delete the chat with \"${chatToDelete}\"? This action will erase all message history."
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = chatToDelete
                        if (name != null) {
                            com.example.twopchat.P2PMessageRelay.deleteChat(context, name)
                        }
                        chatToDelete = null
                    }) {
                        Text(if (appLanguage == "Русский") "Удалить" else "Delete", color = Color.Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { chatToDelete = null }) {
                        Text(if (appLanguage == "Русский") "Отмена" else "Cancel")
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ================= Contacts Tab Screen =================
