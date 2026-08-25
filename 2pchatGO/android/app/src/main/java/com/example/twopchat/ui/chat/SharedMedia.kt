package com.example.twopchat.ui.chat

import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow


import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.R
import com.example.twopchat.data.Localizations
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.relay.TransportType
import com.example.twopchat.relay.connectionTransportLabel
import com.example.twopchat.copyTextToClipboard
import com.example.twopchat.theme.StealthBlack
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.Locale

private val linkRegex = Regex("""(https?://[^\s]+)""")

private fun Message.hasAvailableAttachment(): Boolean {
    val path = attachmentUri
    return !path.isNullOrBlank() && ("://" in path || File(path).isFile)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.getDefault(), "%,.1f %s", value, units[digitGroups])
}

private fun formatDate(epochMs: Long, language: String): String {
    val pattern = if (language == "Русский") "dd.MM.yyyy HH:mm" else "MM/dd/yyyy hh:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedMediaScreen(
    peerName: String,
    messages: List<Message>,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    appLanguage: String,
    isVerified: Boolean,
    isMuted: Boolean,
    onToggleMute: (Boolean) -> Unit,
    onAvatarClick: (Bitmap) -> Unit,
    onImageClick: (List<String>, Int) -> Unit,
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateToMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var currentPeerName by remember(peerName) { mutableStateOf(peerName) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var newNicknameInput by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var isSelectMode by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateListOf<Message>() }

    LaunchedEffect(selectedTab) {
        isSelectMode = false
        selectedItems.clear()
    }

    val mediaList = remember(messages) {
        messages.filter {
            (it.attachmentType == "IMAGE" || it.attachmentType == "VIDEO") &&
                it.hasAvailableAttachment()
        }.reversed()
    }
    val mediaUris = remember(mediaList) {
        mediaList.filter { it.attachmentType == "IMAGE" }.mapNotNull { it.attachmentUri }
    }
    val filesList = remember(messages) {
        messages.filter {
            it.attachmentType == "FILE" && it.hasAvailableAttachment()
        }.reversed()
    }
    val linksList = remember(messages) {
        messages.flatMap { msg ->
            linkRegex.findAll(msg.text).map { match ->
                Pair(msg, match.value)
            }.toList()
        }.reversed()
    }
    val voiceList = remember(messages) {
        messages.filter {
            it.attachmentType == "VOICE" && it.hasAvailableAttachment()
        }.reversed()
    }

    val tabs = listOf(
        Localizations.tr(appLanguage, "Медиа", "Media", "Medien", "Multimedia", "Média", "Mídia"),
        Localizations.tr(appLanguage, "Файлы", "Files", "Dateien", "Archivos", "Fichiers", "Arquivos"),
        Localizations.tr(appLanguage, "Ссылки", "Links", "Links", "Enlaces", "Liens", "Links"),
        Localizations.tr(appLanguage, "Голосовые", "Voice", "Sprachnachrichten", "Voz", "Vocal", "Voz")
    )

    // Initials helper
    val initials = if (currentPeerName == "Saved Messages") {
        "🔖"
    } else if (currentPeerName.contains(" ")) {
        currentPeerName.split(" ").map { it.take(1) }.joinToString("")
    } else currentPeerName.take(2).uppercase()

    val isDark = surfaceColor.luminance() < 0.5f
    val mainBg = if (isDark) Color(0xFF0C0E10) else Color(0xFFF4F6F9)
    val cardBg = if (isDark) Color(0xFF16191C) else Color(0xFFFFFFFF)

    var showConnectionModeSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(mainBg)
    ) {
        // Custom Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(surfaceColor)
                .border(width = 0.5.dp, color = onSurfaceColor.copy(alpha = 0.05f))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectMode) {
                IconButton(
                    onClick = {
                        isSelectMode = false
                        selectedItems.clear()
                    },
                    modifier = Modifier.background(onSurfaceColor.copy(alpha = 0.03f), shape = CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel selection",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = selectedItems.size.toString(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
                Spacer(modifier = Modifier.weight(1f))
                if (selectedItems.size == 1) {
                    IconButton(
                        onClick = {
                            val selectedMessage = selectedItems.first()
                            onNavigateToMessage(selectedMessage.id)
                            isSelectMode = false
                            selectedItems.clear()
                        }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_eye),
                            contentDescription = "Go to message",
                            tint = primaryColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.background(onSurfaceColor.copy(alpha = 0.03f), shape = CircleShape)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_back_arrow),
                        contentDescription = "Back",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = Localizations.tr(appLanguage, "Профиль", "Profile", "Profil", "Perfil", "Profil", "Perfil"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Profile Card (Large photo, details, quick actions)
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(
                                    primaryColor.copy(alpha = 0.10f),
                                    surfaceColor.copy(alpha = 0.90f),
                                    primaryColor.copy(alpha = 0.04f)
                                )
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .border(0.5.dp, primaryColor.copy(alpha = 0.20f), RoundedCornerShape(20.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                            // Large Avatar Box
                            val context = androidx.compose.ui.platform.LocalContext.current
                            var highResAvatarBitmap by remember(currentPeerName) { mutableStateOf<Bitmap?>(null) }
                            LaunchedEffect(currentPeerName) {
                                withContext(Dispatchers.IO) {
                                    highResAvatarBitmap = P2PMessageRelay.getOriginalAvatar(context, currentPeerName)
                                }
                            }
                            val avatarBitmap = highResAvatarBitmap ?: P2PMessageRelay.peerAvatars[currentPeerName]
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(96.dp)
                                    .background(primaryColor.copy(alpha = 0.1f), shape = CircleShape)
                                    .then(
                                        if (avatarBitmap != null) {
                                            Modifier.clickable { onAvatarClick(avatarBitmap) }
                                        } else Modifier
                                    )
                            ) {
                                if (avatarBitmap != null) {
                                    Image(
                                        bitmap = avatarBitmap.asImageBitmap(),
                                        contentDescription = "Avatar",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                    )
                                } else if (initials == "🔖") {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_saved_messages),
                                        contentDescription = "Saved Messages",
                                        tint = primaryColor,
                                        modifier = Modifier.size(44.dp)
                                    )
                                } else {
                                    Text(
                                        text = initials,
                                        color = primaryColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 32.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            val isRawKey = remember(currentPeerName) {
                                P2PMessageRelay.isRawFingerprint(currentPeerName) ||
                                    (currentPeerName.length == 44 && currentPeerName.endsWith("="))
                            }
                            val displayText = when {
                                currentPeerName == "Saved Messages" -> Localizations.getString("saved_messages_title", appLanguage)
                                isRawKey -> "${currentPeerName.take(8)}...${currentPeerName.takeLast(6)}"
                                else -> currentPeerName
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = displayText,
                                    fontSize = if (isRawKey) 18.sp else 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceColor,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                                if (currentPeerName != "Saved Messages") {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            newNicknameInput = if (isRawKey) "" else currentPeerName
                                            showRenameDialog = true
                                        },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(primaryColor.copy(alpha = 0.12f), shape = CircleShape)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_edit),
                                            contentDescription = "Edit Name",
                                            tint = primaryColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            if (isRawKey) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    color = primaryColor.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.clickable {
                                        newNicknameInput = ""
                                        showRenameDialog = true
                                    }
                                ) {
                                    Text(
                                        text = Localizations.tr(appLanguage, "+ Задать имя контакта", "+ Set contact name", "+ Name festlegen", "+ Establecer nombre", "+ Définir le nom", "+ Definir nome"),
                                        color = primaryColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            val isOnline = P2PMessageRelay.peerSessionStates[currentPeerName] == true
                            val statusText = if (currentPeerName == "Saved Messages") {
                                Localizations.getString("local_storage", appLanguage)
                            } else if (isOnline) {
                                val transportName = connectionTransportLabel(
                                    rawTransport = P2PMessageRelay.peerConnectionTransports[currentPeerName],
                                    endpoint = P2PMessageRelay.peerEndpoints[currentPeerName],
                                    appLanguage = appLanguage,
                                )
                                Localizations.tr(appLanguage, "В сети • $transportName", "Online • $transportName", "Online • $transportName", "En línea • $transportName", "En ligne • $transportName", "Online • $transportName")
                            } else {
                                Localizations.tr(appLanguage, "Не в сети", "Offline", "Offline", "Desconectado", "Hors ligne", "Offline")
                            }
                            Text(
                                text = statusText,
                                fontSize = 13.sp,
                                color = if (isOnline && currentPeerName != "Saved Messages") primaryColor else onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // Action Buttons Row (Telegram look)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Chat button
                                ProfileActionButton(
                                    iconRes = R.drawable.ic_menu_chats,
                                    label = Localizations.tr(appLanguage, "Чат", "Chat", "Chat", "Chat", "Discussion", "Conversar"),
                                    primaryColor = primaryColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    onClick = onBack
                                )

                                // Mute Notifications Toggle button (functional)
                                if (currentPeerName != "Saved Messages") {
                                    ProfileActionButton(
                                        iconRes = if (isMuted) R.drawable.ic_notifications_off else R.drawable.ic_notifications,
                                        label = if (isMuted) {
                                            Localizations.tr(appLanguage, "Вкл. звук", "Unmute", "Stumm aus", "Sonido", "Réactiver", "Som ligado")
                                        } else {
                                            Localizations.tr(appLanguage, "Выкл. звук", "Mute", "Stummschalten", "Silenciar", "Masquer", "Silenciar")
                                        },
                                        primaryColor = primaryColor,
                                        onSurfaceVariant = onSurfaceVariant,
                                        onClick = { onToggleMute(!isMuted) }
                                    )
                                }
                        }
                    }
                }
            }

            // User Info Details Section (Verification Status, Shield)
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(
                                    primaryColor.copy(alpha = 0.10f),
                                    surfaceColor.copy(alpha = 0.90f),
                                    primaryColor.copy(alpha = 0.04f)
                                )
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .border(0.5.dp, primaryColor.copy(alpha = 0.20f), RoundedCornerShape(20.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = Localizations.tr(appLanguage, "Информация", "Information", "Informationen", "Información", "Informations", "Informações"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Personal Search Address Row
                        val fingerprint = remember(currentPeerName) {
                            val sp = com.example.twopchat.config.P2PPreferences.prefs(context)
                            sp.getString("peer_fingerprint_$currentPeerName", "") ?: ""
                        }
                        val stableCode = remember(currentPeerName, fingerprint) {
                            val sp = com.example.twopchat.config.P2PPreferences.prefs(context)
                            val savedCode = sp.getString("discovery_code_$currentPeerName", null)
                            if (!savedCode.isNullOrBlank()) {
                                savedCode
                            } else {
                                val seedString = fingerprint.ifBlank { currentPeerName }
                                val hash = seedString.hashCode().toLong() and 0xFFFFFFFFL
                                val random = java.util.Random(hash)
                                val alphabet = "23456789bcdfghjkmnpqrstvwxyz"
                                (1..3).joinToString("-") {
                                    (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
                                }
                            }
                        }
                        val searchAddress = remember(currentPeerName, stableCode) {
                            "$currentPeerName#$stableCode"
                        }

                        InfoDetailRow(
                            label = Localizations.tr(appLanguage, "Личный адрес", "Personal address", "Persönliche Adresse", "Dirección personal", "Adresse personnelle", "Endereço pessoal"),
                            value = searchAddress,
                            onSurfaceColor = onSurfaceColor,
                            onSurfaceVariant = onSurfaceVariant,
                            isMonospace = true,
                            onClick = {
                                copyTextToClipboard(context, "2PChat contact", searchAddress)
                                Toast.makeText(
                                    context,
                                    if (appLanguage == "Русский") "Адрес скопирован" else "Address copied",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )

                        val peerAboutMe = remember(currentPeerName, fingerprint) {
                            val sp = com.example.twopchat.config.P2PPreferences.prefs(context)
                            val db = com.example.twopchat.data.ChatDatabaseHelper.getInstance(context)
                            val byName = sp.getString("peer_about_me_$currentPeerName", null)?.trim()
                            val byFp = if (fingerprint.isNotBlank()) sp.getString("peer_about_me_$fingerprint", null)?.trim() else null
                            val byDb = db.getPeerAboutMe(currentPeerName)?.trim()
                            val byDbFp = if (fingerprint.isNotBlank()) db.getPeerAboutMe(fingerprint)?.trim() else null
                            val localProfile = if (currentPeerName == "Saved Messages" || currentPeerName == sp.getString("username_profile", "")) {
                                sp.getString("about_me_profile", null)?.trim()
                            } else null
                            byName?.takeIf { it.isNotEmpty() }
                                ?: byFp?.takeIf { it.isNotEmpty() }
                                ?: byDb?.takeIf { it.isNotEmpty() }
                                ?: byDbFp?.takeIf { it.isNotEmpty() }
                                ?: localProfile?.takeIf { it.isNotEmpty() }
                                ?: ""
                        }

                        HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))
                        InfoDetailRow(
                            label = Localizations.tr(appLanguage, "О себе", "About me", "Über mich", "Sobre mí", "À propos", "Sobre mim"),
                            value = if (peerAboutMe.isNotEmpty()) {
                                peerAboutMe
                            } else {
                                Localizations.tr(appLanguage, "Не указано", "Not specified", "Nicht angegeben", "No especificado", "Non spécifié", "Não especificado")
                            },
                            onSurfaceColor = if (peerAboutMe.isNotEmpty()) onSurfaceColor else onSurfaceVariant.copy(alpha = 0.55f),
                            onSurfaceVariant = onSurfaceVariant,
                            onClick = if (peerAboutMe.isNotEmpty()) {
                                {
                                    copyTextToClipboard(context, "About me", peerAboutMe)
                                    Toast.makeText(
                                        context,
                                        if (appLanguage == "Русский") "Текст скопирован" else "Copied",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } else null
                        )

                        if (currentPeerName != "Saved Messages") {
                            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))

                            // Verification Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_shield_status),
                                    contentDescription = "Shield",
                                    tint = if (isVerified) Color(0xFF4CAF50) else Color(0xFFFFC107),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = Localizations.tr(appLanguage, "Статус верификации", "Verification Status", "Verifizierungsstatus", "Estado de verificación", "Statut de vérification", "Status de verificação"),
                                        fontSize = 11.sp,
                                        color = onSurfaceVariant
                                    )
                                    Text(
                                        text = if (isVerified) {
                                            Localizations.tr(appLanguage, "Личность подтверждена", "Identity Verified", "Identität verifiziert", "Identidad verificada", "Identité vérifiée", "Identidade verificada")
                                        } else {
                                            Localizations.tr(appLanguage, "Личность не подтверждена", "Identity Not Verified", "Identität nicht verifiziert", "Identidad no verificada", "Identité non vérifiée", "Identidade não verificada")
                                        },
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = onSurfaceColor
                                    )
                                }
                            }

                            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))

                            // Connection Mode Row
                            val transportPref = P2PPreferences.getPeerTransportPreference(context, currentPeerName)
                            val prefLabel = when (transportPref) {
                                P2PPreferences.PeerTransportPreference.AUTO -> if (appLanguage == "Русский") "⚡ Авто" else "⚡ Auto"
                                P2PPreferences.PeerTransportPreference.TOR_ONLY -> if (appLanguage == "Русский") "🟣 Только Tor" else "🟣 Tor Only"
                                P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY -> if (appLanguage == "Русский") "🟢 Только Yggdrasil" else "🟢 Yggdrasil Only"
                                P2PPreferences.PeerTransportPreference.DIRECT_ONLY -> if (appLanguage == "Русский") "🟢 Только Direct P2P" else "🟢 Direct P2P Only"
                            }
                            val transportType = P2PMessageRelay.getPeerTransportType(currentPeerName)
                            val activeLabel = when (transportType) {
                                TransportType.ONION -> "Tor Onion"
                                TransportType.DIRECT -> "Direct P2P"
                                TransportType.YGGDRASIL -> "Yggdrasil"
                                TransportType.DISCONNECTED -> if (appLanguage == "Русский") "Не в сети" else "Offline"
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showConnectionModeSheet = true }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_shield_status),
                                    contentDescription = "Connection Mode",
                                    tint = primaryColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = Localizations.tr(appLanguage, "Режим соединения", "Connection Mode", "Verbindungsmodus", "Modo de conexión", "Mode de connexion", "Modo de conexão"),
                                        fontSize = 11.sp,
                                        color = onSurfaceVariant
                                    )
                                    Text(
                                        text = "$prefLabel ($activeLabel)",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = onSurfaceColor
                                    )
                                }
                                Text(
                                    text = "›",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceVariant.copy(alpha = 0.60f)
                                )
                            }
                        }
                    }
                }
            }

            // Category Tab selector
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = primaryColor,
                        edgePadding = 8.dp,
                        indicator = {
                            TabRowDefaults.PrimaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(selectedTab),
                                color = primaryColor
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        tabs.forEachIndexed { index, label ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selectedTab == index) primaryColor else onSurfaceVariant
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Tab Content List
            when (selectedTab) {
                0 -> {
                    // Media Tab (Grid of images)
                    if (mediaList.isEmpty()) {
                        item {
                            EmptyStateView(
                                text = Localizations.tr(appLanguage, "Медиафайлов нет", "No media shared", "Keine Medien vorhanden", "Sin archivos multimedia", "Aucun média partagé", "Nenhuma mídia compartilhada"),
                                onSurfaceVariant = onSurfaceVariant
                            )
                        }
                    } else {
                        val chunkedMedia = mediaList.chunked(3)
                        items(chunkedMedia) { rowItems ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                rowItems.forEach { item ->
                                    val uri = item.attachmentUri.orEmpty()
                                    val isSelected = selectedItems.contains(item)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .background(onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                            .clip(RoundedCornerShape(12.dp))
                                            .combinedClickable(
                                                onClick = {
                                                    if (isSelectMode) {
                                                        if (isSelected) {
                                                            selectedItems.remove(item)
                                                            if (selectedItems.isEmpty()) isSelectMode = false
                                                        } else {
                                                            selectedItems.add(item)
                                                        }
                                                    } else {
                                                        if (item.attachmentType == "VIDEO") {
                                                            onVideoClick(uri)
                                                        } else {
                                                            val idx = mediaUris.indexOf(uri)
                                                            if (idx != -1) {
                                                                onImageClick(mediaUris, idx)
                                                            }
                                                        }
                                                    }
                                                },
                                                onLongClick = {
                                                    if (!isSelectMode) {
                                                        isSelectMode = true
                                                        selectedItems.clear()
                                                        selectedItems.add(item)
                                                    }
                                                }
                                            )
                                    ) {
                                        val isVideo = item.attachmentType == "VIDEO"
                                        val bitmap = if (isVideo) {
                                            rememberVideoThumbnail(uri)
                                        } else {
                                            rememberSampledImage(uri, 200, 200)
                                        }

                                        if (bitmap != null) {
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = if (isVideo) "Shared video" else "Shared image",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop,
                                            )
                                        } else {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.fillMaxSize()
                                            ) {
                                                Icon(
                                                    painter = painterResource(id = if (isVideo) R.drawable.ic_attach_file else R.drawable.ic_attach_gallery),
                                                    contentDescription = if (isVideo) "Video preview" else "Image preview",
                                                    tint = onSurfaceVariant.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }

                                        if (isVideo) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.Center)
                                                    .size(32.dp)
                                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.PlayArrow,
                                                    contentDescription = "Play Video",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        
                                        if (isSelectMode) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(if (isSelected) Color.Black.copy(alpha = 0.3f) else Color.Transparent)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(8.dp)
                                                    .size(22.dp)
                                                    .background(
                                                        color = if (isSelected) primaryColor else Color.Black.copy(alpha = 0.35f),
                                                        shape = CircleShape
                                                    )
                                                    .border(
                                                        width = 1.5.dp,
                                                        color = Color.White,
                                                        shape = CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Selected",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (rowItems.size < 3) {
                                    repeat(3 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // Files Tab
                    if (filesList.isEmpty()) {
                        item {
                            EmptyStateView(
                                text = Localizations.tr(appLanguage, "Файлов нет", "No files shared", "Keine Dateien vorhanden", "Sin archivos compartidos", "Aucun fichier partagé", "Nenhum arquivo compartilhado"),
                                onSurfaceVariant = onSurfaceVariant
                            )
                        }
                    } else {
                        items(filesList) { item ->
                            val filePath = item.attachmentUri
                            val file = filePath?.let(::File)
                            val validFile = file?.takeIf { it.exists() }
                            val fileSize = validFile?.let { formatFileSize(it.length()) } ?: "n/a"
                            val fileDate = formatDate(item.sentAtEpochMs, appLanguage)

                            val isSelected = selectedItems.contains(item)
                            Card(
                                colors = CardDefaults.cardColors(containerColor = if (isSelected) primaryColor.copy(alpha = 0.08f) else cardBg),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.5.dp,
                                        color = if (isSelected) primaryColor else onSurfaceColor.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clip(RoundedCornerShape(14.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (isSelectMode) {
                                                if (isSelected) {
                                                    selectedItems.remove(item)
                                                    if (selectedItems.isEmpty()) isSelectMode = false
                                                } else {
                                                    selectedItems.add(item)
                                                }
                                            } else {
                                                if (validFile != null) {
                                                    try {
                                                        val contentUri = androidx.core.content.FileProvider.getUriForFile(
                                                            context,
                                                            "${context.packageName}.fileprovider",
                                                            validFile,
                                                        )
                                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                                            setDataAndType(contentUri, "*/*")
                                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                        }
                                                        context.startActivity(intent)
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, if (appLanguage == "Русский") "Не удалось открыть файл" else "Cannot open file", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectMode) {
                                                isSelectMode = true
                                                selectedItems.clear()
                                                selectedItems.add(item)
                                            }
                                        }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSelectMode) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .background(
                                                    color = if (isSelected) primaryColor else Color.Transparent,
                                                    shape = CircleShape
                                                )
                                                .border(
                                                    width = 1.5.dp,
                                                    color = if (isSelected) primaryColor else onSurfaceVariant.copy(alpha = 0.5f),
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_attach_file),
                                        contentDescription = "File icon",
                                        tint = primaryColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.attachmentName ?: "Shared File",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = onSurfaceColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "$fileSize • $fileDate",
                                            fontSize = 11.sp,
                                            color = onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    // Links Tab
                    if (linksList.isEmpty()) {
                        item {
                            EmptyStateView(
                                text = Localizations.tr(appLanguage, "Ссылок нет", "No links shared", "Keine Links vorhanden", "Sin enlaces compartidos", "Aucun lien partagé", "Nenhum link compartilhado"),
                                onSurfaceVariant = onSurfaceVariant
                            )
                        }
                    } else {
                        items(linksList) { (msg, url) ->
                            val linkDate = formatDate(msg.sentAtEpochMs, appLanguage)
                            val isSelected = selectedItems.contains(msg)
                            Card(
                                colors = CardDefaults.cardColors(containerColor = if (isSelected) primaryColor.copy(alpha = 0.08f) else cardBg),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.5.dp,
                                        color = if (isSelected) primaryColor else onSurfaceColor.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clip(RoundedCornerShape(14.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (isSelectMode) {
                                                if (isSelected) {
                                                    selectedItems.remove(msg)
                                                    if (selectedItems.isEmpty()) isSelectMode = false
                                                } else {
                                                    selectedItems.add(msg)
                                                }
                                            } else {
                                                try {
                                                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    context.startActivity(browserIntent)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, if (appLanguage == "Русский") "Не удалось открыть ссылку" else "Cannot open link", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectMode) {
                                                isSelectMode = true
                                                selectedItems.clear()
                                                selectedItems.add(msg)
                                            }
                                        }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSelectMode) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .background(
                                                    color = if (isSelected) primaryColor else Color.Transparent,
                                                    shape = CircleShape
                                                )
                                                .border(
                                                    width = 1.5.dp,
                                                    color = if (isSelected) primaryColor else onSurfaceVariant.copy(alpha = 0.5f),
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_quick_link),
                                        contentDescription = "Link icon",
                                        tint = primaryColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = url,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = primaryColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = linkDate,
                                            fontSize = 11.sp,
                                            color = onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                3 -> {
                    // Voice Tab
                    if (voiceList.isEmpty()) {
                        item {
                            EmptyStateView(
                                text = Localizations.tr(appLanguage, "Голосовых сообщений нет", "No voice messages shared", "Keine Sprachnachrichten", "Sin mensajes de voz", "Aucun message vocal", "Nenhum áudio compartilhado"),
                                onSurfaceVariant = onSurfaceVariant
                            )
                        }
                    } else {
                        items(voiceList) { item ->
                            val voiceDate = formatDate(item.sentAtEpochMs, appLanguage)
                            val isSelected = selectedItems.contains(item)
                            Card(
                                colors = CardDefaults.cardColors(containerColor = if (isSelected) primaryColor.copy(alpha = 0.08f) else cardBg),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.5.dp,
                                        color = if (isSelected) primaryColor else onSurfaceColor.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clip(RoundedCornerShape(14.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (isSelectMode) {
                                                if (isSelected) {
                                                    selectedItems.remove(item)
                                                    if (selectedItems.isEmpty()) isSelectMode = false
                                                } else {
                                                    selectedItems.add(item)
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectMode) {
                                                isSelectMode = true
                                                selectedItems.clear()
                                                selectedItems.add(item)
                                            }
                                        }
                                    )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isSelectMode) {
                                            Box(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .background(
                                                        color = if (isSelected) primaryColor else Color.Transparent,
                                                        shape = CircleShape
                                                    )
                                                    .border(
                                                        width = 1.5.dp,
                                                        color = if (isSelected) primaryColor else onSurfaceVariant.copy(alpha = 0.5f),
                                                        shape = CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Selected",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                        }
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_voice_mic),
                                            contentDescription = "Voice icon",
                                            tint = primaryColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = voiceDate,
                                            fontSize = 11.sp,
                                            color = onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    VoiceMessagePlayer(
                                        filePath = item.attachmentUri,
                                        isMine = false,
                                        primaryColor = primaryColor,
                                        contentColor = onSurfaceColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConnectionModeSheet && currentPeerName != "Saved Messages") {
        ConnectionModeBottomSheet(
            peerName = currentPeerName,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = onSurfaceVariant,
            onDismiss = { showConnectionModeSheet = false }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = {
                Text(
                    text = Localizations.tr(appLanguage, "Имя контакта", "Contact Name", "Kontaktname", "Nombre de contacto", "Nom du contact", "Nome do contato"),
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
            },
            text = {
                Column {
                    Text(
                        text = Localizations.tr(appLanguage, "Задайте удобное имя для этого собеседника:", "Set a display name for this contact:", "Legen Sie einen Namen für diesen Kontakt fest:", "Establece un nombre para este contacto:", "Définissez un nom pour ce contact :", "Defina um nome para este contato:"),
                        fontSize = 14.sp,
                        color = onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = newNicknameInput,
                        onValueChange = { newNicknameInput = it },
                        placeholder = {
                            Text(Localizations.tr(appLanguage, "Введите имя...", "Enter name...", "Namen eingeben...", "Introduce el nombre...", "Entrez le nom...", "Insira o nome..."))
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = onSurfaceVariant.copy(alpha = 0.3f),
                            focusedTextColor = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newNicknameInput.trim()
                        if (trimmed.isNotBlank()) {
                            val success = P2PMessageRelay.renamePeer(context, currentPeerName, trimmed)
                            if (success) {
                                currentPeerName = trimmed
                                Toast.makeText(
                                    context,
                                    Localizations.tr(appLanguage, "Имя контакта обновлено", "Contact name updated", "Kontaktname aktualisiert", "Nombre actualizado", "Nom mis à jour", "Nome atualizado"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            showRenameDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    Text(
                        text = Localizations.tr(appLanguage, "Сохранить", "Save", "Speichern", "Guardar", "Enregistrer", "Salvar"),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(
                        text = Localizations.tr(appLanguage, "Отмена", "Cancel", "Abbrechen", "Cancelar", "Annuler", "Cancelar"),
                        color = onSurfaceVariant
                    )
                }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun ProfileActionButton(
    iconRes: Int,
    label: String,
    primaryColor: Color,
    onSurfaceVariant: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable { onClick() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .background(primaryColor.copy(alpha = 0.08f), shape = CircleShape)
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = primaryColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun InfoDetailRow(
    label: String,
    value: String,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    isMonospace: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = if (isMonospace) androidx.compose.ui.text.font.FontFamily.Monospace else androidx.compose.ui.text.font.FontFamily.Default,
                color = onSurfaceColor
            )
        }
        if (onClick != null) {
            Icon(
                painter = painterResource(id = R.drawable.ic_copy),
                contentDescription = "Copy",
                tint = onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(20.dp)
                    .padding(start = 4.dp)
            )
        }
    }
}

@Composable
fun EmptyStateView(
    text: String,
    onSurfaceVariant: Color
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(16.dp)
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}
