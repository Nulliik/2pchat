package com.example.twopchat.ui.chat

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.example.twopchat.R
import com.example.twopchat.VoiceMessageSupport
import java.io.File

data class VoiceRecording(val file: File, val durationMs: Int)

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        cancel()
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(directory, "voice_${System.currentTimeMillis()}.m4a")
        val instance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        return try {
            instance.setAudioSource(MediaRecorder.AudioSource.MIC)
            instance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            instance.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            instance.setAudioEncodingBitRate(64_000)
            instance.setAudioSamplingRate(44_100)
            instance.setOutputFile(file.absolutePath)
            instance.prepare()
            instance.start()
            recorder = instance
            outputFile = file
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (_: Exception) {
            runCatching { instance.release() }
            file.delete()
            false
        }
    }

    fun stop(): VoiceRecording? {
        val instance = recorder ?: return null
        val file = outputFile
        val duration = (SystemClock.elapsedRealtime() - startedAt).toInt()
        recorder = null
        outputFile = null
        return try {
            instance.stop()
            instance.release()
            if (file != null && duration >= 500 && file.length() > 0) {
                VoiceRecording(file, duration)
            } else {
                file?.delete()
                null
            }
        } catch (_: RuntimeException) {
            runCatching { instance.release() }
            file?.delete()
            null
        }
    }

    fun cancel() {
        val instance = recorder
        recorder = null
        runCatching { instance?.stop() }
        runCatching { instance?.release() }
        outputFile?.delete()
        outputFile = null
    }
}

private fun voiceDuration(filePath: String?): Int {
    if (filePath.isNullOrBlank() || !File(filePath).exists()) return 0
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(filePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
    } catch (_: Exception) {
        0
    } finally {
        runCatching { retriever.release() }
    }
}

@Composable
fun VoiceMessagePlayer(
    filePath: String?,
    isMine: Boolean,
    primaryColor: Color,
    contentColor: Color,
) {
    var player by remember(filePath) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(filePath) { mutableStateOf(false) }
    var duration by remember(filePath) { mutableIntStateOf(voiceDuration(filePath)) }
    var position by remember(filePath) { mutableIntStateOf(0) }

    DisposableEffect(filePath) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }
    LaunchedEffect(isPlaying, player) {
        while (isPlaying) {
            position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            kotlinx.coroutines.delay(150)
        }
    }

    Row(
        modifier = Modifier.widthIn(min = 220.dp, max = 280.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                if (filePath.isNullOrBlank()) return@IconButton
                try {
                    val active = player ?: MediaPlayer().also { created ->
                        created.setDataSource(filePath)
                        created.prepare()
                        duration = created.duration
                        created.setOnCompletionListener {
                            isPlaying = false
                            position = 0
                            it.seekTo(0)
                        }
                        player = created
                    }
                    if (active.isPlaying) {
                        active.pause()
                        isPlaying = false
                    } else {
                        active.start()
                        isPlaying = true
                    }
                } catch (_: Exception) {
                    isPlaying = false
                }
            },
            modifier = Modifier
                .size(42.dp)
                .background(if (isMine) Color.White.copy(alpha = 0.2f) else primaryColor.copy(alpha = 0.14f), CircleShape),
        ) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.ic_voice_pause else R.drawable.ic_voice_play),
                contentDescription = if (isPlaying) "Pause voice message" else "Play voice message",
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Slider(
                value = position.coerceAtMost(duration).toFloat(),
                onValueChange = { value ->
                    position = value.toInt()
                    runCatching { player?.seekTo(position) }
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                modifier = Modifier.height(28.dp),
            )
            Text(
                text = VoiceMessageSupport.formatDuration(if (isPlaying) position else duration),
                color = contentColor.copy(alpha = 0.72f),
                fontSize = 10.sp,
            )
        }
    }
}
