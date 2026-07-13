package com.example.twopchat.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingProfileTest {
    @Test
    fun `normalizes surrounding and repeated whitespace`() {
        assertEquals("Bob Smith", normalizeProfileName("  Bob\t  Smith  "))
    }

    @Test
    fun `whitespace-only name remains invalid`() {
        assertEquals("", normalizeProfileName(" \n\t "))
    }
}
