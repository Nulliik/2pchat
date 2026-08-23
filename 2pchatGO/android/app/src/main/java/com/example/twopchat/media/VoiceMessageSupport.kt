package com.example.twopchat.media

object VoiceMessageSupport {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    private val voiceExtensions = setOf("m4a", "aac", "ogg", "opus", "wav", "mp3", "3gp", "amr")
    private val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "3gp", "mov", "flv")

    fun ensureMediaExtension(fileName: String, mime: String): String {
        val safeName = java.io.File(fileName).name.take(120).ifBlank { "file" }
        val currentExtension = safeName.substringAfterLast('.', "").lowercase()
        val alreadyMatchesMediaFamily = when {
            mime.startsWith("image/", ignoreCase = true) -> currentExtension in imageExtensions
            mime.startsWith("video/", ignoreCase = true) -> currentExtension in videoExtensions
            mime.startsWith("audio/", ignoreCase = true) -> currentExtension in voiceExtensions
            else -> currentExtension.isNotBlank()
        }
        if (alreadyMatchesMediaFamily) return safeName
        val extension = when (mime.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "audio/mp4" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/opus" -> "opus"
            else -> null
        }
        return extension?.let { "$safeName.$it" } ?: safeName
    }

    fun attachmentType(fileName: String, mime: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when {
            StickerSupport.isStickerPackFileName(fileName) -> StickerSupport.PACK_ATTACHMENT_TYPE
            StickerSupport.isStickerFileName(fileName) -> StickerSupport.ATTACHMENT_TYPE
            mime.equals("image/gif", ignoreCase = true) || extension == "gif" -> GifStorageManager.ATTACHMENT_TYPE
            mime.startsWith("image/", ignoreCase = true) || extension in imageExtensions -> "IMAGE"
            mime.startsWith("audio/", ignoreCase = true) || extension in voiceExtensions -> "VOICE"
            mime.startsWith("video/", ignoreCase = true) || extension in videoExtensions -> "VIDEO"
            else -> "FILE"
        }
    }

    fun displayMessage(type: String, fileName: String): String = when (type) {
        StickerSupport.PACK_ATTACHMENT_TYPE -> "Sticker pack"
        StickerSupport.ATTACHMENT_TYPE -> "Sticker"
        GifStorageManager.ATTACHMENT_TYPE -> "GIF"
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
