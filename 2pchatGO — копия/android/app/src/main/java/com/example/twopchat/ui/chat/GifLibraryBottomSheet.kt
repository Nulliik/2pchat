package com.example.twopchat.ui.chat

import java.io.File
import com.example.twopchat.media.*
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
import com.example.twopchat.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

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
    var previewGif by remember { mutableStateOf<StoredGif?>(null) }
    var actionsRevealed by remember { mutableStateOf(false) }
    var gridCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
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
                val cellCoordinates = remember(gifs) { mutableMapOf<String, LayoutCoordinates>() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .onGloballyPositioned { gridCoordinates = it }
                        .pointerInput(gifs) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { localOffset ->
                                    val rootGrid = gridCoordinates ?: return@detectDragGesturesAfterLongPress
                                    if (!rootGrid.isAttached) return@detectDragGesturesAfterLongPress
                                    val rootPos = rootGrid.localToRoot(localOffset)
                                    val hitGif = gifs.firstOrNull { gif ->
                                        val coords = cellCoordinates[gif.id] ?: return@firstOrNull false
                                        if (!coords.isAttached) return@firstOrNull false
                                        coords.boundsInRoot().contains(rootPos)
                                    }
                                    if (hitGif != null) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        actionsRevealed = false
                                        previewGif = hitGif
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val rootGrid = gridCoordinates ?: return@detectDragGesturesAfterLongPress
                                    if (!rootGrid.isAttached) return@detectDragGesturesAfterLongPress
                                    val rootPos = rootGrid.localToRoot(change.position)
                                    val hitGif = gifs.firstOrNull { gif ->
                                        val coords = cellCoordinates[gif.id] ?: return@firstOrNull false
                                        if (!coords.isAttached) return@firstOrNull false
                                        coords.boundsInRoot().contains(rootPos)
                                    }
                                    if (hitGif != null && hitGif.id != previewGif?.id) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        previewGif = hitGif
                                    }
                                },
                                onDragEnd = {
                                    if (!actionsRevealed) {
                                        previewGif = null
                                    }
                                },
                                onDragCancel = {
                                    if (!actionsRevealed) {
                                        previewGif = null
                                    }
                                },
                            )
                        },
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(GIF_GRID_COLUMNS),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
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
                            val tileShape = RoundedCornerShape(14.dp)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .onGloballyPositioned { coords ->
                                        cellCoordinates[gif.id] = coords
                                    }
                                    .clip(tileShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .semantics { contentDescription = "Saved GIF tile" }
                                    .clickable { onGifSelected(gif) },
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
                                if (previewGif == null && index in animatedIndices) {
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
            }
            Spacer(Modifier.height(18.dp))
        }

        if (previewGif != null) {
            GifPreviewDialog(
                gif = previewGif,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                initialShowActions = actionsRevealed,
                onActionsRevealed = { actionsRevealed = true },
                onDismiss = {
                    previewGif = null
                    actionsRevealed = false
                },
                onSendGif = {
                    previewGif = null
                    actionsRevealed = false
                    onGifSelected(it)
                    onDismiss()
                },
            )
        }
    }
}
}

// GIF frame decoding dominated the sampled CPU profile. Four centered previews
// retain the animated affordance without running a decoder for every grid cell.
private const val MAX_ANIMATED_GIF_PREVIEWS = 4
private const val GIF_GRID_COLUMNS = 3

