package com.example.twopchat.ui.chat

import android.graphics.Bitmap
import com.example.twopchat.media.*
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import com.example.twopchat.theme.MotionTokens
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.R
import com.example.twopchat.data.Localizations
import com.example.twopchat.theme.StealthBlack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.draw.scale
import java.util.regex.Pattern
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    isAnimatedMediaEnabled: Boolean,
    isSelected: Boolean,
    onSelectionChange: (Message, Boolean) -> Unit,
    isSelectMode: Boolean,
    isRead: Boolean,
    isDelivered: Boolean,
    peerName: String,
    myAvatarBitmap: Bitmap?,
    appLanguage: String,
    animateOnAppearance: Boolean,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onReply: (Message) -> Unit,
    onShowOptions: (Message) -> Unit,
    onOpenImages: (List<String>, Int, Message?) -> Unit,
    onOpenVideo: (String, Message?) -> Unit,
    onOpenStickerPack: (Message) -> Unit,
    onCancelFileTransfer: (Message) -> Unit,
    onRetryFileTransfer: (Message) -> Unit = {},
    onScrollToReply: (String) -> Unit,
    onOpenGifGallery: (Message) -> Unit,
    onOpenImageGallery: (Message) -> Unit,
    highlightedMessageId: String? = null,
    onHighlightFinished: () -> Unit = {},
) {
    val isHighlighted = msg.id == highlightedMessageId
    var highlightAlpha by remember(msg.id, isHighlighted) { mutableStateOf(if (isHighlighted) 0.5f else 0.0f) }
    val formattedTime = remember(msg.id, msg.status, msg.sentAtEpochMs, appLanguage) {
        MessageTimestampFormatter.format(msg, appLanguage)
    }
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
    val isSystemNotification = remember(msg.attachmentType, msg.text) {
        msg.attachmentType?.equals("SYSTEM", ignoreCase = true) == true ||
                msg.text.contains("установили новые обои", ignoreCase = true) ||
                msg.text.contains("установил новые обои", ignoreCase = true) ||
                msg.text.contains("установил(а) новые обои", ignoreCase = true) ||
                msg.text.contains("set a new wallpaper", ignoreCase = true) ||
                msg.text.contains("удалили обои", ignoreCase = true) ||
                msg.text.contains("удалил обои", ignoreCase = true) ||
                msg.text.contains("removed the wallpaper", ignoreCase = true) ||
                msg.text.contains("запретили пересылку", ignoreCase = true) ||
                msg.text.contains("запретил пересылку", ignoreCase = true) ||
                msg.text.contains("disabled forwarding", ignoreCase = true)
    }

    if (isSystemNotification) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.60f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = msg.text,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = remember(context) { com.example.twopchat.config.P2PPreferences.prefs(context) }
    val linkPreviewsEnabled = remember(sharedPrefs) { sharedPrefs.getBoolean("settings_link_previews", false) }
    val isText = msg.attachmentType == null
    val isSticker = msg.attachmentType == StickerSupport.ATTACHMENT_TYPE
    val isGif = msg.attachmentType == GifStorageManager.ATTACHMENT_TYPE
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

    MessageAppearance(
        animateOnAppearance = animateOnAppearance,
        visibleState = visibleState,
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
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { checked -> onSelectionChange(msg, checked) },
                        colors = CheckboxDefaults.colors(checkedColor = primaryColor),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = isSelectMode) {
                            if (isSelectMode) {
                                onSelectionChange(msg, !isSelected)
                            }
                        },
                    horizontalAlignment = alignment
                ) {
                    Box(
                        modifier = bubbleModifier
                            .combinedClickable(
                                onClick = {
                                    if (isSelectMode) {
                                        onSelectionChange(msg, !isSelected)
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
                            .widthIn(max = if (isOnlyEmoji) 140.dp else if (isSticker) 210.dp else 280.dp)
                    ) {
                        Column(horizontalAlignment = if (msg.isMe) Alignment.End else Alignment.Start) {
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
                                
                                ConversationReplyQuote(
                                    author = msg.replyToName ?: "Unknown",
                                    text = msg.replyToText ?: "",
                                    accentColor = replyBarColor,
                                    titleColor = replyTitleColor,
                                    textColor = replyTextColor,
                                    backgroundColor = replyBg,
                                    onClick = { msg.replyToId.let(onScrollToReply) },
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }

                            when (if (msg.albumMediaUris.isNotEmpty()) "ALBUM" else msg.attachmentType) {
                                StickerSupport.ATTACHMENT_TYPE -> {
                                    StickerMessageContent(
                                        filePath = msg.attachmentUri,
                                        fallbackEmoji = msg.text,
                                        isAnimationEnabled = isAnimatedMediaEnabled,
                                        onClick = {
                                            if (isSelectMode) {
                                                onSelectionChange(msg, !isSelected)
                                            } else {
                                                onOpenStickerPack(msg)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectMode) onShowOptions(msg)
                                        },
                                    )
                                }
                                GifStorageManager.ATTACHMENT_TYPE -> {
                                    GifMessageContent(
                                        filePath = msg.attachmentUri,
                                        fallbackText = msg.text,
                                        bubbleShape = bubbleShape,
                                        isAnimationEnabled = isAnimatedMediaEnabled,
                                        onClick = {
                                            if (isSelectMode) {
                                                onSelectionChange(msg, !isSelected)
                                            } else {
                                                onOpenGifGallery(msg)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectMode) onShowOptions(msg)
                                        },
                                    )
                                }
                                StickerSupport.PACK_ATTACHMENT_TYPE -> {
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
                                                text = Localizations.tr(
                                                    appLanguage,
                                                    ru = "Стикерпак добавлен",
                                                    en = "Sticker pack added",
                                                    de = "Sticker-Paket hinzugefügt",
                                                    es = "Pack de stickers añadido",
                                                    fr = "Pack d'autocollants ajouté",
                                                    pt = "Pacote de figurinhas adicionado",
                                                    tr = "Çıkartma paketi eklendi"
                                                ),
                                                color = textColor,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = StickerSupport
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
                                        isSelected = isSelected,
                                        onSelectionChange = onSelectionChange,
                                        isSelectMode = isSelectMode,
                                        isRead = isRead,
                                        isDelivered = isDelivered,
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
                                    val attachmentAvailable = remember(msg.attachmentUri) { isAttachmentAvailable(msg.attachmentUri) }
                                    val progressInfo = com.example.twopchat.relay.P2PMessageRelay.fileProgressStates["$peerName:${msg.id}"]
                                        ?: com.example.twopchat.relay.P2PMessageRelay.fileProgressStates[msg.id]
                                        ?: msg.attachmentName?.let { com.example.twopchat.relay.P2PMessageRelay.fileProgressStates["$peerName:$it"] ?: com.example.twopchat.relay.P2PMessageRelay.fileProgressStates[it] }
                                    val isCancelled = progressInfo?.state ==
                                        com.example.twopchat.relay.P2PMessageRelay.FileTransferState.CANCELLED ||
                                        msg.status.equals("CANCELLED", ignoreCase = true)
                                    val hasFailed = progressInfo?.state ==
                                        com.example.twopchat.relay.P2PMessageRelay.FileTransferState.FAILED ||
                                        msg.status.equals("FAILED", ignoreCase = true)
                                    val isProgressActive = progressInfo?.state == com.example.twopchat.relay.P2PMessageRelay.FileTransferState.TRANSFERRING &&
                                        (progressInfo.totalBytes <= 0L || progressInfo.bytesTransferred < progressInfo.totalBytes)
                                    val isTransferring = !isCancelled && !hasFailed && (
                                        isProgressActive || (msg.isMe && msg.status?.startsWith("SENDING") == true) || (!msg.isMe && msg.status == "RECEIVING")
                                    )
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
                                                                onSelectionChange(msg, !isSelected)
                                                            } else {
                                                                onOpenImageGallery(msg)
                                                            }
                                                        },
                                                        onLongClick = {
                                                            if (isSelectMode) {
                                                                onSelectionChange(msg, !isSelected)
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

                                                if (isTransferring) {
                                                    val info = progressInfo ?: com.example.twopchat.relay.P2PMessageRelay.FileProgressInfo(0L, 0L, 0.0)
                                                    val progressText = formatFileTransferProgress(info)
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(Color.Black.copy(alpha = 0.55f))
                                                            .padding(12.dp),
                                                        contentAlignment = Alignment.BottomCenter
                                                    ) {
                                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                            Text(
                                                                text = progressText,
                                                                color = Color.White,
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            Spacer(modifier = Modifier.height(6.dp))
                                                            androidx.compose.material3.LinearProgressIndicator(
                                                                progress = {
                                                                    if (info.totalBytes > 0L) {
                                                                        (info.bytesTransferred.toFloat() / info.totalBytes.toFloat()).coerceIn(0f, 1f)
                                                                    } else 0f
                                                                },
                                                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                                                color = Color.White,
                                                                trackColor = Color.White.copy(alpha = 0.3f)
                                                            )
                                                        }
                                                    }
                                                    if (msg.isMe) {
                                                        Box(
                                                            modifier = Modifier
                                                                .align(Alignment.TopEnd)
                                                                .padding(8.dp)
                                                                .size(36.dp)
                                                                .clip(CircleShape)
                                                                .background(Color.Black.copy(alpha = 0.65f))
                                                                .clickable {
                                                                    onCancelFileTransfer(msg)
                                                                },
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Close,
                                                                contentDescription = Localizations.tr(
                                                                    appLanguage,
                                                                    ru = "Отменить",
                                                                    en = "Cancel",
                                                                    de = "Abbrechen",
                                                                    es = "Cancelar",
                                                                    fr = "Annuler",
                                                                    pt = "Cancelar",
                                                                    tr = "İptal"
                                                                ),
                                                                tint = Color.White,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                if (isRemoved || isCancelled || hasFailed) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(Color.Black.copy(alpha = 0.45f)),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            verticalArrangement = Arrangement.Center
                                                        ) {
                                                            Text(
                                                                text = if (isCancelled) {
                                                                    Localizations.tr(
                                                                        appLanguage,
                                                                        ru = "Передача отменена",
                                                                        en = "Transfer cancelled",
                                                                        de = "Übertragung abgebrochen",
                                                                        es = "Transferencia cancelada",
                                                                        fr = "Transfert annulé",
                                                                        pt = "Transferência cancelada",
                                                                        tr = "Aktarım iptal edildi"
                                                                    )
                                                                } else if (hasFailed) {
                                                                    Localizations.tr(
                                                                        appLanguage,
                                                                        ru = "Ошибка передачи",
                                                                        en = "Transfer failed",
                                                                        de = "Übertragungsfehler",
                                                                        es = "Error de transferencia",
                                                                        fr = "Échec du transfert",
                                                                        pt = "Falha na transferência",
                                                                        tr = "Aktarım başarısız oldu"
                                                                    )
                                                                } else {
                                                                    Localizations.tr(
                                                                        appLanguage,
                                                                        ru = "Файл удалён",
                                                                        en = "File removed",
                                                                        de = "Datei entfernt",
                                                                        es = "Archivo eliminado",
                                                                        fr = "Fichier supprimé",
                                                                        pt = "Arquivo removido",
                                                                        tr = "Dosya kaldırıldı"
                                                                    )
                                                                },
                                                                color = Color.White.copy(alpha = 0.9f),
                                                                fontSize = 13.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                            )
                                                            if (msg.isMe && (hasFailed || isCancelled) && attachmentAvailable) {
                                                                Spacer(modifier = Modifier.height(6.dp))
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    modifier = Modifier
                                                                        .clip(RoundedCornerShape(14.dp))
                                                                        .background(Color.White.copy(alpha = 0.25f))
                                                                        .clickable { onRetryFileTransfer(msg) }
                                                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.Refresh,
                                                                        contentDescription = "Retry",
                                                                        tint = Color.White,
                                                                        modifier = Modifier.size(13.dp)
                                                                    )
                                                                    Spacer(modifier = Modifier.width(4.dp))
                                                                    Text(
                                                                        text = Localizations.tr(
                                                                            appLanguage,
                                                                            ru = "Возобновить",
                                                                            en = "Resume",
                                                                            de = "Fortsetzen",
                                                                            es = "Reanudar",
                                                                            fr = "Reprendre",
                                                                            pt = "Retomar",
                                                                            tr = "Devam Ettir"
                                                                        ),
                                                                        color = Color.White,
                                                                        fontSize = 12.sp,
                                                                        fontWeight = FontWeight.Bold
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }

                                                // If NO caption, floating timestamp pill in bottom-right corner over the photo
                                                if (!hasCaption) {
                                                    val isPending = msg.status?.startsWith("PENDING") == true || msg.status?.startsWith("SENDING") == true

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
                                                            text = formattedTime,
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
                                                                    painter = painterResource(id = if (isDelivered) com.example.twopchat.R.drawable.ic_msg_double_check else com.example.twopchat.R.drawable.ic_msg_single_check),
                                                                    contentDescription = if (isRead) "Read" else if (isDelivered) "Delivered" else "Sent",
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
                                                val isPending = msg.status?.startsWith("PENDING") == true || msg.status?.startsWith("SENDING") == true

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
                                                            text = formattedTime,
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
                                                                AnimatedContent(
                                                                    targetState = isDelivered to isRead,
                                                                    transitionSpec = {
                                                                        (scaleIn(initialScale = 0.7f, animationSpec = tween(140, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(140)))
                                                                            .togetherWith(fadeOut(animationSpec = tween(90)))
                                                                    },
                                                                    label = "MessageStatusCheckTransition"
                                                                ) { deliveryState ->
                                                                    val deliveredState = deliveryState.first
                                                                    val readState = deliveryState.second
                                                                    Icon(
                                                                        painter = painterResource(id = if (deliveredState) com.example.twopchat.R.drawable.ic_msg_double_check else com.example.twopchat.R.drawable.ic_msg_single_check),
                                                                        contentDescription = if (readState) "Read" else if (deliveredState) "Delivered" else "Sent",
                                                                        tint = if (readState) {
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
                                }
                                "VIDEO" -> {
                                    val completedThumbnail = rememberVideoThumbnail(msg.attachmentUri)
                                    val progressInfo = com.example.twopchat.relay.P2PMessageRelay.fileProgressStates["$peerName:${msg.id}"]
                                        ?: com.example.twopchat.relay.P2PMessageRelay.fileProgressStates[msg.id]
                                        ?: msg.attachmentName?.let { com.example.twopchat.relay.P2PMessageRelay.fileProgressStates["$peerName:$it"] ?: com.example.twopchat.relay.P2PMessageRelay.fileProgressStates[it] }
                                    val transferPreview = com.example.twopchat.relay.P2PMessageRelay.fileTransferPreviews["$peerName:${msg.id}"]
                                        ?: com.example.twopchat.relay.P2PMessageRelay.fileTransferPreviews[msg.id]
                                    val thumbnail = completedThumbnail ?: transferPreview
                                    val attachmentAvailable = remember(msg.attachmentUri) { isAttachmentAvailable(msg.attachmentUri) }
                                    val isCancelled = progressInfo?.state ==
                                        com.example.twopchat.relay.P2PMessageRelay.FileTransferState.CANCELLED ||
                                        msg.status.equals("CANCELLED", ignoreCase = true)
                                    val hasFailed = progressInfo?.state ==
                                        com.example.twopchat.relay.P2PMessageRelay.FileTransferState.FAILED ||
                                        msg.status.equals("FAILED", ignoreCase = true)
                                    val isProgressActive = progressInfo?.state == com.example.twopchat.relay.P2PMessageRelay.FileTransferState.TRANSFERRING &&
                                        (progressInfo.totalBytes <= 0L || progressInfo.bytesTransferred < progressInfo.totalBytes)
                                    val isTransferring = !isCancelled && !hasFailed && (
                                        isProgressActive || (msg.isMe && msg.status?.startsWith("SENDING") == true) || (!msg.isMe && msg.status == "RECEIVING")
                                    )
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
                                                            onSelectionChange(msg, !isSelected)
                                                        } else {
                                                            msg.attachmentUri?.let { onOpenVideo(it, msg) }
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (isSelectMode) {
                                                            onSelectionChange(msg, !isSelected)
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
                                            if (isTransferring) {
                                                val info = progressInfo ?: com.example.twopchat.relay.P2PMessageRelay.FileProgressInfo(0L, 0L, 0.0)
                                                val progressText = formatFileTransferProgress(info)
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.55f))
                                                        .padding(12.dp),
                                                    contentAlignment = Alignment.BottomCenter
                                                ) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text(
                                                            text = progressText,
                                                            color = Color.White,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                        androidx.compose.material3.LinearProgressIndicator(
                                                            progress = {
                                                                if (info.totalBytes > 0L) {
                                                                    (info.bytesTransferred.toFloat() / info.totalBytes.toFloat()).coerceIn(0f, 1f)
                                                                } else 0f
                                                            },
                                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                                            color = Color.White,
                                                            trackColor = Color.White.copy(alpha = 0.3f)
                                                        )
                                                    }
                                                }
                                                if (msg.isMe) {
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.TopEnd)
                                                            .padding(8.dp)
                                                            .size(36.dp)
                                                            .clip(CircleShape)
                                                            .background(Color.Black.copy(alpha = 0.65f))
                                                            .clickable {
                                                                onCancelFileTransfer(msg)
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Close,
                                                            contentDescription = Localizations.tr(
                                                                appLanguage,
                                                                ru = "Отменить",
                                                                en = "Cancel",
                                                                de = "Abbrechen",
                                                                es = "Cancelar",
                                                                fr = "Annuler",
                                                                pt = "Cancelar",
                                                                tr = "İptal"
                                                            ),
                                                            tint = Color.White,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                            } else if (isCancelled || hasFailed || isRemoved) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.55f)),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.Center
                                                    ) {
                                                        Text(
                                                            text = if (isCancelled) {
                                                                Localizations.tr(
                                                                    appLanguage,
                                                                    ru = "Передача отменена",
                                                                    en = "Transfer cancelled",
                                                                    de = "Übertragung abgebrochen",
                                                                    es = "Transferencia cancelada",
                                                                    fr = "Transfert annulé",
                                                                    pt = "Transferência cancelada",
                                                                    tr = "Aktarım iptal edildi"
                                                                )
                                                            } else if (hasFailed) {
                                                                Localizations.tr(
                                                                    appLanguage,
                                                                    ru = "Ошибка передачи",
                                                                    en = "Transfer failed",
                                                                    de = "Übertragungsfehler",
                                                                    es = "Error de transferencia",
                                                                    fr = "Échec du transfert",
                                                                    pt = "Falha na transferência",
                                                                    tr = "Aktarım başarısız oldu"
                                                                )
                                                            } else {
                                                                Localizations.tr(
                                                                    appLanguage,
                                                                    ru = "Файл удалён",
                                                                    en = "File removed",
                                                                    de = "Datei entfernt",
                                                                    es = "Archivo eliminado",
                                                                    fr = "Fichier supprimé",
                                                                    pt = "Arquivo removido",
                                                                    tr = "Dosya kaldırıldı"
                                                                )
                                                            },
                                                            color = Color.White,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                        )
                                                        if (msg.isMe && (hasFailed || isCancelled) && attachmentAvailable) {
                                                            Spacer(modifier = Modifier.height(6.dp))
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier
                                                                    .clip(RoundedCornerShape(14.dp))
                                                                    .background(Color.White.copy(alpha = 0.25f))
                                                                    .clickable { onRetryFileTransfer(msg) }
                                                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Refresh,
                                                                    contentDescription = "Retry",
                                                                    tint = Color.White,
                                                                    modifier = Modifier.size(13.dp)
                                                                )
                                                                Spacer(modifier = Modifier.width(4.dp))
                                                                Text(
                                                                    text = Localizations.tr(
                                                                        appLanguage,
                                                                        ru = "Возобновить",
                                                                        en = "Resume",
                                                                        de = "Fortsetzen",
                                                                        es = "Reanudar",
                                                                        fr = "Reprendre",
                                                                        pt = "Retomar",
                                                                        tr = "Devam Ettir"
                                                                    ),
                                                                    color = Color.White,
                                                                    fontSize = 12.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            // If NO caption, floating timestamp pill in bottom-right corner over the video
                                            if (!hasCaption) {
                                                val isPending = msg.status?.startsWith("PENDING") == true || msg.status?.startsWith("SENDING") == true

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
                                                        text = formattedTime,
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
                                                                painter = painterResource(id = if (isDelivered) com.example.twopchat.R.drawable.ic_msg_double_check else com.example.twopchat.R.drawable.ic_msg_single_check),
                                                                contentDescription = if (isRead) "Read" else if (isDelivered) "Delivered" else "Sent",
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
                                            val isPending = msg.status?.startsWith("PENDING") == true || msg.status?.startsWith("SENDING") == true

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
                                                        text = formattedTime,
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
                                                                painter = painterResource(id = if (isDelivered) com.example.twopchat.R.drawable.ic_msg_double_check else com.example.twopchat.R.drawable.ic_msg_single_check),
                                                                contentDescription = if (isRead) "Read" else if (isDelivered) "Delivered" else "Sent",
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
                                    val attachmentAvailable = remember(msg.attachmentUri) { isAttachmentAvailable(msg.attachmentUri) }
                                    val progressInfo = com.example.twopchat.relay.P2PMessageRelay.fileProgressStates["$peerName:${msg.id}"]
                                        ?: com.example.twopchat.relay.P2PMessageRelay.fileProgressStates[msg.id]
                                        ?: msg.attachmentName?.let { com.example.twopchat.relay.P2PMessageRelay.fileProgressStates["$peerName:$it"] ?: com.example.twopchat.relay.P2PMessageRelay.fileProgressStates[it] }
                                    
                                    val isCancelled = progressInfo?.state ==
                                        com.example.twopchat.relay.P2PMessageRelay.FileTransferState.CANCELLED ||
                                        msg.status.equals("CANCELLED", ignoreCase = true)
                                    val hasFailed = progressInfo?.state ==
                                        com.example.twopchat.relay.P2PMessageRelay.FileTransferState.FAILED ||
                                        msg.status.equals("FAILED", ignoreCase = true)
                                    val isProgressActive = progressInfo?.state == com.example.twopchat.relay.P2PMessageRelay.FileTransferState.TRANSFERRING &&
                                        (progressInfo.totalBytes <= 0L || progressInfo.bytesTransferred < progressInfo.totalBytes)
                                    val isTransferring = !isCancelled && !hasFailed && (
                                        isProgressActive || (msg.isMe && msg.status?.startsWith("SENDING") == true) || (!msg.isMe && msg.status == "RECEIVING")
                                    )

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
                                                val subtext = if (isTransferring) {
                                                    val info = progressInfo ?: com.example.twopchat.relay.P2PMessageRelay.FileProgressInfo(0L, 0L, 0.0)
                                                    formatFileTransferProgress(info)
                                                } else if (isCancelled) {
                                                    Localizations.tr(
                                                        appLanguage,
                                                        ru = "Передача отменена",
                                                        en = "Transfer cancelled",
                                                        de = "Übertragung abgebrochen",
                                                        es = "Transferencia cancelada",
                                                        fr = "Transfert annulé",
                                                        pt = "Transferência cancelada",
                                                        tr = "Aktarım iptal edildi"
                                                    )
                                                } else if (hasFailed) {
                                                    Localizations.tr(
                                                        appLanguage,
                                                        ru = "Ошибка передачи",
                                                        en = "Transfer failed",
                                                        de = "Übertragungsfehler",
                                                        es = "Error de transferencia",
                                                        fr = "Échec du transfert",
                                                        pt = "Falha na transferência",
                                                        tr = "Aktarım başarısız oldu"
                                                    )
                                                } else if (!attachmentAvailable) {
                                                    Localizations.tr(
                                                        appLanguage,
                                                        ru = "Файл удалён",
                                                        en = "File removed",
                                                        de = "Datei entfernt",
                                                        es = "Archivo eliminado",
                                                        fr = "Fichier supprimé",
                                                        pt = "Arquivo removido",
                                                        tr = "Dosya kaldırıldı"
                                                    )
                                                } else {
                                                    "Encrypted Document"
                                                }
                                                Text(
                                                    text = subtext,
                                                    color = if (isTransferring) (if (msg.isMe) Color.White else primaryColor) else textColor.copy(alpha = 0.7f),
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isTransferring) FontWeight.Bold else FontWeight.Normal
                                                )
                                                if (!isTransferring && msg.isMe && (hasFailed || isCancelled) && attachmentAvailable) {
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(12.dp))
                                                            .background(if (msg.isMe) Color.White.copy(alpha = 0.18f) else primaryColor.copy(alpha = 0.12f))
                                                            .clickable { onRetryFileTransfer(msg) }
                                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Refresh,
                                                            contentDescription = "Resume Transfer",
                                                            tint = if (msg.isMe) Color.White else primaryColor,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = Localizations.tr(
                                                                appLanguage,
                                                                ru = "Возобновить",
                                                                en = "Resume",
                                                                de = "Fortsetzen",
                                                                es = "Reanudar",
                                                                fr = "Reprendre",
                                                                pt = "Retomar",
                                                                tr = "Devam Ettir"
                                                            ),
                                                            color = if (msg.isMe) Color.White else primaryColor,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        if (isTransferring) {
                                            val info = progressInfo ?: com.example.twopchat.relay.P2PMessageRelay.FileProgressInfo(0L, 0L, 0.0)
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                androidx.compose.material3.LinearProgressIndicator(
                                                    progress = {
                                                        if (info.totalBytes > 0L) {
                                                            (info.bytesTransferred.toFloat() / info.totalBytes.toFloat()).coerceIn(0f, 1f)
                                                        } else 0f
                                                    },
                                                    modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                                                    color = if (msg.isMe) Color.White else primaryColor,
                                                    trackColor = textColor.copy(alpha = 0.2f)
                                                )
                                                if (msg.isMe) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = Localizations.tr(
                                                            appLanguage,
                                                            ru = "Отменить",
                                                            en = "Cancel",
                                                            de = "Abbrechen",
                                                            es = "Cancelar",
                                                            fr = "Annuler",
                                                            pt = "Cancelar",
                                                            tr = "İptal"
                                                        ),
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
                                    val voiceAttachmentAvailable = remember(msg.attachmentUri) { isAttachmentAvailable(msg.attachmentUri) }
                                    if (voiceAttachmentAvailable) {
                                        VoiceMessagePlayer(
                                            filePath = msg.attachmentUri,
                                            isMine = msg.isMe,
                                            primaryColor = primaryColor,
                                            contentColor = textColor,
                                        )
                                    } else {
                                        Text(
                                            text = Localizations.tr(
                                                appLanguage,
                                                ru = "Голосовой файл удалён",
                                                en = "Voice file removed",
                                                de = "Sprachdatei entfernt",
                                                es = "Archivo de voz eliminado",
                                                fr = "Fichier vocal supprimé",
                                                pt = "Arquivo de voz removido",
                                                tr = "Ses dosyası kaldırıldı"
                                            ),
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
                                    val groupInvite = remember(msg.text) { parseGroupInviteInText(msg.text, peerName) }
                                    if (groupInvite != null) {
                                        val context = androidx.compose.ui.platform.LocalContext.current
                                        GroupInviteCard(
                                            inviteInfo = groupInvite,
                                            isMe = msg.isMe,
                                            primaryColor = primaryColor,
                                            onSurfaceColor = onSurfaceColor,
                                            surfaceColor = surfaceColor,
                                            onJoinClick = {
                                                val accepted = com.example.twopchat.group.runtime.GroupChatCoordinator.acceptPendingInviteForGroup(groupInvite.groupId)
                                                com.example.twopchat.group.runtime.GroupChatCoordinator.requestJoinFromInvite(
                                                    groupInvite.groupId,
                                                    groupInvite.groupToken,
                                                    groupInvite.inviterPeerName
                                                )
                                                val msgText = if (accepted) {
                                                    com.example.twopchat.data.Localizations.tr(
                                                        appLanguage,
                                                        ru = "Вы вошли в группу!",
                                                        en = "You joined the group!",
                                                        de = "Du bist der Gruppe beigetreten!",
                                                        es = "¡Te has unido al grupo!",
                                                        fr = "Vous avez rejoint le groupe !",
                                                        pt = "Você entrou no grupo!",
                                                        tr = "Gruba katıldınız!"
                                                    )
                                                } else {
                                                    com.example.twopchat.data.Localizations.tr(
                                                        appLanguage,
                                                        ru = "Запрос на вступление отправлен!",
                                                        en = "Join request sent!",
                                                        de = "Beitrittsanfrage gesendet!",
                                                        es = "¡Solicitud de unión enviada!",
                                                        fr = "Demande d'adhésion envoyée !",
                                                        pt = "Pedido de adesão enviado!",
                                                        tr = "Katılma isteği gönderildi!"
                                                    )
                                                }
                                                android.widget.Toast.makeText(
                                                    context,
                                                    msgText,
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        )
                                    } else if (isOnlyEmoji) {
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
                                val isTransparentBubble = isOnlyEmoji || isSticker || isGif
                                Surface(
                                    color = if (isTransparentBubble) Color.Black.copy(alpha = 0.42f) else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp, bottom = 2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(
                                            horizontal = if (isTransparentBubble) 6.dp else 0.dp,
                                            vertical = if (isTransparentBubble) 2.dp else 0.dp
                                        )
                                    ) {
                                        Text(
                                            text = formattedTime,
                                            color = (if (isTransparentBubble) {
                                                Color.White.copy(alpha = 0.95f)
                                            } else if (msg.isMe) {
                                                if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.65f)
                                            } else onSurfaceColor.copy(alpha = 0.5f)),
                                            fontSize = 9.sp
                                        )
                                        if (msg.isMe) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            val isPending = msg.status?.startsWith("PENDING") == true || msg.status?.startsWith("SENDING") == true

                                            val statusColor = if (isTransparentBubble) {
                                                Color.White.copy(alpha = 0.95f)
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
                                            } else if (isDelivered) {
                                                Icon(
                                                    painter = painterResource(id = com.example.twopchat.R.drawable.ic_msg_double_check),
                                                    contentDescription = if (isRead) "Read" else "Delivered",
                                                    tint = if (isRead) Color(0xFF64B5F6) else statusColor,
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
                                                            com.example.twopchat.relay.P2PMessageRelay.peerAvatars[peerName]
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
private fun MessageAppearance(
    animateOnAppearance: Boolean,
    visibleState: MutableTransitionState<Boolean>,
    content: @Composable () -> Unit,
) {
    if (!animateOnAppearance) {
        content()
        return
    }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(
            animationSpec = androidx.compose.animation.core.tween(
                180,
                easing = androidx.compose.animation.core.LinearOutSlowInEasing,
            ),
        ) + scaleIn(
            initialScale = 0.96f,
            animationSpec = androidx.compose.animation.core.tween(
                180,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        ) + slideInVertically(
            initialOffsetY = { it / 6 },
            animationSpec = androidx.compose.animation.core.tween(
                180,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
private fun GifMessageContent(
    filePath: String?,
    fallbackText: String,
    bubbleShape: RoundedCornerShape,
    isAnimationEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val validPath = remember(filePath) {
        filePath?.takeIf {
            java.io.File(it).isFile && java.io.File(it).extension.equals("gif", ignoreCase = true)
        }
    }
    Box(
        modifier = Modifier
            .size(width = 260.dp, height = 220.dp)
            .clip(bubbleShape)
            .background(Color.Black.copy(alpha = 0.08f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedGifImage(
            filePath = validPath,
            targetMaxDimensionPx = 640,
            contentScale = GifContentScale.CROP,
            contentDescription = "GIF attachment",
            modifier = Modifier.fillMaxSize(),
            isAnimationEnabled = isAnimationEnabled,
        )
        if (validPath == null) {
            Text(
                text = fallbackText.ifBlank { "GIF" },
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
    isAnimationEnabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var pressed by remember(filePath) { mutableStateOf(false) }
    val stickerScale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = MotionTokens.BouncySpring,
        label = "stickerBounce",
    )
    LaunchedEffect(pressed) {
        if (pressed) {
            delay(110)
            pressed = false
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
        AnimatedStickerImage(
            filePath = filePath,
            fallbackEmoji = fallbackEmoji,
            contentDescription = fallbackEmoji.ifBlank { "Sticker" },
            targetSizePx = 420,
            modifier = Modifier.fillMaxSize(),
            isAnimationEnabled = isAnimationEnabled,
        )
        if (filePath == null) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                strokeWidth = 2.5.dp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            )
        }
    }
}

private val URL_PATTERN = Pattern.compile(
    "(?:^|[\\s])((?:https?://|www\\.)[\\w\\-_]+(?:\\.[\\w\\-_]+)+(?:[\\w\\-\\.,@?^=%&:/~\\+#]*[\\w\\-\\@?^=%&/~\\+#])?)",
    Pattern.CASE_INSENSITIVE
)

@Composable
fun LinkifiedText(
    text: String,
    textColor: Color,
    linkColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val myUsername = remember(context) {
        com.example.twopchat.config.P2PPreferences.prefs(context).getString("username_profile", "") ?: ""
    }

    val annotatedString = remember(text, textColor, linkColor, myUsername) {
        buildAnnotatedString {
            // Pattern for matching URLs and @mentions
            val pattern = Pattern.compile("(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=]+)|(?<=^|\\s)(@[a-zA-Z0-9_А-Яа-я-]+)")
            val matcher = pattern.matcher(text)
            var lastMatchEnd = 0
            while (matcher.find()) {
                val start = matcher.start()
                val end = matcher.end()
                
                // Append text before match
                append(text.substring(lastMatchEnd, start))
                
                val token = text.substring(start, end)
                if (token.startsWith("@")) {
                    // Mention handling
                    val mentionedName = token.removePrefix("@")
                    val isMe = myUsername.isNotBlank() && (
                        myUsername.equals(mentionedName, ignoreCase = true) ||
                        mentionedName.equals("all", ignoreCase = true)
                    )
                    
                    val mentionStart = this.length
                    append(token)
                    val mentionEnd = this.length
                    
                    addStyle(
                        style = SpanStyle(
                            color = linkColor,
                            fontWeight = FontWeight.Bold,
                            background = if (isMe) linkColor.copy(alpha = 0.22f) else Color.Transparent
                        ),
                        start = mentionStart,
                        end = mentionEnd
                    )
                } else {
                    // URL handling
                    val destinationUrl = if (!token.startsWith("http://", ignoreCase = true) && 
                                              !token.startsWith("https://", ignoreCase = true)) {
                        "https://$token"
                    } else {
                        token
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
                    append(token)
                    val linkEnd = this.length
                    
                    addLink(
                        url = linkAnnotation,
                        start = linkStart,
                        end = linkEnd
                    )
                }
                
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

internal data class GroupInviteInfo(
    val groupTitle: String,
    val groupId: String,
    val groupToken: String,
    val inviterPeerName: String
)

internal fun parseGroupInviteInText(text: String, defaultInviter: String): GroupInviteInfo? {
    if (!text.contains("group=") || !text.contains("group_token=")) return null
    val urlRegex = Regex("""(2pchat://connect\?[^\s]+)""")
    val match = urlRegex.find(text) ?: return null
    val rawUrl = match.value
    val uri = try { android.net.Uri.parse(rawUrl) } catch (e: Exception) { return null }
    val groupId = uri.getQueryParameter("group") ?: return null
    val groupToken = uri.getQueryParameter("group_token") ?: return null
    val inviterName = uri.getQueryParameter("name")?.takeIf { it.isNotBlank() } ?: defaultInviter

    val titleMatch = Regex("""«([^»]+)»""").find(text)
    val groupTitle = titleMatch?.groupValues?.get(1)
        ?: uri.getQueryParameter("group_title")
        ?: uri.getQueryParameter("title")
        ?: "Группа"

    return GroupInviteInfo(
        groupTitle = groupTitle,
        groupId = groupId,
        groupToken = groupToken,
        inviterPeerName = inviterName
    )
}

@Composable
internal fun GroupInviteCard(
    inviteInfo: GroupInviteInfo,
    isMe: Boolean,
    primaryColor: Color,
    onSurfaceColor: Color,
    surfaceColor: Color,
    onJoinClick: () -> Unit
) {
    Surface(
        color = if (isMe) Color.White.copy(alpha = 0.15f) else primaryColor.copy(alpha = 0.1f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isMe) Color.White.copy(alpha = 0.3f) else primaryColor.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val appLanguage = remember(context) { com.example.twopchat.config.P2PPreferences.getAppLanguage(context) }
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(primaryColor.copy(alpha = 0.2f))
                ) {
                    Text("👥", fontSize = 22.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = com.example.twopchat.data.Localizations.tr(
                            appLanguage,
                            ru = "Приглашение в группу",
                            en = "Group Invitation",
                            de = "Gruppeneinladung",
                            es = "Invitación al grupo",
                            fr = "Invitation de groupe",
                            pt = "Convite do grupo",
                            tr = "Grup Daveti"
                        ),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isMe) Color.White.copy(alpha = 0.75f) else onSurfaceColor.copy(alpha = 0.6f)
                    )
                    Text(
                        text = inviteInfo.groupTitle,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isMe) Color.White else onSurfaceColor,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            androidx.compose.material3.Button(
                onClick = onJoinClick,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (isMe) Color.White else primaryColor,
                    contentColor = if (isMe) primaryColor else Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                Text(
                    text = com.example.twopchat.data.Localizations.tr(
                        appLanguage,
                        ru = "Принять приглашение",
                        en = "Accept Invite",
                        de = "Einladung annehmen",
                        es = "Aceptar invitación",
                        fr = "Accepter l'invitation",
                        pt = "Aceitar convite",
                        tr = "Daveti Kabul Et"
                    ),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

internal fun isEmojiCodePoint(codePoint: Int): Boolean {
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

internal fun isSingleEmoji(text: String): Boolean {
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
                } catch (_: android.content.ActivityNotFoundException) {
                    // intentionally ignored: no application available to handle ACTION_VIEW intent
                } catch (e: Exception) {
                    com.example.twopchat.logging.SafeLog.w("ChatMessageBubble", "Failed opening link preview URL", e)
                }
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
    isSelected: Boolean,
    onSelectionChange: (Message, Boolean) -> Unit,
    isSelectMode: Boolean,
    isRead: Boolean,
    isDelivered: Boolean,
    appLanguage: String,
    primaryColor: Color,
    textColor: Color,
    linkColor: Color,
    bubbleShape: androidx.compose.ui.graphics.Shape,
    index: Int,
    onOpenImages: (List<String>, Int, Message?) -> Unit,
    onOpenVideo: (String, Message?) -> Unit,
    onShowOptions: (Message) -> Unit
) {
    val onToggleSelection = { onSelectionChange(msg, !isSelected) }
    val uris = msg.albumMediaUris
    val types = msg.albumMediaTypes
    val formattedTime = remember(msg.id, msg.status, msg.sentAtEpochMs, appLanguage) {
        MessageTimestampFormatter.format(msg, appLanguage)
    }
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
                            onToggleSelection = onToggleSelection,
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
                            onToggleSelection = onToggleSelection,
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
                            onToggleSelection = onToggleSelection,
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
                                onToggleSelection = onToggleSelection,
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
                                onToggleSelection = onToggleSelection,
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
                                onToggleSelection = onToggleSelection,
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
                                onToggleSelection = onToggleSelection,
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
                                onToggleSelection = onToggleSelection,
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
                                onToggleSelection = onToggleSelection,
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
                val isPending = msg.status?.startsWith("PENDING") == true || msg.status?.startsWith("SENDING") == true

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
                        text = formattedTime,
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
                                painter = painterResource(id = if (isDelivered) com.example.twopchat.R.drawable.ic_msg_double_check else com.example.twopchat.R.drawable.ic_msg_single_check),
                                contentDescription = if (isRead) "Read" else if (isDelivered) "Delivered" else "Sent",
                                tint = if (isRead) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.95f),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }
        }

        if (hasCaption) {
            val isPending = msg.status?.startsWith("PENDING") == true || msg.status?.startsWith("SENDING") == true

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
                        text = formattedTime,
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
                                painter = painterResource(id = if (isDelivered) com.example.twopchat.R.drawable.ic_msg_double_check else com.example.twopchat.R.drawable.ic_msg_single_check),
                                contentDescription = if (isRead) "Read" else if (isDelivered) "Delivered" else "Sent",
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
    onToggleSelection: () -> Unit,
    isSelectMode: Boolean,
    onOpenImages: (List<String>, Int, Message?) -> Unit,
    onOpenVideo: (String, Message?) -> Unit,
    onShowOptions: (Message) -> Unit
) {
    if (uri.isBlank()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.background(Color.DarkGray.copy(alpha = 0.5f))
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White.copy(alpha = 0.7f),
                strokeWidth = 2.dp
            )
        }
        return
    }
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
                        onToggleSelection()
                    } else {
                        if (isVideo) {
                            onOpenVideo(uri, msg)
                        } else {
                            val imageUrisOnly = allUris.filter { !it.endsWith(".mp4", ignoreCase = true) && !it.endsWith(".mov", ignoreCase = true) }
                            val idx = imageUrisOnly.indexOf(uri).coerceAtLeast(0)
                            onOpenImages(imageUrisOnly.ifEmpty { allUris }, idx, msg)
                        }
                    }
                },
                onLongClick = {
                    if (isSelectMode) {
                        onToggleSelection()
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
        val extraCount = allUris.size - 4
        if (extraCount > 0 && cellIndex == 3) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Text(
                    text = "+$extraCount",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }
    }
}

private fun formatFileTransferProgress(progressInfo: com.example.twopchat.relay.P2PMessageRelay.FileProgressInfo?): String {
    if (progressInfo == null) return "0%"
    val pct = if (progressInfo.totalBytes > 0L) {
        (progressInfo.bytesTransferred * 100 / progressInfo.totalBytes).toInt().coerceIn(0, 100)
    } else 0
    val speedStr = if (progressInfo.speedKbps >= 1024) {
        String.format(java.util.Locale.US, "%.1f MB/s", progressInfo.speedKbps / 1024.0)
    } else if (progressInfo.speedKbps > 0) {
        "${progressInfo.speedKbps.toInt()} KB/s"
    } else {
        ""
    }
    val sizeStr = if (progressInfo.totalBytes > 0L) {
        val transferredMb = progressInfo.bytesTransferred / (1024.0 * 1024.0)
        val totalMb = progressInfo.totalBytes / (1024.0 * 1024.0)
        String.format(java.util.Locale.US, "%.1f / %.1f MB", transferredMb, totalMb)
    } else ""

    return buildString {
        append("$pct%")
        if (sizeStr.isNotEmpty()) append(" • $sizeStr")
        if (speedStr.isNotEmpty()) append(" • $speedStr")
    }
}

