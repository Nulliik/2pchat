package com.example.twopchat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.example.twopchat.media.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerPickerBottomSheet(
    appLanguage: String,
    primaryColor: Color,
    onDismiss: () -> Unit,
    onStickerSelected: (BuiltinSticker) -> Unit,
) {
    val context = LocalContext.current
    val packs by produceState(
        initialValue = StickerSupport.builtinPacks,
        context,
    ) {
        value = withContext(Dispatchers.IO) {
            StickerSupport.availablePacks(context)
        }
    }
    var selectedPackIndex by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    val pack = packs[selectedPackIndex.coerceIn(0, packs.lastIndex)]
    val trimmedQuery = searchQuery.trim()
    val isSearching = trimmedQuery.isNotEmpty()

    val filteredStickers = remember(packs, trimmedQuery, pack) {
        if (!isSearching) {
            pack.stickers
        } else {
            val q = trimmedQuery.lowercase()
            packs.flatMap { it.stickers }.filter { sticker ->
                sticker.emoji.contains(q, ignoreCase = true) ||
                    sticker.stickerId.lowercase().contains(q) ||
                    sticker.packId.lowercase().contains(q)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (appLanguage == "Русский") "Стикеры" else "Stickers",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (isSearching) {
                            if (appLanguage == "Русский") "Найдено: ${filteredStickers.size}" else "Found: ${filteredStickers.size}"
                        } else {
                            pack.title
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(primaryColor.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("☺", fontSize = 22.sp)
                }
            }
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                    context = context,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                placeholder = {
                    Text(
                        if (appLanguage == "Русский") "Поиск стикеров по emoji..." else "Search stickers by emoji...",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                },
                trailingIcon = {
                    if (isSearching) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Text("✕", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primaryColor,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            )
            Spacer(Modifier.height(10.dp))

            if (!isSearching) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(packs, key = { it.id }) { candidate ->
                        val selected = candidate.id == pack.id
                        Text(
                            text = candidate.title,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .background(
                                    if (selected) primaryColor else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(16.dp),
                                )
                                .clickable { selectedPackIndex = packs.indexOf(candidate) }
                                .padding(horizontal = 13.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (isSearching && filteredStickers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            RoundedCornerShape(20.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔍", fontSize = 36.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (appLanguage == "Русский") "Ничего не найдено" else "No stickers found",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredStickers, key = { "${it.packId}_${it.stickerId}" }) { sticker ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (sticker.localFilePath == null) {
                                        Color(sticker.backgroundColor).copy(alpha = 0.75f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    },
                                    RoundedCornerShape(22.dp),
                                )
                                .clickable { onStickerSelected(sticker) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            AnimatedStickerImage(
                                filePath = sticker.localFilePath,
                                fallbackEmoji = sticker.emoji,
                                contentDescription = sticker.emoji.ifBlank { "Sticker" },
                                targetSizePx = 128,
                                modifier = Modifier.size(64.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
