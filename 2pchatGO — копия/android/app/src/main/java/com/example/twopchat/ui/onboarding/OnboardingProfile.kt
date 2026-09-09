package com.example.twopchat.ui.onboarding

import com.example.twopchat.config.canonicalNickname

/** Keep the displayed identity and the normalized discovery namespace aligned. */
internal fun normalizeProfileName(value: String): String =
    canonicalNickname(value)
