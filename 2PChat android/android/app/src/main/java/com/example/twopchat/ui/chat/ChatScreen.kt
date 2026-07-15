package com.example.twopchat.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import android.content.Intent
import android.net.VpnService
import android.util.LruCache
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import java.io.FileInputStream
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import androidx.core.content.edit
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.data.Localizations
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.PythonBridge
import com.example.twopchat.copyTextToClipboard
import com.example.twopchat.SecureStorage
import com.example.twopchat.R
import com.example.twopchat.VoiceMessageSupport
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.theme.StealthBlack
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
                    painter = painterResource(id = R.drawable.ic_reply),
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
    isActive: Boolean,
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
            val attachmentType = VoiceMessageSupport.attachmentType(fileName, mime)
            Message(
                id = json.optString("message_id").ifBlank { newMessageId() },
                text = VoiceMessageSupport.displayMessage(attachmentType, fileName),
                isMe = false,
                timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                attachmentType = attachmentType,
                attachmentUri = filePath,
                attachmentName = fileName
            )
        } catch (_: Exception) {
            null
        }
    }

    var activeFullscreenImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeFullscreenImageIndex by remember { mutableStateOf(0) }
    var showProfileOverlay by remember { mutableStateOf(false) }

    BackHandler {
        if (activeFullscreenImages.isNotEmpty()) {
            activeFullscreenImages = emptyList()
        } else if (showProfileOverlay) {
            showProfileOverlay = false
        } else {
            onBack()
        }
    }
    
    val coroutineScope = rememberCoroutineScope()
    val screenInitTime = remember { System.currentTimeMillis() }
    val listState = rememberLazyListState()
    var hasScrolledToBottomOnInit by remember(peerName) { mutableStateOf(false) }
    val showScrollDownButton by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                false
            } else {
                val lastVisibleItem = visibleItems.last()
                val totalItems = layoutInfo.totalItemsCount
                totalItems - 1 - lastVisibleItem.index >= 5
            }
        }
    }
    val context = LocalContext.current
    var pendingDownloadPath by remember { mutableStateOf<String?>(null) }

    val galleryWritePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val path = pendingDownloadPath
        if (isGranted && path != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val uri = saveImageToPublicGallery(context, path)
                withContext(Dispatchers.Main) {
                    if (uri != null) {
                        Toast.makeText(context, if (appLanguage == "Русский") "Изображение сохранено в Галерею" else "Image saved to Gallery", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, if (appLanguage == "Русский") "Не удалось сохранить изображение" else "Failed to save image", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else if (path != null) {
            Toast.makeText(context, if (appLanguage == "Русский") "Разрешение на запись отклонено" else "Storage permission denied", Toast.LENGTH_SHORT).show()
        }
        pendingDownloadPath = null
    }
    val sharedPrefs = remember(context) { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    var pinnedMsgId by remember(peerName) { mutableStateOf(sharedPrefs.getString("pinned_msg_id_${peerName}", null)) }
    var pinnedMsgText by remember(peerName) { mutableStateOf(SecureStorage.decrypt(sharedPrefs.getString("pinned_msg_text_${peerName}", null))) }
    var pinnedMsgSender by remember(peerName) { mutableStateOf(sharedPrefs.getString("pinned_msg_sender_${peerName}", null)) }
    var pinnedBy by remember(peerName) { mutableStateOf(sharedPrefs.getString("pinned_by_${peerName}", null)) }
    var isMuted by remember(peerName) { mutableStateOf(sharedPrefs.getBoolean("mute_notifications_${peerName}", false)) }
    var isBlocked by remember(peerName) { mutableStateOf(sharedPrefs.getBoolean("blocked_peer_${peerName}", false)) }
    val username = remember { sharedPrefs.getString("username_profile", "User Identity") ?: "User Identity" }
    var isVerified by remember(peerName) { mutableStateOf(sharedPrefs.getBoolean("verified_peer_${peerName}", false)) }
    var showVerifyDialog by remember { mutableStateOf(false) }
    var showIncomingVerifyDialog by remember { mutableStateOf(false) }
    var isWaitingForVerifyResponse by remember { mutableStateOf(false) }
    var showConnectionErrorDialog by remember { mutableStateOf(false) }
    var errorReasonYggdrasilDisabled by remember { mutableStateOf(true) }
    val voiceRecorder = remember(context) { VoiceRecorder(context.applicationContext) }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var recordingElapsedMs by remember { mutableIntStateOf(0) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }

    fun beginVoiceRecording() {
        if (voiceRecorder.start()) {
            recordingStartedAt = android.os.SystemClock.elapsedRealtime()
            recordingElapsedMs = 0
            isRecordingVoice = true
        } else {
            Toast.makeText(context, if (appLanguage == "Русский") "Не удалось начать запись" else "Could not start recording", Toast.LENGTH_SHORT).show()
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginVoiceRecording()
        } else {
            Toast.makeText(context, if (appLanguage == "Русский") "Разрешите доступ к микрофону" else "Microphone permission is required", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(isRecordingVoice) {
        while (isRecordingVoice) {
            recordingElapsedMs = (android.os.SystemClock.elapsedRealtime() - recordingStartedAt).toInt()
            delay(100)
        }
    }

    DisposableEffect(voiceRecorder) {
        onDispose { voiceRecorder.cancel() }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val intent = Intent(context, PacketTunnelProvider::class.java).apply {
                    action = PacketTunnelProvider.ACTION_START
                }
                context.startService(intent)
                sharedPrefs.edit { putBoolean("settings_yggdrasil", true) }
                Toast.makeText(context, if (appLanguage == "Русский") "Yggdrasil успешно включен!" else "Yggdrasil enabled successfully!", Toast.LENGTH_SHORT).show()
            }
        }
    )
    val activeFingerprint = sharedPrefs.getString("peer_fingerprint_$peerName", null).orEmpty()
    var localFingerprint by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        localFingerprint = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            PythonBridge.getLocalFingerprint()
        }.takeUnless { it == "Error" || it == "Not Initialized" || it == "Loading..." }.orEmpty()
    }



    // Load only real persisted messages. Saved Messages keeps its local welcome entry.
    val db = remember(context) { ChatDatabaseHelper.getInstance(context) }
    val persistEnabled = remember(context) { sharedPrefs.getBoolean("persist_chat_history", true) }
    val initialMessages = remember(peerName) { mutableStateListOf<Message>() }
    var isHistoryLoading by remember(peerName) { mutableStateOf(true) }

    LaunchedEffect(peerName) {
        isHistoryLoading = true
        initialMessages.clear()
        val localDefaults = when (peerName) {
            "Saved Messages" -> listOf(
                Message(
                    "saved-messages-welcome",
                    Localizations.getString("saved_messages_welcome", appLanguage),
                    true,
                    "",
                    sentAtEpochMs = 0L,
                )
            )
            else -> emptyList()
        }

        val list = withContext(Dispatchers.IO) {
            db.getMessagesForPeer(peerName)
        }
        if (persistEnabled) {
            if (list.isEmpty()) {
                withContext(Dispatchers.IO) {
                    localDefaults.forEach { db.saveMessage(peerName, it) }
                }
                initialMessages.addAll(localDefaults)
            } else {
                initialMessages.addAll(list)
            }
        } else {
            initialMessages.addAll(localDefaults)
            initialMessages.addAll(list.filter { it.status == "PENDING" })
        }
        isHistoryLoading = false
    }

    fun sendVoiceRecording(recording: VoiceRecording) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val endpoint = P2PMessageRelay.peerEndpoints[peerName]
        val initialStatus = if (endpoint != null || peerName == "Saved Messages") "SENT" else "PENDING"
        val outMsg = Message(
            id = newMessageId(),
            text = "Voice message",
            isMe = true,
            timestamp = time,
            attachmentType = "VOICE",
            attachmentUri = recording.file.absolutePath,
            attachmentName = recording.file.name,
            status = initialStatus,
        )
        initialMessages.add(outMsg)
        if (persistEnabled || initialStatus == "PENDING") {
            db.saveMessage(peerName, outMsg)
        }
        val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
        if (!activeSet.contains(peerName)) {
            sharedPrefs.edit { putStringSet("active_chats", activeSet.toMutableSet().apply { add(peerName) }) }
        }
        sharedPrefs.edit { putString("last_msg_$peerName", SecureStorage.encrypt("You: Voice message")) }

        if (endpoint != null && peerName != "Saved Messages") {
            P2PMessageRelay.sendFile(context, peerName, endpoint, recording.file.absolutePath, outMsg.id) { success ->
                if (!success) {
                    db.updateMessageStatus(outMsg.id, "PENDING")
                    coroutineScope.launch {
                        val index = initialMessages.indexOfFirst { it.id == outMsg.id }
                        if (index != -1) initialMessages[index] = outMsg.copy(status = "PENDING")
                        errorReasonYggdrasilDisabled = !sharedPrefs.getBoolean("settings_yggdrasil", true)
                        showConnectionErrorDialog = true
                    }
                }
            }
        } else if (peerName != "Saved Messages") {
            errorReasonYggdrasilDisabled = !sharedPrefs.getBoolean("settings_yggdrasil", true)
            showConnectionErrorDialog = true
        }
    }

    var inputText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var myTypingState by remember { mutableStateOf(false) }
    val isTyping = P2PMessageRelay.peerTypingStates[peerName] ?: false

    LaunchedEffect(peerName, isHistoryLoading) {
        if (isHistoryLoading) return@LaunchedEffect
        val endpoint = P2PMessageRelay.peerEndpoints[peerName]
        if (endpoint != null && peerName != "Saved Messages") {
            P2PMessageRelay.shareAvatar(context, peerName, endpoint)
            P2PMessageRelay.processOfflineQueue(context, peerName, endpoint)
            
            // Mark all existing incoming messages as READ in database and send read receipts
            initialMessages.forEach { msg ->
                if (!msg.isMe && msg.status != "READ") {
                    withContext(Dispatchers.IO) {
                        db.updateMessageStatus(msg.id, "READ")
                    }
                    val idx = initialMessages.indexOfFirst { it.id == msg.id }
                    if (idx != -1) {
                        initialMessages[idx] = msg.copy(status = "READ")
                    }
                    P2PMessageRelay.sendReadReceipt(context, peerName, endpoint, msg.id)
                }
            }
        }
    }

    LaunchedEffect(inputText) {
        if (peerName == "Saved Messages") return@LaunchedEffect
        val endpoint = P2PMessageRelay.peerEndpoints[peerName] ?: return@LaunchedEffect
        val isCurrentlyTyping = inputText.isNotEmpty()
        if (isCurrentlyTyping != myTypingState) {
            myTypingState = isCurrentlyTyping
            P2PMessageRelay.sendTypingState(context, peerName, endpoint, isCurrentlyTyping)
        }
        
        // Auto reset typing state after 3 seconds of inactivity
        if (isCurrentlyTyping) {
            kotlinx.coroutines.delay(3000)
            if (inputText.isNotEmpty() && myTypingState) {
                myTypingState = false
                P2PMessageRelay.sendTypingState(context, peerName, endpoint, false)
            }
        }
    }

    val messageListener = remember(peerName) {
        object : P2PMessageRelay.MessageListener {
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
                    
                    val endpoint = P2PMessageRelay.peerEndpoints[peerName]
                    if (endpoint != null && peerName != "Saved Messages") {
                        P2PMessageRelay.sendReadReceipt(context, peerName, endpoint, rxMsg.id)
                        coroutineScope.launch(Dispatchers.IO) {
                            db.updateMessageStatus(rxMsg.id, "READ")
                        }
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

            override fun onMessageReactionChanged(sender: String, msgId: String, emoji: String, reactSender: String) {
                if (sender == peerName) {
                    val idx = initialMessages.indexOfFirst { it.id == msgId }
                    if (idx != -1) {
                        val current = initialMessages[idx]
                        val updatedMap = current.reactions.toMutableMap()
                        val sendersList = (updatedMap[emoji] ?: emptyList()).toMutableList()
                        if (!sendersList.contains(reactSender)) {
                            sendersList.add(reactSender)
                            updatedMap[emoji] = sendersList
                            initialMessages[idx] = current.copy(reactions = updatedMap)
                        }
                    }
                }
            }

            override fun onVerificationRequest(sender: String) {
                if (sender == peerName) {
                    showIncomingVerifyDialog = true
                }
            }

            override fun onVerificationResponse(sender: String, success: Boolean) {
                if (sender == peerName) {
                    isWaitingForVerifyResponse = false
                    if (success) {
                        isVerified = true
                        showVerifyDialog = false
                        Toast.makeText(context, if (appLanguage == "Русский") "Собеседник подтвердил личность!" else "Peer successfully verified!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, if (appLanguage == "Русский") "Запрос верификации отклонен собеседником." else "Verification request declined.", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onMessagePinned(sender: String, msgId: String, text: String, isFromSender: Boolean) {
                if (sender == peerName) {
                    sharedPrefs.edit {
                        putString("pinned_msg_id_${peerName}", msgId)
                        putString("pinned_msg_text_${peerName}", SecureStorage.encrypt(text))
                        putString("pinned_msg_sender_${peerName}", if (isFromSender) peerName else "You")
                        putString("pinned_by_${peerName}", peerName)
                    }
                    pinnedMsgId = msgId
                    pinnedMsgText = text
                    pinnedMsgSender = if (isFromSender) peerName else "You"
                    pinnedBy = peerName
                }
            }

            override fun onMessageUnpinned(sender: String) {
                if (sender == peerName) {
                    sharedPrefs.edit {
                        remove("pinned_msg_id_${peerName}")
                        remove("pinned_msg_text_${peerName}")
                        remove("pinned_msg_sender_${peerName}")
                        remove("pinned_by_${peerName}")
                    }
                    pinnedMsgId = null
                    pinnedMsgText = null
                    pinnedMsgSender = null
                    pinnedBy = null
                }
            }

            override fun onMessageEdited(sender: String, msgId: String, text: String) {
                if (sender == peerName) {
                    val idx = initialMessages.indexOfFirst { it.id == msgId }
                    if (idx != -1) {
                        val current = initialMessages[idx]
                        val oldStatus = current.status ?: ""
                        val newStatus = if (oldStatus.contains("edited")) oldStatus else if (oldStatus.isEmpty()) "edited" else "${oldStatus}_edited"
                        initialMessages[idx] = current.copy(text = text, status = newStatus)
                    }
                }
            }
        }
    }

    DisposableEffect(peerName, isActive) {
        if (isActive) {
            P2PMessageRelay.activeChatPeerName = peerName
            sharedPrefs.edit().putInt("unread_count_$peerName", 0).apply()
            P2PMessageRelay.registerMessageListener(messageListener)
        }
        onDispose {
            if (P2PMessageRelay.activeChatPeerName == peerName) {
                P2PMessageRelay.activeChatPeerName = null
            }
            if (isActive) {
                P2PMessageRelay.unregisterMessageListener(messageListener)
            }
            val endpoint = P2PMessageRelay.peerEndpoints[peerName]
            if (endpoint != null && peerName != "Saved Messages" && myTypingState) {
                P2PMessageRelay.sendTypingState(context, peerName, endpoint, false)
            }
        }
    }

    // Session is established lazily on the first real message send — no silent ping needed.

    var showAttachments by remember { mutableStateOf(false) }
    var selectedMessageForOptions by remember { mutableStateOf<Message?>(null) }
    var replyingToMessage by remember { mutableStateOf<Message?>(null) }
    var editingMessage by remember { mutableStateOf<Message?>(null) }
    
    var isSelectMode by remember { mutableStateOf(false) }
    val selectedMessages = remember { mutableStateListOf<Message>() }
    var showForwardDialog by remember { mutableStateOf(false) }
    var messageToForward by remember { mutableStateOf<Message?>(null) }

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
                    val queried = cursor.getString(nameIndex)
                    if (!queried.isNullOrBlank()) {
                        fileName = if (!queried.contains(".")) "$queried.jpg" else queried
                    }
                }
            }
            val tempFile = saveUriToTempFile(context, it, fileName)
            if (tempFile != null) {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val endpoint = P2PMessageRelay.peerEndpoints[peerName]
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
                    P2PMessageRelay.sendFile(context, peerName, endpoint, tempFile.absolutePath, outMsg.id) { success ->
                        if (!success) {
                            db.updateMessageStatus(outMsg.id, "PENDING")
                            coroutineScope.launch {
                                val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                if (idx != -1) {
                                    initialMessages[idx] = outMsg.copy(status = "PENDING")
                                }
                                val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", true)
                                errorReasonYggdrasilDisabled = !isYggEnabled
                                showConnectionErrorDialog = true
                            }
                        }
                    }
                } else if (peerName != "Saved Messages") {
                    val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", true)
                    errorReasonYggdrasilDisabled = !isYggEnabled
                    showConnectionErrorDialog = true
                }
            }
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            var fileName = "video.mp4"
            context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val queried = cursor.getString(nameIndex)
                    if (!queried.isNullOrBlank()) {
                        fileName = if (!queried.contains(".")) "$queried.mp4" else queried
                    }
                }
            }
            val tempFile = saveUriToTempFile(context, it, fileName)
            if (tempFile != null) {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val endpoint = P2PMessageRelay.peerEndpoints[peerName]
                val initialStatus = if (endpoint != null) "SENT" else "PENDING"
                val outMsg = Message(
                    id = newMessageId(),
                    text = "Sent a video",
                    isMe = true,
                    timestamp = time,
                    attachmentType = "VIDEO",
                    attachmentUri = tempFile.absolutePath,
                    attachmentName = fileName,
                    status = initialStatus
                )
                initialMessages.add(outMsg)
                if (persistEnabled || initialStatus == "PENDING") {
                    db.saveMessage(peerName, outMsg)
                }
                if (endpoint != null && peerName != "Saved Messages") {
                    P2PMessageRelay.sendFile(context, peerName, endpoint, tempFile.absolutePath, outMsg.id) { success ->
                        if (!success) {
                            db.updateMessageStatus(outMsg.id, "PENDING")
                            coroutineScope.launch {
                                val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                if (idx != -1) {
                                    initialMessages[idx] = outMsg.copy(status = "PENDING")
                                }
                                val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", true)
                                errorReasonYggdrasilDisabled = !isYggEnabled
                                showConnectionErrorDialog = true
                            }
                        }
                    }
                } else if (peerName != "Saved Messages") {
                    val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", true)
                    errorReasonYggdrasilDisabled = !isYggEnabled
                    showConnectionErrorDialog = true
                }
            }
        }
    }

    var tempCameraFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (!success) return@rememberLauncherForActivityResult
        val file = tempCameraFile ?: return@rememberLauncherForActivityResult
        try {
            // Correct EXIF rotation so photo is not upside-down when sent
            val exif = android.media.ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val rotationAngle = when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (rotationAngle != 0) {
                val decoded = BitmapFactory.decodeFile(file.absolutePath)
                if (decoded != null) {
                    val matrix = android.graphics.Matrix().apply { postRotate(rotationAngle.toFloat()) }
                    val rotated = android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    val out = FileOutputStream(file)
                    rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    out.flush(); out.close()
                    if (rotated != decoded) decoded.recycle()
                    rotated.recycle()
                }
            }
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val endpoint = P2PMessageRelay.peerEndpoints[peerName]
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
                P2PMessageRelay.sendFile(context, peerName, endpoint, file.absolutePath, outMsg.id) { success ->
                    if (!success) {
                        db.updateMessageStatus(outMsg.id, "PENDING")
                        coroutineScope.launch {
                            val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                            if (idx != -1) {
                                initialMessages[idx] = outMsg.copy(status = "PENDING")
                            }
                            val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", true)
                            errorReasonYggdrasilDisabled = !isYggEnabled
                            showConnectionErrorDialog = true
                        }
                    }
                }
            } else if (peerName != "Saved Messages") {
                val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", true)
                errorReasonYggdrasilDisabled = !isYggEnabled
                showConnectionErrorDialog = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
                val endpoint = P2PMessageRelay.peerEndpoints[peerName]
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
                    P2PMessageRelay.sendFile(context, peerName, endpoint, tempFile.absolutePath, outMsg.id) { success ->
                        if (!success) {
                            db.updateMessageStatus(outMsg.id, "PENDING")
                            coroutineScope.launch {
                                val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                if (idx != -1) {
                                    initialMessages[idx] = outMsg.copy(status = "PENDING")
                                }
                                val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", true)
                                errorReasonYggdrasilDisabled = !isYggEnabled
                                showConnectionErrorDialog = true
                            }
                        }
                    }
                } else if (peerName != "Saved Messages") {
                    val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", true)
                    errorReasonYggdrasilDisabled = !isYggEnabled
                    showConnectionErrorDialog = true
                }
            }
        }
    }

    var showLocationDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var isSearchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

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
                if (!hasScrolledToBottomOnInit) {
                    listState.scrollToItem(lastIndex)
                    hasScrolledToBottomOnInit = true
                } else {
                    listState.animateScrollToItem(lastIndex)
                }
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
            if (isSearchMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(surfaceColor)
                        .border(width = 0.5.dp, color = onSurfaceColor.copy(alpha = 0.05f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        isSearchMode = false
                        searchQuery = ""
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back_arrow),
                            contentDescription = "Close search",
                            tint = onSurfaceColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = if (appLanguage == "Русский") "Поиск по сообщениям..." else "Search messages...",
                                color = onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = onSurfaceColor.copy(alpha = 0.2f),
                            cursorColor = primaryColor,
                            focusedTextColor = onSurfaceColor,
                            unfocusedTextColor = onSurfaceColor
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        shape = RoundedCornerShape(24.dp)
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Text("×", fontSize = 22.sp, color = onSurfaceVariant, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
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
                        painter = painterResource(id = R.drawable.ic_back_arrow),
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
                        .clickable(enabled = peerName != "Saved Messages") { showProfileOverlay = true }
                ) {
                    val avatarBitmap = P2PMessageRelay.peerAvatars[peerName]
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap.asImageBitmap(),
                            contentDescription = "Avatar",
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else if (initials == "🔖") {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_saved_messages),
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

                val isMismatch = sharedPrefs.getBoolean("fingerprint_mismatch_${peerName}", false)
                val shieldColor = when {
                    isMismatch -> Color(0xFFF44336) // Red
                    isVerified -> Color(0xFF4CAF50) // Green
                    else -> Color(0xFFFFC107) // Yellow
                }

                Column(modifier = Modifier.weight(1f).clickable(enabled = peerName != "Saved Messages") { showProfileOverlay = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceColor
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val endpoint = P2PMessageRelay.peerEndpoints[peerName]
                        val isOnline = P2PMessageRelay.peerSessionStates[peerName] == true
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
                                val transportName = P2PMessageRelay.peerConnectionTransports[peerName]
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
                        onClick = {
                            if (activeFingerprint.isBlank() || localFingerprint.isBlank()) {
                                Toast.makeText(
                                    context,
                                    if (appLanguage == "Русский") "Fingerprint ещё недоступен" else "Fingerprint is not available yet",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                showVerifyDialog = true
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_shield_status),
                            contentDescription = "Verify",
                            tint = shieldColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Search icon button
                IconButton(
                    onClick = { isSearchMode = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_menu_search),
                        contentDescription = if (appLanguage == "Русский") "Поиск" else "Search",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))

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
                                    P2PMessageRelay.reconnectSession(context, peerName) { success ->
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
                        if (peerName != "Saved Messages") {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isMuted) {
                                            if (appLanguage == "Русский") "Включить уведомления" else "Unmute Notifications"
                                        } else {
                                            if (appLanguage == "Русский") "Выключить уведомления" else "Mute Notifications"
                                        },
                                        color = onSurfaceColor,
                                        fontSize = 14.sp
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    val targetState = !isMuted
                                    sharedPrefs.edit { putBoolean("mute_notifications_${peerName}", targetState) }
                                    isMuted = targetState
                                    val toastText = if (targetState) {
                                        if (appLanguage == "Русский") "Уведомления отключены" else "Notifications muted"
                                    } else {
                                        if (appLanguage == "Русский") "Уведомления включены" else "Notifications unmuted"
                                    }
                                    Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
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
                                sharedPrefs.edit { remove("last_msg_$peerName") }
                            }
                        )
                        if (peerName != "Saved Messages") {
                            DropdownMenuItem(
                                text = { 
                                    Text(
                                        text = if (appLanguage == "Русский") "Удалить чат" else "Delete Chat", 
                                        color = Color.Red,
                                        fontSize = 14.sp
                                    ) 
                                },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                }
            }
            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = {
                        Text(if (appLanguage == "Русский") "Удалить чат?" else "Delete chat?")
                    },
                    text = {
                        Text(
                            if (appLanguage == "Русский") {
                                "Вы уверены, что хотите полностью удалить этот чат? Все сообщения будут безвозвратно удалены."
                            } else {
                                "Are you sure you want to delete this chat? All message history will be permanently lost."
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            com.example.twopchat.P2PMessageRelay.deleteChat(context, peerName)
                            showDeleteDialog = false
                            onBack()
                        }) {
                            Text(if (appLanguage == "Русский") "Удалить" else "Delete", color = Color.Red)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text(if (appLanguage == "Русский") "Отмена" else "Cancel")
                        }
                    }
                )
            }
            } // end else (normal header)

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
                        painter = painterResource(id = R.drawable.ic_pin),
                        contentDescription = "Pinned",
                        tint = primaryColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        val pinnedByText = if (pinnedBy == "You") {
                            if (appLanguage == "Русский") "Вы закрепили сообщение" else "You pinned a message"
                        } else {
                            val name = pinnedBy ?: peerName
                            if (appLanguage == "Русский") "$name закрепил(а) сообщение" else "$name pinned a message"
                        }
                        Text(
                            text = pinnedByText,
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
                            sharedPrefs.edit {
remove("pinned_msg_id_${peerName}")
                                remove("pinned_msg_text_${peerName}")
                                remove("pinned_msg_sender_${peerName}")
                                remove("pinned_by_${peerName}")
                            }
                            pinnedMsgId = null
                            pinnedMsgText = null
                            pinnedMsgSender = null
                            pinnedBy = null
                            P2PMessageRelay.sendUnpinMessage(context, peerName)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("×", fontSize = 18.sp, color = onSurfaceVariant, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Messages List
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (isHistoryLoading) {
                    CircularProgressIndicator(
                        color = primaryColor,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
                ) {
                if (isSearchMode && searchQuery.isNotEmpty()) {
                    item {
                        val count = initialMessages.count { msg ->
                            msg.text.contains(searchQuery, ignoreCase = true) ||
                            (msg.attachmentName?.contains(searchQuery, ignoreCase = true) == true)
                        }
                        Text(
                            text = if (appLanguage == "Русский") "Найдено сообщений: $count" else "Messages found: $count",
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(primaryColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            fontSize = 12.sp,
                            color = primaryColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                val displayMessages = if (isSearchMode && searchQuery.isNotEmpty()) {
                    initialMessages.filter { msg ->
                        msg.text.contains(searchQuery, ignoreCase = true) ||
                        (msg.attachmentName?.contains(searchQuery, ignoreCase = true) == true)
                    }
                } else {
                    initialMessages.toList()
                }
                itemsIndexed(
                    items = displayMessages,
                    key = { _, msg -> msg.id }
                ) { index, msg ->
                    val visibleState = remember(msg.id) {
                        val isNew = msg.sentAtEpochMs > screenInitTime + 500L
                        MutableTransitionState(if (isNew) false else true).apply {
                            targetState = true
                        }
                    }
                    val alignment = if (msg.isMe) Alignment.End else Alignment.Start
                    val bubbleShape = if (msg.isMe) {
                        RoundedCornerShape(18.dp, 18.dp, 2.dp, 18.dp)
                    } else {
                        RoundedCornerShape(18.dp, 18.dp, 18.dp, 2.dp)
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
                        val isLight = surfaceColor.luminance() > 0.5f
                        Modifier.background(
                            color = if (isLight) surfaceColor else surfaceColor,
                            shape = bubbleShape
                        )
                    }

                    val textColor = if (msg.isMe) {
                        if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color.White
                    } else onSurfaceColor

                    androidx.compose.animation.AnimatedVisibility(
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
                                            .then(if (!msg.isMe) Modifier.border(0.5.dp, onSurfaceColor.copy(alpha = if (surfaceColor.luminance() > 0.5f) 0.09f else 0.08f), bubbleShape) else Modifier)
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
                                                                        val allImages = initialMessages.filter { it.attachmentType == "IMAGE" && !it.attachmentUri.isNullOrBlank() }.map { it.attachmentUri!! }
                                                                        val clickedUri = msg.attachmentUri
                                                                        val clickedIndex = if (clickedUri != null) allImages.indexOf(clickedUri) else -1
                                                                        if (clickedIndex != -1) {
                                                                            activeFullscreenImages = allImages
                                                                            activeFullscreenImageIndex = clickedIndex
                                                                        } else if (clickedUri != null) {
                                                                            activeFullscreenImages = listOf(clickedUri)
                                                                            activeFullscreenImageIndex = 0
                                                                        }
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
                                                "VIDEO" -> {
                                                    val thumbnail = rememberVideoThumbnail(msg.attachmentUri)
                                                    val openVideo = {
                                                        msg.attachmentUri?.let { uriPath ->
                                                            try {
                                                                val file = java.io.File(uriPath)
                                                                val contentUri = androidx.core.content.FileProvider.getUriForFile(
                                                                    context,
                                                                    "${context.packageName}.fileprovider",
                                                                    file
                                                                )
                                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                                    setDataAndType(contentUri, "video/*")
                                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                                }
                                                                context.startActivity(intent)
                                                            } catch (e: Exception) {
                                                                Toast.makeText(context, if (appLanguage == "Русский") "Не удалось открыть видео" else "Cannot open video", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    }
                                                    Column {
                                                        Box(
                                                            contentAlignment = Alignment.Center,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(180.dp)
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .clickable { openVideo() }
                                                        ) {
                                                            if (thumbnail != null) {
                                                                Image(
                                                                    bitmap = thumbnail.asImageBitmap(),
                                                                    contentDescription = "Video attachment",
                                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                                    modifier = Modifier.fillMaxSize()
                                                                )
                                                            } else {
                                                                Box(
                                                                    modifier = Modifier
                                                                        .fillMaxSize()
                                                                        .background(Color.Black.copy(alpha = 0.2f)),
                                                                    contentAlignment = Alignment.Center
                                                                ) {
                                                                    Icon(
                                                                        painter = painterResource(id = R.drawable.ic_attach_file),
                                                                        contentDescription = "Video",
                                                                        tint = textColor.copy(alpha = 0.5f),
                                                                        modifier = Modifier.size(40.dp)
                                                                    )
                                                                }
                                                            }
                                                            Box(
                                                                contentAlignment = Alignment.Center,
                                                                modifier = Modifier
                                                                    .size(48.dp)
                                                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                                            ) {
                                                                Icon(
                                                                    painter = painterResource(id = R.drawable.ic_voice_play),
                                                                    contentDescription = "Play",
                                                                    tint = Color.White,
                                                                    modifier = Modifier.size(24.dp).padding(start = 2.dp)
                                                                )
                                                            }
                                                        }
                                                        if (!msg.text.startsWith("Sent a video")) {
                                                            Spacer(modifier = Modifier.height(6.dp))
                                                            Text(
                                                                text = msg.text,
                                                                color = textColor,
                                                                fontSize = 15.sp,
                                                                lineHeight = 20.sp
                                                            )
                                                        }
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
                                                                painter = painterResource(id = R.drawable.ic_attach_file),
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
                                                "VOICE" -> {
                                                    VoiceMessagePlayer(
                                                        filePath = msg.attachmentUri,
                                                        isMine = msg.isMe,
                                                        primaryColor = primaryColor,
                                                        contentColor = textColor,
                                                    )
                                                }
                                                "LOCATION" -> {
                                                    Column {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Icon(
                                                                painter = painterResource(id = R.drawable.ic_attach_location),
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
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.align(Alignment.End)
                                            ) {
                                                Text(
                                                    text = MessageTimestampFormatter.format(msg, appLanguage),
                                                    color = (if (msg.isMe) {
                                                        if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.65f)
                                                    } else onSurfaceColor.copy(alpha = 0.5f)),
                                                    fontSize = 9.sp
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
                                                    val statusColor = if (msg.isMe) {
                                                        if (primaryColor == com.example.twopchat.theme.MintGreen) {
                                                            if (isRead) StealthBlack else StealthBlack.copy(alpha = 0.4f)
                                                        } else {
                                                            if (isRead) Color.White else Color.White.copy(alpha = 0.5f)
                                                        }
                                                    } else {
                                                        if (isRead) primaryColor else onSurfaceVariant.copy(alpha = 0.4f)
                                                    }
                                                    
                                                    Text(
                                                        text = statusText,
                                                        color = statusColor,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                if (msg.reactions.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        msg.reactions.forEach { (emoji, senders) ->
                                                            Surface(
                                                                shape = RoundedCornerShape(8.dp),
                                                                color = if (msg.isMe) {
                                                                    if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.25f)
                                                                } else primaryColor.copy(alpha = 0.15f)
                                                            ) {
                                                                Row(
                                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Text(text = emoji, fontSize = 11.sp)
                                                                    if (senders.size > 1) {
                                                                        Text(
                                                                            text = " ${senders.size}",
                                                                            fontSize = 9.sp,
                                                                            color = if (msg.isMe) {
                                                                                if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color.White
                                                                            } else onSurfaceColor,
                                                                            fontWeight = FontWeight.Bold
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
            }

            // Scroll To Bottom Button
            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollDownButton,
                enter = scaleIn(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
                exit = scaleOut(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
            ) {
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            if (initialMessages.isNotEmpty()) {
                                listState.animateScrollToItem(initialMessages.size - 1)
                            }
                        }
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color(0xFF1E2226).copy(alpha = 0.76f), CircleShape)
                        .border(width = 0.5.dp, color = Color.White.copy(alpha = 0.08f), shape = CircleShape)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_scroll_down),
                        contentDescription = "Scroll Down",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
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
                                        val attachmentsDir = File(context.filesDir, "attachments")
                                        if (!attachmentsDir.exists()) attachmentsDir.mkdirs()
                                        val file = File(attachmentsDir, "camera_capture_${System.currentTimeMillis()}.jpg")
                                        tempCameraFile = file
                                        val photoUri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        cameraLauncher.launch(photoUri)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Camera launch failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                "Gallery" -> galleryLauncher.launch("image/*")
                                "Video" -> videoLauncher.launch("video/*")
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

                // Editing Message Preview Bar
                AnimatedVisibility(
                    visible = editingMessage != null,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
                ) {
                    editingMessage?.let { editMsg ->
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
                                    text = if (appLanguage == "Русский") "Редактирование сообщения" else "Edit Message",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor
                                )
                                Text(
                                    text = editMsg.text,
                                    fontSize = 11.sp,
                                    color = onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { 
                                    editingMessage = null 
                                    inputText = ""
                                },
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
                                        sharedPrefs.edit {
                                            remove("pinned_msg_id_${peerName}")
                                            remove("pinned_msg_text_${peerName}")
                                            remove("pinned_msg_sender_${peerName}")
                                            remove("pinned_by_${peerName}")
                                        }
                                        pinnedMsgId = null
                                        pinnedMsgText = null
                                        pinnedMsgSender = null
                                        pinnedBy = null
                                        P2PMessageRelay.sendUnpinMessage(context, peerName)
                                    }
                                }
                                selectedMessages.clear()
                                isSelectMode = false
                            },
                            enabled = selectedMessages.isNotEmpty()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_delete),
                                contentDescription = "Delete Selected",
                                tint = if (selectedMessages.isNotEmpty()) Color.Red else onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                } else if (isBlocked && peerName != "Saved Messages") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                            .border(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Пользователь заблокирован" else "User is blocked",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (appLanguage == "Русский") "Разблокировать" else "Unblock",
                            color = primaryColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    sharedPrefs.edit { putBoolean("blocked_peer_${peerName}", false) }
                                    isBlocked = false
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Attachment toggle button
                        IconButton(
                            onClick = {
                                if (isRecordingVoice) {
                                    voiceRecorder.cancel()
                                    isRecordingVoice = false
                                    recordingElapsedMs = 0
                                } else {
                                    showAttachments = !showAttachments
                                }
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .background(onSurfaceColor.copy(alpha = 0.03f), shape = CircleShape)
                        ) {
                            if (showAttachments || isRecordingVoice) {
                                Text(
                                    text = "×",
                                    fontSize = 22.sp,
                                    color = primaryColor,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_attach_paperclip),
                                    contentDescription = "Attach",
                                    tint = primaryColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        val isDark = surfaceColor.luminance() < 0.5f
                        val inputBg = if (isDark) Color(0xFF0F1012) else Color(0xFFE4E7EC)

                        if (isRecordingVoice) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .background(inputBg, RoundedCornerShape(22.dp))
                                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(22.dp))
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(9.dp).background(Color.Red, CircleShape))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = VoiceMessageSupport.formatDuration(recordingElapsedMs),
                                    color = onSurfaceColor,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = if (appLanguage == "Русский") "Нажмите × для отмены" else "Tap × to cancel",
                                    color = onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                        } else {
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
                                    .border(0.5.dp, onSurfaceColor.copy(alpha = if (surfaceColor.luminance() > 0.5f) 0.09f else 0.05f), RoundedCornerShape(22.dp))
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        IconButton(
                            onClick = {
                                if (isRecordingVoice) {
                                    val recording = voiceRecorder.stop()
                                    isRecordingVoice = false
                                    recordingElapsedMs = 0
                                    if (recording != null) {
                                        sendVoiceRecording(recording)
                                    } else {
                                        Toast.makeText(context, if (appLanguage == "Русский") "Запись слишком короткая" else "Recording is too short", Toast.LENGTH_SHORT).show()
                                    }
                                } else if (inputText.isBlank()) {
                                    showAttachments = false
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        beginVoiceRecording()
                                    } else {
                                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                } else {
                                    val userText = inputText.trim()
                                    inputText = ""
                                    showAttachments = false
                                    val currentEditing = editingMessage
                                    if (currentEditing != null) {
                                        editingMessage = null
                                        if (userText.isNotEmpty()) {
                                            db.updateMessageText(currentEditing.id, userText, true)
                                            val idx = initialMessages.indexOfFirst { it.id == currentEditing.id }
                                            if (idx != -1) {
                                                val oldStatus = currentEditing.status ?: ""
                                                val newStatus = if (oldStatus.contains("edited")) oldStatus else if (oldStatus.isEmpty()) "edited" else "${oldStatus}_edited"
                                                initialMessages[idx] = currentEditing.copy(text = userText, status = newStatus)
                                            }
                                            val endpoint = P2PMessageRelay.peerEndpoints[peerName]
                                            if (endpoint != null && peerName != "Saved Messages") {
                                                P2PMessageRelay.sendEditMessage(context, peerName, endpoint, currentEditing.id, userText)
                                            }
                                        }
                                    } else {
                                        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                        
                                        val replyTo = replyingToMessage
                                        replyingToMessage = null

                                    val endpoint = P2PMessageRelay.peerEndpoints[peerName]
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
                                        sharedPrefs.edit { putStringSet("active_chats", newSet) }
                                    }
                                    sharedPrefs.edit { putString("last_msg_$peerName", SecureStorage.encrypt("You: $userText")) }

                                    // Send message payload
                                    val payload = if (replyTo != null) {
                                        org.json.JSONObject().apply {
                                            put("type", "reply")
                                            put("message_id", outMsg.id)
                                            put("text", userText)
                                            put("reply_to_id", replyTo.id)
                                            put("reply_to_text", replyTo.text)
                                            put("reply_to_name", replyTo.let { if (it.isMe) username else peerName })
                                        }.toString()
                                    } else {
                                        org.json.JSONObject().apply {
                                            put("type", "text")
                                            put("message_id", outMsg.id)
                                            put("text", userText)
                                        }.toString()
                                    }

                                    // Send over real TCP socket if endpoint is resolved
                                    if (endpoint != null && peerName != "Saved Messages") {
                                        P2PMessageRelay.sendMessage(context, endpoint, username, payload) { success ->
                                            if (!success) {
                                                db.updateMessageStatus(outMsg.id, "PENDING")
                                                coroutineScope.launch {
                                                    val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                                    if (idx != -1) {
                                                        initialMessages[idx] = outMsg.copy(status = "PENDING")
                                                    }
                                                    val isYggEnabled = sharedPrefs.getBoolean("settings_yggdrasil", true)
                                                    errorReasonYggdrasilDisabled = !isYggEnabled
                                                    showConnectionErrorDialog = true
                                                }
                                            }
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
                                painter = painterResource(
                                    id = when {
                                        isRecordingVoice -> R.drawable.ic_voice_stop
                                        editingMessage != null -> R.drawable.ic_check
                                        inputText.isBlank() -> R.drawable.ic_voice_mic
                                        else -> R.drawable.ic_send_airplane
                                    }
                                ),
                                contentDescription = when {
                                    isRecordingVoice -> "Send voice message"
                                    editingMessage != null -> "Confirm edit"
                                    inputText.isBlank() -> "Record voice message"
                                    else -> "Send"
                                },
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

                        // Quick Emoji Reactions
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf("👍", "❤️", "😂", "😮", "😢", "🔥", "💩").forEach { emoji ->
                                Surface(
                                    shape = CircleShape,
                                    color = primaryColor.copy(alpha = 0.12f),
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clickable {
                                            val idx = initialMessages.indexOfFirst { it.id == msg.id }
                                            if (idx != -1) {
                                                val current = initialMessages[idx]
                                                val updatedMap = current.reactions.toMutableMap()
                                                val sendersList = (updatedMap[emoji] ?: emptyList()).toMutableList()
                                                if (!sendersList.contains("Me")) {
                                                    sendersList.add("Me")
                                                    updatedMap[emoji] = sendersList
                                                    initialMessages[idx] = current.copy(reactions = updatedMap)
                                                    db.updateMessageReactions(msg.id, updatedMap)
                                                }
                                            }
                                            val endpoint = P2PMessageRelay.peerEndpoints[peerName]
                                            if (endpoint != null && peerName != "Saved Messages") {
                                                P2PMessageRelay.sendReaction(context, peerName, endpoint, msg.id, msg.text, emoji)
                                            }
                                            selectedMessageForOptions = null
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(text = emoji, fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = onSurfaceColor.copy(alpha = 0.08f)
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
                                painter = painterResource(id = R.drawable.ic_reply),
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
                                    sharedPrefs.edit {
                                        putString("pinned_msg_id_${peerName}", msg.id)
                                        putString("pinned_msg_text_${peerName}", SecureStorage.encrypt(msg.text))
                                        putString("pinned_msg_sender_${peerName}", if (msg.isMe) "You" else peerName)
                                        putString("pinned_by_${peerName}", "You")
                            }
                                    pinnedMsgId = msg.id
                                    pinnedMsgText = msg.text
                                    pinnedMsgSender = if (msg.isMe) "You" else peerName
                                    pinnedBy = "You"
                                    P2PMessageRelay.sendPinMessage(context, peerName, msg.id, msg.text, msg.isMe)
                                    selectedMessageForOptions = null
                                }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_pin),
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

                        // Edit
                        val isEditable = msg.isMe && 
                                (System.currentTimeMillis() - msg.sentAtEpochMs <= 3600_000L) && 
                                msg.attachmentType == null
                        
                        if (isEditable) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        editingMessage = msg
                                        inputText = msg.text
                                        selectedMessageForOptions = null
                                    }
                                    .padding(vertical = 12.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_edit),
                                    contentDescription = "Edit",
                                    tint = onSurfaceColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = if (appLanguage == "Русский") "Редактировать" else "Edit",
                                    fontSize = 15.sp,
                                    color = onSurfaceColor
                                )
                            }
                        }

                        // Copy
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    copyTextToClipboard(context, "2PChat message", msg.text)
                                    Toast.makeText(context, if (appLanguage == "Русский") "Текст скопирован" else "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                                    selectedMessageForOptions = null
                                }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_copy),
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

                        // Save Image (Only if attachmentType is IMAGE)
                        if (msg.attachmentType == "IMAGE" && msg.attachmentUri != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val path = msg.attachmentUri
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                                            ) == PackageManager.PERMISSION_GRANTED
                                        ) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val uri = saveImageToPublicGallery(context, path)
                                                withContext(Dispatchers.Main) {
                                                    if (uri != null) {
                                                        Toast.makeText(context, if (appLanguage == "Русский") "Изображение сохранено в Галерею" else "Image saved to Gallery", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(context, if (appLanguage == "Русский") "Не удалось сохранить изображение" else "Failed to save image", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        } else {
                                            pendingDownloadPath = path
                                            galleryWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        }
                                        selectedMessageForOptions = null
                                    }
                                    .padding(vertical = 12.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_download),
                                    contentDescription = "Save Image",
                                    tint = onSurfaceColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = if (appLanguage == "Русский") "Скачать изображение" else "Save Image",
                                    fontSize = 15.sp,
                                    color = onSurfaceColor
                                )
                            }
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
                                painter = painterResource(id = R.drawable.ic_forward),
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
                                        sharedPrefs.edit {
remove("pinned_msg_id_${peerName}")
                                            remove("pinned_msg_text_${peerName}")
                                            remove("pinned_msg_sender_${peerName}")
                                            remove("pinned_by_${peerName}")
                                        }
                                        pinnedMsgId = null
                                        pinnedMsgText = null
                                        pinnedMsgSender = null
                                        pinnedBy = null
                                        P2PMessageRelay.sendUnpinMessage(context, peerName)
                                    }
                                    selectedMessageForOptions = null
                                }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_delete),
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
                                painter = painterResource(id = R.drawable.ic_select),
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
                                            val forwardEndpoint = P2PMessageRelay.peerEndpoints[chatName]
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
                                            sharedPrefs.edit { putString("last_msg_$chatName", SecureStorage.encrypt("You: $textToForward")) }
                                            
                                            // Send if there is an endpoint
                                            if (forwardEndpoint != null && chatName != "Saved Messages") {
                                                if (messageToForward?.attachmentType != null && messageToForward?.attachmentUri != null) {
                                                    P2PMessageRelay.sendFile(context, chatName, forwardEndpoint, messageToForward!!.attachmentUri!!, fwdMsg.id) { success ->
                                                        if (!success) {
                                                            db.updateMessageStatus(fwdMsg.id, "PENDING")
                                                        }
                                                    }
                                                } else {
                                                    P2PMessageRelay.sendMessage(context, forwardEndpoint, username, textToForward) { success ->
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
                                    }
                                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            ) {
                                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_attach_location),
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
            val emojis = remember(localFingerprint, activeFingerprint) {
                getVerificationEmojis(localFingerprint, activeFingerprint)
            }

            AlertDialog(
                onDismissRequest = { 
                    if (!isWaitingForVerifyResponse) showVerifyDialog = false 
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        enabled = !isWaitingForVerifyResponse,
                        onClick = { showVerifyDialog = false }
                    ) {
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
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") {
                                "Сравните эти эмодзи безопасности со своим собеседником по другому каналу или голосом:"
                            } else {
                                "Compare these security emojis with your peer over another channel or voice:"
                            },
                            fontSize = 13.sp,
                            color = onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(vertical = 12.dp)
                                .fillMaxWidth()
                        ) {
                            emojis.forEach { emoji ->
                                Text(text = emoji, fontSize = 32.sp)
                            }
                        }

                        if (isVerified) {
                            Button(
                                onClick = {
                                    isVerified = false
                                    sharedPrefs.edit { putBoolean("verified_peer_${peerName}", false) }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(Localizations.getString("unverify_btn", appLanguage))
                            }
                        } else {
                            if (isWaitingForVerifyResponse) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    CircularProgressIndicator(
                                        color = primaryColor,
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = if (appLanguage == "Русский") "Ожидание подтверждения от собеседника..." else "Waiting for confirmation from peer...",
                                        fontSize = 11.sp,
                                        color = onSurfaceVariant
                                    )
                                }
                            } else {
                                Button(
                                    onClick = {
                                        isWaitingForVerifyResponse = true
                                        P2PMessageRelay.sendVerificationRequest(context, peerName) { success ->
                                            if (!success) {
                                                isWaitingForVerifyResponse = false
                                                Toast.makeText(context, if (appLanguage == "Русский") "Не удалось отправить запрос" else "Failed to send request", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF4CAF50),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (appLanguage == "Русский") "Отправить запрос верификации" else "Send verification request"
                                    )
                                }
                            }
                        }
                    }
                },
                containerColor = surfaceColor,
                shape = RoundedCornerShape(20.dp)
            )
        }

        if (showIncomingVerifyDialog) {
            val peerFingerprint = sharedPrefs.getString("peer_fingerprint_$peerName", null).orEmpty()
            val emojis = remember(localFingerprint, peerFingerprint) {
                getVerificationEmojis(localFingerprint, peerFingerprint)
            }

            AlertDialog(
                onDismissRequest = {
                    showIncomingVerifyDialog = false
                    P2PMessageRelay.sendVerificationResponse(context, peerName, false)
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        onClick = {
                            showIncomingVerifyDialog = false
                            P2PMessageRelay.sendVerificationResponse(context, peerName, false)
                        }
                    ) {
                        Text(if (appLanguage == "Русский") "Отклонить" else "Decline", color = MaterialTheme.colorScheme.error)
                    }
                },
                title = {
                    Text(
                        text = if (appLanguage == "Русский") "Запрос верификации" else "Verification Request",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") {
                                "$peerName предлагает подтвердить безопасность вашего подключения. Сверьте эти эмодзи:"
                            } else {
                                "$peerName wants to verify the security of your connection. Compare these emojis:"
                            },
                            fontSize = 13.sp,
                            color = onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(vertical = 12.dp)
                                .fillMaxWidth()
                        ) {
                            emojis.forEach { emoji ->
                                Text(text = emoji, fontSize = 32.sp)
                            }
                        }

                        Button(
                            onClick = {
                                isVerified = true
                                sharedPrefs.edit { putBoolean("verified_peer_${peerName}", true) }
                                P2PMessageRelay.sendVerificationResponse(context, peerName, true)
                                showIncomingVerifyDialog = false
                                Toast.makeText(context, if (appLanguage == "Русский") "Личность подтверждена! Соединение защищено." else "Identity verified! Connection secured.", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4CAF50),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (appLanguage == "Русский") "Подтвердить совпадение" else "Confirm Match"
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
                                        sharedPrefs.edit { putBoolean("settings_yggdrasil", true) }
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

        if (activeFullscreenImages.isNotEmpty()) {
            FullscreenImageViewer(
                imagePaths = activeFullscreenImages,
                initialIndex = activeFullscreenImageIndex,
                appLanguage = appLanguage,
                onClose = { activeFullscreenImages = emptyList() }
            )
        }

        if (showProfileOverlay && peerName != "Saved Messages") {
            SharedMediaScreen(
                peerName = peerName,
                messages = initialMessages.toList(),
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                appLanguage = appLanguage,
                isVerified = isVerified,
                isMuted = isMuted,
                onToggleMute = { newMuted ->
                    isMuted = newMuted
                    sharedPrefs.edit().putBoolean("mute_notifications_$peerName", newMuted).apply()
                },
                onImageClick = { paths, index ->
                    if (paths.isNotEmpty()) {
                        activeFullscreenImages = paths
                        activeFullscreenImageIndex = index
                        showProfileOverlay = false
                    }
                },
                onBack = { showProfileOverlay = false }
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
            AttachmentItem("Camera", R.drawable.ic_attach_camera, primaryColor.copy(alpha = 0.1f)),
            AttachmentItem("Gallery", R.drawable.ic_attach_gallery, primaryColor.copy(alpha = 0.1f)),
            AttachmentItem("Video", R.drawable.ic_voice_play, primaryColor.copy(alpha = 0.1f)),
            AttachmentItem("File", R.drawable.ic_attach_file, primaryColor.copy(alpha = 0.1f)),
            AttachmentItem("Location", R.drawable.ic_attach_location, primaryColor.copy(alpha = 0.1f))
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

object AttachmentImageCache {
    private val cacheSize = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    private val cache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}

@Composable
fun rememberSampledImage(filePath: String?, targetWidth: Int = 400, targetHeight: Int = 400): Bitmap? {
    if (filePath == null) return null
    val cached = AttachmentImageCache.get(filePath)
    var bitmapState by remember(filePath) { mutableStateOf<Bitmap?>(cached) }
    LaunchedEffect(filePath) {
        if (bitmapState != null) return@LaunchedEffect
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
                        AttachmentImageCache.put(filePath, decoded)
                        withContext(Dispatchers.Main) {
                            bitmapState = decoded
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    return bitmapState
}

@Composable
fun rememberVideoThumbnail(filePath: String?): Bitmap? {
    if (filePath == null) return null
    val cacheKey = "thumb_$filePath"
    val cached = AttachmentImageCache.get(cacheKey)
    var bitmapState by remember(filePath) { mutableStateOf<Bitmap?>(cached) }
    LaunchedEffect(filePath) {
        if (bitmapState != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val file = java.io.File(filePath)
                if (file.exists()) {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(filePath)
                    val frame = retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()
                    if (frame != null) {
                        AttachmentImageCache.put(cacheKey, frame)
                        withContext(Dispatchers.Main) {
                            bitmapState = frame
                        }
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FullscreenImageViewer(
    imagePaths: List<String>,
    initialIndex: Int,
    appLanguage: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = initialIndex,
        pageCount = { imagePaths.size }
    )
    var isZoomed by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scope.launch(Dispatchers.IO) {
                val currentPath = imagePaths[pagerState.currentPage]
                val uri = saveImageToPublicGallery(context, currentPath)
                withContext(Dispatchers.Main) {
                    if (uri != null) {
                        Toast.makeText(context, if (appLanguage == "Русский") "Изображение сохранено в Галерею" else "Image saved to Gallery", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, if (appLanguage == "Русский") "Не удалось сохранить изображение" else "Failed to save image", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(context, if (appLanguage == "Русский") "Разрешение на запись отклонено" else "Storage permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !isZoomed
        ) { page ->
            val imagePath = imagePaths[page]
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }

            val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
                scale = (scale * zoomChange).coerceIn(1f, 5f)
                if (scale > 1f) {
                    offset += offsetChange
                } else {
                    offset = Offset.Zero
                }
            }

            LaunchedEffect(scale) {
                if (page == pagerState.currentPage) {
                    isZoomed = scale > 1f
                }
            }

            LaunchedEffect(pagerState.currentPage) {
                scale = 1f
                offset = Offset.Zero
                if (page == pagerState.currentPage) {
                    isZoomed = false
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { onClose() },
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
                                translationX = if (scale > 1f) offset.x else 0f,
                                translationY = if (scale > 1f) offset.y else 0f
                            )
                            .transformable(state = transformState)
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
            }
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
                painter = painterResource(id = R.drawable.ic_back_arrow),
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // Page Indicator
        if (imagePaths.size > 1) {
            Text(
                text = "${pagerState.currentPage + 1} / ${imagePaths.size}",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
            )
        }

        // Download Button
        IconButton(
            onClick = {
                val currentPath = imagePaths[pagerState.currentPage]
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    scope.launch(Dispatchers.IO) {
                        val uri = saveImageToPublicGallery(context, currentPath)
                        withContext(Dispatchers.Main) {
                            if (uri != null) {
                                Toast.makeText(context, if (appLanguage == "Русский") "Изображение сохранено в Галерею" else "Image saved to Gallery", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, if (appLanguage == "Русский") "Не удалось сохранить изображение" else "Failed to save image", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 16.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_download),
                contentDescription = "Download",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

fun getVerificationEmojis(localFingerprint: String, peerFingerprint: String): List<String> {
    val emojiList = listOf(
        "🦄", "🦊", "🚀", "💎", "🍕", "🎈", "🚗", "🥝", "🎸", "🌟",
        "🦁", "🐼", "🐻", "🐨", "🐙", "🦋", "🍄", "🍉", "🍓", "🍍",
        "🥞", "🍔", "🍿", "🍩", "🍪", "🛹", "🚲", "⛵", "🛸", "🌈",
        "☀️", "⚡", "🔥", "🔮", "🛡️", "🔑", "📦", "🎨", "🎭", "🎮"
    )
    val hash = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val identityPair = listOf(localFingerprint, peerFingerprint).sorted().joinToString("|")
        digest.digest(identityPair.toByteArray(Charsets.UTF_8))
    } catch (e: java.lang.Exception) {
        (localFingerprint + peerFingerprint).toByteArray(Charsets.UTF_8)
    }
    val result = mutableListOf<String>()
    for (i in 0 until 4) {
        val byteVal = if (i < hash.size) hash[i].toInt() and 0xFF else 0
        val index = byteVal % emojiList.size
        result.add(emojiList[index])
    }
    return result
}

fun saveImageToPublicGallery(context: android.content.Context, filePath: String): Uri? {
    val srcFile = File(filePath)
    if (!srcFile.exists()) return null

    val extension = srcFile.extension.lowercase()
    val mimeType = when (extension) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/x-ms-bmp"
        else -> "image/jpeg"
    }
    val fileName = "2pchat_${System.currentTimeMillis()}.${if (extension.isNotEmpty()) extension else "jpg"}"

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "2PChat")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (imageUri != null) {
                resolver.openOutputStream(imageUri).use { outputStream ->
                    if (outputStream != null) {
                        FileInputStream(srcFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
                return imageUri
            }
        } else {
            val targetDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "2PChat"
            )
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val targetFile = File(targetDir, fileName)
            FileOutputStream(targetFile).use { outputStream ->
                FileInputStream(srcFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType),
                null
            )
            return Uri.fromFile(targetFile)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}
