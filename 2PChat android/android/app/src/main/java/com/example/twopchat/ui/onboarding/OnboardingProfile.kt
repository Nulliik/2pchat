package com.example.twopchat.ui.onboarding

private val PROFILE_WHITESPACE = Regex("\\s+")

/** Keep the displayed identity and the normalized discovery namespace aligned. */
internal fun normalizeProfileName(value: String): String =
    value.trim().replace(PROFILE_WHITESPACE, "_")
