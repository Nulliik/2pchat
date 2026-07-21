package com.example.twopchat.ui.onboarding

private val PROFILE_WHITESPACE = Regex("\\s+")

/** Keep the displayed identity and the normalized discovery namespace aligned. */
internal fun normalizeProfileName(value: String): String =
    value.replace(Regex("[\\x00-\\x1F\\x7F]"), "")
        .trim()
        .replace(PROFILE_WHITESPACE, "_")
        .take(32)
