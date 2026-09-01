package com.example.twopchat.ui.chat.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import com.example.twopchat.ui.chat.FullscreenImageViewer
import com.example.twopchat.ui.chat.FullscreenVideoPlayer

@Composable
internal fun ChatFullscreenMediaViewer(
    activeFullscreenImages: List<String>,
    activeFullscreenImageIndex: Int,
    activeFullscreenBitmapOverrides: Map<String, Bitmap>,
    activeFullscreenVideo: String?,
    appLanguage: String,
    activeFullscreenCaption: String? = null,
    activeFullscreenTimestamp: String? = null,
    onNavigateToMessage: ((String) -> Unit)? = null,
    onShareMedia: ((String) -> Unit)? = null,
    onDeleteMedia: ((String) -> Unit)? = null,
    onForwardMedia: ((String) -> Unit)? = null,
    onCloseImages: () -> Unit,
    onCloseVideo: () -> Unit,
) {
    if (activeFullscreenImages.isNotEmpty()) {
        FullscreenImageViewer(
            imagePaths = activeFullscreenImages,
            initialIndex = activeFullscreenImageIndex,
            appLanguage = appLanguage,
            bitmapOverrides = activeFullscreenBitmapOverrides,
            caption = activeFullscreenCaption,
            timestamp = activeFullscreenTimestamp,
            onGoToMessage = onNavigateToMessage,
            onShare = onShareMedia,
            onDelete = onDeleteMedia,
            onForward = onForwardMedia,
            onClose = onCloseImages,
        )
    }

    if (activeFullscreenVideo != null) {
        FullscreenVideoPlayer(
            videoPath = activeFullscreenVideo,
            appLanguage = appLanguage,
            caption = activeFullscreenCaption,
            timestamp = activeFullscreenTimestamp,
            onGoToMessage = onNavigateToMessage,
            onShare = onShareMedia,
            onDelete = onDeleteMedia,
            onForward = onForwardMedia,
            onClose = onCloseVideo,
        )
    }
}
