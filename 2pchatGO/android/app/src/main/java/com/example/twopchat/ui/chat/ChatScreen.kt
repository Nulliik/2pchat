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
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.relay.resolvePeerEndpoint
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.tor.*
import com.example.twopchat.bridge.P2PBridgeProvider
import com.example.twopchat.copyTextToClipboard
import com.example.twopchat.security.*
import com.example.twopchat.R
import com.example.twopchat.media.*
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
import androidx.compose.ui.draw.blur
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
import com.example.twopchat.ui.chat.state.*
import com.example.twopchat.ui.chat.components.ChatFullscreenMediaViewer

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
    var showConnectionModeSheet by remember { mutableStateOf(false) }

    BackHandler {
        if (activeFullscreenImages.isNotEmpty()) {
            activeFullscreenImages = emptyList()
            activeFullscreenBitmapOverrides = emptyMap()
        } else if (activeFullscreenVideo != null) {
            activeFullscreenVideo = null
        } else if (showProfileOverlay) {
            showProfileOverlay = false
        } else if (showConnectionModeSheet) {
            showConnectionModeSheet = false
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
    val sharedPrefs = remember(context) { com.example.twopchat.config.P2PPreferences.prefs(context) }
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
    var activePinnedIndex by remember(peerName) { mutableIntStateOf(0) }
    var showPinnedSheet by remember { mutableStateOf(false) }
    var isMuted by remember(peerName) { mutableStateOf(com.example.twopchat.config.P2PPreferences.isPeerMuted(context, peerName)) }
    var isBlocked by remember(peerName) { mutableStateOf(com.example.twopchat.config.P2PPreferences.isPeerBlocked(context, peerName)) }
    var showHardBlockDialog by remember { mutableStateOf(false) }
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
    var recordingAmplitudes by remember { mutableStateOf<List<Float>>(emptyList()) }

    fun beginVoiceRecording() {
        recordingAmplitudes = emptyList()
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
        val currentAmps = mutableListOf<Float>()
        while (isRecordingVoice) {
            recordingElapsedMs = (android.os.SystemClock.elapsedRealtime() - recordingStartedAt).toInt()
            val amp = voiceRecorder.sampleAmplitude()
            currentAmps.add(amp)
            recordingAmplitudes = currentAmps.takeLast(24).toList()
            delay(50)
        }
    }

    DisposableEffect(voiceRecorder) {
        onDispose { voiceRecorder.cancel() }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                com.example.twopchat.yggdrasil.YggdrasilCoordinator.start(context)
                sharedPrefs.edit { putBoolean("settings_yggdrasil", true) }
                Toast.makeText(context, if (appLanguage == "Русский") "Yggdrasil успешно включен!" else "Yggdrasil enabled successfully!", Toast.LENGTH_SHORT).show()
            }
        }
    )
    var localFingerprint by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        localFingerprint = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            P2PBridgeProvider.get(context).getLocalFingerprint()
        }.takeUnless { it == "Error" || it == "Not Initialized" || it == "Loading..." }.orEmpty()
    }



    // Load only real persisted messages. Saved Messages keeps its local welcome entry.
    val db = remember(context) { ChatDatabaseHelper.getInstance(context) }
    val persistEnabled = remember(context) { sharedPrefs.getBoolean("persist_chat_history", true) }
    val chatViewModel: ChatScreenViewModel = viewModel(key = "chat:$peerName")
    val initialMessages = chatViewModel.messages
    val pinnedMessagesList = remember(initialMessages, pinnedMsgId, pinnedMsgText) {
        val dbPinned = initialMessages.filter { it.isPinned }
        val pId = pinnedMsgId
        val pText = pinnedMsgText
        if (dbPinned.isNotEmpty()) {
            dbPinned
        } else if (pId != null && pText != null) {
            val found = initialMessages.find { it.id == pId }
            if (found != null) listOf(found)
            else listOf(
                Message(
                    id = pId,
                    text = pText,
                    isMe = pinnedMsgSender == "You",
                    timestamp = "",
                    isPinned = true
                )
            )
        } else {
            emptyList()
        }
    }
    var selectedCategoryFilter by remember { mutableStateOf(SearchCategoryFilter.ALL) }
    var selectedDateFilterMs by remember { mutableStateOf<Long?>(null) }
    var showDatePickerDialog by remember { mutableStateOf(false) }

    var showWallpaperModal by remember { mutableStateOf(false) }

    val peerFp = remember(peerName) {
        sharedPrefs.getString("peer_fingerprint_$peerName", "") ?: ""
    }

    var prefsWallpaperVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(peerName, peerFp) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && (key.startsWith("direct_wallpaper_") || key.startsWith("peer_fingerprint_"))) {
                prefsWallpaperVersion++
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    var wallpaperPath by remember(peerName, prefsWallpaperVersion) {
        mutableStateOf(com.example.twopchat.config.P2PPreferences.getDirectWallpaperPath(context, peerName))
    }
    var wallpaperDimming by remember(peerName, prefsWallpaperVersion) {
        mutableIntStateOf(com.example.twopchat.config.P2PPreferences.getDirectWallpaperDimming(context, peerName))
    }
    var wallpaperBlur by remember(peerName, prefsWallpaperVersion) {
        mutableStateOf(com.example.twopchat.config.P2PPreferences.getDirectWallpaperBlur(context, peerName))
    }
    var wallpaperBitmap by remember(wallpaperPath, wallpaperBlur) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(wallpaperPath, peerName, prefsWallpaperVersion, wallpaperBlur) {
        wallpaperBitmap = withContext(Dispatchers.IO) {
            val resolvedPath = wallpaperPath ?: com.example.twopchat.config.P2PPreferences.getDirectWallpaperPath(context, peerName)
            resolvedPath?.let { path ->
                try {
                    val rawBmp = BitmapFactory.decodeFile(path)
                    if (rawBmp != null && wallpaperBlur) {
                        val blurred = com.example.twopchat.security.ImageSanitizer.fastBlur(rawBmp, 20)
                        if (blurred != rawBmp) rawBmp.recycle()
                        blurred
                    } else {
                        rawBmp
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

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
        if (!isActive) {
            isHistoryLoading = false
            return@LaunchedEffect
        }
        try {
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
            com.example.twopchat.relay.MessageNotificationService.clearHistory(context, peerName)
            hasAppliedInitialScroll = false
            isLoadingOlderHistory = false
            newMessagesBelowCount = 0
            if (initialMessages.isNotEmpty() && unreadMessagesOnOpen <= 0) {
                listState.scrollToItem(initialMessages.lastIndex)
            }
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

            val fastHistoryLimit = fastHistoryMessageLimit(unreadMessagesOnOpen)
            val recentPersistedMessages = if (persistEnabled) {
                withContext(Dispatchers.IO) {
                    try {
                        db.getMessagesForPeerPaged(
                            peerName = peerName,
                            limit = fastHistoryLimit,
                            offset = 0,
                        ).map { message ->
                            repairMisclassifiedLocalImage(message).also { repaired ->
                                if (repaired !== message) {
                                    persistDatabase {
                                        try {
                                            db.saveMessage(peerName, repaired)
                                        } catch (e: Exception) {
                                            android.util.Log.e("ChatScreen", "Failed to update repaired message", e)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatScreen", "Failed to load messages for $peerName", e)
                        emptyList()
                    }
                }
            } else {
                emptyList()
            }
            var fastSnapshot = if (recentPersistedMessages.isEmpty() && persistEnabled) {
                initialMessages.filter { it.status == "PENDING" }
            } else {
                mergeRecentHistoryMessages(
                    currentMessages = initialMessages.toList(),
                    recentPersistedMessages = recentPersistedMessages,
                )
            }
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
                    try {
                        db.saveMessages(peerName, localDefaults)
                    } catch (e: Exception) {
                        android.util.Log.e("ChatScreen", "Failed to persist local defaults", e)
                    }
                }
                loadedPersistedMessageCount = localDefaults.size
                hasMoreHistory = false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ChatScreen", "Unexpected error during chat history load for $peerName", e)
        } finally {
            isFastHistoryLoaded = true
            isHistoryLoading = false
        }
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
                        if (repaired !== message) {
                            persistDatabase { db.saveMessage(peerName, repaired) }
                        }
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
            ?: P2PPreferences.prefs(context).getString(P2PPreferences.lastEndpoint(peerName), null).orEmpty()
        val isLive = P2PMessageRelay.peerSessionStates[peerName] == true || endpoint.isNotBlank()
        val initialStatus = if (peerName == "Saved Messages") "SENT" else if (isLive) "SENDING" else "PENDING"
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
        if (persistEnabled || initialStatus == "PENDING" || initialStatus == "SENDING") {
            persistDatabase { db.saveMessage(peerName, outMsg) }
        }
        if (peerName != "Saved Messages") {
            val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
            if (!activeSet.contains(peerName)) {
                sharedPrefs.edit { putStringSet("active_chats", activeSet.toMutableSet().apply { add(peerName) }) }
            }
        }
        sharedPrefs.edit { putString("last_msg_$peerName", SecureStorage.encrypt("You: Voice message")) }

        if (peerName != "Saved Messages") {
            P2PMessageRelay.sendFile(context, peerName, endpoint, recording.file.absolutePath, outMsg.id) { success ->
                val finalStatus = if (success) "SENT" else "PENDING"
                persistDatabase { db.updateMessageStatus(outMsg.id, finalStatus) }
                coroutineScope.launch {
                    val index = initialMessages.indexOfFirst { it.id == outMsg.id }
                    if (index != -1) initialMessages[index] = outMsg.copy(status = finalStatus)
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
        if (peerName != "Saved Messages") {
            val isOnline = P2PMessageRelay.isPeerOnline(context, peerName)
            val resolvedEp = resolvePeerEndpoint(
                peerName = peerName,
                liveEndpoint = P2PMessageRelay.peerEndpoints[peerName],
                persistedEndpoint = sharedPrefs.getString(P2PPreferences.lastEndpoint(peerName), null),
                onionEndpoint = P2PPreferences.getPeerOnionAddress(context, peerName),
            ).orEmpty()
            if (!isOnline && resolvedEp.isNotBlank()) {
                P2PMessageRelay.reconnectSession(context, peerName)
            } else {
                P2PMessageRelay.sendConnectedPeerHeartbeat(context, peerName)
            }
            if (resolvedEp.isNotBlank()) {
                P2PMessageRelay.shareAvatar(context, peerName, resolvedEp)
                P2PMessageRelay.processOfflineQueue(context, peerName, resolvedEp)
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
        val endpoint = P2PMessageRelay.peerEndpoints[peerName] ?: ""
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
                    val state = if (totalBytes > 0L && bytesTransferred >= totalBytes) {
                        P2PMessageRelay.FileTransferState.COMPLETED
                    } else {
                        P2PMessageRelay.FileTransferState.TRANSFERRING
                    }
                    P2PMessageRelay.fileProgressStates[key] = P2PMessageRelay.FileProgressInfo(
                        bytesTransferred = bytesTransferred,
                        totalBytes = totalBytes,
                        speedKbps = speedKbps,
                        state = state,
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
            com.example.twopchat.relay.MessageNotificationService.cancelNotificationForPeer(context, peerName)
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
                ?: P2PPreferences.prefs(context).getString(P2PPreferences.lastEndpoint(peerName), null).orEmpty()
            val isLive = P2PMessageRelay.peerSessionStates[peerName] == true || endpoint.isNotBlank()
            val initialStatus = if (peerName == "Saved Messages") "SENT" else if (isLive) "SENDING" else "PENDING"
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
            if (persistEnabled || initialStatus == "PENDING" || initialStatus == "SENDING") {
                persistDatabase { db.saveMessage(peerName, outMsg) }
            }
            if (peerName != "Saved Messages") {
                val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                if (peerName !in activeSet) {
                    sharedPrefs.edit {
                        putStringSet("active_chats", activeSet.toMutableSet().apply { add(peerName) })
                    }
                }
            }

            val lastText = if (appLanguage == "Русский") "Вы: Стикер" else "You: Sticker"
            sharedPrefs.edit { putString("last_msg_$peerName", SecureStorage.encrypt(lastText)) }

            if (peerName != "Saved Messages") {
                P2PMessageRelay.sendFile(
                    context = context,
                    peerName = peerName,
                    endpoint = endpoint,
                    filePath = stickerFile.absolutePath,
                    messageId = outMsg.id,
                    caption = sticker.emoji,
                ) { success ->
                    val finalStatus = if (success) "SENT" else "PENDING"
                    persistDatabase { db.updateMessageStatus(outMsg.id, finalStatus) }
                    coroutineScope.launch {
                        val index = initialMessages.indexOfFirst { it.id == outMsg.id }
                        if (index != -1) initialMessages[index] = outMsg.copy(status = finalStatus)
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
                ?: P2PPreferences.prefs(context).getString(P2PPreferences.lastEndpoint(peerName), null).orEmpty()
            val isLive = P2PMessageRelay.peerSessionStates[peerName] == true || endpoint.isNotBlank()
            val initialStatus = if (peerName == "Saved Messages") "SENT" else if (isLive) "SENDING" else "PENDING"
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
            if (persistEnabled || initialStatus == "PENDING" || initialStatus == "SENDING") {
                persistDatabase { db.saveMessage(peerName, outMsg) }
            }
            if (peerName != "Saved Messages") {
                val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()).orEmpty()
                if (peerName !in activeSet) {
                    sharedPrefs.edit {
                        putStringSet("active_chats", activeSet + peerName)
                    }
                }
            }
            sharedPrefs.edit {
                putString(
                    "last_msg_$peerName",
                    SecureStorage.encrypt(if (appLanguage == "Русский") "Вы: GIF" else "You: GIF"),
                )
            }
            if (peerName != "Saved Messages") {
                P2PMessageRelay.sendFile(
                    context,
                    peerName,
                    endpoint,
                    file.absolutePath,
                    outMsg.id,
                ) { success ->
                    val finalStatus = if (success) "SENT" else "PENDING"
                    persistDatabase { db.updateMessageStatus(outMsg.id, finalStatus) }
                    coroutineScope.launch {
                        val index = initialMessages.indexOfFirst { it.id == outMsg.id }
                        if (index != -1) initialMessages[index] = outMsg.copy(status = finalStatus)
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
            ?: P2PPreferences.prefs(context).getString(P2PPreferences.lastEndpoint(peerName), null).orEmpty()
        val isLive = P2PMessageRelay.peerSessionStates[peerName] == true || endpoint.isNotBlank()
        val initialStatus = if (peerName == "Saved Messages") "SENT" else if (isLive) "SENDING" else "PENDING"

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
            if (persistEnabled || initialStatus == "PENDING" || initialStatus == "SENDING") {
                persistDatabase { db.saveMessage(peerName, outMsg) }
            }
            if (peerName != "Saved Messages") {
                P2PMessageRelay.sendFile(context, peerName, endpoint, file.absolutePath, outMsg.id, customCaption) { success ->
                    val finalStatus = if (success) "SENT" else "PENDING"
                    persistDatabase { db.updateMessageStatus(outMsg.id, finalStatus) }
                    coroutineScope.launch {
                        val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                        if (idx != -1) initialMessages[idx] = outMsg.copy(status = finalStatus)
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
            if (persistEnabled || initialStatus == "PENDING" || initialStatus == "SENDING") {
                persistDatabase { db.saveMessage(peerName, outMsg) }
            }
            if (peerName != "Saved Messages") {
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
                        ?: P2PPreferences.prefs(context).getString(P2PPreferences.lastEndpoint(peerName), null).orEmpty()
                    val isLive = P2PMessageRelay.peerSessionStates[peerName] == true || endpoint.isNotBlank()
                    val initialStatus = if (peerName == "Saved Messages") "SENT" else if (isLive) "SENDING" else "PENDING"
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
                    if (persistEnabled || initialStatus == "PENDING" || initialStatus == "SENDING") {
                        persistDatabase { db.saveMessage(peerName, outMsg) }
                    }
                    if (peerName != "Saved Messages") {
                        P2PMessageRelay.sendFile(context, peerName, endpoint, file.absolutePath, outMsg.id, caption.trim()) { success ->
                            val finalStatus = if (success) "SENT" else "PENDING"
                            persistDatabase { db.updateMessageStatus(outMsg.id, finalStatus) }
                            coroutineScope.launch {
                                val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                if (idx != -1) {
                                    initialMessages[idx] = outMsg.copy(status = finalStatus)
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    val albumFiles = pendingAlbumFiles
    if (albumFiles != null) {
        AlbumPreviewModal(
            files = albumFiles,
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
        ChatProcessingAlbumDialog(appLanguage = appLanguage)
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
                    ?: P2PPreferences.prefs(context).getString(P2PPreferences.lastEndpoint(peerName), null).orEmpty()
                val isLive = P2PMessageRelay.peerSessionStates[peerName] == true || endpoint.isNotBlank()
                val initialStatus = if (peerName == "Saved Messages") "SENT" else if (isLive) "SENDING" else "PENDING"
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
                if (persistEnabled || initialStatus == "PENDING" || initialStatus == "SENDING") {
                    persistDatabase { db.saveMessage(peerName, outMsg) }
                }
                if (peerName != "Saved Messages") {
                    P2PMessageRelay.sendFile(
                        context = context,
                        peerName = peerName,
                        endpoint = endpoint,
                        filePath = tempFile.absolutePath,
                        messageId = outMsg.id,
                        asDocument = true,
                    ) { success ->
                        val finalStatus = if (success) "SENT" else "PENDING"
                        persistDatabase { db.updateMessageStatus(outMsg.id, finalStatus) }
                        coroutineScope.launch {
                            val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                            if (idx != -1) {
                                initialMessages[idx] = outMsg.copy(status = finalStatus)
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

    val videoToEdit = editingVideoPath
    if (videoToEdit != null) {
        VideoEditorModal(
            videoPath = videoToEdit,
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
                        ?: P2PPreferences.prefs(context).getString(P2PPreferences.lastEndpoint(peerName), null).orEmpty()
                    val isLive = P2PMessageRelay.peerSessionStates[peerName] == true || endpoint.isNotBlank()
                    val initialStatus = if (peerName == "Saved Messages") "SENT" else if (isLive) "SENDING" else "PENDING"
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
                    if (persistEnabled || initialStatus == "PENDING" || initialStatus == "SENDING") {
                        persistDatabase { db.saveMessage(peerName, outMsg) }
                    }
                    if (peerName != "Saved Messages") {
                        P2PMessageRelay.sendFile(context, peerName, endpoint, file.absolutePath, outMsg.id, caption.trim()) { success ->
                            val finalStatus = if (success) "SENT" else "PENDING"
                            persistDatabase { db.updateMessageStatus(outMsg.id, finalStatus) }
                            coroutineScope.launch {
                                val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                if (idx != -1) {
                                    initialMessages[idx] = outMsg.copy(status = finalStatus)
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
                db.batchUpdateMessageStatuses(visibleUnreadIds.associateWith { "READ" })
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
        modifier = modifier.fillMaxSize()
    ) {
        val wpBitmap = wallpaperBitmap
        if (wpBitmap != null) {
            Image(
                bitmap = wpBitmap.asImageBitmap(),
                contentDescription = "Wallpaper",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (wallpaperBlur) Modifier.blur(12.dp) else Modifier)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = wallpaperDimming / 100f))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            ChatHeader(
                peerName = peerName,
                appLanguage = appLanguage,
                isSearchMode = isSearchMode,
                searchQuery = searchQuery,
                isVerified = isVerified,
                isMuted = isMuted,
                isForwardingRestricted = isForwardingRestricted,
                onSetWallpaper = { showWallpaperModal = true },
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
                onOpenConnectionMode = { showConnectionModeSheet = true },
                onVerify = { showVerifyDialog = true },
                onBlockPeer = { showHardBlockDialog = true },
                onReconnect = {
                    Toast.makeText(
                        context,
                        if (appLanguage == "Русский") "Попытка подключения к $peerName..." else "Attempting to connect to $peerName...",
                        Toast.LENGTH_SHORT
                    ).show()
                    P2PMessageRelay.reconnectSession(context, peerName) { initiated ->
                        if (!initiated) {
                            val text = if (appLanguage == "Русский") "Нет доступных адресов для $peerName (Офлайн)" else "No available endpoints for $peerName (Offline)"
                            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                        }
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
                    val fp = sharedPrefs.getString("peer_fingerprint_$peerName", null)
                    val aliases = listOfNotNull(fp).filter { it.isNotBlank() }
                    persistDatabase { db.clearMessagesForPeer(peerName, aliases) }
                    initialMessages.clear()
                    chatViewModel.loadedPersistedMessageCount.intValue = 0
                    chatViewModel.hasMoreHistory.value = false
                    sharedPrefs.edit {
                        remove("last_msg_$peerName")
                        remove("unread_count_$peerName")
                        remove(P2PPreferences.pinnedMessageId(peerName))
                        remove(P2PPreferences.pinnedMessageText(peerName))
                        remove(P2PPreferences.pinnedMessageSender(peerName))
                        remove(P2PPreferences.pinnedBy(peerName))
                        aliases.forEach { alias ->
                            remove("last_msg_$alias")
                            remove("unread_count_$alias")
                            remove(P2PPreferences.pinnedMessageId(alias))
                            remove(P2PPreferences.pinnedMessageText(alias))
                            remove(P2PPreferences.pinnedMessageSender(alias))
                            remove(P2PPreferences.pinnedBy(alias))
                        }
                    }
                    com.example.twopchat.relay.MessageNotificationService.clearHistory(context, peerName)
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

            val currentPinnedMsg = if (pinnedMessagesList.isNotEmpty()) {
                pinnedMessagesList[activePinnedIndex % pinnedMessagesList.size]
            } else null

            val currentPinnedPreview = when {
                currentPinnedMsg == null -> ""
                currentPinnedMsg.text.isNotBlank() -> currentPinnedMsg.text
                currentPinnedMsg.attachmentType == "IMAGE" -> if (appLanguage == "Русский") "📷 Фотография" else "📷 Photo"
                currentPinnedMsg.attachmentType == "VIDEO" -> if (appLanguage == "Русский") "🎥 Видеозапись" else "🎥 Video"
                currentPinnedMsg.attachmentType == "VOICE" -> if (appLanguage == "Русский") "🎤 Голосовое сообщение" else "🎤 Voice Message"
                currentPinnedMsg.attachmentType == "FILE" -> "📁 ${currentPinnedMsg.attachmentName ?: "File"}"
                else -> if (appLanguage == "Русский") "Вложение" else "Attachment"
            }

            val currentPinnedTitle = if (currentPinnedMsg != null) {
                if (currentPinnedMsg.isMe) {
                    if (appLanguage == "Русский") "Вы закрепили сообщение" else "You pinned a message"
                } else {
                    if (appLanguage == "Русский") "$peerName закрепил(а) сообщение" else "$peerName pinned a message"
                }
            } else ""

            ConversationPinnedMessageBar(
                visible = currentPinnedMsg != null,
                title = currentPinnedTitle,
                preview = currentPinnedPreview,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                pinnedCount = pinnedMessagesList.size,
                currentIndex = if (pinnedMessagesList.isNotEmpty()) (activePinnedIndex % pinnedMessagesList.size) + 1 else 1,
                onClick = {
                    if (currentPinnedMsg != null && pinnedMessagesList.isNotEmpty()) {
                        val idx = initialMessages.indexOfFirst { it.id == currentPinnedMsg.id }
                        if (idx != -1) {
                            coroutineScope.launch {
                                listState.animateScrollToItem(idx)
                                highlightedMessageId = currentPinnedMsg.id
                            }
                        }
                        activePinnedIndex = (activePinnedIndex + 1) % pinnedMessagesList.size
                    }
                },
                onUnpin = {
                    if (currentPinnedMsg != null) {
                        val targetId = currentPinnedMsg.id
                        val msgIndex = initialMessages.indexOfFirst { it.id == targetId }
                        if (msgIndex != -1) {
                            initialMessages[msgIndex] = initialMessages[msgIndex].copy(isPinned = false)
                        }
                        ChatDatabaseHelper.getInstance(context).updateMessagePinned(targetId, false)
                        if (pinnedMessagesList.size <= 1) {
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
                        P2PMessageRelay.sendUnpinMessage(context, peerName)
                    }
                },
                onOpenSheet = { showPinnedSheet = true }
            )

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
                            val idx = initialMessages.indexOfFirst { it.id == message.id }
                            if (idx != -1) {
                                val current = initialMessages[idx]
                                initialMessages[idx] = current.copy(status = "CANCELLED")
                            }
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
                val forwardPill = forwardingNotificationPill
                if (forwardPill != null) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        Text(
                            text = forwardPill,
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
                recordingAmplitudes = recordingAmplitudes,
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
                    com.example.twopchat.config.P2PPreferences.setPeerBlocked(context, peerName, false)
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
                                    val isConnected = P2PMessageRelay.peerSessionStates[peerName] == true || endpoint != null || peerName == "Saved Messages"
                                    val initialStatus = if (isConnected) "SENT" else "PENDING"

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
                                    if (peerName != "Saved Messages") {
                                        val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                                        if (!activeSet.contains(peerName)) {
                                            val newSet = activeSet.toMutableSet()
                                            newSet.add(peerName)
                                            sharedPrefs.edit { putStringSet("active_chats", newSet) }
                                        }
                                    }
                                    sharedPrefs.edit { putString("last_msg_$peerName", SecureStorage.encrypt("You: $userText")) }

                                    // Send message payload
                                    val payload = if (replyTo != null) {
                                        org.json.JSONObject().apply {
                                            put("type", "reply")
                                            put("message_id", outMsg.id)
                                            put("text", userText)
                                            put("sender", username)
                                            put("nickname", username)
                                            put("reply_to_id", replyTo.id)
                                            put("reply_to_text", replyTo.text)
                                            put("reply_to_name", replyTo.let { if (it.isMe) username else peerName })
                                        }.toString()
                                    } else {
                                        org.json.JSONObject().apply {
                                            put("type", "text")
                                            put("message_id", outMsg.id)
                                            put("text", userText)
                                            put("sender", username)
                                            put("nickname", username)
                                        }.toString()
                                    }

                                    // Send over real P2P transport
                                    if (peerName != "Saved Messages") {
                                        P2PMessageRelay.sendMessageToPeer(context, peerName, payload) { success ->
                                            if (!success) {
                                                persistDatabase { db.updateMessageStatus(outMsg.id, "PENDING") }
                                                coroutineScope.launch {
                                                    try {
                                                        val idx = initialMessages.indexOfFirst { it.id == outMsg.id }
                                                        if (idx in initialMessages.indices) {
                                                            initialMessages[idx] = outMsg.copy(status = "PENDING")
                                                        }
                                                    } catch (_: Throwable) {}
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
        val optionsMsg = selectedMessageForOptions
        if (optionsMsg != null) {
            val msg = optionsMsg
            ChatMessageOptionsMenu(
                msg = msg,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                isForwardingRestricted = isForwardingRestricted,
                onDismiss = { selectedMessageForOptions = null },
                onReactionClick = { emoji ->
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
                },
                onReply = {
                    replyingToMessage = msg
                    selectedMessageForOptions = null
                },
                onPin = {
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
                },
                onEdit = {
                    editingMessage = msg
                    inputText = msg.text
                    selectedMessageForOptions = null
                },
                onCopy = {
                    copyTextToClipboard(context, "2PChat message", msg.text)
                    Toast.makeText(context, if (appLanguage == "Русский") "Текст скопирован" else "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                    selectedMessageForOptions = null
                },
                onSaveGif = {
                    val uri = msg.attachmentUri
                    if (uri != null) {
                        val source = File(uri)
                        coroutineScope.launch {
                            val stored = withContext(Dispatchers.IO) {
                                GifStorageManager.save(context, source)
                            }
                            Toast.makeText(
                                context,
                                if (stored != null) {
                                    if (appLanguage == "Русский") "GIF сохранён в коллекцию" else "GIF saved to collection"
                                } else {
                                    if (appLanguage == "Русский") "Не удалось сохранить GIF" else "Could not save GIF"
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    selectedMessageForOptions = null
                },
                onSaveAttachment = {
                    val attachUri = msg.attachmentUri
                    if (attachUri != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                        ) {
                            coroutineScope.launch(Dispatchers.IO) {
                                val uri = if (msg.attachmentType == "IMAGE") {
                                    saveImageToPublicGallery(context, attachUri)
                                } else {
                                    saveFileToPublicDownloads(context, attachUri, msg.attachmentName ?: "file")
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
                        } else {
                            pendingDownloadMsg = msg
                            storageWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }
                    selectedMessageForOptions = null
                },
                onForward = {
                    messageToForward = msg
                    showForwardDialog = true
                    selectedMessageForOptions = null
                },
                onDelete = {
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
                },
                onSelect = {
                    isSelectMode = true
                    selectedMessages.clear()
                    selectedMessages.add(msg)
                    selectedMessageForOptions = null
                }
            )
        }

        if (showDatePickerDialog) {
            ChatDatePickerDialog(
                selectedDateFilterMs = selectedDateFilterMs,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onDismiss = { showDatePickerDialog = false },
                onDateSelected = { selectedDateFilterMs = it }
            )
        }

        if (showForwardDialog && messageToForward != null) {
            ChatForwardDialog(
                context = context,
                peerName = peerName,
                username = username,
                messageToForward = messageToForward,
                persistEnabled = persistEnabled,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                onDismiss = {
                    showForwardDialog = false
                    messageToForward = null
                },
                onPersistDatabase = { persistDatabase(it) }
            )
        }

        if (showVerifyDialog) {
            ChatVerifyPeerDialog(
                context = context,
                peerName = peerName,
                localFingerprint = localFingerprint,
                activeFingerprint = activeFingerprint,
                isVerified = isVerified,
                isWaitingForVerifyResponse = isWaitingForVerifyResponse,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onDismiss = {
                    showVerifyDialog = false
                    isWaitingForVerifyResponse = false
                },
                onSetVerified = { isVerified = it },
                onSetWaitingResponse = { isWaitingForVerifyResponse = it }
            )
        }

        if (showHardBlockDialog) {
            ChatHardBlockDialog(
                context = context,
                peerName = peerName,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                coroutineScope = coroutineScope,
                onDismiss = { showHardBlockDialog = false },
                onBlocked = { isBlocked = true }
            )
        }

        if (showIncomingVerifyDialog) {
            ChatIncomingVerifyDialog(
                context = context,
                peerName = peerName,
                localFingerprint = localFingerprint,
                peerFingerprint = sharedPrefs.getString("peer_fingerprint_$peerName", null).orEmpty(),
                appLanguage = appLanguage,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onDismiss = { showIncomingVerifyDialog = false },
                onVerified = { isVerified = true }
            )
        }

        if (showIdentityWarning && isIdentityPaused) {
            ChatIdentityWarningDialog(
                peerName = peerName,
                pendingFingerprint = pendingFingerprint,
                appLanguage = appLanguage,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onDismiss = { showIdentityWarning = false },
                onReviewNewKey = {
                    showIdentityWarning = false
                    showIdentityConfirmation = true
                }
            )
        }

        if (showIdentityConfirmation && isIdentityPaused) {
            ChatIdentityConfirmationDialog(
                context = context,
                peerName = peerName,
                activeFingerprint = activeFingerprint,
                pendingFingerprint = pendingFingerprint,
                identityDecisionInProgress = identityDecisionInProgress,
                appLanguage = appLanguage,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onDismiss = {
                    showIdentityConfirmation = false
                    showIdentityWarning = true
                },
                onSetDecisionInProgress = { identityDecisionInProgress = it },
                onAcceptComplete = { showIdentityConfirmation = false },
                onRejectComplete = { showIdentityConfirmation = false }
            )
        }

        if (showConnectionErrorDialog && !isIdentityPaused) {
            ChatConnectionErrorDialog(
                context = context,
                errorReasonYggdrasilDisabled = errorReasonYggdrasilDisabled,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                vpnLauncher = vpnLauncher,
                onDismiss = { showConnectionErrorDialog = false }
            )
        }

        ChatModalsOverlay(
            context = context,
            peerName = peerName,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = onSurfaceVariant,
            initialMessages = initialMessages,
            listState = listState,
            coroutineScope = coroutineScope,
            showProfileOverlay = showProfileOverlay,
            onDismissProfileOverlay = { showProfileOverlay = false },
            isVerified = isVerified,
            isMuted = isMuted,
            onToggleMute = { newMuted ->
                isMuted = newMuted
                sharedPrefs.edit().putBoolean("mute_notifications_$peerName", newMuted).apply()
            },
            onNavigateToMessage = { messageId ->
                showProfileOverlay = false
                val idx = initialMessages.indexOfFirst { it.id == messageId }
                if (idx != -1) {
                    coroutineScope.launch {
                        listState.scrollToItem(idx)
                        highlightedMessageId = messageId
                    }
                }
            },
            activeFullscreenImages = activeFullscreenImages,
            activeFullscreenImageIndex = activeFullscreenImageIndex,
            activeFullscreenBitmapOverrides = activeFullscreenBitmapOverrides,
            activeFullscreenVideo = activeFullscreenVideo,
            onCloseFullscreenImages = {
                activeFullscreenImages = emptyList()
                activeFullscreenBitmapOverrides = emptyMap()
            },
            onCloseFullscreenVideo = { activeFullscreenVideo = null },
            onOpenFullscreenAvatar = { avatarBitmap ->
                val highRes = P2PMessageRelay.getOriginalAvatar(context, peerName) ?: avatarBitmap
                if (highRes != null) {
                    val avatarKey = "avatar:$peerName"
                    activeFullscreenBitmapOverrides = mapOf(avatarKey to highRes)
                    activeFullscreenImages = listOf(avatarKey)
                    activeFullscreenImageIndex = 0
                }
            },
            onOpenFullscreenImages = { paths, index ->
                if (paths.isNotEmpty()) {
                    activeFullscreenBitmapOverrides = emptyMap()
                    activeFullscreenImages = paths
                    activeFullscreenImageIndex = index
                }
            },
            onOpenFullscreenVideo = { activeFullscreenVideo = it },
            showConnectionModeSheet = showConnectionModeSheet,
            onDismissConnectionModeSheet = { showConnectionModeSheet = false },
            showStickerPicker = showStickerPicker,
            onDismissStickerPicker = { showStickerPicker = false },
            onSelectSticker = ::sendSticker,
            viewedStickerMessage = viewedStickerMessage,
            onDismissViewedSticker = {
                viewedStickerMessage = null
                stickerPackRequestInProgress = false
                stickerPackRequestError = StickerPackRequestError.NONE
            },
            stickerPackRequestInProgress = stickerPackRequestInProgress,
            onSetStickerPackRequestInProgress = { stickerPackRequestInProgress = it },
            stickerPackRequestError = stickerPackRequestError,
            onSetStickerPackRequestError = { stickerPackRequestError = it },
            stickerPackPreviewRevision = stickerPackPreviewRevision,
            showGifLibrary = showGifLibrary,
            onDismissGifLibrary = { showGifLibrary = false },
            storedGifs = storedGifs,
            gifLibraryLoading = gifLibraryLoading,
            gifImportLauncher = gifImportLauncher,
            onSelectGif = { sendGifFile(it) },
            showPinnedSheet = showPinnedSheet,
            onDismissPinnedSheet = { showPinnedSheet = false },
            pinnedMessagesList = pinnedMessagesList,
            onActivePinnedIndexChanged = { activePinnedIndex = it },
            onHighlightMessage = { highlightedMessageId = it },
            onClearPinnedHeader = {
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
            },
            showWallpaperModal = showWallpaperModal,
            onDismissWallpaperModal = { showWallpaperModal = false },
            wallpaperPath = wallpaperPath,
            wallpaperDimming = wallpaperDimming,
            wallpaperBlur = wallpaperBlur,
            onWallpaperUpdated = { path, dimming, blur ->
                wallpaperPath = path
                wallpaperDimming = dimming
                wallpaperBlur = blur
            }
        )
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
            // Trigger MediaScanner (API-21+ compatible)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
            return Uri.fromFile(destFile)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}
