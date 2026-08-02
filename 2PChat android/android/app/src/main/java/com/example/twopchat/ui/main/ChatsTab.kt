package com.example.twopchat.ui.main

import android.widget.Toast
import android.content.Intent
import android.content.Context
import androidx.core.content.edit
import android.net.VpnService
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import org.json.JSONArray
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.twopchat.PythonBridge
import com.example.twopchat.Chat
import com.example.twopchat.CreateGroup
import com.example.twopchat.GroupConversation
import com.example.twopchat.GroupInvites
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.group.runtime.GroupChatCoordinator
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState


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
    val chatsViewModel: ChatsViewModel = viewModel(factory = ChatsViewModel.factory(context))
    val sharedPrefs = remember(context) { com.example.twopchat.P2PPreferences.prefs(context) }
    var activeChatsSet by chatsViewModel.activeChatsSet
    var chatListRevision by chatsViewModel.chatListRevision
    var profilePhotoUri by chatsViewModel.profilePhotoUri
    val profileBitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        context,
        profilePhotoUri,
    ) {
        value = withContext(Dispatchers.IO) {
            com.example.twopchat.ui.onboarding.loadBitmapFromUri(context, profilePhotoUri)
        }
    }
    var currentUsername by chatsViewModel.currentUsername
    var activeMenuPeer by remember { mutableStateOf<PeerItem?>(null) }
    var activeMenuGroup by remember { mutableStateOf<com.example.twopchat.group.ui.GroupSummary?>(null) }
    val groupSummaries by GroupChatCoordinator.summaries.collectAsState()
    val sortedGroupSummaries = remember(groupSummaries, chatListRevision) {
        groupSummaries.sortedWith(
            compareByDescending<com.example.twopchat.group.ui.GroupSummary> {
                sharedPrefs.getBoolean("pinned_group_${it.groupId}", false)
            }.thenBy { it.title.lowercase(java.util.Locale.ROOT) }
        )
    }
    val pendingGroupInvites by GroupChatCoordinator.pendingInvites.collectAsState()
    LaunchedEffect(context) {
        GroupChatCoordinator.initialize(context)
    }

    // Read relay SnapshotState maps during composition so route changes are
    // visible immediately even when SharedPreferences hasn't changed.
    val peerNames = remember(activeChatsSet, chatListRevision) { activeChatsSet.toList() }
    val peers = remember(peerNames, chatListRevision, appLanguage) {
        peerNames.map { name ->
            val draft = sharedPrefs.getString(com.example.twopchat.P2PPreferences.draftMessage(name), null)?.takeIf { it.isNotBlank() }
            val hasDraft = draft != null
            val draftPrefix = if (appLanguage == "Русский") "Черновик: " else "Draft: "
            val lastMsg = if (hasDraft) {
                "$draftPrefix$draft"
            } else {
                com.example.twopchat.SecureStorage.decrypt(
                    sharedPrefs.getString("last_msg_$name", null)
                ) ?: "No messages yet"
            }
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
                isDirect = true,
                initials = if (name.length >= 2) name.substring(0, 2).uppercase() else name.uppercase(),
                unreadCount = sharedPrefs.getInt("unread_count_$name", 0),
                isPinned = isPinned,
                isBlocked = isBlocked,
                hasDraft = hasDraft
            )
        }.sortedWith(
            compareByDescending<PeerItem> { it.isPinned }
                .thenBy { it.name }
        )
    }

    // Hero Card live state
    var heroActivePeers by chatsViewModel.heroActivePeers
    var heroUpnpOk by chatsViewModel.heroUpnpOk
    var heroTrackersOk by chatsViewModel.heroTrackersOk
    var heroYggOk by chatsViewModel.heroYggOk
    var isRefreshingAll by chatsViewModel.isRefreshingAll
    val heroScope = rememberCoroutineScope()

    val activeHandshakesLabel = remember(appLanguage) {
        Localizations.getString("active_handshakes", appLanguage).uppercase()
    }
    val savedMessagesName = remember(appLanguage) {
        Localizations.getString("saved_messages_title", appLanguage)
    }
    val savedMessagesDesc = remember(appLanguage) {
        Localizations.getString("saved_messages_desc", appLanguage)
    }

    val totalUnreadDirect = remember(peers) { peers.sumOf { it.unreadCount } }
    val totalUnreadGroups = remember(groupSummaries) { groupSummaries.sumOf { it.unreadCount } }
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // ─── HERO CARD & STATUS PILLS (TOP HEADER) ───────────────────────────
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.18f),
                                surfaceColor.copy(alpha = 0.95f),
                                primaryColor.copy(alpha = 0.08f)
                            )
                        ),
                        shape = RoundedCornerShape(26.dp)
                    )
                    .border(1.dp, primaryColor.copy(alpha = 0.35f), RoundedCornerShape(26.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Avatar + Name + Refresh
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(46.dp)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(primaryColor.copy(alpha = 0.85f), primaryColor.copy(alpha = 0.40f))
                                    ),
                                    shape = CircleShape
                                )
                                .border(1.5.dp, primaryColor.copy(alpha = 0.55f), CircleShape)
                        ) {
                            val avatarBitmap = profileBitmap
                            if (avatarBitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = avatarBitmap.asImageBitmap(),
                                    contentDescription = "My Profile Avatar",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                )
                            } else {
                                Text(
                                    text = currentUsername.take(2).uppercase(),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (appLanguage == "Русский") "МОЙ ПРОФИЛЬ" else "MY PROFILE",
                                    fontSize = 9.sp, color = onSurfaceVariant,
                                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
                                )
                            }
                            Text(
                                text = currentUsername, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                                color = onSurfaceColor, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(primaryColor.copy(alpha = 0.15f), shape = RoundedCornerShape(14.dp))
                                .border(0.5.dp, primaryColor.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                                .clickable(enabled = !isRefreshingAll) {
                                    isRefreshingAll = true
                                    val startMsg = if (appLanguage == "Русский") "Обновление всех подключений..." else "Refreshing all connections..."
                                    val endMsg = if (appLanguage == "Русский") "Подключения успешно обновлены!" else "Connections successfully refreshed!"
                                    Toast.makeText(context, startMsg, Toast.LENGTH_SHORT).show()
                                    heroScope.launch {
                                        withContext(Dispatchers.IO) {
                                            PythonBridge.triggerUpnpReopen()
                                        }
                                        P2PMessageRelay.refreshAnnouncement(context)
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
                                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = primaryColor
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh connections",
                                    tint = primaryColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

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
                                true  -> Color(0xFF10B981)
                                false -> Color(0xFFEF4444)
                                null  -> onSurfaceVariant
                            }
                            
                            val pillInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            val isPillPressed by pillInteractionSource.collectIsPressedAsState()
                            val pillScale by animateFloatAsState(
                                targetValue = if (isPillPressed) 0.94f else 1.0f,
                                animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
                                label = "pillScale"
                            )

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .graphicsLayer {
                                        scaleX = pillScale
                                        scaleY = pillScale
                                    }
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(
                                        interactionSource = pillInteractionSource,
                                        indication = ripple(),
                                        onClick = { onStatusPillClick(node) }
                                    )
                                    .background(pillColor.copy(alpha = 0.12f))
                                    .border(0.75.dp, pillColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                    .padding(vertical = 8.dp, horizontal = 2.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(12.dp)
                                ) {
                                    if (ok != null) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .border(1.dp, pillColor.copy(alpha = 0.38f), CircleShape)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .background(pillColor, CircleShape)
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
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
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                    } else if (ok == false && (node == RadarNode.ROUTER || node == RadarNode.TRACKERS || node == RadarNode.YGGDRASIL)) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Error",
                                            tint = pillColor,
                                            modifier = Modifier.size(12.dp)
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
        }

        // ─── SUB-TAB SEGMENT CONTROL (ЛИЧНЫЕ / ГРУППЫ) ──────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .background(surfaceColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .border(1.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val isDirectSelected = pagerState.currentPage == 0
            val directTitle = if (appLanguage == "Русский") "Личные" else "Direct"
            val groupsTitle = if (appLanguage == "Русский") "Группы" else "Groups"

            // Tab 0: Direct Chats
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDirectSelected) primaryColor else Color.Transparent)
                    .clickable {
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = directTitle,
                        fontSize = 13.sp,
                        fontWeight = if (isDirectSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isDirectSelected) Color.White else onSurfaceVariant
                    )
                    if (totalUnreadDirect > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(if (isDirectSelected) Color.White.copy(alpha = 0.25f) else primaryColor, CircleShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$totalUnreadDirect",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Tab 1: Groups
            val isGroupsSelected = pagerState.currentPage == 1
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isGroupsSelected) primaryColor else Color.Transparent)
                    .clickable {
                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = groupsTitle,
                        fontSize = 13.sp,
                        fontWeight = if (isGroupsSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isGroupsSelected) Color.White else onSurfaceVariant
                    )
                    if (totalUnreadGroups > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(if (isGroupsSelected) Color.White.copy(alpha = 0.25f) else primaryColor, CircleShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$totalUnreadGroups",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ─── SWIPEABLE HORIZONTAL PAGER (PAGES 0 AND 1) ───────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            if (page == 0) {
                // PAGE 0: DIRECT CHATS
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    item(key = "saved_messages") {
                        Box(modifier = Modifier.padding(bottom = 10.dp)) {
                            PeerRow(
                                peer = PeerItem(
                                    name = savedMessagesName, lastMsg = savedMessagesDesc,
                                    transport = "LOCAL RAM", isDirect = true, initials = "🔖"
                                ),
                                appLanguage = appLanguage, primaryColor = primaryColor, surfaceColor = surfaceColor,
                                onSurfaceColor = onSurfaceColor, onSurfaceVariant = onSurfaceVariant,
                                onClick = { onItemClick(Chat("Saved Messages")) }
                            )
                        }
                    }

                    items(
                        items = peers,
                        key = { peer -> peer.name },
                        contentType = { "peer" },
                    ) { peer ->
                        Box(modifier = Modifier.padding(bottom = 10.dp)) {
                            PeerRow(
                                peer = peer,
                                appLanguage = appLanguage,
                                primaryColor = primaryColor,
                                surfaceColor = surfaceColor,
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                onClick = {
                                    sharedPrefs.edit { putInt("unread_count_${peer.name}", 0) }
                                    onItemClick(Chat(peer.name))
                                },
                                onLongClick = {
                                    activeMenuPeer = peer
                                }
                            )
                        }
                    }

                    if (peers.isEmpty()) {
                        item(key = "empty_chats") {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, bottom = 8.dp)
                                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(primaryColor.copy(alpha = 0.12f), shape = CircleShape)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_saved_messages),
                                            contentDescription = "No Peers",
                                            tint = primaryColor,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = if (appLanguage == "Русский") "Пока нет активных чатов" else "No active chats yet",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = onSurfaceColor
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (appLanguage == "Русский") 
                                            "Скопируйте ссылку на свой профиль выше или добавьте контакт во вкладке «Поиск»" 
                                        else 
                                            "Copy your profile link above or add contacts from the Search tab",
                                        fontSize = 12.sp,
                                        color = onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    item(key = "bottom_spacer_direct") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            } else {
                // PAGE 1: GROUPS
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    item(key = "group_actions") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { onItemClick(CreateGroup) },
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (appLanguage == "Русский") "Новая группа" else "New group",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            OutlinedButton(
                                onClick = { onItemClick(GroupInvites) },
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = 0.4f))
                            ) {
                                val count = pendingGroupInvites.invites.size
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = null,
                                    tint = primaryColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (appLanguage == "Русский") {
                                        "Приглашения${if (count > 0) " ($count)" else ""}"
                                    } else {
                                        "Invites${if (count > 0) " ($count)" else ""}"
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = primaryColor
                                )
                            }
                        }
                    }

                    items(
                        items = sortedGroupSummaries,
                        key = { summary -> "group:${summary.groupId}" },
                        contentType = { "group" },
                    ) { summary ->
                        val groupDraft = sharedPrefs.getString("draft_msg_group_${summary.groupId}", null)?.takeIf { it.isNotBlank() }
                        val hasGroupDraft = groupDraft != null
                        val draftPrefix = if (appLanguage == "Русский") "Черновик: " else "Draft: "
                        val lastMsgText = if (hasGroupDraft) {
                            "$draftPrefix$groupDraft"
                        } else {
                            summary.lastMessagePreview.ifBlank {
                                if (appLanguage == "Русский") "Сообщений пока нет" else "No messages yet"
                            }
                        }
                        val isGroupPinned = sharedPrefs.getBoolean("pinned_group_${summary.groupId}", false)
                        Box(modifier = Modifier.padding(bottom = 10.dp)) {
                            PeerRow(
                                peer = PeerItem(
                                    name = summary.title,
                                    lastMsg = lastMsgText,
                                    transport = "${summary.memberCount} MEMBERS",
                                    isDirect = false,
                                    initials = summary.title.take(2).uppercase(),
                                    unreadCount = summary.unreadCount,
                                    isPinned = isGroupPinned,
                                    hasDraft = hasGroupDraft,
                                    avatarUri = summary.avatarUri
                                ),
                                appLanguage = appLanguage,
                                primaryColor = primaryColor,
                                surfaceColor = surfaceColor,
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant,
                                onClick = { onItemClick(GroupConversation(summary.groupId)) },
                                onLongClick = { activeMenuGroup = summary }
                            )
                        }
                    }

                    if (groupSummaries.isEmpty()) {
                        item(key = "empty_groups") {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = surfaceColor.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, bottom = 8.dp)
                                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = if (appLanguage == "Русский") "Вы пока не состоите ни в одной группе" else "You are not in any groups yet",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = onSurfaceColor
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (appLanguage == "Русский") 
                                            "Создайте свою группу кнопкой выше или примите приглашение" 
                                        else 
                                            "Create your group using the button above or accept an invite",
                                        fontSize = 12.sp,
                                        color = onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    item(key = "bottom_spacer_groups") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
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

    if (activeMenuGroup != null) {
        val groupSummary = activeMenuGroup!!
        val isPinned = sharedPrefs.getBoolean("pinned_group_${groupSummary.groupId}", false)
        val isMuted = sharedPrefs.getBoolean("mute_group_${groupSummary.groupId}", false)

        Dialog(
            onDismissRequest = { activeMenuGroup = null },
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
                            Box(modifier = Modifier.size(44.dp)) {
                                val avatarFile = remember(groupSummary.avatarUri) {
                                    groupSummary.avatarUri?.let { java.io.File(it) }?.takeIf { it.exists() }
                                }
                                val avatarBitmap = remember(avatarFile) {
                                    avatarFile?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
                                }
                                if (avatarBitmap != null) {
                                    Image(
                                        bitmap = avatarBitmap.asImageBitmap(),
                                        contentDescription = "Group Avatar",
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                } else {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(primaryColor.copy(alpha = 0.15f), shape = CircleShape)
                                    ) {
                                        Text(
                                            text = groupSummary.title.take(2).uppercase(),
                                            color = primaryColor,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = groupSummary.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = onSurfaceColor
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (appLanguage == "Русский") "${groupSummary.memberCount} участников" else "${groupSummary.memberCount} members",
                                    fontSize = 12.sp,
                                    color = primaryColor
                                )
                            }
                        }
                        IconButton(onClick = { activeMenuGroup = null }) {
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
                            sharedPrefs.edit().putBoolean("pinned_group_${groupSummary.groupId}", !isPinned).apply()
                            chatListRevision++
                            activeMenuGroup = null
                        }
                    )

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
                            sharedPrefs.edit().putBoolean("mute_group_${groupSummary.groupId}", !isMuted).apply()
                            chatListRevision++
                            activeMenuGroup = null
                        }
                    )

                    DialogOptionRow(
                        label = if (appLanguage == "Русский") "Очистить историю" else "Clear History",
                        textColor = Color.Red,
                        iconTint = Color.Red,
                        iconRes = com.example.twopchat.R.drawable.ic_broom,
                        onClick = {
                            GroupChatCoordinator.clearHistory(groupSummary.groupId)
                            chatListRevision++
                            activeMenuGroup = null
                            Toast.makeText(context, if (appLanguage == "Русский") "История очищена" else "History cleared", Toast.LENGTH_SHORT).show()
                        }
                    )

                    DialogOptionRow(
                        iconRes = com.example.twopchat.R.drawable.ic_delete,
                        label = if (appLanguage == "Русский") "Покинуть группу" else "Leave Group",
                        textColor = Color.Red,
                        iconTint = Color.Red,
                        onClick = {
                            GroupChatCoordinator.leaveGroup(groupSummary.groupId)
                            chatListRevision++
                            activeMenuGroup = null
                            Toast.makeText(context, if (appLanguage == "Русский") "Вы вышли из группы" else "Left group", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
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
