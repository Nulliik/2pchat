package com.example.twopchat.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import android.content.Intent
import android.net.VpnService
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import com.example.twopchat.yggdrasil.PacketTunnelProvider
import androidx.core.content.edit
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.data.Localizations
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.P2PPreferences
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
import java.io.File
import java.io.FileOutputStream
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import com.example.twopchat.theme.StealthBlack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun newMessageId(): String = java.util.UUID.randomUUID().toString()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    peerName: String,
    isActive: Boolean,
    appLanguage: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeFullscreenImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeFullscreenImageIndex by remember { mutableStateOf(0) }
    var activeFullscreenBitmapOverrides by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var activeFullscreenVideo by remember { mutableStateOf<String?>(null) }
    var showProfileOverlay by remember { mutableStateOf(false) }

    BackHandler {
        if (activeFullscreenImages.isNotEmpty()) {
            activeFullscreenImages = emptyList()
            activeFullscreenBitmapOverrides = emptyMap()
        } else if (activeFullscreenVideo != null) {
            activeFullscreenVideo = null
        } else if (showProfileOverlay) {
            showProfileOverlay = false
        } else {
            onBack()
        }
    }
    
    val coroutineScope = rememberCoroutineScope()
    fun persistDatabase(operation: () -> Unit) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                operation()
            } catch (error: Exception) {
                android.util.Log.e("ChatScreen", "Background database operation failed", error)
            }
        }
    }
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
    var pendingDownloadMsg by remember { mutableStateOf<Message?>(null) }

    val storageWritePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val msg = pendingDownloadMsg
        if (isGranted && msg != null && msg.attachmentUri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val uri = if (msg.attachmentType == "IMAGE") {
                    saveImageToPublicGallery(context, msg.attachmentUri)
                } else {
                    saveFileToPublicDownloads(context, msg.attachmentUri, msg.attachmentName ?: "file")
                }
                withContext(Dispatchers.Main) {
                    if (uri != null) {
                        val successText = if (msg.attachmentType == "IMAGE") {
                            if (appLanguage == "Русский") "Изображение сохранено в Галерею" else "Image saved to Gallery"
                        } else {
                            if (appLanguage == "Русский") "Файл сохранен в Загрузки" else "File saved to Downloads"
                        }
                        Toast.makeText(context, successText, Toast.LENGTH_SHORT).show()
                    } else {
                        val failText = if (msg.attachmentType == "IMAGE") {
                            if (appLanguage == "Русский") "Не удалось сохранить изображение" else "Failed to save image"
                        } else {
                            if (appLanguage == "Русский") "Не удалось сохранить файл" else "Failed to save file"
                        }
                        Toast.makeText(context, failText, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else if (msg != null) {
            Toast.makeText(context, if (appLanguage == "Русский") "Разрешение на запись отклонено" else "Storage permission denied", Toast.LENGTH_SHORT).show()
        }
        pendingDownloadMsg = null
    }
    val sharedPrefs = remember(context) { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    var pinnedMsgId by remember(peerName) { mutableStateOf(sharedPrefs.getString("pinned_msg_id_${peerName}", null)) }
    var pinnedMsgText by remember(peerName) { mutableStateOf(SecureStorage.decrypt(sharedPrefs.getString("pinned_msg_text_${peerName}", null))) }
    var pinnedMsgSender by remember(peerName) { mutableStateOf(sharedPrefs.getString("pinned_msg_sender_${peerName}", null)) }
    var pinnedBy by remember(peerName) { mutableStateOf(sharedPrefs.getString("pinned_by_${peerName}", null)) }
    var isMuted by remember(peerName) { mutableStateOf(sharedPrefs.getBoolean("mute_notifications_${peerName}", false)) }
    var isBlocked by remember(peerName) { mutableStateOf(sharedPrefs.getBoolean("blocked_peer_${peerName}", false)) }
    var isForwardingRestricted by remember(peerName) { mutableStateOf(sharedPrefs.getBoolean("restrict_forwarding_${peerName}", false)) }
    val username = remember { sharedPrefs.getString("username_profile", "User Identity") ?: "User Identity" }
    var activeFingerprint by remember(peerName) {
        mutableStateOf(sharedPrefs.getString(P2PPreferences.peerFingerprint(peerName), null).orEmpty())
    }
    var pendingFingerprint by remember(peerName) {
        mutableStateOf(sharedPrefs.getString(P2PPreferences.pendingPeerFingerprint(peerName), null).orEmpty())
    }
    var isIdentityPaused by remember(peerName) {
        mutableStateOf(P2PPreferences.isPeerIdentityChangePending(context, peerName))
    }
    var showIdentityWarning by remember(peerName) { mutableStateOf(isIdentityPaused) }
    var showIdentityConfirmation by remember(peerName) { mutableStateOf(false) }
    var identityDecisionInProgress by remember(peerName) { mutableStateOf(false) }
    var isVerified by remember(peerName) { mutableStateOf(P2PPreferences.isPeerVerified(context, peerName)) }
    DisposableEffect(sharedPrefs, peerName) {
        val verificationKey = P2PPreferences.verifiedPeer(peerName)
        val fingerprintKey = P2PPreferences.peerFingerprint(peerName)
        val mismatchKey = P2PPreferences.fingerprintMismatch(peerName)
        val pendingFingerprintKey = P2PPreferences.pendingPeerFingerprint(peerName)
        val forwardingKey = "restrict_forwarding_${peerName}"
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == verificationKey) {
                isVerified = prefs.getBoolean(verificationKey, false)
            } else if (key == fingerprintKey) {
                activeFingerprint = prefs.getString(fingerprintKey, null).orEmpty()
            } else if (key == mismatchKey || key == pendingFingerprintKey) {
                val wasPaused = isIdentityPaused
                pendingFingerprint = prefs.getString(pendingFingerprintKey, null).orEmpty()
                isIdentityPaused = P2PPreferences.isPeerIdentityChangePending(context, peerName)
                if (!wasPaused && isIdentityPaused) showIdentityWarning = true
                if (!isIdentityPaused) {
                    showIdentityWarning = false
                    showIdentityConfirmation = false
                }
            } else if (key == forwardingKey) {
                isForwardingRestricted = prefs.getBoolean(forwardingKey, false)
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
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
    var localFingerprint by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        localFingerprint = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            PythonBridge.getLocalFingerprint()
        }.takeUnless { it == "Error" || it == "Not Initialized" || it == "Loading..." }.orEmpty()
    }



    // Load only real persisted messages. Saved Messages keeps its local welcome entry.
    val db = remember(context) { ChatDatabaseHelper.getInstance(context) }
    val persistEnabled = remember(context) { sharedPrefs.getBoolean("persist_chat_history", true) }
    val chatViewModel: ChatScreenViewModel = viewModel(key = "chat:$peerName")
    val initialMessages = chatViewModel.messages
    var isHistoryLoading by chatViewModel.isHistoryLoading

    LaunchedEffect(peerName, isActive) {
        if (!isActive) return@LaunchedEffect
        // Navigation keeps the keyed ViewModel alive after leaving a chat. Refresh
        // every time the entry becomes active so messages received on MainScreen
        // are loaded from the database instead of leaving a stale in-memory list.
        isHistoryLoading = initialMessages.isEmpty()
        val currentSnapshot = initialMessages.toList()
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
        if (persistEnabled && list.isEmpty() && localDefaults.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                localDefaults.forEach { db.saveMessage(peerName, it) }
            }
        }
        val mergedMessages = mergeHistorySnapshot(
            persistedMessages = list,
            currentMessages = currentSnapshot,
            defaultMessages = localDefaults,
            persistHistory = persistEnabled,
        )
        initialMessages.clear()
        initialMessages.addAll(mergedMessages)
        isHistoryLoading = false
    }

    fun sendVoiceRecording(recording: VoiceRecording) {
        if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
            recording.file.delete()
            Toast.makeText(context, if (appLanguage == "Русский") "Отправка приостановлена до подтверждения ключа" else "Sending is paused until the key is confirmed", Toast.LENGTH_LONG).show()
            return
        }
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
            persistDatabase { db.saveMessage(peerName, outMsg) }
        }
        val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
        if (!activeSet.contains(peerName)) {
            sharedPrefs.edit { putStringSet("active_chats", activeSet.toMutableSet().apply { add(peerName) }) }
        }
        sharedPrefs.edit { putString("last_msg_$peerName", SecureStorage.encrypt("You: Voice message")) }

        if (endpoint != null && peerName != "Saved Messages") {
            P2PMessageRelay.sendFile(context, peerName, endpoint, recording.file.absolutePath, outMsg.id) { success ->
                if (!success) {
                    persistDatabase { db.updateMessageStatus(outMsg.id, "PENDING") }
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

    var inputText by chatViewModel.inputText
    var myTypingState by remember { mutableStateOf(false) }
    val isTyping = P2PMessageRelay.peerTypingStates[peerName] ?: false

    LaunchedEffect(peerName, isHistoryLoading) {
        if (isHistoryLoading) return@LaunchedEffect
        val endpoint = P2PMessageRelay.peerEndpoints[peerName]
        if (peerName != "Saved Messages") {
            if (endpoint != null) {
                P2PMessageRelay.shareAvatar(context, peerName, endpoint)
                P2PMessageRelay.processOfflineQueue(context, peerName, endpoint)
            }
            // Mark all existing incoming messages as READ in database and send read receipts
            var hasUnread = false
            initialMessages.forEach { msg ->
                if (!msg.isMe && msg.status?.startsWith("READ") != true) {
                    hasUnread = true
                    val idx = initialMessages.indexOfFirst { it.id == msg.id }
                    if (idx != -1) {
                        val current = initialMessages[idx]
                        val oldStatus = current.status ?: ""
                        val newStatus = MessageDeliveryStatus.merge(oldStatus, "READ")
                        initialMessages[idx] = msg.copy(status = newStatus)
                    }
                    P2PMessageRelay.sendReadReceipt(context, peerName, endpoint, msg.id)
                }
            }
            if (hasUnread) {
                withContext(Dispatchers.IO) {
                    db.markMessagesAsRead(peerName)
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
            override fun onMessageReceived(sender: String, message: Message) {
                if (sender == peerName) {
                    val endpoint = P2PMessageRelay.peerEndpoints[peerName]
                    val rxMsg = message.copy(status = MessageDeliveryStatus.merge(message.status, "READ"))
                    if (peerName != "Saved Messages") {
                        P2PMessageRelay.sendReadReceipt(context, peerName, endpoint, rxMsg.id)
                        coroutineScope.launch(Dispatchers.IO) {
                            db.updateMessageStatus(rxMsg.id, "READ")
                        }
                    }
                    val existingIndex = initialMessages.indexOfFirst { it.id == rxMsg.id }
                    if (existingIndex == -1) {
                        initialMessages.add(rxMsg)
                    } else {
                        initialMessages[existingIndex] = rxMsg
                    }
                }
            }

            override fun onMessageStatusChanged(sender: String, msgId: String, status: String) {
                if (sender == peerName) {
                    val idx = initialMessages.indexOfFirst { it.id == msgId }
                    if (idx != -1) {
                        val current = initialMessages[idx]
                        initialMessages[idx] = current.copy(
                            status = MessageDeliveryStatus.merge(current.status, status)
                        )
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
                        P2PPreferences.setPeerVerified(context, peerName, true)
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

            override fun onForwardingStateChanged(sender: String, enabled: Boolean) {
                if (sender == peerName) {
                    isForwardingRestricted = enabled
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
    var selectedMessageForOptions by chatViewModel.selectedMessageForOptions
    var replyingToMessage by chatViewModel.replyingToMessage
    var editingMessage by chatViewModel.editingMessage
    
    var isSelectMode by remember { mutableStateOf(false) }
    val selectedMessages = chatViewModel.selectedMessages
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
        if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
            Toast.makeText(context, if (appLanguage == "Русский") "Отправка приостановлена до подтверждения ключа" else "Sending is paused until the key is confirmed", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
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
                    persistDatabase { db.saveMessage(peerName, outMsg) }
                }
                if (endpoint != null && peerName != "Saved Messages") {
                    P2PMessageRelay.sendFile(context, peerName, endpoint, tempFile.absolutePath, outMsg.id) { success ->
                        if (!success) {
                            persistDatabase { db.updateMessageStatus(outMsg.id, "PENDING") }
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
        if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
            Toast.makeText(context, if (appLanguage == "Русский") "Отправка приостановлена до подтверждения ключа" else "Sending is paused until the key is confirmed", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
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
                    persistDatabase { db.saveMessage(peerName, outMsg) }
                }
                if (endpoint != null && peerName != "Saved Messages") {
                    P2PMessageRelay.sendFile(context, peerName, endpoint, tempFile.absolutePath, outMsg.id) { success ->
                        if (!success) {
                            persistDatabase { db.updateMessageStatus(outMsg.id, "PENDING") }
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
        if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
            tempCameraFile?.delete()
            Toast.makeText(context, if (appLanguage == "Русский") "Отправка приостановлена до подтверждения ключа" else "Sending is paused until the key is confirmed", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
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
                persistDatabase { db.saveMessage(peerName, outMsg) }
            }
            if (endpoint != null && peerName != "Saved Messages") {
                P2PMessageRelay.sendFile(context, peerName, endpoint, file.absolutePath, outMsg.id) { success ->
                    if (!success) {
                        persistDatabase { db.updateMessageStatus(outMsg.id, "PENDING") }
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
        if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
            Toast.makeText(context, if (appLanguage == "Русский") "Отправка приостановлена до подтверждения ключа" else "Sending is paused until the key is confirmed", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
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
                val detectedType = VoiceMessageSupport.attachmentType(fileName, "")
                val displayMsgText = if (detectedType == "IMAGE") "Sent an image" else if (detectedType == "VIDEO") "Sent a video" else fileName
                val outMsg = Message(
                    id = newMessageId(),
                    text = displayMsgText,
                    isMe = true,
                    timestamp = time,
                    attachmentType = detectedType,
                    attachmentUri = tempFile.absolutePath,
                    attachmentName = fileName,
                    status = initialStatus
                )
                initialMessages.add(outMsg)
                if (persistEnabled || initialStatus == "PENDING") {
                    persistDatabase { db.saveMessage(peerName, outMsg) }
                }
                if (endpoint != null && peerName != "Saved Messages") {
                    P2PMessageRelay.sendFile(context, peerName, endpoint, tempFile.absolutePath, outMsg.id) { success ->
                        if (!success) {
                            persistDatabase { db.updateMessageStatus(outMsg.id, "PENDING") }
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


    var isSearchMode by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

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
            ChatHeader(
                peerName = peerName,
                appLanguage = appLanguage,
                isSearchMode = isSearchMode,
                searchQuery = searchQuery,
                isVerified = isVerified,
                isMuted = isMuted,
                isForwardingRestricted = isForwardingRestricted,
                onToggleForwardingRestriction = { restricted ->
                    isForwardingRestricted = restricted
                    sharedPrefs.edit().putBoolean("restrict_forwarding_$peerName", restricted).apply()
                    P2PMessageRelay.sendForwardingState(context, peerName, restricted)
                },
                activeFingerprint = activeFingerprint,
                localFingerprint = localFingerprint,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onBack = onBack,
                onSearchModeChange = { isSearchMode = it },
                onSearchQueryChange = { searchQuery = it },
                onShowProfile = { showProfileOverlay = true },
                onVerify = { showVerifyDialog = true },
                onReconnect = {
                    P2PMessageRelay.reconnectSession(context, peerName) { success ->
                        val text = if (success) {
                            if (appLanguage == "Русский") "Переподключение запущено..." else "Reconnection initiated..."
                        } else {
                            if (appLanguage == "Русский") "Не удалось переподключить" else "Failed to reconnect"
                        }
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                    }
                },
                onToggleMuted = { muted ->
                    sharedPrefs.edit { putBoolean("mute_notifications_$peerName", muted) }
                    isMuted = muted
                    val text = if (muted) {
                        if (appLanguage == "Русский") "Уведомления отключены" else "Notifications muted"
                    } else {
                        if (appLanguage == "Русский") "Уведомления включены" else "Notifications unmuted"
                    }
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                },
                onClearHistory = {
                    persistDatabase { db.clearMessagesForPeer(peerName) }
                    initialMessages.clear()
                    sharedPrefs.edit { remove("last_msg_$peerName") }
                },
                onDeleteChat = {
                    P2PMessageRelay.deleteChat(context, peerName)
                    onBack()
                },
            )

            if (isIdentityPaused && peerName != "Saved Messages") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .border(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                        .clickable { showIdentityWarning = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("⚠", fontSize = 20.sp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (appLanguage == "Русский") "Ключ безопасности изменился" else "Security key changed",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        Text(
                            if (appLanguage == "Русский") "Соединение и отправка приостановлены" else "Connection and sending are paused",
                            color = onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        if (appLanguage == "Русский") "Подробнее" else "Review",
                        color = primaryColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
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

            ChatMessageList(
                modifier = Modifier.weight(1f),
                messages = initialMessages,
                selectedMessages = selectedMessages,
                isHistoryLoading = isHistoryLoading,
                isSearchMode = isSearchMode,
                searchQuery = searchQuery,
                isSelectMode = isSelectMode,
                isTyping = isTyping,
                peerName = peerName,
                appLanguage = appLanguage,
                screenInitTime = screenInitTime,
                showScrollDownButton = showScrollDownButton,
                listState = listState,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onReply = { replyingToMessage = it },
                onShowOptions = { selectedMessageForOptions = it },
                onOpenImages = { images, index ->
                    activeFullscreenBitmapOverrides = emptyMap()
                    activeFullscreenImages = images
                    activeFullscreenImageIndex = index
                },
                onOpenVideo = { activeFullscreenVideo = it },
            )

            ChatInputBar(
                showAttachments = showAttachments,
                replyingToMessage = replyingToMessage,
                editingMessage = editingMessage,
                isSelectMode = isSelectMode,
                selectedCount = selectedMessages.size,
                isBlocked = isBlocked,
                isIdentityPaused = isIdentityPaused,
                isRecordingVoice = isRecordingVoice,
                recordingElapsedMs = recordingElapsedMs,
                inputText = inputText,
                peerName = peerName,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                surfaceVariant = surfaceVariant,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onAttachmentClick = { type ->
                    showAttachments = false
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
                            }
                },
                onDismissReply = { replyingToMessage = null },
                onDismissEditing = {
                    editingMessage = null
                    inputText = ""
                },
                onCancelSelection = {
                    isSelectMode = false
                    selectedMessages.clear()
                },
                onDeleteSelected = {
                    selectedMessages.forEach { msg ->
                                    persistDatabase { db.deleteMessage(msg.id) }
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
                onUnblock = {
                    sharedPrefs.edit { putBoolean("blocked_peer_${peerName}", false) }
                    isBlocked = false
                },
                onReviewIdentity = { showIdentityWarning = true },
                onToggleAttachments = {
                    if (isRecordingVoice) {
                        voiceRecorder.cancel()
                        isRecordingVoice = false
                        recordingElapsedMs = 0
                    } else {
                        showAttachments = !showAttachments
                    }
                },
                onInputTextChange = { inputText = it },
                onActionClick = {
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
                                            persistDatabase { db.updateMessageText(currentEditing.id, userText) }
                                            val idx = initialMessages.indexOfFirst { it.id == currentEditing.id }
                                            if (idx != -1) {
                                                val oldStatus = currentEditing.status ?: ""
                                                val newStatus = if (oldStatus.contains("edited")) oldStatus else if (oldStatus.isEmpty()) "edited" else "${oldStatus}_edited"
                                                initialMessages[idx] = currentEditing.copy(text = userText, status = newStatus)
                                            }
                                            val endpoint = P2PMessageRelay.peerEndpoints[peerName]
                                            if (peerName != "Saved Messages") {
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
                                        persistDatabase { db.saveMessage(peerName, outMsg) }
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
                                                persistDatabase { db.updateMessageStatus(outMsg.id, "PENDING") }
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
            )
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

                        // Save Attachment (If attachmentUri != null, regardless of whether it is IMAGE, VIDEO, or FILE)
                        if (msg.attachmentUri != null) {
                            val isImage = msg.attachmentType == "IMAGE"
                            val isVideo = msg.attachmentType == "VIDEO"
                            val title = if (isImage) {
                                if (appLanguage == "Русский") "Скачать изображение" else "Save Image"
                            } else if (isVideo) {
                                if (appLanguage == "Русский") "Скачать видео" else "Save Video"
                            } else {
                                if (appLanguage == "Русский") "Скачать файл" else "Save File"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                                            ) == PackageManager.PERMISSION_GRANTED
                                        ) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val uri = if (isImage) {
                                                    saveImageToPublicGallery(context, msg.attachmentUri)
                                                } else {
                                                    saveFileToPublicDownloads(context, msg.attachmentUri, msg.attachmentName ?: "file")
                                                }
                                                withContext(Dispatchers.Main) {
                                                    if (uri != null) {
                                                        val successText = if (isImage) {
                                                            if (appLanguage == "Русский") "Изображение сохранено в Галерею" else "Image saved to Gallery"
                                                        } else {
                                                            if (appLanguage == "Русский") "Файл сохранен в Загрузки" else "File saved to Downloads"
                                                        }
                                                        Toast.makeText(context, successText, Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        val failText = if (isImage) {
                                                            if (appLanguage == "Русский") "Не удалось сохранить изображение" else "Failed to save image"
                                                        } else {
                                                            if (appLanguage == "Русский") "Не удалось сохранить файл" else "Failed to save file"
                                                        }
                                                        Toast.makeText(context, failText, Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        } else {
                                            pendingDownloadMsg = msg
                                            storageWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        }
                                        selectedMessageForOptions = null
                                    }
                                    .padding(vertical = 12.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_download),
                                    contentDescription = "Save Attachment",
                                    tint = onSurfaceColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = title,
                                    fontSize = 15.sp,
                                    color = onSurfaceColor
                                )
                            }
                        }

                        // Forward
                        if (!isForwardingRestricted) {
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
                        }

                        // Delete
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    persistDatabase { db.deleteMessage(msg.id) }
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
            var forwardSearchQuery by remember { mutableStateOf("") }
            val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
            val chatList = remember(activeSet, peerName) {
                activeSet.filter { it != peerName }.toList()
            }
            val filteredChats = remember(chatList, forwardSearchQuery) {
                chatList.filter { it.contains(forwardSearchQuery, ignoreCase = true) }
            }
            
            AlertDialog(
                onDismissRequest = { 
                    showForwardDialog = false
                    messageToForward = null
                },
                confirmButton = {},
                dismissButton = {},
                containerColor = surfaceColor,
                shape = RoundedCornerShape(24.dp),
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        // Header Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (appLanguage == "Русский") "Переслать сообщение" else "Forward Message",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = onSurfaceColor
                            )
                            IconButton(
                                onClick = {
                                    showForwardDialog = false
                                    messageToForward = null
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Search Bar
                        BasicTextField(
                            value = forwardSearchQuery,
                            onValueChange = { forwardSearchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = onSurfaceColor,
                                fontSize = 14.sp,
                                platformStyle = PlatformTextStyle(
                                    includeFontPadding = false
                                )
                            ),
                            cursorBrush = SolidColor(onSurfaceColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .background(surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .border(
                                    width = 0.5.dp, 
                                    color = onSurfaceColor.copy(alpha = 0.08f), 
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            decorationBox = { innerTextField ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (forwardSearchQuery.isEmpty()) {
                                            Text(
                                                text = if (appLanguage == "Русский") "Поиск получателя..." else "Search recipient...", 
                                                color = onSurfaceVariant.copy(alpha = 0.5f),
                                                fontSize = 14.sp,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(
                                                        includeFontPadding = false
                                                    )
                                                )
                                            )
                                        }
                                        innerTextField()
                                    }
                                    if (forwardSearchQuery.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconButton(
                                            onClick = { forwardSearchQuery = "" },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Clear",
                                                tint = onSurfaceVariant.copy(alpha = 0.5f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Body List
                        if (chatList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") "Нет других активных чатов" else "No other active chats", 
                                    color = onSurfaceVariant,
                                    fontSize = 14.sp
                                )
                            }
                        } else if (filteredChats.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (appLanguage == "Русский") "Ничего не найдено" else "No matches found", 
                                    color = onSurfaceVariant,
                                    fontSize = 14.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 300.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filteredChats) { chatName ->
                                    val initials = if (chatName == "Saved Messages") {
                                        "🔖"
                                    } else if (chatName.contains(" ")) {
                                        chatName.split(" ").map { it.take(1) }.joinToString("")
                                    } else {
                                        chatName.take(2).uppercase()
                                    }
                                    val avatarBitmap = P2PMessageRelay.peerAvatars[chatName]
                                    val endpoint = P2PMessageRelay.peerEndpoints[chatName]
                                    val isOnline = endpoint != null || chatName == "Saved Messages"
                                    
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable forwardClick@{
                                                if (P2PPreferences.isPeerIdentityChangePending(context, chatName)) {
                                                    Toast.makeText(
                                                        context,
                                                        if (appLanguage == "Русский") "В чате $chatName отправка приостановлена из-за смены ключа" else "Sending to $chatName is paused because its key changed",
                                                        Toast.LENGTH_LONG,
                                                    ).show()
                                                    return@forwardClick
                                                }
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
                                                
                                                if (persistEnabled || fwdInitialStatus == "PENDING") {
                                                    persistDatabase { db.saveMessage(chatName, fwdMsg) }
                                                }
                                                sharedPrefs.edit { putString("last_msg_$chatName", SecureStorage.encrypt("You: $textToForward")) }
                                                
                                                if (forwardEndpoint != null && chatName != "Saved Messages") {
                                                    if (messageToForward?.attachmentType != null && messageToForward?.attachmentUri != null) {
                                                        P2PMessageRelay.sendFile(context, chatName, forwardEndpoint, messageToForward!!.attachmentUri!!, fwdMsg.id) { success ->
                                                            if (!success) {
                                                                persistDatabase { db.updateMessageStatus(fwdMsg.id, "PENDING") }
                                                            }
                                                        }
                                                    } else {
                                                        P2PMessageRelay.sendMessage(context, forwardEndpoint, username, textToForward) { success ->
                                                            if (!success) {
                                                                persistDatabase { db.updateMessageStatus(fwdMsg.id, "PENDING") }
                                                            }
                                                        }
                                                    }
                                                }
                                                
                                                Toast.makeText(context, if (appLanguage == "Русский") "Переслано в $chatName" else "Forwarded to $chatName", Toast.LENGTH_SHORT).show()
                                                showForwardDialog = false
                                                messageToForward = null
                                            }
                                            .padding(all = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Avatar Circle
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(
                                                    brush = Brush.linearGradient(
                                                        colors = listOf(primaryColor.copy(alpha = 0.15f), primaryColor.copy(alpha = 0.05f))
                                                    ),
                                                    shape = CircleShape
                                                )
                                        ) {
                                            if (avatarBitmap != null) {
                                                Image(
                                                    bitmap = avatarBitmap.asImageBitmap(),
                                                    contentDescription = "Avatar",
                                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                                )
                                            } else if (chatName == "Saved Messages") {
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
                                        
                                        Spacer(modifier = Modifier.width(12.dp))
                                        
                                        // Info Column
                                        Column(
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = chatName,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 15.sp,
                                                color = onSurfaceColor
                                            )
                                            
                                            Spacer(modifier = Modifier.height(2.dp))
                                            
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .background(
                                                            color = if (isOnline) Color(0xFF4CAF50) else onSurfaceVariant.copy(alpha = 0.4f),
                                                            shape = CircleShape
                                                        )
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = when {
                                                        chatName == "Saved Messages" -> if (appLanguage == "Русский") "Личное хранилище" else "Personal storage"
                                                        isOnline -> if (appLanguage == "Русский") "В сети" else "Online"
                                                        else -> if (appLanguage == "Русский") "Был(а) недавно" else "Offline"
                                                    },
                                                    fontSize = 11.sp,
                                                    color = onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                        
                                        // Forward Icon Button
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .background(primaryColor.copy(alpha = 0.1f), CircleShape)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_forward),
                                                contentDescription = "Forward to $chatName",
                                                tint = primaryColor,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
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
                                    P2PPreferences.setPeerVerified(context, peerName, false)
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
                                P2PPreferences.setPeerVerified(context, peerName, true)
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

        if (showIdentityWarning && isIdentityPaused) {
            AlertDialog(
                onDismissRequest = { showIdentityWarning = false },
                title = {
                    Text(
                        if (appLanguage == "Русский") "Ключ безопасности изменился" else "Security key changed",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Text(
                        if (appLanguage == "Русский") {
                            "У $peerName появился новый ключ. Это может быть переустановка приложения, новый аккаунт с тем же именем или попытка перехвата. До вашего решения соединение, сообщения, файлы и служебные подтверждения заблокированы."
                        } else {
                            "$peerName presented a new key. This may be an app reinstall, a new account with the same name, or an interception attempt. Connection, messages, files, and delivery controls are blocked until you decide."
                        },
                        color = onSurfaceColor,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showIdentityWarning = false
                            showIdentityConfirmation = true
                        },
                        enabled = pendingFingerprint.isNotBlank(),
                    ) {
                        Text(if (appLanguage == "Русский") "Проверить новый ключ" else "Review new key")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showIdentityWarning = false }) {
                        Text(if (appLanguage == "Русский") "Оставить заблокированным" else "Keep blocked")
                    }
                },
                containerColor = surfaceColor,
                shape = RoundedCornerShape(20.dp),
            )
        }

        if (showIdentityConfirmation && isIdentityPaused) {
            AlertDialog(
                onDismissRequest = {
                    if (!identityDecisionInProgress) {
                        showIdentityConfirmation = false
                        showIdentityWarning = true
                    }
                },
                title = {
                    Text(
                        if (appLanguage == "Русский") "Подтвердить новый ключ?" else "Confirm the new key?",
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (appLanguage == "Русский") {
                                "Сверьте новый ключ с $peerName по другому доверенному каналу. После принятия прежняя верификация будет сброшена и создастся новая защищённая сессия."
                            } else {
                                "Compare the new key with $peerName over another trusted channel. Accepting it resets the previous verification and creates a new secure session."
                            },
                            color = onSurfaceColor,
                        )
                        Text(
                            (if (appLanguage == "Русский") "Прежний: " else "Previous: ") +
                                activeFingerprint.chunked(4).joinToString(" "),
                            color = onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Text(
                            (if (appLanguage == "Русский") "Новый: " else "New: ") +
                                pendingFingerprint.chunked(4).joinToString(" "),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (identityDecisionInProgress) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !identityDecisionInProgress && pendingFingerprint.isNotBlank(),
                        onClick = {
                            identityDecisionInProgress = true
                            P2PMessageRelay.acceptPendingPeerIdentity(context, peerName) { connected ->
                                identityDecisionInProgress = false
                                val accepted = !P2PPreferences.isPeerIdentityChangePending(context, peerName)
                                showIdentityConfirmation = !accepted
                                val message = if (!accepted) {
                                    if (appLanguage == "Русский") "Не удалось принять новый ключ" else "Could not accept the new key"
                                } else if (appLanguage == "Русский") {
                                    if (connected) "Новый ключ принят, создаётся новая сессия" else "Новый ключ принят; подключиться сейчас не удалось"
                                } else {
                                    if (connected) "New key accepted; creating a new session" else "New key accepted; could not reconnect now"
                                }
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        },
                    ) {
                        Text(if (appLanguage == "Русский") "Я сверил(а), принять" else "I verified it, accept")
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !identityDecisionInProgress,
                        onClick = {
                            identityDecisionInProgress = true
                            P2PMessageRelay.rejectPendingPeerIdentity(context, peerName) {
                                identityDecisionInProgress = false
                                showIdentityConfirmation = false
                                Toast.makeText(
                                    context,
                                    if (appLanguage == "Русский") "Новый ключ отклонён; сохранён прежний ключ" else "New key rejected; previous key remains pinned",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    ) {
                        Text(if (appLanguage == "Русский") "Отклонить новый ключ" else "Reject new key")
                    }
                },
                containerColor = surfaceColor,
                shape = RoundedCornerShape(20.dp),
            )
        }

        if (showConnectionErrorDialog && !isIdentityPaused) {
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
                onAvatarClick = { avatarBitmap ->
                    val avatarKey = "avatar:$peerName"
                    activeFullscreenBitmapOverrides = mapOf(avatarKey to avatarBitmap)
                    activeFullscreenImages = listOf(avatarKey)
                    activeFullscreenImageIndex = 0
                },
                onImageClick = { paths, index ->
                    if (paths.isNotEmpty()) {
                        activeFullscreenBitmapOverrides = emptyMap()
                        activeFullscreenImages = paths
                        activeFullscreenImageIndex = index
                    }
                },
                onBack = { showProfileOverlay = false }
            )
        }

        if (activeFullscreenImages.isNotEmpty()) {
            FullscreenImageViewer(
                imagePaths = activeFullscreenImages,
                initialIndex = activeFullscreenImageIndex,
                appLanguage = appLanguage,
                bitmapOverrides = activeFullscreenBitmapOverrides,
                onClose = {
                    activeFullscreenImages = emptyList()
                    activeFullscreenBitmapOverrides = emptyMap()
                }
            )
        }

        if (activeFullscreenVideo != null) {
            FullscreenVideoPlayer(
                videoPath = activeFullscreenVideo!!,
                appLanguage = appLanguage,
                onClose = { activeFullscreenVideo = null }
            )
        }
    }
}

fun saveFileToPublicDownloads(context: android.content.Context, filePath: String, originalName: String): Uri? {
    val srcFile = java.io.File(filePath)
    if (!srcFile.exists()) return null

    val fileName = originalName.ifBlank { srcFile.name }
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + java.io.File.separator + "2PChat")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val fileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (fileUri != null) {
                resolver.openOutputStream(fileUri).use { outputStream ->
                    if (outputStream != null) {
                        java.io.FileInputStream(srcFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(fileUri, contentValues, null, null)
                return fileUri
            }
        } else {
            val targetDir = java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "2PChat"
            )
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val destFile = java.io.File(targetDir, fileName)
            java.io.FileInputStream(srcFile).use { inputStream ->
                java.io.FileOutputStream(destFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            // Trigger MediaScanner
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(destFile)
            context.sendBroadcast(mediaScanIntent)
            return Uri.fromFile(destFile)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}
