package com.example.twopchat.group.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.group.ui.GroupChatUiState
import com.example.twopchat.group.ui.GroupTimelineMessage
import com.example.twopchat.group.ui.GroupUiController
import com.example.twopchat.group.ui.GroupWallpaperModal
import com.example.twopchat.media.BuiltinSticker
import com.example.twopchat.media.GifStorageManager
import com.example.twopchat.media.StickerSupport
import com.example.twopchat.media.StoredGif
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.ui.chat.AlbumPreviewModal
import com.example.twopchat.ui.chat.GifLibraryBottomSheet
import com.example.twopchat.ui.chat.PhotoEditorModal
import com.example.twopchat.ui.chat.PinnedItemModel
import com.example.twopchat.ui.chat.PinnedMessagesSheet
import com.example.twopchat.ui.chat.StickerPackBottomSheet
import com.example.twopchat.ui.chat.StickerPackRequestError
import com.example.twopchat.ui.chat.StickerPickerBottomSheet
import com.example.twopchat.ui.chat.VideoEditorModal
import com.example.twopchat.ui.chat.components.ChatFullscreenMediaViewer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupChatModalsOverlay(
    context: Context,
    state: GroupChatUiState,
    controller: GroupUiController,
    coroutineScope: CoroutineScope,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    // Sticker Picker & Pack
    showStickerPicker: Boolean,
    onDismissStickerPicker: () -> Unit,
    viewedStickerMessage: GroupTimelineMessage?,
    onDismissViewedSticker: () -> Unit,
    stickerPackRequestInProgress: Boolean,
    onSetStickerPackRequestInProgress: (Boolean) -> Unit,
    stickerPackRequestError: StickerPackRequestError,
    onSetStickerPackRequestError: (StickerPackRequestError) -> Unit,
    stickerPackPreviewRevision: Int,
    // GIF Library
    showGifLibrary: Boolean,
    onDismissGifLibrary: () -> Unit,
    gifImportLauncher: ActivityResultLauncher<String>,
    // Editors & Album
    pendingPhotoUri: Uri?,
    onDismissPhotoEditor: () -> Unit,
    pendingVideoPath: String?,
    onDismissVideoEditor: () -> Unit,
    pendingAlbumFiles: List<File>?,
    pendingAlbumTypes: List<String>?,
    onDismissAlbumPreview: () -> Unit,
    // Fullscreen Media Viewer
    activeFullscreenImages: List<String>,
    activeFullscreenImageIndex: Int,
    activeFullscreenBitmapOverrides: Map<String, Bitmap>,
    activeFullscreenVideo: String?,
    onCloseFullscreenImages: () -> Unit,
    onCloseFullscreenVideo: () -> Unit,
    // Pinned Sheet & Wallpaper
    showPinnedSheet: Boolean,
    onDismissPinnedSheet: () -> Unit,
    onNavigateToMessage: (String) -> Unit,
    showWallpaperModal: Boolean,
    onDismissWallpaperModal: () -> Unit,
    wallpaperUriStr: String?,
    wallpaperDimming: Int,
    wallpaperBlur: Boolean,
) {
    if (showStickerPicker) {
        StickerPickerBottomSheet(
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            onDismiss = onDismissStickerPicker,
            onStickerSelected = { sticker: BuiltinSticker ->
                onDismissStickerPicker()
                coroutineScope.launch {
                    val stickerFile = withContext(Dispatchers.IO) {
                        runCatching { StickerSupport.prepareSticker(context, sticker) }.getOrNull()
                    }
                    if (stickerFile != null) {
                        controller.sendAttachment(
                            groupId = state.groupId,
                            uri = Uri.fromFile(stickerFile).toString(),
                            mimeType = "image/sticker",
                            caption = sticker.emoji,
                        )
                    } else {
                        controller.sendMessage(state.groupId, sticker.emoji, state.currentReply?.messageId)
                    }
                }
            }
        )
    }

    viewedStickerMessage?.let { stickerMessage ->
        val att = stickerMessage.attachment
        val packId = att?.let { StickerSupport.packIdFromStickerFileName(it.fileName) }
        if (packId != null) {
            val peerName = stickerMessage.authorName
            val canRequest = !stickerMessage.isMine && peerName.isNotBlank() && peerName != "SYSTEM" && peerName != "System"

            LaunchedEffect(stickerPackRequestInProgress) {
                if (stickerPackRequestInProgress) {
                    delay(10_000L)
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
                fallbackEmoji = if (stickerMessage.text.startsWith("2psticker_") || stickerMessage.text.contains(".webp")) "🎭" else stickerMessage.text,
                canRequestFromPeer = canRequest,
                requestInProgress = stickerPackRequestInProgress,
                previewRevision = stickerPackPreviewRevision,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                requestError = stickerPackRequestError,
                onDismiss = onDismissViewedSticker,
                onRequestPack = {
                    if (peerName.isBlank() || !P2PMessageRelay.peerEndpoints.containsKey(peerName)) {
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
                onStickerSelected = { sticker: BuiltinSticker ->
                    onDismissViewedSticker()
                    coroutineScope.launch {
                        val stickerFile = withContext(Dispatchers.IO) {
                            runCatching { StickerSupport.prepareSticker(context, sticker) }.getOrNull()
                        }
                        if (stickerFile != null) {
                            controller.sendAttachment(
                                groupId = state.groupId,
                                uri = Uri.fromFile(stickerFile).toString(),
                                mimeType = "image/sticker",
                                caption = sticker.emoji,
                            )
                        } else {
                            controller.sendMessage(state.groupId, sticker.emoji, state.currentReply?.messageId)
                        }
                    }
                }
            )
        }
    }

    if (showGifLibrary) {
        val gifList by produceState(initialValue = emptyList<StoredGif>(), context) {
            value = withContext(Dispatchers.IO) { GifStorageManager.list(context) }
        }
        GifLibraryBottomSheet(
            gifs = gifList,
            isLoading = false,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            onDismiss = onDismissGifLibrary,
            onImport = {
                onDismissGifLibrary()
                gifImportLauncher.launch("image/gif")
            },
            onGifSelected = { gif: StoredGif ->
                onDismissGifLibrary()
                controller.sendAttachment(state.groupId, Uri.fromFile(File(gif.filePath)).toString(), "image/gif")
            }
        )
    }

    pendingPhotoUri?.let { uri ->
        PhotoEditorModal(
            imageUri = uri,
            imagePath = null,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = onSurfaceColor.copy(alpha = 0.7f),
            onDismiss = onDismissPhotoEditor,
            onSendPhoto = { editedFilePath: String, caption: String ->
                onDismissPhotoEditor()
                controller.sendAttachment(
                    state.groupId,
                    Uri.fromFile(File(editedFilePath)).toString(),
                    "image/png",
                    caption.trim().takeIf { it.isNotBlank() }
                )
            }
        )
    }

    pendingVideoPath?.let { path ->
        VideoEditorModal(
            videoPath = path,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = onSurfaceColor.copy(alpha = 0.7f),
            onDismiss = onDismissVideoEditor,
            onSendVideo = { editedPath: String, caption: String ->
                onDismissVideoEditor()
                val targetUri = if (editedPath.startsWith("content://") || editedPath.startsWith("file://")) editedPath else Uri.fromFile(File(editedPath)).toString()
                controller.sendAttachment(
                    state.groupId,
                    targetUri,
                    "video/mp4",
                    caption.trim().takeIf { it.isNotBlank() }
                )
            }
        )
    }

    if (pendingAlbumFiles != null) {
        AlbumPreviewModal(
            files = pendingAlbumFiles,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            onDismiss = onDismissAlbumPreview,
            onSendAlbum = { finalFiles: List<File>, caption: String ->
                val types = pendingAlbumTypes ?: emptyList()
                onDismissAlbumPreview()
                coroutineScope.launch {
                    val cleanCaption = caption.trim().takeIf { it.isNotBlank() }
                    if (finalFiles.size == 1) {
                        val file = finalFiles.first()
                        val mime = types.firstOrNull() ?: "IMAGE"
                        val fileMime = when (mime) {
                            "VIDEO" -> "video/mp4"
                            GifStorageManager.ATTACHMENT_TYPE -> "image/gif"
                            else -> if (file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true)) "image/jpeg" else "image/png"
                        }
                        controller.sendAttachment(state.groupId, Uri.fromFile(file).toString(), fileMime, cleanCaption)
                    } else if (finalFiles.size > 1) {
                        val uris = finalFiles.map { Uri.fromFile(it).toString() }
                        val mimes = finalFiles.mapIndexed { idx: Int, file: File ->
                            val mime = types.getOrNull(idx) ?: "IMAGE"
                            when (mime) {
                                "VIDEO" -> "video/mp4"
                                GifStorageManager.ATTACHMENT_TYPE -> "image/gif"
                                else -> if (file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true)) "image/jpeg" else "image/png"
                            }
                        }
                        controller.sendMediaAlbum(state.groupId, uris, mimes, cleanCaption)
                    }
                }
            }
        )
    }

    ChatFullscreenMediaViewer(
        activeFullscreenImages = activeFullscreenImages,
        activeFullscreenImageIndex = activeFullscreenImageIndex,
        activeFullscreenBitmapOverrides = activeFullscreenBitmapOverrides,
        activeFullscreenVideo = activeFullscreenVideo,
        appLanguage = appLanguage,
        onCloseImages = onCloseFullscreenImages,
        onCloseVideo = onCloseFullscreenVideo,
    )

    if (showPinnedSheet) {
        val pinnedItems = remember(state.messages, state.pinnedMessage) {
            val list = state.messages.filter { it.isPinned }
            if (list.isNotEmpty()) {
                list.map { msg ->
                    PinnedItemModel(
                        id = msg.messageId,
                        senderName = msg.authorName.ifBlank { "Участник" },
                        text = msg.text,
                        timestamp = msg.timestampLabel,
                        attachmentType = msg.attachment?.mimeType,
                        attachmentName = msg.attachment?.fileName,
                    )
                }
            } else {
                state.pinnedMessage?.let { pinned ->
                    val msg = state.messages.find { it.messageId == pinned.messageId }
                    listOf(
                        PinnedItemModel(
                            id = pinned.messageId,
                            senderName = msg?.authorName?.ifBlank { "Участник" } ?: "Участник",
                            text = pinned.text,
                            timestamp = msg?.timestampLabel ?: "",
                            attachmentType = msg?.attachment?.mimeType,
                            attachmentName = msg?.attachment?.fileName,
                        )
                    )
                } ?: emptyList()
            }
        }

        PinnedMessagesSheet(
            pinnedItems = pinnedItems,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
            onDismiss = onDismissPinnedSheet,
            onSelectPinnedMessage = { item: PinnedItemModel ->
                onNavigateToMessage(item.id)
            },
            onUnpinMessage = { item: PinnedItemModel ->
                controller.unpinMessage(state.groupId, item.id)
            },
            onUnpinAll = {
                pinnedItems.forEach { item ->
                    controller.unpinMessage(state.groupId, item.id)
                }
            }
        )
    }

    if (showWallpaperModal) {
        GroupWallpaperModal(
            groupTitle = state.title,
            currentWallpaperPath = wallpaperUriStr,
            currentDimming = wallpaperDimming,
            currentBlur = wallpaperBlur,
            appLanguage = appLanguage,
            primaryColor = primaryColor,
            surfaceColor = surfaceColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
            onDismiss = onDismissWallpaperModal,
            onApply = { selectedBitmap, dimming, isBlur ->
                onDismissWallpaperModal()
                val dir = File(context.filesDir, "group_wallpapers").also { it.mkdirs() }
                val targetFile = File(dir, "wallpaper_${state.groupId}.jpg")
                if (selectedBitmap != null) {
                    try {
                        FileOutputStream(targetFile).use { out ->
                            selectedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        P2PPreferences.prefs(context).edit().apply {
                            putString("group_wallpaper_${state.groupId}", targetFile.absolutePath)
                            putInt("group_wallpaper_dimming_${state.groupId}", dimming)
                            putBoolean("group_wallpaper_blur_${state.groupId}", isBlur)
                            apply()
                        }
                        controller.updateGroupWallpaper(state.groupId, targetFile.absolutePath, dimming, isBlur)
                        Toast.makeText(
                            context,
                            com.example.twopchat.data.Localizations.tr(
                                appLanguage,
                                ru = "Обои установлены для всех участников",
                                en = "Wallpaper updated for all members",
                                de = "Hintergrundbild für alle Mitglieder aktualisiert",
                                es = "Fondo de pantalla actualizado para todos los miembros",
                                fr = "Fond d'écran mis à jour pour tous les membres",
                                pt = "Papel de parede atualizado para todos os membros",
                                tr = "Duvar kağıdı tüm üyeler için güncellendi"
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    P2PPreferences.prefs(context).edit().apply {
                        remove("group_wallpaper_${state.groupId}")
                        remove("group_wallpaper_dimming_${state.groupId}")
                        remove("group_wallpaper_blur_${state.groupId}")
                        apply()
                    }
                    controller.updateGroupWallpaper(state.groupId, null, 45, false)
                    Toast.makeText(
                        context,
                        com.example.twopchat.data.Localizations.tr(
                            appLanguage,
                            ru = "Обои сброшены для всех участников",
                            en = "Wallpaper removed for all members",
                            de = "Hintergrundbild für alle Mitglieder entfernt",
                            es = "Fondo de pantalla eliminado para todos los miembros",
                            fr = "Fond d'écran supprimé pour tous les membres",
                            pt = "Papel de parede removido para todos os membros",
                            tr = "Duvar kağıdı tüm üyeler için kaldırıldı"
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}
