package com.example.twopchat.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@UnstableApi
object VideoTranscoder {

    enum class VideoQuality(val targetHeight: Int, val labelKey: String) {
        HD_720P(720, "720p"),
        FULL_HD_1080P(1080, "1080p"),
        ORIGINAL(0, "original");

        fun displayName(appLanguage: String): String = when (this) {
            HD_720P -> when (appLanguage) {
                "Русский" -> "720p (Сжатое)"
                "Deutsch" -> "720p (Komprimiert)"
                "Español" -> "720p (Comprimido)"
                "Français" -> "720p (Compressé)"
                "Português" -> "720p (Comprimido)"
                else -> "720p (Compressed)"
            }
            FULL_HD_1080P -> "1080p (HD)"
            ORIGINAL -> when (appLanguage) {
                "Русский" -> "Оригинал"
                "Deutsch" -> "Original"
                "Español" -> "Original"
                "Français" -> "Original"
                "Português" -> "Original"
                else -> "Original"
            }
        }
    }

    suspend fun compressVideo(
        context: Context,
        inputPath: String,
        quality: VideoQuality,
        onProgress: (Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.Main) {
        val inputFile = File(inputPath)
        if (!inputFile.isFile || inputFile.length() <= 0) {
            return@withContext Result.failure(IllegalArgumentException("Invalid input video file"))
        }

        if (quality == VideoQuality.ORIGINAL) {
            return@withContext Result.success(inputFile)
        }

        val cacheDir = File(context.cacheDir, "transcoded_videos").apply { if (!exists()) mkdirs() }
        val outputFile = File(cacheDir, "transcoded_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.mp4")

        val deferred = CompletableDeferred<File>()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (outputFile.exists() && outputFile.length() > 0) {
                    deferred.complete(outputFile)
                } else {
                    deferred.completeExceptionally(IllegalStateException("Output file is empty or missing"))
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                outputFile.delete()
                deferred.completeExceptionally(exportException)
            }
        }

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(listener)
            .build()

        val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))
        val effects = if (quality.targetHeight > 0) {
            androidx.media3.transformer.Effects(
                listOf(),
                listOf(androidx.media3.effect.Presentation.createForHeight(quality.targetHeight))
            )
        } else {
            androidx.media3.transformer.Effects.EMPTY
        }
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(effects)
            .build()

        try {
            transformer.start(editedMediaItem, outputFile.absolutePath)
        } catch (e: Exception) {
            outputFile.delete()
            return@withContext Result.failure(e)
        }

        val progressHolder = ProgressHolder()
        val progressJob = launch {
            while (isActive && !deferred.isCompleted) {
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress)
                }
                delay(120)
            }
        }

        try {
            val result = deferred.await()
            onProgress(100)
            Result.success(result)
        } catch (e: Exception) {
            outputFile.delete()
            Result.failure(e)
        } finally {
            progressJob.cancel()
        }
    }
}
