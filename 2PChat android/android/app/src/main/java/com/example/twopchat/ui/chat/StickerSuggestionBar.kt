package com.example.twopchat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.twopchat.BuiltinSticker

@Composable
internal fun StickerSuggestionBar(
    stickers: List<BuiltinSticker>,
    primaryColor: Color,
    surfaceVariant: Color,
    onStickerSelect: (BuiltinSticker) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = stickers.isNotEmpty(),
        enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(20.dp),
                    )
                    .padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(stickers, key = { "${it.packId}_${it.stickerId}" }) { sticker ->
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (sticker.localFilePath == null) {
                                    Color(sticker.backgroundColor).copy(alpha = 0.75f)
                                } else {
                                    surfaceVariant.copy(alpha = 0.65f)
                                },
                            )
                            .clickable { onStickerSelect(sticker) },
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedStickerImage(
                            filePath = sticker.localFilePath,
                            fallbackEmoji = sticker.emoji,
                            contentDescription = sticker.emoji.ifBlank { "Sticker suggestion" },
                            targetSizePx = 120,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                }
            }
        }
    }
}
