package com.example.twopchat.ui.onboarding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

@Composable
fun ImageCropper(
    imageUri: Uri,
    onCropSuccess: (String) -> Unit,
    onCancel: () -> Unit,
    appLanguage: String
) {
    val context = LocalContext.current
    val sourceBitmap = remember(imageUri) {
        try {
            val inputStream = context.contentResolver.openInputStream(imageUri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            null
        }
    }

    if (sourceBitmap == null) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
            onCancel()
        }
        return
    }

    val imageWidth = sourceBitmap.width.toFloat()
    val imageHeight = sourceBitmap.height.toFloat()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
    ) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()

        // 85% of smaller dimension is the crop circle diameter
        val cropDiameter = min(containerWidth, containerHeight) * 0.85f
        val cropRadiusPx = cropDiameter / 2f

        // Initial scale: image must fully cover the crop circle
        val fitScale = max(cropDiameter / imageWidth, cropDiameter / imageHeight)

        var userScale by remember { mutableStateOf(1.0f) }
        var userOffset by remember { mutableStateOf(Offset.Zero) }

        // Gestures detection
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (userScale * zoom).coerceIn(1.0f, 5.0f)
                        
                        // Constrain pan offset based on new scale
                        val scaledW = imageWidth * fitScale * newScale
                        val scaledH = imageHeight * fitScale * newScale
                        
                        val maxOffsetX = max(0f, scaledW / 2f - cropRadiusPx)
                        val maxOffsetY = max(0f, scaledH / 2f - cropRadiusPx)

                        userScale = newScale
                        userOffset = Offset(
                            (userOffset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                            (userOffset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = sourceBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = fitScale * userScale
                        scaleY = fitScale * userScale
                        translationX = userOffset.x
                        translationY = userOffset.y
                    }
            )
        }

        // Circular grid mask overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = containerWidth / 2f
            val cy = containerHeight / 2f
            val r = cropRadiusPx

            // 1. Semi-transparent black overlay outside the circle
            val backgroundPath = Path().apply {
                addRect(Rect(0f, 0f, containerWidth, containerHeight))
            }
            val circlePath = Path().apply {
                addOval(Rect(cx - r, cy - r, cx + r, cy + r))
            }
            val resultPath = Path.combine(PathOperation.Difference, backgroundPath, circlePath)
            drawPath(resultPath, color = Color.Black.copy(alpha = 0.7f))

            // 2. Circle border
            drawCircle(
                color = Color.White,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx())
            )

            // 3. 3x3 Grid Lines inside the circle
            clipPath(circlePath) {
                val step = (r * 2f) / 3f
                val left = cx - r
                val top = cy - r

                // Vertical lines
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(left + step, top),
                    end = Offset(left + step, top + r * 2f),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(left + step * 2f, top),
                    end = Offset(left + step * 2f, top + r * 2f),
                    strokeWidth = 1.dp.toPx()
                )

                // Horizontal lines
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(left, top + step),
                    end = Offset(left + r * 2f, top + step),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(left, top + step * 2f),
                    end = Offset(left + r * 2f, top + step * 2f),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        // Top control buttons (Header)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onCancel,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
            ) {
                Text("✕", color = Color.White, fontSize = 20.sp)
            }

            TextButton(
                onClick = {
                    // Compose cropped matrix
                    val targetSize = 512
                    val targetBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(targetBitmap)
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        isFilterBitmap = true
                    }

                    val matrix = android.graphics.Matrix()
                    // Fit & center
                    matrix.postTranslate(-imageWidth / 2f, -imageHeight / 2f)
                    matrix.postScale(fitScale, fitScale)
                    matrix.postTranslate(containerWidth / 2f, containerHeight / 2f)

                    // User zoom & pan
                    matrix.postScale(userScale, userScale, containerWidth / 2f, containerHeight / 2f)
                    matrix.postTranslate(userOffset.x, userOffset.y)

                    // Relate to crop square origin
                    val cropLeft = containerWidth / 2f - cropRadiusPx
                    val cropTop = containerHeight / 2f - cropRadiusPx
                    matrix.postTranslate(-cropLeft, -cropTop)

                    // Scale to target size
                    val finalScale = targetSize.toFloat() / (cropRadiusPx * 2f)
                    matrix.postScale(finalScale, finalScale)

                    canvas.drawBitmap(sourceBitmap, matrix, paint)

                    // Save file to internal storage
                    try {
                        val file = File(context.filesDir, "profile_avatar.jpg")
                        val fos = FileOutputStream(file)
                        targetBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                        fos.flush()
                        fos.close()
                        onCropSuccess(file.absolutePath)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(context, "Failed to crop image", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.textButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
            ) {
                Text(
                    text = if (appLanguage == "Русский") "Готово" else "Done",
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        }
    }
}
