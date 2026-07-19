package com.example.twopchat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import java.util.regex.Pattern

@Composable
internal fun ChatMessageBubble(
    index: Int,
    msg: Message,
    messages: List<Message>,
    selectedMessages: MutableList<Message>,
    isSelectMode: Boolean,
    isTyping: Boolean,
    peerName: String,
    appLanguage: String,
    screenInitTime: Long,
    listState: LazyListState,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onReply: (Message) -> Unit,
    onShowOptions: (Message) -> Unit,
    onOpenImages: (List<String>, Int) -> Unit,
    onOpenVideo: (String) -> Unit,
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
    val sharedPrefs = remember(context) { context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE) }
    val myAvatarBitmap = remember(context) {
        val uri = sharedPrefs.getString("profile_photo_uri", null)
        com.example.twopchat.ui.onboarding.loadBitmapFromUri(context, uri)
    }
    val linkPreviewsEnabled = remember(sharedPrefs) { sharedPrefs.getBoolean("settings_link_previews", true) }
    val isText = msg.attachmentType == null
    val isOnlyEmoji = isText && isSingleEmoji(msg.text)
    val detectedUrl = remember(msg.text, isText) {
        if (!isText) null else {
            val matcher = URL_PATTERN.matcher(msg.text)
            if (matcher.find()) matcher.group(1) else null
        }
    }
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
    val bubbleModifier = if (isOnlyEmoji) {
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

    val textColor = if (msg.isMe) {
        if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color.White
    } else onSurfaceColor
    val linkColor = if (msg.isMe) {
        if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color.White
    } else primaryColor

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
                            .then(if (!msg.isMe && !isOnlyEmoji) Modifier.border(0.5.dp, onSurfaceColor.copy(alpha = if (surfaceColor.luminance() > 0.5f) 0.09f else 0.08f), bubbleShape) else Modifier)
                            .padding(
                                horizontal = if (isOnlyEmoji) 6.dp else 16.dp,
                                vertical = if (isOnlyEmoji) 4.dp else 11.dp
                            )
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
                                                        val allImages = messages.filter { it.attachmentType == "IMAGE" && !it.attachmentUri.isNullOrBlank() }.map { it.attachmentUri!! }
                                                        val clickedUri = msg.attachmentUri
                                                        val clickedIndex = if (clickedUri != null) allImages.indexOf(clickedUri) else -1
                                                        if (clickedIndex != -1) {
                                                            onOpenImages(allImages, clickedIndex)
                                                        } else if (clickedUri != null) {
                                                            onOpenImages(listOf(clickedUri), 0)
                                                        }
                                                    }
                                            )
                                            if (!msg.text.startsWith("Sent an image") && !msg.text.startsWith("Captured a photo")) {
                                                Spacer(modifier = Modifier.height(6.dp))
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
                                }
                                "VIDEO" -> {
                                    val thumbnail = rememberVideoThumbnail(msg.attachmentUri)
                                    Column {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(180.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    msg.attachmentUri?.let(onOpenVideo)
                                                }
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
                                            LinkifiedText(
                                                text = msg.text,
                                                textColor = textColor,
                                                linkColor = linkColor,
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
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(
                                    text = MessageTimestampFormatter.format(msg, appLanguage),
                                    color = (if (isOnlyEmoji) {
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
                                    
                                    val statusText = when {
                                        isPending -> "🕒"
                                        isRead -> "✓✓"
                                        else -> "✓"
                                    }
                                    val statusColor = if (isOnlyEmoji) {
                                        if (isRead) primaryColor else onSurfaceVariant.copy(alpha = 0.4f)
                                    } else if (msg.isMe) {
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

