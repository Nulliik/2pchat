package com.example.twopchat.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.media.BuiltinSticker
import com.example.twopchat.media.StickerSupport
import com.example.twopchat.media.StoredGif
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.ui.chat.components.ChatFullscreenMediaViewer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatModalsOverlay(
    context: Context,
    peerName: String,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    initialMessages: SnapshotStateList<Message>,
    listState: LazyListState,
    coroutineScope: CoroutineScope,
    // Profile & Media Viewer State
    showProfileOverlay: Boolean,
    onDismissProfileOverlay: () -> Unit,
    isVerified: Boolean,
    isMuted: Boolean,
    onToggleMute: (Boolean) -> Unit,
    onNavigateToMessage: (String) -> Unit,
    activeFullscreenImages: List<String>,
    activeFullscreenImageIndex: Int,
    activeFullscreenBitmapOverrides: Map<String, Bitmap>,
    activeFullscreenVideo: String?,
    activeFullscreenCaption: String? = null,
    activeFullscreenTimestamp: String? = null,
    onShareMedia: ((String) -> Unit)? = null,
    onDeleteMedia: ((String) -> Unit)? = null,
    onForwardMedia: ((String) -> Unit)? = null,
    onCloseFullscreenImages: () -> Unit,
    onCloseFullscreenVideo: () -> Unit,
    onOpenFullscreenAvatar: (Bitmap?) -> Unit,
    onOpenFullscreenImages: (List<String>, Int, Message?) -> Unit,
    onOpenFullscreenVideo: (String, Message?) -> Unit,
    // Connection Mode Sheet
    showConnectionModeSheet: Boolean,
    onDismissConnectionModeSheet: () -> Unit,
    // Sticker Picker & Pack Bottom Sheet
    showStickerPicker: Boolean,
    onDismissStickerPicker: () -> Unit,
    onSelectSticker: (BuiltinSticker) -> Unit,
    viewedStickerMessage: Message?,
    onDismissViewedSticker: () -> Unit,
    stickerPackRequestInProgress: Boolean,
    onSetStickerPackRequestInProgress: (Boolean) -> Unit,
    stickerPackRequestError: StickerPackRequestError,
    onSetStickerPackRequestError: (StickerPackRequestError) -> Unit,
    stickerPackPreviewRevision: Int,
    // GIF Library Sheet
    showGifLibrary: Boolean,
    onDismissGifLibrary: () -> Unit,
    storedGifs: List<StoredGif>,
    gifLibraryLoading: Boolean,
    gifImportLauncher: ActivityResultLauncher<String>,
    onSelectGif: (File) -> Unit,
    // Pinned Messages Sheet
    showPinnedSheet: Boolean,
    onDismissPinnedSheet: () -> Unit,
    pinnedMessagesList: List<Message>,
    onActivePinnedIndexChanged: (Int) -> Unit,
    onHighlightMessage: (String) -> Unit,
    onClearPinnedHeader: () -> Unit,
    // Wallpaper Modal
    showWallpaperModal: Boolean,
    onDismissWallpaperModal: () -> Unit,
    wallpaperPath: String?,
    wallpaperDimming: Int,
    wallpaperBlur: Boolean,
    onWallpaperUpdated: (String?, Int, Boolean) -> Unit,
) {
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
            onToggleMute = onToggleMute,
            onAvatarClick = onOpenFullscreenAvatar,
            onImageClick = { paths, idx -> onOpenFullscreenImages(paths, idx, null) },
            onVideoClick = { videoPath -> onOpenFullscreenVideo(videoPath, null) },
            onBack = onDismissProfileOverlay,
            onNavigateToMessage = onNavigateToMessage,
        )
    }

    if (showConnectionModeSheet && peerName != "Saved Messages") {
        ConnectionModeBottomSheet(
            peerName = peerName,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = onSurfaceVariant,
            onDismiss = onDismissConnectionModeSheet,
        )
    }

    if (showStickerPicker) {
        StickerPickerBottomSheet(
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            onDismiss = onDismissStickerPicker,
            onStickerSelected = onSelectSticker,
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
                        onSetStickerPackRequestInProgress(false)
                        if (stickerPackRequestError == StickerPackRequestError.NONE) {
                            onSetStickerPackRequestError(StickerPackRequestError.TIMEOUT)
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
                onDismiss = onDismissViewedSticker,
                onRequestPack = {
                    if (peerName !in P2PMessageRelay.peerEndpoints) {
                        onSetStickerPackRequestError(StickerPackRequestError.PEER_OFFLINE)
                        onSetStickerPackRequestInProgress(false)
                        return@StickerPackBottomSheet
                    }
                    onSetStickerPackRequestError(StickerPackRequestError.NONE)
                    onSetStickerPackRequestInProgress(true)
                    P2PMessageRelay.requestStickerPack(context, peerName, packId) { sent ->
                        if (!sent) {
                            onSetStickerPackRequestInProgress(false)
                            onSetStickerPackRequestError(StickerPackRequestError.NETWORK_ERROR)
                        }
                    }
                },
                onStickerSelected = onSelectSticker,
            )
        }
    }

    if (showGifLibrary) {
        GifLibraryBottomSheet(
            gifs = storedGifs,
            isLoading = gifLibraryLoading,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            onDismiss = onDismissGifLibrary,
            onImport = {
                onDismissGifLibrary()
                gifImportLauncher.launch("image/gif")
            },
            onGifSelected = { onSelectGif(File(it.filePath)) },
        )
    }

    ChatFullscreenMediaViewer(
        activeFullscreenImages = activeFullscreenImages,
        activeFullscreenImageIndex = activeFullscreenImageIndex,
        activeFullscreenBitmapOverrides = activeFullscreenBitmapOverrides,
        activeFullscreenVideo = activeFullscreenVideo,
        appLanguage = appLanguage,
        activeFullscreenCaption = activeFullscreenCaption,
        activeFullscreenTimestamp = activeFullscreenTimestamp,
        onNavigateToMessage = onNavigateToMessage,
        onShareMedia = onShareMedia,
        onDeleteMedia = onDeleteMedia,
        onForwardMedia = onForwardMedia,
        onCloseImages = onCloseFullscreenImages,
        onCloseVideo = onCloseFullscreenVideo,
    )

    if (showPinnedSheet) {
        val pinnedItems = remember(pinnedMessagesList) {
            pinnedMessagesList.map { msg ->
                PinnedItemModel(
                    id = msg.id,
                    senderName = if (msg.isMe) (if (appLanguage == "Русский") "Вы" else "You") else peerName,
                    text = msg.text,
                    timestamp = msg.timestamp,
                    attachmentType = msg.attachmentType,
                    attachmentName = msg.attachmentName,
                )
            }
        }
        PinnedMessagesSheet(
            pinnedItems = pinnedItems,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = onSurfaceVariant,
            onDismiss = onDismissPinnedSheet,
            onSelectPinnedMessage = { item ->
                val idx = initialMessages.indexOfFirst { it.id == item.id }
                if (idx != -1) {
                    val pinIdx = pinnedMessagesList.indexOfFirst { it.id == item.id }
                    if (pinIdx != -1) onActivePinnedIndexChanged(pinIdx)
                    coroutineScope.launch {
                        listState.animateScrollToItem(idx)
                        onHighlightMessage(item.id)
                    }
                }
            },
            onUnpinMessage = { item ->
                val msgIndex = initialMessages.indexOfFirst { it.id == item.id }
                if (msgIndex != -1) {
                    initialMessages[msgIndex] = initialMessages[msgIndex].copy(isPinned = false)
                }
                ChatDatabaseHelper.getInstance(context).updateMessagePinned(item.id, false)
                if (pinnedItems.size <= 1) {
                    onClearPinnedHeader()
                }
                P2PMessageRelay.sendUnpinMessage(context, peerName)
            },
            onUnpinAll = {
                pinnedItems.forEach { item ->
                    val msgIndex = initialMessages.indexOfFirst { it.id == item.id }
                    if (msgIndex != -1) {
                        initialMessages[msgIndex] = initialMessages[msgIndex].copy(isPinned = false)
                    }
                    ChatDatabaseHelper.getInstance(context).updateMessagePinned(item.id, false)
                }
                onClearPinnedHeader()
                P2PMessageRelay.sendUnpinMessage(context, peerName)
            }
        )
    }

    if (showWallpaperModal) {
        DirectChatWallpaperModal(
            peerName = peerName,
            currentWallpaperPath = wallpaperPath,
            currentDimming = wallpaperDimming,
            currentBlur = wallpaperBlur,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = onSurfaceVariant,
            onDismiss = onDismissWallpaperModal,
            onApply = { bitmap, dimming, isBlur, _, applyToPeer ->
                onDismissWallpaperModal()
                val dir = File(context.filesDir, "direct_wallpapers").also { it.mkdirs() }
                val targetFile = File(dir, "wallpaper_$peerName.jpg")
                val fp = com.example.twopchat.config.P2PPreferences.getPeerFingerprint(context, peerName)
                if (bitmap != null) {
                    try {
                        FileOutputStream(targetFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        if (!fp.isNullOrBlank() && fp != peerName) {
                            try {
                                val fpFile = File(dir, "wallpaper_$fp.jpg")
                                FileOutputStream(fpFile).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                }
                            } catch (e: Exception) {
                                com.example.twopchat.logging.SafeLog.d("ChatModalsOverlay", "Failed writing duplicate wallpaper for fp alias: ${e.javaClass.simpleName}")
                            }
                        }
                        com.example.twopchat.config.P2PPreferences.setDirectWallpaper(context, peerName, targetFile.absolutePath, dimming, isBlur)
                        onWallpaperUpdated(targetFile.absolutePath, dimming, isBlur)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    targetFile.delete()
                    if (!fp.isNullOrBlank() && fp != peerName) {
                        File(dir, "wallpaper_$fp.jpg").delete()
                    }
                    com.example.twopchat.config.P2PPreferences.setDirectWallpaper(context, peerName, null, 0, false)
                    onWallpaperUpdated(null, 0, false)
                }

                if (applyToPeer) {
                    P2PMessageRelay.sendDirectWallpaperUpdate(context, peerName, bitmap, dimming, isBlur)
                    val textRu = if (bitmap != null) "Вы установили новые обои для этого чата" else "Вы удалили обои для этого чата"
                    val textEn = if (bitmap != null) "You set a new wallpaper for this chat" else "You removed the wallpaper for this chat"
                    val sysMsg = Message(
                        id = java.util.UUID.randomUUID().toString(),
                        text = if (appLanguage == "Русский") textRu else textEn,
                        isMe = true,
                        timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                        attachmentType = "SYSTEM"
                    )
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        ChatDatabaseHelper.getInstance(context).saveMessage(peerName, sysMsg)
                    }
                    initialMessages.add(sysMsg)
                    Toast.makeText(context, if (appLanguage == "Русский") "Обои применены у вас и отправлены $peerName" else "Wallpaper set for you and $peerName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, if (appLanguage == "Русский") "Обои применены у вас" else "Wallpaper applied", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
