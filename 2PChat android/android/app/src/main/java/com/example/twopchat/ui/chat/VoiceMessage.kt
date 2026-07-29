package com.example.twopchat.ui.chat

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.SliderDefaults
import com.example.twopchat.theme.StealthBlack
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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

    var speedMultiplier by remember { mutableFloatStateOf(1.0f) }

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
        modifier = Modifier.widthIn(min = 230.dp, max = 290.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val playBtnBg = if (isMine) {
            if (primaryColor.luminance() > 0.5f) {
                StealthBlack
            } else {
                Color.White
            }
        } else {
            primaryColor
        }

        val playBtnIconTint = if (isMine) {
            primaryColor
        } else {
            if (primaryColor.luminance() > 0.5f) StealthBlack else Color.White
        }

        IconButton(
            onClick = {
                if (filePath.isNullOrBlank()) return@IconButton
                try {
                    val active = player ?: MediaPlayer().also { created ->
                        created.setDataSource(filePath)
                        created.prepare()
                        duration = created.duration
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && speedMultiplier != 1.0f) {
                            runCatching { created.playbackParams = created.playbackParams.setSpeed(speedMultiplier) }
                        }
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && speedMultiplier != 1.0f) {
                            runCatching { active.playbackParams = active.playbackParams.setSpeed(speedMultiplier) }
                        }
                        active.start()
                        isPlaying = true
                    }
                } catch (_: Exception) {
                    isPlaying = false
                }
            },
            modifier = Modifier
                .size(42.dp)
                .background(playBtnBg, CircleShape),
        ) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.ic_voice_pause else R.drawable.ic_voice_play),
                contentDescription = if (isPlaying) "Pause voice message" else "Play voice message",
                tint = playBtnIconTint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            val themeColor = if (isMine) {
                if (primaryColor.luminance() > 0.5f) StealthBlack else Color.White
            } else {
                primaryColor
            }
            Slider(
                value = position.coerceAtMost(duration).toFloat(),
                onValueChange = { value ->
                    position = value.toInt()
                    runCatching { player?.seekTo(position) }
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                modifier = Modifier.height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = themeColor,
                    activeTrackColor = themeColor,
                    inactiveTrackColor = themeColor.copy(alpha = 0.24f)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = VoiceMessageSupport.formatDuration(if (isPlaying) position else duration),
                    color = contentColor.copy(alpha = 0.72f),
                    fontSize = 10.sp,
                )
                
                // Playback Speed Toggle Chip
                val speedText = when (speedMultiplier) {
                    1.5f -> "1.5x"
                    2.0f -> "2x"
                    else -> "1x"
                }
                Box(
                    modifier = Modifier
                        .background(themeColor.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp))
                        .clickable {
                            val next = when (speedMultiplier) {
                                1.0f -> 1.5f
                                1.5f -> 2.0f
                                else -> 1.0f
                            }
                            speedMultiplier = next
                            runCatching {
                                player?.let { p ->
                                    val wasPlaying = p.isPlaying
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        p.playbackParams = p.playbackParams.setSpeed(next)
                                        if (!wasPlaying) p.pause()
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = speedText,
                        color = themeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
