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
import com.example.twopchat.BuiltinSticker
import com.example.twopchat.StickerSendRateLimiter
import com.example.twopchat.StickerSupport
import com.example.twopchat.GifStorageManager
import com.example.twopchat.StoredGif
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

internal fun shouldAutoScrollAfterAppend(
    previousItemCount: Int,
    lastVisibleItemIndex: Int,
    newestMessageIsMine: Boolean,
): Boolean {
    if (newestMessageIsMine || previousItemCount <= 0) return true
    val previousLastIndex = previousItemCount - 1
    return previousLastIndex - lastVisibleItemIndex <= 1
}

internal fun shouldCountIncomingMessage(
    previousItemCount: Int,
    lastVisibleItemIndex: Int,
): Boolean = lastVisibleItemIndex >= 0 && !shouldAutoScrollAfterAppend(
    previousItemCount = previousItemCount,
    lastVisibleItemIndex = lastVisibleItemIndex,
    newestMessageIsMine = false,
)

internal fun didAppendNewestMessage(
    previousMessageCount: Int,
    currentMessageCount: Int,
    previousNewestMessageId: String?,
    currentNewestMessageId: String?,
): Boolean = currentMessageCount > previousMessageCount &&
    currentNewestMessageId != null &&
    currentNewestMessageId != previousNewestMessageId

internal fun isMessageListAtBottom(
    totalItemCount: Int,
    lastVisibleItemIndex: Int,
): Boolean = totalItemCount <= 0 || lastVisibleItemIndex >= totalItemCount - 1

internal fun historyReplacementScrollIndex(
    messages: List<Message>,
    anchorMessageId: String?,
    wasAtBottom: Boolean,
    initialUnreadAnchorMessageId: String? = null,
): Int {
    if (messages.isEmpty()) return -1
    if (anchorMessageId == null && initialUnreadAnchorMessageId != null) {
        val unreadAnchorIndex = messages.indexOfFirst { it.id == initialUnreadAnchorMessageId }
        if (unreadAnchorIndex >= 0) return unreadAnchorIndex
    }
    if (wasAtBottom || anchorMessageId == null) return messages.lastIndex
    return messages.indexOfFirst { it.id == anchorMessageId }
}

internal fun initialChatScrollIndex(
    messageCount: Int,
    unreadMessageCount: Int,
): Int {
    if (messageCount <= 0) return -1
    if (unreadMessageCount <= 0) return messageCount - 1
    return (messageCount - unreadMessageCount).coerceIn(0, messageCount - 1)
}

internal fun shouldLoadOlderHistory(
    hasAppliedInitialScroll: Boolean,
    firstVisibleItemIndex: Int,
    hasMoreHistory: Boolean,
    isLoadingOlderHistory: Boolean,
    isSearchMode: Boolean,
    showProfileOverlay: Boolean,
): Boolean = hasAppliedInitialScroll &&
    firstVisibleItemIndex <= 2 &&
    hasMoreHistory &&
    !isLoadingOlderHistory &&
    !isSearchMode &&
    !showProfileOverlay

internal fun repairMisclassifiedLocalImage(message: Message): Message {
    if (message.attachmentType != "FILE") return message
    val path = message.attachmentUri?.takeIf { it.isNotBlank() && "://" !in it } ?: return message
    val file = File(path)
    if (!file.isFile) return message
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        message.copy(attachmentType = "IMAGE")
    } else {
        message
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
    val listState = rememberLazyListState()
    var isSearchMode by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchListView by rememberSaveable { mutableStateOf(false) }
    val arrivalAnimationTracker = remember(peerName) { MessageArrivalAnimationTracker() }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var hasAppliedInitialScroll by remember(peerName) { mutableStateOf(false) }
    var isFastHistoryLoaded by remember(peerName) { mutableStateOf(false) }
    var previousMessageCount by remember(peerName) { mutableIntStateOf(0) }
    var previousNewestMessageId by remember(peerName) { mutableStateOf<String?>(null) }
    var previousTypingState by remember(peerName) { mutableStateOf(false) }
    var newMessagesBelowCount by remember(peerName) { mutableIntStateOf(0) }
    val messageListAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            isMessageListAtBottom(
                totalItemCount = layoutInfo.totalItemsCount,
                lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
            )
        }
    }
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
    var viewedStickerMessage by remember { mutableStateOf<Message?>(null) }
    var stickerPackRequestInProgress by remember { mutableStateOf(false) }
    var stickerPackRequestError by remember { mutableStateOf(StickerPackRequestError.NONE) }
    var stickerPackPreviewRevision by remember { mutableStateOf(0) }
    var showGifLibrary by remember { mutableStateOf(false) }
    var gifLibraryLoading by remember { mutableStateOf(false) }
    var storedGifs by remember { mutableStateOf<List<StoredGif>>(emptyList()) }

    LaunchedEffect(showGifLibrary) {
        if (!showGifLibrary) return@LaunchedEffect
        gifLibraryLoading = true
        storedGifs = withContext(Dispatchers.IO) { GifStorageManager.list(context) }
        gifLibraryLoading = false
    }

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
    val sharedPrefs = remember(context) { com.example.twopchat.P2PPreferences.prefs(context) }
    var profilePhotoUri by remember {
        mutableStateOf(sharedPrefs.getString("profile_photo_uri", null))
    }
    val myAvatarBitmap by produceState<Bitmap?>(
        initialValue = null,
        context,
        profilePhotoUri,
    ) {
        value = withContext(Dispatchers.IO) {
            com.example.twopchat.ui.onboarding.loadBitmapFromUri(context, profilePhotoUri)
        }
    }
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun triggerHaptic(type: androidx.compose.ui.hapticfeedback.HapticFeedbackType = androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) {
        if (sharedPrefs.getBoolean("settings_haptic_feedback", true)) {
            try {
                hapticFeedback.performHapticFeedback(type)
            } catch (_: Exception) {}
        }
    }
    var pinnedMsgId by remember(peerName, isActive) { mutableStateOf(sharedPrefs.getString("pinned_msg_id_${peerName}", null)) }
    var pinnedMsgText by remember(peerName, isActive) { mutableStateOf(SecureStorage.decrypt(sharedPrefs.getString("pinned_msg_text_${peerName}", null))) }
    var pinnedMsgSender by remember(peerName, isActive) { mutableStateOf(sharedPrefs.getString("pinned_msg_sender_${peerName}", null)) }
    var pinnedBy by remember(peerName, isActive) { mutableStateOf(sharedPrefs.getString("pinned_by_${peerName}", null)) }
    var isMuted by remember(peerName) { mutableStateOf(sharedPrefs.getBoolean("mute_notifications_${peerName}", false)) }
    var isBlocked by remember(peerName) { mutableStateOf(sharedPrefs.getBoolean("blocked_peer_${peerName}", false)) }
    var isForwardingRestricted by remember(peerName) { mutableStateOf(sharedPrefs.getBoolean("restrict_forwarding_${peerName}", false)) }
    var forwardingNotificationPill by remember(peerName) { mutableStateOf<String?>(null) }
    
    // Auto-hide forwarding notification pill after 15s
    LaunchedEffect(forwardingNotificationPill) {
        if (forwardingNotificationPill != null) {
            kotlinx.coroutines.delay(15000)
            forwardingNotificationPill = null
        }
    }
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
            if (key == "profile_photo_uri") {
                profilePhotoUri = prefs.getString(key, null)
            } else if (key == verificationKey) {
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
    var selectedCategoryFilter by remember { mutableStateOf(SearchCategoryFilter.ALL) }
    var selectedDateFilterMs by remember { mutableStateOf<Long?>(null) }
    var showDatePickerDialog by remember { mutableStateOf(false) }

    val searchMatchedIndices by remember(initialMessages, searchQuery, selectedCategoryFilter, selectedDateFilterMs) {
        derivedStateOf {
            if (searchQuery.isBlank() && selectedCategoryFilter == SearchCategoryFilter.ALL && selectedDateFilterMs == null) {
                emptyList<Int>()
            } else {
                initialMessages.mapIndexedNotNull { index, msg ->
                    val matchesText = searchQuery.isBlank() ||
                        msg.text.contains(searchQuery, ignoreCase = true) ||
                        msg.attachmentName?.contains(searchQuery, ignoreCase = true) == true
                    val matchesCat = msg.matchesCategoryFilter(selectedCategoryFilter)
                    val matchesDate = msg.matchesDateFilter(selectedDateFilterMs)
                    if (matchesText && matchesCat && matchesDate) index else null
                }
            }
        }
    }
    var currentMatchPointer by remember(searchQuery, selectedCategoryFilter, selectedDateFilterMs) { mutableIntStateOf(0) }
    var isHistoryLoading by chatViewModel.isHistoryLoading
    var loadedPersistedMessageCount by chatViewModel.loadedPersistedMessageCount
    var hasMoreHistory by chatViewModel.hasMoreHistory
    var isLoadingOlderHistory by chatViewModel.isLoadingOlderHistory
    val unreadMessagesOnOpen = remember(peerName, isActive) {
        if (isActive) sharedPrefs.getInt("unread_count_$peerName", 0) else 0
    }

    LaunchedEffect(peerName, isActive) {
        if (!isActive) return@LaunchedEffect
        sharedPrefs.edit { putInt("unread_count_$peerName", 0) }
        initialMessages.indices.forEach { index ->
            val message = initialMessages[index]
            val attachmentPath = message.attachmentUri
            if (
                !attachmentPath.isNullOrBlank() &&
                "://" !in attachmentPath &&
                !File(attachmentPath).isFile
            ) {
                initialMessages[index] = message.copy(attachmentUri = null)
            }
        }
        com.example.twopchat.MessageNotificationService.clearHistory(context, peerName)
        hasAppliedInitialScroll = false
        isFastHistoryLoaded = false
        isLoadingOlderHistory = false
        newMessagesBelowCount = 0
        // Re-entry may reuse a cached ViewModel. Move a fully read chat to its
        // latest message immediately, but leave unread chats for the unread
        // anchor applied after the fast history snapshot is loaded.
        if (initialMessages.isNotEmpty() && unreadMessagesOnOpen <= 0) {
            listState.scrollToItem(initialMessages.lastIndex)
        }
        // Navigation keeps the keyed ViewModel alive after leaving a chat. Refresh
        // every time the entry becomes active so messages received on MainScreen
        // are loaded from the database instead of leaving a stale in-memory list.
        isHistoryLoading = initialMessages.isEmpty()
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

        // Reading and decrypting a large SQLCipher history can take noticeable
        // time. Fetch only the indexed recent unread rows first so every message
        // received while the chat was inactive appears immediately.
        val fastHistoryLimit = fastHistoryMessageLimit(unreadMessagesOnOpen)
        val recentPersistedMessages = if (persistEnabled) {
            withContext(Dispatchers.IO) {
                db.getMessagesForPeerPaged(
                    peerName = peerName,
                    limit = fastHistoryLimit,
                    offset = 0,
                ).map { message ->
                    repairMisclassifiedLocalImage(message).also { repaired ->
                        if (repaired !== message) db.saveMessage(peerName, repaired)
                    }
                }
            }
        } else {
            emptyList()
        }
        var fastSnapshot = mergeRecentHistoryMessages(
            currentMessages = initialMessages.toList(),
            recentPersistedMessages = recentPersistedMessages,
        )
        if (fastSnapshot.isEmpty()) fastSnapshot = localDefaults
        fastSnapshot = fastSnapshot.map { msg ->
            if (msg.id == "saved-messages-welcome") {
                msg.copy(text = Localizations.getString("saved_messages_welcome", appLanguage))
            } else {
                msg
            }
        }
        loadedPersistedMessageCount = recentPersistedMessages.size
        hasMoreHistory = persistEnabled && recentPersistedMessages.size >= fastHistoryLimit
        if (fastSnapshot != initialMessages.toList()) {
            initialMessages.clear()
            initialMessages.addAll(fastSnapshot)
        }
        if (persistEnabled && recentPersistedMessages.isEmpty() && localDefaults.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                localDefaults.forEach { db.saveMessage(peerName, it) }
            }
            loadedPersistedMessageCount = localDefaults.size
            hasMoreHistory = false
        }
        isFastHistoryLoaded = true
        isHistoryLoading = false
    }

    suspend fun loadOlderHistoryPage(preserveScrollPosition: Boolean = true): Boolean {
        if (!persistEnabled || !hasMoreHistory || isLoadingOlderHistory) return false
        isLoadingOlderHistory = true
        return try {
            val currentMessages = initialMessages.toList()
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val firstVisibleOffset = listState.firstVisibleItemScrollOffset
            val currentFirstVisibleMessageId = currentMessages.getOrNull(firstVisibleIndex)?.id
            val olderPage = withContext(Dispatchers.IO) {
                db.getMessagesForPeerPaged(
                    peerName = peerName,
                    limit = HISTORY_PAGE_SIZE,
                    offset = loadedPersistedMessageCount,
                ).map { message ->
                    repairMisclassifiedLocalImage(message).also { repaired ->
                        if (repaired !== message) db.saveMessage(peerName, repaired)
                    }
                }
            }
            loadedPersistedMessageCount += olderPage.size
            hasMoreHistory = olderPage.size >= HISTORY_PAGE_SIZE
            if (olderPage.isEmpty()) {
                false
            } else {
                val mergedMessages = mergeOlderHistoryPage(currentMessages, olderPage)
                val addedMessageCount = mergedMessages.size - currentMessages.size
                if (mergedMessages != currentMessages) {
                    initialMessages.clear()
                    initialMessages.addAll(mergedMessages)
                    if (preserveScrollPosition && addedMessageCount > 0) {
                        val targetIndex = if (currentFirstVisibleMessageId != null) {
                            val newIdx = mergedMessages.indexOfFirst { it.id == currentFirstVisibleMessageId }
                            if (newIdx >= 0) newIdx else (firstVisibleIndex + addedMessageCount)
                        } else {
                            firstVisibleIndex + addedMessageCount
                        }
                        listState.scrollToItem(
                            targetIndex.coerceIn(0, mergedMessages.lastIndex),
                            firstVisibleOffset,
                        )
                    }
                }
                true
            }
        } finally {
            isLoadingOlderHistory = false
        }
    }

    LaunchedEffect(
        peerName,
        isActive,
        persistEnabled,
        isFastHistoryLoaded,
        hasAppliedInitialScroll,
    ) {
        if (
            !isActive ||
            !persistEnabled ||
            !isFastHistoryLoaded ||
            !hasAppliedInitialScroll
        ) {
            return@LaunchedEffect
        }
        snapshotFlow {
            shouldLoadOlderHistory(
                hasAppliedInitialScroll = hasAppliedInitialScroll,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                hasMoreHistory = hasMoreHistory,
                isLoadingOlderHistory = isLoadingOlderHistory,
                isSearchMode = isSearchMode,
                showProfileOverlay = showProfileOverlay,
            )
        }.collect { shouldLoadOlder ->
            if (shouldLoadOlder) loadOlderHistoryPage()
        }
    }

    // Search and Shared Media historically cover the complete conversation.
    // Preserve that behavior, but defer the expensive full read until the user
    // explicitly opens one of those views instead of doing it on every chat open.
    LaunchedEffect(isSearchMode, showProfileOverlay, isFastHistoryLoaded) {
        if ((!isSearchMode && !showProfileOverlay) || !isFastHistoryLoaded) return@LaunchedEffect
        while (hasMoreHistory) {
            if (isLoadingOlderHistory) {
                delay(25)
            } else if (!loadOlderHistoryPage(preserveScrollPosition = false)) {
                break
            }
        }
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
        arrivalAnimationTracker.mark(outMsg.id)
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
                    }
                }
            }
        }
    }

    var inputText by chatViewModel.inputText
    val availableStickerPacks by produceState(
        initialValue = StickerSupport.builtinPacks,
        context,
    ) {
        value = withContext(Dispatchers.IO) {
            StickerSupport.availablePacks(context)
        }
    }
    val inlineSuggestedStickers = remember(inputText, availableStickerPacks) {
        val trimmed = inputText.trim()
        if (trimmed.isEmpty()) {
            emptyList()
        } else {
            val q = trimmed.lowercase()
            availableStickerPacks.flatMap { it.stickers }.filter { sticker ->
                sticker.emoji.isNotBlank() && (
                    q.contains(sticker.emoji) ||
                    sticker.emoji.contains(q) ||
                    (q.length >= 2 && sticker.stickerId.lowercase().contains(q))
                )
            }.distinctBy { "${it.packId}_${it.stickerId}" }
        }
    }
    var myTypingState by remember { mutableStateOf(false) }
    val isTyping = P2PMessageRelay.peerTypingStates[peerName] ?: false

    LaunchedEffect(peerName) {
        val savedDraft = sharedPrefs.getString(P2PPreferences.draftMessage(peerName), null)
        if (!savedDraft.isNullOrEmpty() && inputText.isEmpty()) {
            inputText = savedDraft
        }
    }

    LaunchedEffect(peerName, isActive) {
        if (!isActive) return@LaunchedEffect
        val endpoint = P2PMessageRelay.peerEndpoints[peerName]
        if (peerName != "Saved Messages") {
            if (endpoint != null) {
                P2PMessageRelay.shareAvatar(context, peerName, endpoint)
                P2PMessageRelay.processOfflineQueue(context, peerName, endpoint)
            }
        }
    }

    LaunchedEffect(inputText) {
        if (inputText.isNotEmpty()) {
            kotlinx.coroutines.delay(300)
        }
        val draftKey = P2PPreferences.draftMessage(peerName)
        val currentDraft = sharedPrefs.getString(draftKey, null)
        if (inputText.isNotEmpty()) {
            if (currentDraft != inputText) {
                sharedPrefs.edit().putString(draftKey, inputText).apply()
            }
        } else {
            if (currentDraft != null) {
                sharedPrefs.edit().remove(draftKey).apply()
            }
        }

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
                    val rxMsg = message
                    val existingIndex = initialMessages.indexOfFirst { it.id == rxMsg.id }
                    if (existingIndex == -1) {
                        val layoutInfo = listState.layoutInfo
                        val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        if (shouldCountIncomingMessage(layoutInfo.totalItemsCount, lastVisibleItemIndex)) {
                            newMessagesBelowCount += 1
                        }
                        sharedPrefs.edit {
                            putInt("unread_count_$peerName", 0)
                        }
                        arrivalAnimationTracker.mark(rxMsg.id)
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

            override fun onFileProgress(sender: String, msgId: String, bytesTransferred: Long, totalBytes: Long, speedKbps: Double) {
                if (sender == peerName || msgId.isNotEmpty()) {
                    val key = if (sender.isNotEmpty()) "$sender:$msgId" else msgId
                    P2PMessageRelay.fileProgressStates[key] = P2PMessageRelay.FileProgressInfo(
                        bytesTransferred = bytesTransferred,
                        totalBytes = totalBytes,
                        speedKbps = speedKbps
                    )
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
                    if (msgId == pinnedMsgId) {
                        sharedPrefs.edit {
                            putString("pinned_msg_text_${peerName}", SecureStorage.encrypt(text))
                        }
                        pinnedMsgText = text
                    }
                }
            }

            override fun onMessageDeleted(sender: String, msgId: String) {
                if (sender == peerName) {
                    val idx = initialMessages.indexOfFirst { it.id == msgId }
                    if (idx != -1) {
                        initialMessages.removeAt(idx)
                    }
                    if (msgId == pinnedMsgId) {
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
            }

            override fun onStickerPackInstalled(sender: String, packId: String) {
                if (sender != peerName) return
                stickerPackRequestInProgress = false
                stickerPackPreviewRevision += 1
                Toast.makeText(
                    context,
                    if (appLanguage == "Русский") {
                        "Стикерпак загружен для предпросмотра"
                    } else {
                        "Sticker pack ready to preview"
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }

            override fun onForwardingStateChanged(sender: String, enabled: Boolean) {
                if (sender == peerName) {
                    isForwardingRestricted = enabled
                    forwardingNotificationPill = if (enabled) {
                        Localizations.getString("peer_disabled_forwarding", appLanguage)
                    } else {
                        Localizations.getString("peer_enabled_forwarding", appLanguage)
                    }
                }
            }
        }
    }

    DisposableEffect(peerName, isActive) {
        if (isActive) {
            P2PMessageRelay.activeChatPeerName = peerName
            P2PMessageRelay.registerMessageListener(messageListener)
            sharedPrefs.edit { putInt("unread_count_$peerName", 0) }
        }
        onDispose {
            // Use atomic CAS to avoid clearing the name that was already set
            // by the next chat screen during a fast peer switch (BUG-03).
            P2PMessageRelay.clearActiveChatPeerName(peerName)
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
    var showStickerPicker by remember { mutableStateOf(false) }
    val stickerRateLimiter = remember(peerName) { StickerSendRateLimiter() }
    var selectedMessageForOptions by chatViewModel.selectedMessageForOptions
    var replyingToMessage by chatViewModel.replyingToMessage
    var editingMessage by chatViewModel.editingMessage
    
    var isSelectMode by remember { mutableStateOf(false) }
    val selectedMessages = chatViewModel.selectedMessages
    var showForwardDialog by remember { mutableStateOf(false) }
    var messageToForward by remember { mutableStateOf<Message?>(null) }

    fun sendSticker(sticker: BuiltinSticker) {
        showStickerPicker = false
        showAttachments = false
        if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
            Toast.makeText(
                context,
                if (appLanguage == "Русский") {
                    "Отправка приостановлена до подтверждения ключа"
                } else {
                    "Sending is paused until the key is confirmed"
                },
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (!stickerRateLimiter.tryAcquire()) {
            Toast.makeText(
                context,
                if (appLanguage == "Русский") {
                    "Не более 3 стикеров в секунду"
                } else {
                    "Up to 3 stickers per second"
                },
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        coroutineScope.launch {
            val stickerFile = withContext(Dispatchers.IO) {
                runCatching { StickerSupport.prepareSticker(context, sticker) }.getOrNull()
            }
            if (stickerFile == null) {
                Toast.makeText(
                    context,
                    if (appLanguage == "Русский") "Не удалось подготовить стикер" else "Could not prepare sticker",
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val endpoint = P2PMessageRelay.peerEndpoints[peerName]
            val initialStatus = if (endpoint != null || peerName == "Saved Messages") "SENT" else "PENDING"
            val outMsg = Message(
                id = newMessageId(),
                text = sticker.emoji,
                isMe = true,
                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                attachmentType = StickerSupport.ATTACHMENT_TYPE,
                attachmentUri = stickerFile.absolutePath,
                attachmentName = stickerFile.name,
                status = initialStatus,
            )
            arrivalAnimationTracker.mark(outMsg.id)
            initialMessages.add(outMsg)
            triggerHaptic()
            if (persistEnabled || initialStatus == "PENDING") {
                persistDatabase { db.saveMessage(peerName, outMsg) }
            }
            val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
            if (peerName !in activeSet) {
                sharedPrefs.edit {
                    putStringSet("active_chats", activeSet.toMutableSet().apply { add(peerName) })
                }
            }

            val lastText = if (appLanguage == "Русский") "Вы: Стикер" else "You: Sticker"
            sharedPrefs.edit { putString("last_msg_$peerName", SecureStorage.encrypt(lastText)) }

            if (endpoint != null && peerName != "Saved Messages") {
                P2PMessageRelay.sendFile(
                    context = context,
                    peerName = peerName,
                    endpoint = endpoint,
                    filePath = stickerFile.absolutePath,
                    messageId = outMsg.id,
                    caption = sticker.emoji,
                ) { success ->
                    if (!success) {
                        persistDatabase { db.updateMessageStatus(outMsg.id, "PENDING") }
                        coroutineScope.launch {
                            val index = initialMessages.indexOfFirst { it.id == outMsg.id }
                            if (index != -1) initialMessages[index] = outMsg.copy(status = "PENDING")
                        }
                    }
                }
            }
        }
    }

    fun sendGifFile(source: File) {
        showGifLibrary = false
        if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
            Toast.makeText(
                context,
                if (appLanguage == "Русский") {
                    "Отправка приостановлена до подтверждения ключа"
                } else {
                    "Sending is paused until the key is confirmed"
                },
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        coroutineScope.launch {
            val stored = withContext(Dispatchers.IO) {
                GifStorageManager.save(context, source).also {
                    val attachments = File(context.filesDir, "attachments").canonicalFile
                    if (source.canonicalFile.parentFile == attachments) source.delete()
                }
            }
            if (stored == null) {
                Toast.makeText(
                    context,
                    if (appLanguage == "Русский") "Некорректный GIF" else "Invalid GIF",
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            storedGifs = withContext(Dispatchers.IO) { GifStorageManager.list(context) }
            val file = File(stored.filePath)
            val endpoint = P2PMessageRelay.peerEndpoints[peerName]
            val initialStatus = if (endpoint != null || peerName == "Saved Messages") "SENT" else "PENDING"
            val outMsg = Message(
                id = newMessageId(),
                text = "GIF",
                isMe = true,
                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                attachmentType = GifStorageManager.ATTACHMENT_TYPE,
                attachmentUri = file.absolutePath,
                attachmentName = file.name,
                status = initialStatus,
            )
            arrivalAnimationTracker.mark(outMsg.id)
            initialMessages.add(outMsg)
            triggerHaptic()
            if (persistEnabled || initialStatus == "PENDING") {
                persistDatabase { db.saveMessage(peerName, outMsg) }
            }
            val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()).orEmpty()
            if (peerName !in activeSet) {
                sharedPrefs.edit {
                    putStringSet("active_chats", activeSet + peerName)
                }
            }
            sharedPrefs.edit {
                putString(
                    "last_msg_$peerName",
                    SecureStorage.encrypt(if (appLanguage == "Русский") "Вы: GIF" else "You: GIF"),
                )
            }
            if (endpoint != null && peerName != "Saved Messages") {
                P2PMessageRelay.sendFile(
                    context,
                    peerName,
                    endpoint,
                    file.absolutePath,
                    outMsg.id,
                ) { success ->
                    if (!success) {
                        persistDatabase { db.updateMessageStatus(outMsg.id, "PENDING") }
                        coroutineScope.launch {
                            val index = initialMessages.indexOfFirst { it.id == outMsg.id }
                            if (index != -1) initialMessages[index] = outMsg.copy(status = "PENDING")
                        }
                    }
                }
            }
        }
    }

    // Helper to copy Uri contents to a persistent file
    fun saveUriToTempFile(context: android.content.Context, uri: Uri, originalName: String): java.io.File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val attachmentsDir = java.io.File(context.filesDir, "attachments")
            if (!attachmentsDir.exists()) {
                attachmentsDir.mkdirs()
            }
            val file = java.io.File(attachmentsDir, "sent_file_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}_$originalName")
            // Use .use{} on both streams — guarantees close() even if an exception
            // is thrown during the copy, preventing file descriptor leaks (WARN-05).
            inputStream.use { input ->
                java.io.FileOutputStream(file).use { output ->
                    val buffer = ByteArray(4 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    var editingPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var editingPhotoPath by remember { mutableStateOf<String?>(null) }
    var editingVideoPath by remember { mutableStateOf<String?>(null) }
    var pendingAlbumFiles by remember { mutableStateOf<List<File>?>(null) }
    var pendingAlbumTypes by remember { mutableStateOf<List<String>?>(null) }
    var isProcessingAlbum by remember { mutableStateOf(false) }

    fun handleMultipleUrisSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return
        isProcessingAlbum = true
        coroutineScope.launch(Dispatchers.IO) {
            val tempFiles = mutableListOf<File>()
            val mediaTypes = mutableListOf<String>()
            try {
                for ((index, uri) in uris.withIndex()) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}

                    var fileName = "media_$index"
                    var mimeType = context.contentResolver.getType(uri).orEmpty()
                    try {
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1 && cursor.moveToFirst()) {
                                val queried = cursor.getString(nameIndex)
                                if (!queried.isNullOrBlank()) fileName = queried
                            }
                        }
                    } catch (_: Exception) {}

                    fileName = VoiceMessageSupport.ensureMediaExtension(fileName, mimeType)
                    val detectedType = VoiceMessageSupport.attachmentType(fileName, mimeType)

                    val tempFile = saveUriToTempFile(context, uri, fileName)
                    if (tempFile != null) {
                        tempFiles.add(tempFile)
                        mediaTypes.add(
                            when (detectedType) {
                                "VIDEO" -> "VIDEO"
                                GifStorageManager.ATTACHMENT_TYPE -> GifStorageManager.ATTACHMENT_TYPE
                                else -> "IMAGE"
                            },
                        )
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isProcessingAlbum = false
                    if (tempFiles.isNotEmpty()) {
                        pendingAlbumFiles = tempFiles
                        pendingAlbumTypes = mediaTypes
                    }
                }
            }
        }
    }

    fun processAndSendMediaAlbum(tempFiles: List<File>, mediaTypes: List<String>, customCaption: String = "") {
        if (tempFiles.isEmpty()) return

        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val endpoint = P2PMessageRelay.peerEndpoints[peerName]
        val initialStatus = if (endpoint != null) "SENT" else "PENDING"

        if (tempFiles.size == 1) {
            val file = tempFiles.first()
            val type = mediaTypes.firstOrNull() ?: "IMAGE"
            val defaultMsgText = when (type) {
                "VIDEO" -> if (appLanguage == "Русский") "Видеозапись" else "Sent a video"
                GifStorageManager.ATTACHMENT_TYPE -> "GIF"
                else -> if (appLanguage == "Русский") "Фотография" else "Sent an image"
            }
            val msgText = customCaption.ifBlank { defaultMsgText }
            val outMsg = Message(
                id = newMessageId(),
                text = msgText,
                isMe = true,
                timestamp = time,
                attachmentType = type,
                attachmentUri = file.absolutePath,
                attachmentName = file.name,
                status = initialStatus
            )
            arrivalAnimationTracker.mark(outMsg.id)
            initialMessages.add(outMsg)
            if (persistEnabled || initialStatus == "PENDING") {
                persistDatabase { db.saveMessage(peerName, outMsg) }
            }
            if (endpoint != null && peerName != "Saved Messages") {
                P2PMessageRelay.sendFile(context, peerName, endpoint, file.absolutePath, outMsg.id, customCaption) { success ->
                    if (!success) {
                        persistDatabase { db.updateMessageStatus(outMsg.id, "PENDING") }
                        coroutineScope.launch {
                            val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                            if (idx != -1) initialMessages[idx] = outMsg.copy(status = "PENDING")
                        }
                    }
                }
            }
        } else {
            val albumUris = tempFiles.map { it.absolutePath }
            val defaultTitle = if (appLanguage == "Русский") "Альбом (${tempFiles.size})" else "Sent an album (${tempFiles.size})"
            val albumText = customCaption.ifBlank { defaultTitle }
            val outMsg = Message(
                id = newMessageId(),
                text = albumText,
                isMe = true,
                timestamp = time,
                attachmentType = "ALBUM",
                attachmentUri = albumUris.first(),
                attachmentName = "Album",
                status = initialStatus,
                albumMediaUris = albumUris,
                albumMediaTypes = mediaTypes,
            )
            arrivalAnimationTracker.mark(outMsg.id)
            initialMessages.add(outMsg)
            if (persistEnabled || initialStatus == "PENDING") {
                persistDatabase { db.saveMessage(peerName, outMsg) }
            }
            if (endpoint != null && peerName != "Saved Messages") {
                coroutineScope.launch(Dispatchers.IO) {
                    for ((idx, file) in tempFiles.withIndex()) {
                        val fileCaption = if (idx == 0) customCaption else ""
                        val fileTransferId = "${outMsg.id}_$idx"
                        val latch = java.util.concurrent.CountDownLatch(1)
                        var transferOk = false
                        P2PMessageRelay.sendFile(
                            context = context,
                            peerName = peerName,
                            endpoint = endpoint,
                            filePath = file.absolutePath,
                            messageId = fileTransferId,
                            caption = fileCaption,
                            albumId = outMsg.id,
                            albumIndex = idx,
                            albumCount = tempFiles.size,
                        ) { success ->
                            transferOk = success
                            latch.countDown()
                        }
                        latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
                        if (!transferOk) {
                            persistDatabase { db.updateMessageStatus(outMsg.id, "PENDING") }
                            withContext(Dispatchers.Main) {
                                val messageIdx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                if (messageIdx != -1) initialMessages[messageIdx] = outMsg.copy(status = "PENDING")
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    // Picker Launchers with Multi-Select support
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
            Toast.makeText(context, if (appLanguage == "Русский") "Отправка приостановлена до подтверждения ключа" else "Sending is paused until the key is confirmed", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        if (uris.size == 1) {
            val uri = uris.first()
            val mime = context.contentResolver.getType(uri).orEmpty()
            var fileName = "image"
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(index).orEmpty().ifBlank { fileName }
                    }
                }
            }
            if (VoiceMessageSupport.attachmentType(fileName, mime) == GifStorageManager.ATTACHMENT_TYPE) {
                coroutineScope.launch(Dispatchers.IO) {
                    val file = saveUriToTempFile(
                        context,
                        uri,
                        fileName.takeIf { it.endsWith(".gif", true) } ?: "$fileName.gif",
                    )
                    if (file != null) {
                        withContext(Dispatchers.Main) { sendGifFile(file) }
                    }
                }
            } else {
                editingPhotoUri = uri
            }
        } else {
            handleMultipleUrisSelected(uris)
        }
    }

    val gifImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                var fileName = "imported_${System.currentTimeMillis()}.gif"
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index != -1 && cursor.moveToFirst()) {
                            fileName = cursor.getString(index).orEmpty().ifBlank { fileName }
                        }
                    }
                }
                if (!fileName.endsWith(".gif", ignoreCase = true)) fileName += ".gif"
                saveUriToTempFile(context, uri, fileName)?.let { temporary ->
                    GifStorageManager.save(context, temporary).also { temporary.delete() }
                }
            }
            if (result == null) {
                Toast.makeText(
                    context,
                    if (appLanguage == "Русский") "Не удалось добавить GIF" else "Could not add GIF",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                storedGifs = withContext(Dispatchers.IO) { GifStorageManager.list(context) }
                showGifLibrary = true
            }
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
            Toast.makeText(context, if (appLanguage == "Русский") "Отправка приостановлена до подтверждения ключа" else "Sending is paused until the key is confirmed", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        if (uris.size == 1) {
            val uri = uris.first()
            var fileName = "video.mp4"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val queried = cursor.getString(nameIndex)
                    if (!queried.isNullOrBlank()) {
                        fileName = if (!queried.contains(".")) "$queried.mp4" else queried
                    }
                }
            }
            val tempFile = saveUriToTempFile(context, uri, fileName)
            if (tempFile != null) {
                editingVideoPath = tempFile.absolutePath
            }
        } else {
            handleMultipleUrisSelected(uris)
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
        editingPhotoPath = file.absolutePath
    }

    if (editingPhotoUri != null || editingPhotoPath != null) {
        PhotoEditorModal(
            imageUri = editingPhotoUri,
            imagePath = editingPhotoPath,
            appLanguage = appLanguage,
            onDismiss = {
                editingPhotoUri = null
                editingPhotoPath = null
            },
            onSendPhoto = { editedFilePath, caption ->
                editingPhotoUri = null
                editingPhotoPath = null
                val file = File(editedFilePath)
                if (file.exists()) {
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val endpoint = P2PMessageRelay.peerEndpoints[peerName]
                    val initialStatus = if (endpoint != null) "SENT" else "PENDING"
                    val msgText = caption.ifBlank { if (appLanguage == "Русский") "Фотография" else "Sent an image" }
                    val outMsg = Message(
                        id = newMessageId(),
                        text = msgText,
                        isMe = true,
                        timestamp = time,
                        attachmentType = "IMAGE",
                        attachmentUri = file.absolutePath,
                        attachmentName = file.name,
                        status = initialStatus
                    )
                    arrivalAnimationTracker.mark(outMsg.id)
                    initialMessages.add(outMsg)
                    if (persistEnabled || initialStatus == "PENDING") {
                        persistDatabase { db.saveMessage(peerName, outMsg) }
                    }
                    if (endpoint != null && peerName != "Saved Messages") {
                        P2PMessageRelay.sendFile(context, peerName, endpoint, file.absolutePath, outMsg.id, caption.trim()) { success ->
                            if (!success) {
                                persistDatabase { db.updateMessageStatus(outMsg.id, "PENDING") }
                                coroutineScope.launch {
                                    val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                    if (idx != -1) {
                                        initialMessages[idx] = outMsg.copy(status = "PENDING")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    if (pendingAlbumFiles != null) {
        AlbumPreviewModal(
            files = pendingAlbumFiles!!,
            appLanguage = appLanguage,
            primaryColor = MaterialTheme.colorScheme.primary,
            surfaceColor = MaterialTheme.colorScheme.surface,
            onSurfaceColor = MaterialTheme.colorScheme.onSurface,
            onDismiss = {
                pendingAlbumFiles = null
                pendingAlbumTypes = null
            },
            onSendAlbum = { finalFiles, caption ->
                val types = pendingAlbumTypes ?: emptyList()
                pendingAlbumFiles = null
                pendingAlbumTypes = null
                processAndSendMediaAlbum(finalFiles, types, caption)
            }
        )
    }

    if (isProcessingAlbum) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = if (appLanguage == "Русский") "Подготовка медиафайлов..." else "Preparing media files...",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }


    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
            Toast.makeText(context, if (appLanguage == "Русский") "Отправка приостановлена до подтверждения ключа" else "Sending is paused until the key is confirmed", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        if (uris.size > 1 && uris.all { uri ->
            val type = context.contentResolver.getType(uri).orEmpty()
            type.startsWith("image/") || type.startsWith("video/")
        }) {
            handleMultipleUrisSelected(uris)
            return@rememberLauncherForActivityResult
        }
        for (uri in uris) {
            var fileName = "file"
            val mime = context.contentResolver.getType(uri).orEmpty()
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            fileName = VoiceMessageSupport.ensureMediaExtension(fileName, mime)
            val tempFile = saveUriToTempFile(context, uri, fileName)
            if (tempFile != null) {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val endpoint = P2PMessageRelay.peerEndpoints[peerName]
                val initialStatus = if (endpoint != null) "SENT" else "PENDING"
                // Some document providers return extensionless generated names
                // for photos and stickers. Preserve their MIME type instead of
                // rendering them forever as generic sent_file_* attachments.
                val detectedType = VoiceMessageSupport.attachmentType(fileName, mime)
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
                arrivalAnimationTracker.mark(outMsg.id)
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
                            }
                        }
                    }
                }
            }
        }
    }


    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    if (editingVideoPath != null) {
        VideoEditorModal(
            videoPath = editingVideoPath!!,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = onSurfaceVariant,
            onDismiss = {
                editingVideoPath = null
            },
            onSendVideo = { vPath, caption ->
                editingVideoPath = null
                val file = File(vPath)
                if (file.exists()) {
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val endpoint = P2PMessageRelay.peerEndpoints[peerName]
                    val initialStatus = if (endpoint != null) "SENT" else "PENDING"
                    val msgText = caption.ifBlank { if (appLanguage == "Русский") "Видеозапись" else "Sent a video" }
                    val outMsg = Message(
                        id = newMessageId(),
                        text = msgText,
                        isMe = true,
                        timestamp = time,
                        attachmentType = "VIDEO",
                        attachmentUri = file.absolutePath,
                        attachmentName = file.name,
                        status = initialStatus
                    )
                    arrivalAnimationTracker.mark(outMsg.id)
                    initialMessages.add(outMsg)
                    if (persistEnabled || initialStatus == "PENDING") {
                        persistDatabase { db.saveMessage(peerName, outMsg) }
                    }
                    if (endpoint != null && peerName != "Saved Messages") {
                        P2PMessageRelay.sendFile(context, peerName, endpoint, file.absolutePath, outMsg.id, caption.trim()) { success ->
                            if (!success) {
                                persistDatabase { db.updateMessageStatus(outMsg.id, "PENDING") }
                                coroutineScope.launch {
                                    val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                    if (idx != -1) {
                                        initialMessages[idx] = outMsg.copy(status = "PENDING")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    LaunchedEffect(messageListAtBottom) {
        if (messageListAtBottom) {
            newMessagesBelowCount = 0
        }
    }

    LaunchedEffect(peerName, isSearchMode, isFastHistoryLoaded, hasAppliedInitialScroll) {
        if (
            peerName == "Saved Messages" ||
            isSearchMode ||
            !isFastHistoryLoaded ||
            !hasAppliedInitialScroll
        ) {
            return@LaunchedEffect
        }

        snapshotFlow {
            val lastVisibleMessageIndex = listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
                ?.coerceAtMost(initialMessages.lastIndex)
                ?: -1
            if (lastVisibleMessageIndex < 0) {
                emptyList()
            } else {
                initialMessages
                    .take(lastVisibleMessageIndex + 1)
                    .filter { message ->
                        !message.isMe && message.status?.startsWith("READ") != true
                    }
                    .map { it.id }
            }
        }.collect { visibleUnreadIds ->
            if (visibleUnreadIds.isEmpty()) return@collect

            val visibleUnreadIdSet = visibleUnreadIds.toSet()
            initialMessages.indices.forEach { index ->
                val message = initialMessages[index]
                if (message.id in visibleUnreadIdSet && !message.isMe) {
                    initialMessages[index] = message.copy(
                        status = MessageDeliveryStatus.merge(message.status, "READ")
                    )
                }
            }

            val endpoint = P2PMessageRelay.peerEndpoints[peerName]
            visibleUnreadIds.forEach { messageId ->
                P2PMessageRelay.sendReadReceipt(context, peerName, endpoint, messageId)
            }
            withContext(Dispatchers.IO) {
                visibleUnreadIds.forEach { messageId ->
                    db.updateMessageStatus(messageId, "READ")
                }
            }

            val unreadKey = "unread_count_$peerName"
            sharedPrefs.edit {
                putInt(
                    unreadKey,
                    (sharedPrefs.getInt(unreadKey, 0) - visibleUnreadIds.size).coerceAtLeast(0),
                )
            }
        }
    }

    LaunchedEffect(initialMessages.size, isTyping, isSearchMode, isFastHistoryLoaded) {
        if (!isFastHistoryLoaded) return@LaunchedEffect
        val currentMessageCount = initialMessages.size
        val previousItemCount = previousMessageCount + if (previousTypingState) 1 else 0
        val currentItemCount = currentMessageCount + if (isTyping) 1 else 0
        val lastIndex = currentItemCount - 1
        val currentNewestMessageId = initialMessages.lastOrNull()?.id

        if (!isSearchMode && lastIndex >= 0) {
            if (!hasAppliedInitialScroll) {
                val initialIndex = initialChatScrollIndex(
                    messageCount = currentMessageCount,
                    unreadMessageCount = unreadMessagesOnOpen,
                )
                if (initialIndex >= 0) {
                    listState.scrollToItem(initialIndex)
                }
                hasAppliedInitialScroll = true
            } else if (
                didAppendNewestMessage(
                    previousMessageCount = previousMessageCount,
                    currentMessageCount = currentMessageCount,
                    previousNewestMessageId = previousNewestMessageId,
                    currentNewestMessageId = currentNewestMessageId,
                ) || (isTyping && !previousTypingState)
            ) {
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val newestMessageIsMine = currentNewestMessageId != previousNewestMessageId &&
                    initialMessages.lastOrNull()?.isMe == true
                if (shouldAutoScrollAfterAppend(previousItemCount, lastVisibleIndex, newestMessageIsMine)) {
                    listState.animateScrollToItem(lastIndex)
                }
            }
        }
        previousMessageCount = currentMessageCount
        previousNewestMessageId = currentNewestMessageId
        previousTypingState = isTyping
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
                    forwardingNotificationPill = if (restricted) {
                        Localizations.getString("you_disabled_forwarding", appLanguage)
                    } else {
                        Localizations.getString("you_enabled_forwarding", appLanguage)
                    }
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
                    Toast.makeText(
                        context,
                        if (appLanguage == "Русский") "Проверка и переподключение к $peerName..." else "Checking connection with $peerName...",
                        Toast.LENGTH_SHORT
                    ).show()
                    P2PMessageRelay.reconnectSession(context, peerName) { success ->
                        val isOnline = P2PMessageRelay.peerSessionStates[peerName] == true
                        val text = if (success || isOnline) {
                            if (appLanguage == "Русский") "Связь с $peerName восстановлена (Онлайн)" else "Connection restored with $peerName (Online)"
                        } else {
                            if (appLanguage == "Русский") "Собеседник $peerName недоступен (Офлайн)" else "Peer $peerName is unreachable (Offline)"
                        }
                        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
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
            AnimatedVisibility(
                visible = pinnedMsgId != null && pinnedMsgText != null,
                enter = expandVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(160)),
                exit = shrinkVertically(animationSpec = tween(180, easing = FastOutLinearInEasing)) + fadeOut(animationSpec = tween(140))
            ) {
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

            Box(modifier = Modifier.weight(1f)) {
                val hasSearchActive = isSearchMode && (searchQuery.isNotEmpty() || selectedCategoryFilter != SearchCategoryFilter.ALL || selectedDateFilterMs != null)
                if (hasSearchActive && isSearchListView) {
                    SearchResultsListViewOverlay(
                        messages = initialMessages,
                        matchedIndices = searchMatchedIndices,
                        peerName = peerName,
                        myAvatarBitmap = myAvatarBitmap,
                        appLanguage = appLanguage,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor,
                        onSurfaceVariant = onSurfaceVariant,
                        onSelectMatch = { matchIndex ->
                            isSearchListView = false
                            currentMatchPointer = matchIndex
                            val targetIdx = searchMatchedIndices[matchIndex]
                            coroutineScope.launch {
                                listState.animateScrollToItem(targetIdx)
                                highlightedMessageId = initialMessages[targetIdx].id
                            }
                        }
                    )
                } else {
                    ChatMessageList(
                        modifier = Modifier.fillMaxSize(),
                        messages = initialMessages,
                        selectedMessages = selectedMessages,
                        isHistoryLoading = isHistoryLoading,
                        isSearchMode = isSearchMode,
                        searchQuery = searchQuery,
                        isSelectMode = isSelectMode,
                        isTyping = isTyping,
                        peerName = peerName,
                        myAvatarBitmap = myAvatarBitmap,
                        appLanguage = appLanguage,
                        arrivalAnimationTracker = arrivalAnimationTracker,
                        showScrollDownButton = showScrollDownButton,
                        newMessagesBelowCount = newMessagesBelowCount,
                        onScrollToBottom = { newMessagesBelowCount = 0 },
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
                        onOpenStickerPack = {
                            stickerPackRequestInProgress = false
                            viewedStickerMessage = it
                        },
                        onCancelFileTransfer = { message ->
                            P2PMessageRelay.cancelFileTransfer(
                                context,
                                peerName,
                                message.id,
                            )
                        },
                        highlightedMessageId = highlightedMessageId,
                        onHighlightFinished = { highlightedMessageId = null },
                        onJumpToMessage = { targetMsg ->
                            isSearchMode = false
                            searchQuery = ""
                            val targetIndex = initialMessages.indexOfFirst { it.id == targetMsg.id }
                            if (targetIndex != -1) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(targetIndex)
                                    highlightedMessageId = targetMsg.id
                                }
                            }
                        },
                    )

                    if (hasSearchActive && !isSearchListView) {
                        SearchNavigationFabs(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 16.dp),
                            onNavigatePrev = {
                                if (searchMatchedIndices.isNotEmpty()) {
                                    currentMatchPointer = if (currentMatchPointer > 0) currentMatchPointer - 1 else searchMatchedIndices.lastIndex
                                    val targetIdx = searchMatchedIndices[currentMatchPointer]
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(targetIdx)
                                        highlightedMessageId = initialMessages[targetIdx].id
                                    }
                                }
                            },
                            onNavigateNext = {
                                if (searchMatchedIndices.isNotEmpty()) {
                                    currentMatchPointer = if (currentMatchPointer < searchMatchedIndices.lastIndex) currentMatchPointer + 1 else 0
                                    val targetIdx = searchMatchedIndices[currentMatchPointer]
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(targetIdx)
                                        highlightedMessageId = initialMessages[targetIdx].id
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Translucent Floating Notification Pill for Forwarding Toggles
            AnimatedVisibility(
                visible = forwardingNotificationPill != null,
                enter = fadeIn(animationSpec = tween(250)) + slideInVertically(animationSpec = tween(250)) { it / 2 },
                exit = fadeOut(animationSpec = tween(250)) + slideOutVertically(animationSpec = tween(250)) { it / 2 },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 6.dp)
            ) {
                if (forwardingNotificationPill != null) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        Text(
                            text = forwardingNotificationPill!!,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            val hasSearchActive = isSearchMode && (searchQuery.isNotEmpty() || selectedCategoryFilter != SearchCategoryFilter.ALL || selectedDateFilterMs != null)
            if (hasSearchActive) {
                SearchBottomBarPill(
                    matchCount = searchMatchedIndices.size,
                    currentIndex = currentMatchPointer,
                    isListView = isSearchListView,
                    selectedCategory = selectedCategoryFilter,
                    selectedDateMs = selectedDateFilterMs,
                    appLanguage = appLanguage,
                    primaryColor = primaryColor,
                    surfaceColor = surfaceColor,
                    onSurfaceColor = onSurfaceColor,
                    onToggleListView = { isSearchListView = !isSearchListView },
                    onSelectCategory = { selectedCategoryFilter = it },
                    onPickDate = { showDatePickerDialog = true },
                    onClearDate = { selectedDateFilterMs = null }
                )
            } else {
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
                suggestedStickers = inlineSuggestedStickers,
                onSelectSuggestedSticker = { sticker ->
                    sendSticker(sticker)
                    inputText = ""
                },
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
                                "GIF" -> showGifLibrary = true
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
                                    P2PMessageRelay.sendDeleteMessage(context, peerName, msg.id)
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
                        showStickerPicker = false
                        showAttachments = !showAttachments
                    }
                },
                onOpenStickerPicker = {
                    showAttachments = false
                    showStickerPicker = true
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
                                    val userText = inputText.trim().take(4096)
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
                                            if (currentEditing.id == pinnedMsgId) {
                                                sharedPrefs.edit {
                                                    putString(
                                                        "pinned_msg_text_${peerName}",
                                                        SecureStorage.encrypt(userText),
                                                    )
                                                }
                                                pinnedMsgText = userText
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
                                    arrivalAnimationTracker.mark(outMsg.id)
                                    initialMessages.add(outMsg)
                                    triggerHaptic()
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
                                                 }
                                             }
                                         }
                                     }
                                }
                            }
                },
            )
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
                            .verticalScroll(rememberScrollState())
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
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("👍", "❤️", "🔥", "😂", "😮", "😢", "👏", "💩", "🎉", "💯").forEach { emoji ->
                                val senders = msg.reactions[emoji] ?: emptyList()
                                val isSelected = senders.any { it.equals("Me", ignoreCase = true) }
                                val bgColor = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.12f)

                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = bgColor,
                                    border = if (isSelected) BorderStroke(1.5.dp, primaryColor) else null,
                                    modifier = Modifier
                                        .height(40.dp)
                                        .clickable {
                                            triggerHaptic()
                                            val idx = initialMessages.indexOfFirst { it.id == msg.id }
                                            if (idx != -1) {
                                                val current = initialMessages[idx]
                                                val updatedMap = current.reactions.toMutableMap()
                                                val currentSenders = (updatedMap[emoji] ?: emptyList()).toMutableList()
                                                val hasReacted = currentSenders.any { it.equals("Me", ignoreCase = true) }

                                                if (hasReacted) {
                                                    currentSenders.removeAll { it.equals("Me", ignoreCase = true) }
                                                    if (currentSenders.isEmpty()) {
                                                        updatedMap.remove(emoji)
                                                    } else {
                                                        updatedMap[emoji] = currentSenders
                                                    }
                                                } else {
                                                    currentSenders.add("Me")
                                                    updatedMap[emoji] = currentSenders
                                                }

                                                initialMessages[idx] = current.copy(reactions = updatedMap)
                                                db.updateMessageReactions(msg.id, updatedMap)
                                            }
                                            val endpoint = P2PMessageRelay.peerEndpoints[peerName]
                                            if (endpoint != null && peerName != "Saved Messages") {
                                                P2PMessageRelay.sendReaction(context, peerName, endpoint, msg.id, msg.text, emoji)
                                            }
                                            selectedMessageForOptions = null
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(text = emoji, fontSize = 20.sp)
                                        if (senders.isNotEmpty()) {
                                            Text(
                                                text = "${senders.size}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color.White else primaryColor
                                            )
                                        }
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

                        if (
                            msg.attachmentType == GifStorageManager.ATTACHMENT_TYPE &&
                            msg.attachmentUri != null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val source = File(msg.attachmentUri)
                                        selectedMessageForOptions = null
                                        coroutineScope.launch {
                                            val stored = withContext(Dispatchers.IO) {
                                                GifStorageManager.save(context, source)
                                            }
                                            Toast.makeText(
                                                context,
                                                if (stored != null) {
                                                    if (appLanguage == "Русский") {
                                                        "GIF сохранён в коллекцию"
                                                    } else {
                                                        "GIF saved to collection"
                                                    }
                                                } else {
                                                    if (appLanguage == "Русский") {
                                                        "Не удалось сохранить GIF"
                                                    } else {
                                                        "Could not save GIF"
                                                    }
                                                },
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                    .padding(vertical = 12.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_add_photo_smiley),
                                    contentDescription = "Save GIF",
                                    tint = onSurfaceColor,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    text = if (appLanguage == "Русский") {
                                        "Сохранить в Мои GIF"
                                    } else {
                                        "Save to My GIFs"
                                    },
                                    fontSize = 15.sp,
                                    color = onSurfaceColor,
                                )
                            }
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
                                    P2PMessageRelay.sendDeleteMessage(context, peerName, msg.id)
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

        // Custom Themed Date Picker Dialog matching active app primaryColor theme
        if (showDatePickerDialog) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = selectedDateFilterMs ?: System.currentTimeMillis()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePickerDialog = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis?.let { dateMs ->
                                val utcCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = dateMs }
                                val localCal = java.util.Calendar.getInstance().apply {
                                    set(java.util.Calendar.YEAR, utcCal.get(java.util.Calendar.YEAR))
                                    set(java.util.Calendar.MONTH, utcCal.get(java.util.Calendar.MONTH))
                                    set(java.util.Calendar.DAY_OF_MONTH, utcCal.get(java.util.Calendar.DAY_OF_MONTH))
                                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                                    set(java.util.Calendar.MINUTE, 0)
                                    set(java.util.Calendar.SECOND, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }
                                selectedDateFilterMs = localCal.timeInMillis
                            }
                            showDatePickerDialog = false
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = primaryColor)
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "ОК" else "OK",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDatePickerDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = primaryColor)
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "ОТМЕНА" else "CANCEL",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = DatePickerDefaults.colors(
                    containerColor = surfaceColor,
                )
            ) {
                DatePicker(
                    state = datePickerState,
                    colors = DatePickerDefaults.colors(
                        containerColor = surfaceColor,
                        titleContentColor = primaryColor,
                        headlineContentColor = primaryColor,
                        weekdayContentColor = onSurfaceColor.copy(alpha = 0.6f),
                        subheadContentColor = onSurfaceColor,
                        yearContentColor = onSurfaceColor,
                        selectedYearContainerColor = primaryColor,
                        selectedYearContentColor = if (primaryColor == com.example.twopchat.theme.MintGreen) com.example.twopchat.theme.StealthBlack else Color.White,
                        selectedDayContainerColor = primaryColor,
                        selectedDayContentColor = if (primaryColor == com.example.twopchat.theme.MintGreen) com.example.twopchat.theme.StealthBlack else Color.White,
                        todayDateBorderColor = primaryColor,
                        todayContentColor = primaryColor,
                        dayContentColor = onSurfaceColor,
                    )
                )
            }
        }

        // Forward Dialog
        if (showForwardDialog && messageToForward != null) {
            val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
            val groups = com.example.twopchat.group.runtime.GroupChatCoordinator.visibleGroups()
            
            val groupItems = groups.map { group ->
                com.example.twopchat.ui.common.RecipientItem(
                    id = "group_${group.groupId}",
                    title = group.title,
                    subtitle = if (appLanguage == "Русский") "Группа" else "Group",
                    isOnline = true,
                    isGroup = true,
                )
            }

            val peerItems = activeSet.filter { it != peerName }.map { name ->
                val avatar = P2PMessageRelay.peerAvatars[name]
                val isOnline = P2PMessageRelay.peerSessionStates[name] == true || name == "Saved Messages"
                val subtitle = when {
                    name == "Saved Messages" -> if (appLanguage == "Русский") "Личное хранилище" else "Personal storage"
                    isOnline -> if (appLanguage == "Русский") "В сети" else "Online"
                    else -> if (appLanguage == "Русский") "Был(а) недавно" else "Offline"
                }
                val initials = if (name == "Saved Messages") {
                    "🔖"
                } else if (name.contains(" ")) {
                    name.split(" ").map { it.take(1) }.joinToString("")
                } else {
                    name.take(2).uppercase()
                }
                com.example.twopchat.ui.common.RecipientItem(
                    id = "peer_$name",
                    title = name,
                    subtitle = subtitle,
                    isOnline = isOnline,
                    avatarBitmap = avatar,
                    initials = initials,
                    isGroup = false,
                )
            }

            com.example.twopchat.ui.common.RecipientPickerDialog(
                title = if (appLanguage == "Русский") "Переслать сообщение" else "Forward Message",
                searchPlaceholder = if (appLanguage == "Русский") "Поиск получателя..." else "Search recipient...",
                recipients = groupItems + peerItems,
                primaryColor = primaryColor,
                onDismiss = {
                    showForwardDialog = false
                    messageToForward = null
                },
                onRecipientSelected = { item ->
                    val currentMsg = messageToForward
                    if (item.isGroup) {
                        val targetGroupId = item.id.removePrefix("group_")
                        val textToForward = currentMsg?.text.orEmpty()
                        showForwardDialog = false
                        messageToForward = null
                        if (currentMsg?.attachmentUri != null && currentMsg?.attachmentName != null) {
                            com.example.twopchat.group.runtime.GroupChatCoordinator.sendAttachment(targetGroupId, currentMsg.attachmentName!!, "")
                        } else {
                            com.example.twopchat.group.runtime.GroupChatCoordinator.sendMessage(targetGroupId, textToForward, null)
                        }
                        Toast.makeText(context, if (appLanguage == "Русский") "Переслано в ${item.title}" else "Forwarded to ${item.title}", Toast.LENGTH_SHORT).show()
                    } else {
                        val chatName = item.id.removePrefix("peer_")
                        if (P2PPreferences.isPeerIdentityChangePending(context, chatName)) {
                            Toast.makeText(
                                context,
                                if (appLanguage == "Русский") "В чате $chatName отправка приостановлена из-за смены ключа" else "Sending to $chatName is paused because its key changed",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@RecipientPickerDialog
                        }
                        val textToForward = currentMsg?.text ?: ""
                        val forwardTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                        val forwardEndpoint = P2PMessageRelay.peerEndpoints[chatName]
                        val fwdInitialStatus = if (forwardEndpoint != null || chatName == "Saved Messages") "SENT" else "PENDING"
                        val fwdMsg = Message(
                            id = newMessageId(),
                            text = textToForward,
                            isMe = true,
                            timestamp = forwardTime,
                            attachmentType = currentMsg?.attachmentType,
                            attachmentUri = currentMsg?.attachmentUri,
                            attachmentName = currentMsg?.attachmentName,
                            status = fwdInitialStatus
                        )
                        
                        if (persistEnabled || fwdInitialStatus == "PENDING") {
                            persistDatabase { db.saveMessage(chatName, fwdMsg) }
                        }
                        sharedPrefs.edit { putString("last_msg_$chatName", SecureStorage.encrypt("You: $textToForward")) }
                        
                        if (forwardEndpoint != null && chatName != "Saved Messages") {
                            if (currentMsg?.attachmentType != null && currentMsg?.attachmentUri != null) {
                                P2PMessageRelay.sendFile(context, chatName, forwardEndpoint, currentMsg.attachmentUri!!, fwdMsg.id) { success ->
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
                }
            )
        }



        if (showVerifyDialog) {
            val emojis = remember(localFingerprint, activeFingerprint) {
                getVerificationEmojis(localFingerprint, activeFingerprint)
            }

            LaunchedEffect(isWaitingForVerifyResponse) {
                if (isWaitingForVerifyResponse) {
                    kotlinx.coroutines.delay(30000L)
                    if (isWaitingForVerifyResponse) {
                        isWaitingForVerifyResponse = false
                        Toast.makeText(
                            context,
                            if (appLanguage == "Русский") "Собеседник не ответил на запрос верификации" else "Peer did not respond to verification request",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            AlertDialog(
                onDismissRequest = { 
                    showVerifyDialog = false 
                    isWaitingForVerifyResponse = false
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        onClick = { 
                            showVerifyDialog = false 
                            isWaitingForVerifyResponse = false
                        }
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
                            val dangerRed = Color(0xFFE53935)
                            Button(
                                onClick = {
                                    isVerified = false
                                    P2PPreferences.setPeerVerified(context, peerName, false)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = dangerRed,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = Localizations.getString("unverify_btn", appLanguage),
                                    fontWeight = FontWeight.Bold
                                )
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
                                    OutlinedButton(
                                        onClick = {
                                            isWaitingForVerifyResponse = false
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (appLanguage == "Русский") "Отменить запрос" else "Cancel request",
                                            fontSize = 12.sp
                                        )
                                    }
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
                onVideoClick = { activeFullscreenVideo = it },
                onBack = { showProfileOverlay = false },
                onNavigateToMessage = { messageId ->
                    showProfileOverlay = false
                    val idx = initialMessages.indexOfFirst { it.id == messageId }
                    if (idx != -1) {
                        coroutineScope.launch {
                            listState.scrollToItem(idx)
                            highlightedMessageId = messageId
                        }
                    }
                }
            )
        }

        if (showStickerPicker) {
            StickerPickerBottomSheet(
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                onDismiss = { showStickerPicker = false },
                onStickerSelected = ::sendSticker,
            )
        }

        viewedStickerMessage?.let { stickerMessage ->
            val packId = StickerSupport.packIdFromStickerFileName(
                stickerMessage.attachmentName.orEmpty(),
            )
            if (packId != null) {
                LaunchedEffect(stickerPackRequestInProgress) {
                    if (stickerPackRequestInProgress) {
                        kotlinx.coroutines.delay(10_000L)
                        if (stickerPackRequestInProgress) {
                            stickerPackRequestInProgress = false
                            if (stickerPackRequestError == StickerPackRequestError.NONE) {
                                stickerPackRequestError = StickerPackRequestError.TIMEOUT
                            }
                        }
                    }
                }
                StickerPackBottomSheet(
                    packId = packId,
                    fallbackEmoji = stickerMessage.text,
                    canRequestFromPeer = !stickerMessage.isMe && peerName != "Saved Messages",
                    requestInProgress = stickerPackRequestInProgress,
                    previewRevision = stickerPackPreviewRevision,
                    appLanguage = appLanguage,
                    primaryColor = primaryColor,
                    requestError = stickerPackRequestError,
                    onDismiss = {
                        viewedStickerMessage = null
                        stickerPackRequestInProgress = false
                        stickerPackRequestError = StickerPackRequestError.NONE
                    },
                    onRequestPack = {
                        if (peerName !in P2PMessageRelay.peerEndpoints) {
                            stickerPackRequestError = StickerPackRequestError.PEER_OFFLINE
                            stickerPackRequestInProgress = false
                            return@StickerPackBottomSheet
                        }
                        stickerPackRequestError = StickerPackRequestError.NONE
                        stickerPackRequestInProgress = true
                        P2PMessageRelay.requestStickerPack(context, peerName, packId) { sent ->
                            if (!sent) {
                                stickerPackRequestInProgress = false
                                stickerPackRequestError = StickerPackRequestError.NETWORK_ERROR
                            }
                        }
                    },
                    onStickerSelected = ::sendSticker,
                )
            }
        }

        if (showGifLibrary) {
            GifLibraryBottomSheet(
                gifs = storedGifs,
                isLoading = gifLibraryLoading,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                onDismiss = { showGifLibrary = false },
                onImport = {
                    showGifLibrary = false
                    gifImportLauncher.launch("image/gif")
                },
                onGifSelected = { sendGifFile(File(it.filePath)) },
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
