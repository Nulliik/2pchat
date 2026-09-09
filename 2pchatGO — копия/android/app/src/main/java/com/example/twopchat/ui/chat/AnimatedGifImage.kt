@file:Suppress("DEPRECATION")

package com.example.twopchat.ui.chat

import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.example.twopchat.media.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val animatedGifDecodeSlots = Semaphore(2)

internal enum class GifContentScale {
    CROP,
    FIT,
}

/**
 * Lifecycle-aware GIF renderer shared by chat bubbles and the full-screen viewer.
 *
 * Platform decoding is resized before frames are allocated, which prevents a large
 * source GIF from being decoded at its original dimensions merely for a small bubble.
 */
@Composable
internal fun AnimatedGifImage(
    filePath: String?,
    targetMaxDimensionPx: Int,
    contentScale: GifContentScale,
    contentDescription: String,
    modifier: Modifier = Modifier,
    isAnimationEnabled: Boolean = true,
    loadingLabel: String? = "GIF…",
) {
    val shouldAnimate = isAnimationEnabled && isAnimatedMediaActive()
    val validatedPath by produceState<String?>(
        initialValue = null,
        filePath,
    ) {
        value = withContext(Dispatchers.IO) {
            filePath?.takeIf { GifStorageManager.validateGif(File(it)) != null }
        }
    }
    val drawable by produceState<Drawable?>(
        initialValue = null,
        validatedPath,
        targetMaxDimensionPx,
    ) {
        val path = validatedPath
        value = if (path != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            withContext(Dispatchers.IO) {
                animatedGifDecodeSlots.withPermit {
                    runCatching {
                        ImageDecoder.decodeDrawable(
                            ImageDecoder.createSource(File(path)),
                        ) { decoder, info, _ ->
                            val width = info.size.width.coerceAtLeast(1)
                            val height = info.size.height.coerceAtLeast(1)
                            val target = targetMaxDimensionPx.coerceAtLeast(1)
                            val scale = minOf(
                                target.toFloat() / width,
                                target.toFloat() / height,
                                1f,
                            )
                            decoder.setTargetSize(
                                (width * scale).toInt().coerceAtLeast(1),
                                (height * scale).toInt().coerceAtLeast(1),
                            )
                        }
                    }.getOrNull()
                }
            }
        } else {
            null
        }
    }
    val movie by produceState<Movie?>(
        initialValue = null,
        validatedPath,
    ) {
        val path = validatedPath
        value = if (path != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            withContext(Dispatchers.IO) {
                animatedGifDecodeSlots.withPermit {
                    @Suppress("DEPRECATION")
                    Movie.decodeFile(path)
                }
            }
        } else {
            null
        }
    }

    DisposableEffect(drawable, shouldAnimate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (drawable as? AnimatedImageDrawable)?.repeatCount =
                AnimatedImageDrawable.REPEAT_INFINITE
        }
        if (shouldAnimate) {
            (drawable as? Animatable)?.start()
        } else {
            (drawable as? Animatable)?.stop()
        }
        onDispose {
            (drawable as? Animatable)?.stop()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (drawable as? AnimatedImageDrawable)?.clearAnimationCallbacks()
            }
        }
    }

    var frameTimeMs by remember(movie) { mutableLongStateOf(0L) }
    LaunchedEffect(movie, shouldAnimate) {
        if (!shouldAnimate) {
            frameTimeMs = 0L
            return@LaunchedEffect
        }
        val startedAt = withFrameMillis { it }
        while (isActive && movie != null) {
            frameTimeMs = withFrameMillis { it } - startedAt
        }
    }

    Box(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        when {
            drawable != null -> AndroidView(
                factory = { context ->
                    android.widget.ImageView(context).apply {
                        scaleType = when (contentScale) {
                            GifContentScale.CROP -> android.widget.ImageView.ScaleType.CENTER_CROP
                            GifContentScale.FIT -> android.widget.ImageView.ScaleType.FIT_CENTER
                        }
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                update = { imageView ->
                    imageView.scaleType = when (contentScale) {
                        GifContentScale.CROP -> android.widget.ImageView.ScaleType.CENTER_CROP
                        GifContentScale.FIT -> android.widget.ImageView.ScaleType.FIT_CENTER
                    }
                    if (imageView.drawable !== drawable) imageView.setImageDrawable(drawable)
                },
                onReset = { imageView ->
                    imageView.setImageDrawable(null)
                },
                onRelease = { imageView ->
                    imageView.setImageDrawable(null)
                },
                modifier = Modifier.fillMaxSize(),
            )
            movie != null -> Canvas(Modifier.fillMaxSize()) {
                val gif = movie ?: return@Canvas
                val width = gif.width().coerceAtLeast(1)
                val height = gif.height().coerceAtLeast(1)
                val duration = gif.duration().takeIf { it > 0 } ?: 1_000
                gif.setTime((frameTimeMs % duration).toInt())
                drawIntoCanvas { composeCanvas ->
                    val scaleX = size.width / width
                    val scaleY = size.height / height
                    val scale = when (contentScale) {
                        GifContentScale.CROP -> maxOf(scaleX, scaleY)
                        GifContentScale.FIT -> minOf(scaleX, scaleY)
                    }
                    val scaledWidth = width * scale
                    val scaledHeight = height * scale
                    val native = composeCanvas.nativeCanvas
                    native.save()
                    native.translate(
                        (size.width - scaledWidth) / 2f,
                        (size.height - scaledHeight) / 2f,
                    )
                    native.scale(scale, scale)
                    gif.draw(native, 0f, 0f)
                    native.restore()
                }
            }
            loadingLabel != null -> Text(
                text = if (filePath.isNullOrBlank()) "GIF" else loadingLabel,
                color = Color.White,
            )
        }
    }
}
