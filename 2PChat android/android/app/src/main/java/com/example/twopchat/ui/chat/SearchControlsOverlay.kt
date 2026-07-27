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

import java.util.Calendar
import java.util.TimeZone
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items

enum class SearchCategoryFilter {
    ALL,
    MEDIA,
    FILES,
    LINKS,
}

internal fun Message.matchesCategoryFilter(category: SearchCategoryFilter): Boolean {
    return when (category) {
        SearchCategoryFilter.ALL -> true
        SearchCategoryFilter.MEDIA -> {
            val type = attachmentType?.uppercase() ?: ""
            type == "IMAGE" || type == "VIDEO" || type == "GIF" || type == "STICKER" || albumMediaUris.isNotEmpty()
        }
        SearchCategoryFilter.FILES -> {
            val type = attachmentType?.uppercase() ?: ""
            attachmentType != null && type != "IMAGE" && type != "VIDEO" && type != "GIF" && type != "STICKER" && albumMediaUris.isEmpty()
        }
        SearchCategoryFilter.LINKS -> {
            text.contains("http://", ignoreCase = true) || text.contains("https://", ignoreCase = true)
        }
    }
}

internal fun Message.matchesDateFilter(dateMs: Long?, timeZone: TimeZone = TimeZone.getDefault()): Boolean {
    if (dateMs == null || dateMs <= 0L) return true
    if (sentAtEpochMs <= 0L) return false
    val cal1 = Calendar.getInstance(timeZone).apply { timeInMillis = sentAtEpochMs }
    val cal2 = Calendar.getInstance(timeZone).apply { timeInMillis = dateMs }
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
           cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

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
    selectedCategory: SearchCategoryFilter = SearchCategoryFilter.ALL,
    selectedDateMs: Long? = null,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onToggleListView: () -> Unit,
    onSelectCategory: (SearchCategoryFilter) -> Unit = {},
    onPickDate: () -> Unit = {},
    onClearDate: () -> Unit = {},
) {
    val accentColor = primaryColor
    val isRu = appLanguage == "Русский"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        color = surfaceColor,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            // Category & Date Filter Bar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Calendar Date Chip
                item {
                    val dateText = if (selectedDateMs != null) {
                        val cal = Calendar.getInstance().apply { timeInMillis = selectedDateMs }
                        String.format("%02d.%02d.%04d ✕", cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR))
                    } else {
                        if (isRu) "Дата" else "Date"
                    }
                    val isDateActive = selectedDateMs != null
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isDateActive) accentColor else accentColor.copy(alpha = 0.12f),
                        modifier = Modifier.clickable {
                            if (isDateActive) onClearDate() else onPickDate()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = com.example.twopchat.R.drawable.ic_calendar),
                                contentDescription = "Calendar",
                                tint = if (isDateActive) Color.White else accentColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = dateText,
                                color = if (isDateActive) Color.White else accentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Category Chips
                items(SearchCategoryFilter.values()) { category ->
                    val isSelected = selectedCategory == category
                    val chipTitle = when (category) {
                        SearchCategoryFilter.ALL -> if (isRu) "Все" else "All"
                        SearchCategoryFilter.MEDIA -> if (isRu) "Медиа" else "Media"
                        SearchCategoryFilter.FILES -> if (isRu) "Файлы" else "Files"
                        SearchCategoryFilter.LINKS -> if (isRu) "Ссылки" else "Links"
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) accentColor else onSurfaceColor.copy(alpha = 0.08f),
                        modifier = Modifier.clickable { onSelectCategory(category) }
                    ) {
                        Text(
                            text = chipTitle,
                            color = if (isSelected) Color.White else onSurfaceColor,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // Status count & Toggle view row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    val text = if (!isListView) {
                        if (matchCount > 0) {
                            "${currentIndex + 1} из $matchCount"
                        } else {
                            if (isRu) "0 результатов" else "0 results"
                        }
                    } else {
                        if (isRu) "$matchCount результатов" else "$matchCount results"
                    }
                    Text(
                        text = text,
                        color = accentColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = if (!isListView) {
                        if (isRu) "Списком" else "List"
                    } else {
                        if (isRu) "В чате" else "In chat"
                    },
                    color = accentColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onToggleListView() }
                )
            }
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
