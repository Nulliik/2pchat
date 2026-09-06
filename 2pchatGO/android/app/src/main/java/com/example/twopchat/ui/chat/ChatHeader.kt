package com.example.twopchat.ui.chat

import android.widget.Toast
import android.provider.Settings
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.R
import com.example.twopchat.relay.TransportType
import com.example.twopchat.relay.connectionTransportLabel
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
    onOpenConnectionMode: () -> Unit = {},
    onBlockPeer: () -> Unit = {},
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Check system setting for reduced motion
    val reduceMotion = remember(context) {
        try {
            val transitionScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f
            )
            val animatorScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            transitionScale == 0f || animatorScale == 0f
        } catch (_: Throwable) {
            false
        }
    }

    // Breathing pulse animation for online status
    val infiniteTransition = rememberInfiniteTransition(label = "chatHeaderAvatarPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )

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

    val isLight = surfaceColor.luminance() > 0.5f
    val headerBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(
        alpha = if (isLight) 0.16f else 0.10f
    )

    Surface(
        color = surfaceColor,
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, headerBorderColor)
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(42.dp)
                .background(onSurfaceColor.copy(alpha = 0.04f), CircleShape),
        ) {
            Icon(
                painterResource(R.drawable.ic_back_arrow),
                "Back",
                tint = onSurfaceColor,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        val savedMessages = peerName == "Saved Messages"
        val isRaw = P2PMessageRelay.isRawFingerprint(peerName) ||
            (peerName.length == 44 && peerName.endsWith("="))
        val displayName = when {
            savedMessages -> Localizations.getString("saved_messages_title", appLanguage)
            isRaw -> "${peerName.take(8)}...${peerName.takeLast(6)}"
            else -> peerName
        }
        val initials = when {
            savedMessages -> "🔖"
            peerName.contains(" ") -> peerName.split(" ").joinToString("") { it.take(1) }
            else -> peerName.take(2).uppercase()
        }
        // Query Go Core as source of truth: avoids stale-RAM false-negative when the
        val isOnline = P2PMessageRelay.peerSessionStates[peerName] == true
        val isMismatch = com.example.twopchat.config.P2PPreferences.prefs(context)
            .getBoolean("fingerprint_mismatch_$peerName", false)
        val shieldColor = when {
            isMismatch -> Color(0xFFF44336)
            isVerified -> Color(0xFF4CAF50)
            else -> Color(0xFFFFC107)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !savedMessages, onClick = onShowProfile)
                .padding(vertical = 4.dp, horizontal = 2.dp),
        ) {
            Box {
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
                    val onlineGreen = Color(0xFF10B981)
                    Box(
                        modifier = Modifier
                            .size(13.dp)
                            .align(Alignment.BottomEnd),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!reduceMotion) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = pulseScale
                                        scaleY = pulseScale
                                        alpha = pulseAlpha
                                    }
                                    .background(onlineGreen, CircleShape)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(surfaceColor, CircleShape)
                                .padding(1.5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(onlineGreen, CircleShape)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurfaceColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (savedMessages) {
                    Text(
                        Localizations.getString("local_storage", appLanguage),
                        fontSize = 11.sp,
                        color = onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                } else {
                    val transportType = P2PMessageRelay.getPeerTransportType(context, peerName)
                    val rttMs = P2PMessageRelay.peerRttMs[peerName]
                    ConnectionTypeBadge(
                        transportType = transportType,
                        rttMs = rttMs,
                        appLanguage = appLanguage,
                        primaryColor = primaryColor,
                        onSurfaceVariant = onSurfaceVariant,
                        modifier = Modifier.clickable { onOpenConnectionMode() },
                    )
                }
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
                modifier = Modifier.size(42.dp),
            ) {
                Icon(painterResource(R.drawable.ic_shield_status), "Verify", tint = shieldColor, modifier = Modifier.size(18.dp))
            }
        }
        IconButton(onClick = { onSearchModeChange(true) }, modifier = Modifier.size(42.dp)) {
            Icon(painterResource(R.drawable.ic_menu_search), "Search", tint = onSurfaceColor.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        }
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(42.dp)) {
                Text("⋮", fontSize = 20.sp, color = onSurfaceColor.copy(alpha = 0.85f), fontWeight = FontWeight.Bold)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(surfaceColor)) {
                if (!savedMessages) {
                    DropdownMenuItem(
                        text = { Text(Localizations.tr(appLanguage, "Режим соединения", "Connection Mode", "Verbindungsmodus", "Modo de conexión", "Mode de connexion", "Modo de conexão", tr = "Bağlantı Modu"), color = onSurfaceColor) },
                        onClick = { showMenu = false; onOpenConnectionMode() },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_shield_status),
                                contentDescription = "Connection Mode",
                                tint = primaryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(Localizations.tr(appLanguage, "Переподключить соединение", "Reconnect Connection", "Verbindung neu herstellen", "Reconectar conexión", "Reconnecter la connexion", "Reconectar conexão", tr = "Bağlantıyı Yenile"), color = onSurfaceColor) },
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
                                Localizations.tr(appLanguage, "Включить уведомления", "Unmute Notifications", "Stummschaltung aufheben", "Reactivar notificaciones", "Réactiver les notifications", "Desativar mudo", tr = "Bildirimleri Aç")
                            } else {
                                Localizations.tr(appLanguage, "Выключить уведомления", "Mute Notifications", "Stummschalten", "Silenciar notificaciones", "Masquer les notifications", "Silenciar notificação", tr = "Bildirimleri Kapat")
                            },
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
                                Localizations.tr(appLanguage, "Разрешить пересылку", "Allow Forwarding", "Weiterleiten erlauben", "Permitir reenvío", "Autoriser le transfert", "Permitir encaminhamento", tr = "İletmeye İzin Ver")
                            } else {
                                Localizations.tr(appLanguage, "Запретить пересылку", "Restrict Forwarding", "Weiterleitung einschränken", "Restringir reenvío", "Restreindre le transfert", "Restringir encaminhamento", tr = "İletmeyi Kısıtla")
                            },
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
                    text = { Text(Localizations.tr(appLanguage, "Установить обои", "Set Wallpaper", "Hintergrund festlegen", "Establecer fondo", "Définir le fond d'écran", "Definir papel de parede", tr = "Duvar Kağıdı Belirle"), color = onSurfaceColor) },
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
                    text = { Text(Localizations.tr(appLanguage, "Очистить историю", "Clear History", "Verlauf löschen", "Borrar historial", "Effacer l'historique", "Limpar histórico", tr = "Geçmişi Temizle"), color = Color.Red) },
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
                        text = { Text(Localizations.tr(appLanguage, "Заблокировать", "Block User", "Benutzer blockieren", "Bloquear usuario", "Bloquer l'utilisateur", "Bloquear usuário", tr = "Engelle"), color = Color.Red) },
                        onClick = { showMenu = false; onBlockPeer() },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_block),
                                contentDescription = "Block User",
                                tint = Color.Red,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(Localizations.tr(appLanguage, "Удалить чат", "Delete Chat", "Chat löschen", "Eliminar chat", "Supprimer le chat", "Excluir conversa", tr = "Sohbeti Sil"), color = Color.Red) },
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
    } // end Row
    } // end Surface

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

@Composable
internal fun ConnectionTypeBadge(
    transportType: TransportType,
    rttMs: Long?,
    appLanguage: String,
    primaryColor: Color,
    onSurfaceVariant: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Filter RTT metric: noise reduction (< 50ms omitted), formatted as integer ms
    val shouldShowRtt = rttMs != null && rttMs >= 50
    val rttText = if (shouldShowRtt) " • ${rttMs} ms" else ""

    val badgeData = when (transportType) {
        TransportType.ONION -> {
            val text = "Tor Onion"
            val purple = Color(0xFFA78BFA)
            BadgeData(
                bg = Color(0xFF7C3AED).copy(alpha = 0.18f),
                content = purple,
                iconRes = R.drawable.ic_tor,
                iconEmoji = null,
                text = "$text$rttText",
            )
        }
        TransportType.DIRECT -> {
            val text = "Direct P2P"
            val green = Color(0xFF10B981)
            BadgeData(
                bg = green.copy(alpha = 0.15f),
                content = green,
                iconRes = null,
                iconEmoji = "⚡",
                text = "$text$rttText",
            )
        }
        TransportType.YGGDRASIL -> {
            val yggMode = com.example.twopchat.config.P2PPreferences.getYggdrasilMode(context)
            val modeSuffix = if (yggMode == com.example.twopchat.config.P2PPreferences.YggdrasilMode.PROXY) " (Proxy)" else " (VPN)"
            val text = "Yggdrasil$modeSuffix"
            val cyan = Color(0xFF06B6D4)
            BadgeData(
                bg = cyan.copy(alpha = 0.15f),
                content = cyan,
                iconRes = null,
                iconEmoji = "🌐",
                text = "$text$rttText",
            )
        }
        TransportType.DISCONNECTED -> {
            val text = if (appLanguage == "Русский") "Не в сети" else "Offline"
            BadgeData(
                bg = onSurfaceVariant.copy(alpha = 0.08f),
                content = onSurfaceVariant.copy(alpha = 0.70f),
                iconRes = null,
                iconEmoji = "○",
                text = text,
            )
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(badgeData.bg)
            .border(0.5.dp, badgeData.content.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        if (badgeData.iconRes != null) {
            Icon(
                painter = painterResource(badgeData.iconRes),
                contentDescription = badgeData.text,
                tint = Color.Unspecified,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
        } else if (badgeData.iconEmoji != null) {
            Text(
                text = badgeData.iconEmoji,
                fontSize = 10.sp,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Text(
            text = badgeData.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = badgeData.content,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

private data class BadgeData(
    val bg: Color,
    val content: Color,
    val iconRes: Int?,
    val iconEmoji: String?,
    val text: String,
)

