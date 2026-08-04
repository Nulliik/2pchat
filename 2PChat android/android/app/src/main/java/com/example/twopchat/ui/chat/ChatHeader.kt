package com.example.twopchat.ui.chat

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.R
import com.example.twopchat.connectionTransportLabel
import com.example.twopchat.data.Localizations
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh

@Composable
internal fun ChatHeader(
    peerName: String,
    appLanguage: String,
    isSearchMode: Boolean,
    searchQuery: String,
    isVerified: Boolean,
    isMuted: Boolean,
    isForwardingRestricted: Boolean,
    onToggleForwardingRestriction: (Boolean) -> Unit,
    activeFingerprint: String,
    localFingerprint: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onBack: () -> Unit,
    onSearchModeChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onShowProfile: () -> Unit,
    onVerify: () -> Unit,
    onReconnect: () -> Unit,
    onToggleMuted: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onDeleteChat: () -> Unit,
    onSetWallpaper: () -> Unit = {},
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (isSearchMode) {
        ConversationSearchHeader(
            query = searchQuery,
            placeholder = if (appLanguage == "Русский") "Поиск по сообщениям..." else "Search messages...",
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = onSurfaceVariant,
            onClose = {
                onSearchModeChange(false)
                onSearchQueryChange("")
            },
            onQueryChange = onSearchQueryChange,
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor)
            .statusBarsPadding()
            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(36.dp)
                .background(onSurfaceColor.copy(alpha = 0.04f), CircleShape),
        ) {
            Icon(
                painterResource(R.drawable.ic_back_arrow),
                "Back",
                tint = onSurfaceColor,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        val savedMessages = peerName == "Saved Messages"
        val displayName = if (savedMessages) Localizations.getString("saved_messages_title", appLanguage) else peerName
        val initials = when {
            savedMessages -> "🔖"
            peerName.contains(" ") -> peerName.split(" ").joinToString("") { it.take(1) }
            else -> peerName.take(2).uppercase()
        }
        val isOnline = P2PMessageRelay.peerSessionStates[peerName] == true
        Box(
            modifier = Modifier.clickable(enabled = !savedMessages, onClick = onShowProfile)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(43.dp)
                    .background(primaryColor.copy(alpha = 0.12f), CircleShape),
            ) {
                val avatar = P2PMessageRelay.peerAvatars[peerName]
                when {
                    avatar != null -> Image(avatar.asImageBitmap(), "Avatar", Modifier.fillMaxSize().clip(CircleShape))
                    savedMessages -> Icon(
                        painterResource(R.drawable.ic_saved_messages),
                        "Saved Messages",
                        tint = primaryColor,
                        modifier = Modifier.size(22.dp),
                    )
                    else -> Text(initials, color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            if (!savedMessages && isOnline) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(primaryColor, CircleShape)
                        .border(1.5.dp, surfaceColor, CircleShape)
                        .align(Alignment.BottomEnd)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        val isMismatch = com.example.twopchat.P2PPreferences.prefs(context)
            .getBoolean("fingerprint_mismatch_$peerName", false)
        val shieldColor = when {
            isMismatch -> Color(0xFFF44336)
            isVerified -> Color(0xFF4CAF50)
            else -> Color(0xFFFFC107)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = !savedMessages, onClick = onShowProfile),
        ) {
            Text(
                displayName,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurfaceColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!savedMessages && !isOnline) {
                    Box(Modifier.size(5.dp).background(onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
                    Spacer(Modifier.width(4.dp))
                }
                val status = when {
                    savedMessages -> Localizations.getString("local_storage", appLanguage)
                    !isOnline -> if (appLanguage == "Русский") "Не в сети" else "Offline"
                    else -> {
                        val transport = connectionTransportLabel(
                            rawTransport = P2PMessageRelay.peerConnectionTransports[peerName],
                            endpoint = P2PMessageRelay.peerEndpoints[peerName],
                            appLanguage = appLanguage,
                        )
                        val rtt = P2PMessageRelay.peerRttMs[peerName]?.let { " • ${it}ms" }.orEmpty()
                        if (appLanguage == "Русский") "В сети • $transport$rtt" else "Online • $transport$rtt"
                    }
                }
                Text(
                    status,
                    fontSize = 11.sp,
                    color = if (isOnline) primaryColor.copy(alpha = 0.9f) else onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        if (!savedMessages) {
            IconButton(
                onClick = {
                    if (activeFingerprint.isBlank() || localFingerprint.isBlank()) {
                        Toast.makeText(
                            context,
                            if (appLanguage == "Русский") "Fingerprint ещё недоступен" else "Fingerprint is not available yet",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else onVerify()
                },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(painterResource(R.drawable.ic_shield_status), "Verify", tint = shieldColor, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(2.dp))
        }
        IconButton(onClick = { onSearchModeChange(true) }, modifier = Modifier.size(34.dp)) {
            Icon(painterResource(R.drawable.ic_menu_search), "Search", tint = onSurfaceColor.copy(alpha = 0.85f), modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(2.dp))
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(34.dp)) {
                Text("⋮", fontSize = 18.sp, color = onSurfaceColor.copy(alpha = 0.85f), fontWeight = FontWeight.Bold)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(surfaceColor)) {
                if (!savedMessages) {
                    DropdownMenuItem(
                        text = { Text(if (appLanguage == "Русский") "Переподключить соединение" else "Reconnect Connection", color = onSurfaceColor) },
                        onClick = { showMenu = false; onReconnect() },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reconnect",
                                tint = onSurfaceColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(
                            if (isMuted) {
                                if (appLanguage == "Русский") "Включить уведомления" else "Unmute Notifications"
                            } else if (appLanguage == "Русский") "Выключить уведомления" else "Mute Notifications",
                            color = onSurfaceColor,
                        ) },
                        onClick = { showMenu = false; onToggleMuted(!isMuted) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(if (isMuted) R.drawable.ic_notifications else R.drawable.ic_notifications_off),
                                contentDescription = "Mute/Unmute",
                                tint = onSurfaceColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(
                            if (isForwardingRestricted) {
                                if (appLanguage == "Русский") "Разрешить пересылку" else "Allow Forwarding"
                            } else if (appLanguage == "Русский") "Запретить пересылку" else "Restrict Forwarding",
                            color = onSurfaceColor,
                        ) },
                        onClick = { showMenu = false; onToggleForwardingRestriction(!isForwardingRestricted) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(if (isForwardingRestricted) R.drawable.ic_forward else R.drawable.ic_forward_off),
                                contentDescription = "Restrict Forwarding",
                                tint = onSurfaceColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (appLanguage == "Русский") "Установить обои" else "Set Wallpaper", color = onSurfaceColor) },
                    onClick = { showMenu = false; onSetWallpaper() },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_chat_wallpaper),
                            contentDescription = "Set Wallpaper",
                            tint = onSurfaceColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (appLanguage == "Русский") "Очистить историю" else "Clear History", color = Color.Red) },
                    onClick = { showMenu = false; onClearHistory() },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_broom),
                            contentDescription = "Clear History",
                            tint = Color.Red,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
                if (!savedMessages) {
                    DropdownMenuItem(
                        text = { Text(if (appLanguage == "Русский") "Удалить чат" else "Delete Chat", color = Color.Red) },
                        onClick = { showMenu = false; showDeleteDialog = true },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = "Delete Chat",
                                tint = Color.Red,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        val dangerRed = Color(0xFFE53935)
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    text = if (appLanguage == "Русский") "Удалить чат?" else "Delete chat?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (appLanguage == "Русский") "Вы уверены, что хотите полностью удалить этот чат? Все сообщения будут безвозвратно удалены."
                    else "Are you sure you want to delete this chat? All message history will be permanently lost.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { showDeleteDialog = false; onDeleteChat() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = dangerRed,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (appLanguage == "Русский") "Удалить" else "Delete",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(if (appLanguage == "Русский") "Отмена" else "Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}
