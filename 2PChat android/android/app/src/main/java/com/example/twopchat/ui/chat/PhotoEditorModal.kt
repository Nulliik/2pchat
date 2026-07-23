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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
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
    SQUARE("1:1", "1:1", 1.0f),
    FOUR_THREE("4:3", "4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", "16:9", 16f / 9f)
}

@Composable
fun PhotoEditorModal(
    imageUri: Uri?,
    imagePath: String?,
    appLanguage: String,
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
    var strokeColor by remember { mutableStateOf(Color(0xFF4CAF50)) } // Mint green default
    var strokeWidthPx by remember { mutableFloatStateOf(10f) }

    val drawnPaths = remember { mutableStateListOf<DrawPathData>() }
    var currentPathPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var captionText by remember { mutableStateOf("") }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Palette colors
    val paletteColors = listOf(
        Color(0xFF4CAF50), // Mint
        Color(0xFFE53935), // Red
        Color(0xFFFFEB3B), // Yellow
        Color(0xFF2196F3), // Blue
        Color(0xFFFFFFFF), // White
        Color(0xFFFF9800), // Orange
        Color(0xFF9C27B0)  // Purple
    )

    // Current transformed preview bitmap
    val transformedBitmap = remember(originalBitmap, rotationDegrees, selectedAspectRatio) {
        var bmp = originalBitmap

        // Apply rotation if needed
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }

        // Apply aspect ratio crop if selected
        val targetRatio = selectedAspectRatio.ratio
        if (targetRatio != null) {
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
                .background(Color(0xFF0F141A))
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
                            tint = Color.White
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
                                tint = Color.White
                            )
                        }

                        // Toggle Drawing Mode
                        IconButton(onClick = { isDrawingMode = !isDrawingMode }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Draw",
                                tint = if (isDrawingMode) Color(0xFF4CAF50) else Color.White
                            )
                        }

                        // Undo Drawing (removes last drawn path)
                        IconButton(
                            onClick = { if (drawnPaths.isNotEmpty()) drawnPaths.removeAt(drawnPaths.size - 1) },
                            enabled = drawnPaths.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Undo",
                                tint = if (drawnPaths.isNotEmpty()) Color.White else Color.Gray.copy(alpha = 0.4f)
                            )
                        }

                        // Clear Drawing (clears all)
                        IconButton(
                            onClick = { drawnPaths.clear() },
                            enabled = drawnPaths.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear",
                                tint = if (drawnPaths.isNotEmpty()) Color(0xFFFF5252) else Color.Gray.copy(alpha = 0.4f)
                            )
                        }
                    }
                }

                // Aspect Ratio Selector Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AspectRatioOption.values().forEach { option ->
                        val isSelected = selectedAspectRatio == option
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedAspectRatio = option },
                            label = {
                                Text(
                                    text = if (appLanguage == "Русский") option.labelRu else option.labelEn,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF4CAF50),
                                selectedLabelColor = Color.White,
                                containerColor = Color.White.copy(alpha = 0.1f),
                                labelColor = Color.White.copy(alpha = 0.8f)
                            ),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }

                // Image Preview + Interactive Drawing Canvas Container
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

                    // Drawing Overlay Canvas
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(isDrawingMode) {
                                if (!isDrawingMode) return@pointerInput
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
                                val composePath = androidx.compose.ui.graphics.Path().apply {
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
                            val activePath = androidx.compose.ui.graphics.Path().apply {
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
                    color = Color(0xFF161E27),
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
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 14.sp
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White.copy(alpha = 0.07f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                                focusedBorderColor = Color(0xFF4CAF50),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
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
                                    // Composite final bitmap (transformed image + drawn paths)
                                    val finalBmp = Bitmap.createBitmap(
                                        transformedBitmap.width,
                                        transformedBitmap.height,
                                        Bitmap.Config.ARGB_8888
                                    )
                                    val canvas = android.graphics.Canvas(finalBmp)
                                    canvas.drawBitmap(transformedBitmap, 0f, 0f, null)

                                    // Draw paths onto final bitmap (scaled from view container to bitmap size)
                                    if (drawnPaths.isNotEmpty() && containerSize.width > 0 && containerSize.height > 0) {
                                        val scaleX = transformedBitmap.width.toFloat() / containerSize.width.toFloat()
                                        val scaleY = transformedBitmap.height.toFloat() / containerSize.height.toFloat()

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
                                                path.moveTo(pathData.points.first().x * scaleX, pathData.points.first().y * scaleY)
                                                for (i in 1 until pathData.points.size) {
                                                    path.lineTo(pathData.points[i].x * scaleX, pathData.points[i].y * scaleY)
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
                                containerColor = Color(0xFF4CAF50),
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
