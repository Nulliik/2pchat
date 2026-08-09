package com.example.twopchat.ui.chat

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.R
import com.example.twopchat.VoiceMessageSupport
import com.example.twopchat.theme.StealthBlack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    fun getMaxAmplitude(): Int {
        return runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
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

private suspend fun extractWaveformSamples(filePath: String?, sampleCount: Int = 28): List<Float> {
    if (filePath.isNullOrBlank()) return List(sampleCount) { 0.35f }
    return withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            return@withContext List(sampleCount) { 0.35f }
        }
        runCatching {
            val bytes = file.readBytes()
            val step = (bytes.size / sampleCount).coerceAtLeast(1)
            val samples = ArrayList<Float>(sampleCount)
            var maxAmp = 1
            for (i in 0 until sampleCount) {
                var sum = 0L
                val start = i * step
                val end = (start + step).coerceAtMost(bytes.size)
                for (j in start until end) {
                    sum += Math.abs(bytes[j].toInt())
                }
                val avg = if (end > start) (sum / (end - start)).toInt() else 1
                maxAmp = maxOf(maxAmp, avg)
                samples.add(avg.toFloat())
            }
            samples.map { raw ->
                val norm = (raw / maxAmp.toFloat()).coerceIn(0f, 1f)
                0.2f + (norm * 0.8f)
            }
        }.getOrElse {
            val seed = filePath.hashCode()
            List(sampleCount) { idx ->
                val v = Math.abs(Math.sin((seed + idx * 17).toDouble())).toFloat()
                0.2f + (v * 0.8f)
            }
        }
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

    var waveformSamples by remember(filePath) { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedEffect(filePath) {
        waveformSamples = extractWaveformSamples(filePath, sampleCount = 28)
    }

    DisposableEffect(filePath) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }

    LaunchedEffect(isPlaying, player) {
        while (isPlaying) {
            position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            kotlinx.coroutines.delay(100)
        }
    }

    Row(
        modifier = Modifier.widthIn(min = 230.dp, max = 290.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val playBtnBg = if (isMine) {
            if (primaryColor.luminance() > 0.5f) StealthBlack else Color.White
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
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            val themeColor = if (isMine) {
                if (primaryColor.luminance() > 0.5f) StealthBlack else Color.White
            } else {
                primaryColor
            }

            val progressRatio = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

            AudioWaveformVisualizer(
                samples = waveformSamples.ifEmpty { List(28) { 0.35f } },
                progress = progressRatio,
                activeColor = themeColor,
                inactiveColor = themeColor.copy(alpha = 0.28f),
                onSeek = { fraction ->
                    val targetPos = (fraction * duration.coerceAtLeast(1)).toInt()
                    position = targetPos
                    runCatching { player?.seekTo(targetPos) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
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
                    fontWeight = FontWeight.Medium
                )

                // Playback Speed Toggle Chip (1x / 1.5x / 2x)
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

@Composable
private fun AudioWaveformVisualizer(
    samples: List<Float>,
    progress: Float,
    activeColor: Color,
    inactiveColor: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek(fraction)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek(fraction)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val totalWidth = maxWidth
        val barCount = samples.size.coerceAtLeast(1)
        val spacing = 2.dp
        val barWidth = ((totalWidth - (spacing * (barCount - 1))) / barCount).coerceAtLeast(2.dp)

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            samples.forEachIndexed { index, sampleHeightRatio ->
                val barFraction = index.toFloat() / barCount.toFloat()
                val isPlayed = barFraction <= progress

                val barColor = if (isPlayed) activeColor else inactiveColor
                val minHeight = 5.dp
                val maxHeight = 24.dp
                val barHeight = minHeight + ((maxHeight - minHeight) * sampleHeightRatio)

                Box(
                    modifier = Modifier
                        .width(barWidth)
                        .height(barHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor)
                )
            }
        }
    }
}
