package com.example.twopchat.ui.chat

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

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
)

object MessageTimestampFormatter {
    fun format(
        message: Message,
        language: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val base = if (message.sentAtEpochMs <= 0L) {
            message.timestamp
        } else {
            val locale = if (language == "Русский") Locale.forLanguageTag("ru") else Locale.ENGLISH
            val sent = Calendar.getInstance(timeZone).apply { timeInMillis = message.sentAtEpochMs }
            val today = Calendar.getInstance(timeZone).apply { timeInMillis = nowEpochMs }
            val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            val pattern = when {
                isSameDate(sent, today) -> "HH:mm"
                isSameDate(sent, yesterday) -> return (if (language == "Русский") "вчера" else "yesterday") + (if (message.status?.contains("edited") == true) (if (language == "Русский") " (ред.)" else " (edited)") else "")
                sent.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "d MMM"
                else -> "dd.MM.yyyy"
            }
            formatDate(pattern, locale, timeZone, sent)
        }
        val isEdited = message.status?.contains("edited") == true
        return if (isEdited) {
            base + (if (language == "Русский") " (ред.)" else " (edited)")
        } else {
            base
        }
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
