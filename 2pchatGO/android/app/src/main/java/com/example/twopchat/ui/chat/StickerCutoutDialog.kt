package com.example.twopchat.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.twopchat.media.StickerSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private enum class CutoutTool {
    ERASER,
    RESTORE,
    LASSO,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerCutoutDialog(
    sourceUri: Uri?,
    appLanguage: String,
    primaryColor: Color,
    onDismiss: () -> Unit,
    onSave: (processedBitmap: Bitmap, emoji: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var processing by remember { mutableStateOf(false) }

    var selectedTool by remember { mutableStateOf(CutoutTool.ERASER) }
    var brushRadius by remember { mutableFloatStateOf(24f) }
    var hasWhiteOutline by remember { mutableStateOf(false) }
    var stickerEmoji by remember { mutableStateOf("✨") }

    val lassoPoints = remember { mutableStateListOf<Offset>() }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    var canvasBitmapVersion by remember { mutableStateOf(0) }

    // Load and normalize initial source image
    LaunchedEffect(sourceUri) {
        if (sourceUri == null) {
            onDismiss()
            return@LaunchedEffect
        }
        loading = true
        withContext(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, "cutout_source_${System.nanoTime()}.tmp")
            try {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                val decoded = StickerSupport.decodeStickerBitmap(tempFile)
                if (decoded != null) {
                    val argb = decoded.copy(Bitmap.Config.ARGB_8888, true)
                    if (argb !== decoded) decoded.recycle()
                    originalBitmap = argb.copy(Bitmap.Config.ARGB_8888, false)
                    currentBitmap = argb
                }
            } catch (_: Throwable) {
            } finally {
                tempFile.delete()
            }
        }
        loading = false
    }

    fun applyEraserOrRestoreAt(touchX: Float, touchY: Float, isErase: Boolean) {
        val bmp = currentBitmap ?: return
        val orig = originalBitmap ?: return
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return

        val scaleX = bmp.width.toFloat() / canvasSize.width.toFloat()
        val scaleY = bmp.height.toFloat() / canvasSize.height.toFloat()
        val bmpX = (touchX * scaleX).roundToInt()
        val bmpY = (touchY * scaleY).roundToInt()
        val bmpRadius = (brushRadius * maxOf(scaleX, scaleY)).roundToInt().coerceAtLeast(1)

        val minX = (bmpX - bmpRadius).coerceIn(0, bmp.width - 1)
        val maxX = (bmpX + bmpRadius).coerceIn(0, bmp.width - 1)
        val minY = (bmpY - bmpRadius).coerceIn(0, bmp.height - 1)
        val maxY = (bmpY + bmpRadius).coerceIn(0, bmp.height - 1)

        val rSq = bmpRadius * bmpRadius
        var modified = false
        for (y in minY..maxY) {
            val dy = y - bmpY
            val dySq = dy * dy
            for (x in minX..maxX) {
                val dx = x - bmpX
                if (dx * dx + dySq <= rSq) {
                    val newColor = if (isErase) AndroidColor.TRANSPARENT else orig.getPixel(x, y)
                    if (bmp.getPixel(x, y) != newColor) {
                        bmp.setPixel(x, y, newColor)
                        modified = true
                    }
                }
            }
        }
        if (modified) {
            canvasBitmapVersion++
        }
    }

    fun autoRemoveBackground() {
        val bmp = currentBitmap ?: return
        processing = true
        scope.launch {
            val cut = withContext(Dispatchers.Default) {
                StickerSupport.removeBackgroundAuto(bmp, tolerance = 35)
            }
            currentBitmap = cut
            canvasBitmapVersion++
            processing = false
            Toast.makeText(
                context,
                if (appLanguage == "Русский") "Фон удален" else "Background removed",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun applyLassoCut() {
        val bmp = currentBitmap ?: return
        if (lassoPoints.size < 3 || canvasSize.width <= 0 || canvasSize.height <= 0) {
            lassoPoints.clear()
            return
        }
        processing = true
        scope.launch {
            val scaleX = bmp.width.toFloat() / canvasSize.width.toFloat()
            val scaleY = bmp.height.toFloat() / canvasSize.height.toFloat()
            val pts = lassoPoints.map { PointF(it.x * scaleX, it.y * scaleY) }
            val cut = withContext(Dispatchers.Default) {
                StickerSupport.applyLassoCut(bmp, pts)
            }
            currentBitmap = cut
            lassoPoints.clear()
            canvasBitmapVersion++
            processing = false
        }
    }

    fun resetToOriginal() {
        val orig = originalBitmap ?: return
        currentBitmap = orig.copy(Bitmap.Config.ARGB_8888, true)
        lassoPoints.clear()
        canvasBitmapVersion++
    }

    fun finalizeAndSave() {
        val bmp = currentBitmap ?: return
        processing = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                if (hasWhiteOutline) {
                    StickerSupport.applyStickerOutline(bmp, strokeWidth = 12f)
                } else {
                    bmp
                }
            }
            onSave(result, stickerEmoji.ifBlank { "✨" })
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF121214),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color.White.copy(alpha = 0.1f), CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                        )
                    }

                    Text(
                        text = if (appLanguage == "Русский") "Редактор стикера" else "Sticker Studio",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = ::resetToOriginal,
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color.White.copy(alpha = 0.1f), CircleShape),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset",
                                tint = Color.White,
                            )
                        }

                        Button(
                            onClick = ::finalizeAndSave,
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 14.dp,
                                vertical = 8.dp,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Done",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (appLanguage == "Русский") "Готово" else "Done",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Canvas Area with Checkerboard Background Pattern
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1E1E22))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading || processing) {
                        CircularProgressIndicator(color = primaryColor)
                    } else {
                        val bmp = currentBitmap
                        if (bmp != null) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .onSizeChanged { canvasSize = it }
                                    .pointerInput(selectedTool, brushRadius) {
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                when (selectedTool) {
                                                    CutoutTool.ERASER -> applyEraserOrRestoreAt(
                                                        offset.x,
                                                        offset.y,
                                                        isErase = true,
                                                    )
                                                    CutoutTool.RESTORE -> applyEraserOrRestoreAt(
                                                        offset.x,
                                                        offset.y,
                                                        isErase = false,
                                                    )
                                                    CutoutTool.LASSO -> {
                                                        lassoPoints.clear()
                                                        lassoPoints.add(offset)
                                                    }
                                                }
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                when (selectedTool) {
                                                    CutoutTool.ERASER -> applyEraserOrRestoreAt(
                                                        change.position.x,
                                                        change.position.y,
                                                        isErase = true,
                                                    )
                                                    CutoutTool.RESTORE -> applyEraserOrRestoreAt(
                                                        change.position.x,
                                                        change.position.y,
                                                        isErase = false,
                                                    )
                                                    CutoutTool.LASSO -> lassoPoints.add(change.position)
                                                }
                                            },
                                            onDragEnd = {
                                                if (selectedTool == CutoutTool.LASSO) {
                                                    applyLassoCut()
                                                }
                                            },
                                        )
                                    },
                            ) {
                                // Draw Checkerboard Transparency Pattern
                                val checkerSize = 20f
                                val cols = (size.width / checkerSize).toInt() + 1
                                val rows = (size.height / checkerSize).toInt() + 1
                                for (r in 0 until rows) {
                                    for (c in 0 until cols) {
                                        val color = if ((r + c) % 2 == 0) Color(0xFF2A2A30) else Color(0xFF36363E)
                                        drawRect(
                                            color = color,
                                            topLeft = Offset(c * checkerSize, r * checkerSize),
                                            size = androidx.compose.ui.geometry.Size(checkerSize, checkerSize),
                                        )
                                    }
                                }

                                // Draw Bitmap
                                drawIntoCanvas { canvas ->
                                    val nativeBmp = bmp
                                    if (!nativeBmp.isRecycled) {
                                        val srcRect = android.graphics.Rect(0, 0, nativeBmp.width, nativeBmp.height)
                                        val dstRect = android.graphics.Rect(0, 0, size.width.toInt(), size.height.toInt())
                                        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                                        canvas.nativeCanvas.drawBitmap(nativeBmp, srcRect, dstRect, paint)
                                    }
                                }

                                // Draw Lasso Points path if in Lasso mode
                                if (selectedTool == CutoutTool.LASSO && lassoPoints.size >= 2) {
                                    val path = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(lassoPoints[0].x, lassoPoints[0].y)
                                        for (i in 1 until lassoPoints.size) {
                                            lineTo(lassoPoints[i].x, lassoPoints[i].y)
                                        }
                                    }
                                    drawPath(
                                        path = path,
                                        color = primaryColor,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                                            width = 3.dp.toPx(),
                                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Action Toolbar: Magic Wand, Outline Toggle, Emoji
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = ::autoRemoveBackground,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8B5CF6),
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "🪄 Удалить фон" else "🪄 Magic Cutout",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    FilterChip(
                        selected = hasWhiteOutline,
                        onClick = { hasWhiteOutline = !hasWhiteOutline },
                        label = {
                            Text(
                                text = if (appLanguage == "Русский") "✨ Обводка" else "✨ Outline",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = primaryColor.copy(alpha = 0.25f),
                            selectedLabelColor = primaryColor,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    )

                    OutlinedTextField(
                        value = stickerEmoji,
                        onValueChange = { stickerEmoji = it.take(4) },
                        modifier = Modifier.width(64.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        ),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Brush Tools Mode Selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = selectedTool == CutoutTool.ERASER,
                        onClick = { selectedTool = CutoutTool.ERASER },
                        label = { Text(if (appLanguage == "Русский") "🧽 Ластик" else "🧽 Eraser") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    FilterChip(
                        selected = selectedTool == CutoutTool.RESTORE,
                        onClick = { selectedTool = CutoutTool.RESTORE },
                        label = { Text(if (appLanguage == "Русский") "🖌️ Восстановить" else "🖌️ Restore") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    FilterChip(
                        selected = selectedTool == CutoutTool.LASSO,
                        onClick = { selectedTool = CutoutTool.LASSO },
                        label = { Text(if (appLanguage == "Русский") "✂️ Лассо" else "✂️ Lasso") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    )
                }

                // Brush Radius Slider (when using Eraser / Restore)
                if (selectedTool == CutoutTool.ERASER || selectedTool == CutoutTool.RESTORE) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (appLanguage == "Русский") "Размер:" else "Size:",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = brushRadius,
                            onValueChange = { brushRadius = it },
                            valueRange = 8f..60f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = primaryColor,
                                activeTrackColor = primaryColor,
                            ),
                        )
                    }
                }
            }
        }
    }
}
