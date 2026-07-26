package com.example.twopchat.ui.chat

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.R
import com.example.twopchat.theme.StealthBlack
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.draw.scale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import java.util.regex.Pattern
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive

internal class MessageArrivalAnimationTracker(
    private val lifetimeMs: Long = 1_500L,
) {
    private val pending = mutableMapOf<String, Long>()

    @Synchronized
    fun mark(messageId: String, nowEpochMs: Long = System.currentTimeMillis()) {
        pending.entries.removeAll { it.value < nowEpochMs }
        pending[messageId] = nowEpochMs + lifetimeMs
    }

    @Synchronized
    fun consume(messageId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val expiresAt = pending.remove(messageId) ?: return false
        pending.entries.removeAll { it.value < nowEpochMs }
        return nowEpochMs <= expiresAt
    }
}

private fun isAttachmentAvailable(uri: String?): Boolean {
    if (uri.isNullOrBlank()) return false
    return "://" in uri || java.io.File(uri).isFile
}

@Composable
internal fun ChatMessageBubble(
    index: Int,
    msg: Message,
    messages: List<Message>,
    selectedMessages: MutableList<Message>,
    isSelectMode: Boolean,
    isTyping: Boolean,
    peerName: String,
    myAvatarBitmap: Bitmap?,
    appLanguage: String,
    animateOnAppearance: Boolean,
    listState: LazyListState,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onReply: (Message) -> Unit,
    onShowOptions: (Message) -> Unit,
    onOpenImages: (List<String>, Int) -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenStickerPack: (Message) -> Unit,
    onCancelFileTransfer: (Message) -> Unit,
    highlightedMessageId: String? = null,
    onHighlightFinished: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val isHighlighted = msg.id == highlightedMessageId
    var highlightAlpha by remember(msg.id, isHighlighted) { mutableStateOf(if (isHighlighted) 0.5f else 0.0f) }
    if (isHighlighted && highlightAlpha > 0f) {
        LaunchedEffect(msg.id) {
            androidx.compose.animation.core.animate(
                initialValue = 0.5f,
                targetValue = 0f,
                animationSpec = tween(2500)
            ) { value, _ ->
                highlightAlpha = value
            }
            onHighlightFinished()
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = remember(context) { com.example.twopchat.P2PPreferences.prefs(context) }
    val linkPreviewsEnabled = remember(sharedPrefs) { sharedPrefs.getBoolean("settings_link_previews", false) }
    val isText = msg.attachmentType == null
    val isSticker = msg.attachmentType == com.example.twopchat.StickerSupport.ATTACHMENT_TYPE
    val isGif = msg.attachmentType == com.example.twopchat.GifStorageManager.ATTACHMENT_TYPE
    val isOnlyEmoji = isText && isSingleEmoji(msg.text)
    val detectedUrl = remember(msg.text, isText) {
        if (!isText) null else {
            val matcher = URL_PATTERN.matcher(msg.text)
            if (matcher.find()) matcher.group(1) else null
        }
    }
    val visibleState = remember(msg.id) {
        MutableTransitionState(!animateOnAppearance).apply {
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
    val bubbleModifier = if (isOnlyEmoji || isSticker || isGif) {
        Modifier
    } else if (msg.isMe) {
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

    val onPrimaryContent = if (primaryColor.luminance() > 0.4f) Color(0xFF1A1A1A) else Color.White
    val textColor = if (msg.isMe) {
        if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else onPrimaryContent
    } else onSurfaceColor
    val linkColor = if (msg.isMe) {
        if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else onPrimaryContent
    } else primaryColor

    androidx.compose.animation.AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(animationSpec = tween(200)) + scaleIn(
            initialScale = 0.85f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + slideInVertically(
            initialOffsetY = { it / 3 },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        SwipeToReplyContainer(
            onReply = {
                onReply(msg)
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (highlightAlpha > 0f) primaryColor.copy(alpha = highlightAlpha * 0.4f) else Color.Transparent)
                    .padding(vertical = 2.dp),
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
                                        onShowOptions(msg)
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectMode) {
                                        onShowOptions(msg)
                                    }
                                }
                            )
                            // Subtle border for incoming bubbles
                            .then(if (!msg.isMe && !isOnlyEmoji && !isSticker && !isGif && msg.attachmentType != "IMAGE" && msg.attachmentType != "VIDEO") Modifier.border(0.5.dp, onSurfaceColor.copy(alpha = if (surfaceColor.luminance() > 0.5f) 0.09f else 0.08f), bubbleShape) else Modifier)
                            .padding(
                                horizontal = if (isOnlyEmoji || isSticker || isGif || msg.attachmentType == "IMAGE" || msg.attachmentType == "VIDEO") 0.dp else 16.dp,
                                vertical = if (isOnlyEmoji || isSticker || isGif || msg.attachmentType == "IMAGE" || msg.attachmentType == "VIDEO") 0.dp else 11.dp
                            )
                            .widthIn(max = 280.dp)
                    ) {
                        Column(horizontalAlignment = alignment) {
                            // Render reply quote if this message is a reply
                            if (msg.replyToId != null) {
                                val replyBg = if (isOnlyEmoji) {
                                    onSurfaceColor.copy(alpha = 0.07f)
                                } else if (msg.isMe) {
                                    Color.White.copy(alpha = 0.15f)
                                } else {
                                    onSurfaceColor.copy(alpha = 0.05f)
                                }
                                // Use contrast-safe colors: if primaryColor is dark, use white; if light, use dark text
                                val onPrimary = if (primaryColor.luminance() > 0.4f) Color(0xFF1A1A1A) else Color.White
                                val replyBarColor = if (isOnlyEmoji) {
                                    primaryColor
                                } else if (msg.isMe) {
                                    if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else onPrimary
                                } else primaryColor
                                val replyTextColor = if (isOnlyEmoji) {
                                    onSurfaceVariant
                                } else if (msg.isMe) {
                                    if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack.copy(alpha = 0.8f) else onPrimary.copy(alpha = 0.8f)
                                } else onSurfaceVariant
                                val replyTitleColor = if (isOnlyEmoji) {
                                    primaryColor
                                } else if (msg.isMe) {
                                    if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else onPrimary
                                } else primaryColor
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(replyBg)
                                        .clickable {
                                            val targetIndex = messages.indexOfFirst { it.id == msg.replyToId }
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

                            when (if (msg.albumMediaUris.isNotEmpty()) "ALBUM" else msg.attachmentType) {
                                com.example.twopchat.StickerSupport.ATTACHMENT_TYPE -> {
                                    StickerMessageContent(
                                        filePath = msg.attachmentUri,
                                        fallbackEmoji = msg.text,
                                        onClick = {
                                            if (isSelectMode) {
                                                if (selectedMessages.contains(msg)) {
                                                    selectedMessages.remove(msg)
                                                } else {
                                                    selectedMessages.add(msg)
                                                }
                                            } else {
                                                onOpenStickerPack(msg)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectMode) onShowOptions(msg)
                                        },
                                    )
                                }
                                com.example.twopchat.GifStorageManager.ATTACHMENT_TYPE -> {
                                    GifMessageContent(
                                        filePath = msg.attachmentUri,
                                        fallbackText = msg.text,
                                        bubbleShape = bubbleShape,
                                        onLongClick = {
                                            if (!isSelectMode) onShowOptions(msg)
                                        },
                                    )
                                }
                                com.example.twopchat.StickerSupport.PACK_ATTACHMENT_TYPE -> {
                                    Row(
                                        modifier = Modifier
                                            .background(
                                                onSurfaceColor.copy(alpha = 0.07f),
                                                RoundedCornerShape(14.dp),
                                            )
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("🎭", fontSize = 32.sp)
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = if (appLanguage == "Русский") "Стикерпак добавлен" else "Sticker pack added",
                                                color = textColor,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = com.example.twopchat.StickerSupport
                                                    .packIdFromArchiveFileName(msg.attachmentName.orEmpty())
                                                    .orEmpty(),
                                                color = textColor.copy(alpha = 0.65f),
                                                fontSize = 11.sp,
                                            )
                                        }
                                    }
                                }
                                "ALBUM" -> {
                                    MediaAlbumGridBubble(
                                        msg = msg,
                                        messages = messages,
                                        selectedMessages = selectedMessages,
                                        isSelectMode = isSelectMode,
                                        isTyping = isTyping,
                                        peerName = peerName,
                                        appLanguage = appLanguage,
                                        primaryColor = primaryColor,
                                        textColor = textColor,
                                        linkColor = linkColor,
                                        bubbleShape = bubbleShape,
                                        index = index,
                                        onOpenImages = onOpenImages,
                                        onOpenVideo = onOpenVideo,
                                        onShowOptions = onShowOptions
                                    )
                                }
                                "IMAGE" -> {
                                    val bitmap = rememberSampledImage(msg.attachmentUri)
                                    val attachmentAvailable = isAttachmentAvailable(msg.attachmentUri)
                                    val progressInfo = com.example.twopchat.P2PMessageRelay.fileProgressStates["$peerName:${msg.id}"]
                                        ?: com.example.twopchat.P2PMessageRelay.fileProgressStates[msg.id]
                                        ?: msg.attachmentName?.let { com.example.twopchat.P2PMessageRelay.fileProgressStates["$peerName:$it"] ?: com.example.twopchat.P2PMessageRelay.fileProgressStates[it] }
                                    val isTransferring = progressInfo?.state ==
                                        com.example.twopchat.P2PMessageRelay.FileTransferState.TRANSFERRING
                                    val isCancelled = progressInfo?.state ==
                                        com.example.twopchat.P2PMessageRelay.FileTransferState.CANCELLED ||
                                        msg.status.equals("CANCELLED", ignoreCase = true)
                                    val hasFailed = progressInfo?.state ==
                                        com.example.twopchat.P2PMessageRelay.FileTransferState.FAILED ||
                                        msg.status.equals("FAILED", ignoreCase = true)
                                    val isRemoved = !isTransferring && !isCancelled && !hasFailed &&
                                        !attachmentAvailable

                                    val isDefaultText = msg.text.isBlank() ||
                                            msg.text.startsWith("Sent an image") ||
                                            msg.text.startsWith("Captured a photo") ||
                                            msg.text.equals("Фотография", ignoreCase = true) ||
                                            msg.text.equals("Отправлена фотография", ignoreCase = true)

                                    val hasCaption = !isDefaultText

                                    if (bitmap != null || isTransferring || isRemoved || isCancelled || hasFailed) {
                                        Column(
                                            modifier = Modifier.widthIn(max = 280.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .then(
                                                        if (bitmap == null) {
                                                            Modifier.height(140.dp)
                                                        } else {
                                                            Modifier
                                                        },
                                                    )
                                                    .heightIn(max = 320.dp)
                                                    .clip(
                                                        if (hasCaption) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
                                                        else bubbleShape
                                                    )
                                                    .combinedClickable(
                                                        enabled = !isTransferring &&
                                                            !isCancelled &&
                                                            !hasFailed &&
                                                            attachmentAvailable,
                                                        onClick = {
                                                            if (isSelectMode) {
                                                                if (selectedMessages.contains(msg)) {
                                                                    selectedMessages.remove(msg)
                                                                } else {
                                                                    selectedMessages.add(msg)
                                                                }
                                                            } else {
                                                                val allImages = messages.filter { it.attachmentType == "IMAGE" && !it.attachmentUri.isNullOrBlank() }.map { it.attachmentUri!! }
                                                                val clickedUri = msg.attachmentUri
                                                                val clickedIndex = if (clickedUri != null) allImages.indexOf(clickedUri) else -1
                                                                if (clickedIndex != -1) {
                                                                    onOpenImages(allImages, clickedIndex)
                                                                } else if (clickedUri != null) {
                                                                    onOpenImages(listOf(clickedUri), 0)
                                                                }
                                                            }
                                                        },
                                                        onLongClick = {
                                                            if (isSelectMode) {
                                                                if (selectedMessages.contains(msg)) {
                                                                    selectedMessages.remove(msg)
                                                                } else {
                                                                    selectedMessages.add(msg)
                                                                }
                                                            } else {
                                                                onShowOptions(msg)
                                                            }
                                                        }
                                                    )
                                            ) {
                                                if (bitmap != null) {
                                                    Image(
                                                        bitmap = bitmap.asImageBitmap(),
                                                        contentDescription = "Image attachment",
                                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                } else {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(200.dp)
                                                            .background(Color.Gray.copy(alpha = 0.2f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(id = com.example.twopchat.R.drawable.ic_attach_file),
                                                            contentDescription = "Loading",
                                                            tint = textColor.copy(alpha = 0.5f),
                                                            modifier = Modifier.size(36.dp)
                                                        )
                                                    }
                                                }

                                                if (isTransferring && progressInfo != null) {
                                                    val pct = if (progressInfo.totalBytes > 0L) {
                                                        (progressInfo.bytesTransferred * 100 / progressInfo.totalBytes).toInt()
                                                    } else 0
                                                    val speedStr = if (progressInfo.speedKbps >= 1024) {
                                                        String.format(java.util.Locale.US, "%.1f MB/s", progressInfo.speedKbps / 1024.0)
                                                    } else {
                                                        "${progressInfo.speedKbps.toInt()} KB/s"
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(Color.Black.copy(alpha = 0.55f))
                                                            .padding(12.dp),
                                                        contentAlignment = Alignment.BottomCenter
                                                    ) {
                                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                            Text(
                                                                text = "$pct% • $speedStr",
                                                                color = Color.White,
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            Spacer(modifier = Modifier.height(6.dp))
                                                            androidx.compose.material3.LinearProgressIndicator(
                                                                progress = {
                                                                    if (progressInfo.totalBytes > 0L) {
                                                                        (progressInfo.bytesTransferred.toFloat() / progressInfo.totalBytes.toFloat()).coerceIn(0f, 1f)
                                                                    } else 0f
                                                                },
                                                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                                                color = Color.White,
                                                                trackColor = Color.White.copy(alpha = 0.3f)
                                                            )
                                                        }
                                                    }
                                                    if (msg.isMe) {
                                                        Text(
                                                            text = "×",
                                                            color = Color.White,
                                                            fontSize = 24.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier
                                                                .align(Alignment.TopEnd)
                                                                .background(
                                                                    Color.Black.copy(alpha = 0.55f),
                                                                    CircleShape,
                                                                )
                                                                .clickable {
                                                                    onCancelFileTransfer(msg)
                                                                }
                                                                .padding(horizontal = 9.dp, vertical = 3.dp),
                                                        )
                                                    }
                                                }
                                                if (isRemoved || isCancelled || hasFailed) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(Color.Black.copy(alpha = 0.18f)),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text(
                                                            text = if (isCancelled) {
                                                                if (appLanguage == "Русский") "Передача отменена" else "Transfer cancelled"
                                                            } else if (hasFailed) {
                                                                if (appLanguage == "Русский") "Ошибка передачи" else "Transfer failed"
                                                            } else {
                                                                if (appLanguage == "Русский") "Файл удалён" else "File removed"
                                                            },
                                                            color = textColor.copy(alpha = 0.75f),
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                        )
                                                    }
                                                }

                                                // If NO caption, floating timestamp pill in bottom-right corner over the photo
                                                if (!hasCaption) {
                                                    val hasIncomingAfter = if (index < messages.size - 1) {
                                                        messages.subList(index + 1, messages.size).any { !it.isMe }
                                                    } else false
                                                    val isRead = hasIncomingAfter || msg.status?.startsWith("READ") == true || isTyping || peerName == "Saved Messages"
                                                    val isPending = msg.status?.startsWith("PENDING") == true

                                                    Row(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomEnd)
                                                            .padding(6.dp)
                                                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                                            .padding(horizontal = 7.dp, vertical = 3.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Text(
                                                            text = MessageTimestampFormatter.format(msg, appLanguage),
                                                            fontSize = 10.sp,
                                                            color = Color.White.copy(alpha = 0.95f),
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                        if (msg.isMe) {
                                                            if (isPending) {
                                                                androidx.compose.material3.CircularProgressIndicator(
                                                                    modifier = Modifier.size(10.dp),
                                                                    color = Color.White.copy(alpha = 0.8f),
                                                                    strokeWidth = 1.2.dp
                                                                )
                                                            } else {
                                                                Icon(
                                                                    painter = painterResource(id = if (isRead) com.example.twopchat.R.drawable.ic_msg_double_check else com.example.twopchat.R.drawable.ic_msg_single_check),
                                                                    contentDescription = if (isRead) "Read" else "Sent",
                                                                    tint = if (isRead) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.95f),
                                                                    modifier = Modifier.size(13.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            // If HAS caption, render clean caption container at bottom of card
                                            if (hasCaption) {
                                                val hasIncomingAfter = if (index < messages.size - 1) {
                                                    messages.subList(index + 1, messages.size).any { !it.isMe }
                                                } else false
                                                val isRead = hasIncomingAfter || msg.status?.startsWith("READ") == true || isTyping || peerName == "Saved Messages"
                                                val isPending = msg.status?.startsWith("PENDING") == true

                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                                                ) {
                                                    LinkifiedText(
                                                        text = msg.text,
                                                        textColor = textColor,
                                                        linkColor = linkColor,
                                                        fontSize = 15.sp,
                                                        lineHeight = 20.sp
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Row(
                                                        modifier = Modifier.align(Alignment.End),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Text(
                                                            text = MessageTimestampFormatter.format(msg, appLanguage),
                                                            fontSize = 10.sp,
                                                            color = textColor.copy(alpha = 0.75f),
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                        if (msg.isMe) {
                                                            if (isPending) {
                                                                androidx.compose.material3.CircularProgressIndicator(
                                                                    modifier = Modifier.size(10.dp),
                                                                    color = textColor.copy(alpha = 0.6f),
                                                                    strokeWidth = 1.2.dp
                                                                )
                                                            } else {
                                                                Icon(
                                                                    painter = painterResource(id = if (isRead) com.example.twopchat.R.drawable.ic_msg_double_check else com.example.twopchat.R.drawable.ic_msg_single_check),
                                                                    contentDescription = if (isRead) "Read" else "Sent",
                                                                    tint = if (isRead) {
                                                                        if (msg.isMe && primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color(0xFF64B5F6)
                                                                    } else textColor.copy(alpha = 0.75f),
                                                                    modifier = Modifier.size(13.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                "VIDEO" -> {
                                    val completedThumbnail = rememberVideoThumbnail(msg.attachmentUri)
                                    val progressInfo = com.example.twopchat.P2PMessageRelay.fileProgressStates["$peerName:${msg.id}"]
                                        ?: com.example.twopchat.P2PMessageRelay.fileProgressStates[msg.id]
                                        ?: msg.attachmentName?.let { com.example.twopchat.P2PMessageRelay.fileProgressStates["$peerName:$it"] ?: com.example.twopchat.P2PMessageRelay.fileProgressStates[it] }
                                    val transferPreview = com.example.twopchat.P2PMessageRelay.fileTransferPreviews["$peerName:${msg.id}"]
                                        ?: com.example.twopchat.P2PMessageRelay.fileTransferPreviews[msg.id]
                                    val thumbnail = completedThumbnail ?: transferPreview
                                    val attachmentAvailable = isAttachmentAvailable(msg.attachmentUri)
                                    val isTransferring = progressInfo?.state ==
                                        com.example.twopchat.P2PMessageRelay.FileTransferState.TRANSFERRING
                                    val isCancelled = progressInfo?.state ==
                                        com.example.twopchat.P2PMessageRelay.FileTransferState.CANCELLED ||
                                        msg.status.equals("CANCELLED", ignoreCase = true)
                                    val hasFailed = progressInfo?.state ==
                                        com.example.twopchat.P2PMessageRelay.FileTransferState.FAILED ||
                                        msg.status.equals("FAILED", ignoreCase = true)
                                    val isRemoved = !isTransferring && !isCancelled && !hasFailed &&
                                        !attachmentAvailable

                                    val isDefaultText = msg.text.isBlank() ||
                                            msg.text.startsWith("Sent a video") ||
                                            msg.text.equals("Видеозапись", ignoreCase = true) ||
                                            msg.text.equals("Отправлено видео", ignoreCase = true)

                                    val hasCaption = !isDefaultText

                                    Column(
                                        modifier = Modifier.widthIn(max = 280.dp)
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(200.dp)
                                                .clip(
                                                    if (hasCaption) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
                                                    else bubbleShape
                                                )
                                                .combinedClickable(
                                                    enabled = !isTransferring &&
                                                        !isCancelled &&
                                                        !hasFailed &&
                                                        attachmentAvailable,
                                                    onClick = {
                                                        if (isSelectMode) {
                                                            if (selectedMessages.contains(msg)) {
                                                                selectedMessages.remove(msg)
                                                            } else {
                                                                selectedMessages.add(msg)
                                                            }
                                                        } else {
                                                            msg.attachmentUri?.let(onOpenVideo)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (isSelectMode) {
                                                            if (selectedMessages.contains(msg)) {
                                                                selectedMessages.remove(msg)
                                                            } else {
                                                                selectedMessages.add(msg)
                                                            }
                                                        } else {
                                                            onShowOptions(msg)
                                                        }
                                                    }
                                                )
                                        ) {
                                            if (thumbnail != null) {
                                                Image(
                                                    bitmap = thumbnail.asImageBitmap(),
                                                    contentDescription = "Video attachment",
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .then(
                                                            if (transferPreview != null && completedThumbnail == null) {
                                                                Modifier.blur(10.dp)
                                                            } else {
                                                                Modifier
                                                            },
                                                        )
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.2f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_attach_file),
                                                        contentDescription = "Video",
                                                        tint = textColor.copy(alpha = 0.5f),
                                                        modifier = Modifier.size(40.dp)
                                                    )
                                                }
                                            }
                                            if (!isTransferring && !isCancelled && !hasFailed && !isRemoved) {
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
                                            if (isTransferring && progressInfo != null) {
                                                val pct = if (progressInfo.totalBytes > 0L) {
                                                    (progressInfo.bytesTransferred * 100 / progressInfo.totalBytes).toInt()
                                                } else 0
                                                val speedStr = if (progressInfo.speedKbps >= 1024) {
                                                    String.format(java.util.Locale.US, "%.1f MB/s", progressInfo.speedKbps / 1024.0)
                                                } else {
                                                    "${progressInfo.speedKbps.toInt()} KB/s"
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.55f))
                                                        .padding(12.dp),
                                                    contentAlignment = Alignment.BottomCenter
                                                ) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text(
                                                            text = "$pct% • $speedStr",
                                                            color = Color.White,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                        androidx.compose.material3.LinearProgressIndicator(
                                                            progress = {
                                                                if (progressInfo.totalBytes > 0L) {
                                                                    (progressInfo.bytesTransferred.toFloat() / progressInfo.totalBytes.toFloat()).coerceIn(0f, 1f)
                                                                } else 0f
                                                            },
                                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                                            color = Color.White,
                                                            trackColor = Color.White.copy(alpha = 0.3f)
                                                        )
                                                    }
                                                }
                                                if (msg.isMe) {
                                                    Text(
                                                        text = "×",
                                                        color = Color.White,
                                                        fontSize = 24.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier
                                                            .align(Alignment.TopEnd)
                                                            .padding(8.dp)
                                                            .background(
                                                                Color.Black.copy(alpha = 0.6f),
                                                                CircleShape,
                                                            )
                                                            .clickable {
                                                                onCancelFileTransfer(msg)
                                                            }
                                                            .padding(horizontal = 9.dp, vertical = 3.dp),
                                                    )
                                                }
                                            } else if (isCancelled || hasFailed || isRemoved) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.55f)),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        text = if (isCancelled) {
                                                            if (appLanguage == "Русский") "Передача отменена" else "Transfer cancelled"
                                                        } else if (hasFailed) {
                                                            if (appLanguage == "Русский") "Ошибка передачи" else "Transfer failed"
                                                        } else {
                                                            if (appLanguage == "Русский") "Файл удалён" else "File removed"
                                                        },
                                                        color = Color.White,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                    )
                                                }
                                            }

                                            // If NO caption, floating timestamp pill in bottom-right corner over the video
                                            if (!hasCaption) {
                                                val hasIncomingAfter = if (index < messages.size - 1) {
                                                    messages.subList(index + 1, messages.size).any { !it.isMe }
                                                } else false
                                                val isRead = hasIncomingAfter || msg.status?.startsWith("READ") == true || isTyping || peerName == "Saved Messages"
                                                val isPending = msg.status?.startsWith("PENDING") == true

                                                Row(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(6.dp)
                                                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        text = MessageTimestampFormatter.format(msg, appLanguage),
                                                        fontSize = 10.sp,
                                                        color = Color.White.copy(alpha = 0.95f),
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    if (msg.isMe) {
                                                        if (isPending) {
                                                            androidx.compose.material3.CircularProgressIndicator(
                                                                modifier = Modifier.size(10.dp),
                                                                color = Color.White.copy(alpha = 0.8f),
                                                                strokeWidth = 1.2.dp
                                                            )
                                                        } else {
                                                            Icon(
                                                                painter = painterResource(id = if (isRead) com.example.twopchat.R.drawable.ic_msg_double_check else com.example.twopchat.R.drawable.ic_msg_single_check),
                                                                contentDescription = if (isRead) "Read" else "Sent",
                                                                tint = if (isRead) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.95f),
                                                                modifier = Modifier.size(13.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        // If HAS caption, render clean caption container at bottom of card
                                        if (hasCaption) {
                                            val hasIncomingAfter = if (index < messages.size - 1) {
                                                messages.subList(index + 1, messages.size).any { !it.isMe }
                                            } else false
                                            val isRead = hasIncomingAfter || msg.status?.startsWith("READ") == true || isTyping || peerName == "Saved Messages"
                                            val isPending = msg.status?.startsWith("PENDING") == true

                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                                            ) {
                                                LinkifiedText(
                                                    text = msg.text,
                                                    textColor = textColor,
                                                    linkColor = linkColor,
                                                    fontSize = 15.sp,
                                                    lineHeight = 20.sp
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Row(
                                                    modifier = Modifier.align(Alignment.End),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        text = MessageTimestampFormatter.format(msg, appLanguage),
                                                        fontSize = 10.sp,
                                                        color = textColor.copy(alpha = 0.75f),
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    if (msg.isMe) {
                                                        if (isPending) {
                                                            androidx.compose.material3.CircularProgressIndicator(
                                                                modifier = Modifier.size(10.dp),
                                                                color = textColor.copy(alpha = 0.6f),
                                                                strokeWidth = 1.2.dp
                                                            )
                                                        } else {
                                                            Icon(
                                                                painter = painterResource(id = if (isRead) com.example.twopchat.R.drawable.ic_msg_double_check else com.example.twopchat.R.drawable.ic_msg_single_check),
                                                                contentDescription = if (isRead) "Read" else "Sent",
                                                                tint = if (isRead) {
                                                                    if (msg.isMe && primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color(0xFF64B5F6)
                                                                } else textColor.copy(alpha = 0.75f),
                                                                modifier = Modifier.size(13.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                "FILE" -> {
                                    val attachmentAvailable = isAttachmentAvailable(msg.attachmentUri)
                                    val progressInfo = com.example.twopchat.P2PMessageRelay.fileProgressStates["$peerName:${msg.id}"]
                                        ?: com.example.twopchat.P2PMessageRelay.fileProgressStates[msg.id]
                                        ?: msg.attachmentName?.let { com.example.twopchat.P2PMessageRelay.fileProgressStates["$peerName:$it"] ?: com.example.twopchat.P2PMessageRelay.fileProgressStates[it] }
                                    
                                    val isTransferring = progressInfo?.state ==
                                        com.example.twopchat.P2PMessageRelay.FileTransferState.TRANSFERRING
                                    val isCancelled = progressInfo?.state ==
                                        com.example.twopchat.P2PMessageRelay.FileTransferState.CANCELLED ||
                                        msg.status.equals("CANCELLED", ignoreCase = true)
                                    val hasFailed = progressInfo?.state ==
                                        com.example.twopchat.P2PMessageRelay.FileTransferState.FAILED ||
                                        msg.status.equals("FAILED", ignoreCase = true)

                                    Column {
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
                                                val subtext = if (isTransferring && progressInfo != null) {
                                                    val pct = if (progressInfo.totalBytes > 0L) {
                                                        (progressInfo.bytesTransferred * 100 / progressInfo.totalBytes).toInt()
                                                    } else 0
                                                    val speedStr = if (progressInfo.speedKbps >= 1024) {
                                                        String.format(java.util.Locale.US, "%.1f MB/s", progressInfo.speedKbps / 1024.0)
                                                    } else {
                                                        "${progressInfo.speedKbps.toInt()} KB/s"
                                                    }
                                                    "$pct% • $speedStr"
                                                } else if (isCancelled) {
                                                    if (appLanguage == "Русский") "Передача отменена" else "Transfer cancelled"
                                                } else if (hasFailed) {
                                                    if (appLanguage == "Русский") "Ошибка передачи" else "Transfer failed"
                                                } else if (!attachmentAvailable) {
                                                    if (appLanguage == "Русский") "Файл удалён" else "File removed"
                                                } else {
                                                    "Encrypted Document"
                                                }
                                                Text(
                                                    text = subtext,
                                                    color = if (isTransferring) (if (msg.isMe) Color.White else primaryColor) else textColor.copy(alpha = 0.7f),
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isTransferring) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        }
                                        if (isTransferring && progressInfo != null) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                androidx.compose.material3.LinearProgressIndicator(
                                                    progress = {
                                                        if (progressInfo.totalBytes > 0L) {
                                                            (progressInfo.bytesTransferred.toFloat() / progressInfo.totalBytes.toFloat()).coerceIn(0f, 1f)
                                                        } else 0f
                                                    },
                                                    modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                                                    color = if (msg.isMe) Color.White else primaryColor,
                                                    trackColor = textColor.copy(alpha = 0.2f)
                                                )
                                                if (msg.isMe) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = if (appLanguage == "Русский") "Отменить" else "Cancel",
                                                        color = if (msg.isMe) Color.White else primaryColor,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.clickable {
                                                            onCancelFileTransfer(msg)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                "VOICE" -> {
                                    if (isAttachmentAvailable(msg.attachmentUri)) {
                                        VoiceMessagePlayer(
                                            filePath = msg.attachmentUri,
                                            isMine = msg.isMe,
                                            primaryColor = primaryColor,
                                            contentColor = textColor,
                                        )
                                    } else {
                                        Text(
                                            text = if (appLanguage == "Русский") {
                                                "Голосовой файл удалён"
                                            } else {
                                                "Voice file removed"
                                            },
                                            color = textColor.copy(alpha = 0.7f),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
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
                                            LinkifiedText(
                                                text = msg.text,
                                                textColor = textColor,
                                                linkColor = linkColor,
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
                                    if (isOnlyEmoji) {
                                        Text(
                                            text = msg.text.trim(),
                                            fontSize = 72.sp,
                                            lineHeight = 80.sp
                                        )
                                    } else {
                                        LinkifiedText(
                                            text = msg.text,
                                            textColor = textColor,
                                            linkColor = linkColor,
                                            fontSize = 15.sp,
                                            lineHeight = 20.sp
                                        )
                                        if (linkPreviewsEnabled && detectedUrl != null) {
                                            LinkPreviewCard(
                                                url = detectedUrl,
                                                isMe = msg.isMe,
                                                primaryColor = primaryColor,
                                                onSurfaceColor = onSurfaceColor,
                                                surfaceColor = surfaceColor
                                            )
                                        }
                                    }
                                }
                            }
                            if (msg.attachmentType != "IMAGE" && msg.attachmentType != "VIDEO" && msg.attachmentType != "ALBUM" && msg.albumMediaUris.isEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text(
                                        text = MessageTimestampFormatter.format(msg, appLanguage),
                                        color = (if (isOnlyEmoji || isSticker || isGif) {
                                            onSurfaceColor.copy(alpha = 0.5f)
                                        } else if (msg.isMe) {
                                            if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.65f)
                                        } else onSurfaceColor.copy(alpha = 0.5f)),
                                        fontSize = 9.sp
                                    )
                                    if (msg.isMe) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        val hasIncomingAfter = if (index < messages.size - 1) {
                                            messages.subList(index + 1, messages.size).any { !it.isMe }
                                        } else false
                                        
                                        val isRead = hasIncomingAfter || msg.status?.startsWith("READ") == true || isTyping || peerName == "Saved Messages"
                                        val isPending = msg.status?.startsWith("PENDING") == true
                                        
                                        val statusColor = if (isOnlyEmoji || isSticker || isGif) {
                                            onSurfaceVariant.copy(alpha = 0.5f)
                                        } else if (msg.isMe) {
                                            if (primaryColor == com.example.twopchat.theme.MintGreen) {
                                                StealthBlack.copy(alpha = 0.45f)
                                            } else {
                                                Color.White.copy(alpha = 0.55f)
                                            }
                                        } else {
                                            onSurfaceVariant.copy(alpha = 0.5f)
                                        }
                                        
                                        if (isPending) {
                                            Text(
                                                text = "🕒",
                                                color = statusColor,
                                                fontSize = 9.sp
                                            )
                                        } else if (isRead) {
                                            Icon(
                                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_msg_double_check),
                                                contentDescription = "Read",
                                                tint = statusColor,
                                                modifier = Modifier.height(11.dp).width(16.dp)
                                            )
                                        } else {
                                            Icon(
                                                painter = painterResource(id = com.example.twopchat.R.drawable.ic_msg_single_check),
                                                contentDescription = "Sent",
                                                tint = statusColor,
                                                modifier = Modifier.height(11.dp).width(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            if (msg.reactions.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                        msg.reactions.forEach { (emoji, senders) ->
                                            val hasLocalUserReacted = senders.contains("Me") || senders.contains("me")
                                            val chipBg = if (hasLocalUserReacted) {
                                                if (isOnlyEmoji) {
                                                    primaryColor
                                                } else if (msg.isMe) {
                                                    Color.White
                                                } else {
                                                    primaryColor
                                                }
                                            } else {
                                                if (isOnlyEmoji) {
                                                    onSurfaceColor.copy(alpha = 0.08f)
                                                } else if (msg.isMe) {
                                                    if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.25f)
                                                } else {
                                                    primaryColor.copy(alpha = 0.12f)
                                                }
                                            }
                                            
                                            val contentColor = if (hasLocalUserReacted) {
                                                if (isOnlyEmoji) {
                                                    Color.White
                                                } else if (msg.isMe) {
                                                    if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else primaryColor
                                                } else {
                                                    Color.White
                                                }
                                            } else {
                                                if (isOnlyEmoji) {
                                                    onSurfaceColor.copy(alpha = 0.85f)
                                                } else if (msg.isMe) {
                                                    if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color.White
                                                } else {
                                                    onSurfaceColor.copy(alpha = 0.85f)
                                                }
                                            }

                                            Surface(
                                                shape = CircleShape,
                                                color = chipBg,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(text = emoji, fontSize = 11.sp)
                                                    
                                                    senders.forEach { sender ->
                                                        val avatar = if (sender.equals("Me", ignoreCase = true)) {
                                                            myAvatarBitmap
                                                        } else {
                                                            com.example.twopchat.P2PMessageRelay.peerAvatars[peerName]
                                                        }
                                                        
                                                        if (avatar != null) {
                                                            Image(
                                                                bitmap = avatar.asImageBitmap(),
                                                                contentDescription = "Avatar",
                                                                modifier = Modifier
                                                                    .size(16.dp)
                                                                    .clip(CircleShape)
                                                            )
                                                        } else {
                                                            val initials = if (sender.equals("Me", ignoreCase = true)) "M" else sender.take(1).uppercase()
                                                            Box(
                                                                contentAlignment = Alignment.Center,
                                                                modifier = Modifier
                                                                    .size(16.dp)
                                                                    .background(contentColor.copy(alpha = 0.2f), shape = CircleShape)
                                                            ) {
                                                                Text(
                                                                    text = initials,
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = contentColor
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
    }

@Composable
private fun GifMessageContent(
    filePath: String?,
    fallbackText: String,
    bubbleShape: RoundedCornerShape,
    onLongClick: () -> Unit,
) {
    val validPath = remember(filePath) {
        filePath?.takeIf {
            com.example.twopchat.GifStorageManager.validateGif(java.io.File(it)) != null
        }
    }
    val drawable by produceState<Drawable?>(initialValue = null, validPath) {
        value = if (validPath != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(java.io.File(validPath)),
                    ) { decoder, info, _ ->
                        val width = info.size.width.coerceAtLeast(1)
                        val height = info.size.height.coerceAtLeast(1)
                        val scale = minOf(640f / width, 640f / height, 1f)
                        decoder.setTargetSize(
                            (width * scale).toInt().coerceAtLeast(1),
                            (height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                }.getOrNull()
            }
        } else {
            null
        }
    }
    val movie by produceState<Movie?>(initialValue = null, validPath) {
        value = if (validPath != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                Movie.decodeFile(validPath)
            }
        } else {
            null
        }
    }
    DisposableEffect(drawable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val animated = drawable as? AnimatedImageDrawable
            animated?.start()
            onDispose { animated?.stop() }
        } else {
            onDispose {}
        }
    }
    var frameTimeMs by remember(validPath) { mutableStateOf(0L) }
    LaunchedEffect(movie) {
        val startedAt = withFrameMillis { it }
        while (isActive && movie != null) {
            frameTimeMs = withFrameMillis { it } - startedAt
        }
    }

    Box(
        modifier = Modifier
            .size(width = 260.dp, height = 220.dp)
            .clip(bubbleShape)
            .background(Color.Black.copy(alpha = 0.08f))
            .combinedClickable(onClick = {}, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            drawable != null -> AndroidView(
                factory = { context ->
                    android.widget.ImageView(context).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { it.setImageDrawable(drawable) },
                modifier = Modifier.fillMaxSize(),
            )
            movie != null -> androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val gif = movie ?: return@Canvas
                val duration = gif.duration().takeIf { it > 0 } ?: 1_000
                gif.setTime((frameTimeMs % duration).toInt())
                drawIntoCanvas { composeCanvas ->
                    val scale = maxOf(
                        size.width / gif.width().coerceAtLeast(1),
                        size.height / gif.height().coerceAtLeast(1),
                    )
                    val native = composeCanvas.nativeCanvas
                    native.save()
                    native.scale(scale, scale)
                    gif.draw(native, 0f, 0f)
                    native.restore()
                }
            }
            else -> Text(
                text = if (validPath == null) fallbackText.ifBlank { "GIF" } else "GIF…",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        Text(
            text = "GIF",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun StickerMessageContent(
    filePath: String?,
    fallbackEmoji: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val drawable = remember(filePath) {
        filePath
            ?.let { java.io.File(it) }
            ?.takeIf { com.example.twopchat.StickerSupport.validateWebP(it) != null }
            ?.let { file ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                    } else {
                        @Suppress("DEPRECATION")
                        Drawable.createFromPath(file.absolutePath)
                    }
                }.getOrNull()
            }
    }
    var pressed by remember(filePath) { mutableStateOf(false) }
    val stickerScale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "stickerBounce",
    )
    LaunchedEffect(pressed) {
        if (pressed) {
            delay(110)
            pressed = false
        }
    }
    DisposableEffect(drawable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val animatedDrawable = drawable as? AnimatedImageDrawable
            animatedDrawable?.start()
            onDispose { animatedDrawable?.stop() }
        } else {
            onDispose {}
        }
    }

    Box(
        modifier = Modifier
            .size(210.dp)
            .scale(stickerScale)
            .combinedClickable(
                onClick = {
                    pressed = true
                    onClick()
                },
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (drawable != null) {
            AndroidView(
                factory = { context ->
                    android.widget.ImageView(context).apply {
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                update = { imageView -> imageView.setImageDrawable(drawable) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = fallbackEmoji.ifBlank { "🎭" },
                fontSize = 72.sp,
                lineHeight = 80.sp,
            )
        }
    }
}

private val URL_PATTERN = Pattern.compile(
    "(?:^|[\\s])((?:https?://|www\\.)[\\w\\-_]+(?:\\.[\\w\\-_]+)+(?:[\\w\\-\\.,@?^=%&:/~\\+#]*[\\w\\-\\@?^=%&/~\\+#])?)",
    Pattern.CASE_INSENSITIVE
)

@Composable
internal fun LinkifiedText(
    text: String,
    textColor: Color,
    linkColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier
) {
    val annotatedString = remember(text, textColor, linkColor) {
        buildAnnotatedString {
            val matcher = URL_PATTERN.matcher(text)
            var lastMatchEnd = 0
            while (matcher.find()) {
                val start = matcher.start(1)
                val end = matcher.end(1)
                
                // Append text before link
                append(text.substring(lastMatchEnd, start))
                
                val originalUrl = text.substring(start, end)
                val destinationUrl = if (!originalUrl.startsWith("http://", ignoreCase = true) && 
                                          !originalUrl.startsWith("https://", ignoreCase = true)) {
                    "https://$originalUrl"
                } else {
                    originalUrl
                }
                
                val linkStyles = TextLinkStyles(
                    style = SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.Bold
                    )
                )
                val linkAnnotation = LinkAnnotation.Url(
                    url = destinationUrl,
                    styles = linkStyles
                )
                
                val linkStart = this.length
                append(originalUrl)
                val linkEnd = this.length
                
                addLink(
                    url = linkAnnotation,
                    start = linkStart,
                    end = linkEnd
                )
                
                lastMatchEnd = end
            }
            if (lastMatchEnd < text.length) {
                append(text.substring(lastMatchEnd))
            }
        }
    }

    Text(
        text = annotatedString,
        color = textColor,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        modifier = modifier
    )
}

private fun isEmojiCodePoint(codePoint: Int): Boolean {
    return (codePoint in 0x1F300..0x1F5FF) || // Misc Symbols & Pictographs
           (codePoint in 0x1F600..0x1F64F) || // Emoticons
           (codePoint in 0x1F680..0x1F6FF) || // Transport & Map
           (codePoint in 0x2600..0x26FF) ||   // Misc Symbols
           (codePoint in 0x2700..0x27BF) ||   // Dingbats
           (codePoint in 0x1F900..0x1F9FF) || // Supplemental Symbols & Pictographs
           (codePoint in 0x1FA70..0x1FAFF) || // Symbols & Pictographs Extended-A
           (codePoint in 0x1F1E6..0x1F1FF) || // Flags (Regional Indicators)
           (codePoint in 0xE0020..0xE007F) || // Tag characters (flag subregions)
           (codePoint in 0x1F000..0x1F02F) || // Mahjong
           (codePoint in 0x1F0A0..0x1F0FF) || // Playing cards
           (codePoint in 0x2190..0x21FF) ||   // Arrows (some are emojis)
           (codePoint in 0x2300..0x23FF) ||   // Misc Technical
           (codePoint in 0x2900..0x297F) ||   // Supplemental Arrows
           (codePoint in 0x2B00..0x2BFF) ||   // Misc Symbols & Arrows
           (codePoint in 0x3030..0x303D) ||
           (codePoint in 0x3297..0x3299) ||
           (codePoint == 0x203C || codePoint == 0x2049) ||
           (codePoint in 0x2050..0x205F) ||
           (codePoint in 0x2000..0x206F && codePoint == 0x200D) // ZWJ
}

private fun isSingleEmoji(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    
    val boundary = java.text.BreakIterator.getCharacterInstance()
    boundary.setText(trimmed)
    var graphemeCount = 0
    var start = boundary.first()
    var end = boundary.next()
    var singleGrapheme = ""
    while (end != java.text.BreakIterator.DONE) {
        graphemeCount++
        if (graphemeCount == 1) {
            singleGrapheme = trimmed.substring(start, end)
        }
        start = end
        end = boundary.next()
    }
    
    if (graphemeCount != 1) return false
    
    var i = 0
    while (i < singleGrapheme.length) {
        val codePoint = singleGrapheme.codePointAt(i)
        if (isEmojiCodePoint(codePoint)) {
            return true
        }
        i += Character.charCount(codePoint)
    }
    
    return false
}

@Composable
internal fun LinkPreviewCard(
    url: String,
    isMe: Boolean,
    primaryColor: Color,
    onSurfaceColor: Color,
    surfaceColor: Color
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val metadataState = remember(url) { mutableStateOf<LinkPreviewMetadata?>(null) }

    LaunchedEffect(url) {
        metadataState.value = LinkPreviewFetcher.fetchPreview(url)
    }

    val previewData = metadataState.value ?: return

    val cardBg = if (isMe) {
        Color.White.copy(alpha = 0.15f)
    } else {
        onSurfaceColor.copy(alpha = 0.06f)
    }

    val titleColor = if (isMe) Color.White else onSurfaceColor
    val descColor = if (isMe) Color.White.copy(alpha = 0.8f) else onSurfaceColor.copy(alpha = 0.7f)
    val siteColor = if (isMe) Color.White.copy(alpha = 0.9f) else primaryColor

    Spacer(modifier = Modifier.height(6.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .clickable {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(previewData.url))
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
            .padding(10.dp)
    ) {
        if (!previewData.siteName.isNullOrBlank()) {
            Text(
                text = "🌐  " + previewData.siteName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = siteColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
        }

        if (!previewData.title.isNullOrBlank()) {
            Text(
                text = previewData.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        if (!previewData.description.isNullOrBlank() && previewData.description != previewData.title) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = previewData.description,
                fontSize = 11.sp,
                color = descColor,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                lineHeight = 15.sp
            )
        }

        val bitmap = rememberNetworkImage(previewData.imageUrl)
        if (bitmap != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Crop
                )
                val isVideo = previewData.url.contains("youtube.com", ignoreCase = true) || 
                              previewData.url.contains("youtu.be", ignoreCase = true)
                if (isVideo) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaAlbumGridBubble(
    msg: Message,
    messages: List<Message>,
    selectedMessages: MutableList<Message>,
    isSelectMode: Boolean,
    isTyping: Boolean,
    peerName: String,
    appLanguage: String,
    primaryColor: Color,
    textColor: Color,
    linkColor: Color,
    bubbleShape: androidx.compose.ui.graphics.Shape,
    index: Int,
    onOpenImages: (List<String>, Int) -> Unit,
    onOpenVideo: (String) -> Unit,
    onShowOptions: (Message) -> Unit
) {
    val uris = msg.albumMediaUris
    val types = msg.albumMediaTypes
    val hasCaption = msg.text.isNotBlank() &&
            !msg.text.startsWith("Sent an album") &&
            !msg.text.startsWith("Album") &&
            !msg.text.startsWith("Альбом") &&
            !msg.text.equals("Альбом", ignoreCase = true) &&
            !msg.text.equals("Медиаальбом", ignoreCase = true)

    Column(modifier = Modifier.widthIn(max = 280.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(
                    if (hasCaption) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
                    else bubbleShape
                )
        ) {
            when (uris.size) {
                2 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        AlbumItemCell(
                            uri = uris[0],
                            type = types.getOrNull(0) ?: "IMAGE",
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            allUris = uris,
                            cellIndex = 0,
                            msg = msg,
                            selectedMessages = selectedMessages,
                            isSelectMode = isSelectMode,
                            onOpenImages = onOpenImages,
                            onOpenVideo = onOpenVideo,
                            onShowOptions = onShowOptions
                        )
                        AlbumItemCell(
                            uri = uris[1],
                            type = types.getOrNull(1) ?: "IMAGE",
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            allUris = uris,
                            cellIndex = 1,
                            msg = msg,
                            selectedMessages = selectedMessages,
                            isSelectMode = isSelectMode,
                            onOpenImages = onOpenImages,
                            onOpenVideo = onOpenVideo,
                            onShowOptions = onShowOptions
                        )
                    }
                }
                3 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        AlbumItemCell(
                            uri = uris[0],
                            type = types.getOrNull(0) ?: "IMAGE",
                            modifier = Modifier.weight(1.2f).fillMaxHeight(),
                            allUris = uris,
                            cellIndex = 0,
                            msg = msg,
                            selectedMessages = selectedMessages,
                            isSelectMode = isSelectMode,
                            onOpenImages = onOpenImages,
                            onOpenVideo = onOpenVideo,
                            onShowOptions = onShowOptions
                        )
                        Column(
                            modifier = Modifier.weight(0.8f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            AlbumItemCell(
                                uri = uris[1],
                                type = types.getOrNull(1) ?: "IMAGE",
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                allUris = uris,
                                cellIndex = 1,
                                msg = msg,
                                selectedMessages = selectedMessages,
                                isSelectMode = isSelectMode,
                                onOpenImages = onOpenImages,
                                onOpenVideo = onOpenVideo,
                                onShowOptions = onShowOptions
                            )
                            AlbumItemCell(
                                uri = uris[2],
                                type = types.getOrNull(2) ?: "IMAGE",
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                allUris = uris,
                                cellIndex = 2,
                                msg = msg,
                                selectedMessages = selectedMessages,
                                isSelectMode = isSelectMode,
                                onOpenImages = onOpenImages,
                                onOpenVideo = onOpenVideo,
                                onShowOptions = onShowOptions
                            )
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            AlbumItemCell(
                                uri = uris.getOrNull(0) ?: "",
                                type = types.getOrNull(0) ?: "IMAGE",
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                allUris = uris,
                                cellIndex = 0,
                                msg = msg,
                                selectedMessages = selectedMessages,
                                isSelectMode = isSelectMode,
                                onOpenImages = onOpenImages,
                                onOpenVideo = onOpenVideo,
                                onShowOptions = onShowOptions
                            )
                            AlbumItemCell(
                                uri = uris.getOrNull(1) ?: "",
                                type = types.getOrNull(1) ?: "IMAGE",
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                allUris = uris,
                                cellIndex = 1,
                                msg = msg,
                                selectedMessages = selectedMessages,
                                isSelectMode = isSelectMode,
                                onOpenImages = onOpenImages,
                                onOpenVideo = onOpenVideo,
                                onShowOptions = onShowOptions
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            AlbumItemCell(
                                uri = uris.getOrNull(2) ?: "",
                                type = types.getOrNull(2) ?: "IMAGE",
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                allUris = uris,
                                cellIndex = 2,
                                msg = msg,
                                selectedMessages = selectedMessages,
                                isSelectMode = isSelectMode,
                                onOpenImages = onOpenImages,
                                onOpenVideo = onOpenVideo,
                                onShowOptions = onShowOptions
                            )
                            AlbumItemCell(
                                uri = uris.getOrNull(3) ?: "",
                                type = types.getOrNull(3) ?: "IMAGE",
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                allUris = uris,
                                cellIndex = 3,
                                msg = msg,
                                selectedMessages = selectedMessages,
                                isSelectMode = isSelectMode,
                                onOpenImages = onOpenImages,
                                onOpenVideo = onOpenVideo,
                                onShowOptions = onShowOptions
                            )
                        }
                    }
                }
            }

            if (!hasCaption) {
                val hasIncomingAfter = if (index < messages.size - 1) {
                    messages.subList(index + 1, messages.size).any { !it.isMe }
                } else false
                val isRead = hasIncomingAfter || msg.status?.startsWith("READ") == true || isTyping || peerName == "Saved Messages"
                val isPending = msg.status?.startsWith("PENDING") == true

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = MessageTimestampFormatter.format(msg, appLanguage),
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.95f),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                    if (msg.isMe) {
                        if (isPending) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                color = Color.White.copy(alpha = 0.8f),
                                strokeWidth = 1.2.dp
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = if (isRead) com.example.twopchat.R.drawable.ic_msg_double_check else com.example.twopchat.R.drawable.ic_msg_single_check),
                                contentDescription = if (isRead) "Read" else "Sent",
                                tint = if (isRead) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.95f),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }
        }

        if (hasCaption) {
            val hasIncomingAfter = if (index < messages.size - 1) {
                messages.subList(index + 1, messages.size).any { !it.isMe }
            } else false
            val isRead = hasIncomingAfter || msg.status?.startsWith("READ") == true || isTyping || peerName == "Saved Messages"
            val isPending = msg.status?.startsWith("PENDING") == true

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
            ) {
                LinkifiedText(
                    text = msg.text,
                    textColor = textColor,
                    linkColor = linkColor,
                    fontSize = 15.sp,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = MessageTimestampFormatter.format(msg, appLanguage),
                        fontSize = 11.sp,
                        color = textColor.copy(alpha = 0.6f)
                    )
                    if (msg.isMe) {
                        if (isPending) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(11.dp),
                                color = primaryColor,
                                strokeWidth = 1.2.dp
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = if (isRead) com.example.twopchat.R.drawable.ic_msg_double_check else com.example.twopchat.R.drawable.ic_msg_single_check),
                                contentDescription = if (isRead) "Read" else "Sent",
                                tint = if (isRead) Color(0xFF64B5F6) else textColor.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumItemCell(
    uri: String,
    type: String,
    modifier: Modifier,
    allUris: List<String>,
    cellIndex: Int,
    msg: Message,
    selectedMessages: MutableList<Message>,
    isSelectMode: Boolean,
    onOpenImages: (List<String>, Int) -> Unit,
    onOpenVideo: (String) -> Unit,
    onShowOptions: (Message) -> Unit
) {
    if (uri.isBlank()) return
    val isVideo = type == "VIDEO" || uri.endsWith(".mp4", ignoreCase = true) || uri.endsWith(".mov", ignoreCase = true)
    val imageBitmap = if (!isVideo) rememberSampledImage(uri) else null
    val videoThumbnail = if (isVideo) rememberVideoThumbnail(uri) else null
    val bitmap = imageBitmap ?: videoThumbnail

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(Color.DarkGray)
            .combinedClickable(
                onClick = {
                    if (isSelectMode) {
                        if (selectedMessages.contains(msg)) {
                            selectedMessages.remove(msg)
                        } else {
                            selectedMessages.add(msg)
                        }
                    } else {
                        if (isVideo) {
                            onOpenVideo(uri)
                        } else {
                            val imageUrisOnly = allUris.filter { !it.endsWith(".mp4", ignoreCase = true) && !it.endsWith(".mov", ignoreCase = true) }
                            val idx = imageUrisOnly.indexOf(uri).coerceAtLeast(0)
                            onOpenImages(imageUrisOnly.ifEmpty { allUris }, idx)
                        }
                    }
                },
                onLongClick = {
                    if (isSelectMode) {
                        if (selectedMessages.contains(msg)) {
                            selectedMessages.remove(msg)
                        } else {
                            selectedMessages.add(msg)
                        }
                    } else {
                        onShowOptions(msg)
                    }
                }
            )
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Album Item",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (isVideo) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp).padding(start = 2.dp)
                )
            }
        }
    }
}

