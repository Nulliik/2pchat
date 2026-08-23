package com.example.twopchat.config

import java.text.Normalizer

internal const val MAX_NICKNAME_CODE_POINTS = 32

/**
 * Canonical profile/discovery name used at every Android boundary.
 *
 * Printable Unicode (including punctuation and emoji) is supported. Whitespace
 * groups become underscores, while control and invisible format characters are
 * removed so the displayed name and tracker namespace cannot drift apart.
 */
internal fun canonicalNickname(value: String, truncate: Boolean = true): String {
    val cleaned = StringBuilder()
    Normalizer.normalize(value, Normalizer.Form.NFC).codePoints().forEach { codePoint ->
        when {
            Character.isWhitespace(codePoint) -> cleaned.append(' ')
            Character.getType(codePoint) == Character.CONTROL.toInt() -> Unit
            Character.getType(codePoint) == Character.FORMAT.toInt() -> Unit
            else -> cleaned.appendCodePoint(codePoint)
        }
    }

    val normalized = cleaned.toString()
        .trim()
        .replace(Regex("\\s+"), "_")
    if (!truncate || normalized.codePointCount(0, normalized.length) <= MAX_NICKNAME_CODE_POINTS) {
        return normalized
    }
    val end = normalized.offsetByCodePoints(0, MAX_NICKNAME_CODE_POINTS)
    return normalized.substring(0, end)
}

/** Reject overlong lookup names instead of silently searching for a truncation. */
internal fun validatedSearchNickname(value: String): String? {
    val normalized = canonicalNickname(value, truncate = false)
    return normalized.takeIf {
        it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_NICKNAME_CODE_POINTS
    }
}

internal fun isValidNickname(value: String): Boolean =
    validatedSearchNickname(value) != null

