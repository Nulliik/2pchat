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
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.connectionTransportLabel
import com.example.twopchat.copyTextToClipboard
import com.example.twopchat.theme.StealthBlack
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
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
        mediaList.filter { it.attachmentType == "IMAGE" }.map { it.attachmentUri!! }
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
        if (appLanguage == "Русский") "Медиа" else "Media",
        if (appLanguage == "Русский") "Файлы" else "Files",
        if (appLanguage == "Русский") "Ссылки" else "Links",
        if (appLanguage == "Русский") "Голосовые" else "Voice"
    )

    // Initials helper
    val initials = if (peerName == "Saved Messages") {
        "🔖"
    } else if (peerName.contains(" ")) {
        peerName.split(" ").map { it.take(1) }.joinToString("")
    } else peerName.take(2).uppercase()

    val isDark = surfaceColor.luminance() < 0.5f
    val mainBg = if (isDark) Color(0xFF0C0E10) else Color(0xFFF4F6F9)
    val cardBg = if (isDark) Color(0xFF16191C) else Color(0xFFFFFFFF)

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
                    text = if (appLanguage == "Русский") "Профиль" else "Profile",
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
                            val avatarBitmap = P2PMessageRelay.peerAvatars[peerName]
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
                            Text(
                                text = if (peerName == "Saved Messages") Localizations.getString("saved_messages_title", appLanguage) else peerName,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = onSurfaceColor
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            val isOnline = P2PMessageRelay.peerSessionStates[peerName] == true
                            val statusText = if (peerName == "Saved Messages") {
                                Localizations.getString("local_storage", appLanguage)
                            } else if (isOnline) {
                                val transportName = connectionTransportLabel(
                                    rawTransport = P2PMessageRelay.peerConnectionTransports[peerName],
                                    endpoint = P2PMessageRelay.peerEndpoints[peerName],
                                    appLanguage = appLanguage,
                                )
                                if (appLanguage == "Русский") "В сети • $transportName" else "Online • $transportName"
                            } else {
                                if (appLanguage == "Русский") "Не в сети" else "Offline"
                            }
                            Text(
                                text = statusText,
                                fontSize = 13.sp,
                                color = if (isOnline && peerName != "Saved Messages") primaryColor else onSurfaceVariant
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
                                    label = if (appLanguage == "Русский") "Чат" else "Chat",
                                    primaryColor = primaryColor,
                                    onSurfaceVariant = onSurfaceVariant,
                                    onClick = onBack
                                )

                                // Mute Notifications Toggle button (functional)
                                if (peerName != "Saved Messages") {
                                    ProfileActionButton(
                                        iconRes = if (isMuted) R.drawable.ic_notifications_off else R.drawable.ic_notifications,
                                        label = if (isMuted) {
                                            if (appLanguage == "Русский") "Вкл. звук" else "Unmute"
                                        } else {
                                            if (appLanguage == "Русский") "Выкл. звук" else "Mute"
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
                            text = if (appLanguage == "Русский") "Информация" else "Information",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Personal Search Address Row
                        val fingerprint = remember(peerName) {
                            val sp = com.example.twopchat.P2PPreferences.prefs(context)
                            sp.getString("peer_fingerprint_$peerName", "") ?: ""
                        }
                        val stableCode = remember(peerName, fingerprint) {
                            val sp = com.example.twopchat.P2PPreferences.prefs(context)
                            val savedCode = sp.getString("discovery_code_$peerName", null)
                            if (!savedCode.isNullOrBlank()) {
                                savedCode
                            } else {
                                val seedString = fingerprint.ifBlank { peerName }
                                val hash = seedString.hashCode().toLong() and 0xFFFFFFFFL
                                val random = java.util.Random(hash)
                                val alphabet = "23456789bcdfghjkmnpqrstvwxyz"
                                (1..3).joinToString("-") {
                                    (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
                                }
                            }
                        }
                        val searchAddress = remember(peerName, stableCode) {
                            "$peerName#$stableCode"
                        }

                        InfoDetailRow(
                            label = if (appLanguage == "Русский") "Личный адрес" else "Personal address",
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

                        val peerAboutMe = remember(peerName) {
                            val sp = com.example.twopchat.P2PPreferences.prefs(context)
                            sp.getString("peer_about_me_$peerName", "") ?: ""
                        }
                        if (peerAboutMe.isNotEmpty()) {
                            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))
                            InfoDetailRow(
                                label = if (appLanguage == "Русский") "О себе" else "About me",
                                value = peerAboutMe,
                                onSurfaceColor = onSurfaceColor,
                                onSurfaceVariant = onSurfaceVariant
                            )
                        }

                        if (peerName != "Saved Messages") {
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
                                        text = if (appLanguage == "Русский") "Статус верификации" else "Verification Status",
                                        fontSize = 11.sp,
                                        color = onSurfaceVariant
                                    )
                                    Text(
                                        text = if (isVerified) {
                                            if (appLanguage == "Русский") "Личность подтверждена" else "Identity Verified"
                                        } else {
                                            if (appLanguage == "Русский") "Личность не подтверждена" else "Identity Not Verified"
                                        },
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = onSurfaceColor
                                    )
                                }
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
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = primaryColor,
                        edgePadding = 8.dp,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
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
                                text = if (appLanguage == "Русский") "Медиафайлов нет" else "No media shared",
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
                                    val uri = item.attachmentUri!!
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
                                text = if (appLanguage == "Русский") "Файлов нет" else "No files shared",
                                onSurfaceVariant = onSurfaceVariant
                            )
                        }
                    } else {
                        items(filesList) { item ->
                            val file = File(item.attachmentUri!!)
                            val exists = file.exists()
                            val fileSize = if (exists) formatFileSize(file.length()) else "n/a"
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
                                                if (exists) {
                                                    try {
                                                        val contentUri = androidx.core.content.FileProvider.getUriForFile(
                                                            context,
                                                            "${context.packageName}.fileprovider",
                                                            file,
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
                                text = if (appLanguage == "Русский") "Ссылок нет" else "No links shared",
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
                                text = if (appLanguage == "Русский") "Голосовых сообщений нет" else "No voice messages shared",
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
