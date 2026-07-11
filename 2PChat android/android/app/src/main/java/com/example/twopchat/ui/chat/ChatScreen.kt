package com.example.twopchat.ui.chat

import android.widget.Toast
import android.content.Intent
import android.net.VpnService
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import com.example.twopchat.theme.StealthBlack
import com.example.twopchat.data.Localizations
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlin.math.abs

data class Message(
    val id: String,
    val text: String,
    val isMe: Boolean,
    val timestamp: String,
    val attachmentType: String? = null, // "IMAGE", "FILE", "LOCATION"
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToName: String? = null,
    val status: String? = null
)

private fun newMessageId(): String = java.util.UUID.randomUUID().toString()

@Composable
fun SwipeToReplyContainer(
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    val threshold = 120f
    val limit = 200f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {},
                    onDragEnd = {
                        if (abs(offsetX.value) > threshold) {
                            onReply()
                        }
                        coroutineScope.launch {
                            offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch {
                            offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val newOffset = (offsetX.value + dragAmount).coerceIn(-limit, limit)
                        coroutineScope.launch {
                            offsetX.snapTo(newOffset)
                        }
                    }
                )
            }
    ) {
        if (offsetX.value != 0f) {
            val isRight = offsetX.value > 0
            val alignment = if (isRight) Alignment.CenterStart else Alignment.CenterEnd
            val iconAlpha = (abs(offsetX.value) / threshold).coerceIn(0f, 1f)
            val iconScale = (abs(offsetX.value) / threshold).coerceIn(0.5f, 1f)
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = alignment
            ) {
                Icon(
                    painter = painterResource(id = com.example.twopchat.R.drawable.ic_reply),
                    contentDescription = "Reply",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = iconAlpha),
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer(scaleX = iconScale, scaleY = iconScale)
                )
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    peerName: String,
    appLanguage: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun parseIncomingAttachmentMessage(text: String): Message? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) {
            return null
        }
        return try {
            val json = org.json.JSONObject(trimmed)
            if (json.optString("type") != "file") {
                return null
            }
            val fileName = json.optString("file_name", "file")
            val filePath = json.optString("file_path", "")
            val mime = json.optString("mime", "")
            val isImage = mime.startsWith("image/")
            Message(
                id = newMessageId(),
                text = if (isImage) "Sent an image" else fileName,
                isMe = false,
                timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                attachmentType = if (isImage) "IMAGE" else "FILE",
                attachmentUri = filePath,
                attachmentName = fileName
            )
        } catch (_: Exception) {
            null
        }
    }

    var activeFullscreenImageUri by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (activeFullscreenImageUri != null) {
            activeFullscreenImageUri = null
        } else {
            onBack()
        }
    }
    
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sharedPrefs = remember(context) { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    val username = remember { sharedPrefs.getString("username_profile", "User Identity") ?: "User Identity" }
    var isVerified by remember(peerName) { mutableStateOf(sharedPrefs.getBoolean("verified_peer_${peerName}", false)) }
    var showVerifyDialog by remember { mutableStateOf(false) }
    var showConnectionErrorDialog by remember { mutableStateOf(false) }
    var errorReasonYggdrasilDisabled by remember { mutableStateOf(true) }
    var mockMismatchToggle by remember(peerName) { mutableStateOf(sharedPrefs.getBoolean("mock_mismatch_${peerName}", false)) }

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val intent = Intent(context, PacketTunnelProvider::class.java).apply {
                    action = PacketTunnelProvider.ACTION_START
                }
                context.startService(intent)
                sharedPrefs.edit().putBoolean("settings_yggdrasil", true).apply()
                Toast.makeText(context, if (appLanguage == "Русский") "Yggdrasil успешно включен!" else "Yggdrasil enabled successfully!", Toast.LENGTH_SHORT).show()
            }
        }
    )
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
    val db = remember(context) { com.example.twopchat.data.ChatDatabaseHelper(context) }
    val persistEnabled = remember(context) { sharedPrefs.getBoolean("persist_chat_history", true) }
    val initialMessages = remember(peerName) {
        val mockList = when (peerName) {
            "Eleanor Vance" -> listOf(
                Message("1", "Hey! Did you check out the new design mockups?", false, "12:35"),
                Message("2", "Yes, they look fantastic! Especially the dark theme.", true, "12:36"),
                Message("3", "The designs look fantastic!", true, "12:36")
            )
            "Liam O'Connor" -> listOf(
                Message("1", "Did we get the testing keys from the server?", false, "11:15"),
                Message("2", "Yes, I loaded them into the P2P transport module.", true, "11:18"),
                Message("3", "Thanks for the feedback.", false, "11:20")
            )
            "Sarah Chen" -> listOf(
                Message("1", "The direct connection looks very stable.", false, "09:40"),
                Message("2", "Awesome. I'll verify the latency metrics.", true, "09:42"),
                Message("3", "Last message, your work is great!", false, "09:45")
            )
            "Saved Messages" -> listOf(
                Message("1", Localizations.getString("saved_messages_welcome", appLanguage), true, "12:00")
            )
            else -> emptyList()
        }

        val list = db.getMessagesForPeer(peerName)
        if (persistEnabled) {
            if (list.isEmpty()) {
                mockList.forEach { db.saveMessage(peerName, it) }
                mutableStateListOf<Message>().apply { addAll(mockList) }
            } else {
                mutableStateListOf<Message>().apply { addAll(list) }
            }
        } else {
            mutableStateListOf<Message>().apply {
                addAll(mockList)
                addAll(list.filter { it.status == "PENDING" })
            }
        }
    }

    var inputText by remember { mutableStateOf("") }
    var myTypingState by remember { mutableStateOf(false) }
    var localMockTyping by remember { mutableStateOf(false) }
    val isTyping = localMockTyping || (com.example.twopchat.P2PMessageRelay.peerTypingStates[peerName] ?: false)

    LaunchedEffect(peerName) {
        val endpoint = com.example.twopchat.P2PMessageRelay.peerEndpoints[peerName]
        if (endpoint != null && peerName != "Saved Messages") {
            com.example.twopchat.P2PMessageRelay.shareAvatar(context, peerName, endpoint)
            com.example.twopchat.P2PMessageRelay.processOfflineQueue(context, peerName, endpoint)
            
            // Mark all existing incoming messages as READ in database and send read receipts
            initialMessages.forEach { msg ->
                if (!msg.isMe && msg.status != "READ") {
                    db.updateMessageStatus(msg.id, "READ")
                    val idx = initialMessages.indexOfFirst { it.id == msg.id }
                    if (idx != -1) {
                        initialMessages[idx] = msg.copy(status = "READ")
                    }
                    com.example.twopchat.P2PMessageRelay.sendReadReceipt(context, peerName, endpoint, msg.id)
                }
            }
        }
    }

    LaunchedEffect(inputText) {
        if (peerName == "Saved Messages") return@LaunchedEffect
        val endpoint = com.example.twopchat.P2PMessageRelay.peerEndpoints[peerName] ?: return@LaunchedEffect
        val isCurrentlyTyping = inputText.isNotEmpty()
        if (isCurrentlyTyping != myTypingState) {
            myTypingState = isCurrentlyTyping
            com.example.twopchat.P2PMessageRelay.sendTypingState(context, peerName, endpoint, isCurrentlyTyping)
        }
        
        // Auto reset typing state after 3 seconds of inactivity
        if (isCurrentlyTyping) {
            kotlinx.coroutines.delay(3000)
            if (inputText.isNotEmpty() && myTypingState) {
                myTypingState = false
                com.example.twopchat.P2PMessageRelay.sendTypingState(context, peerName, endpoint, false)
            }
        }
    }

    val messageListener = remember(peerName) {
        object : com.example.twopchat.P2PMessageRelay.MessageListener {
            override fun onMessageReceived(sender: String, text: String) {
                if (sender == peerName) {
                    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                    val attachmentMessage = parseIncomingAttachmentMessage(text)
                    val rxMsg = if (attachmentMessage != null) {
                        attachmentMessage.copy(status = "READ")
                    } else {
                        val trimmed = text.trim()
                        if (trimmed.startsWith("{")) {
                            try {
                                val json = org.json.JSONObject(trimmed)
                                if (json.optString("type") == "reply") {
                                    val replyText = json.optString("text")
                                    val replyToId = json.optString("reply_to_id")
                                    val replyToText = json.optString("reply_to_text")
                                    val replyToName = json.optString("reply_to_name")
                                    Message(
                                        id = newMessageId(),
                                        text = replyText,
                                        isMe = false,
                                        timestamp = time,
                                        replyToId = replyToId,
                                        replyToText = replyToText,
                                        replyToName = replyToName,
                                        status = "READ"
                                    )
                                } else {
                                    Message(newMessageId(), text, false, time, status = "READ")
                                }
                            } catch (e: Exception) {
                                Message(newMessageId(), text, false, time, status = "READ")
                            }
                        } else {
                            Message(newMessageId(), text, false, time, status = "READ")
                        }
                    }
                    
                    val endpoint = com.example.twopchat.P2PMessageRelay.peerEndpoints[peerName]
                    if (endpoint != null && peerName != "Saved Messages") {
                        com.example.twopchat.P2PMessageRelay.sendReadReceipt(context, peerName, endpoint, rxMsg.id)
                        db.updateMessageStatus(rxMsg.id, "READ")
                    }
                    initialMessages.add(rxMsg)
                }
            }

            override fun onMessageStatusChanged(sender: String, msgId: String, status: String) {
                if (sender == peerName) {
                    val idx = initialMessages.indexOfFirst { it.id == msgId }
                    if (idx != -1) {
                        val current = initialMessages[idx]
                        initialMessages[idx] = current.copy(status = status)
                    }
                }
            }
        }
    }

    DisposableEffect(peerName) {
        com.example.twopchat.P2PMessageRelay.activeChatPeerName = peerName
        com.example.twopchat.P2PMessageRelay.registerMessageListener(messageListener)
        onDispose {
            com.example.twopchat.P2PMessageRelay.activeChatPeerName = null
            com.example.twopchat.P2PMessageRelay.unregisterMessageListener(messageListener)
            val endpoint = com.example.twopchat.P2PMessageRelay.peerEndpoints[peerName]
            if (endpoint != null && peerName != "Saved Messages" && myTypingState) {
                com.example.twopchat.P2PMessageRelay.sendTypingState(context, peerName, endpoint, false)
            }
        }
    }

    // Session is established lazily on the first real message send — no silent ping needed.

    var showAttachments by remember { mutableStateOf(false) }
    var selectedMessageForOptions by remember { mutableStateOf<Message?>(null) }
    var replyingToMessage by remember { mutableStateOf<Message?>(null) }
    
    var pinnedMsgId by remember(peerName) { mutableStateOf(sharedPrefs.getString("pinned_msg_id_${peerName}", null)) }
    var pinnedMsgText by remember(peerName) { mutableStateOf(com.example.twopchat.SecureStorage.decrypt(sharedPrefs.getString("pinned_msg_text_${peerName}", null))) }
    var pinnedMsgSender by remember(peerName) { mutableStateOf(sharedPrefs.getString("pinned_msg_sender_${peerName}", null)) }

    var isSelectMode by remember { mutableStateOf(false) }
    val selectedMessages = remember { mutableStateListOf<Message>() }
    var showForwardDialog by remember { mutableStateOf(false) }
    var messageToForward by remember { mutableStateOf<Message?>(null) }

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

    // Helper to copy Uri contents to a persistent file
    fun saveUriToTempFile(context: android.content.Context, uri: Uri, originalName: String): java.io.File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val attachmentsDir = java.io.File(context.filesDir, "attachments")
            if (!attachmentsDir.exists()) {
                attachmentsDir.mkdirs()
            }
            val file = java.io.File(attachmentsDir, "sent_file_${System.currentTimeMillis()}_$originalName")
            val outputStream = java.io.FileOutputStream(file)
            val buffer = ByteArray(4 * 1024)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
            }
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Picker Launchers
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            var fileName = "photo.jpg"
            context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            val tempFile = saveUriToTempFile(context, it, fileName)
            if (tempFile != null) {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val endpoint = com.example.twopchat.P2PMessageRelay.peerEndpoints[peerName]
                val initialStatus = if (endpoint != null) "SENT" else "PENDING"
                val outMsg = Message(
                    id = newMessageId(),
                    text = "Sent an image",
                    isMe = true,
                    timestamp = time,
                    attachmentType = "IMAGE",
                    attachmentUri = tempFile.absolutePath,
                    attachmentName = fileName,
                    status = initialStatus
                )
                initialMessages.add(outMsg)
                if (persistEnabled || initialStatus == "PENDING") {
                    db.saveMessage(peerName, outMsg)
                }
                if (endpoint != null && peerName != "Saved Messages") {
                    com.example.twopchat.P2PMessageRelay.sendFile(context, endpoint, tempFile.absolutePath) { success ->
                        if (!success) {
                            db.updateMessageStatus(outMsg.id, "PENDING")
                            coroutineScope.launch {
                                val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                if (idx != -1) {
                                    initialMessages[idx] = outMsg.copy(status = "PENDING")
                                }
                                val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", false)
                                errorReasonYggdrasilDisabled = !isYggEnabled
                                showConnectionErrorDialog = true
                            }
                        }
                    }
                } else if (peerName != "Saved Messages") {
                    val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", false)
                    errorReasonYggdrasilDisabled = !isYggEnabled
                    showConnectionErrorDialog = true
                }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap?.let {
            val attachmentsDir = File(context.filesDir, "attachments")
            if (!attachmentsDir.exists()) {
                attachmentsDir.mkdirs()
            }
            val file = File(attachmentsDir, "camera_capture_${System.currentTimeMillis()}.jpg")
            try {
                val out = FileOutputStream(file)
                it.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
                out.close()
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val endpoint = com.example.twopchat.P2PMessageRelay.peerEndpoints[peerName]
                val initialStatus = if (endpoint != null) "SENT" else "PENDING"
                val outMsg = Message(
                    id = newMessageId(),
                    text = "Captured a photo",
                    isMe = true,
                    timestamp = time,
                    attachmentType = "IMAGE",
                    attachmentUri = file.absolutePath,
                    attachmentName = file.name,
                    status = initialStatus
                )
                initialMessages.add(outMsg)
                if (persistEnabled || initialStatus == "PENDING") {
                    db.saveMessage(peerName, outMsg)
                }
                if (endpoint != null && peerName != "Saved Messages") {
                    com.example.twopchat.P2PMessageRelay.sendFile(context, endpoint, file.absolutePath) { success ->
                        if (!success) {
                            db.updateMessageStatus(outMsg.id, "PENDING")
                            coroutineScope.launch {
                                val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                if (idx != -1) {
                                    initialMessages[idx] = outMsg.copy(status = "PENDING")
                                }
                                val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", false)
                                errorReasonYggdrasilDisabled = !isYggEnabled
                                showConnectionErrorDialog = true
                            }
                        }
                    }
                } else if (peerName != "Saved Messages") {
                    val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", false)
                    errorReasonYggdrasilDisabled = !isYggEnabled
                    showConnectionErrorDialog = true
                }
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
            val tempFile = saveUriToTempFile(context, it, fileName)
            if (tempFile != null) {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val endpoint = com.example.twopchat.P2PMessageRelay.peerEndpoints[peerName]
                val initialStatus = if (endpoint != null) "SENT" else "PENDING"
                val outMsg = Message(
                    id = newMessageId(),
                    text = fileName,
                    isMe = true,
                    timestamp = time,
                    attachmentType = "FILE",
                    attachmentUri = tempFile.absolutePath,
                    attachmentName = fileName,
                    status = initialStatus
                )
                initialMessages.add(outMsg)
                if (persistEnabled || initialStatus == "PENDING") {
                    db.saveMessage(peerName, outMsg)
                }
                if (endpoint != null && peerName != "Saved Messages") {
                    com.example.twopchat.P2PMessageRelay.sendFile(context, endpoint, tempFile.absolutePath) { success ->
                        if (!success) {
                            db.updateMessageStatus(outMsg.id, "PENDING")
                            coroutineScope.launch {
                                val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                if (idx != -1) {
                                    initialMessages[idx] = outMsg.copy(status = "PENDING")
                                }
                                val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", false)
                                errorReasonYggdrasilDisabled = !isYggEnabled
                                showConnectionErrorDialog = true
                            }
                        }
                    }
                } else if (peerName != "Saved Messages") {
                    val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", false)
                    errorReasonYggdrasilDisabled = !isYggEnabled
                    showConnectionErrorDialog = true
                }
            }
        }
    }

    var showLocationDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

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
                    Icon(
                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_back_arrow),
                        contentDescription = "Back",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
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
                    val avatarBitmap = com.example.twopchat.P2PMessageRelay.peerAvatars[peerName]
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap.asImageBitmap(),
                            contentDescription = "Avatar",
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else if (initials == "🔖") {
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
                        val endpoint = com.example.twopchat.P2PMessageRelay.peerEndpoints[peerName]
                        val isOnline = com.example.twopchat.P2PMessageRelay.peerSessionStates[peerName] == true
                        if (peerName != "Saved Messages") {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(if (isOnline) primaryColor else onSurfaceVariant.copy(alpha = 0.4f), shape = CircleShape)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        Text(
                            text = if (peerName == "Saved Messages") {
                                Localizations.getString("local_storage", appLanguage)
                            } else if (isOnline) {
                                val transportName = com.example.twopchat.P2PMessageRelay.peerConnectionTransports[peerName]
                                    ?: if (appLanguage == "Русский") "маршрут определяется" else "detecting route"
                                if (appLanguage == "Русский") "В сети • $transportName" else "Online • $transportName"
                            } else {
                                if (appLanguage == "Русский") "Не в сети" else "Offline"
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

                // Three-dot Action Menu
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("⋮", fontSize = 18.sp, color = onSurfaceColor, fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(surfaceColor)
                    ) {
                        if (peerName != "Saved Messages") {
                            DropdownMenuItem(
                                text = { 
                                    Text(
                                        text = if (appLanguage == "Русский") "Переподключить соединение" else "Reconnect Connection", 
                                        color = onSurfaceColor,
                                        fontSize = 14.sp
                                    ) 
                                },
                                onClick = {
                                    showMenu = false
                                    com.example.twopchat.P2PMessageRelay.reconnectSession(context, peerName) { success ->
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            if (success) {
                                                Toast.makeText(context, if (appLanguage == "Русский") "Переподключение запущено..." else "Reconnection initiated...", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, if (appLanguage == "Русский") "Не удалось переподключить" else "Failed to reconnect", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    text = if (appLanguage == "Русский") "Очистить историю" else "Clear History", 
                                    color = Color.Red,
                                    fontSize = 14.sp
                                ) 
                            },
                            onClick = {
                                showMenu = false
                                db.clearMessagesForPeer(peerName)
                                initialMessages.clear()
                                sharedPrefs.edit().remove("last_msg_$peerName").apply()
                            }
                        )
                    }
                }
            }

            // Pinned Message Bar
            if (pinnedMsgId != null && pinnedMsgText != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(surfaceColor.copy(alpha = 0.95f))
                        .border(width = 0.5.dp, color = onSurfaceColor.copy(alpha = 0.05f))
                        .clickable {
                            val idx = initialMessages.indexOfFirst { it.id == pinnedMsgId }
                            if (idx != -1) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(idx)
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_pin),
                        contentDescription = "Pinned",
                        tint = primaryColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (appLanguage == "Русский") "Закреплённое сообщение" else "Pinned Message",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor
                        )
                        Text(
                            text = pinnedMsgText ?: "",
                            fontSize = 12.sp,
                            color = onSurfaceColor,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = {
                            sharedPrefs.edit().apply {
                                remove("pinned_msg_id_${peerName}")
                                remove("pinned_msg_text_${peerName}")
                                remove("pinned_msg_sender_${peerName}")
                                apply()
                            }
                            pinnedMsgId = null
                            pinnedMsgText = null
                            pinnedMsgSender = null
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("×", fontSize = 18.sp, color = onSurfaceVariant, fontWeight = FontWeight.Bold)
                    }
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
                itemsIndexed(
                    items = initialMessages,
                    key = { _, msg -> msg.id }
                ) { index, msg ->
                    val visibleState = remember(msg.id) {
                        MutableTransitionState(false).apply {
                            targetState = true
                        }
                    }
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

                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = fadeIn(animationSpec = tween(220)) + slideInVertically(
                            initialOffsetY = { it / 5 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SwipeToReplyContainer(
                            onReply = {
                                replyingToMessage = msg
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSelectMode) {
                                    val isSelected = selectedMessages.contains(msg)
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                selectedMessages.add(msg)
                                            } else {
                                                selectedMessages.remove(msg)
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = primaryColor),
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = isSelectMode) {
                                            if (isSelectMode) {
                                                if (selectedMessages.contains(msg)) {
                                                    selectedMessages.remove(msg)
                                                } else {
                                                    selectedMessages.add(msg)
                                                }
                                            }
                                        },
                                    horizontalAlignment = alignment
                                ) {
                                    Box(
                                        modifier = bubbleModifier
                                            .combinedClickable(
                                                onClick = {
                                                    if (isSelectMode) {
                                                        if (selectedMessages.contains(msg)) {
                                                            selectedMessages.remove(msg)
                                                        } else {
                                                            selectedMessages.add(msg)
                                                        }
                                                    } else {
                                                        selectedMessageForOptions = msg
                                                    }
                                                },
                                                onLongClick = {
                                                    if (!isSelectMode) {
                                                        selectedMessageForOptions = msg
                                                    }
                                                }
                                            )
                                            // Subtle border for incoming bubbles
                                            .then(if (!msg.isMe) Modifier.border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), bubbleShape) else Modifier)
                                            .padding(horizontal = 16.dp, vertical = 11.dp)
                                            .widthIn(max = 280.dp)
                                    ) {
                                        Column {
                                            // Render reply quote if this message is a reply
                                            if (msg.replyToId != null) {
                                                val replyBg = if (msg.isMe) Color.White.copy(alpha = 0.15f) else onSurfaceColor.copy(alpha = 0.05f)
                                                val replyBarColor = if (msg.isMe) {
                                                    if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color.White
                                                } else primaryColor
                                                val replyTextColor = if (msg.isMe) {
                                                    if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.8f)
                                                } else onSurfaceVariant
                                                val replyTitleColor = if (msg.isMe) {
                                                    if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color.White
                                                } else primaryColor
                                                
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(replyBg)
                                                        .clickable {
                                                            val targetIndex = initialMessages.indexOfFirst { it.id == msg.replyToId }
                                                            if (targetIndex != -1) {
                                                                coroutineScope.launch {
                                                                    listState.animateScrollToItem(targetIndex)
                                                                }
                                                            }
                                                        }
                                                        .padding(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .width(3.dp)
                                                            .height(36.dp)
                                                            .background(replyBarColor, RoundedCornerShape(2.dp))
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = msg.replyToName ?: "Unknown",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = replyTitleColor
                                                        )
                                                        Text(
                                                            text = msg.replyToText ?: "",
                                                            fontSize = 11.sp,
                                                            color = replyTextColor,
                                                            maxLines = 1,
                                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                            }

                                            when (msg.attachmentType) {
                                                "IMAGE" -> {
                                                    val bitmap = rememberSampledImage(msg.attachmentUri)
                                                    if (bitmap != null) {
                                                        Column {
                                                            Image(
                                                                bitmap = bitmap.asImageBitmap(),
                                                                contentDescription = "Image attachment",
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .heightIn(max = 200.dp)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .clickable {
                                                                        activeFullscreenImageUri = msg.attachmentUri
                                                                    }
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
                                            
                                            val isRead = hasIncomingAfter || msg.status == "READ" || isTyping || peerName == "Saved Messages"
                                            val isPending = msg.status == "PENDING"
                                            
                                            val statusText = when {
                                                isPending -> "🕒"
                                                isRead -> "✓✓"
                                                else -> "✓"
                                            }
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

                // Reply Preview Bar
                AnimatedVisibility(
                    visible = replyingToMessage != null,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
                ) {
                    replyingToMessage?.let { replyMsg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .background(onSurfaceColor.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
                                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(36.dp)
                                    .background(primaryColor, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (replyMsg.isMe) (if (appLanguage == "Русский") "Вы" else "You") else peerName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor
                                )
                                Text(
                                    text = replyMsg.text,
                                    fontSize = 11.sp,
                                    color = onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { replyingToMessage = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Text("×", fontSize = 18.sp, color = onSurfaceVariant, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (isSelectMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    isSelectMode = false
                                    selectedMessages.clear()
                                }
                            ) {
                                Text("×", fontSize = 24.sp, color = onSurfaceColor, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (appLanguage == "Русский") "Выбрано: ${selectedMessages.size}" else "Selected: ${selectedMessages.size}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = onSurfaceColor
                            )
                        }

                        IconButton(
                            onClick = {
                                selectedMessages.forEach { msg ->
                                    db.deleteMessage(msg.id)
                                    initialMessages.remove(msg)
                                    if (msg.id == pinnedMsgId) {
                                        sharedPrefs.edit().apply {
                                            remove("pinned_msg_id_${peerName}")
                                            remove("pinned_msg_text_${peerName}")
                                            remove("pinned_msg_sender_${peerName}")
                                            apply()
                                        }
                                        pinnedMsgId = null
                                        pinnedMsgText = null
                                        pinnedMsgSender = null
                                    }
                                }
                                selectedMessages.clear()
                                isSelectMode = false
                            },
                            enabled = selectedMessages.isNotEmpty()
                        ) {
                            Icon(
                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_delete),
                                contentDescription = "Delete Selected",
                                tint = if (selectedMessages.isNotEmpty()) Color.Red else onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                } else {
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
                                    
                                    val replyTo = replyingToMessage
                                    replyingToMessage = null

                                    val endpoint = com.example.twopchat.P2PMessageRelay.peerEndpoints[peerName]
                                    val initialStatus = if (endpoint != null || peerName == "Saved Messages") "SENT" else "PENDING"

                                    // Add user message
                                    val outMsg = Message(
                                        id = newMessageId(),
                                        text = userText,
                                        isMe = true,
                                        timestamp = time,
                                        replyToId = replyTo?.id,
                                        replyToText = replyTo?.text,
                                        replyToName = replyTo?.let { if (it.isMe) (if (appLanguage == "Русский") "Вы" else "You") else peerName },
                                        status = initialStatus
                                    )
                                    initialMessages.add(outMsg)
                                    if (persistEnabled || initialStatus == "PENDING") {
                                        db.saveMessage(peerName, outMsg)
                                    }

                                    // Persist in shared preferences last message list
                                    val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                                    if (!activeSet.contains(peerName)) {
                                        val newSet = activeSet.toMutableSet()
                                        newSet.add(peerName)
                                        sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                                    }
                                    sharedPrefs.edit().putString("last_msg_$peerName", com.example.twopchat.SecureStorage.encrypt("You: $userText")).apply()

                                    // Send message payload
                                    val payload = if (replyTo != null) {
                                        org.json.JSONObject().apply {
                                            put("type", "reply")
                                            put("text", userText)
                                            put("reply_to_id", replyTo.id)
                                            put("reply_to_text", replyTo.text)
                                            put("reply_to_name", replyTo.let { if (it.isMe) username else peerName })
                                        }.toString()
                                    } else {
                                        userText
                                    }

                                    // Send over real TCP socket if endpoint is resolved
                                    if (endpoint != null && peerName != "Saved Messages") {
                                        com.example.twopchat.P2PMessageRelay.sendMessage(context, endpoint, username, payload) { success ->
                                            if (!success) {
                                                db.updateMessageStatus(outMsg.id, "PENDING")
                                                coroutineScope.launch {
                                                    val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                                    if (idx != -1) {
                                                        initialMessages[idx] = outMsg.copy(status = "PENDING")
                                                    }
                                                    val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", false)
                                                    errorReasonYggdrasilDisabled = !isYggEnabled
                                                    showConnectionErrorDialog = true
                                                }
                                            }
                                        }
                                    }

                                    // Trigger mock reply with typing delay (only for demo mockup contacts)
                                    if (peerName == "Eleanor Vance" || peerName == "Liam O'Connor" || peerName == "Sarah Chen") {
                                        coroutineScope.launch {
                                            delay(1000)
                                            localMockTyping = true
                                            delay(1500)
                                            localMockTyping = false
                                            val replyText = autoReplies[replyIndex % autoReplies.size]
                                            replyIndex++
                                            val replyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                            val replyMsg = Message(
                                                System.currentTimeMillis().toString(),
                                                replyText,
                                                false,
                                                replyTime
                                            )
                                            initialMessages.add(replyMsg)
                                            if (persistEnabled) {
                                                db.saveMessage(peerName, replyMsg)
                                            }
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
        }

        // Message Options Overlay Panel
        if (selectedMessageForOptions != null) {
            val msg = selectedMessageForOptions!!
            AlertDialog(
                onDismissRequest = { selectedMessageForOptions = null },
                confirmButton = {},
                dismissButton = {},
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Действия с сообщением" else "Message Actions",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        
                        // Reply
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    replyingToMessage = msg
                                    selectedMessageForOptions = null
                                }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_reply),
                                contentDescription = "Reply",
                                tint = onSurfaceColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = if (appLanguage == "Русский") "Ответить" else "Reply",
                                fontSize = 15.sp,
                                color = onSurfaceColor
                            )
                        }

                        // Pin
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    sharedPrefs.edit().apply {
                                        putString("pinned_msg_id_${peerName}", msg.id)
                                        putString("pinned_msg_text_${peerName}", com.example.twopchat.SecureStorage.encrypt(msg.text))
                                        putString("pinned_msg_sender_${peerName}", if (msg.isMe) "You" else peerName)
                                        apply()
                                    }
                                    pinnedMsgId = msg.id
                                    pinnedMsgText = msg.text
                                    pinnedMsgSender = if (msg.isMe) "You" else peerName
                                    selectedMessageForOptions = null
                                }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_pin),
                                contentDescription = "Pin",
                                tint = onSurfaceColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = if (appLanguage == "Русский") "Закрепить" else "Pin",
                                fontSize = 15.sp,
                                color = onSurfaceColor
                            )
                        }

                        // Copy
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(msg.text))
                                    Toast.makeText(context, if (appLanguage == "Русский") "Текст скопирован" else "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                                    selectedMessageForOptions = null
                                }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_copy),
                                contentDescription = "Copy",
                                tint = onSurfaceColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = if (appLanguage == "Русский") "Копировать текст" else "Copy Text",
                                fontSize = 15.sp,
                                color = onSurfaceColor
                            )
                        }

                        // Forward
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    messageToForward = msg
                                    showForwardDialog = true
                                    selectedMessageForOptions = null
                                }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_forward),
                                contentDescription = "Forward",
                                tint = onSurfaceColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = if (appLanguage == "Русский") "Переслать" else "Forward",
                                fontSize = 15.sp,
                                color = onSurfaceColor
                            )
                        }

                        // Delete
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    db.deleteMessage(msg.id)
                                    initialMessages.remove(msg)
                                    if (msg.id == pinnedMsgId) {
                                        sharedPrefs.edit().apply {
                                            remove("pinned_msg_id_${peerName}")
                                            remove("pinned_msg_text_${peerName}")
                                            remove("pinned_msg_sender_${peerName}")
                                            apply()
                                        }
                                        pinnedMsgId = null
                                        pinnedMsgText = null
                                        pinnedMsgSender = null
                                    }
                                    selectedMessageForOptions = null
                                }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_delete),
                                contentDescription = "Delete",
                                tint = Color.Red,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = if (appLanguage == "Русский") "Удалить" else "Delete",
                                fontSize = 15.sp,
                                color = Color.Red
                            )
                        }

                        // Select
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    isSelectMode = true
                                    selectedMessages.clear()
                                    selectedMessages.add(msg)
                                    selectedMessageForOptions = null
                                }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_select),
                                contentDescription = "Select",
                                tint = onSurfaceColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = if (appLanguage == "Русский") "Выделить" else "Select",
                                fontSize = 15.sp,
                                color = onSurfaceColor
                            )
                        }
                    }
                },
                containerColor = surfaceColor,
                shape = RoundedCornerShape(24.dp)
            )
        }

        // Forward Dialog
        if (showForwardDialog && messageToForward != null) {
            val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
            val chatList = activeSet.filter { it != peerName }.toList()
            
            AlertDialog(
                onDismissRequest = { 
                    showForwardDialog = false
                    messageToForward = null
                },
                title = { Text(if (appLanguage == "Русский") "Переслать сообщение" else "Forward Message", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = onSurfaceColor) },
                text = {
                    if (chatList.isEmpty()) {
                        Text(if (appLanguage == "Русский") "Нет других активных чатов" else "No other active chats", color = onSurfaceVariant)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(chatList) { chatName ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val textToForward = messageToForward?.text ?: ""
                                            val forwardTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                            val forwardEndpoint = com.example.twopchat.P2PMessageRelay.peerEndpoints[chatName]
                                            val fwdInitialStatus = if (forwardEndpoint != null || chatName == "Saved Messages") "SENT" else "PENDING"
                                            val fwdMsg = Message(
                                                id = newMessageId(),
                                                text = textToForward,
                                                isMe = true,
                                                timestamp = forwardTime,
                                                attachmentType = messageToForward?.attachmentType,
                                                attachmentUri = messageToForward?.attachmentUri,
                                                attachmentName = messageToForward?.attachmentName,
                                                status = fwdInitialStatus
                                            )
                                            
                                            // Save to DB for the forwarded peer
                                            if (persistEnabled || fwdInitialStatus == "PENDING") {
                                                db.saveMessage(chatName, fwdMsg)
                                            }
                                            // Update last message in active chats list
                                            sharedPrefs.edit().putString("last_msg_$chatName", com.example.twopchat.SecureStorage.encrypt("You: $textToForward")).apply()
                                            
                                            // Send if there is an endpoint
                                            if (forwardEndpoint != null && chatName != "Saved Messages") {
                                                if (messageToForward?.attachmentType != null && messageToForward?.attachmentUri != null) {
                                                    com.example.twopchat.P2PMessageRelay.sendFile(context, forwardEndpoint, messageToForward!!.attachmentUri!!) { success ->
                                                        if (!success) {
                                                            db.updateMessageStatus(fwdMsg.id, "PENDING")
                                                        }
                                                    }
                                                } else {
                                                    com.example.twopchat.P2PMessageRelay.sendMessage(context, forwardEndpoint, username, textToForward) { success ->
                                                        if (!success) {
                                                            db.updateMessageStatus(fwdMsg.id, "PENDING")
                                                        }
                                                    }
                                                }
                                            }
                                            
                                            Toast.makeText(context, if (appLanguage == "Русский") "Переслано в $chatName" else "Forwarded to $chatName", Toast.LENGTH_SHORT).show()
                                            showForwardDialog = false
                                            messageToForward = null
                                        }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = chatName, fontSize = 15.sp, color = onSurfaceColor)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { 
                        showForwardDialog = false
                        messageToForward = null
                    }) {
                        Text(Localizations.getString("close", appLanguage), color = primaryColor)
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
                                                id = newMessageId(),
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
                                                localMockTyping = true
                                                delay(1500)
                                                localMockTyping = false
                                                val replyText = "Received location coordinates for ${loc.first}."
                                                val replyTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                                initialMessages.add(Message(newMessageId(), replyText, false, replyTime))
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

        if (showConnectionErrorDialog) {
            AlertDialog(
                onDismissRequest = { showConnectionErrorDialog = false },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showConnectionErrorDialog = false }) {
                        Text(Localizations.getString("close", appLanguage), color = primaryColor)
                    }
                },
                title = {
                    Text(
                        text = if (appLanguage == "Русский") "Ошибка подключения" else "Connection Failed",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = if (errorReasonYggdrasilDisabled) {
                                if (appLanguage == "Русский") {
                                    "Не удалось установить P2P-подключение к собеседнику. Скорее всего, вы или ваш собеседник находитесь за «серым» IP-адресом (NAT), что блокирует прямое соединение.\n\nРекомендуется включить Yggdrasil для обхода NAT и прямой связи."
                                } else {
                                    "Failed to establish P2P connection. Most likely, you or your peer are behind a NAT/firewall which blocks direct packets.\n\nIt is recommended to enable Yggdrasil routing to bypass NAT."
                                }
                            } else {
                                if (appLanguage == "Русский") {
                                    "Не удалось подключиться через сеть Yggdrasil. Убедитесь, что ваш собеседник также включил Yggdrasil в настройках и мессенджер запущен на обоих устройствах."
                                } else {
                                    "Failed to connect via Yggdrasil. Ensure your peer has also enabled Yggdrasil and the app is active on both devices."
                                }
                            },
                            fontSize = 13.sp,
                            color = onSurfaceVariant
                        )

                        if (errorReasonYggdrasilDisabled) {
                            Button(
                                onClick = {
                                    showConnectionErrorDialog = false
                                    val vpnIntent = VpnService.prepare(context)
                                    if (vpnIntent != null) {
                                        vpnLauncher.launch(vpnIntent)
                                    } else {
                                        val intent = Intent(context, PacketTunnelProvider::class.java).apply {
                                            action = PacketTunnelProvider.ACTION_START
                                        }
                                        context.startService(intent)
                                        sharedPrefs.edit().putBoolean("settings_yggdrasil", true).apply()
                                        Toast.makeText(context, if (appLanguage == "Русский") "Yggdrasil успешно включен!" else "Yggdrasil enabled successfully!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") "Включить Yggdrasil" else "Enable Yggdrasil",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                },
                containerColor = surfaceColor,
                shape = RoundedCornerShape(20.dp)
            )
        }

        activeFullscreenImageUri?.let { uri ->
            FullscreenImageViewer(
                imagePath = uri,
                onClose = { activeFullscreenImageUri = null }
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

@Composable
fun rememberSampledImage(filePath: String?, targetWidth: Int = 400, targetHeight: Int = 400): Bitmap? {
    var bitmapState by remember(filePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(filePath) {
        if (filePath == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val file = java.io.File(filePath)
                if (file.exists()) {
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(filePath, options)
                    options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
                    options.inJustDecodeBounds = false
                    val decoded = BitmapFactory.decodeFile(filePath, options)
                    if (decoded != null) {
                        bitmapState = decoded
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    return bitmapState
}

fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

@Composable
fun FullscreenImageViewer(
    imagePath: String,
    onClose: () -> Unit
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    
    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset += offsetChange
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClose() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val bitmap = rememberSampledImage(imagePath, targetWidth = 1200, targetHeight = 1200)
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Fullscreen Image",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .transformable(state = state)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onClose() },
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 3f
                                }
                            }
                        )
                    }
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }

        // Close Button
        IconButton(
            onClick = { onClose() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 40.dp, start = 16.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                painter = painterResource(id = com.example.twopchat.R.drawable.ic_back_arrow),
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
