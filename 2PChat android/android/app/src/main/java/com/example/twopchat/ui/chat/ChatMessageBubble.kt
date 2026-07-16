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
) {
    val coroutineScope = rememberCoroutineScope()
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
                                            }
                                        }
                                    } else {
                                        LinkifiedText(
                                            text = msg.text,
                                            textColor = textColor,
                                            linkColor = linkColor,
                                            fontSize = 15.sp,
                                            lineHeight = 20.sp
                                        )
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
                                    LinkifiedText(
                                        text = msg.text,
                                        textColor = textColor,
                                        linkColor = linkColor,
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
