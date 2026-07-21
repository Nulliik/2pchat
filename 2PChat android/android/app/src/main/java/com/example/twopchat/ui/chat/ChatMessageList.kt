package com.example.twopchat.ui.chat

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.twopchat.data.Localizations
import com.example.twopchat.theme.StealthBlack
import kotlinx.coroutines.launch

@Composable
internal fun ChatMessageList(
    modifier: Modifier = Modifier,
    messages: List<Message>,
    selectedMessages: MutableList<Message>,
    isHistoryLoading: Boolean,
    isSearchMode: Boolean,
    searchQuery: String,
    isSelectMode: Boolean,
    isTyping: Boolean,
    peerName: String,
    myAvatarBitmap: Bitmap?,
    appLanguage: String,
    arrivalAnimationTracker: MessageArrivalAnimationTracker,
    showScrollDownButton: Boolean,
    newMessagesBelowCount: Int,
    onScrollToBottom: () -> Unit,
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
    // Messages List
    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        if (isHistoryLoading) {
            CircularProgressIndicator(
                color = primaryColor,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
        ) {
        if (isSearchMode && searchQuery.isNotEmpty()) {
            item {
                val count = messages.count { msg ->
                    msg.text.contains(searchQuery, ignoreCase = true) ||
                    (msg.attachmentName?.contains(searchQuery, ignoreCase = true) == true)
                }
                Text(
                    text = if (appLanguage == "Русский") "Найдено сообщений: $count" else "Messages found: $count",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(primaryColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    color = primaryColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        val displayMessages = if (isSearchMode && searchQuery.isNotEmpty()) {
            messages.filter { msg ->
                msg.text.contains(searchQuery, ignoreCase = true) ||
                (msg.attachmentName?.contains(searchQuery, ignoreCase = true) == true)
            }
        } else {
            messages.toList()
        }
        itemsIndexed(
            items = displayMessages,
            key = { _, msg -> msg.id }
        ) { index, msg ->
            val previousMessage = displayMessages.getOrNull(index - 1)
            val showDateHeader = remember(
                msg.id,
                msg.sentAtEpochMs,
                previousMessage?.id,
                previousMessage?.sentAtEpochMs,
            ) {
                if (previousMessage == null) {
                    msg.sentAtEpochMs > 0L
                } else {
                    MessageTimestampFormatter.isDifferentDay(previousMessage.sentAtEpochMs, msg.sentAtEpochMs)
                }
            }
            val dateHeaderText = remember(msg.sentAtEpochMs, appLanguage) {
                MessageTimestampFormatter.formatDateHeader(msg.sentAtEpochMs, appLanguage)
            }

            if (showDateHeader && dateHeaderText.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = dateHeaderText,
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            val animateOnAppearance = remember(msg.id) {
                arrivalAnimationTracker.consume(msg.id)
            }
            ChatMessageBubble(
                index = index,
                msg = msg,
                messages = messages,
                selectedMessages = selectedMessages,
                isSelectMode = isSelectMode,
                isTyping = isTyping,
                peerName = peerName,
                myAvatarBitmap = myAvatarBitmap,
                appLanguage = appLanguage,
                animateOnAppearance = animateOnAppearance,
                listState = listState,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onReply = onReply,
                onShowOptions = onShowOptions,
                onOpenImages = onOpenImages,
                onOpenVideo = onOpenVideo,
                highlightedMessageId = highlightedMessageId,
                onHighlightFinished = onHighlightFinished,
            )
        }

        if (isTyping) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(surfaceColor, shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                            .padding(horizontal = 16.dp, vertical = 11.dp)
                    ) {
                        Text(
                            text = Localizations.getString("typing", appLanguage),
                            color = onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
    }

    // Scroll To Bottom Button
    androidx.compose.animation.AnimatedVisibility(
        visible = showScrollDownButton || newMessagesBelowCount > 0,
        enter = scaleIn(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
        exit = scaleOut(animationSpec = tween(200)) + fadeOut(animationSpec = tween(200)),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = 16.dp)
    ) {
        BadgedBox(
            badge = {
                if (newMessagesBelowCount > 0) {
                    Badge(
                        containerColor = primaryColor,
                        contentColor = Color.White,
                    ) {
                        Text(
                            text = if (newMessagesBelowCount > 99) "99+" else newMessagesBelowCount.toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            },
        ) {
            IconButton(
                onClick = {
                    onScrollToBottom()
                    coroutineScope.launch {
                        val lastItemIndex = listState.layoutInfo.totalItemsCount - 1
                        if (lastItemIndex >= 0) {
                            listState.animateScrollToItem(lastItemIndex)
                        }
                    }
                },
                modifier = Modifier
                    .size(38.dp)
                    .background(Color(0xFF1E2226).copy(alpha = 0.76f), CircleShape)
                    .border(width = 0.5.dp, color = Color.White.copy(alpha = 0.08f), shape = CircleShape)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_scroll_down),
                    contentDescription = if (newMessagesBelowCount > 0) {
                        if (appLanguage == "Русский") {
                            "Вниз, новых сообщений: $newMessagesBelowCount"
                        } else {
                            "Scroll down, $newMessagesBelowCount new messages"
                        }
                    } else {
                        if (appLanguage == "Русский") "Вниз" else "Scroll down"
                    },
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
}
