package com.example.twopchat.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

fun generateQrCodeBitmap(payload: String, sizePx: Int = 512): Bitmap? {
    if (payload.isBlank() || sizePx <= 0) return null
    return runCatching {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val rowOffset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[rowOffset + x] =
                    if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }.getOrNull()
}

@Composable
fun rememberQrCodeBitmap(payload: String, sizePx: Int = 512): Bitmap? =
    remember(payload, sizePx) { generateQrCodeBitmap(payload, sizePx) }

@Composable
fun QrCodeImage(
    payload: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    rememberQrCodeBitmap(payload)?.let { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}
