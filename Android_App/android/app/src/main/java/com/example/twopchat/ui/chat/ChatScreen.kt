package com.example.twopchat.ui.chat

import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.AppDiagnostics
import com.example.twopchat.theme.StealthBlack
import com.example.twopchat.data.Localizations
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Message(
    val id: String,
    val text: String,
    val isMe: Boolean,
    val timestamp: String,
    val attachmentType: String? = null, // "IMAGE", "FILE", "LOCATION"
    val attachmentUri: String? = null,
    val attachmentName: String? = null
)

private fun buildSystemMessage(text: String): Message {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    return Message(
        id = "system-" + System.currentTimeMillis().toString(),
        text = text,
        isMe = false,
        timestamp = time,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    peerName: String,
    appLanguage: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler {
        onBack()
    }
    
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sharedPrefs = remember(context) { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    val username = remember { sharedPrefs.getString("username_profile", "User Identity") ?: "User Identity" }
    val peerStatuses by AppDiagnostics.peerStatuses.collectAsState()
    var isVerified by remember(peerName) { mutableStateOf(sharedPrefs.getBoolean("verified_peer_${peerName}", false)) }
    var showVerifyDialog by remember { mutableStateOf(false) }
    var mockMismatchToggle by remember(peerName) { mutableStateOf(sharedPrefs.getBoolean("mock_mismatch_${peerName}", false)) }
    val activeFingerprint = remember(peerName, mockMismatchToggle) {
        if (mockMismatchToggle) {
            "WARNING_MISMATCHED_ATTACK_KEY_999999"
        } else {
            when (peerName) {
                "Eleanor Vance" -> "2TFcRb7mE1eAnOrVaNcE9823471029837419"
                "Liam O'Connor" -> "2TFcRb7mLiAmOcOnNoR1029384756102938"
                "Sarah Chen" -> "2TFcRb7mSaRaHcHeN92837410293847102938"
                else -> "2TFcRb7m" + peerName.hashCode().toString().padStart(16, 'x')
            }
        }
    }



    // Determine initial messages based on peer
    val initialMessages = remember(peerName) {
        when (peerName) {
            "Eleanor Vance" -> mutableStateListOf(
                Message("1", "Hey! Did you check out the new design mockups?", false, "12:35"),
                Message("2", "Yes, they look fantastic! Especially the dark theme.", true, "12:36"),
                Message("3", "The designs look fantastic!", true, "12:36")
            )
            "Liam O'Connor" -> mutableStateListOf(
                Message("1", "Did we get the testing keys from the server?", false, "11:15"),
                Message("2", "Yes, I loaded them into the P2P transport module.", true, "11:18"),
                Message("3", "Thanks for the feedback.", false, "11:20")
            )
            "Sarah Chen" -> mutableStateListOf(
                Message("1", "The direct connection looks very stable.", false, "09:40"),
                Message("2", "Awesome. I'll verify the latency metrics.", true, "09:42"),
                Message("3", "Last message, your work is great!", false, "09:45")
            )
            "Saved Messages" -> mutableStateListOf(
                Message("1", Localizations.getString("saved_messages_welcome", appLanguage), true, "12:00")
            )
            else -> mutableStateListOf(
                Message("1", "Connection established secure channel.", false, "12:00")
            )
        }
    }

    DisposableEffect(peerName) {
        com.example.twopchat.P2PMessageRelay.onMessageReceived = { sender, text ->
            if (sender == peerName) {
                coroutineScope.launch {
                    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                    initialMessages.add(Message(System.currentTimeMillis().toString(), text, false, time))
                }
            }
        }
        com.example.twopchat.P2PMessageRelay.onNetworkStatusChanged = { changedPeer, status ->
            if (changedPeer == peerName) {
                AppDiagnostics.setPeerStatus(changedPeer, status)
            }
        }
        onDispose {
            com.example.twopchat.P2PMessageRelay.onMessageReceived = null
            com.example.twopchat.P2PMessageRelay.onNetworkStatusChanged = null
        }
    }

    LaunchedEffect(peerName) {
        // Silent connection handshake ping to register ourselves on the peer's device
        val endpoint = com.example.twopchat.P2PMessageRelay.peerEndpoints[peerName]
        if (endpoint != null) {
            AppDiagnostics.setPeerStatus(peerName, "Connecting to $endpoint")
            com.example.twopchat.P2PMessageRelay.sendMessage(endpoint, username, "") { success, error ->
                if (!success) {
                    coroutineScope.launch {
                        val detail = error ?: "unknown error"
                        initialMessages.add(buildSystemMessage("P2P connection failed: $detail"))
                    }
                }
            }
        } else {
            AppDiagnostics.setPeerStatus(peerName, "No known endpoint yet")
        }
    }

    var inputText by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(false) }
    var selectedMessageForOptions by remember { mutableStateOf<Message?>(null) }

    // Auto replies repository
    val autoReplies = remember(peerName) {
        when (peerName) {
            "Eleanor Vance" -> listOf(
                "Thanks, let me know if you need anything else!",
                "By the way, I'm working on the active sessions screen now.",
                "Let's sync up over the P2P link later!"
            )
            "Liam O'Connor" -> listOf(
                "Great! I see the connection peer-to-peer active now.",
                "Let's check if the double ratchet session keys rotate correctly.",
                "Acknowledged. I'll run the Yggdrasil daemon benchmarks."
            )
            "Sarah Chen" -> listOf(
                "Thank you! Let me know when you run the metrics on your end.",
                "I will be online for another hour checking the handshake packets.",
                "Perfect. Security rules look completely green."
            )
            else -> listOf("Message received securely.")
        }
    }
    var replyIndex by remember { mutableStateOf(0) }

    // Picker Launchers
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val localPath = com.example.twopchat.ui.onboarding.saveImageToInternalStorage(context, it)
            if (localPath != null) {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                initialMessages.add(
                    Message(
                        id = System.currentTimeMillis().toString(),
                        text = "Sent an image",
                        isMe = true,
                        timestamp = time,
                        attachmentType = "IMAGE",
                        attachmentUri = localPath
                    )
                )
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap?.let {
            val file = File(context.cacheDir, "camera_capture_${System.currentTimeMillis()}.jpg")
            try {
                val out = FileOutputStream(file)
                it.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
                out.close()
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                initialMessages.add(
                    Message(
                        id = System.currentTimeMillis().toString(),
                        text = "Captured a photo",
                        isMe = true,
                        timestamp = time,
                        attachmentType = "IMAGE",
                        attachmentUri = file.absolutePath
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            var fileName = "Document.pdf"
            context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            initialMessages.add(
                Message(
                    id = System.currentTimeMillis().toString(),
                    text = fileName,
                    isMe = true,
                    timestamp = time,
                    attachmentType = "FILE",
                    attachmentName = fileName
                )
            )
        }
    }

    var showLocationDialog by remember { mutableStateOf(false) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val networkStatus = peerStatuses[peerName] ?: "Idle"

    // Scroll to bottom when messages list size changes
    LaunchedEffect(initialMessages.size, isTyping) {
        if (initialMessages.isNotEmpty() || isTyping) {
            val lastIndex = initialMessages.size - 1 + (if (isTyping) 1 else 0)
            if (lastIndex >= 0) {
                listState.animateScrollToItem(lastIndex)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .safeDrawingPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: Glassmorphic surface feel with border
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceColor)
                    .border(width = 0.5.dp, color = onSurfaceColor.copy(alpha = 0.05f))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.background(onSurfaceColor.copy(alpha = 0.03f), shape = CircleShape)
                ) {
                    Text("←", fontSize = 18.sp, color = onSurfaceColor, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(12.dp))

                val displayName = if (peerName == "Saved Messages") {
                    Localizations.getString("saved_messages_title", appLanguage)
                } else {
                    peerName
                }

                // Avatar Mockup
                val initials = if (peerName == "Saved Messages") {
                    "🔖"
                } else if (peerName.contains(" ")) {
                    peerName.split(" ").map { it.take(1) }.joinToString("")
                } else peerName.take(2).uppercase()
                
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .background(primaryColor.copy(alpha = 0.1f), shape = CircleShape)
                ) {
                    if (initials == "🔖") {
                        Icon(
                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_saved_messages),
                            contentDescription = "Saved Messages",
                            tint = primaryColor,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Text(
                            text = initials,
                            color = primaryColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                val isMismatch = mockMismatchToggle
                val shieldColor = when {
                    isMismatch -> Color(0xFFF44336) // Red
                    isVerified -> Color(0xFF4CAF50) // Green
                    else -> Color(0xFFFFC107) // Yellow
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (peerName != "Saved Messages") {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .background(primaryColor, shape = CircleShape)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        Text(
                            text = if (peerName == "Saved Messages") {
                                Localizations.getString("local_storage", appLanguage)
                            } else if (peerName == "Liam O'Connor") {
                                "Yggdrasil Link"
                            } else {
                                "Direct P2P Link"
                            },
                            fontSize = 11.sp,
                            color = onSurfaceVariant
                        )
                    }
                }

                if (peerName != "Saved Messages") {
                    IconButton(
                        onClick = { showVerifyDialog = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_shield_status),
                            contentDescription = "Verify",
                            tint = shieldColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Secure Badge (Always standard and clean)
                Box(
                    modifier = Modifier
                        .background(primaryColor.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = Localizations.getString("secure_badge", appLanguage),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                }
            }

            if (peerName != "Saved Messages") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(primaryColor.copy(alpha = 0.08f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(primaryColor, shape = CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${Localizations.getString("network_status", appLanguage)}: $networkStatus",
                        color = onSurfaceColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Messages List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
            ) {
                itemsIndexed(initialMessages) { index, msg ->
                    val alignment = if (msg.isMe) Alignment.End else Alignment.Start
                    val bubbleShape = if (msg.isMe) {
                        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
                    } else {
                        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
                    }

                    // Gradient for outgoing bubbles; solid surface for incoming
                    val bubbleModifier = if (msg.isMe) {
                        Modifier.background(
                            brush = Brush.linearGradient(
                                colors = listOf(primaryColor, primaryColor.copy(alpha = 0.85f))
                            ),
                            shape = bubbleShape
                        )
                    } else {
                        Modifier.background(
                            color = surfaceColor,
                            shape = bubbleShape
                        )
                    }

                    val textColor = if (msg.isMe) {
                        if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color.White
                    } else onSurfaceColor

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = alignment
                    ) {
                        Box(
                            modifier = bubbleModifier
                                .combinedClickable(
                                    onClick = { selectedMessageForOptions = msg },
                                    onLongClick = { selectedMessageForOptions = msg }
                                )
                                // Subtle border for incoming bubbles
                                .then(if (!msg.isMe) Modifier.border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), bubbleShape) else Modifier)
                                .padding(horizontal = 16.dp, vertical = 11.dp)
                                .widthIn(max = 280.dp)
                        ) {
                            when (msg.attachmentType) {
                                "IMAGE" -> {
                                    val bitmap = remember(msg.attachmentUri) {
                                        if (msg.attachmentUri != null) {
                                            try {
                                                BitmapFactory.decodeFile(msg.attachmentUri)
                                            } catch (e: Exception) {
                                                null
                                            }
                                        } else null
                                    }
                                    if (bitmap != null) {
                                        Column {
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = "Image attachment",
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 200.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                            )
                                            if (!msg.text.startsWith("Sent an image") && !msg.text.startsWith("Captured a photo")) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = msg.text,
                                                    color = textColor,
                                                    fontSize = 15.sp,
                                                    lineHeight = 20.sp
                                                )
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = msg.text,
                                            color = textColor,
                                            fontSize = 15.sp,
                                            lineHeight = 20.sp
                                        )
                                    }
                                }
                                "FILE" -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(if (msg.isMe) Color.White.copy(alpha = 0.2f) else primaryColor.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp))
                                        ) {
                                            Icon(
                                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_attach_file),
                                                contentDescription = "Document",
                                                tint = if (msg.isMe) {
                                                    if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color.White
                                                } else primaryColor,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = msg.attachmentName ?: "Document.pdf",
                                                color = textColor,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "Encrypted Document",
                                                color = textColor.copy(alpha = 0.7f),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                                "LOCATION" -> {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_attach_location),
                                                contentDescription = "Location",
                                                tint = if (msg.isMe) {
                                                    if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color.White
                                                } else primaryColor,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = msg.text,
                                                color = textColor,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(100.dp)
                                                .background(if (msg.isMe) Color.White.copy(alpha = 0.15f) else onSurfaceColor.copy(alpha = 0.05f), shape = RoundedCornerShape(8.dp))
                                                .border(0.5.dp, textColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    text = msg.attachmentName ?: "Coordinates",
                                                    color = textColor,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = "Secure Peer Location",
                                                    color = textColor.copy(alpha = 0.6f),
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    Text(
                                        text = msg.text,
                                        color = textColor,
                                        fontSize = 15.sp,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = msg.timestamp,
                                color = onSurfaceVariant.copy(alpha = 0.6f),
                                fontSize = 10.sp
                            )
                            if (msg.isMe) {
                                Spacer(modifier = Modifier.width(4.dp))
                                val hasIncomingAfter = if (index < initialMessages.size - 1) {
                                    initialMessages.subList(index + 1, initialMessages.size).any { !it.isMe }
                                } else false
                                
                                val isRead = hasIncomingAfter || isTyping || peerName == "Saved Messages"
                                val statusText = if (isRead) "✓✓" else "✓"
                                val statusColor = if (isRead) primaryColor else onSurfaceVariant.copy(alpha = 0.4f)
                                
                                Text(
                                    text = statusText,
                                    color = statusColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (isTyping) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(surfaceColor, shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                                    .padding(horizontal = 16.dp, vertical = 11.dp)
                            ) {
                                Text(
                                    text = Localizations.getString("typing", appLanguage),
                                    color = onSurfaceVariant,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // Input Bar & Action Triggers
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceColor)
                    .border(width = 0.5.dp, color = onSurfaceColor.copy(alpha = 0.05f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Expanded Attachment Panel mockup
                AnimatedVisibility(
                    visible = showAttachments,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    AttachmentPanel(
                        primaryColor = primaryColor,
                        surfaceVariant = surfaceVariant,
                        onSurfaceColor = onSurfaceColor,
                        onAttachmentClick = { type ->
                            showAttachments = false
                            when (type) {
                                "Camera" -> {
                                    try {
                                        cameraLauncher.launch(null)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Camera launch failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                "Gallery" -> galleryLauncher.launch("image/*")
                                "File" -> fileLauncher.launch("*/*")
                                "Location" -> showLocationDialog = true
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Attachment toggle button
                    IconButton(
                        onClick = { showAttachments = !showAttachments },
                        modifier = Modifier
                            .size(44.dp)
                            .background(onSurfaceColor.copy(alpha = 0.03f), shape = CircleShape)
                    ) {
                        if (showAttachments) {
                            Text(
                                text = "×",
                                fontSize = 22.sp,
                                color = primaryColor,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_attach_paperclip),
                                contentDescription = "Attach",
                                tint = primaryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    val isDark = backgroundColor == StealthBlack
                    val inputBg = if (isDark) Color(0xFF0F1012) else Color(0xFFE4E7EC)

                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text(Localizations.getString("write_placeholder", appLanguage), color = onSurfaceVariant.copy(alpha = 0.6f)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = inputBg,
                            unfocusedContainerColor = inputBg,
                            focusedTextColor = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(22.dp),
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier
                            .weight(1f)
                            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(22.dp))
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                val userText = inputText.trim()
                                inputText = ""
                                showAttachments = false
                                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                
                                // Add user message
                                initialMessages.add(Message(System.currentTimeMillis().toString(), userText, true, time))

                                // Persist in shared preferences last message list
                                val activeSet = sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")) ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")
                                if (!activeSet.contains(peerName)) {
                                    val newSet = activeSet.toMutableSet()
                                    newSet.add(peerName)
                                    sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                                }
                                sharedPrefs.edit().putString("last_msg_$peerName", "You: $userText").apply()

                                // Send over real TCP socket if endpoint is resolved
                                val endpoint = com.example.twopchat.P2PMessageRelay.peerEndpoints[peerName]
                                if (endpoint != null) {
                                    com.example.twopchat.P2PMessageRelay.sendMessage(endpoint, username, userText) { success, error ->
                                        if (!success) {
                                            coroutineScope.launch {
                                                val detail = error ?: "unknown error"
                                                initialMessages.add(buildSystemMessage("Message delivery failed: $detail"))
                                                Toast.makeText(context, "P2P send failed: $detail", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                } else {
                                    initialMessages.add(buildSystemMessage("No known endpoint for this peer"))
                                    Toast.makeText(context, "No known endpoint for this peer", Toast.LENGTH_LONG).show()
                                }

                                // Trigger mock reply with typing delay (only for demo mockup contacts)
                                if (peerName == "Eleanor Vance" || peerName == "Liam O'Connor" || peerName == "Sarah Chen") {
                                    coroutineScope.launch {
                                        delay(1000)
                                        isTyping = true
                                        delay(1500)
                                        isTyping = false
                                        val replyText = autoReplies[replyIndex % autoReplies.size]
                                        replyIndex++
                                        val replyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                        initialMessages.add(
                                            Message(
                                                System.currentTimeMillis().toString(),
                                                replyText,
                                                false,
                                                replyTime
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .background(primaryColor, shape = CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_send_airplane),
                            contentDescription = "Send",
                            tint = if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Message Options Overlay Panel
        if (selectedMessageForOptions != null) {
            val msg = selectedMessageForOptions!!
            AlertDialog(
                onDismissRequest = { selectedMessageForOptions = null },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { selectedMessageForOptions = null }) {
                        Text(Localizations.getString("close", appLanguage), color = primaryColor)
                    }
                },
                title = { Text(Localizations.getString("msg_options", appLanguage), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = onSurfaceColor) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(msg.text))
                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                    selectedMessageForOptions = null
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                           Text(Localizations.getString("copy_text", appLanguage), fontSize = 15.sp, color = onSurfaceColor)
                        }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    Toast.makeText(context, "Reply triggered", Toast.LENGTH_SHORT).show()
                                    selectedMessageForOptions = null
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(Localizations.getString("reply_msg", appLanguage), fontSize = 15.sp, color = onSurfaceColor)
                        }
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    initialMessages.remove(msg)
                                    Toast.makeText(context, "Message deleted from session", Toast.LENGTH_SHORT).show()
                                    selectedMessageForOptions = null
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(Localizations.getString("delete_msg", appLanguage), fontSize = 15.sp, color = Color.Red)
                        }
                    }
                },
                containerColor = surfaceColor,
                shape = RoundedCornerShape(20.dp)
            )
        }

        if (showLocationDialog) {
            val locationsList = listOf(
                "Moscow" to "55.7558° N, 37.6173° E",
                "New York" to "40.7128° N, -74.0060° E",
                "London" to "51.5074° N, -0.1278° W",
                "Tokyo" to "35.6762° N, 139.6503° E"
            )
            AlertDialog(
                onDismissRequest = { showLocationDialog = false },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showLocationDialog = false }) {
                        Text(Localizations.getString("close", appLanguage), color = primaryColor)
                    }
                },
                title = { Text("Select Location", fontWeight = FontWeight.Bold, color = onSurfaceColor) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        locationsList.forEach { loc ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = surfaceColor),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showLocationDialog = false
                                        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                        initialMessages.add(
                                            Message(
                                                id = System.currentTimeMillis().toString(),
                                                text = loc.first,
                                                isMe = true,
                                                timestamp = time,
                                                attachmentType = "LOCATION",
                                                attachmentName = loc.second
                                            )
                                        )
                                        if (peerName != "Saved Messages") {
                                            coroutineScope.launch {
                                                delay(1000)
                                                isTyping = true
                                                delay(1500)
                                                isTyping = false
                                                val replyText = "Received location coordinates for ${loc.first}."
                                                val replyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                                initialMessages.add(Message(System.currentTimeMillis().toString(), replyText, false, replyTime))
                                            }
                                        }
                                    }
                                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            ) {
                                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_attach_location),
                                        contentDescription = "Pin",
                                        tint = primaryColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(text = loc.first, fontWeight = FontWeight.SemiBold, color = onSurfaceColor)
                                        Text(text = loc.second, fontSize = 11.sp, color = onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                },
                containerColor = surfaceColor,
                shape = RoundedCornerShape(20.dp)
            )
        }

        if (showVerifyDialog) {
            val peerFingerprint = remember(peerName) {
                when (peerName) {
                    "Eleanor Vance" -> "2TFcRb7mE1eAnOrVaNcE9823471029837419"
                    "Liam O'Connor" -> "2TFcRb7mLiAmOcOnNoR1029384756102938"
                    "Sarah Chen" -> "2TFcRb7mSaRaHcHeN92837410293847102938"
                    else -> "2TFcRb7m" + peerName.hashCode().toString().padStart(16, 'x')
                }
            }

            AlertDialog(
                onDismissRequest = { showVerifyDialog = false },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showVerifyDialog = false }) {
                        Text(Localizations.getString("close", appLanguage), color = primaryColor)
                    }
                },
                title = { 
                    Text(
                        text = Localizations.getString("verify_peer", appLanguage), 
                        fontSize = 16.sp, 
                        fontWeight = FontWeight.Bold, 
                        color = onSurfaceColor
                    ) 
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = Localizations.getString("verify_desc", appLanguage),
                            fontSize = 13.sp,
                            color = onSurfaceVariant
                        )
                        
                        Text(
                            text = Localizations.getString("fingerprint_label", appLanguage) + ":",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceVariant
                        )

                        Card(
                            colors = CardDefaults.cardColors(containerColor = surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                        ) {
                            Text(
                                text = peerFingerprint,
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = onSurfaceColor,
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    mockMismatchToggle = !mockMismatchToggle
                                    sharedPrefs.edit().putBoolean("mock_mismatch_${peerName}", mockMismatchToggle).apply()
                                    sharedPrefs.edit().putBoolean("fingerprint_mismatch_${peerName}", mockMismatchToggle).apply()
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = mockMismatchToggle,
                                onCheckedChange = {
                                    mockMismatchToggle = it
                                    sharedPrefs.edit().putBoolean("mock_mismatch_${peerName}", it).apply()
                                    sharedPrefs.edit().putBoolean("fingerprint_mismatch_${peerName}", it).apply()
                                },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.error)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (appLanguage == "Русский") "Симулировать MitM-атаку (Красный щит)"
                                       else "Simulate MitM Attack (Red Shield)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                isVerified = !isVerified
                                sharedPrefs.edit().putBoolean("verified_peer_${peerName}", isVerified).apply()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isVerified) MaterialTheme.colorScheme.error else Color(0xFF4CAF50),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (isVerified) Localizations.getString("unverify_btn", appLanguage)
                                       else Localizations.getString("verify_btn", appLanguage)
                            )
                        }
                    }
                },
                containerColor = surfaceColor,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

// Media Attachment Composable
@Composable
fun AttachmentPanel(
    primaryColor: Color,
    surfaceVariant: Color,
    onSurfaceColor: Color,
    onAttachmentClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val attachments = listOf(
            AttachmentItem("Camera", com.example.twopchat.R.drawable.ic_attach_camera, primaryColor.copy(alpha = 0.1f)),
            AttachmentItem("Gallery", com.example.twopchat.R.drawable.ic_attach_gallery, primaryColor.copy(alpha = 0.1f)),
            AttachmentItem("File", com.example.twopchat.R.drawable.ic_attach_file, primaryColor.copy(alpha = 0.1f)),
            AttachmentItem("Location", com.example.twopchat.R.drawable.ic_attach_location, primaryColor.copy(alpha = 0.1f))
        )
        
        attachments.forEach { item ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onAttachmentClick(item.label) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(52.dp)
                        .background(item.bgColor, shape = CircleShape)
                        .border(0.5.dp, primaryColor.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(
                        painter = painterResource(id = item.iconRes),
                        contentDescription = item.label,
                        tint = primaryColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = item.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = onSurfaceColor)
            }
        }
    }
}

data class AttachmentItem(
    val label: String,
    val iconRes: Int,
    val bgColor: Color
)
