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
import androidx.compose.ui.graphics.graphicsLayer
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    val currentPeerName = peerName
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
        Localizations.tr(appLanguage, "Медиа", "Media", "Medien", "Multimedia", "Média", "Mídia", tr = "Medya"),
        Localizations.tr(appLanguage, "Файлы", "Files", "Dateien", "Archivos", "Fichiers", "Arquivos", tr = "Dosyalar"),
        Localizations.tr(appLanguage, "Ссылки", "Links", "Links", "Enlaces", "Liens", "Links", tr = "Bağlantılar"),
        Localizations.tr(appLanguage, "Голосовые", "Voice", "Sprachnachrichten", "Voz", "Vocal", "Voz", tr = "Sesli Mesajlar")
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

    var highResAvatarBitmap by remember(currentPeerName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(currentPeerName) {
        withContext(Dispatchers.IO) {
            highResAvatarBitmap = P2PMessageRelay.getOriginalAvatar(context, currentPeerName)
        }
        if (currentPeerName != "Saved Messages") {
            P2PMessageRelay.requestPeerProfile(context, currentPeerName)
            P2PMessageRelay.shareAvatar(context, currentPeerName, force = true)
        }
    }
    val avatarBitmap = highResAvatarBitmap ?: P2PMessageRelay.peerAvatars[currentPeerName]

    val isRawKey = remember(currentPeerName) {
        P2PMessageRelay.isRawFingerprint(currentPeerName) ||
            (currentPeerName.length == 44 && currentPeerName.endsWith("="))
    }
    val displayText = when {
        currentPeerName == "Saved Messages" -> Localizations.getString("saved_messages_title", appLanguage)
        isRawKey -> "${currentPeerName.take(8)}...${currentPeerName.takeLast(6)}"
        else -> currentPeerName
    }

    val scrollState = androidx.compose.foundation.lazy.rememberLazyListState()
    val collapseFraction by remember {
        derivedStateOf {
            if (scrollState.firstVisibleItemIndex > 0) 1f
            else (scrollState.firstVisibleItemScrollOffset / 280f).coerceIn(0f, 1f)
        }
    }

    var preferenceVersion by remember { mutableStateOf(0) }
    DisposableEffect(context) {
        val sp = com.example.twopchat.config.P2PPreferences.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && (key.startsWith("peer_about_me_") || key.startsWith("peer_fingerprint_") || key == "about_me_profile")) {
                preferenceVersion++
            }
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sp.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(mainBg)
    ) {
        // Custom Top Bar with Collapsing Header Animation
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
                Spacer(modifier = Modifier.width(12.dp))

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // Title "Профиль" (visible when top is expanded)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = collapseFraction < 0.35f,
                        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(150)),
                        exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(150))
                    ) {
                        Text(
                            text = Localizations.tr(appLanguage, "Профиль", "Profile", "Profil", "Perfil", "Profil", "Perfil", tr = "Profil"),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor
                        )
                    }

                    // Mini avatar + Contact name (fades in as user scrolls down)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = collapseFraction >= 0.35f,
                        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(180)) + slideInVertically(
                            initialOffsetY = { it / 2 },
                            animationSpec = androidx.compose.animation.core.tween(180)
                        ),
                        exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(150)) + slideOutVertically(
                            targetOffsetY = { it / 2 },
                            animationSpec = androidx.compose.animation.core.tween(150)
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(primaryColor.copy(alpha = 0.12f), shape = CircleShape)
                                    .border(1.dp, primaryColor.copy(alpha = 0.35f), CircleShape)
                                    .clip(CircleShape)
                            ) {
                                if (avatarBitmap != null) {
                                    Image(
                                        bitmap = avatarBitmap.asImageBitmap(),
                                        contentDescription = "Mini Avatar",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else if (initials == "🔖") {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_saved_messages),
                                        contentDescription = "Saved Messages",
                                        tint = primaryColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Text(
                                        text = initials,
                                        color = primaryColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = displayText,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = onSurfaceColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isVerified) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_check_bold),
                                            contentDescription = "Verified",
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = if (isVerified) {
                                        Localizations.tr(appLanguage, "Подтвержденный контакт", "Verified Contact", "Verifizierter Kontakt", "Contacto verificado", "Contact vérifié", "Contato verificado", tr = "Doğrulanmış Kişi")
                                    } else {
                                        Localizations.tr(appLanguage, "Медиа и файлы", "Media & files", "Medien & Dateien", "Archivos y medios", "Médias & fichiers", "Mídias e arquivos", tr = "Medya ve Dosyalar")
                                    },
                                    fontSize = 11.sp,
                                    color = if (isVerified) Color(0xFF4CAF50) else onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = scrollState,
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
                            // Large Avatar Box with smooth scale & alpha transition
                            val avatarScale = (1f - (collapseFraction * 0.15f)).coerceIn(0.85f, 1f)
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(96.dp)
                                    .graphicsLayer {
                                        scaleX = avatarScale
                                        scaleY = avatarScale
                                    }
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
                                Localizations.tr(appLanguage, "В сети • $transportName", "Online • $transportName", "Online • $transportName", "En línea • $transportName", "En ligne • $transportName", "Online • $transportName", tr = "Çevrimiçi • $transportName")
                            } else {
                                Localizations.tr(appLanguage, "Не в сети", "Offline", "Offline", "Desconectado", "Hors ligne", "Offline", tr = "Çevrimdışı")
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
                                    label = Localizations.tr(appLanguage, "Чат", "Chat", "Chat", "Chat", "Discussion", "Conversar", tr = "Sohbet"),
                                    primaryColor = primaryColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    onClick = onBack
                                )

                                // Mute Notifications Toggle button (functional)
                                if (currentPeerName != "Saved Messages") {
                                    ProfileActionButton(
                                        iconRes = if (isMuted) R.drawable.ic_notifications_off else R.drawable.ic_notifications,
                                        label = if (isMuted) {
                                            Localizations.tr(appLanguage, "Вкл. звук", "Unmute", "Stumm aus", "Sonido", "Réactiver", "Som ligado", tr = "Sesi Aç")
                                        } else {
                                            Localizations.tr(appLanguage, "Выкл. звук", "Mute", "Stummschalten", "Silenciar", "Masquer", "Silenciar", tr = "Sessize Al")
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
                            text = Localizations.tr(appLanguage, "Информация", "Information", "Informationen", "Información", "Informations", "Informações", tr = "Bilgi"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        val fingerprint = remember(currentPeerName, preferenceVersion) {
                            com.example.twopchat.config.P2PPreferences.findPeerFingerprint(context, currentPeerName).orEmpty()
                        }

                        val stableCode = remember(currentPeerName, fingerprint, preferenceVersion) {
                            val sp = com.example.twopchat.config.P2PPreferences.prefs(context)
                            val bySp = sp.getString("discovery_code_$currentPeerName", null)?.trim()
                                ?: (if (fingerprint.isNotBlank()) sp.getString("discovery_code_$fingerprint", null)?.trim() else null)
                                ?: sp.getString("discovery_code_${currentPeerName.substringBefore('#')}", null)?.trim()
                            if (!bySp.isNullOrBlank()) {
                                bySp
                            } else {
                                val seedString = fingerprint.ifBlank { currentPeerName.substringBefore('#') }
                                val digest = java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(seedString.toByteArray(Charsets.UTF_8))
                                digest.take(4).joinToString("") { "%02x".format(it) }
                            }
                        }

                        val searchAddress = remember(currentPeerName, stableCode) {
                            val baseName = currentPeerName.substringBefore("#").trim().ifEmpty { currentPeerName }
                            "$baseName#$stableCode"
                        }

                        InfoDetailRow(
                            label = Localizations.tr(appLanguage, "Личный адрес", "Personal address", "Persönliche Adresse", "Dirección personal", "Adresse personnelle", "Endereço pessoal", tr = "Kişisel Adres"),
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

                        val peerAboutMe = remember(currentPeerName, fingerprint, preferenceVersion) {
                            com.example.twopchat.config.P2PPreferences.getPeerAboutMe(context, currentPeerName, fingerprint)
                        }

                        HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))
                        InfoDetailRow(
                            label = Localizations.tr(appLanguage, "О себе", "About me", "Über mich", "Sobre mí", "À propos", "Sobre mim", tr = "Hakkımda"),
                            value = if (peerAboutMe.isNotEmpty()) {
                                peerAboutMe
                            } else {
                                Localizations.tr(appLanguage, "Не указано", "Not specified", "Nicht angegeben", "No especificado", "Non spécifié", "Não especificado", tr = "Belirtilmemiş")
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
                                        text = Localizations.tr(appLanguage, "Статус верификации", "Verification Status", "Verifizierungsstatus", "Estado de verificación", "Statut de vérification", "Status de verificação", tr = "Doğrulama Durumu"),
                                        fontSize = 11.sp,
                                        color = onSurfaceVariant
                                    )
                                    Text(
                                        text = if (isVerified) {
                                            Localizations.tr(appLanguage, "Личность подтверждена", "Identity Verified", "Identität verifiziert", "Identidad verificada", "Identité vérifiée", "Identidade verificada", tr = "Kimlik Doğrulandı")
                                        } else {
                                            Localizations.tr(appLanguage, "Личность не подтверждена", "Identity Not Verified", "Identität nicht verifiziert", "Identidad no verificada", "Identité non vérifiée", "Identidade não verificada", tr = "Kimlik Doğrulanmadı")
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
                                        text = Localizations.tr(appLanguage, "Режим соединения", "Connection Mode", "Verbindungsmodus", "Modo de conexión", "Mode de connexion", "Modo de conexão", tr = "Bağlantı Modu"),
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

            // Sticky Category Tab selector
            stickyHeader(key = "sticky_tabs") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(mainBg)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
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
            }

            // Tab Content List
            when (selectedTab) {
                0 -> {
                    // Media Tab (Grid of images)
                    if (mediaList.isEmpty()) {
                        item {
                            SharedMediaEmptyState(
                                iconRes = R.drawable.ic_attach_gallery,
                                title = Localizations.tr(appLanguage, "Медиафайлов пока нет", "No media shared yet", "Noch keine Medien", "Sin archivos multimedia", "Aucun média partagé", "Nenhuma mídia compartilhada", tr = "Henüz paylaşılan medya yok"),
                                description = Localizations.tr(appLanguage, "Фотографии и видео из этого диалога будут отображаться здесь", "Photos and videos shared in this chat will appear here", "Fotos und Videos aus diesem Chat werden hier angezeigt", "Las fotos y videos compartidos aparecerán aquí", "Les photos et vidéos apparaîtront ici", "Fotos e vídeos compartilhados aparecerão aqui", tr = "Bu sohbette paylaşılan fotoğraflar ve videolar burada görünecek"),
                                primaryColor = primaryColor,
                                onSurfaceColor = onSurfaceColor,
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
                            SharedMediaEmptyState(
                                iconRes = R.drawable.ic_attach_file,
                                title = Localizations.tr(appLanguage, "Файлов пока нет", "No files shared yet", "Noch keine Dateien", "Sin archivos compartidos", "Aucun fichier partagé", "Nenhum arquivo compartilhado", tr = "Henüz dosya yok"),
                                description = Localizations.tr(appLanguage, "Документы, архивы и файлы из этого диалога будут доступны здесь", "Documents, archives, and files shared in this chat will be available here", "Dokumente und Dateien aus diesem Chat werden hier gespeichert", "Los documentos y archivos compartidos aparecerán aquí", "Les documents et fichiers apparaîtront ici", "Documentos e arquivos compartilhados aparecerão aqui", tr = "Bu sohbette paylaşılan belgeler ve dosyalar burada bulunacak"),
                                primaryColor = primaryColor,
                                onSurfaceColor = onSurfaceColor,
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
                            SharedMediaEmptyState(
                                iconRes = R.drawable.ic_quick_link,
                                title = Localizations.tr(appLanguage, "Ссылок пока нет", "No links shared yet", "Noch keine Links", "Sin enlaces compartidos", "Aucun lien partagé", "Nenhum link compartilhado", tr = "Henüz bağlantı yok"),
                                description = Localizations.tr(appLanguage, "Все веб-ссылки, упомянутые в сообщениях, автоматически собираются здесь", "Web links mentioned in messages will automatically be collected here", "Web-Links aus Nachrichten werden hier gesammelt", "Los enlaces web compartidos se recopilarán aquí", "Les liens web seront regroupés ici", "Links compartilhados serão reunidos aqui", tr = "Mesajlardaki web bağlantıları otomatik olarak burada toplanır"),
                                primaryColor = primaryColor,
                                onSurfaceColor = onSurfaceColor,
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
                            SharedMediaEmptyState(
                                iconRes = R.drawable.ic_voice_mic,
                                title = Localizations.tr(appLanguage, "Голосовых сообщений нет", "No voice messages yet", "Keine Sprachnachrichten", "Sin mensajes de voz", "Aucun message vocal", "Nenhuma mensagem de voz", tr = "Henüz sesli mesaj yok"),
                                description = Localizations.tr(appLanguage, "Голосовые сообщения и аудиозаписи будут сохраняться в этом разделе", "Voice messages and audio recordings will be saved in this section", "Sprachnachrichten und Audio werden hier gespeichert", "Los mensajes de voz y audios se guardarán aquí", "Les messages vocaux seront enregistrés ici", "Mensagens de voz serão salvas aqui", tr = "Sesli mesajlar ve ses kayıtları bu bölümde saklanacaktır"),
                                primaryColor = primaryColor,
                                onSurfaceColor = onSurfaceColor,
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
fun SharedMediaEmptyState(
    iconRes: Int,
    title: String,
    description: String,
    primaryColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 36.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Glowing dual-halo icon box
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(68.dp)
                    .background(primaryColor.copy(alpha = 0.08f), shape = CircleShape)
                    .border(1.5.dp, primaryColor.copy(alpha = 0.25f), CircleShape)
                    .padding(5.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(primaryColor.copy(alpha = 0.14f), shape = CircleShape)
                ) {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = title,
                        tint = primaryColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = description,
                fontSize = 12.sp,
                color = onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
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
