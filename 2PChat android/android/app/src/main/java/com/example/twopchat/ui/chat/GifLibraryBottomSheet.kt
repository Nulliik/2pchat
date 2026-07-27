package com.example.twopchat.ui.chat

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.StoredGif
import com.example.twopchat.GifStorageManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GifLibraryBottomSheet(
    gifs: List<StoredGif>,
    isLoading: Boolean,
    appLanguage: String,
    primaryColor: Color,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onGifSelected: (StoredGif) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val gridState = rememberLazyGridState()
    val animatedPreviewTargetPx = remember(density) {
        with(density) { 112.dp.roundToPx().coerceIn(192, 384) }
    }
    val animatedIndices by remember(gridState) {
        derivedStateOf {
            if (gridState.isScrollInProgress) {
                emptySet()
            } else {
                val layoutInfo = gridState.layoutInfo
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                layoutInfo.visibleItemsInfo
                    .asSequence()
                    .filter {
                        it.offset.y + it.size.height > layoutInfo.viewportStartOffset &&
                            it.offset.y < layoutInfo.viewportEndOffset
                    }
                    .sortedBy { item ->
                        kotlin.math.abs(item.offset.y + item.size.height / 2 - viewportCenter)
                    }
                    .take(MAX_ANIMATED_GIF_PREVIEWS)
                    .map { it.index }
                    .toSet()
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
                Column {
                    Text(
                        text = if (appLanguage == "Русский") "Мои GIF" else "My GIFs",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (appLanguage == "Русский") {
                            "${gifs.size} сохранено · видимые превью анимируются"
                        } else {
                            "${gifs.size} saved · visible previews animate"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onImport,
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                ) {
                    Text(if (appLanguage == "Русский") "Добавить" else "Add")
                }
            }
            Spacer(Modifier.height(14.dp))
            if (!isLoading && gifs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            RoundedCornerShape(24.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (appLanguage == "Русский") {
                            "Сохранённые GIF появятся здесь"
                        } else {
                            "Saved GIFs will appear here"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(gifs, key = { _, gif -> gif.id }) { index, gif ->
                        val previewPath by produceState(gif.previewPath, gif.id) {
                            if (value.isNullOrBlank() || !java.io.File(value.orEmpty()).isFile) {
                                value = withContext(Dispatchers.IO) {
                                    GifStorageManager.ensurePreview(context, gif)
                                }
                            }
                        }
                        val preview = rememberSampledImage(
                            previewPath,
                            targetWidth = 192,
                            targetHeight = 192,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(112.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable { onGifSelected(gif) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (preview != null) {
                                Image(
                                    bitmap = preview.asImageBitmap(),
                                    contentDescription = "GIF",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().height(112.dp),
                                )
                            } else {
                                Text(
                                    text = "GIF",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                            if (index in animatedIndices) {
                                AnimatedGifImage(
                                    filePath = gif.filePath,
                                    targetMaxDimensionPx = animatedPreviewTargetPx,
                                    contentScale = GifContentScale.CROP,
                                    contentDescription = "Animated GIF preview",
                                    modifier = Modifier.fillMaxWidth().height(112.dp),
                                    loadingLabel = null,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

// GIF frame decoding dominated the sampled CPU profile. Two centered previews
// retain the animated affordance without running a decoder for every grid cell.
private const val MAX_ANIMATED_GIF_PREVIEWS = 2
