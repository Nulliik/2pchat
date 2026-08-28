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

    Surface(
        color = surfaceColor,
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f))
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
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
        Spacer(Modifier.width(12.dp))
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
        // fingerprint↔nickname mapping has not yet propagated to peerSessionStates.
        val isOnline = P2PMessageRelay.isPeerOnline(context, peerName)
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
                        text = { Text(Localizations.tr(appLanguage, "Режим соединения", "Connection Mode", "Verbindungsmodus", "Modo de conexión", "Mode de connexion", "Modo de conexão"), color = onSurfaceColor) },
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
                        text = { Text(Localizations.tr(appLanguage, "Переподключить соединение", "Reconnect Connection", "Verbindung neu herstellen", "Reconectar conexión", "Reconnecter la connexion", "Reconectar conexão"), color = onSurfaceColor) },
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
                                Localizations.tr(appLanguage, "Включить уведомления", "Unmute Notifications", "Stummschaltung aufheben", "Reactivar notificaciones", "Réactiver les notifications", "Desativar mudo")
                            } else {
                                Localizations.tr(appLanguage, "Выключить уведомления", "Mute Notifications", "Stummschalten", "Silenciar notificaciones", "Masquer les notifications", "Silenciar notificação")
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
                                Localizations.tr(appLanguage, "Разрешить пересылку", "Allow Forwarding", "Weiterleiten erlauben", "Permitir reenvío", "Autoriser le transfert", "Permitir encaminhamento")
                            } else {
                                Localizations.tr(appLanguage, "Запретить пересылку", "Restrict Forwarding", "Weiterleitung einschränken", "Restringir reenvío", "Restreindre le transfert", "Restringir encaminhamento")
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
                    text = { Text(Localizations.tr(appLanguage, "Установить обои", "Set Wallpaper", "Hintergrund festlegen", "Establecer fondo", "Définir le fond d'écran", "Definir papel de parede"), color = onSurfaceColor) },
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
                    text = { Text(Localizations.tr(appLanguage, "Очистить историю", "Clear History", "Verlauf löschen", "Borrar historial", "Effacer l'historique", "Limpar histórico"), color = Color.Red) },
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
                        text = { Text(Localizations.tr(appLanguage, "Заблокировать", "Block User", "Benutzer blockieren", "Bloquear usuario", "Bloquer l'utilisateur", "Bloquear usuário"), color = Color.Red) },
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
                        text = { Text(Localizations.tr(appLanguage, "Удалить чат", "Delete Chat", "Chat löschen", "Eliminar chat", "Supprimer le chat", "Excluir conversa"), color = Color.Red) },
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
    val (badgeBg, contentColor, iconRes, label) = when (transportType) {
        TransportType.ONION -> {
            val text = "Tor Onion"
            val rttText = rttMs?.let { " • ${it}ms" }.orEmpty()
            val purple = Color(0xFFA78BFA)
            BadgeData(
                Color(0xFF7C3AED).copy(alpha = 0.20f),
                purple,
                R.drawable.ic_tor,
                "$text$rttText",
            )
        }
        TransportType.DIRECT -> {
            val text = "Direct P2P"
            val rttText = rttMs?.let { " • ${it}ms" }.orEmpty()
            val green = Color(0xFF22C55E)
            BadgeData(
                green.copy(alpha = 0.15f),
                green,
                null,
                "$text$rttText",
            )
        }
        TransportType.YGGDRASIL -> {
            val yggMode = com.example.twopchat.config.P2PPreferences.getYggdrasilMode(context)
            val modeSuffix = if (yggMode == com.example.twopchat.config.P2PPreferences.YggdrasilMode.PROXY) " (Proxy)" else " (VPN)"
            val text = "Yggdrasil$modeSuffix"
            val rttText = rttMs?.let { " • ${it}ms" }.orEmpty()
            val teal = if (yggMode == com.example.twopchat.config.P2PPreferences.YggdrasilMode.PROXY) Color(0xFF06B6D4) else Color(0xFF10B981)
            BadgeData(
                teal.copy(alpha = 0.16f),
                teal,
                null,
                "$text$rttText",
            )
        }
        TransportType.DISCONNECTED -> {
            val text = if (appLanguage == "Русский") "Не в сети" else "Offline"
            BadgeData(
                onSurfaceVariant.copy(alpha = 0.10f),
                onSurfaceVariant.copy(alpha = 0.70f),
                null,
                text,
            )
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(badgeBg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = "Tor Onion",
                tint = Color.Unspecified,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
        } else {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(contentColor)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

private data class BadgeData(
    val bg: Color,
    val content: Color,
    val icon: Int?,
    val text: String,
)

