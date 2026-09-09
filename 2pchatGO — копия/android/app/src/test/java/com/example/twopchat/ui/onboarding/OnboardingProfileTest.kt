package com.example.twopchat.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingProfileTest {
    @Test
    fun `normalizes surrounding and repeated whitespace`() {
        assertEquals("Bob_Smith", normalizeProfileName("  Bob\t  Smith  "))
    }

    @Test
    fun `whitespace-only name remains invalid`() {
        assertEquals("", normalizeProfileName(" \n\t "))
    }

    @Test
    fun `limits nickname by Unicode code points without splitting emoji`() {
        val value = "a".repeat(31) + "😀" + "tail"

        assertEquals("a".repeat(31) + "😀", normalizeProfileName(value))
    }

    @Test
    fun `keeps printable special characters and removes invisible controls`() {
        assertEquals(
            "Anne-Marie#2_🦊",
            normalizeProfileName(" Anne-Marie#2\u200B 🦊\u0000 "),
        )
    }
}
