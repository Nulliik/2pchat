package com.example.twopchat.ui.chat

import com.example.twopchat.media.*
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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

internal fun incomingMessageAfterFlags(messages: List<Message>): BooleanArray {
    val result = BooleanArray(messages.size)
    var hasIncomingAfter = false
    for (index in messages.lastIndex downTo 0) {
        result[index] = hasIncomingAfter
        if (!messages[index].isMe) hasIncomingAfter = true
    }
    return result
}

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
    onOpenImages: (List<String>, Int, Message?) -> Unit,
    onOpenVideo: (String, Message?) -> Unit,
    onOpenStickerPack: (Message) -> Unit,
    onCancelFileTransfer: (Message) -> Unit,
    onRetryFileTransfer: (Message) -> Unit = {},
    highlightedMessageId: String? = null,
    onHighlightFinished: () -> Unit = {},
    onJumpToMessage: ((Message) -> Unit)? = null,
    onSendGreeting: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val displayMessages = messages
    val currentMessages by rememberUpdatedState(displayMessages)
    val currentOnOpenImages by rememberUpdatedState(onOpenImages)
    val onScrollToReply = remember(listState, coroutineScope) {
        { replyToId: String ->
            val target = currentMessages.indexOfFirst { it.id == replyToId }
            if (target >= 0) {
                coroutineScope.launch { listState.animateScrollToItem(target) }
            }
        }
    }
    val onOpenGifGallery = remember {
        { message: Message ->
            val paths = currentMessages
                .asSequence()
                .filter {
                    it.attachmentType == GifStorageManager.ATTACHMENT_TYPE
                }
                .mapNotNull { it.attachmentUri }
                .filter { java.io.File(it).isFile }
                .distinct()
                .toList()
            val clickedPath = message.attachmentUri
            val clickedIndex = paths.indexOf(clickedPath)
            if (clickedIndex >= 0) {
                currentOnOpenImages(paths, clickedIndex, message)
            } else if (!clickedPath.isNullOrBlank()) {
                currentOnOpenImages(listOf(clickedPath), 0, message)
            }
        }
    }
    val onOpenImageGallery = remember {
        { message: Message ->
            val paths = currentMessages
                .asSequence()
                .filter { it.attachmentType == "IMAGE" }
                .mapNotNull { it.attachmentUri }
                .distinct()
                .toList()
            val clickedPath = message.attachmentUri
            val clickedIndex = paths.indexOf(clickedPath)
            if (clickedIndex >= 0) {
                currentOnOpenImages(paths, clickedIndex, message)
            } else if (!clickedPath.isNullOrBlank()) {
                currentOnOpenImages(listOf(clickedPath), 0, message)
            }
        }
    }
    val selectedIds = remember(selectedMessages.size) {
        selectedMessages.mapTo(HashSet(selectedMessages.size)) { it.id }
    }
    val onSelectionChange = remember(selectedMessages) {
        { message: Message, selected: Boolean ->
            if (selected) {
                if (message.id !in selectedIds) selectedMessages.add(message)
            } else {
                val index = selectedMessages.indexOfFirst { it.id == message.id }
                if (index >= 0) selectedMessages.removeAt(index)
            }
            Unit
        }
    }
    val activeAnimatedGifMessageIds by remember(listState, displayMessages) {
        derivedStateOf {
            if (listState.isScrollInProgress) {
                emptySet()
            } else {
                val layoutInfo = listState.layoutInfo
                val viewportCenter =
                    (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                layoutInfo.visibleItemsInfo
                    .asSequence()
                    .filter { it.index in displayMessages.indices }
                    .filter {
                        displayMessages[it.index].attachmentType ==
                            GifStorageManager.ATTACHMENT_TYPE
                    }
                    .sortedBy {
                        kotlin.math.abs(it.offset + it.size / 2 - viewportCenter)
                    }
                    .take(MAX_ACTIVE_CHAT_GIFS)
                    .map { displayMessages[it.index].id }
                    .toSet()
            }
        }
    }
    // Messages List
    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        if (isHistoryLoading) {
            CircularProgressIndicator(
                color = primaryColor,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (displayMessages.isEmpty() && (!isSearchMode || searchQuery.isBlank())) {
            EmptyChatHeroCard(
                peerName = peerName,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSendGreeting = onSendGreeting,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
            )
        } else if (displayMessages.isEmpty() && isSearchMode && searchQuery.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = surfaceColor.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = Localizations.tr(
                            appLanguage,
                            ru = "Сообщения не найдены",
                            en = "No messages found",
                            de = "Keine Nachrichten gefunden",
                            es = "No se encontraron mensajes",
                            fr = "Aucun message trouvé",
                            pt = "Nenhuma mensagem encontrada",
                            tr = "Mesaj bulunamadı"
                        ),
                        color = onSurfaceColor.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
        } else {
            CompositionLocalProvider(LocalScrollInProgress provides listState.isScrollInProgress) {
                LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
        ) {
        itemsIndexed(
            items = displayMessages,
            key = { _, msg -> msg.id },
            contentType = { _, msg -> msg.attachmentType ?: "text" }
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
            val isPendingOrSending = msg.status?.startsWith("PENDING") == true ||
                msg.status?.startsWith("SENDING") == true ||
                msg.status?.startsWith("FAILED") == true
            val isRead = msg.isMe && !isPendingOrSending && (
                msg.status?.startsWith("READ") == true || peerName == "Saved Messages"
            )
            val isDelivered = msg.isMe && !isPendingOrSending && (
                isRead || msg.status?.startsWith("DELIVERED") == true
            )
            val isSelected = msg.id in selectedIds
            if (isSearchMode && searchQuery.isNotEmpty() && onJumpToMessage != null) {
                Box(modifier = Modifier.fillMaxWidth().clickable { onJumpToMessage(msg) }) {
                    ChatMessageBubble(
                        index = index,
                        msg = msg,
                        isAnimatedMediaEnabled = msg.id in activeAnimatedGifMessageIds,
                        isSelected = isSelected,
                        onSelectionChange = onSelectionChange,
                        isSelectMode = isSelectMode,
                        isRead = isRead,
                        isDelivered = isDelivered,
                        peerName = peerName,
                        myAvatarBitmap = myAvatarBitmap,
                        appLanguage = appLanguage,
                        animateOnAppearance = animateOnAppearance,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurfaceColor = onSurfaceColor,
                        onSurfaceVariant = onSurfaceVariant,
                        onReply = onReply,
                        onShowOptions = onShowOptions,
                        onOpenImages = onOpenImages,
                        onOpenVideo = onOpenVideo,
                        onOpenStickerPack = onOpenStickerPack,
                        onCancelFileTransfer = onCancelFileTransfer,
                        onRetryFileTransfer = onRetryFileTransfer,
                        onScrollToReply = onScrollToReply,
                        onOpenGifGallery = onOpenGifGallery,
                        onOpenImageGallery = onOpenImageGallery,
                        highlightedMessageId = highlightedMessageId,
                        onHighlightFinished = onHighlightFinished,
                    )
                }
            } else {
                ChatMessageBubble(
                    index = index,
                    msg = msg,
                    isAnimatedMediaEnabled = msg.id in activeAnimatedGifMessageIds,
                    isSelected = isSelected,
                    onSelectionChange = onSelectionChange,
                    isSelectMode = isSelectMode,
                    isRead = isRead,
                    isDelivered = isDelivered,
                    peerName = peerName,
                    myAvatarBitmap = myAvatarBitmap,
                    appLanguage = appLanguage,
                    animateOnAppearance = animateOnAppearance,
                    primaryColor = primaryColor,
                    surfaceColor = surfaceColor,
                    onSurfaceColor = onSurfaceColor,
                    onSurfaceVariant = onSurfaceVariant,
                    onReply = onReply,
                    onShowOptions = onShowOptions,
                    onOpenImages = onOpenImages,
                    onOpenVideo = onOpenVideo,
                    onOpenStickerPack = onOpenStickerPack,
                    onCancelFileTransfer = onCancelFileTransfer,
                    onRetryFileTransfer = onRetryFileTransfer,
                    onScrollToReply = onScrollToReply,
                    onOpenGifGallery = onOpenGifGallery,
                    onOpenImageGallery = onOpenImageGallery,
                    highlightedMessageId = highlightedMessageId,
                    onHighlightFinished = onHighlightFinished,
                )
            }
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

private const val MAX_ACTIVE_CHAT_GIFS = 2

@Composable
internal fun EmptyChatHeroCard(
    peerName: String,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSendGreeting: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSavedMessages = peerName == "Saved Messages"
    Surface(
        color = surfaceColor.copy(alpha = 0.88f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.28f)),
        shadowElevation = 10.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            // Shield Emblem
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.24f),
                                primaryColor.copy(alpha = 0.06f)
                            )
                        )
                    )
                    .border(1.5.dp, primaryColor.copy(alpha = 0.45f), CircleShape)
            ) {
                Text(
                    text = if (isSavedMessages) "📑" else "🛡️",
                    fontSize = 30.sp
                )
            }

            Spacer(Modifier.height(14.dp))

            // Title
            Text(
                text = if (isSavedMessages) {
                    Localizations.tr(
                        appLanguage,
                        ru = "Избранное",
                        en = "Saved Messages",
                        de = "Gespeicherte Nachrichten",
                        es = "Mensajes guardados",
                        fr = "Messages enregistrés",
                        pt = "Mensagens salvas",
                        tr = "Kayıtlı Mesajlar"
                    )
                } else {
                    Localizations.tr(
                        appLanguage,
                        ru = "Сквозное P2P-шифрование",
                        en = "End-to-End P2P Encryption",
                        de = "Ende-zu-Ende P2P-Verschlüsselung",
                        es = "Cifrado P2P de extremo a extremo",
                        fr = "Chiffrement P2P de bout en bout",
                        pt = "Criptografia P2P ponta a ponta",
                        tr = "Uçtan Uca P2P Şifreleme"
                    )
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = onSurfaceColor,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            // Description
            Text(
                text = if (isSavedMessages) {
                    Localizations.tr(
                        appLanguage,
                        ru = "Ваше личное зашифрованное хранилище. Сохраняйте сюда заметки, ссылки и файлы.",
                        en = "Your private encrypted storage. Save notes, links and media files here.",
                        de = "Ihr privater verschlüsselter Speicher. Speichern Sie hier Notizen, Links und Medien.",
                        es = "Su almacenamiento privado cifrado. Guarde notas, enlaces y archivos aquí.",
                        fr = "Votre espace de stockage privé chiffré. Enregistrez vos notes et fichiers ici.",
                        pt = "Seu armazenamento criptografado privado. Salve notas, links e mídias aqui.",
                        tr = "Özel şifreli depolama alanınız. Notları, bağlantıları ve medyayı buraya kaydedin."
                    )
                } else {
                    Localizations.tr(
                        appLanguage,
                        ru = "Сообщения передаются напрямую между вашими устройствами по Double Ratchet. Никаких промежуточных серверов.",
                        en = "Messages are exchanged directly between your devices via Double Ratchet. No intermediate servers.",
                        de = "Nachrichten werden direkt zwischen Ihren Geräten über Double Ratchet ausgetauscht. Keine Zwischenserver.",
                        es = "Los mensajes se intercambian directamente entre sus dispositivos mediante Double Ratchet. Sin servidores intermediarios.",
                        fr = "Les messages sont échangés directement entre vos appareils via Double Ratchet. Aucun serveur intermédiaire.",
                        pt = "As mensagens são trocadas diretamente entre seus dispositivos via Double Ratchet. Sem servidores intermediários.",
                        tr = "Mesajlar cihazlarınız arasında doğrudan Double Ratchet ile iletilir. Ara sunucu yoktur."
                    )
                },
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = onSurfaceColor.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(Modifier.height(14.dp))

            // Micro Trust Chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    Text(
                        text = "🔒 Double Ratchet",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
                Surface(
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    Text(
                        text = "⚡ Direct P2P",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF34D399),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
                Surface(
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    Text(
                        text = "🛡️ Zero Cloud",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF60A5FA),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            if (!isSavedMessages) {
                Spacer(Modifier.height(18.dp))

                // Fast Action Greeting Button
                Button(
                    onClick = onSendGreeting,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor.copy(alpha = 0.18f),
                        contentColor = primaryColor
                    ),
                    border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(
                        text = "👋",
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = Localizations.tr(
                            appLanguage,
                            ru = "Помахать рукой",
                            en = "Say Hello",
                            de = "Hallo sagen",
                            es = "Saludar",
                            fr = "Dire bonjour",
                            pt = "Acenar",
                            tr = "Selam ver"
                        ),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor
                    )
                }
            }
        }
    }
}

