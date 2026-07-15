package com.example.twopchat

object VoiceMessageSupport {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    private val voiceExtensions = setOf("m4a", "aac", "ogg", "opus", "wav", "mp3", "3gp", "amr")
    private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "3gp", "mov", "flv")

    fun attachmentType(fileName: String, mime: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/", ignoreCase = true) || extension in imageExtensions -> "IMAGE"
            mime.startsWith("audio/", ignoreCase = true) || extension in voiceExtensions -> "VOICE"
            mime.startsWith("video/", ignoreCase = true) || extension in videoExtensions -> "VIDEO"
            else -> "FILE"
        }
    }

    fun displayMessage(type: String, fileName: String): String = when (type) {
        "IMAGE" -> "Sent an image"
        "VOICE" -> "Voice message"
        "VIDEO" -> "Sent a video"
        else -> "Sent a file: $fileName"
    }

    fun formatDuration(durationMs: Int): String {
        val totalSeconds = durationMs.coerceAtLeast(0) / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
