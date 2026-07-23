package com.example.twopchat.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DrawPathData(
    val points: List<Offset>,
    val color: Color,
    val strokeWidth: Float
)

enum class AspectRatioOption(val labelRu: String, val labelEn: String, val ratio: Float?) {
    ORIGINAL("Оригинал", "Original", null),
    FREEFORM("Свободно", "Freeform", -1f),
    SQUARE("1:1", "1:1", 1.0f),
    FOUR_THREE("4:3", "4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", "16:9", 16f / 9f)
}

private enum class DragHandle { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER }

private fun calculateImageBounds(containerW: Float, containerH: Float, imgW: Float, imgH: Float): Rect {
    if (containerW <= 0f || containerH <= 0f || imgW <= 0f || imgH <= 0f) return Rect(0f, 0f, containerW, containerH)
    val containerRatio = containerW / containerH
    val imgRatio = imgW / imgH

    val displayW: Float
    val displayH: Float
    val displayLeft: Float
    val displayTop: Float

    if (imgRatio > containerRatio) {
        displayW = containerW
        displayH = containerW / imgRatio
        displayLeft = 0f
        displayTop = (containerH - displayH) / 2f
    } else {
        displayH = containerH
        displayW = containerH * imgRatio
        displayLeft = (containerW - displayW) / 2f
        displayTop = 0f
    }
    return Rect(displayLeft, displayTop, displayLeft + displayW, displayTop + displayH)
}

@Composable
fun PhotoEditorModal(
    imageUri: Uri?,
    imagePath: String?,
    appLanguage: String,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    surfaceColor: Color = MaterialTheme.colorScheme.surface,
    onSurfaceColor: Color = MaterialTheme.colorScheme.onSurface,
    onSurfaceVariant: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onDismiss: () -> Unit,
    onSendPhoto: (editedFilePath: String, caption: String) -> Unit
) {
    val context = LocalContext.current

    // Decode source bitmap
    val originalBitmap = remember(imageUri, imagePath) {
        try {
            if (imageUri != null) {
                context.contentResolver.openInputStream(imageUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } else if (!imagePath.isNullOrBlank()) {
                val file = File(imagePath)
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    if (originalBitmap == null) {
        LaunchedEffect(Unit) {
            Toast.makeText(
                context,
                if (appLanguage == "Русский") "Не удалось загрузить изображение" else "Failed to load image",
                Toast.LENGTH_SHORT
            ).show()
            onDismiss()
        }
        return
    }

    // State for rotation, aspect ratio crop, drawing and caption
    var rotationDegrees by remember { mutableIntStateOf(0) }
    var selectedAspectRatio by remember { mutableStateOf(AspectRatioOption.ORIGINAL) }
    var isDrawingMode by remember { mutableStateOf(false) }
    var strokeColor by remember(primaryColor) { mutableStateOf(primaryColor) }
    var strokeWidthPx by remember { mutableFloatStateOf(10f) }

    // Freeform crop rectangle normalized fractions [0f..1f]
    var cropLeft by remember { mutableFloatStateOf(0.05f) }
    var cropTop by remember { mutableFloatStateOf(0.05f) }
    var cropRight by remember { mutableFloatStateOf(0.95f) }
    var cropBottom by remember { mutableFloatStateOf(0.95f) }
    var activeHandle by remember { mutableStateOf(DragHandle.NONE) }

    val drawnPaths = remember { mutableStateListOf<DrawPathData>() }
    var currentPathPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var captionText by remember { mutableStateOf("") }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Palette colors (starts with current active primary theme color)
    val paletteColors = remember(primaryColor) {
        listOf(
            primaryColor,
            Color(0xFFE53935), // Red
            Color(0xFFFFEB3B), // Yellow
            Color(0xFF2196F3), // Blue
            Color(0xFFFFFFFF), // White
            Color(0xFFFF9800), // Orange
            Color(0xFF9C27B0)  // Purple
        )
    }

    // Current transformed preview bitmap (rotation & preset ratio crop)
    val transformedBitmap = remember(originalBitmap, rotationDegrees, selectedAspectRatio) {
        var bmp = originalBitmap

        // Apply rotation if needed
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }

        // Apply preset aspect ratio crop if selected (except FREEFORM which uses custom handles)
        val targetRatio = selectedAspectRatio.ratio
        if (targetRatio != null && targetRatio > 0f) {
            val srcW = bmp.width
            val srcH = bmp.height
            val currentRatio = srcW.toFloat() / srcH.toFloat()

            var cropW = srcW
            var cropH = srcH
            if (currentRatio > targetRatio) {
                cropW = (srcH * targetRatio).toInt().coerceAtMost(srcW)
            } else {
                cropH = (srcW / targetRatio).toInt().coerceAtMost(srcH)
            }
            val startX = (srcW - cropW) / 2
            val startY = (srcH - cropH) / 2
            bmp = Bitmap.createBitmap(bmp, startX, startY, cropW, cropH)
        }

        bmp
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(surfaceColor)
                .safeDrawingPadding()
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = onSurfaceColor
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rotate 90°
                        IconButton(onClick = {
                            rotationDegrees = (rotationDegrees + 90) % 360
                        }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Rotate",
                                tint = onSurfaceColor
                            )
                        }

                        // Toggle Drawing Mode
                        IconButton(onClick = {
                            isDrawingMode = !isDrawingMode
                        }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Draw",
                                tint = if (isDrawingMode) primaryColor else onSurfaceColor
                            )
                        }

                        // Undo Drawing
                        IconButton(
                            onClick = { if (drawnPaths.isNotEmpty()) drawnPaths.removeAt(drawnPaths.size - 1) },
                            enabled = drawnPaths.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Undo",
                                tint = if (drawnPaths.isNotEmpty()) onSurfaceColor else onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }

                        // Clear Drawing
                        IconButton(
                            onClick = { drawnPaths.clear() },
                            enabled = drawnPaths.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear",
                                tint = if (drawnPaths.isNotEmpty()) Color(0xFFFF5252) else onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }

                // Aspect Ratio & Freeform Crop Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AspectRatioOption.values().forEach { option ->
                        val isSelected = selectedAspectRatio == option
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedAspectRatio = option
                                if (option == AspectRatioOption.FREEFORM) {
                                    cropLeft = 0.05f
                                    cropTop = 0.05f
                                    cropRight = 0.95f
                                    cropBottom = 0.95f
                                }
                            },
                            label = {
                                if (option == AspectRatioOption.FREEFORM) {
                                    Icon(
                                        painter = painterResource(id = com.example.twopchat.R.drawable.ic_crop_custom),
                                        contentDescription = "Crop",
                                        tint = if (isSelected) Color.White else onSurfaceColor.copy(alpha = 0.8f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else {
                                    Text(
                                        text = if (appLanguage == "Русский") option.labelRu else option.labelEn,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = primaryColor,
                                selectedLabelColor = Color.White,
                                containerColor = onSurfaceColor.copy(alpha = 0.08f),
                                labelColor = onSurfaceColor.copy(alpha = 0.8f)
                            ),
                            modifier = Modifier.padding(horizontal = 3.dp)
                        )
                    }
                }

                // Image Preview + Drawing + Freeform Crop Overlay Container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp)
                        .onGloballyPositioned { containerSize = it.size },
                    contentAlignment = Alignment.Center
                ) {
                    val imgBitmap = transformedBitmap.asImageBitmap()

                    Image(
                        bitmap = imgBitmap,
                        contentDescription = "Edited Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Drawing Overlay Canvas (only active when drawing mode is enabled)
                    if (isDrawingMode) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            currentPathPoints = listOf(offset)
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            currentPathPoints = currentPathPoints + change.position
                                        },
                                        onDragEnd = {
                                            if (currentPathPoints.isNotEmpty()) {
                                                drawnPaths.add(
                                                    DrawPathData(
                                                        points = currentPathPoints,
                                                        color = strokeColor,
                                                        strokeWidth = strokeWidthPx
                                                    )
                                                )
                                                currentPathPoints = emptyList()
                                            }
                                        },
                                        onDragCancel = {
                                            currentPathPoints = emptyList()
                                        }
                                    )
                                }
                        ) {
                            // Render confirmed paths
                            for (pathData in drawnPaths) {
                                if (pathData.points.size > 1) {
                                    val composePath = Path().apply {
                                        moveTo(pathData.points.first().x, pathData.points.first().y)
                                        for (i in 1 until pathData.points.size) {
                                            lineTo(pathData.points[i].x, pathData.points[i].y)
                                        }
                                    }
                                    drawPath(
                                        path = composePath,
                                        color = pathData.color,
                                        style = Stroke(
                                            width = pathData.strokeWidth,
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        )
                                    )
                                }
                            }

                            // Render active in-flight drag path
                            if (currentPathPoints.size > 1) {
                                val activePath = Path().apply {
                                    moveTo(currentPathPoints.first().x, currentPathPoints.first().y)
                                    for (i in 1 until currentPathPoints.size) {
                                        lineTo(currentPathPoints[i].x, currentPathPoints[i].y)
                                    }
                                }
                                drawPath(
                                    path = activePath,
                                    color = strokeColor,
                                    style = Stroke(
                                        width = strokeWidthPx,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }
                    } else if (drawnPaths.isNotEmpty()) {
                        // Render static drawn paths when drawing mode is off
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            for (pathData in drawnPaths) {
                                if (pathData.points.size > 1) {
                                    val composePath = Path().apply {
                                        moveTo(pathData.points.first().x, pathData.points.first().y)
                                        for (i in 1 until pathData.points.size) {
                                            lineTo(pathData.points[i].x, pathData.points[i].y)
                                        }
                                    }
                                    drawPath(
                                        path = composePath,
                                        color = pathData.color,
                                        style = Stroke(
                                            width = pathData.strokeWidth,
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Freeform Crop Overlay with 4 Corner L-Brackets (when FREEFORM is selected)
                    if (selectedAspectRatio == AspectRatioOption.FREEFORM) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { pos ->
                                            val containerW = containerSize.width.toFloat()
                                            val containerH = containerSize.height.toFloat()
                                            val imgW = transformedBitmap.width.toFloat()
                                            val imgH = transformedBitmap.height.toFloat()

                                            val imgBounds = calculateImageBounds(containerW, containerH, imgW, imgH)

                                            val rectLeft = imgBounds.left + cropLeft * imgBounds.width
                                            val rectTop = imgBounds.top + cropTop * imgBounds.height
                                            val rectRight = imgBounds.left + cropRight * imgBounds.width
                                            val rectBottom = imgBounds.top + cropBottom * imgBounds.height

                                            val hitRadius = 120f
                                            val distTL = (pos - Offset(rectLeft, rectTop)).getDistance()
                                            val distTR = (pos - Offset(rectRight, rectTop)).getDistance()
                                            val distBL = (pos - Offset(rectLeft, rectBottom)).getDistance()
                                            val distBR = (pos - Offset(rectRight, rectBottom)).getDistance()

                                            activeHandle = when {
                                                distTL < hitRadius -> DragHandle.TOP_LEFT
                                                distTR < hitRadius -> DragHandle.TOP_RIGHT
                                                distBL < hitRadius -> DragHandle.BOTTOM_LEFT
                                                distBR < hitRadius -> DragHandle.BOTTOM_RIGHT
                                                pos.x in rectLeft..rectRight && pos.y in rectTop..rectBottom -> DragHandle.CENTER
                                                else -> DragHandle.NONE
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val containerW = containerSize.width.toFloat()
                                            val containerH = containerSize.height.toFloat()
                                            val imgW = transformedBitmap.width.toFloat()
                                            val imgH = transformedBitmap.height.toFloat()
                                            val imgBounds = calculateImageBounds(containerW, containerH, imgW, imgH)

                                            if (imgBounds.width <= 0f || imgBounds.height <= 0f) return@detectDragGestures

                                            val deltaX = dragAmount.x / imgBounds.width
                                            val deltaY = dragAmount.y / imgBounds.height
                                            val minSize = 0.1f

                                            when (activeHandle) {
                                                DragHandle.TOP_LEFT -> {
                                                    cropLeft = (cropLeft + deltaX).coerceIn(0f, cropRight - minSize)
                                                    cropTop = (cropTop + deltaY).coerceIn(0f, cropBottom - minSize)
                                                }
                                                DragHandle.TOP_RIGHT -> {
                                                    cropRight = (cropRight + deltaX).coerceIn(cropLeft + minSize, 1f)
                                                    cropTop = (cropTop + deltaY).coerceIn(0f, cropBottom - minSize)
                                                }
                                                DragHandle.BOTTOM_LEFT -> {
                                                    cropLeft = (cropLeft + deltaX).coerceIn(0f, cropRight - minSize)
                                                    cropBottom = (cropBottom + deltaY).coerceIn(cropTop + minSize, 1f)
                                                }
                                                DragHandle.BOTTOM_RIGHT -> {
                                                    cropRight = (cropRight + deltaX).coerceIn(cropLeft + minSize, 1f)
                                                    cropBottom = (cropBottom + deltaY).coerceIn(cropTop + minSize, 1f)
                                                }
                                                DragHandle.CENTER -> {
                                                    val bw = cropRight - cropLeft
                                                    val bh = cropBottom - cropTop
                                                    var nL = (cropLeft + deltaX).coerceIn(0f, 1f - bw)
                                                    var nT = (cropTop + deltaY).coerceIn(0f, 1f - bh)
                                                    cropLeft = nL
                                                    cropRight = nL + bw
                                                    cropTop = nT
                                                    cropBottom = nT + bh
                                                }
                                                DragHandle.NONE -> {}
                                            }
                                        },
                                        onDragEnd = { activeHandle = DragHandle.NONE },
                                        onDragCancel = { activeHandle = DragHandle.NONE }
                                    )
                                }
                        ) {
                            val containerW = size.width
                            val containerH = size.height
                            val imgW = transformedBitmap.width.toFloat()
                            val imgH = transformedBitmap.height.toFloat()
                            val imgBounds = calculateImageBounds(containerW, containerH, imgW, imgH)

                            val leftPx = imgBounds.left + cropLeft * imgBounds.width
                            val topPx = imgBounds.top + cropTop * imgBounds.height
                            val rightPx = imgBounds.left + cropRight * imgBounds.width
                            val bottomPx = imgBounds.top + cropBottom * imgBounds.height

                            // Dimmed background outside crop box
                            val dimColor = Color.Black.copy(alpha = 0.55f)
                            drawRect(color = dimColor, topLeft = Offset(0f, 0f), size = Size(containerW, topPx))
                            drawRect(color = dimColor, topLeft = Offset(0f, bottomPx), size = Size(containerW, containerH - bottomPx))
                            drawRect(color = dimColor, topLeft = Offset(0f, topPx), size = Size(leftPx, bottomPx - topPx))
                            drawRect(color = dimColor, topLeft = Offset(rightPx, topPx), size = Size(containerW - rightPx, bottomPx - topPx))

                            // Thin white bounding box
                            drawRect(
                                color = Color.White.copy(alpha = 0.8f),
                                topLeft = Offset(leftPx, topPx),
                                size = Size(rightPx - leftPx, bottomPx - topPx),
                                style = Stroke(width = 2f)
                            )

                            // 3x3 Grid Lines inside crop box
                            val boxW = rightPx - leftPx
                            val boxH = bottomPx - topPx
                            val gridColor = Color.White.copy(alpha = 0.25f)
                            drawLine(gridColor, Offset(leftPx + boxW / 3f, topPx), Offset(leftPx + boxW / 3f, bottomPx), strokeWidth = 1f)
                            drawLine(gridColor, Offset(leftPx + 2f * boxW / 3f, topPx), Offset(leftPx + 2f * boxW / 3f, bottomPx), strokeWidth = 1f)
                            drawLine(gridColor, Offset(leftPx, topPx + boxH / 3f), Offset(rightPx, topPx + boxH / 3f), strokeWidth = 1f)
                            drawLine(gridColor, Offset(leftPx, topPx + 2f * boxH / 3f), Offset(rightPx, topPx + 2f * boxH / 3f), strokeWidth = 1f)

                            // 4 Corner L-Brackets (thick white handles matching user screenshot)
                            val cornerLen = 28.dp.toPx()
                            val cornerStroke = 4.dp.toPx()
                            val cornerColor = Color.White

                            // Top-Left corner L
                            drawLine(cornerColor, Offset(leftPx - cornerStroke / 2f, topPx), Offset(leftPx + cornerLen, topPx), strokeWidth = cornerStroke, cap = StrokeCap.Square)
                            drawLine(cornerColor, Offset(leftPx, topPx - cornerStroke / 2f), Offset(leftPx, topPx + cornerLen), strokeWidth = cornerStroke, cap = StrokeCap.Square)

                            // Top-Right corner L
                            drawLine(cornerColor, Offset(rightPx + cornerStroke / 2f, topPx), Offset(rightPx - cornerLen, topPx), strokeWidth = cornerStroke, cap = StrokeCap.Square)
                            drawLine(cornerColor, Offset(rightPx, topPx - cornerStroke / 2f), Offset(rightPx, topPx + cornerLen), strokeWidth = cornerStroke, cap = StrokeCap.Square)

                            // Bottom-Left corner L
                            drawLine(cornerColor, Offset(leftPx - cornerStroke / 2f, bottomPx), Offset(leftPx + cornerLen, bottomPx), strokeWidth = cornerStroke, cap = StrokeCap.Square)
                            drawLine(cornerColor, Offset(leftPx, bottomPx + cornerStroke / 2f), Offset(leftPx, bottomPx - cornerLen), strokeWidth = cornerStroke, cap = StrokeCap.Square)

                            // Bottom-Right corner L
                            drawLine(cornerColor, Offset(rightPx + cornerStroke / 2f, bottomPx), Offset(rightPx - cornerLen, bottomPx), strokeWidth = cornerStroke, cap = StrokeCap.Square)
                            drawLine(cornerColor, Offset(rightPx, bottomPx + cornerStroke / 2f), Offset(rightPx, bottomPx - cornerLen), strokeWidth = cornerStroke, cap = StrokeCap.Square)
                        }
                    }
                }

                // Drawing Palette (visible when drawing mode is active)
                AnimatedVisibility(
                    visible = isDrawingMode,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        paletteColors.forEach { color ->
                            val isSelected = strokeColor == color
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 6.dp)
                                    .size(if (isSelected) 34.dp else 28.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) Color.White else Color.Gray.copy(alpha = 0.5f),
                                        shape = CircleShape
                                    )
                                    .clickable { strokeColor = color }
                            )
                        }
                    }
                }

                // Bottom Caption Bar & Send Button
                Surface(
                    color = surfaceColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = captionText,
                            onValueChange = { captionText = it },
                            placeholder = {
                                Text(
                                    text = if (appLanguage == "Русский") "Добавить подпись..." else "Add a caption...",
                                    color = onSurfaceVariant.copy(alpha = 0.6f),
                                    fontSize = 14.sp
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = onSurfaceColor.copy(alpha = 0.07f),
                                unfocusedContainerColor = onSurfaceColor.copy(alpha = 0.05f),
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = onSurfaceColor.copy(alpha = 0.15f),
                                focusedTextColor = onSurfaceColor,
                                unfocusedTextColor = onSurfaceColor
                            ),
                            shape = RoundedCornerShape(20.dp),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        // Send Button
                        Button(
                            onClick = {
                                try {
                                    var workingBmp = transformedBitmap

                                    // Apply Freeform Crop if FREEFORM option is active
                                    if (selectedAspectRatio == AspectRatioOption.FREEFORM) {
                                        val srcW = workingBmp.width
                                        val srcH = workingBmp.height
                                        val startX = (cropLeft * srcW).toInt().coerceIn(0, srcW - 1)
                                        val startY = (cropTop * srcH).toInt().coerceIn(0, srcH - 1)
                                        val cropW = ((cropRight - cropLeft) * srcW).toInt().coerceIn(1, srcW - startX)
                                        val cropH = ((cropBottom - cropTop) * srcH).toInt().coerceIn(1, srcH - startY)
                                        workingBmp = Bitmap.createBitmap(workingBmp, startX, startY, cropW, cropH)
                                    }

                                    // Composite final bitmap (transformed image + drawn paths)
                                    val finalBmp = Bitmap.createBitmap(
                                        workingBmp.width,
                                        workingBmp.height,
                                        Bitmap.Config.ARGB_8888
                                    )
                                    val canvas = android.graphics.Canvas(finalBmp)
                                    canvas.drawBitmap(workingBmp, 0f, 0f, null)

                                    // Draw paths onto final bitmap (scaled from image display bounds to bitmap size)
                                    if (drawnPaths.isNotEmpty() && containerSize.width > 0 && containerSize.height > 0) {
                                        val imgBounds = calculateImageBounds(
                                            containerSize.width.toFloat(),
                                            containerSize.height.toFloat(),
                                            transformedBitmap.width.toFloat(),
                                            transformedBitmap.height.toFloat()
                                        )

                                        val scaleX = transformedBitmap.width.toFloat() / imgBounds.width.coerceAtLeast(1f)
                                        val scaleY = transformedBitmap.height.toFloat() / imgBounds.height.coerceAtLeast(1f)

                                        val paint = Paint().apply {
                                            isAntiAlias = true
                                            style = Paint.Style.STROKE
                                            strokeCap = Paint.Cap.ROUND
                                            strokeJoin = Paint.Join.ROUND
                                        }

                                        for (pathData in drawnPaths) {
                                            paint.color = pathData.color.toArgb()
                                            paint.strokeWidth = pathData.strokeWidth * scaleX
                                            if (pathData.points.size > 1) {
                                                val path = android.graphics.Path()
                                                val startX = (pathData.points.first().x - imgBounds.left) * scaleX
                                                val startY = (pathData.points.first().y - imgBounds.top) * scaleY
                                                path.moveTo(startX, startY)
                                                for (i in 1 until pathData.points.size) {
                                                    val ptX = (pathData.points[i].x - imgBounds.left) * scaleX
                                                    val ptY = (pathData.points[i].y - imgBounds.top) * scaleY
                                                    path.lineTo(ptX, ptY)
                                                }
                                                canvas.drawPath(path, paint)
                                            }
                                        }
                                    }

                                    // Save to temporary file
                                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                    val editedFile = File(context.cacheDir, "edited_photo_$timeStamp.jpg")
                                    FileOutputStream(editedFile).use { out ->
                                        finalBmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                                    }

                                    onSendPhoto(editedFile.absolutePath, captionText.trim())
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    Toast.makeText(
                                        context,
                                        if (appLanguage == "Русский") "Ошибка при сохранении фото" else "Error saving photo",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryColor,
                                contentColor = Color.White
                            ),
                            shape = CircleShape,
                            contentPadding = PaddingValues(12.dp),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
