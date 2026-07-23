package com.example.twopchat.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.P2PMessageRelay

@Composable
internal fun SearchNavigationFabs(
    modifier: Modifier = Modifier,
    onNavigatePrev: () -> Unit,
    onNavigateNext: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(Color(0xFF26262A), CircleShape)
                .clickable { onNavigatePrev() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "Previous match",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(26.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(Color(0xFF26262A), CircleShape)
                .clickable { onNavigateNext() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Next match",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
internal fun SearchBottomBarPill(
    matchCount: Int,
    currentIndex: Int,
    isListView: Boolean,
    appLanguage: String,
    primaryColor: Color,
    onToggleListView: () -> Unit
) {
    val accentColor = primaryColor
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        color = Color(0xFF1C1C1E),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                val text = if (!isListView) {
                    if (matchCount > 0) {
                        "${currentIndex + 1} из $matchCount"
                    } else {
                        if (appLanguage == "Русский") "0 результатов" else "0 results"
                    }
                } else {
                    if (appLanguage == "Русский") "$matchCount результатов" else "$matchCount results"
                }
                Text(
                    text = text,
                    color = accentColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = if (!isListView) {
                    if (appLanguage == "Русский") "Списком" else "List"
                } else {
                    if (appLanguage == "Русский") "В чате" else "In chat"
                },
                color = accentColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onToggleListView() }
            )
        }
    }
}

@Composable
internal fun SearchResultsListViewOverlay(
    modifier: Modifier = Modifier,
    messages: List<Message>,
    matchedIndices: List<Int>,
    peerName: String,
    myAvatarBitmap: Bitmap?,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onSelectMatch: (Int) -> Unit
) {
    val peerAvatar = P2PMessageRelay.peerAvatars[peerName]

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(surfaceColor)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            itemsIndexed(matchedIndices) { matchPointer, messageIndex ->
                val msg = messages.getOrNull(messageIndex) ?: return@itemsIndexed
                val avatarBitmap = if (msg.isMe) myAvatarBitmap else peerAvatar
                val displayName = if (msg.isMe) {
                    if (appLanguage == "Русский") "Вы" else "You"
                } else {
                    peerName
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectMatch(matchPointer) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap.asImageBitmap(),
                            contentDescription = displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(primaryColor.copy(alpha = 0.85f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = displayName.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = displayName,
                                color = onSurfaceColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = MessageTimestampFormatter.format(msg, appLanguage),
                                color = onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = msg.text.ifBlank { msg.attachmentName ?: "Attachment" },
                            color = primaryColor,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (matchPointer < matchedIndices.lastIndex) {
                    HorizontalDivider(
                        color = onSurfaceColor.copy(alpha = 0.06f),
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(start = 64.dp)
                    )
                }
            }
        }
    }
}
