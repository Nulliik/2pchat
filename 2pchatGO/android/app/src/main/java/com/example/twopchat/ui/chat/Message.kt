package com.example.twopchat.ui.chat

import androidx.compose.runtime.Immutable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class Message(
    val id: String,
    val text: String,
    val isMe: Boolean,
    /** Legacy pre-v7 display value, retained only for rows which predate Unix timestamps. */
    val timestamp: String,
    val attachmentType: String? = null,
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToName: String? = null,
    val status: String? = null,
    val reactions: Map<String, List<String>> = emptyMap(),
    val sentAtEpochMs: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val albumMediaUris: List<String> = emptyList(),
    val albumMediaTypes: List<String> = emptyList(),
)

object MessageTimestampFormatter {
    private val headerCache = ConcurrentHashMap<String, String>()

    fun format(
        message: Message,
        language: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val base = if (message.sentAtEpochMs <= 0L) {
            val raw = message.timestamp
            if (raw == "вчера" || raw == "yesterday" || raw == "Yesterday") "" else raw
        } else {
            val locale = if (language == "Русский") Locale.forLanguageTag("ru") else Locale.ENGLISH
            val sent = Calendar.getInstance(timeZone).apply { timeInMillis = message.sentAtEpochMs }
            formatDate("HH:mm", locale, timeZone, sent)
        }
        val isEdited = message.status?.contains("edited") == true
        return if (isEdited) {
            if (base.contains("edited") || base.contains("(ред.)")) base else base + (if (language == "Русский") " (ред.)" else " (edited)")
        } else {
            base
        }
    }

    fun formatDateHeader(
        epochMs: Long,
        language: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        if (epochMs <= 0L) return ""
        val dayBucket = epochMs / 86_400_000L
        val nowDayBucket = nowEpochMs / 86_400_000L
        val cacheKey = "$dayBucket|$nowDayBucket|$language|${timeZone.id}"
        return headerCache.getOrPut(cacheKey) {
            val locale = if (language == "Русский") Locale.forLanguageTag("ru") else Locale.ENGLISH
            val sent = Calendar.getInstance(timeZone).apply { timeInMillis = epochMs }
            val today = Calendar.getInstance(timeZone).apply { timeInMillis = nowEpochMs }
            val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }

            when {
                isSameDate(sent, today) -> if (language == "Русский") "Сегодня" else "Today"
                isSameDate(sent, yesterday) -> if (language == "Русский") "Вчера" else "Yesterday"
                sent.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> formatDate("d MMMM", locale, timeZone, sent)
                else -> formatDate("d MMMM yyyy", locale, timeZone, sent)
            }
        }
    }

    fun isDifferentDay(
        firstEpochMs: Long,
        secondEpochMs: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Boolean {
        if (firstEpochMs <= 0L || secondEpochMs <= 0L) return false
        val cal1 = Calendar.getInstance(timeZone).apply { timeInMillis = firstEpochMs }
        val cal2 = Calendar.getInstance(timeZone).apply { timeInMillis = secondEpochMs }
        return !isSameDate(cal1, cal2)
    }

    private fun isSameDate(left: Calendar, right: Calendar): Boolean =
        left.get(Calendar.ERA) == right.get(Calendar.ERA) &&
            left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
            left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)

    @Synchronized
    private fun formatDate(
        pattern: String,
        locale: Locale,
        timeZone: TimeZone,
        calendar: Calendar,
    ): String {
        val key = "$pattern|${locale.toLanguageTag()}|${timeZone.id}"
        val formatter = formatters.getOrPut(key) {
            SimpleDateFormat(pattern, locale).apply { this.timeZone = timeZone }
        }
        return formatter.format(calendar.time)
    }

    private val formatters = mutableMapOf<String, SimpleDateFormat>()
}
