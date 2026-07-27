package com.example.twopchat.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalHapticFeedback
import com.example.twopchat.StoredGif
import com.example.twopchat.GifStorageManager
import com.example.twopchat.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
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
    val hapticFeedback = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()
    var previewIndex by remember(gifs) { mutableStateOf<Int?>(null) }
    val animatedPreviewTargetPx = remember(density) {
        with(density) { 112.dp.roundToPx().coerceIn(192, 384) }
    }
    val largePreviewTargetPx = remember(density) {
        with(density) { 320.dp.roundToPx().coerceIn(512, 1024) }
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
        Box(modifier = Modifier.fillMaxWidth()) {
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
                            "${gifs.size} сохранено · удерживайте для просмотра"
                        } else {
                            "${gifs.size} saved · hold to preview"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onImport,
                    modifier = Modifier
                        .size(40.dp)
                        .background(primaryColor, RoundedCornerShape(12.dp)),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_square),
                        contentDescription = if (appLanguage == "Русский") "Добавить GIF" else "Add GIF",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
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
                    columns = GridCells.Fixed(GIF_GRID_COLUMNS),
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
                        var cellWidthPx by remember(gif.id) { mutableIntStateOf(1) }
                        val tileShape = RoundedCornerShape(14.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .onSizeChanged { cellWidthPx = it.width.coerceAtLeast(1) }
                                .clip(tileShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .semantics { contentDescription = "Saved GIF tile" }
                                .clickable { onGifSelected(gif) }
                                .pointerInput(gif.id, gifs.size, cellWidthPx) {
                                    var currentIndex = index
                                    var horizontalTravelPx = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            currentIndex = index
                                            horizontalTravelPx = 0f
                                            previewIndex = index
                                            hapticFeedback.performHapticFeedback(
                                                HapticFeedbackType.LongPress,
                                            )
                                        },
                                        onDragCancel = {
                                            horizontalTravelPx = 0f
                                            previewIndex = null
                                        },
                                        onDragEnd = {
                                            horizontalTravelPx = 0f
                                            previewIndex = null
                                        },
                                    ) { change, dragAmount ->
                                        change.consume()
                                        horizontalTravelPx += dragAmount.x
                                        val switchThreshold =
                                            (cellWidthPx * PREVIEW_SWITCH_FRACTION)
                                                .coerceAtLeast(32.dp.toPx())
                                        while (kotlin.math.abs(horizontalTravelPx) >= switchThreshold) {
                                            val direction = if (horizontalTravelPx > 0f) 1 else -1
                                            val nextIndex =
                                                (currentIndex + direction).coerceIn(0, gifs.lastIndex)
                                            if (nextIndex == currentIndex) {
                                                horizontalTravelPx = 0f
                                                break
                                            }
                                            currentIndex = nextIndex
                                            previewIndex = currentIndex
                                            horizontalTravelPx -= direction * switchThreshold
                                            hapticFeedback.performHapticFeedback(
                                                HapticFeedbackType.LongPress,
                                            )
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (preview != null) {
                                Image(
                                    bitmap = preview.asImageBitmap(),
                                    contentDescription = "GIF",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
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
                            if (previewIndex == null && index in animatedIndices) {
                                AnimatedGifImage(
                                    filePath = gif.filePath,
                                    targetMaxDimensionPx = animatedPreviewTargetPx,
                                    contentScale = GifContentScale.CROP,
                                    contentDescription = "Animated GIF preview",
                                    modifier = Modifier.fillMaxSize(),
                                    loadingLabel = null,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }

            val activePreviewIndex = previewIndex
            val activePreviewGif = activePreviewIndex?.let(gifs::getOrNull)
            if (activePreviewGif != null) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .zIndex(10f)
                        .background(Color.Black.copy(alpha = 0.78f))
                        .semantics { contentDescription = "GIF hold preview" },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) {
                            AnimatedGifImage(
                                filePath = activePreviewGif.filePath,
                                targetMaxDimensionPx = largePreviewTargetPx,
                                contentScale = GifContentScale.FIT,
                                contentDescription = "Selected GIF preview",
                                modifier = Modifier.fillMaxSize(),
                                loadingLabel = null,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "${activePreviewIndex + 1} / ${gifs.size}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (appLanguage == "Русский") {
                                "Ведите влево или вправо, чтобы переключить"
                            } else {
                                "Slide left or right to switch"
                            },
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

// GIF frame decoding dominated the sampled CPU profile. Four centered previews
// retain the animated affordance without running a decoder for every grid cell.
private const val MAX_ANIMATED_GIF_PREVIEWS = 4
private const val GIF_GRID_COLUMNS = 3
private const val PREVIEW_SWITCH_FRACTION = 0.55f
